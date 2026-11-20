-- pgTAP: Abende und Regelwerk – save_evening („Vergleichen und Ersetzen“: erwartete Version 0 für neu,
-- falsche Version → leere Antwort und nichts geändert, Löschen als Markierung, invalid_doc, too_large,
-- Obergrenze 3000), save_rules, und die Zählung über rev: Jede Änderung erhöht rounds.rev um genau eins
-- und stempelt die geänderte Zeile; was nur liest oder abgewiesen wird, ändert rev nicht.
begin;
select plan(81);

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

-- Eine Abend-Zeile als Text (am RLS vorbei): Version|gelöscht|rev|zuletzt geändert von|Dokument.
create function pg_temp.abend(kennung text, was text default 'r') returns text
language sql stable security definer as $$
  select concat_ws('|', e.version, e.deleted::text, e.rev,
                   coalesce((select k from unnest(array['anna', 'ben', 'clara', 'dora']) k
                              where pg_temp.id(k) = e.updated_by), '-'),
                   e.doc::text)
    from public.round_evenings e
   where e.round_id = pg_temp.runde(was) and e.id = kennung
$$;
create function pg_temp.abendzahl() returns int language sql stable security definer as $$
  select count(*)::int from public.round_evenings e where e.round_id = pg_temp.runde('r')
$$;
-- Ein Dokument {"f": "xxx…", "id": "gross"} mit genau so vielen Bytes (als Text, wie PostgreSQL es ausgibt).
create function pg_temp.dokument(bytes int) returns jsonb language sql immutable as $$
  select jsonb_build_object('id', 'gross', 'f',
    repeat('x', bytes - octet_length(jsonb_build_object('id', 'gross', 'f', '')::text)))
$$;
-- Protokoll für den letzten Abschnitt: nach jedem Schritt das rev der Runde „s“.
create temp table protokoll (nr int generated always as identity primary key, schritt text not null, rev bigint);
grant all on protokoll to public;
create function pg_temp.notiere(was text) returns void language sql as $$
  insert into pg_temp.protokoll (schritt, rev) values (was, pg_temp.rev('s'))
$$;
grant execute on all functions in schema pg_temp to public;

select pg_temp.nutzer('anna');
select pg_temp.nutzer('ben');
select pg_temp.nutzer('dora');

select pg_temp.als('anna');
select pg_temp.merke('r', public.create_round('Stammtisch')::text);
select pg_temp.merke('token', public.create_invite(pg_temp.runde('r')));
select pg_temp.als('ben');
select public.join_round(pg_temp.gemerkt('token')) is not null as beigetreten;
select is(pg_temp.rev('r'), 2::bigint, 'Ausgangslage: zwei Mitglieder, rev = 2');

-- ---------------------------------------------------------------------------------------------------
-- save_evening: neuer Abend (erwartete Version 0)
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select results_eq(
  format($$ select version, rev from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "spiele": []}', 0, false) $$,
         pg_temp.runde('r')),
  $$ values (1, 3::bigint) $$,
  'save_evening: neuer Abend mit erwarteter Version 0 → Version 1 und das neue rev'
);
select is(pg_temp.abend('abend-1'), '1|false|3|anna|{"id": "abend-1", "spiele": []}',
  'save_evening: Dokument gespeichert, Version 1, mit rev gestempelt, updated_by = Aufruferin');
select is(pg_temp.rev('r'), 3::bigint, 'save_evening erhöht rev um genau eins');
select is(
  (select updated_at from public.round_evenings where id = 'abend-1'), now(),
  'save_evening setzt updated_at'
);

select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-2', '{"id": "abend-2"}', 1, false) $$, pg_temp.runde('r')),
  'save_evening: neuer Abend mit erwarteter Version 1 → leere Antwort (kein Fehler)'
);
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-2', '{"id": "abend-2"}', null, false) $$, pg_temp.runde('r')),
  'save_evening: neuer Abend ohne erwartete Version (NULL) → leere Antwort'
);
select is(pg_temp.abend('abend-2'), null, '… und der Abend wurde nicht angelegt');
select is(pg_temp.rev('r'), 3::bigint, '… und rev ist nicht gestiegen');

