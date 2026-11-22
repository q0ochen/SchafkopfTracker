-- pgTAP: Runden anlegen, auflisten und verlassen – create_round (Name, Obergrenze 5 Runden je Konto),
-- my_rounds (Reihenfolge, Felder, liest nur), leave_round (not_member, der eigene Spieler bleibt, das letzte
-- Mitglied löscht die Runde samt Spielern, Abenden und Einladungen).
begin;
select plan(45);

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

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('clara');
select pg_temp.nutzer('dora');

-- ---------------------------------------------------------------------------------------------------
-- create_round
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('r1', public.create_round(E'  Stammtisch \t  Runde \n')::text);
select isnt(pg_temp.runde('r1'), null, 'create_round liefert die Kennung der neuen Runde');

select pg_temp.als_postgres();
select results_eq(
  $$ select name, created_by, rev, rules, created_at from public.rounds where id = pg_temp.runde('r1') $$,
  $$ values ('Stammtisch Runde'::text, pg_temp.id('anna'), 1::bigint, null::jsonb, now()) $$,
  'create_round: Name bereinigt (Ränder, Tabulator, Zeilenumbruch, doppelte Leerzeichen), Ersteller gesetzt, rev = 1, noch kein Regelwerk'
);
select results_eq(
  $$ select user_id, player_id, joined_at from public.round_members where round_id = pg_temp.runde('r1') $$,
  $$ values (pg_temp.id('anna'), null::text, now()) $$,
  'create_round: Der Aufrufer ist das erste Mitglied, noch ohne Spieler'
);
select is((select count(*)::int from public.round_players where round_id = pg_temp.runde('r1')), 0,
  'create_round legt keine Spieler an');

select pg_temp.als('anna');
select throws_ok($$ select public.create_round('') $$, 'P0001', 'invalid_name', 'create_round: leerer Name → invalid_name');
select throws_ok($$ select public.create_round(E' \t \n ') $$, 'P0001', 'invalid_name',
  'create_round: nur Leerzeichen → invalid_name');
select throws_ok($$ select public.create_round(null) $$, 'P0001', 'invalid_name', 'create_round: NULL → invalid_name');
select throws_ok($$ select public.create_round(repeat('x', 41)) $$, 'P0001', 'invalid_name',
  'create_round: 41 Zeichen → invalid_name');
select pg_temp.merke('r2', public.create_round('  ' || repeat('x', 40) || '  ')::text);
select pg_temp.merke('r3', public.create_round('Ä' || repeat('ö', 39))::text);
select is((select char_length(name) from public.rounds where id = pg_temp.runde('r2')), 40,
  'create_round: 40 Zeichen nach dem Beschneiden sind erlaubt');
select is((select name from public.rounds where id = pg_temp.runde('r3')), 'Ä' || repeat('ö', 39),
  'create_round: gezählt werden Zeichen, nicht Bytes (40 Umlaute)');
select pg_temp.merke('r4', public.create_round('Dieselbe')::text);
-- Seit dem 21.11.2026 hat ein Konto keine zwei Runden desselben Namens mehr (Einzelheiten in 08_runden_auswahl).
select throws_ok($$ select public.create_round('Dieselbe') $$, 'P0001', 'name_taken',
  'create_round: Einen Namen, den das Konto schon hat, gibt es nicht zweimal → name_taken');
select pg_temp.merke('r5', public.create_round('Noch eine')::text);

-- Obergrenze: 5 Runden je Konto
select throws_ok($$ select public.create_round('Die sechste') $$, 'P0001', 'limit_reached',
  'create_round: die sechste Runde desselben Kontos → limit_reached');
select pg_temp.als_postgres();
select is((select count(*)::int from public.rounds where created_by = pg_temp.id('anna')), 5,
  'Die abgelehnte Runde wurde nicht angelegt');
select pg_temp.als('ben');
select pg_temp.merke('rb', public.create_round('Bens Runde')::text);
select isnt(pg_temp.runde('rb'), null, 'Die Obergrenze gilt je Konto: Ben kann weiter Runden anlegen');
select pg_temp.als('anna');
select public.leave_round(pg_temp.runde('r5'));
select lives_ok($$ select pg_temp.merke('r6', public.create_round('Wieder Platz')::text) $$,
  'create_round: Nach dem Verlassen einer Runde ist wieder Platz');

-- ---------------------------------------------------------------------------------------------------
-- my_rounds
-- ---------------------------------------------------------------------------------------------------
-- Clara legt eine Runde an; Anna (voll) kann nicht beitreten, Ben tritt bei und sagt „Das bin ich“.
select pg_temp.als('clara');
select pg_temp.merke('rc', public.create_round('Claras Runde')::text);
select pg_temp.merke('token_c', public.create_invite(pg_temp.runde('rc')));
select pg_temp.als('ben');
select public.join_round(pg_temp.gemerkt('token_c')) is not null as ben_beigetreten;
select public.claim_player(pg_temp.runde('rc'), 'spieler-b', 'Ben', 3);

