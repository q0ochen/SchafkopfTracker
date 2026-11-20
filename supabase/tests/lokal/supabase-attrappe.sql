-- Attrappe des Supabase-Grundgerüsts für die Prüfung OHNE Docker und ohne Supabase CLI
-- (supabase/tests/lokal/pruefen.sh). Sie bildet nur nach, worauf sich Migrationen und Tests verlassen:
-- die Rollen, das Schema auth mit auth.users, auth.uid(), auth.role() und auth.jwt(), das Schema extensions
-- und die Vorgabe-Rechte, mit denen Supabase neue Tabellen und Funktionen in public für anon, authenticated
-- und service_role freigibt. Die CI (.github/workflows/supabase.yml) nutzt diese Datei NICHT, sondern das
-- echte lokale Supabase.
--
-- Ausgeführt wird sie vom Superuser der Wegwerf-Datenbank mit der psql-Variable „attrappe“. Ohne die
-- Variable – also wenn „supabase test db“ (pg_prove) diese Datei wie einen Test aufruft – meldet sie sich als
-- übersprungen und ändert nichts.
\if :{?attrappe}
\else
  \echo 1..0 # SKIP Kein Test: Attrappe nur fuer supabase/tests/lokal/pruefen.sh
  \quit
\endif

\set ON_ERROR_STOP on
-- Keine Hinweise wie „Rolle ist schon Mitglied“, wenn die Attrappe in eine weitere Datenbank desselben Clusters kommt.
set client_min_messages = warning;

-- ---------------------------------------------------------------------------------------------------
-- Rollen (gelten für den ganzen Cluster, deshalb nur anlegen, wenn sie fehlen)
-- ---------------------------------------------------------------------------------------------------
-- postgres ist bei Supabase KEIN Superuser: Migrationen und pgTAP-Tests laufen mit genau diesen Rechten.
do $$
begin
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'postgres') then
    create role postgres login createdb createrole replication bypassrls;
  end if;
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'anon') then
    create role anon nologin noinherit;
  end if;
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticated') then
    create role authenticated nologin noinherit;
  end if;
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'service_role') then
    create role service_role nologin noinherit bypassrls;
  end if;
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'authenticator') then
    create role authenticator login noinherit;
  end if;
  -- Als diese Rolle legt der Auth-Server (GoTrue) Konten an und löscht sie; in public darf sie nichts.
  if not exists (select 1 from pg_catalog.pg_roles where rolname = 'supabase_auth_admin') then
    create role supabase_auth_admin login noinherit createrole;
  end if;
end
$$;

grant anon, authenticated, service_role to authenticator;
grant anon, authenticated, service_role to postgres;
alter role postgres set search_path to "$user", public, extensions;

-- ---------------------------------------------------------------------------------------------------
-- Diese Datenbank: Rechte von postgres und die Vorgabe-Rechte in public
-- ---------------------------------------------------------------------------------------------------
select format('grant all on database %I to postgres', current_database()) \gexec

grant usage, create on schema public to postgres;
grant usage on schema public to anon, authenticated, service_role;

-- Wie bei Supabase: Was postgres in public anlegt, dürfen anon, authenticated und service_role zunächst
-- vollständig benutzen. Die Migrationen müssen diese Rechte also selbst wieder entziehen.
alter default privileges for role postgres in schema public
  grant all on tables to postgres, anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  grant all on functions to postgres, anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  grant all on sequences to postgres, anon, authenticated, service_role;

-- ---------------------------------------------------------------------------------------------------
-- Schema extensions (dorthin legt pruefen.sh wie „supabase test db“ die Erweiterung pgTAP)
-- ---------------------------------------------------------------------------------------------------
create schema extensions;
grant usage on schema extensions to postgres, anon, authenticated, service_role;

-- ---------------------------------------------------------------------------------------------------
-- Schema auth: Konten und die Funktionen, die das Nutzer-JWT auslesen (wie bei Supabase)
-- ---------------------------------------------------------------------------------------------------
create schema auth authorization supabase_auth_admin;

create table auth.users (
  instance_id uuid,
  id uuid not null primary key,
  aud varchar(255),
  role varchar(255),
  email varchar(255),
  encrypted_password varchar(255),
  email_confirmed_at timestamptz,
  raw_app_meta_data jsonb,
  raw_user_meta_data jsonb,
  created_at timestamptz,
  updated_at timestamptz,
  deleted_at timestamptz,
  is_sso_user boolean not null default false,
  is_anonymous boolean not null default false
);
create unique index users_email_partial_key on auth.users (email) where is_sso_user = false;
alter table auth.users owner to supabase_auth_admin;

create function auth.uid() returns uuid
language sql stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  )::uuid
$$;

create function auth.role() returns text
language sql stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.role', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role')
  )::text
$$;

create function auth.jwt() returns jsonb
language sql stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim', true), ''),
    nullif(current_setting('request.jwt.claims', true), '')
  )::jsonb
$$;

alter function auth.uid() owner to supabase_auth_admin;
alter function auth.role() owner to supabase_auth_admin;
alter function auth.jwt() owner to supabase_auth_admin;

grant usage on schema auth to postgres, anon, authenticated, service_role;
-- postgres darf auf auth.users verweisen (Fremdschlüssel), Trigger anlegen und – für die Tests – Konten
-- anlegen und löschen.
grant select, insert, update, delete, references, trigger on all tables in schema auth to postgres;