-- ---------------------------------------------------------------------------------------------------
-- save_evening: vorhandener Abend – nur mit der richtigen Version
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "ben"}', 0, false) $$, pg_temp.runde('r')),
  'save_evening: Den Abend gibt es schon – erwartete Version 0 → leere Antwort'
);
select is(pg_temp.abend('abend-1'), '1|false|3|anna|{"id": "abend-1", "spiele": []}',
  'Falsche Version: Dokument, Version, rev und updated_by sind unverändert');
select results_eq(
  format($$ select version, rev from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "ben"}', 1, false) $$,
         pg_temp.runde('r')),
  $$ values (2, 4::bigint) $$,
  'save_evening: Mit der richtigen Version speichert auch Ben (jeder darf eintragen) → Version 2'
);
select is(pg_temp.abend('abend-1'), '2|false|4|ben|{"id": "abend-1", "von": "ben"}',
  'save_evening: Dokument ersetzt, Version 2, neu gestempelt, updated_by = Ben');

select pg_temp.als('anna');
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "anna"}', 1, false) $$, pg_temp.runde('r')),
  'save_evening: Anna kommt mit der alten Version 1 zu spät → leere Antwort'
);
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "anna"}', 3, false) $$, pg_temp.runde('r')),
  'save_evening: eine zu hohe Version → leere Antwort'
);
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "anna"}', null, false) $$, pg_temp.runde('r')),
  'save_evening: Version NULL → leere Antwort'
);
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "anna"}', -1, true) $$, pg_temp.runde('r')),
  'save_evening: Version -1 → leere Antwort'
);
select is(pg_temp.abend('abend-1'), '2|false|4|ben|{"id": "abend-1", "von": "ben"}',
  'Nach vier abgewiesenen Versuchen ist am Abend nichts geändert');
select is(pg_temp.rev('r'), 4::bigint, '… und rev ist nicht gestiegen');
-- So liest die App den Abend danach einzeln neu und versucht es mit der Version des Servers erneut.
select results_eq(
  format($$ select id, doc, version, deleted, rev from public.round_evenings where round_id = %L and id = 'abend-1' $$,
         pg_temp.runde('r')),
  $$ values ('abend-1'::text, '{"id": "abend-1", "von": "ben"}'::jsonb, 2, false, 4::bigint) $$,
  'Der Abend lässt sich einzeln neu lesen (id, doc, version, deleted, rev)'
);

-- ---------------------------------------------------------------------------------------------------
-- save_evening: Löschen ist eine Markierung, das Dokument bleibt wie übergeben
-- ---------------------------------------------------------------------------------------------------
select results_eq(
  format($$ select version, rev from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "letzter": "stand"}', 2, true) $$,
         pg_temp.runde('r')),
  $$ values (3, 5::bigint) $$,
  'save_evening: Löschen mit der richtigen Version → Version 3'
);
select is(pg_temp.abend('abend-1'), '3|true|5|anna|{"id": "abend-1", "letzter": "stand"}',
  'save_evening: Die Zeile bleibt mit deleted = true und dem übergebenen Dokument');
select pg_temp.als('ben');
select is_empty(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "von": "ben"}', 2, false) $$, pg_temp.runde('r')),
  'save_evening: Wer den gelöschten Abend mit alter Version ändern will, bekommt eine leere Antwort'
);
select results_eq(
  format($$ select version, rev from public.save_evening(%L, 'abend-1', '{"id": "abend-1", "wieder": "da"}', 3, null) $$,
         pg_temp.runde('r')),
  $$ values (4, 6::bigint) $$,
  'save_evening: Mit der Version des Servers lässt sich der Abend wieder anlegen (deleted NULL gilt als false)'
);
select is(pg_temp.abend('abend-1'), '4|false|6|ben|{"id": "abend-1", "wieder": "da"}',
  'save_evening: Der Abend ist wieder da, Version 4');
