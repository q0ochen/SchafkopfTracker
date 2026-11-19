-- pgTAP: Spieler – claim_player („Das bin ich“: neu anlegen, Gast übernehmen, already_claimed, player_taken,
-- der eigene Name bleibt) und save_player (Gäste anlegen, ändern und löschen; Farbe und „archiviert“ für alle;
-- fester Name mit der Ausnahme für den Ersteller; eindeutige Namen ohne Groß/Klein; Obergrenze 40).
begin;
select plan(110);

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

-- Eine Spielerzeile als Text (am RLS vorbei): Name|Farbe|archiviert|Position|Besitzer|fest|gelöscht.
create function pg_temp.spieler(kennung text) returns text language sql stable security definer as $$
  select concat_ws('|', p.name, p.color_index, p.archived::text, p.position,
                   coalesce((select k from unnest(array['anna', 'ben', 'clara', 'dora', 'emil', 'fritz']) k
                              where pg_temp.id(k) = p.owner), '-'),
                   p.name_locked::text, p.deleted::text)
    from public.round_players p
   where p.round_id = pg_temp.runde('r') and p.id = kennung
$$;
create function pg_temp.spieler_rev(kennung text) returns bigint language sql stable security definer as $$
  select p.rev from public.round_players p where p.round_id = pg_temp.runde('r') and p.id = kennung
$$;
create function pg_temp.mein_spieler(kurzname text) returns text language sql stable security definer as $$
  select m.player_id from public.round_members m
   where m.round_id = pg_temp.runde('r') and m.user_id = pg_temp.id(kurzname)
$$;
create function pg_temp.spielerzahl(auch_geloeschte boolean) returns int language sql stable security definer as $$
  select count(*)::int from public.round_players p
   where p.round_id = pg_temp.runde('r') and (auch_geloeschte or not p.deleted)
$$;
grant execute on all functions in schema pg_temp to public;

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('clara');
select pg_temp.nutzer('dora');
select pg_temp.nutzer('emil');
select pg_temp.nutzer('fritz');

-- Anna legt die Runde an; Ben, Clara, Emil und Fritz treten bei. Dora bleibt draußen.
select pg_temp.als('anna');
select pg_temp.merke('r', public.create_round('Stammtisch')::text);
select pg_temp.merke('token', public.create_invite(pg_temp.runde('r')));
select pg_temp.als('ben');   select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als('clara'); select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als('emil');  select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select pg_temp.als('fritz'); select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select is(pg_temp.rev('r'), 5::bigint, 'Ausgangslage: fünf Mitglieder, rev = 5');

-- ---------------------------------------------------------------------------------------------------
-- claim_player: neu anlegen
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select lives_ok(format($$ select public.claim_player(%L, 'spieler-1', E'  Anna \t ', 5) $$, pg_temp.runde('r')),
  'claim_player: Anna legt sich als neuen Spieler an');
select is(pg_temp.spieler('spieler-1'), 'Anna|5|false|1|anna|true|false',
  'claim_player: Name bereinigt, Farbe gesetzt, Position 1, owner = Aufruferin, name_locked = true');
select is(pg_temp.rev('r'), 6::bigint, 'claim_player erhöht rev');
select is(pg_temp.spieler_rev('spieler-1'), 6::bigint, 'claim_player stempelt die Spielerzeile mit dem neuen rev');
select is(pg_temp.mein_spieler('anna'), 'spieler-1', 'claim_player setzt round_members.player_id');

-- Zwei Gäste für die folgenden Fälle.
select is(public.save_player(pg_temp.runde('r'), 'gast-1', E'  Gast \n  Eins ', 1, false, false), 7::bigint,
  'save_player: legt einen Gast an und liefert das neue rev');
select is(pg_temp.spieler('gast-1'), 'Gast Eins|1|false|2|-|false|false',
  'save_player: Name bereinigt, Position 2, kein Besitzer, Name nicht fest');
select is(pg_temp.spieler_rev('gast-1'), 7::bigint, 'save_player stempelt die Spielerzeile mit dem neuen rev');
select is(pg_temp.rev('r'), 7::bigint, 'save_player erhöht rev um genau eins');
select public.save_player(pg_temp.runde('r'), 'gast-2', 'Gast Zwei', 2, false, false) = 8 as gast_2_angelegt;

