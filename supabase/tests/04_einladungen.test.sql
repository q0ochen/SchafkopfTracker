-- pgTAP: Einladungen – create_invite (Token-Format, nur der Hash wird gespeichert, 14 Tage, Aufräumen,
-- höchstens 20 gültige je Runde), invite_info, join_round (Beitritt, schon Mitglied, 12 Mitglieder je Runde,
-- 5 Runden je Konto, abgelaufen) und das Wiederverknüpfen des eigenen Spielers nach Verlassen und Beitritt.
begin;
select plan(62);

-- ---------------------------------------------------------------------------------------------------
-- Testumgebung (in jeder Testdatei gleich; alles liegt in pg_temp und verschwindet mit dem ROLLBACK)
-- ---------------------------------------------------------------------------------------------------
create function pg_temp.id(kurzname text) returns uuid language sql immutable as $$
  select case kurzname
    when 'anna'  then 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    when 'ben'   then 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    when 'clara' then 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
    when 'dora'  then 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
    when 'emil'  then 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
    when 'fritz' then 'ffffffff-ffff-4fff-8fff-ffffffffffff'
  end::uuid
$$;
-- Wechselt wie PostgREST die Rolle und setzt die JWT-Claims, die auth.uid() liest.
create function pg_temp.als(kurzname text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', pg_temp.id(kurzname), 'role', 'authenticated')::text, true);
  set local role authenticated;
end $$;
create function pg_temp.als_gast() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '{"role":"anon"}', true);
  set local role anon;
end $$;
create function pg_temp.als_server() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
  set local role service_role;
end $$;
create function pg_temp.als_postgres() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '', true);
  reset role;
end $$;
-- Legt ein Konto an (wie der Auth-Server nach der Google-Anmeldung).
create function pg_temp.nutzer(kurzname text) returns uuid language sql as $$
  insert into auth.users (id, aud, role, email)
  values (pg_temp.id(kurzname), 'authenticated', 'authenticated', kurzname || '@example.org')
  returning id
$$;
-- Merkzettel für Kennungen und Tokens, die erst im Test entstehen (lesbar und beschreibbar in jeder Rolle).
create temp table merkzettel (stichwort text primary key, wert text not null);
grant all on merkzettel to public;
create function pg_temp.merke(was text, inhalt text) returns void language sql as $$
  insert into pg_temp.merkzettel as z (stichwort, wert) values (was, inhalt)
  on conflict (stichwort) do update set wert = excluded.wert
$$;
create function pg_temp.gemerkt(was text) returns text language sql stable as $$
  select z.wert from pg_temp.merkzettel z where z.stichwort = was
$$;
create function pg_temp.runde(was text) returns uuid language sql stable as $$
  select pg_temp.gemerkt(was)::uuid
$$;
-- Liest an RLS vorbei (läuft als Eigentümer), auch während ein Nutzer angemeldet ist.
create function pg_temp.rev(was text) returns bigint language sql stable security definer as $$
  select r.rev from public.rounds r where r.id = pg_temp.runde(was)
$$;
grant execute on all functions in schema pg_temp to public;

-- Beliebig viele weitere Konten (für die Obergrenze von 12 Mitgliedern).
create function pg_temp.konto(nummer int) returns uuid language sql immutable as $$
  select ('00000000-0000-4000-8000-' || lpad(nummer::text, 12, '0'))::uuid
$$;
create function pg_temp.als_konto(nummer int) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims',
    json_build_object('sub', pg_temp.konto(nummer), 'role', 'authenticated')::text, true);
  set local role authenticated;
end $$;
-- Einladungen kann kein Client lesen; die Tests schauen als Eigentümer nach.
create function pg_temp.einladungen(was text) returns int language sql stable security definer as $$
  select count(*)::int from public.round_invites i where i.round_id = pg_temp.runde(was)
$$;
grant execute on all functions in schema pg_temp to public;

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('clara');
select pg_temp.nutzer('dora');
select pg_temp.nutzer('emil');
insert into auth.users (id, aud, role, email)
select pg_temp.konto(n), 'authenticated', 'authenticated', 'konto' || n || '@example.org'
from generate_series(1, 9) n;

select pg_temp.als('anna');
select pg_temp.merke('r', public.create_round('Stammtisch')::text);
select pg_temp.als('emil');
select pg_temp.merke('fremd', public.create_round('Fremde Runde')::text);

-- ---------------------------------------------------------------------------------------------------
-- create_invite
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('token', public.create_invite(pg_temp.runde('r')));
select matches(pg_temp.gemerkt('token'), '^[0-9a-f]{32}$', 'create_invite: Token aus 32 Zeichen 0-9a-f');
select pg_temp.merke('token2', public.create_invite(pg_temp.runde('r')));
select isnt(pg_temp.gemerkt('token2'), pg_temp.gemerkt('token'), 'create_invite: Jeder Aufruf liefert einen neuen Token');
select is(pg_temp.rev('r'), 1::bigint, 'create_invite ändert rev nicht');