-- Alle Mitgliedschaften entstehen hier in derselben Transaktion (gleiche Zeit); für die Reihenfolge bekommen
-- sie verschiedene Beitrittszeiten.
select pg_temp.als_postgres();
update public.round_members m
   set joined_at = now() - case m.round_id
     when pg_temp.runde('r1') then interval '5 days'
     when pg_temp.runde('r3') then interval '4 days'
     when pg_temp.runde('r2') then interval '3 days'
     when pg_temp.runde('r4') then interval '2 days'
     when pg_temp.runde('rb') then interval '6 days'
     else interval '1 day' end
 where m.user_id in (pg_temp.id('anna'), pg_temp.id('ben'));
update public.round_members m set joined_at = now() - interval '7 days' where m.user_id = pg_temp.id('clara');

select pg_temp.als('anna');
select results_eq(
  $$ select id, name, rev, member_count, my_player_id, is_creator from public.my_rounds() $$,
  $$ values (pg_temp.runde('r1'), 'Stammtisch Runde'::text, 1::bigint, 1, null::text, true),
            (pg_temp.runde('r3'), 'Ä' || repeat('ö', 39), 1::bigint, 1, null::text, true),
            (pg_temp.runde('r2'), repeat('x', 40), 1::bigint, 1, null::text, true),
            (pg_temp.runde('r4'), 'Dieselbe'::text, 1::bigint, 1, null::text, true),
            (pg_temp.runde('r6'), 'Wieder Platz'::text, 1::bigint, 1, null::text, true) $$,
  'my_rounds: alle Runden des Aufrufers, älteste Mitgliedschaft zuerst (die verlassene Runde fehlt)'
);
select pg_temp.als('ben');
select results_eq(
  $$ select id, name, rev, member_count, my_player_id, is_creator from public.my_rounds() $$,
  $$ values (pg_temp.runde('rb'), 'Bens Runde'::text, 1::bigint, 1, null::text, true),
            (pg_temp.runde('rc'), 'Claras Runde'::text, 3::bigint, 2, 'spieler-b'::text, false) $$,
  'my_rounds: rev, Zahl der Mitglieder, eigener Spieler und „bin ich der Ersteller“'
);
select pg_temp.als('clara');
select results_eq(
  $$ select id, member_count, my_player_id, is_creator from public.my_rounds() $$,
  $$ values (pg_temp.runde('rc'), 2, null::text, true) $$,
  'my_rounds: Clara sieht ihre Runde mit zwei Mitgliedern, noch ohne eigenen Spieler'
);
select pg_temp.als('dora');
select is_empty($$ select * from public.my_rounds() $$, 'my_rounds: Konto ohne Runde → leer');
select pg_temp.als_postgres();
select is((select rev from public.rounds where id = pg_temp.runde('rc')), 3::bigint,
  'my_rounds liest nur: rev ist unverändert');

-- ---------------------------------------------------------------------------------------------------
-- leave_round
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('dora');
select throws_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('rc')), 'P0001', 'not_member',
  'leave_round: fremde Runde → not_member');
select throws_ok($$ select public.leave_round('00000000-0000-4000-8000-000000000000') $$, 'P0001', 'not_member',
  'leave_round: unbekannte Runde → not_member (wie bei einer fremden)');
select throws_ok($$ select public.leave_round(null) $$, 'P0001', 'not_member', 'leave_round: NULL → not_member');

-- Die Runde bekommt Inhalt: Clara sagt „Das bin ich“, es gibt einen Gast, einen Abend und zwei Einladungen.
select pg_temp.als('clara');
select public.claim_player(pg_temp.runde('rc'), 'spieler-c', 'Clara', 2);
select public.save_player(pg_temp.runde('rc'), 'gast-1', 'Gast', 4, false, false) > 0 as gast_angelegt;
select count(*) = 1 as abend_angelegt
  from public.save_evening(pg_temp.runde('rc'), 'abend-1', '{"id": "abend-1"}', 0, false);
select pg_temp.als('ben');
select public.create_invite(pg_temp.runde('rc')) is not null as einladung_von_ben;

