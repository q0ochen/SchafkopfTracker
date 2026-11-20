-- pgTAP: Wer was lesen darf (Row Level Security) und dass niemand direkt schreiben kann.
-- Matrix: Mitglied / Mitglied einer anderen Runde / Konto ohne Runde / nicht angemeldet / service_role,
-- für alle fünf Tabellen. Geschrieben wird nur über die Funktionen aus docs/ARCHITEKTUR.md, Abschnitt 8.3.
begin;
select plan(91);

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
-- Führt einen Befehl aus und liefert „SQLSTATE Meldung“ des Fehlers (oder „kein Fehler“).
create function pg_temp.fehler(befehl text) returns text language plpgsql as $$
begin
  execute befehl;
  return 'kein Fehler';
exception when others then
  return sqlstate || ' ' || sqlerrm;
end $$;
grant execute on all functions in schema pg_temp to public;

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('clara');
select pg_temp.nutzer('dora');

-- ---------------------------------------------------------------------------------------------------
-- Ausgangslage: Runde A (Anna und Ben) und Runde B (nur Clara), beide mit Spielern, Abend, Regelwerk und
-- Einladung. Dora hat ein Konto, aber keine Runde.
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('a', public.create_round('Runde A')::text);
select pg_temp.merke('token_a', public.create_invite(pg_temp.runde('a')));
select public.claim_player(pg_temp.runde('a'), 'spieler-1', 'Anna', 0);
select public.save_player(pg_temp.runde('a'), 'spieler-2', 'Gast A', 1, false, false) > 0 as gast_a_angelegt;
select count(*) = 1 as abend_a_angelegt
  from public.save_evening(pg_temp.runde('a'), 'abend-a', '{"id": "abend-a", "ort": "Wirtshaus"}', 0, false);
select public.save_rules(pg_temp.runde('a'), '{"grundtarif": 10}') > 0 as regeln_a_gesetzt;
select pg_temp.als('ben');
select public.join_round(pg_temp.gemerkt('token_a')) is not null as ben_beigetreten;

select pg_temp.als('clara');
select pg_temp.merke('b', public.create_round('Runde B')::text);
select pg_temp.merke('token_b', public.create_invite(pg_temp.runde('b')));
select public.claim_player(pg_temp.runde('b'), 'spieler-1', 'Clara', 2);
select count(*) = 1 as abend_b_angelegt
  from public.save_evening(pg_temp.runde('b'), 'abend-b', '{"id": "abend-b"}', 0, false);

-- ---------------------------------------------------------------------------------------------------
-- Mitglieder lesen genau ihre Runde
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select results_eq(
  $$ select id, name, rules, created_by from public.rounds $$,
  $$ values (pg_temp.runde('a'), 'Runde A'::text, '{"grundtarif": 10}'::jsonb, pg_temp.id('anna')) $$,
  'Anna sieht genau ihre Runde, mit Regelwerk und Ersteller'
);
select set_eq(
  $$ select round_id, user_id, player_id from public.round_members $$,
  $$ values (pg_temp.runde('a'), pg_temp.id('anna'), 'spieler-1'::text),
            (pg_temp.runde('a'), pg_temp.id('ben'), null::text) $$,
  'Anna sieht die Mitglieder ihrer Runde (und nur die)'
);
select set_eq(
  $$ select round_id, id, name from public.round_players $$,
  $$ values (pg_temp.runde('a'), 'spieler-1'::text, 'Anna'::text),
            (pg_temp.runde('a'), 'spieler-2'::text, 'Gast A'::text) $$,
  'Anna sieht die Spieler ihrer Runde (und nur die)'
);
select results_eq(
  $$ select round_id, id, doc ->> 'ort', version, deleted from public.round_evenings $$,
  $$ values (pg_temp.runde('a'), 'abend-a'::text, 'Wirtshaus'::text, 1, false) $$,
  'Anna sieht die Abende ihrer Runde (und nur die)'
);

