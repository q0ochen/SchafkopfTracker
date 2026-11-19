-- pgTAP: Aufbau des Schemas der gemeinsamen Runde – Tabellen und Spalten wie im Vertrag (docs/ARCHITEKTUR.md,
-- Abschnitt 8.2), Row Level Security auf jeder Tabelle, Rechte von anon/authenticated/service_role,
-- Eigenschaften der Funktionen (SECURITY DEFINER, search_path = '', nur für authenticated).
-- Ausführen: supabase test db (oder ohne Docker: supabase/tests/lokal/pruefen.sh)
begin;
select plan(121);

-- ---------------------------------------------------------------------------------------------------
-- Tabellen, Spalten und RLS
-- ---------------------------------------------------------------------------------------------------
select tables_are(
  'public',
  array['rounds', 'round_players', 'round_members', 'round_evenings', 'round_invites'],
  'Das Schema public enthält genau die Tabellen aus dem Vertrag'
);

select is(
  (select coalesce(array_agg(c.relname::text order by c.relname), '{}')
     from pg_catalog.pg_class c
    where c.relnamespace = 'public'::regnamespace
      and c.relkind in ('r', 'p')
      and not c.relrowsecurity),
  '{}'::text[],
  'Row Level Security ist auf JEDER Tabelle im Schema public aktiv'
);

select columns_are('public', 'rounds',
  array['id', 'name', 'created_by', 'created_at', 'rev', 'rules'],
  'rounds hat genau die Spalten aus dem Vertrag');
select columns_are('public', 'round_players',
  array['round_id', 'id', 'name', 'color_index', 'archived', 'position', 'owner', 'name_locked', 'deleted', 'rev'],
  'round_players hat genau die Spalten aus dem Vertrag');
select columns_are('public', 'round_members',
  array['round_id', 'user_id', 'player_id', 'joined_at'],
  'round_members hat genau die Spalten aus dem Vertrag');
select columns_are('public', 'round_evenings',
  array['round_id', 'id', 'doc', 'version', 'deleted', 'rev', 'updated_at', 'updated_by'],
  'round_evenings hat genau die Spalten aus dem Vertrag');
select columns_are('public', 'round_invites',
  array['token_hash', 'round_id', 'created_by', 'created_at', 'expires_at'],
  'round_invites hat genau die Spalten aus dem Vertrag (kein Feld für den Token selbst)');

select col_type_is('public', 'rounds', 'id', 'uuid', 'rounds.id ist uuid');
select col_type_is('public', 'rounds', 'rev', 'bigint', 'rounds.rev ist bigint');
select col_type_is('public', 'rounds', 'rules', 'jsonb', 'rounds.rules ist jsonb');
select col_type_is('public', 'round_players', 'id', 'text', 'round_players.id ist text');
select col_type_is('public', 'round_players', 'color_index', 'integer', 'round_players.color_index ist integer');
select col_type_is('public', 'round_players', 'position', 'integer', 'round_players.position ist integer');
select col_type_is('public', 'round_players', 'rev', 'bigint', 'round_players.rev ist bigint');
select col_type_is('public', 'round_evenings', 'doc', 'jsonb', 'round_evenings.doc ist jsonb');
select col_type_is('public', 'round_evenings', 'version', 'integer', 'round_evenings.version ist integer');
select col_type_is('public', 'round_evenings', 'rev', 'bigint', 'round_evenings.rev ist bigint');
select col_type_is('public', 'round_invites', 'token_hash', 'text', 'round_invites.token_hash ist text');

select col_is_pk('public', 'rounds', 'id', 'rounds: PK (id)');
select col_is_pk('public', 'round_players', array['round_id', 'id'], 'round_players: PK (round_id, id)');
select col_is_pk('public', 'round_members', array['round_id', 'user_id'], 'round_members: PK (round_id, user_id)');
select col_is_pk('public', 'round_evenings', array['round_id', 'id'], 'round_evenings: PK (round_id, id)');
select col_is_pk('public', 'round_invites', 'token_hash', 'round_invites: PK (token_hash)');
select col_is_unique('public', 'round_members', array['round_id', 'player_id'],
  'round_members: UNIQUE (round_id, player_id) – ein Spieler gehört zu höchstens einem Mitglied');

