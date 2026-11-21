-- pgTAP: zwischen Runden wechseln (21.11.2026) – create_round lehnt einen Namen ab, den das Konto schon hat
-- (bereinigt, ohne Groß/Klein; andere Konten und beigetretene Runden; vor der Obergrenze), my_rounds liefert die
-- Namen der Mitglieder (member_names: nur übernommene Spieler, sortiert, auch nach dem Verlassen richtig).
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.3 und 8.7.
begin;
select plan(22);

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


-- Zählt die Runden, an RLS vorbei.
create function pg_temp.runden_von(kurzname text) returns integer language sql stable security definer as $$
  select count(*)::integer from public.rounds r where r.created_by = pg_temp.id(kurzname)
$$;
grant execute on function pg_temp.runden_von(text) to public;

-- ---------------------------------------------------------------------------------------------------
-- create_round: ein Name je Konto
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('a1', public.create_round('Stammtisch')::text);
select throws_ok($$ select public.create_round('Stammtisch') $$, 'P0001', 'name_taken',
  'create_round: derselbe Name noch einmal → name_taken');
select throws_ok($$ select public.create_round('stammTISCH') $$, 'P0001', 'name_taken',
  'create_round: Groß- und Kleinschreibung zählen nicht');
select throws_ok($$ select public.create_round(E'  STAMMTISCH \t\n') $$, 'P0001', 'name_taken',
  'create_round: verglichen wird der bereinigte Name (Ränder, Tabulator, Zeilenumbruch)');
select is(pg_temp.runden_von('anna'), 1, 'Die abgelehnten Versuche haben keine Runde angelegt');
select lives_ok($$ select pg_temp.merke('a2', public.create_round('Stammtisch 2')::text) $$,
  'create_round: ein anderer Name geht');
select lives_ok($$ select pg_temp.merke('a3', public.create_round(E'Stamm  tisch')::text) $$,
  'create_round: „Stamm tisch“ ist ein anderer Name als „Stammtisch“');
select throws_ok($$ select public.create_round('stamm tisch') $$, 'P0001', 'name_taken',
  'create_round: … und jetzt ebenfalls vergeben');
select throws_ok($$ select public.create_round('') $$, 'P0001', 'invalid_name',
  'create_round: Ein leerer Name bleibt invalid_name (geprüft wird zuerst die Länge)');

-- Andere Konten dürfen denselben Namen verwenden.
select pg_temp.als('ben');
select lives_ok($$ select pg_temp.merke('b1', public.create_round('Stammtisch')::text) $$,
  'create_round: Ben darf seine Runde auch „Stammtisch“ nennen');

-- Durch Beitreten kann ein Konto trotzdem zwei Runden desselben Namens haben.
select pg_temp.merke('token_b1', public.create_invite(pg_temp.runde('b1')));
select pg_temp.als('anna');
select lives_ok(format($$ select public.join_round(%L) $$, pg_temp.gemerkt('token_b1')),
  'join_round: Anna tritt Bens „Stammtisch“ bei, obwohl sie selbst einen hat');
select is((select count(*)::int from public.my_rounds() where lower(name) = 'stammtisch'), 2,
  'my_rounds: Anna hat jetzt zwei Runden namens „Stammtisch“');

-- Auch eine beigetretene Runde belegt den Namen; frei wird er erst, wenn keine Runde des Kontos mehr so heißt.
select public.leave_round(pg_temp.runde('a1'));
select throws_ok($$ select public.create_round('Stammtisch') $$, 'P0001', 'name_taken',
  'create_round: Bens „Stammtisch“, dem Anna beigetreten ist, belegt den Namen ebenso');
select public.leave_round(pg_temp.runde('b1'));
select lives_ok($$ select pg_temp.merke('a4', public.create_round('Stammtisch')::text) $$,
  'create_round: Nach dem Verlassen beider Runden ist der Name wieder frei');