select pg_temp.als('ben');
select results_eq(
  $$ select id from public.rounds $$, $$ values (pg_temp.runde('a')) $$,
  'Ben (beigetreten) sieht dieselbe Runde'
);
select is((select count(*)::int from public.round_players), 2, 'Ben sieht dieselben Spieler');
select is((select count(*)::int from public.round_evenings), 1, 'Ben sieht dieselben Abende');
select is((select count(*)::int from public.round_members), 2, 'Ben sieht dieselben Mitglieder');

-- So lesen die Apps (docs/ARCHITEKTUR.md, Abschnitt 8.3, „Lesen über PostgREST“).
select results_eq(
  format($$ select id, name, rev, rules, created_by from public.rounds where id = %L $$, pg_temp.runde('a')),
  $$ values (pg_temp.runde('a'), 'Runde A'::text, pg_temp.rev('a'), '{"grundtarif": 10}'::jsonb, pg_temp.id('anna')) $$,
  'Kopf der Runde: id, name, rev, rules, created_by'
);
select results_eq(
  format($$ select id, name, color_index, archived, position, owner, name_locked, deleted, rev
            from public.round_players where round_id = %L and rev > 0 order by rev $$, pg_temp.runde('a')),
  $$ values ('spieler-1'::text, 'Anna'::text, 0, false, 1, pg_temp.id('anna'), true, false, 2::bigint),
            ('spieler-2'::text, 'Gast A'::text, 1, false, 2, null::uuid, false, false, 3::bigint) $$,
  'Spieler mit rev > n, nach rev sortiert'
);
select results_eq(
  format($$ select id, version, deleted, rev from public.round_evenings
            where round_id = %L and rev > 3 order by rev limit 100 $$, pg_temp.runde('a')),
  $$ values ('abend-a'::text, 1, false, 4::bigint) $$,
  'Abende mit rev > n, nach rev sortiert, seitenweise'
);
select results_eq(
  format($$ select user_id, player_id from public.round_members where round_id = %L order by joined_at, user_id $$,
         pg_temp.runde('a')),
  $$ values (pg_temp.id('anna'), 'spieler-1'::text), (pg_temp.id('ben'), null::text) $$,
  'Mitglieder der Runde mit ihrem Spieler'
);

-- ---------------------------------------------------------------------------------------------------
-- Mitglied einer ANDEREN Runde: sieht von Runde A nichts
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('clara');
select results_eq($$ select id from public.rounds $$, $$ values (pg_temp.runde('b')) $$,
  'Clara sieht nur ihre eigene Runde B');
select is_empty(format($$ select 1 from public.rounds where id = %L $$, pg_temp.runde('a')),
  'Clara sieht den Kopf von Runde A nicht (leer – wie bei einer Runde, die es nicht gibt)');
select is_empty(format($$ select 1 from public.round_members where round_id = %L $$, pg_temp.runde('a')),
  'Clara sieht die Mitglieder von Runde A nicht');
select is_empty(format($$ select 1 from public.round_players where round_id = %L $$, pg_temp.runde('a')),
  'Clara sieht die Spieler von Runde A nicht');
select is_empty(format($$ select 1 from public.round_evenings where round_id = %L $$, pg_temp.runde('a')),
  'Clara sieht die Abende von Runde A nicht');
select is((select count(*)::int from public.round_players), 1, 'Clara sieht nur den Spieler ihrer Runde');
select is((select count(*)::int from public.round_evenings), 1, 'Clara sieht nur den Abend ihrer Runde');
select is((select count(*)::int from public.round_members), 1, 'Clara sieht nur sich als Mitglied');

-- ---------------------------------------------------------------------------------------------------
-- Konto ohne Runde: sieht gar nichts
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('dora');
select is_empty($$ select 1 from public.rounds $$, 'Dora (ohne Runde) sieht keine Runde');
select is_empty($$ select 1 from public.round_members $$, 'Dora sieht keine Mitglieder');
select is_empty($$ select 1 from public.round_players $$, 'Dora sieht keine Spieler');
select is_empty($$ select 1 from public.round_evenings $$, 'Dora sieht keine Abende');