select col_default_is('public', 'rounds', 'id', 'gen_random_uuid()', 'rounds.id: Vorgabe gen_random_uuid()');
select col_default_is('public', 'rounds', 'rev', '0', 'rounds.rev: Vorgabe 0');
select col_default_is('public', 'rounds', 'created_at', 'now()', 'rounds.created_at: Vorgabe now()');
select col_default_is('public', 'round_members', 'joined_at', 'now()', 'round_members.joined_at: Vorgabe now()');
select col_default_is('public', 'round_invites', 'created_at', 'now()', 'round_invites.created_at: Vorgabe now()');
select col_not_null('public', 'rounds', 'rev', 'rounds.rev ist Pflicht');
select col_is_null('public', 'rounds', 'rules', 'rounds.rules darf leer sein (noch kein Regelwerk)');
select col_is_null('public', 'rounds', 'created_by', 'rounds.created_by darf leer sein (Ersteller gelöscht)');
select col_is_null('public', 'round_players', 'owner', 'round_players.owner darf leer sein (Gast)');
select col_is_null('public', 'round_members', 'player_id', 'round_members.player_id darf leer sein („Das bin ich“ steht aus)');

-- Fremdschlüssel und was beim Löschen passiert: c = ON DELETE CASCADE, n = SET NULL, a = keine Aktion.
select results_eq(
  $$ select f.beziehung collate "default", f.aktion
       from (
         select format('%s(%s) → %s.%s', t.relname,
                       (select string_agg(a.attname, ', ' order by k.ord)
                          from unnest(c.conkey) with ordinality k(attnum, ord)
                          join pg_catalog.pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum),
                       fn.nspname, ft.relname) as beziehung,
                c.confdeltype::text as aktion
           from pg_catalog.pg_constraint c
           join pg_catalog.pg_class t on t.oid = c.conrelid
           join pg_catalog.pg_class ft on ft.oid = c.confrelid
           join pg_catalog.pg_namespace fn on fn.oid = ft.relnamespace
          where c.contype = 'f' and c.connamespace = 'public'::regnamespace
       ) f
      order by f.beziehung collate "C" $$,
  $$ values
       ('round_evenings(round_id) → public.rounds', 'c'),
       ('round_evenings(updated_by) → auth.users', 'n'),
       ('round_invites(created_by) → auth.users', 'c'),
       ('round_invites(round_id) → public.rounds', 'c'),
       ('round_members(round_id) → public.rounds', 'c'),
       ('round_members(round_id, player_id) → public.round_players', 'a'),
       ('round_members(user_id) → auth.users', 'c'),
       ('round_players(owner) → auth.users', 'n'),
       ('round_players(round_id) → public.rounds', 'c'),
       ('rounds(created_by) → auth.users', 'n') $$,
  'Fremdschlüssel wie im Vertrag: CASCADE für Runde, Mitgliedschaft und Einladung, SET NULL für created_by, owner, updated_by'
);

-- Namen sind je Runde eindeutig ohne Groß/Klein, aber nur unter den nicht gelöschten Spielern.
select is(
  (select count(*)::int
     from pg_catalog.pg_index i
     join pg_catalog.pg_class c on c.oid = i.indexrelid
    where i.indrelid = 'public.round_players'::regclass
      and i.indisunique
      and pg_catalog.pg_get_indexdef(i.indexrelid) ~ '\(round_id, lower\(name\)\) WHERE \(NOT deleted\)$'),
  1,
  'round_players: eindeutiger Index auf (round_id, lower(name)) für nicht gelöschte Zeilen'
);
select is(
  (select count(*)::int
     from pg_catalog.pg_index i
    where i.indrelid = 'public.round_evenings'::regclass
      and i.indisunique
      and pg_catalog.pg_get_indexdef(i.indexrelid) ~ '\(round_id, rev\)$'),
  1,
  'round_evenings: eindeutiger Index auf (round_id, rev) für das seitenweise Lesen'
);

-- Die Grenzen der Spalten (als Eigentümer direkt geprüft; Clients können ohnehin nicht direkt schreiben).
insert into public.rounds (id, name) values ('00000000-0000-4000-8000-000000000001', 'Prüfrunde');
select throws_ok($$ insert into public.rounds (name) values ('') $$, '23514', null,
  'rounds.name: leer wird abgelehnt');
select throws_ok($$ insert into public.rounds (name) values (repeat('x', 41)) $$, '23514', null,
  'rounds.name: mehr als 40 Zeichen werden abgelehnt');
