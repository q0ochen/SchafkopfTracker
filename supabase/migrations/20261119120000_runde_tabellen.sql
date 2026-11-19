-- Schafkopf Tracker – Gemeinsame Runde: Tabellen (Schema public), Constraints und Indizes.
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.2.
--
-- Sicherheitsgrundsatz: Jede Tabelle bekommt sofort Row Level Security und zunächst KEINE Rechte für Clients
-- (anon/authenticated). Lesen wird in 20261119120100_runde_zugriffsrechte.sql freigegeben, geschrieben wird
-- ausschließlich über die Funktionen aus 20261119120200_runde_funktionen.sql.
--
-- Auf dem Server liegen nur Tatsachen: Spieler, Abende im Dateiformat der Sicherung und das Regelwerk für neue
-- Abende. Stand, Geber, Bock-Zähler und Statistik rechnet jedes Gerät selbst.

-- ---------------------------------------------------------------------------------------------------
-- Internes Schema für Hilfsfunktionen. Es ist NICHT in [api].schemas eingetragen und wird deshalb von der
-- Data API (PostgREST) nicht ausgeliefert; Clients können diese Funktionen nicht aufrufen.
-- ---------------------------------------------------------------------------------------------------
create schema if not exists private;
revoke all on schema private from public;
revoke all on schema private from anon, authenticated, service_role;
comment on schema private is
  'Schafkopf Tracker: interne Hilfsfunktionen der gemeinsamen Runde. Nicht über die Data API erreichbar.';

-- ---------------------------------------------------------------------------------------------------
-- Runden. rev zählt jede Änderung der Runde (Abende, Spieler, Regeln, Mitglieder); jede schreibende
-- Funktion erhöht es zuerst und stempelt die geänderten Zeilen mit dem neuen Wert.
-- ---------------------------------------------------------------------------------------------------
create table public.rounds (
  id uuid primary key default gen_random_uuid(),
  name text not null
    constraint rounds_name_laenge check (char_length(name) between 1 and 40),
  created_by uuid null references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),
  rev bigint not null default 0
    constraint rounds_rev_nicht_negativ check (rev >= 0),
  rules jsonb null
    constraint rounds_rules_objekt check (rules is null or jsonb_typeof(rules) = 'object')
);
-- Für das Löschen eines Kontos (ON DELETE SET NULL sucht nach created_by).
create index rounds_created_by_idx on public.rounds (created_by);
comment on table public.rounds is
  'Gemeinsame Runden. Lesen: nur Mitglieder. Schreiben: nur über Funktionen.';
comment on column public.rounds.rev is
  'Zählt jede Änderung der Runde (Abende, Spieler, Regeln, Mitglieder).';
comment on column public.rounds.rules is
  'Regelwerk für neue Abende im Format von RuleSetJson.encode; NULL, solange keines hinterlegt ist.';

-- ---------------------------------------------------------------------------------------------------
-- Spieler einer Runde. owner = das Mitglied, dem der Spieler gehört (NULL = Gast ohne Konto).
-- Gelöschte Spieler bleiben als Zeile mit deleted = true, damit alle Geräte davon erfahren.
-- ---------------------------------------------------------------------------------------------------
create table public.round_players (
  round_id uuid not null references public.rounds (id) on delete cascade,
  id text not null
    constraint round_players_id_laenge check (char_length(id) between 1 and 64),
  name text not null
    constraint round_players_name_laenge check (char_length(name) between 1 and 24),
  color_index integer not null
    constraint round_players_color_index_bereich check (color_index between 0 and 31),
  archived boolean not null default false,
  position integer not null,
  owner uuid null references auth.users (id) on delete set null,
  name_locked boolean not null default false,
  deleted boolean not null default false,
  rev bigint not null,
  primary key (round_id, id),
  -- Löschen lassen sich nur Gäste; eine gelöschte Zeile gehört deshalb nie jemandem.
  constraint round_players_geloescht_ohne_besitzer check (not deleted or owner is null)
);
-- Namen sind je Runde eindeutig, ohne Groß/Klein. Gelöschte Spieler geben ihren Namen frei.
create unique index round_players_name_key on public.round_players (round_id, lower(name)) where not deleted;
-- Je Konto höchstens ein eigener Spieler in einer Runde („Das bin ich“).
create unique index round_players_owner_key on public.round_players (round_id, owner) where owner is not null;
-- Für das Löschen eines Kontos (ON DELETE SET NULL sucht nach owner).
create index round_players_owner_idx on public.round_players (owner) where owner is not null;
-- Für den Abgleich: „alle Spieler der Runde mit rev > n“.
create index round_players_rev_idx on public.round_players (round_id, rev);
comment on table public.round_players is
  'Spieler einer Runde (Gäste und Mitglieder). Lesen: nur Mitglieder. Schreiben: nur über Funktionen.';
comment on column public.round_players.position is 'Reihenfolge des Anlegens in der Runde (ab 1).';
comment on column public.round_players.owner is 'Konto, dem der Spieler gehört; NULL = Gast.';
comment on column public.round_players.name_locked is
  'Der Besitzer hat den Namen selbst festgelegt; ändern darf ihn nur noch der Ersteller der Runde.';