select pg_temp.als_postgres();
select results_eq(
  $$ select round_id, created_by, created_at, expires_at from public.round_invites
      where token_hash = encode(sha256(convert_to(pg_temp.gemerkt('token'), 'UTF8')), 'hex') $$,
  $$ values (pg_temp.runde('r'), pg_temp.id('anna'), now(), now() + interval '14 days') $$,
  'create_invite: gespeichert wird hex(sha256(token)), gültig ab jetzt 14 Tage'
);
select is(
  (select count(*)::int from public.round_invites i
    where i.token_hash in (pg_temp.gemerkt('token'), pg_temp.gemerkt('token2'))
       or i::text like '%' || pg_temp.gemerkt('token') || '%'),
  0,
  'create_invite: Der Token selbst steht nirgends in der Tabelle'
);

select pg_temp.als('ben');
select throws_ok(format($$ select public.create_invite(%L) $$, pg_temp.runde('r')), 'P0001', 'not_member',
  'create_invite: Nur Mitglieder dürfen einladen (not_member)');
select throws_ok($$ select public.create_invite('00000000-0000-4000-8000-000000000000') $$, 'P0001', 'not_member',
  'create_invite: unbekannte Runde → not_member');
select throws_ok($$ select public.create_invite(null) $$, 'P0001', 'not_member', 'create_invite: NULL → not_member');
select is(pg_temp.einladungen('r'), 2, 'Abgewiesene Aufrufe legen keine Einladung an');

-- ---------------------------------------------------------------------------------------------------
-- invite_info
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select results_eq(
  format($$ select round_id, round_name, member_count, already_member from public.invite_info(%L) $$, pg_temp.gemerkt('token')),
  $$ values (pg_temp.runde('r'), 'Stammtisch'::text, 1, false) $$,
  'invite_info: Runde, Name und Zahl der Mitglieder – für Ben, der noch nicht dabei ist'
);
select results_eq(
  format($$ select round_id from public.invite_info(%L) $$, '  ' || upper(pg_temp.gemerkt('token')) || E' \n'),
  $$ values (pg_temp.runde('r')) $$,
  'invite_info: Leerzeichen außen und Großbuchstaben werden verziehen'
);
select pg_temp.als('anna');
select results_eq(
  format($$ select member_count, already_member from public.invite_info(%L) $$, pg_temp.gemerkt('token')),
  $$ values (1, true) $$,
  'invite_info: Anna ist schon Mitglied'
);
select pg_temp.als('ben');
select throws_ok($$ select * from public.invite_info('0123456789abcdef0123456789abcdef') $$, 'P0001', 'invalid_invite',
  'invite_info: unbekannter Token → invalid_invite');
select throws_ok($$ select * from public.invite_info('') $$, 'P0001', 'invalid_invite', 'invite_info: leer → invalid_invite');
select throws_ok($$ select * from public.invite_info(null) $$, 'P0001', 'invalid_invite', 'invite_info: NULL → invalid_invite');
select throws_ok(format($$ select * from public.invite_info(%L) $$, left(pg_temp.gemerkt('token'), 31)),
  'P0001', 'invalid_invite', 'invite_info: 31 Zeichen → invalid_invite');
select throws_ok(format($$ select * from public.invite_info(%L) $$, pg_temp.gemerkt('token') || '0'),
  'P0001', 'invalid_invite', 'invite_info: 33 Zeichen → invalid_invite');
select throws_ok($$ select * from public.invite_info('zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz') $$, 'P0001', 'invalid_invite',
  'invite_info: andere Zeichen als 0-9a-f → invalid_invite');
select throws_ok(
  format($$ select * from public.invite_info(%L) $$, encode(sha256(convert_to(pg_temp.gemerkt('token'), 'UTF8')), 'hex')),
  'P0001', 'invalid_invite', 'invite_info: Der gespeicherte Hash ist kein Token');
select is(pg_temp.rev('r'), 1::bigint, 'invite_info liest nur: rev ist unverändert');