select throws_ok($$ insert into public.rounds (name, rules) values ('x', '[1]') $$, '23514', null,
  'rounds.rules: muss ein JSON-Objekt sein');
select throws_ok(
  $$ insert into public.round_players (round_id, id, name, color_index, position, rev)
     values ('00000000-0000-4000-8000-000000000001', 's1', repeat('x', 25), 0, 1, 1) $$,
  '23514', null, 'round_players.name: mehr als 24 Zeichen werden abgelehnt');
select throws_ok(
  $$ insert into public.round_players (round_id, id, name, color_index, position, rev)
     values ('00000000-0000-4000-8000-000000000001', repeat('s', 65), 'x', 0, 1, 1) $$,
  '23514', null, 'round_players.id: mehr als 64 Zeichen werden abgelehnt');
select throws_ok(
  $$ insert into public.round_players (round_id, id, name, color_index, position, rev)
     values ('00000000-0000-4000-8000-000000000001', 's1', 'x', 32, 1, 1) $$,
  '23514', null, 'round_players.color_index: nur 0 bis 31');
select throws_ok(
  $$ insert into public.round_evenings (round_id, id, doc, version, rev)
     values ('00000000-0000-4000-8000-000000000001', 'a1', '{"id": "a2"}', 1, 1) $$,
  '23514', null, 'round_evenings.doc: "id" muss zur Kennung passen');
select throws_ok(
  $$ insert into public.round_evenings (round_id, id, doc, version, rev)
     values ('00000000-0000-4000-8000-000000000001', 'a1', '["a1"]', 1, 1) $$,
  '23514', null, 'round_evenings.doc: muss ein JSON-Objekt sein');
select throws_ok(
  $$ insert into public.round_evenings (round_id, id, doc, version, rev)
     values ('00000000-0000-4000-8000-000000000001', 'a1', '{"id": "a1"}', 0, 1) $$,
  '23514', null, 'round_evenings.version: beginnt bei 1');
delete from public.rounds where id = '00000000-0000-4000-8000-000000000001';

-- ---------------------------------------------------------------------------------------------------
-- anon: keinerlei Tabellenrechte
-- ---------------------------------------------------------------------------------------------------
select table_privs_are('public', 'rounds', 'anon', '{}', 'anon: keine Rechte auf rounds');
select table_privs_are('public', 'round_players', 'anon', '{}', 'anon: keine Rechte auf round_players');
select table_privs_are('public', 'round_members', 'anon', '{}', 'anon: keine Rechte auf round_members');
select table_privs_are('public', 'round_evenings', 'anon', '{}', 'anon: keine Rechte auf round_evenings');
select table_privs_are('public', 'round_invites', 'anon', '{}', 'anon: keine Rechte auf round_invites');
select is(
  (select count(*)::int
     from information_schema.column_privileges p
    where p.table_schema in ('public', 'private') and p.grantee in ('anon', 'PUBLIC', 'service_role')),
  0,
  'anon/PUBLIC/service_role: auch keine Spaltenrechte'
);

-- ---------------------------------------------------------------------------------------------------
-- authenticated: vier Tabellen nur lesen, round_invites gar nicht
-- ---------------------------------------------------------------------------------------------------
select table_privs_are('public', 'rounds', 'authenticated', array['SELECT'], 'authenticated: rounds nur lesen');
select table_privs_are('public', 'round_players', 'authenticated', array['SELECT'], 'authenticated: round_players nur lesen');
select table_privs_are('public', 'round_members', 'authenticated', array['SELECT'], 'authenticated: round_members nur lesen');
select table_privs_are('public', 'round_evenings', 'authenticated', array['SELECT'], 'authenticated: round_evenings nur lesen');
select table_privs_are('public', 'round_invites', 'authenticated', '{}', 'authenticated: kein Zugriff auf round_invites');
select is(
  (select coalesce(array_agg(distinct p.privilege_type::text), '{}')
     from information_schema.column_privileges p
    where p.table_schema = 'public' and p.grantee = 'authenticated'),
  array['SELECT'],
  'authenticated: auch spaltenweise nur SELECT'
);
select is(
  (select count(*)::int
     from information_schema.column_privileges p
    where p.table_schema = 'public' and p.table_name = 'round_invites' and p.grantee = 'authenticated'),
  0,
  'authenticated: keine Spalte von round_invites lesbar'
);