-- ---------------------------------------------------------------------------------------------------
-- claim_player: already_claimed, der eigene Name bleibt
-- ---------------------------------------------------------------------------------------------------
select throws_ok(format($$ select public.claim_player(%L, 'spieler-x', 'Zweitname', 0) $$, pg_temp.runde('r')),
  'P0001', 'already_claimed', 'claim_player: Anna hat schon einen Spieler – ein weiterer neuer → already_claimed');
select throws_ok(format($$ select public.claim_player(%L, 'gast-1', 'Zweitname', 0) $$, pg_temp.runde('r')),
  'P0001', 'already_claimed', 'claim_player: … und auch kein Gast zusätzlich → already_claimed');
select is(pg_temp.rev('r'), 8::bigint, 'Abgewiesene Aufrufe erhöhen rev nicht');

select lives_ok(format($$ select public.claim_player(%L, 'spieler-1', 'Anders', 6) $$, pg_temp.runde('r')),
  'claim_player: derselbe Spieler noch einmal (zum Beispiel wiederholter Aufruf) → kein Fehler');
select is(pg_temp.spieler('spieler-1'), 'Anna|6|false|1|anna|true|false',
  'claim_player: Gehörte der Spieler dem Aufrufer schon, bleibt der Name (die Farbe wird übernommen)');
select is(pg_temp.spieler_rev('spieler-1'), 9::bigint, '… und die Zeile ist neu gestempelt');

-- ---------------------------------------------------------------------------------------------------
-- claim_player: player_taken, Gast übernehmen
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-1', 'Ben', 0) $$, pg_temp.runde('r')),
  'P0001', 'player_taken', 'claim_player: Annas Spieler gehört einer anderen → player_taken');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-1', '', 0) $$, pg_temp.runde('r')),
  'P0001', 'player_taken', 'claim_player: player_taken kommt vor der Prüfung des Namens');
select lives_ok(format($$ select public.claim_player(%L, 'gast-1', 'Ben', 3) $$, pg_temp.runde('r')),
  'claim_player: Ben übernimmt den Gast „Gast Eins“');
select is(pg_temp.spieler('gast-1'), 'Ben|3|false|2|ben|true|false',
  'claim_player: Der Gast gehört jetzt Ben, heißt wie gewünscht, der Name ist fest, die Position bleibt');
select is(pg_temp.spieler_rev('gast-1'), pg_temp.rev('r'), 'claim_player stempelt die übernommene Zeile');
select is(pg_temp.mein_spieler('ben'), 'gast-1', 'claim_player: Ben ist mit dem Spieler verknüpft');