-- ---------------------------------------------------------------------------------------------------
-- is_round_member: nur die eigene Mitgliedschaft, keine Auskunft über fremde Runden
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select ok(public.is_round_member(pg_temp.runde('a')), 'is_round_member: Anna ist Mitglied von Runde A');
select ok(not public.is_round_member(pg_temp.runde('b')), 'is_round_member: Anna ist kein Mitglied von Runde B');
select ok(not public.is_round_member('00000000-0000-4000-8000-000000000000'),
  'is_round_member: unbekannte Runde wie fremde Runde (false)');
select ok(not public.is_round_member(null), 'is_round_member: NULL ist false');

-- ---------------------------------------------------------------------------------------------------
-- Einladungen liest niemand
-- ---------------------------------------------------------------------------------------------------
select throws_ok($$ select token_hash from public.round_invites $$, '42501', null,
  'Mitglieder können round_invites nicht lesen');
select throws_ok($$ select count(*) from public.round_invites $$, '42501', null,
  'Mitglieder können Einladungen nicht einmal zählen');

-- ---------------------------------------------------------------------------------------------------
-- Direktes Schreiben gibt es für niemanden (auch nicht in der eigenen Runde)
-- ---------------------------------------------------------------------------------------------------
select throws_ok($$ insert into public.rounds (name) values ('Direkt') $$, '42501', null,
  'rounds: kein direktes INSERT');
select throws_ok(format($$ update public.rounds set name = 'Gehackt', rev = 99 where id = %L $$, pg_temp.runde('a')),
  '42501', null, 'rounds: kein direktes UPDATE (auch nicht der eigenen Runde)');
select throws_ok(format($$ delete from public.rounds where id = %L $$, pg_temp.runde('a')),
  '42501', null, 'rounds: kein direktes DELETE');

select throws_ok(
  format($$ insert into public.round_players (round_id, id, name, color_index, position, rev)
            values (%L, 'direkt', 'Direkt', 0, 9, 9) $$, pg_temp.runde('a')),
  '42501', null, 'round_players: kein direktes INSERT');
select throws_ok($$ update public.round_players set name = 'Gehackt', name_locked = false $$,
  '42501', null, 'round_players: kein direktes UPDATE');
select throws_ok($$ delete from public.round_players $$, '42501', null, 'round_players: kein direktes DELETE');

select throws_ok(
  format($$ insert into public.round_members (round_id, user_id) values (%L, %L) $$,
         pg_temp.runde('b'), pg_temp.id('anna')),
  '42501', null, 'round_members: kein direktes INSERT (niemand kann sich selbst in eine Runde schreiben)');
select throws_ok($$ update public.round_members set player_id = 'spieler-2' $$,
  '42501', null, 'round_members: kein direktes UPDATE');
select throws_ok($$ delete from public.round_members $$, '42501', null, 'round_members: kein direktes DELETE');

select throws_ok(
  format($$ insert into public.round_evenings (round_id, id, doc, version, rev)
            values (%L, 'direkt', '{"id": "direkt"}', 1, 9) $$, pg_temp.runde('a')),
  '42501', null, 'round_evenings: kein direktes INSERT');
select throws_ok($$ update public.round_evenings set doc = '{"id": "abend-a", "gehackt": true}' $$,
  '42501', null, 'round_evenings: kein direktes UPDATE (nur save_evening mit erwarteter Version)');
select throws_ok($$ delete from public.round_evenings $$, '42501', null, 'round_evenings: kein direktes DELETE');

select throws_ok(
  format($$ insert into public.round_invites (token_hash, round_id, created_by, expires_at)
            values (repeat('a', 64), %L, %L, now() + interval '1 day') $$, pg_temp.runde('a'), pg_temp.id('anna')),
  '42501', null, 'round_invites: kein direktes INSERT');
select throws_ok($$ update public.round_invites set expires_at = now() + interval '10 years' $$,
  '42501', null, 'round_invites: kein direktes UPDATE');
select throws_ok($$ delete from public.round_invites $$, '42501', null, 'round_invites: kein direktes DELETE');

-- Interne Hilfsfunktionen sind für Clients nicht erreichbar.
select throws_ok(format($$ select private.bump_rev(%L) $$, pg_temp.runde('a')), '42501', null,
  'Mitglieder können private.bump_rev nicht aufrufen');
select throws_ok(format($$ select private.lock_round(%L, %L) $$, pg_temp.runde('b'), pg_temp.id('clara')),
  '42501', null, 'Mitglieder können private.lock_round nicht aufrufen');