-- service_role wird nicht gebraucht und bekommt nichts.
select table_privs_are('public', 'rounds', 'service_role', '{}', 'service_role: keine Rechte auf rounds');
select table_privs_are('public', 'round_players', 'service_role', '{}', 'service_role: keine Rechte auf round_players');
select table_privs_are('public', 'round_members', 'service_role', '{}', 'service_role: keine Rechte auf round_members');
select table_privs_are('public', 'round_evenings', 'service_role', '{}', 'service_role: keine Rechte auf round_evenings');
select table_privs_are('public', 'round_invites', 'service_role', '{}', 'service_role: keine Rechte auf round_invites');

-- ---------------------------------------------------------------------------------------------------
-- Richtlinien: je lesbarer Tabelle genau eine, nur SELECT, nur authenticated; round_invites ohne
-- ---------------------------------------------------------------------------------------------------
select policies_are('public', 'rounds', array['Runden lesen: nur Mitglieder'], 'rounds: genau eine Richtlinie');
select policies_are('public', 'round_players', array['Spieler lesen: nur Mitglieder der Runde'], 'round_players: genau eine Richtlinie');
select policies_are('public', 'round_members', array['Mitglieder lesen: nur Mitglieder der Runde'], 'round_members: genau eine Richtlinie');
select policies_are('public', 'round_evenings', array['Abende lesen: nur Mitglieder der Runde'], 'round_evenings: genau eine Richtlinie');
select policies_are('public', 'round_invites', '{}'::name[], 'round_invites: keine Richtlinie (nur über Funktionen)');
select results_eq(
  $$ select p.tablename::text collate "default", p.cmd::text,
            array_to_string(p.roles, ', ') collate "default", p.permissive::text
       from pg_catalog.pg_policies p where p.schemaname = 'public' order by p.tablename $$,
  $$ values ('round_evenings', 'SELECT', 'authenticated', 'PERMISSIVE'),
            ('round_members', 'SELECT', 'authenticated', 'PERMISSIVE'),
            ('round_players', 'SELECT', 'authenticated', 'PERMISSIVE'),
            ('rounds', 'SELECT', 'authenticated', 'PERMISSIVE') $$,
  'Alle Richtlinien: nur SELECT und nur für authenticated – kein INSERT/UPDATE/DELETE für irgendwen'
);
select is(
  (select count(*)::int from pg_catalog.pg_policies p
    where p.schemaname = 'public' and p.qual !~ 'is_round_member\((round_)?id\)'),
  0,
  'Jede Richtlinie prüft die Mitgliedschaft mit is_round_member'
);

-- ---------------------------------------------------------------------------------------------------
-- Funktionen: SECURITY DEFINER, search_path = '', Rückgabetypen wie im Vertrag
-- ---------------------------------------------------------------------------------------------------
select is_definer('public', 'is_round_member', array['uuid'], 'is_round_member ist SECURITY DEFINER');
select is_definer('public', 'create_round', array['text'], 'create_round ist SECURITY DEFINER');
select is_definer('public', 'my_rounds', array[]::text[], 'my_rounds ist SECURITY DEFINER');
select is_definer('public', 'create_invite', array['uuid'], 'create_invite ist SECURITY DEFINER');
select is_definer('public', 'invite_info', array['text'], 'invite_info ist SECURITY DEFINER');
select is_definer('public', 'join_round', array['text'], 'join_round ist SECURITY DEFINER');
select is_definer('public', 'leave_round', array['uuid'], 'leave_round ist SECURITY DEFINER');
select is_definer('public', 'claim_player', array['uuid', 'text', 'text', 'integer'], 'claim_player ist SECURITY DEFINER');
select is_definer('public', 'save_player', array['uuid', 'text', 'text', 'integer', 'boolean', 'boolean'], 'save_player ist SECURITY DEFINER');
select is_definer('public', 'save_rules', array['uuid', 'jsonb'], 'save_rules ist SECURITY DEFINER');
select is_definer('public', 'save_evening', array['uuid', 'text', 'jsonb', 'integer', 'boolean'], 'save_evening ist SECURITY DEFINER');
select is_definer('private', 'handle_user_deleted', array[]::text[], 'handle_user_deleted ist SECURITY DEFINER (läuft für den Auth-Server)');