-- Ist beides verletzt, sagt der Server zuerst, dass es den Namen schon gibt.
select pg_temp.als('dora');
select public.create_round('D' || n) from generate_series(1, 5) as n;
select throws_ok($$ select public.create_round('d1') $$, 'P0001', 'name_taken',
  'create_round: fünf Runden und ein vergebener Name → name_taken (vor limit_reached)');
select throws_ok($$ select public.create_round('D6') $$, 'P0001', 'limit_reached',
  'create_round: fünf Runden und ein neuer Name → limit_reached wie bisher');

-- ---------------------------------------------------------------------------------------------------
-- my_rounds: member_names
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('clara');
select pg_temp.merke('k', public.create_round('Klub')::text);
select is((select member_names from public.my_rounds() where id = pg_temp.runde('k')), '{}'::text[],
  'my_rounds: Ohne „Das bin ich“ ist member_names leer (nicht NULL)');

select public.claim_player(pg_temp.runde('k'), 'spieler-z', 'Zora', 1);
select public.save_player(pg_temp.runde('k'), 'gast-1', 'Gast', 2, false, false) > 0 as gast_angelegt;
select pg_temp.merke('token_k', public.create_invite(pg_temp.runde('k')));
select pg_temp.als('ben');
select public.join_round(pg_temp.gemerkt('token_k')) is not null as ben_beigetreten;
select public.claim_player(pg_temp.runde('k'), 'spieler-b', 'anton', 3);
select pg_temp.als('anna');
select public.join_round(pg_temp.gemerkt('token_k')) is not null as anna_beigetreten;

select results_eq(
  $$ select member_count, member_names from public.my_rounds() where id = pg_temp.runde('k') $$,
  $$ values (3, array['anton', 'Zora']::text[]) $$,
  'my_rounds: die Namen der übernommenen Spieler, ohne Groß/Klein sortiert; Gäste und Mitglieder ohne Spieler fehlen'
);
select pg_temp.als('ben');
select is((select member_names from public.my_rounds() where id = pg_temp.runde('k')), array['anton', 'Zora']::text[],
  'my_rounds: Jedes Mitglied sieht dieselben Namen, auch den eigenen');

-- Die Erstellerin korrigiert einen festen Namen: member_names zeigt den neuen.
select pg_temp.als('clara');
select public.save_player(pg_temp.runde('k'), 'spieler-b', 'Anton', 3, false, false) > 0 as korrigiert;
select is((select member_names from public.my_rounds() where id = pg_temp.runde('k')), array['Anton', 'Zora']::text[],
  'my_rounds: Nach einer Korrektur steht der neue Name in member_names');

-- Ben geht: Sein Spieler bleibt in der Runde (mit Besitzer), aber er ist kein Mitglied mehr.
select pg_temp.als('ben');
select public.leave_round(pg_temp.runde('k'));
select pg_temp.als('clara');
select results_eq(
  $$ select member_count, member_names from public.my_rounds() where id = pg_temp.runde('k') $$,
  $$ values (2, array['Zora']::text[]) $$,
  'my_rounds: Wer die Runde verlassen hat, fehlt in member_names, auch wenn sein Spieler bleibt'
);

-- Anna sagt noch „Das bin ich“ und übernimmt den Gast: Er zählt jetzt mit.
select pg_temp.als('anna');
select public.claim_player(pg_temp.runde('k'), 'gast-1', 'Anna', 2);
select is((select member_names from public.my_rounds() where id = pg_temp.runde('k')), array['Anna', 'Zora']::text[],
  'my_rounds: Ein übernommener Gast erscheint mit dem festgelegten Namen');

-- Die übrigen Spalten sind unverändert (Stichprobe; die Einzelheiten prüft 03_runden).
select results_eq(
  $$ select name, member_count, my_player_id, is_creator from public.my_rounds() where id = pg_temp.runde('k') $$,
  $$ values ('Klub'::text, 2, 'gast-1'::text, false) $$,
  'my_rounds: Name, Mitglieder, eigener Spieler und „Ersteller“ wie bisher'
);

select * from finish();
rollback;