-- ---------------------------------------------------------------------------------------------------
-- Nicht angemeldet (anon): keine Tabelle, keine Funktion
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_gast();
select throws_ok($$ select 1 from public.rounds $$, '42501', null, 'anon: kein Lesezugriff auf rounds');
select throws_ok($$ select 1 from public.round_players $$, '42501', null, 'anon: kein Lesezugriff auf round_players');
select throws_ok($$ select 1 from public.round_members $$, '42501', null, 'anon: kein Lesezugriff auf round_members');
select throws_ok($$ select 1 from public.round_evenings $$, '42501', null, 'anon: kein Lesezugriff auf round_evenings');
select throws_ok($$ select 1 from public.round_invites $$, '42501', null, 'anon: kein Lesezugriff auf round_invites');
select throws_ok($$ insert into public.rounds (name) values ('Gast') $$, '42501', null, 'anon: kein INSERT');
-- Die Funktionen: Schon die Datenbank verweigert den Aufruf (kein EXECUTE-Recht). Die Meldung darf deshalb NICHT
-- „not_authenticated“ sein – das wäre erst die Prüfung in der Funktion selbst.
select matches(pg_temp.fehler($$ select public.create_round('Gast') $$), '^42501 (?!not_authenticated)',
  'anon darf create_round nicht aufrufen');
select matches(pg_temp.fehler($$ select * from public.my_rounds() $$), '^42501 (?!not_authenticated)',
  'anon darf my_rounds nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.create_invite(%L) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf create_invite nicht aufrufen');
select matches(pg_temp.fehler(format($$ select * from public.invite_info(%L) $$, pg_temp.gemerkt('token_a'))),
  '^42501 (?!not_authenticated)', 'anon darf invite_info nicht aufrufen (auch nicht mit gültigem Token)');
select matches(pg_temp.fehler(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token_a'))),
  '^42501 (?!not_authenticated)', 'anon darf join_round nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.leave_round(%L) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf leave_round nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.claim_player(%L, 'spieler-2', 'Gast', 0) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf claim_player nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.save_player(%L, 'x', 'Gast', 0, false, false) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf save_player nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.save_rules(%L, '{}') $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf save_rules nicht aufrufen');
select matches(
  pg_temp.fehler(format($$ select * from public.save_evening(%L, 'x', '{"id": "x"}', 0, false) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf save_evening nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.is_round_member(%L) $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'anon darf is_round_member nicht aufrufen');

-- ---------------------------------------------------------------------------------------------------
-- service_role (wird nicht gebraucht): ebenfalls nichts
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_server();
select throws_ok($$ select 1 from public.rounds $$, '42501', null, 'service_role: kein Zugriff auf rounds');
select throws_ok($$ select 1 from public.round_invites $$, '42501', null, 'service_role: kein Zugriff auf round_invites');
select matches(pg_temp.fehler($$ select public.create_round('Server') $$), '^42501 (?!not_authenticated)',
  'service_role darf create_round nicht aufrufen');
select matches(pg_temp.fehler(format($$ select public.save_rules(%L, '{}') $$, pg_temp.runde('a'))),
  '^42501 (?!not_authenticated)', 'service_role darf save_rules nicht aufrufen');

-- ---------------------------------------------------------------------------------------------------
-- Rolle authenticated, aber ohne Konto im JWT: sieht nichts und darf nichts
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
set local role authenticated;
select is_empty($$ select 1 from public.rounds $$, 'Ohne Konto im JWT: keine Runde sichtbar');
select is_empty($$ select 1 from public.round_evenings $$, 'Ohne Konto im JWT: kein Abend sichtbar');
-- Jede der zehn Funktionen prüft zuerst die Anmeldung – vor der Mitgliedschaft und vor jeder Auskunft.
select throws_ok($$ select public.create_round('Niemand') $$, '42501', 'not_authenticated',
  'Ohne Konto im JWT: create_round → not_authenticated');
select throws_ok($$ select * from public.my_rounds() $$, '42501', 'not_authenticated',
  'Ohne Konto im JWT: my_rounds → not_authenticated');
select throws_ok(format($$ select public.create_invite(%L) $$, pg_temp.runde('a')), '42501', 'not_authenticated',
  'Ohne Konto im JWT: create_invite → not_authenticated');
select throws_ok(format($$ select * from public.invite_info(%L) $$, pg_temp.gemerkt('token_a')), '42501', 'not_authenticated',
  'Ohne Konto im JWT: invite_info → not_authenticated (keine Auskunft über den Token)');
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token_a')), '42501', 'not_authenticated',
  'Ohne Konto im JWT: join_round → not_authenticated');
select throws_ok(format($$ select public.leave_round(%L) $$, pg_temp.runde('a')), '42501', 'not_authenticated',
  'Ohne Konto im JWT: leave_round → not_authenticated');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-2', 'Niemand', 0) $$, pg_temp.runde('a')),
  '42501', 'not_authenticated', 'Ohne Konto im JWT: claim_player → not_authenticated');
select throws_ok(format($$ select public.save_player(%L, 'spieler-2', 'Niemand', 0, false, false) $$, pg_temp.runde('a')),
  '42501', 'not_authenticated', 'Ohne Konto im JWT: save_player → not_authenticated');
select throws_ok(format($$ select public.save_rules(%L, '{}') $$, pg_temp.runde('a')), '42501', 'not_authenticated',
  'Ohne Konto im JWT: save_rules → not_authenticated');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'abend-a', '{"id": "abend-a"}', 1, false) $$, pg_temp.runde('a')),
  '42501', 'not_authenticated', 'Ohne Konto im JWT: save_evening → not_authenticated');