-- ---------------------------------------------------------------------------------------------------
-- join_round
-- ---------------------------------------------------------------------------------------------------
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'), 'join_round: Ben tritt bei und bekommt die Kennung der Runde');
select is(pg_temp.rev('r'), 2::bigint, 'join_round erhöht rev (die Mitglieder haben sich geändert)');
select results_eq(
  $$ select user_id, player_id from public.round_members order by joined_at, user_id $$,
  $$ values (pg_temp.id('anna'), null::text), (pg_temp.id('ben'), null::text) $$,
  'join_round: Ben ist Mitglied (noch ohne Spieler) und liest jetzt die Runde'
);
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'), 'join_round: schon Mitglied → kein Fehler, wieder die Kennung');
select is(pg_temp.rev('r'), 2::bigint, 'join_round: schon Mitglied → nichts weiter, rev bleibt');
select is((select count(*)::int from public.round_members), 2, 'join_round: schon Mitglied → keine zweite Mitgliedschaft');
select pg_temp.als('clara');
select is(public.join_round(upper(pg_temp.gemerkt('token2'))), pg_temp.runde('r'),
  'join_round: Eine Einladung gilt für mehrere Personen; auch der zweite Token führt in die Runde');
select results_eq(
  format($$ select member_count, already_member from public.invite_info(%L) $$, pg_temp.gemerkt('token')),
  $$ values (3, true) $$,
  'invite_info zählt die Mitglieder mit'
);

select pg_temp.als('dora');
select throws_ok($$ select public.join_round('0123456789abcdef0123456789abcdef') $$, 'P0001', 'invalid_invite',
  'join_round: unbekannter Token → invalid_invite');
select throws_ok($$ select public.join_round(null) $$, 'P0001', 'invalid_invite', 'join_round: NULL → invalid_invite');
select throws_ok($$ select public.join_round('') $$, 'P0001', 'invalid_invite', 'join_round: leer → invalid_invite');

-- ---------------------------------------------------------------------------------------------------
-- Ablauf nach 14 Tagen, Aufräumen, höchstens 20 gültige Einladungen je Runde
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
update public.round_invites
   set created_at = now() - interval '14 days', expires_at = now()
 where token_hash = encode(sha256(convert_to(pg_temp.gemerkt('token'), 'UTF8')), 'hex');
select pg_temp.als('dora');
select throws_ok(format($$ select * from public.invite_info(%L) $$, pg_temp.gemerkt('token')), 'P0001', 'invalid_invite',
  'invite_info: Nach 14 Tagen ist die Einladung abgelaufen');
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token')), 'P0001', 'invalid_invite',
  'join_round: abgelaufene Einladung → invalid_invite');
select pg_temp.als('ben');
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token')), 'P0001', 'invalid_invite',
  'join_round: abgelaufene Einladung → invalid_invite, auch für ein Mitglied');
select is((select count(*)::int from public.round_members), 3, 'Abgelaufene Einladung: kein neues Mitglied');

-- Emils fremde Runde hat ebenfalls eine abgelaufene Einladung; sie bleibt beim Aufräumen von Annas Runde liegen.
select pg_temp.als('emil');
select public.create_invite(pg_temp.runde('fremd')) is not null as einladung_fremd;
select pg_temp.als_postgres();
update public.round_invites set created_at = now() - interval '20 days', expires_at = now() - interval '6 days'
 where round_id = pg_temp.runde('fremd');

select pg_temp.als('ben');
select is(pg_temp.einladungen('r'), 2, 'Vor dem Aufräumen: eine gültige und eine abgelaufene Einladung');
select lives_ok(
  format($$ select public.create_invite(%L) from generate_series(1, 19) $$, pg_temp.runde('r')),
  'create_invite: 19 weitere Einladungen (jedes Mitglied darf einladen)'
);
select is(pg_temp.einladungen('r'), 20, 'create_invite löscht dabei die abgelaufene Einladung der Runde: 1 + 19 = 20');
select is(pg_temp.einladungen('fremd'), 1, 'Abgelaufene Einladungen ANDERER Runden bleiben unberührt');
select throws_ok(format($$ select public.create_invite(%L) $$, pg_temp.runde('r')), 'P0001', 'limit_reached',
  'create_invite: die 21. gültige Einladung → limit_reached');
select pg_temp.als_postgres();
update public.round_invites set created_at = now() - interval '15 days', expires_at = now() - interval '1 day'
 where token_hash = encode(sha256(convert_to(pg_temp.gemerkt('token2'), 'UTF8')), 'hex');
select pg_temp.als('anna');
select lives_ok(format($$ select public.create_invite(%L) $$, pg_temp.runde('r')),
  'create_invite: Abgelaufene Einladungen zählen nicht mit');
select is(pg_temp.einladungen('r'), 20, 'Wieder genau 20 gültige Einladungen');