select results_eq(
  format($$ select version, rev from public.save_evening(%L, 'abend-3', '{"id": "abend-3"}', 0, true) $$, pg_temp.runde('r')),
  $$ values (1, 7::bigint) $$,
  'save_evening: Ein unbekannter Abend lässt sich auch gleich als gelöscht anlegen (Version 1)'
);
select is(pg_temp.abend('abend-3'), '1|true|7|ben|{"id": "abend-3"}', '… als Zeile mit deleted = true');

-- ---------------------------------------------------------------------------------------------------
-- save_evening: invalid_doc
-- ---------------------------------------------------------------------------------------------------
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', null, 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: Dokument NULL → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', 'null', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: JSON-null → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', '[{"id": "abend-4"}]', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: JSON-Liste statt Objekt → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', '"abend-4"', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: JSON-Text statt Objekt → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', '{}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: Objekt ohne "id" → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', '{"id": "abend-5"}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: "id" passt nicht zu p_id → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, 'abend-4', '{"id": null}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: "id" ist null → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, '4', '{"id": 4}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: "id" ist eine Zahl statt Text → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, '', '{"id": ""}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: leere Kennung → invalid_doc');
select throws_ok(
  format($$ select * from public.save_evening(%L, repeat('a', 65), jsonb_build_object('id', repeat('a', 65)), 0, false) $$,
         pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: Kennung mit 65 Zeichen → invalid_doc');
select throws_ok(format($$ select * from public.save_evening(%L, null, '{"id": "abend-4"}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: Kennung NULL → invalid_doc');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "anders"}', 4, false) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_evening: Auch beim Ändern muss "id" zur Kennung passen → invalid_doc');
select results_eq(
  format($$ select version from public.save_evening(%L, repeat('a', 64), jsonb_build_object('id', repeat('a', 64)), 0, false) $$,
         pg_temp.runde('r')),
  $$ values (1) $$,
  'save_evening: Eine Kennung mit 64 Zeichen ist erlaubt'
);
select is(pg_temp.abendzahl(), 3, 'Abgewiesene Dokumente legen keinen Abend an');

-- ---------------------------------------------------------------------------------------------------
-- save_evening: too_large (über 300 000 Bytes, gemessen am Text, wie PostgreSQL das Dokument ausgibt)
-- ---------------------------------------------------------------------------------------------------
select is(octet_length(pg_temp.dokument(300000)::text), 300000, 'Das Prüfdokument hat genau 300 000 Bytes');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'gross', pg_temp.dokument(300001), 0, false) $$, pg_temp.runde('r')),
  'P0001', 'too_large', 'save_evening: 300 001 Bytes → too_large');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'gross', jsonb_build_object('id', 'gross', 'f', repeat('ä', 150000)), 0, false) $$,
         pg_temp.runde('r')),
  'P0001', 'too_large', 'save_evening: Gezählt werden Bytes, nicht Zeichen (150 000 Umlaute sind zu groß)');
select results_eq(
  format($$ select version from public.save_evening(%L, 'gross', pg_temp.dokument(300000), 0, false) $$, pg_temp.runde('r')),
  $$ values (1) $$,
  'save_evening: genau 300 000 Bytes sind erlaubt'
);
select throws_ok(
  format($$ select * from public.save_evening(%L, 'gross', pg_temp.dokument(300001), 1, false) $$, pg_temp.runde('r')),
  'P0001', 'too_large', 'save_evening: Auch beim Ändern gilt die Grenze → too_large');