-- ---------------------------------------------------------------------------------------------------
-- claim_player: invalid_name, name_taken (ohne Groß/Klein), ungültige Kennung, Farbe
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('clara');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', '', 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'claim_player: leerer Name → invalid_name');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', E' \t ', 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'claim_player: nur Leerzeichen → invalid_name');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', null, 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'claim_player: NULL → invalid_name');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', repeat('x', 25), 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'claim_player: 25 Zeichen → invalid_name');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', 'ANNA', 0) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'claim_player: „ANNA“ ist vergeben (Anna) → name_taken, ohne Groß/Klein');
select throws_ok(format($$ select public.claim_player(%L, 'gast-2', ' ben ', 0) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'claim_player: Auch beim Übernehmen eines Gastes darf der Name nicht vergeben sein');
select throws_ok(format($$ select public.claim_player(%L, '', 'Clara', 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'claim_player: leere Spieler-Kennung → invalid_doc');
select throws_ok(format($$ select public.claim_player(%L, repeat('k', 65), 'Clara', 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'claim_player: Spieler-Kennung mit 65 Zeichen → invalid_doc');
select throws_ok(format($$ select public.claim_player(%L, null, 'Clara', 0) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'claim_player: Spieler-Kennung NULL → invalid_doc');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', 'Clara', null) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'claim_player: Farbe NULL → invalid_doc');
select is(pg_temp.mein_spieler('clara'), null, 'Nach all den Fehlversuchen hat Clara weiterhin keinen Spieler');
select is(pg_temp.spielerzahl(true), 3, '… und es ist kein Spieler entstanden');

select pg_temp.als('emil');
select lives_ok(format($$ select public.claim_player(%L, repeat('k', 64), repeat('y', 24), 35) $$, pg_temp.runde('r')),
  'claim_player: Kennung mit 64 und Name mit 24 Zeichen sind erlaubt');
select is(pg_temp.spieler(repeat('k', 64)), repeat('y', 24) || '|3|false|4|emil|true|false',
  'claim_player: Farbe 35 wird auf 0 bis 31 abgebildet (35 mod 32 = 3); Position 4');

-- ---------------------------------------------------------------------------------------------------
-- claim_player: nur Mitglieder
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('dora');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-d', 'Dora', 0) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'claim_player: kein Mitglied → not_member');
select throws_ok(format($$ select public.claim_player(%L, '', '', null) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'claim_player: kein Mitglied → not_member, auch bei ungültigen Angaben');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-1', 'Dora', 0) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'claim_player: kein Mitglied erfährt nicht, ob es einen Spieler gibt (not_member statt player_taken)');
select throws_ok($$ select public.claim_player('00000000-0000-4000-8000-000000000000', 'spieler-d', 'Dora', 0) $$,
  'P0001', 'not_member', 'claim_player: unbekannte Runde → not_member');

-- ---------------------------------------------------------------------------------------------------
-- save_player: Gäste ändern, eindeutige Namen
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select pg_temp.merke('vorher', pg_temp.rev('r')::text);
select is(public.save_player(pg_temp.runde('r'), 'gast-2', 'Zweiter Gast', 33, true, false),
  pg_temp.gemerkt('vorher')::bigint + 1, 'save_player: Jedes Mitglied darf einen Gast ändern; liefert rev + 1');
select is(pg_temp.spieler('gast-2'), 'Zweiter Gast|1|true|3|-|false|false',
  'save_player: Name, Farbe (33 mod 32 = 1) und „archiviert“ geändert, Position bleibt');
select is(pg_temp.spieler_rev('gast-2'), pg_temp.rev('r'), 'save_player stempelt die geänderte Zeile');
select lives_ok(format($$ select public.save_player(%L, 'gast-2', 'zweiter GAST', 1, null, null) $$, pg_temp.runde('r')),
  'save_player: nur die Schreibweise des eigenen Namens ändern ist erlaubt; NULL bei archiviert/gelöscht gilt als false');
select is(pg_temp.spieler('gast-2'), 'zweiter GAST|1|false|3|-|false|false', '… und gespeichert');

select throws_ok(format($$ select public.save_player(%L, 'gast-2', 'aNNa', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'save_player: umbenennen auf einen vergebenen Namen → name_taken (ohne Groß/Klein)');
select throws_ok(format($$ select public.save_player(%L, 'gast-3', '  BEN ', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'save_player: neuer Gast mit vergebenem Namen → name_taken');
select throws_ok(format($$ select public.save_player(%L, 'gast-3', '', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'save_player: neuer Gast ohne Namen → invalid_name');
select throws_ok(format($$ select public.save_player(%L, 'gast-3', null, 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'save_player: Name NULL → invalid_name');
select throws_ok(format($$ select public.save_player(%L, 'gast-2', repeat('z', 25), 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'save_player: umbenennen auf 25 Zeichen → invalid_name');
select throws_ok(format($$ select public.save_player(%L, '', 'Gast', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_player: leere Kennung → invalid_doc');
select throws_ok(format($$ select public.save_player(%L, repeat('k', 65), 'Gast', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_player: Kennung mit 65 Zeichen → invalid_doc');
select throws_ok(format($$ select public.save_player(%L, null, 'Gast', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_player: Kennung NULL → invalid_doc');
select throws_ok(format($$ select public.save_player(%L, 'gast-3', 'Gast', null, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_player: Farbe NULL → invalid_doc');
select is(pg_temp.spieler('gast-3'), null, 'Abgewiesene Aufrufe legen keinen Spieler an');

-- Umlaute: „ohne Groß/Klein“ gilt auch für sie, wenn die Datenbank Umlaute umwandeln kann.
select public.save_player(pg_temp.runde('r'), 'gast-j', 'Jörg', 4, false, false) > 0 as gast_j_angelegt;
select case when lower('Ö') = 'ö'
  then throws_ok(format($$ select public.save_player(%L, 'gast-j2', 'JÖRG', 4, false, false) $$, pg_temp.runde('r')),
         'P0001', 'name_taken', 'save_player: „JÖRG“ ist vergeben (Jörg) → name_taken')
  else skip('Diese Datenbank wandelt Umlaute nicht in Kleinbuchstaben um', 1)
end;

-- ---------------------------------------------------------------------------------------------------
-- save_player: fester Name (name_locked) mit der Ausnahme für den Ersteller; Farbe und „archiviert“ für alle
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('clara');
select lives_ok(format($$ select public.save_player(%L, 'spieler-1', '  Anna ', 7, true, false) $$, pg_temp.runde('r')),
  'save_player: Farbe und „archiviert“ darf jedes Mitglied bei jedem Spieler ändern (Name unverändert)');
select is(pg_temp.spieler('spieler-1'), 'Anna|7|true|1|anna|true|false',
  'save_player: Annas Spieler hat die neue Farbe und ist archiviert; Besitzerin und fester Name bleiben');
select throws_ok(format($$ select public.save_player(%L, 'spieler-1', 'Anja', 7, true, false) $$, pg_temp.runde('r')),
  'P0001', 'name_locked', 'save_player: Den festen Namen einer anderen ändern → name_locked');
select throws_ok(format($$ select public.save_player(%L, 'spieler-1', 'ANNA', 7, true, false) $$, pg_temp.runde('r')),
  'P0001', 'name_locked', 'save_player: Auch nur die Schreibweise ändern → name_locked');
select throws_ok(format($$ select public.save_player(%L, 'spieler-1', '', 7, true, false) $$, pg_temp.runde('r')),
  'P0001', 'name_locked', 'save_player: name_locked kommt vor der Prüfung des neuen Namens');
select pg_temp.als('ben');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', 'Benedikt', 3, false, false) $$, pg_temp.runde('r')),
  'P0001', 'name_locked', 'save_player: Auch der Besitzer selbst kann seinen festen Namen nicht mehr ändern');
select lives_ok(format($$ select public.save_player(%L, 'gast-1', 'Ben', 8, false, false) $$, pg_temp.runde('r')),
  'save_player: Seine Farbe darf er ändern');
select pg_temp.als('anna');
select lives_ok(format($$ select public.save_player(%L, 'gast-1', 'Benedikt', 8, false, false) $$, pg_temp.runde('r')),
  'save_player: Die Erstellerin der Runde darf einen festen Namen korrigieren');
select is(pg_temp.spieler('gast-1'), 'Benedikt|8|false|2|ben|true|false',
  'save_player: Der korrigierte Name bleibt fest und der Spieler gehört weiter Ben');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', 'anna', 8, false, false) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'save_player: Auch die Erstellerin kann keinen vergebenen Namen setzen → name_taken');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', repeat('b', 25), 8, false, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_name', 'save_player: … und keinen ungültigen → invalid_name');
select lives_ok(format($$ select public.save_player(%L, 'spieler-1', 'Anna B.', 7, false, false) $$, pg_temp.runde('r')),
  'save_player: Die Erstellerin darf auch ihren eigenen festen Namen korrigieren');

-- ---------------------------------------------------------------------------------------------------
-- save_player: löschen (nur Gäste), die Zeile bleibt
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('clara');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', 'Benedikt', 8, false, true) $$, pg_temp.runde('r')),
  'P0001', 'player_owned', 'save_player: Der Spieler eines Mitglieds lässt sich nicht löschen → player_owned');
select pg_temp.als('ben');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', 'Benedikt', 8, false, true) $$, pg_temp.runde('r')),
  'P0001', 'player_owned', 'save_player: … auch nicht von seinem Besitzer');
select pg_temp.als('anna');
select throws_ok(format($$ select public.save_player(%L, 'gast-1', 'Benedikt', 8, false, true) $$, pg_temp.runde('r')),
  'P0001', 'player_owned', 'save_player: … und nicht von der Erstellerin');

select pg_temp.als('clara');
select pg_temp.merke('vorher', pg_temp.rev('r')::text);
select is(public.save_player(pg_temp.runde('r'), 'gast-2', null, null, null, true),
  pg_temp.gemerkt('vorher')::bigint + 1,
  'save_player: Einen Gast löschen darf jedes Mitglied (Name und Farbe werden dabei nicht beachtet); liefert rev + 1');
select is(pg_temp.spieler('gast-2'), 'zweiter GAST|1|false|3|-|false|true',
  'save_player: Die Zeile bleibt mit deleted = true, sonst unverändert');
select is(pg_temp.spieler_rev('gast-2'), pg_temp.rev('r'), 'save_player stempelt die gelöschte Zeile');
select results_eq(
  format($$ select id, deleted from public.round_players where round_id = %L and rev > %s order by rev $$,
         pg_temp.runde('r'), pg_temp.gemerkt('vorher')),
  $$ values ('gast-2'::text, true) $$,
  'Die anderen Geräte erfahren vom Löschen: Die Zeile kommt mit rev > n'
);

select pg_temp.merke('vorher', pg_temp.rev('r')::text);
select is(public.save_player(pg_temp.runde('r'), 'gast-2', 'egal', 1, false, true), pg_temp.gemerkt('vorher')::bigint,
  'save_player: schon gelöscht und noch einmal löschen → nichts passiert, das aktuelle rev kommt zurück');
select is(public.save_player(pg_temp.runde('r'), 'gibt-es-nicht', 'egal', 1, false, true), pg_temp.gemerkt('vorher')::bigint,
  'save_player: einen unbekannten Spieler löschen → nichts passiert, das aktuelle rev kommt zurück');
select is(pg_temp.rev('r'), pg_temp.gemerkt('vorher')::bigint, '… und rev ist dabei nicht gestiegen');
select is(pg_temp.spieler('gibt-es-nicht'), null, '… und es ist keine Zeile entstanden');

select lives_ok(format($$ select public.save_player(%L, 'gast-4', 'Zweiter Gast', 2, false, false) $$, pg_temp.runde('r')),
  'save_player: Der Name eines gelöschten Spielers ist wieder frei');
select is(pg_temp.spieler('gast-4'), 'Zweiter Gast|2|false|6|-|false|false',
  'save_player: Der neue Gast bekommt die nächste Position (5 hat „Jörg“; gelöschte Positionen werden nicht neu vergeben)');

-- Eine gelöschte Zeile lebt wieder auf.
select throws_ok(format($$ select public.save_player(%L, 'gast-2', 'zweiter gast', 1, false, false) $$, pg_temp.runde('r')),
  'P0001', 'name_taken', 'save_player: gelöschten Spieler wiederbeleben – sein alter Name ist inzwischen vergeben → name_taken');
select lives_ok(format($$ select public.save_player(%L, 'gast-2', 'Wieder da', 9, false, false) $$, pg_temp.runde('r')),
  'save_player: gelöschten Spieler mit neuem Namen wiederbeleben');
select is(pg_temp.spieler('gast-2'), 'Wieder da|9|false|3|-|false|false',
  'save_player: Die Zeile ist wieder ein Gast, an alter Position');
select public.save_player(pg_temp.runde('r'), 'gast-2', null, null, null, true) > 0 as gast_2_wieder_geloescht;

-- claim_player belebt eine gelöschte Zeile als eigenen Spieler wieder.
select pg_temp.als('fritz');
select lives_ok(format($$ select public.claim_player(%L, 'gast-2', 'Fritz', -1) $$, pg_temp.runde('r')),
  'claim_player: Eine gelöschte Kennung wird wie ein neuer Spieler angelegt');
select is(pg_temp.spieler('gast-2'), 'Fritz|31|false|3|fritz|true|false',
  'claim_player: Die Zeile lebt als Fritz’ Spieler wieder auf (Farbe -1 wird zu 31)');
select is(pg_temp.mein_spieler('fritz'), 'gast-2', 'claim_player: Fritz ist mit dem Spieler verknüpft');

-- ---------------------------------------------------------------------------------------------------
-- save_player: nur Mitglieder
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('dora');
select throws_ok(format($$ select public.save_player(%L, 'gast-9', 'Dora', 0, false, false) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_player: kein Mitglied → not_member');
select throws_ok(format($$ select public.save_player(%L, '', '', null, null, null) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_player: kein Mitglied → not_member, auch bei ungültigen Angaben');
select throws_ok(format($$ select public.save_player(%L, 'gast-4', 'x', 0, false, true) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_player: kein Mitglied kann nichts löschen → not_member');
select throws_ok($$ select public.save_player(null, 'gast-9', 'Dora', 0, false, false) $$,
  'P0001', 'not_member', 'save_player: Runde NULL → not_member');

-- ---------------------------------------------------------------------------------------------------
-- Obergrenze: 40 nicht gelöschte Spieler je Runde
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select is(pg_temp.spielerzahl(false), 6, 'Bisher 6 nicht gelöschte Spieler');
select lives_ok(
  format($$ select public.save_player(%L, 'voll-' || n, 'Voll ' || n, n, false, false) from generate_series(7, 40) n $$,
         pg_temp.runde('r')),
  'save_player: Auffüllen auf 40 Spieler'
);
select is(pg_temp.spielerzahl(false), 40, 'Die Runde hat 40 nicht gelöschte Spieler');
select throws_ok(format($$ select public.save_player(%L, 'voll-41', 'Voll 41', 0, false, false) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'save_player: der 41. Spieler → limit_reached');
select lives_ok(format($$ select public.save_player(%L, 'voll-40', 'Voll vierzig', 1, true, false) $$, pg_temp.runde('r')),
  'save_player: Vorhandene Spieler lassen sich bei voller Runde weiter ändern');
select pg_temp.als('clara');
select throws_ok(format($$ select public.claim_player(%L, 'spieler-c', 'Clara', 0) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'claim_player: Bei voller Runde kann sich niemand NEU anlegen → limit_reached');
select lives_ok(format($$ select public.claim_player(%L, 'gast-4', 'ZWEITER Gast', 2) $$, pg_temp.runde('r')),
  'claim_player: Einen vorhandenen Gast übernehmen geht auch bei voller Runde (der eigene Name in anderer Schreibweise ist nicht „vergeben“)');
select is(pg_temp.spieler('gast-4'), 'ZWEITER Gast|2|false|6|clara|true|false', '… und Clara hat ihren Spieler');

select pg_temp.als('anna');
select public.save_player(pg_temp.runde('r'), 'voll-39', null, null, null, true) > 0 as voll_39_geloescht;
select public.save_player(pg_temp.runde('r'), 'voll-38', null, null, null, true) > 0 as voll_38_geloescht;
select lives_ok(format($$ select public.save_player(%L, 'voll-41', 'Voll 41', 0, false, false) $$, pg_temp.runde('r')),
  'save_player: Nach dem Löschen ist wieder Platz');
select lives_ok(format($$ select public.save_player(%L, 'voll-39', 'Voll 39 neu', 0, false, false) $$, pg_temp.runde('r')),
  'save_player: … auch für einen wiederbelebten Spieler');
select throws_ok(format($$ select public.save_player(%L, 'voll-38', 'Voll 38 neu', 0, false, false) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'save_player: Wiederbeleben zählt mit – bei 40 Spielern → limit_reached');
select is(pg_temp.spielerzahl(false), 40, 'Weiterhin 40 nicht gelöschte Spieler');
select is(pg_temp.spielerzahl(true), 41, 'Mit dem gelöschten sind es 41 Zeilen');

-- Zusätzliche Sicherung: höchstens 400 Zeilen einschließlich der gelöschten. Die gelöschten Zeilen legt der Test
-- als Eigentümer direkt an.
select pg_temp.als_postgres();
insert into public.round_players (round_id, id, name, color_index, position, deleted, rev)
select pg_temp.runde('r'), 'alt-' || n, 'Alt ' || n, 0, 100 + n, true, 0
from generate_series(1, 358) n;
select pg_temp.als('anna');
select public.save_player(pg_temp.runde('r'), 'voll-41', null, null, null, true) > 0 as voll_41_geloescht;
select is(pg_temp.spielerzahl(false), 39, '39 nicht gelöschte Spieler …');
select is(pg_temp.spielerzahl(true), 399, '… und 399 Zeilen insgesamt');
select lives_ok(format($$ select public.save_player(%L, 'neu-400', 'Neu 400', 0, false, false) $$, pg_temp.runde('r')),
  'save_player: Die 400. Zeile ist noch erlaubt');
select public.save_player(pg_temp.runde('r'), 'neu-400', null, null, null, true) > 0 as neu_400_geloescht;
select throws_ok(format($$ select public.save_player(%L, 'neu-401', 'Neu 401', 0, false, false) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'save_player: eine 401. Zeile (einschließlich gelöschter) → limit_reached');
select lives_ok(format($$ select public.save_player(%L, 'alt-1', 'Alt 1', 0, false, false) $$, pg_temp.runde('r')),
  'save_player: Eine gelöschte Zeile wiederbeleben geht weiterhin (keine neue Zeile)');
select is(pg_temp.spieler('neu-400'), 'Neu 400|0|false|459|-|false|true',
  'Positionen laufen weiter: die größte der Runde plus eins');

select * from finish();
rollback;