select function_returns('public', 'is_round_member', array['uuid'], 'boolean', 'is_round_member liefert boolean');
select function_returns('public', 'create_round', array['text'], 'uuid', 'create_round liefert die Kennung der Runde');
select function_returns('public', 'my_rounds', array[]::text[], 'setof record', 'my_rounds liefert eine Tabelle');
select function_returns('public', 'create_invite', array['uuid'], 'text', 'create_invite liefert den Token');
select function_returns('public', 'invite_info', array['text'], 'setof record', 'invite_info liefert eine Tabelle');
select function_returns('public', 'join_round', array['text'], 'uuid', 'join_round liefert die Kennung der Runde');
select function_returns('public', 'leave_round', array['uuid'], 'void', 'leave_round liefert nichts');
select function_returns('public', 'claim_player', array['uuid', 'text', 'text', 'integer'], 'void', 'claim_player liefert nichts');
select function_returns('public', 'save_player', array['uuid', 'text', 'text', 'integer', 'boolean', 'boolean'], 'bigint', 'save_player liefert das neue rev');
select function_returns('public', 'save_rules', array['uuid', 'jsonb'], 'bigint', 'save_rules liefert das neue rev');
select function_returns('public', 'save_evening', array['uuid', 'text', 'jsonb', 'integer', 'boolean'], 'setof record', 'save_evening liefert eine Tabelle');

-- Namen der Parameter und der Ergebnisspalten: So ruft PostgREST die Funktionen auf (JSON-Schlüssel).
select results_eq(
  $$ select p.proname::text collate "default", pg_catalog.pg_get_function_arguments(p.oid),
            pg_catalog.pg_get_function_result(p.oid)
       from pg_catalog.pg_proc p
      where p.pronamespace = 'public'::regnamespace
      order by p.proname $$,
  $$ values
       ('claim_player', 'p_round uuid, p_player text, p_name text, p_color_index integer', 'void'),
       ('create_invite', 'p_round uuid', 'text'),
       ('create_round', 'p_name text', 'uuid'),
       ('invite_info', 'p_token text', 'TABLE(round_id uuid, round_name text, member_count integer, already_member boolean)'),
       ('is_round_member', 'p_round uuid', 'boolean'),
       ('join_round', 'p_token text', 'uuid'),
       ('leave_round', 'p_round uuid', 'void'),
       ('my_rounds', '', 'TABLE(id uuid, name text, rev bigint, member_count integer, my_player_id text, is_creator boolean)'),
       ('save_evening', 'p_round uuid, p_id text, p_doc jsonb, p_expected_version integer, p_deleted boolean', 'TABLE(version integer, rev bigint)'),
       ('save_player', 'p_round uuid, p_id text, p_name text, p_color_index integer, p_archived boolean, p_deleted boolean', 'bigint'),
       ('save_rules', 'p_round uuid, p_rules jsonb', 'bigint') $$,
  'Das Schema public enthält genau die Funktionen aus dem Vertrag, mit genau diesen Parametern und Ergebnissen'
);

select volatility_is('public', 'is_round_member', array['uuid'], 'stable', 'is_round_member liest nur');
select volatility_is('public', 'my_rounds', array[]::text[], 'stable', 'my_rounds liest nur');
select volatility_is('public', 'invite_info', array['text'], 'stable', 'invite_info liest nur');

