-- pgTAP: Konto löschen – wird ein Konto aus auth.users gelöscht (im Supabase-Dashboard oder über die Admin-API),
-- verschwinden seine Mitgliedschaften und Einladungen; sein Spieler wird zum Gast (der Name bleibt fest),
-- rounds.created_by und round_evenings.updated_by werden leer. Die anderen Geräte erfahren davon, weil rev
-- steigt und die Spielerzeile gestempelt wird. Eine Runde ohne Mitglieder wird gelöscht. Alles andere bleibt.
begin;
select plan(46);

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

-- Zeilen als Text (am RLS vorbei).
create function pg_temp.spieler(kennung text, was text default 'r1') returns text
language sql stable security definer as $$
  select concat_ws('|', p.name,
                   coalesce((select k from unnest(array['anna', 'ben', 'clara', 'dora', 'emil']) k
                              where pg_temp.id(k) = p.owner), '-'),
                   p.name_locked::text, p.deleted::text, p.rev)
    from public.round_players p
   where p.round_id = pg_temp.runde(was) and p.id = kennung
$$;
create function pg_temp.abend(kennung text, was text default 'r1') returns text
language sql stable security definer as $$
  select concat_ws('|', e.version, e.rev,
                   coalesce((select k from unnest(array['anna', 'ben', 'clara', 'dora', 'emil']) k
                              where pg_temp.id(k) = e.updated_by), '-'),
                   e.doc::text)
    from public.round_evenings e
   where e.round_id = pg_temp.runde(was) and e.id = kennung
$$;
create function pg_temp.mitglieder(was text) returns text language sql stable security definer as $$
  select string_agg(k, ', ' order by k)
    from unnest(array['anna', 'ben', 'clara', 'dora', 'emil']) k
    join public.round_members m on m.user_id = pg_temp.id(k)
   where m.round_id = pg_temp.runde(was)
$$;
grant execute on all functions in schema pg_temp to public;

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('clara');
select pg_temp.nutzer('dora');
select pg_temp.nutzer('emil');
select pg_temp.nutzer('fritz');   -- hat ein Konto, aber nie eine Runde

-- ---------------------------------------------------------------------------------------------------
-- Ausgangslage
--   r1 „Stammtisch“: Anna (Erstellerin), Ben, Clara; Dora war dabei und ist wieder gegangen (ihr Spieler bleibt).
--   r2 „Annas Solo“: nur Anna.   r3 „Emils Runde“: nur Emil.
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('r1', public.create_round('Stammtisch')::text);
select pg_temp.merke('token', public.create_invite(pg_temp.runde('r1')));
select public.claim_player(pg_temp.runde('r1'), 'spieler-a', 'Anna', 0);
select public.save_player(pg_temp.runde('r1'), 'gast', 'Gast', 3, false, false) > 0 as gast_angelegt;
select count(*) = 1 as abend_a from public.save_evening(pg_temp.runde('r1'), 'abend-a', '{"id": "abend-a"}', 0, false);
select public.save_rules(pg_temp.runde('r1'), '{"grundtarif": 10}') > 0 as regeln_gesetzt;
select pg_temp.merke('r2', public.create_round('Annas Solo')::text);
select public.claim_player(pg_temp.runde('r2'), 'spieler-a', 'Anna', 0);
select count(*) = 1 as abend_solo from public.save_evening(pg_temp.runde('r2'), 'abend-solo', '{"id": "abend-solo"}', 0, false);
select public.create_invite(pg_temp.runde('r2')) is not null as einladung_solo;

select pg_temp.als('ben');
select public.join_round(pg_temp.gemerkt('token')) is not null as ben_beigetreten;
select public.claim_player(pg_temp.runde('r1'), 'spieler-b', 'Ben', 1);
select count(*) = 1 as abend_b from public.save_evening(pg_temp.runde('r1'), 'abend-b', '{"id": "abend-b"}', 0, false);
select public.create_invite(pg_temp.runde('r1')) is not null as einladung_von_ben;
select pg_temp.als('clara');
select public.join_round(pg_temp.gemerkt('token')) is not null as clara_beigetreten;
select pg_temp.als('dora');
select public.join_round(pg_temp.gemerkt('token')) is not null as dora_beigetreten;
select public.claim_player(pg_temp.runde('r1'), 'spieler-d', 'Dora', 2);
select public.leave_round(pg_temp.runde('r1'));
select pg_temp.als('emil');
select pg_temp.merke('r3', public.create_round('Emils Runde')::text);
select public.claim_player(pg_temp.runde('r3'), 'spieler-e', 'Emil', 4);