-- ---------------------------------------------------------------------------------------------------
-- Höchstens 5 Runden je Konto (auch beim Beitreten)
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
delete from public.round_invites where round_id = pg_temp.runde('r');
select pg_temp.als('anna');
select pg_temp.merke('token', public.create_invite(pg_temp.runde('r')));
select pg_temp.als('dora');
select pg_temp.merke('dora-' || n, public.create_round('Dora ' || n)::text) from generate_series(1, 5) n;
select results_eq(
  format($$ select member_count, already_member from public.invite_info(%L) $$, pg_temp.gemerkt('token')),
  $$ values (3, false) $$,
  'Annas Runde hat erst drei Mitglieder – an ihr liegt es also nicht'
);
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token')), 'P0001', 'limit_reached',
  'join_round: Mit 5 Runden ist das Konto voll → limit_reached');
select is(pg_temp.rev('r'), 3::bigint, 'Der abgewiesene Beitritt hat rev nicht erhöht');
select public.leave_round(pg_temp.runde('dora-5'));
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'),
  'join_round: Nach dem Verlassen einer eigenen Runde klappt der Beitritt');
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'),
  'join_round: schon Mitglied → kein Fehler, auch wenn das Konto jetzt wieder 5 Runden hat');
select pg_temp.als_postgres();
select is((select count(*)::int from public.round_members where user_id = pg_temp.id('dora')), 5,
  'Dora hat genau 5 Mitgliedschaften');

-- ---------------------------------------------------------------------------------------------------
-- Höchstens 12 Mitglieder je Runde
-- ---------------------------------------------------------------------------------------------------
-- Anna, Ben, Clara und Dora sind dabei; acht weitere Konten füllen die Runde auf 12.
select pg_temp.als_konto(1);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(2);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(3);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(4);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(5);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(6);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(7);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als_konto(8);  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select is((select count(*)::int from public.round_members), 12, 'Die Runde hat jetzt 12 Mitglieder');
select is(pg_temp.rev('r'), 12::bigint, 'Jeder Beitritt hat rev um eins erhöht');
select pg_temp.als_konto(9);
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token')), 'P0001', 'limit_reached',
  'join_round: das 13. Mitglied → limit_reached');
select is(pg_temp.rev('r'), 12::bigint, 'Der abgewiesene Beitritt hat rev nicht erhöht');
select is_empty($$ select * from public.my_rounds() $$, '… und das Konto ist kein Mitglied geworden');
select results_eq(
  format($$ select member_count, already_member from public.invite_info(%L) $$, pg_temp.gemerkt('token')),
  $$ values (12, false) $$,
  'invite_info zeigt die volle Runde (12 Mitglieder)'
);
select pg_temp.als_konto(8);
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'),
  'join_round: Wer schon Mitglied der vollen Runde ist, bekommt weiterhin keinen Fehler');
select public.leave_round(pg_temp.runde('r'));
select pg_temp.als_konto(9);
select lives_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token')),
  'join_round: Sobald jemand die Runde verlässt, ist wieder Platz');

-- ---------------------------------------------------------------------------------------------------
-- Verlassen und wieder beitreten: Der eigene Spieler wird wieder verknüpft
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select public.claim_player(pg_temp.runde('r'), 'spieler-b', 'Ben', 1);
select public.leave_round(pg_temp.runde('r'));
select pg_temp.als('anna');
select results_eq(
  $$ select name, owner, name_locked from public.round_players where id = 'spieler-b' $$,
  $$ values ('Ben'::text, pg_temp.id('ben'), true) $$,
  'Nach dem Verlassen gehört der Spieler weiter Ben'
);
select throws_ok(format($$ select public.claim_player(%L, 'spieler-b', 'Anna', 0) $$, pg_temp.runde('r')),
  'P0001', 'player_taken', 'Niemand anders kann Bens Spieler übernehmen, solange er fort ist');
select pg_temp.als('ben');
select is(public.join_round(pg_temp.gemerkt('token')), pg_temp.runde('r'), 'Ben tritt wieder bei');
select results_eq(
  $$ select my_player_id from public.my_rounds() $$,
  $$ values ('spieler-b'::text) $$,
  'join_round: Der eigene Spieler ist wieder verknüpft'
);
select results_eq(
  $$ select name, owner, name_locked, rev from public.round_players where id = 'spieler-b' $$,
  $$ values ('Ben'::text, pg_temp.id('ben'), true, 15::bigint) $$,
  'Die Spielerzeile selbst ist beim Wiederbeitritt unverändert'
);
-- Clara hatte nie einen Spieler: Nach Verlassen und Beitritt bleibt player_id leer.
select pg_temp.als('clara');
select public.leave_round(pg_temp.runde('r'));
select public.join_round(pg_temp.gemerkt('token')) is not null as wieder_beigetreten;
select results_eq(
  $$ select my_player_id from public.my_rounds() $$,
  $$ values (null::text) $$,
  'join_round: Ohne eigenen Spieler bleibt player_id leer'
);

select * from finish();
rollback;