select is(
  (select coalesce(array_agg(format('%s.%s', n.nspname, p.proname) order by n.nspname, p.proname), '{}')
     from pg_catalog.pg_proc p
     join pg_catalog.pg_namespace n on n.oid = p.pronamespace
    where n.nspname in ('public', 'private')
      and not coalesce('search_path=""' = any (p.proconfig), false)),
  '{}'::text[],
  'Jede Funktion in public und private hat search_path = '''''
);

-- ---------------------------------------------------------------------------------------------------
-- Ausführungsrechte
-- ---------------------------------------------------------------------------------------------------
select function_privs_are('public', 'is_round_member', array['uuid'], 'authenticated', array['EXECUTE'], 'authenticated darf is_round_member (für die Richtlinien)');
select function_privs_are('public', 'create_round', array['text'], 'authenticated', array['EXECUTE'], 'authenticated darf create_round');
select function_privs_are('public', 'my_rounds', array[]::text[], 'authenticated', array['EXECUTE'], 'authenticated darf my_rounds');
select function_privs_are('public', 'create_invite', array['uuid'], 'authenticated', array['EXECUTE'], 'authenticated darf create_invite');
select function_privs_are('public', 'invite_info', array['text'], 'authenticated', array['EXECUTE'], 'authenticated darf invite_info');
select function_privs_are('public', 'join_round', array['text'], 'authenticated', array['EXECUTE'], 'authenticated darf join_round');
select function_privs_are('public', 'leave_round', array['uuid'], 'authenticated', array['EXECUTE'], 'authenticated darf leave_round');
select function_privs_are('public', 'claim_player', array['uuid', 'text', 'text', 'integer'], 'authenticated', array['EXECUTE'], 'authenticated darf claim_player');
select function_privs_are('public', 'save_player', array['uuid', 'text', 'text', 'integer', 'boolean', 'boolean'], 'authenticated', array['EXECUTE'], 'authenticated darf save_player');
select function_privs_are('public', 'save_rules', array['uuid', 'jsonb'], 'authenticated', array['EXECUTE'], 'authenticated darf save_rules');
select function_privs_are('public', 'save_evening', array['uuid', 'text', 'jsonb', 'integer', 'boolean'], 'authenticated', array['EXECUTE'], 'authenticated darf save_evening');

select is(
  (select coalesce(array_agg(format('%s.%s', n.nspname, p.proname) order by n.nspname, p.proname), '{}')
     from pg_catalog.pg_proc p
     join pg_catalog.pg_namespace n on n.oid = p.pronamespace
    where n.nspname in ('public', 'private')
      and (pg_catalog.has_function_privilege('anon', p.oid, 'EXECUTE')
           or pg_catalog.has_function_privilege('public', p.oid, 'EXECUTE')
           or pg_catalog.has_function_privilege('service_role', p.oid, 'EXECUTE'))),
  '{}'::text[],
  'anon/PUBLIC/service_role dürfen keine Funktion in public oder private ausführen'
);
select is(
  (select coalesce(array_agg(p.proname::text order by p.proname), '{}')
     from pg_catalog.pg_proc p
    where p.pronamespace = 'public'::regnamespace
      and pg_catalog.has_function_privilege('authenticated', p.oid, 'EXECUTE')),
  array['claim_player', 'create_invite', 'create_round', 'invite_info', 'is_round_member', 'join_round',
        'leave_round', 'my_rounds', 'save_evening', 'save_player', 'save_rules'],
  'authenticated darf in public genau die zehn Funktionen aus dem Vertrag und is_round_member ausführen'
);
select is(
  (select coalesce(array_agg(p.proname::text order by p.proname), '{}')
     from pg_catalog.pg_proc p
    where p.pronamespace = 'private'::regnamespace
      and pg_catalog.has_function_privilege('authenticated', p.oid, 'EXECUTE')),
  '{}'::text[],
  'authenticated darf keine Funktion in private ausführen'
);

-- ---------------------------------------------------------------------------------------------------
-- Schema private, Trigger, keine weiteren Abhängigkeiten
-- ---------------------------------------------------------------------------------------------------
select schema_privs_are('private', 'authenticated', '{}', 'authenticated hat keinen Zugriff auf das Schema private');
select schema_privs_are('private', 'anon', '{}', 'anon hat keinen Zugriff auf das Schema private');
select schema_privs_are('private', 'service_role', '{}', 'service_role hat keinen Zugriff auf das Schema private');
select tables_are('private', '{}'::name[], 'Das Schema private enthält keine Tabellen');
select has_trigger('auth', 'users', 'on_auth_user_deleted', 'Trigger räumt die Runden eines gelöschten Kontos auf');
select is(
  (select count(*)::int from pg_catalog.pg_trigger t
     join pg_catalog.pg_class c on c.oid = t.tgrelid
    where c.relnamespace = 'public'::regnamespace and not t.tgisinternal),
  0,
  'Auf den Tabellen in public liegen keine eigenen Trigger (geschrieben wird nur über die Funktionen)'
);
select is(
  (select count(*)::int from pg_catalog.pg_class c
    where c.relnamespace in ('public'::regnamespace, 'private'::regnamespace) and c.relkind in ('S', 'v', 'm')),
  0,
  'Keine Sequenzen und keine Sichten in public und private'
);
select is(
  (select count(*)::int
     from pg_catalog.pg_proc p
    where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace)
      and p.prosrc ~* '(gen_random_bytes|digest|hmac|crypt)\s*\('),
  0,
  'Keine Funktion braucht pgcrypto (Token aus gen_random_uuid(), Hash mit sha256())'
);

select * from finish();
rollback;