-- ---------------------------------------------------------------------------------------------------
-- save_evening: nur Mitglieder
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('dora');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'abend-9', '{"id": "abend-9"}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_evening: kein Mitglied → not_member');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'abend-1', '{"id": "abend-1"}', 4, true) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_evening: kein Mitglied kann keinen Abend ändern oder löschen → not_member');
select throws_ok(
  format($$ select * from public.save_evening(%L, '', null, null, null) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_evening: kein Mitglied → not_member, auch bei ungültigen Angaben (nicht invalid_doc)');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'gross', pg_temp.dokument(300001), 0, false) $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_evening: kein Mitglied → not_member, auch bei zu großem Dokument (nicht too_large)');
select throws_ok(
  $$ select * from public.save_evening('00000000-0000-4000-8000-000000000000', 'abend-9', '{"id": "abend-9"}', 0, false) $$,
  'P0001', 'not_member', 'save_evening: unbekannte Runde → not_member');
select throws_ok(
  $$ select * from public.save_evening(null, 'abend-9', '{"id": "abend-9"}', 0, false) $$,
  'P0001', 'not_member', 'save_evening: Runde NULL → not_member');

-- ---------------------------------------------------------------------------------------------------
-- save_rules
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('ben');
select pg_temp.merke('vorher', pg_temp.rev('r')::text);
select is(public.save_rules(pg_temp.runde('r'), '{"grundtarif": 10, "bock": {"an": true}}'),
  pg_temp.gemerkt('vorher')::bigint + 1, 'save_rules: Jedes Mitglied darf das Regelwerk setzen; liefert rev + 1');
select results_eq(
  format($$ select rules, rev from public.rounds where id = %L $$, pg_temp.runde('r')),
  $$ values ('{"grundtarif": 10, "bock": {"an": true}}'::jsonb, pg_temp.gemerkt('vorher')::bigint + 1) $$,
  'save_rules: Das Regelwerk steht im Kopf der Runde'
);
select throws_ok(format($$ select public.save_rules(%L, null) $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_rules: NULL → invalid_doc');
select throws_ok(format($$ select public.save_rules(%L, 'null') $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_rules: JSON-null → invalid_doc');
select throws_ok(format($$ select public.save_rules(%L, '[]') $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_rules: JSON-Liste → invalid_doc');
select throws_ok(format($$ select public.save_rules(%L, '"regeln"') $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_rules: JSON-Text → invalid_doc');
select throws_ok(format($$ select public.save_rules(%L, '10') $$, pg_temp.runde('r')),
  'P0001', 'invalid_doc', 'save_rules: JSON-Zahl → invalid_doc');
select throws_ok(
  format($$ select public.save_rules(%L, jsonb_build_object('f', repeat('x', 20000))) $$, pg_temp.runde('r')),
  'P0001', 'too_large', 'save_rules: mehr als 20 000 Bytes → too_large');
select lives_ok(
  format($$ select public.save_rules(%L, jsonb_build_object('f', repeat('x', 20000 - 9))) $$, pg_temp.runde('r')),
  'save_rules: genau 20 000 Bytes sind erlaubt');
select lives_ok(format($$ select public.save_rules(%L, '{}') $$, pg_temp.runde('r')),
  'save_rules: Ein leeres Objekt ist erlaubt');
select is((select rules from public.rounds where id = pg_temp.runde('r')), '{}'::jsonb, '… und gespeichert');
select pg_temp.als('dora');
select throws_ok(format($$ select public.save_rules(%L, '{"grundtarif": 20}') $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_rules: kein Mitglied → not_member');
select throws_ok(format($$ select public.save_rules(%L, '[]') $$, pg_temp.runde('r')),
  'P0001', 'not_member', 'save_rules: kein Mitglied → not_member, auch bei ungültigem Regelwerk');
select throws_ok($$ select public.save_rules('00000000-0000-4000-8000-000000000000', '{}') $$,
  'P0001', 'not_member', 'save_rules: unbekannte Runde → not_member');

-- ---------------------------------------------------------------------------------------------------
-- Obergrenze: 3000 Abende je Runde (gelöschte zählen mit)
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select is(pg_temp.abendzahl(), 4, 'Bisher 4 Abende (einer davon gelöscht)');
select pg_temp.merke('vorher', pg_temp.rev('r')::text);
select is(
  (select count(*)::int
     from generate_series(5, 3000) n
     cross join lateral public.save_evening(pg_temp.runde('r'), 'voll-' || n,
                                            jsonb_build_object('id', 'voll-' || n, 'nr', n), 0, false)),
  2996,
  'save_evening: Auffüllen auf 3000 Abende'
);
select is(pg_temp.abendzahl(), 3000, 'Die Runde hat 3000 Abende');
select is(pg_temp.rev('r'), pg_temp.gemerkt('vorher')::bigint + 2996, 'Jeder neue Abend hat rev um genau eins erhöht');
select throws_ok(
  format($$ select * from public.save_evening(%L, 'voll-3001', '{"id": "voll-3001"}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'save_evening: der 3001. Abend → limit_reached');
select results_eq(
  format($$ select version from public.save_evening(%L, 'voll-3000', '{"id": "voll-3000", "geändert": true}', 1, false) $$,
         pg_temp.runde('r')),
  $$ values (2) $$,
  'save_evening: Vorhandene Abende lassen sich bei voller Runde weiter ändern'
);
select results_eq(
  format($$ select version from public.save_evening(%L, 'voll-2999', '{"id": "voll-2999"}', 1, true) $$, pg_temp.runde('r')),
  $$ values (2) $$,
  'save_evening: … und löschen'
);
select throws_ok(
  format($$ select * from public.save_evening(%L, 'voll-3001', '{"id": "voll-3001"}', 0, false) $$, pg_temp.runde('r')),
  'P0001', 'limit_reached', 'save_evening: Ein gelöschter Abend macht keinen Platz (die Zeile bleibt) → limit_reached');

-- So blättert die App: rev > n, nach rev sortiert, je 100 Zeilen, weiter mit dem größten gelesenen rev.
select is(
  (with recursive seiten as (
     select 0 as seite, 0::bigint as bis, 0 as zeilen, 0 as gesamt
     union all
     select s.seite + 1, n.bis, n.zeilen, s.gesamt + n.zeilen
       from seiten s
       cross join lateral (
         select coalesce(max(x.rev), s.bis) as bis, count(*)::int as zeilen
           from (select e.rev from public.round_evenings e
                  where e.round_id = pg_temp.runde('r') and e.rev > s.bis
                  order by e.rev limit 100) x
       ) n
      where s.seite = 0 or s.zeilen = 100
   )
   select max(gesamt) from seiten),
  3000,
  'Seitenweise gelesen (rev > n, je 100) kommt jeder der 3000 Abende genau einmal'
);
select is(
  (select count(distinct e.rev)::int from public.round_evenings e where e.round_id = pg_temp.runde('r')), 3000,
  'Jeder Abend trägt ein eigenes rev');
select is(
  (select max(e.rev) from public.round_evenings e where e.round_id = pg_temp.runde('r')), pg_temp.rev('r'),
  'Das größte rev eines Abends ist das rev der Runde (die letzte Änderung war ein Abend)');

-- ---------------------------------------------------------------------------------------------------
-- rev zählt jede Änderung: eine frische Runde, Schritt für Schritt
-- ---------------------------------------------------------------------------------------------------
select pg_temp.als('anna');
select pg_temp.merke('s', public.create_round('Schritt für Schritt')::text);
select pg_temp.notiere('create_round');
select count(*) from public.my_rounds();
select pg_temp.notiere('my_rounds (liest nur)');
select pg_temp.merke('token_s', public.create_invite(pg_temp.runde('s')));
select pg_temp.notiere('create_invite (ändert rev nicht)');
select pg_temp.als('ben');
select count(*) from public.invite_info(pg_temp.gemerkt('token_s'));
select pg_temp.notiere('invite_info (liest nur)');
select public.join_round(pg_temp.gemerkt('token_s')) is not null;
select pg_temp.notiere('join_round');
select public.join_round(pg_temp.gemerkt('token_s')) is not null;
select pg_temp.notiere('join_round (schon Mitglied)');
select public.claim_player(pg_temp.runde('s'), 'spieler-b', 'Ben', 1);
select pg_temp.notiere('claim_player');
select public.save_player(pg_temp.runde('s'), 'gast', 'Gast', 2, false, false) is not null;
select pg_temp.notiere('save_player (neu)');
select public.save_rules(pg_temp.runde('s'), '{"grundtarif": 10}') is not null;
select pg_temp.notiere('save_rules');
select count(*) from public.save_evening(pg_temp.runde('s'), 'abend', '{"id": "abend"}', 0, false);
select pg_temp.notiere('save_evening (neu)');
select count(*) from public.save_evening(pg_temp.runde('s'), 'abend', '{"id": "abend"}', 0, false);
select pg_temp.notiere('save_evening (falsche Version)');
select count(*) from public.save_evening(pg_temp.runde('s'), 'abend', '{"id": "abend", "n": 2}', 1, false);
select pg_temp.notiere('save_evening (ändern)');
select public.save_player(pg_temp.runde('s'), 'unbekannt', 'x', 0, false, true) is not null;
select pg_temp.notiere('save_player (unbekannten löschen)');
select public.save_player(pg_temp.runde('s'), 'gast', 'Gast', 2, false, true) is not null;
select pg_temp.notiere('save_player (Gast löschen)');
select throws_ok(format($$ select public.save_rules(%L, '[]') $$, pg_temp.runde('s')), 'P0001', 'invalid_doc',
  'Ein Aufruf, der mit einem Fehler endet …');
select pg_temp.notiere('save_rules (Fehler)');
select pg_temp.als('anna');
select public.leave_round(pg_temp.runde('s'));
select pg_temp.notiere('leave_round');

select pg_temp.als_postgres();
select results_eq(
  $$ select schritt, rev from pg_temp.protokoll order by nr $$,
  $$ values ('create_round', 1::bigint),
            ('my_rounds (liest nur)', 1),
            ('create_invite (ändert rev nicht)', 1),
            ('invite_info (liest nur)', 1),
            ('join_round', 2),
            ('join_round (schon Mitglied)', 2),
            ('claim_player', 3),
            ('save_player (neu)', 4),
            ('save_rules', 5),
            ('save_evening (neu)', 6),
            ('save_evening (falsche Version)', 6),
            ('save_evening (ändern)', 7),
            ('save_player (unbekannten löschen)', 7),
            ('save_player (Gast löschen)', 8),
            ('save_rules (Fehler)', 8),
            ('leave_round', 9) $$,
  'rev steigt bei jeder Änderung um genau eins – und nur dann'
);
select results_eq(
  $$ select 'Spieler ' || p.id, p.rev from public.round_players p where p.round_id = pg_temp.runde('s')
     union all
     select 'Abend ' || e.id, e.rev from public.round_evenings e where e.round_id = pg_temp.runde('s')
     order by 2 $$,
  $$ values ('Spieler spieler-b', 3::bigint), ('Abend abend', 7), ('Spieler gast', 8) $$,
  'Jede geänderte Zeile trägt das rev ihrer letzten Änderung'
);
-- Ein Gerät mit cursor = 5 liest alles mit rev > 5 und hat danach alles bis zum größten gelesenen rev.
select results_eq(
  $$ select 'Abend ' || e.id, e.rev from public.round_evenings e where e.round_id = pg_temp.runde('s') and e.rev > 5
     union all
     select 'Spieler ' || p.id, p.rev from public.round_players p where p.round_id = pg_temp.runde('s') and p.rev > 5
     order by 2 $$,
  $$ values ('Abend abend', 7::bigint), ('Spieler gast', 8) $$,
  'Wer Zeilen mit rev > 5 liest, bekommt genau die späteren Änderungen (der Spieler mit rev 3 kommt nicht noch einmal)'
);

select * from finish();
rollback;