select pg_temp.als_postgres();
select is(pg_temp.rev('r1'), 12::bigint, 'Vorher: r1 steht bei rev = 12');
select is(pg_temp.mitglieder('r1'), 'anna, ben, clara', 'Vorher: Anna, Ben und Clara sind Mitglieder von r1');
select is(pg_temp.spieler('spieler-a'), 'Anna|anna|true|false|2', 'Vorher: Annas Spieler gehört Anna');
select is(pg_temp.spieler('spieler-d'), 'Dora|dora|true|false|11', 'Vorher: Doras Spieler gehört weiter Dora, obwohl sie gegangen ist');
select is((select count(*)::int from public.round_invites where round_id = pg_temp.runde('r1')), 2,
  'Vorher: zwei Einladungen in r1 (von Anna und von Ben)');

-- ---------------------------------------------------------------------------------------------------
-- Annas Konto wird gelöscht
-- ---------------------------------------------------------------------------------------------------
select lives_ok($$ delete from auth.users where id = pg_temp.id('anna') $$, 'Annas Konto lässt sich löschen');

select is(pg_temp.mitglieder('r1'), 'ben, clara', 'Annas Mitgliedschaft ist weg, Ben und Clara bleiben');
select is(pg_temp.rev('r1'), 13::bigint, 'rev ist um eins gestiegen: Die anderen Geräte bemerken die Änderung');
select is(pg_temp.spieler('spieler-a'), 'Anna|-|true|false|13',
  'Annas Spieler ist jetzt ein Gast (owner leer), der Name bleibt und bleibt fest; die Zeile ist mit dem neuen rev gestempelt');
select is(pg_temp.spieler('spieler-b'), 'Ben|ben|true|false|7', 'Bens Spieler ist unverändert');
select is(pg_temp.spieler('gast'), 'Gast|-|false|false|3', 'Der Gast ist unverändert');
select is(pg_temp.spieler('spieler-d'), 'Dora|dora|true|false|11', 'Doras Spieler ist unverändert');
select results_eq(
  $$ select name, created_by, rules from public.rounds where id = pg_temp.runde('r1') $$,
  $$ values ('Stammtisch'::text, null::uuid, '{"grundtarif": 10}'::jsonb) $$,
  'Die Runde bleibt, nur created_by ist leer'
);
select is(pg_temp.abend('abend-a'), '1|4|-|{"id": "abend-a"}', 'Annas Abend bleibt, nur updated_by ist leer');
select is(pg_temp.abend('abend-b'), '1|8|ben|{"id": "abend-b"}', 'Bens Abend ist unverändert');
select results_eq(
  $$ select created_by from public.round_invites where round_id = pg_temp.runde('r1') $$,
  $$ values (pg_temp.id('ben')) $$,
  'Annas Einladung ist gelöscht, Bens Einladung bleibt'
);
select is(
  (select m.player_id from public.round_members m
    where m.round_id = pg_temp.runde('r1') and m.user_id = pg_temp.id('ben')),
  'spieler-b', 'Ben ist weiter mit seinem Spieler verknüpft');

-- r2 hatte nur Anna als Mitglied: Die Runde verschwindet samt Inhalt.
select is_empty($$ select 1 from public.rounds where id = pg_temp.runde('r2') $$,
  'Die Runde, in der Anna das einzige Mitglied war, ist gelöscht');
select is_empty($$ select 1 from public.round_players where round_id = pg_temp.runde('r2') $$, '… samt Spielern');
select is_empty($$ select 1 from public.round_evenings where round_id = pg_temp.runde('r2') $$, '… samt Abenden');
select is_empty($$ select 1 from public.round_invites where round_id = pg_temp.runde('r2') $$, '… samt Einladungen');
select is_empty($$ select 1 from public.round_members where round_id = pg_temp.runde('r2') $$, '… und ohne Mitglieder');

-- Fremde Runden bleiben unberührt.
select is(pg_temp.rev('r3'), 2::bigint, 'Emils Runde ist unverändert (rev)');
select is(pg_temp.spieler('spieler-e', 'r3'), 'Emil|emil|true|false|2', 'Emils Spieler ist unverändert');
select is_empty(
  $$ select 1 from public.rounds r where r.created_by = pg_temp.id('anna')
     union all select 1 from public.round_players p where p.owner = pg_temp.id('anna')
     union all select 1 from public.round_members m where m.user_id = pg_temp.id('anna')
     union all select 1 from public.round_evenings e where e.updated_by = pg_temp.id('anna')
     union all select 1 from public.round_invites i where i.created_by = pg_temp.id('anna') $$,
  'Nichts verweist mehr auf Annas Konto'
);