comment on column public.round_players.rev is 'Wert von rounds.rev bei der letzten Änderung dieser Zeile.';

-- ---------------------------------------------------------------------------------------------------
-- Mitglieder einer Runde (Konten). player_id = der eigene Spieler, sobald „Das bin ich“ gesagt ist.
-- ---------------------------------------------------------------------------------------------------
create table public.round_members (
  round_id uuid not null references public.rounds (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  player_id text null,
  joined_at timestamptz not null default now(),
  primary key (round_id, user_id),
  constraint round_members_player_key unique (round_id, player_id),
  constraint round_members_player_fkey foreign key (round_id, player_id)
    references public.round_players (round_id, id)
);
-- Der Primärschlüssel deckt Abfragen über die Runde ab, dieser Index „alle Runden eines Kontos“.
create index round_members_user_id_idx on public.round_members (user_id);
comment on table public.round_members is
  'Mitglieder einer Runde. Lesen: nur Mitglieder derselben Runde. Schreiben: nur über Funktionen.';

-- ---------------------------------------------------------------------------------------------------
-- Abende: je Abend EIN JSON-Dokument (Dateiformat der Sicherung) mit Versionsnummer. Geschrieben wird nur
-- mit „erwartete Version“ (save_evening). Gelöschte Abende bleiben als Zeile mit deleted = true.
-- ---------------------------------------------------------------------------------------------------
create table public.round_evenings (
  round_id uuid not null references public.rounds (id) on delete cascade,
  id text not null
    constraint round_evenings_id_laenge check (char_length(id) between 1 and 64),
  doc jsonb not null
    constraint round_evenings_doc_objekt check (jsonb_typeof(doc) = 'object'),
  version integer not null
    constraint round_evenings_version_positiv check (version >= 1),
  deleted boolean not null default false,
  rev bigint not null,
  updated_at timestamptz not null default now(),
  updated_by uuid null references auth.users (id) on delete set null,
  primary key (round_id, id),
  -- Das Dokument trägt seine eigene Kennung (als Text): "id" = id.
  constraint round_evenings_doc_id check (jsonb_typeof(doc -> 'id') = 'string' and doc ->> 'id' = id)
);
-- Für den Abgleich: „alle Abende der Runde mit rev > n, nach rev sortiert, seitenweise“. Eindeutig, weil jede
-- Änderung genau einen Abend stempelt – so überspringt das Blättern nach dem größten gelesenen rev nichts.
create unique index round_evenings_rev_key on public.round_evenings (round_id, rev);
-- Für das Löschen eines Kontos (ON DELETE SET NULL sucht nach updated_by).
create index round_evenings_updated_by_idx on public.round_evenings (updated_by);
comment on table public.round_evenings is
  'Abende einer Runde als JSON-Dokument mit Versionsnummer. Lesen: nur Mitglieder. Schreiben: nur save_evening.';
comment on column public.round_evenings.version is 'Zählt jede gespeicherte Fassung dieses Abends, ab 1.';
comment on column public.round_evenings.rev is 'Wert von rounds.rev bei der letzten Änderung dieser Zeile.';

-- ---------------------------------------------------------------------------------------------------
-- Einladungen. Gespeichert wird nur hex(sha256(token)), nie der Token selbst. Kein Client-Zugriff außer
-- über die Funktionen create_invite, invite_info und join_round.
-- ---------------------------------------------------------------------------------------------------
create table public.round_invites (
  token_hash text primary key
    constraint round_invites_token_hash_format check (token_hash ~ '^[0-9a-f]{64}$'),
  round_id uuid not null references public.rounds (id) on delete cascade,
  created_by uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  constraint round_invites_ablauf_nach_erstellung check (expires_at > created_at)
);
-- „Gültige Einladungen dieser Runde“ und das Aufräumen abgelaufener Einladungen.
create index round_invites_round_id_idx on public.round_invites (round_id, expires_at);
-- Für das Löschen eines Kontos (ON DELETE CASCADE sucht nach created_by).
create index round_invites_created_by_idx on public.round_invites (created_by);
comment on table public.round_invites is
  'Einladungen (nur der SHA-256-Wert des Tokens, 14 Tage gültig). Keine Zugriffsregel: nur über Funktionen.';

-- ---------------------------------------------------------------------------------------------------
-- Row Level Security auf JEDER Tabelle; zunächst keinerlei Client-Rechte. (Supabase gibt neue Tabellen in
-- public sonst automatisch für anon, authenticated und service_role frei.)
-- ---------------------------------------------------------------------------------------------------
alter table public.rounds enable row level security;
alter table public.round_players enable row level security;
alter table public.round_members enable row level security;
alter table public.round_evenings enable row level security;
alter table public.round_invites enable row level security;

revoke all on table
  public.rounds, public.round_players, public.round_members, public.round_evenings, public.round_invites
  from public, anon, authenticated, service_role;