-- Ben verlässt die Runde: Clara bleibt, Bens Spieler bleibt mit Besitzer.
select is(pg_temp.rev('rc'), 6::bigint, 'Vor dem Verlassen: rev = 6');
select lives_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('rc')), 'leave_round: Ben verlässt Claras Runde');
select is(pg_temp.rev('rc'), 7::bigint, 'leave_round erhöht rev (die Mitglieder haben sich geändert)');
select pg_temp.als_postgres();
select results_eq(
  $$ select user_id from public.round_members where round_id = pg_temp.runde('rc') $$,
  $$ values (pg_temp.id('clara')) $$,
  'leave_round: Nur Bens Mitgliedschaft ist weg'
);
select results_eq(
  $$ select name, owner, name_locked, deleted, rev from public.round_players
      where round_id = pg_temp.runde('rc') and id = 'spieler-b' $$,
  $$ values ('Ben'::text, pg_temp.id('ben'), true, false, 3::bigint) $$,
  'leave_round: Bens Spieler bleibt unverändert, mit owner'
);
select is((select count(*)::int from public.round_invites where round_id = pg_temp.runde('rc')), 2,
  'leave_round: Einladungen bleiben gültig');
select pg_temp.als('ben');
select is_empty($$ select * from public.my_rounds() where name = 'Claras Runde' $$,
  'leave_round: Die Runde steht nicht mehr in Bens my_rounds');
select throws_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('rc')), 'P0001', 'not_member',
  'leave_round: zweimal verlassen → not_member');
select throws_ok(format($$ select public.save_rules(%L, '{}') $$, pg_temp.runde('rc')), 'P0001', 'not_member',
  'Nach dem Verlassen darf Ben nichts mehr schreiben (not_member)');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1"}', 1, false) $$, pg_temp.runde('rc')),
  'P0001', 'not_member', 'Nach dem Verlassen darf Ben keinen Abend mehr speichern (not_member)');

-- Clara ist das letzte Mitglied: Mit ihr verschwindet die Runde samt Inhalt.
select pg_temp.als('clara');
select lives_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('rc')),
  'leave_round: Das letzte Mitglied (mit eigenem Spieler) verlässt die Runde');
select pg_temp.als_postgres();
select is_empty($$ select 1 from public.rounds where id = pg_temp.runde('rc') $$,
  'Verlässt das letzte Mitglied die Runde, wird sie gelöscht');
select is_empty($$ select 1 from public.round_members where round_id = pg_temp.runde('rc') $$, '… ohne Mitglieder');
select is_empty($$ select 1 from public.round_players where round_id = pg_temp.runde('rc') $$, '… samt Spielern');
select is_empty($$ select 1 from public.round_evenings where round_id = pg_temp.runde('rc') $$, '… samt Abenden');
select is_empty($$ select 1 from public.round_invites where round_id = pg_temp.runde('rc') $$, '… samt Einladungen');
select is((select count(*)::int from public.rounds where created_by in (pg_temp.id('anna'), pg_temp.id('ben'))), 6,
  'Die anderen Runden bleiben (5 von Anna, 1 von Ben)');
select pg_temp.als('clara');
select throws_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('rc')), 'P0001', 'not_member',
  'leave_round: gelöschte Runde → not_member');
select throws_ok(format($$ select * from public.invite_info(%L) $$, pg_temp.gemerkt('token_c')), 'P0001', 'invalid_invite',
  'Die Einladung einer gelöschten Runde ist ungültig');
select is_empty($$ select * from public.my_rounds() $$, 'Clara hat keine Runde mehr');

-- ---------------------------------------------------------------------------------------------------
-- Der Projektinhaber kann eine Runde auch direkt löschen (Dashboard): Alles Zugehörige verschwindet mit
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select public.claim_player(pg_temp.runde('rb'), 'spieler-b', 'Ben', 1);
select pg_temp.merke('token_b', public.create_invite(pg_temp.runde('rb')));
select pg_temp.als('clara');
select public.join_round(pg_temp.gemerkt('token_b')) is not null as clara_beigetreten;
select public.claim_player(pg_temp.runde('rb'), 'spieler-c', 'Clara', 2);
select count(*) = 1 as abend_angelegt
  from public.save_evening(pg_temp.runde('rb'), 'abend-1', '{"id": "abend-1"}', 0, false);
select pg_temp.als_postgres();
select lives_ok($$ delete from public.rounds where id = pg_temp.runde('rb') $$,
  'Als Eigentümer der Datenbank: eine Runde mit Mitgliedern und verknüpften Spielern direkt löschen');
select is(
  (select count(*)::int from public.round_members where round_id = pg_temp.runde('rb'))
  + (select count(*)::int from public.round_players where round_id = pg_temp.runde('rb'))
  + (select count(*)::int from public.round_evenings where round_id = pg_temp.runde('rb'))
  + (select count(*)::int from public.round_invites where round_id = pg_temp.runde('rb')),
  0, '… und nichts von ihr bleibt übrig');

select * from finish();
rollback;