-- So sieht es ein Gerät mit cursor = 12: Die Spielerzeile kommt mit rev > 12, die Mitgliederliste ist kürzer.
select pg_temp.als('ben');
select results_eq(
  format($$ select id, name, owner, name_locked, deleted, rev from public.round_players
            where round_id = %L and rev > 12 order by rev $$, pg_temp.runde('r1')),
  $$ values ('spieler-a'::text, 'Anna'::text, null::uuid, true, false, 13::bigint) $$,
  'Ben liest mit rev > 12 genau die geänderte Spielerzeile'
);
select results_eq(
  format($$ select user_id from public.round_members where round_id = %L order by user_id $$, pg_temp.runde('r1')),
  $$ values (pg_temp.id('ben')), (pg_temp.id('clara')) $$,
  'Ben liest die neue Mitgliederliste'
);

-- Der frei gewordene Spieler lässt sich übernehmen („Das bin ich“) – mit neuem, wieder festem Namen.
select throws_ok(format($$ select public.save_player(%L, 'spieler-a', 'Anja', 0, false, false) $$, pg_temp.runde('r1')),
  'P0001', 'name_locked', 'Der Name des verwaisten Spielers bleibt fest (und ohne Ersteller kann ihn niemand korrigieren)');
select pg_temp.als('clara');
select lives_ok(format($$ select public.claim_player(%L, 'spieler-a', 'Clara', 0) $$, pg_temp.runde('r1')),
  'Clara übernimmt den Spieler, der Anna gehörte');
select is(pg_temp.spieler('spieler-a'), 'Clara|clara|true|false|14', 'Der Spieler gehört jetzt Clara');

-- ---------------------------------------------------------------------------------------------------
-- Doras Konto: Sie ist kein Mitglied mehr, besitzt in r1 aber noch einen Spieler
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
select lives_ok($$ delete from auth.users where id = pg_temp.id('dora') $$, 'Doras Konto lässt sich löschen');
select is(pg_temp.spieler('spieler-d'), 'Dora|-|true|false|15',
  'Doras Spieler ist jetzt ein Gast und mit dem neuen rev gestempelt');
select is(pg_temp.rev('r1'), 15::bigint, 'rev ist um eins gestiegen');
select is(pg_temp.mitglieder('r1'), 'ben, clara', 'Die Mitglieder sind unverändert');
select pg_temp.als('ben');
select lives_ok(format($$ select public.save_player(%L, 'spieler-d', null, null, null, true) $$, pg_temp.runde('r1')),
  'Ein verwaister Spieler ist ein Gast und lässt sich löschen');
select lives_ok(format($$ select public.save_player(%L, 'spieler-d', 'Neuer Gast', 5, false, false) $$, pg_temp.runde('r1')),
  'Die gelöschte Zeile lässt sich als Gast wiederbeleben – mit neuem Namen, obwohl der alte fest war');
select is(pg_temp.spieler('spieler-d'), 'Neuer Gast|-|false|false|17', 'Der wiederbelebte Gast hat keinen festen Namen mehr');

-- ---------------------------------------------------------------------------------------------------
-- Ein Konto ohne Runde, und die letzten beiden Mitglieder auf einmal
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als_postgres();
select lives_ok($$ delete from auth.users where id = pg_temp.id('fritz') $$, 'Ein Konto ohne Runde lässt sich löschen');
select is((select count(*)::int from public.rounds where id in (pg_temp.runde('r1'), pg_temp.runde('r2'), pg_temp.runde('r3'))), 2,
  'Es gibt weiterhin r1 und r3');

select lives_ok($$ delete from auth.users where id in (pg_temp.id('ben'), pg_temp.id('clara')) $$,
  'Ben und Clara (die letzten Mitglieder von r1, beide mit eigenem Spieler) lassen sich zusammen löschen');
select is_empty($$ select 1 from public.rounds where id = pg_temp.runde('r1') $$,
  'Mit dem letzten Mitglied ist auch r1 gelöscht');
select is((select count(*)::int from public.round_players where round_id = pg_temp.runde('r1')), 0, '… samt Spielern');
select is((select count(*)::int from public.round_evenings where round_id = pg_temp.runde('r1')), 0, '… samt Abenden');
select is((select count(*)::int from public.round_invites where round_id = pg_temp.runde('r1')), 0, '… samt Einladungen');

select results_eq(
  $$ select name, created_by, rev from public.rounds where id in (pg_temp.runde('r1'), pg_temp.runde('r2'), pg_temp.runde('r3')) $$,
  $$ values ('Emils Runde'::text, pg_temp.id('emil'), 2::bigint) $$,
  'Übrig ist nur Emils Runde, unverändert'
);
select results_eq(
  $$ select u.id from auth.users u
      where u.id in (pg_temp.id('anna'), pg_temp.id('ben'), pg_temp.id('clara'), pg_temp.id('dora'),
                     pg_temp.id('emil'), pg_temp.id('fritz')) $$,
  $$ values (pg_temp.id('emil')) $$,
  'Von den Konten des Tests ist nur Emils übrig'
);

select * from finish();
rollback;