select ok(not public.is_round_member(pg_temp.runde('a')), 'Ohne Konto im JWT: is_round_member ist false');

-- Ein anonymes Konto (falls die anonyme Anmeldung je versehentlich eingeschaltet würde) darf ebenfalls nichts.
select pg_temp.als_postgres();
insert into auth.users (id, aud, role, is_anonymous)
values ('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', 'authenticated', 'authenticated', true);
select set_config('request.jwt.claims',
  '{"sub": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "role": "authenticated", "is_anonymous": true}', true) is not null as anonym_angemeldet;
set local role authenticated;
select throws_ok($$ select public.create_round('Anonym') $$, '42501', 'not_authenticated',
  'Anonymes Konto: create_round → not_authenticated');
select throws_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token_a')), '42501', 'not_authenticated',
  'Anonymes Konto: join_round → not_authenticated (auch mit gültigem Token)');
select throws_ok($$ select * from public.my_rounds() $$, '42501', 'not_authenticated',
  'Anonymes Konto: my_rounds → not_authenticated');

-- ---------------------------------------------------------------------------------------------------
-- Nach all den abgewiesenen Versuchen ist nichts verändert
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
select results_eq(
  $$ select name, rev from public.rounds where id in (pg_temp.runde('a'), pg_temp.runde('b')) order by name $$,
  $$ values ('Runde A'::text, 6::bigint), ('Runde B'::text, 3::bigint) $$,
  'Runden unverändert (Namen und rev)'
);
select is((select count(*)::int from public.round_players where round_id in (pg_temp.runde('a'), pg_temp.runde('b'))), 3, 'Spieler unverändert');
select is((select count(*)::int from public.round_members where round_id in (pg_temp.runde('a'), pg_temp.runde('b'))), 3, 'Mitglieder unverändert');
select is((select count(*)::int from public.round_evenings where round_id in (pg_temp.runde('a'), pg_temp.runde('b'))), 2, 'Abende unverändert');
select is((select count(*)::int from public.round_invites where round_id in (pg_temp.runde('a'), pg_temp.runde('b'))), 2, 'Einladungen unverändert');

-- Wer die Runde verlässt, liest sie nicht mehr.
select pg_temp.als('ben');
select public.leave_round(pg_temp.runde('a'));
select is_empty($$ select 1 from public.rounds $$, 'Nach dem Verlassen sieht Ben die Runde nicht mehr');
select is_empty($$ select 1 from public.round_evenings $$, 'Nach dem Verlassen sieht Ben die Abende nicht mehr');

select * from finish();
rollback;
