-- Schafkopf Tracker – Gemeinsame Runde: Funktionen (RPCs).
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.3.
--
-- Alle RPCs: SECURITY DEFINER (umgehen RLS, prüfen daher alles selbst), search_path = '' und vollständig
-- qualifizierte Namen, ausführbar NUR für authenticated. Fehler werden mit SQLSTATE P0001 und einem
-- maschinenlesbaren Code als Meldung ausgelöst (zum Beispiel „name_taken“); die Apps übersetzen sie.
--
-- Reihenfolge in jeder Funktion, die eine Runde nennt:
--   1. Anmeldung prüfen.
--   2. Mitgliedschaft prüfen. Wer kein Mitglied der genannten Runde ist, bekommt IMMER „not_member“ – auch
--      wenn es die Runde nicht gibt oder die übrigen Angaben ungültig sind.
--   3. rounds.rev erhöhen (UPDATE … RETURNING). Das sperrt die Zeile der Runde und reiht alle Schreiber
--      einer Runde hintereinander. Deshalb gilt: Wer Zeilen mit rev > n liest, hat danach alles bis zum
--      größten gelesenen rev vollständig.
--   4. Eingaben prüfen und schreiben; die geänderten Zeilen werden mit dem neuen rev gestempelt. Ein Fehler
--      macht alles rückgängig, auch die Erhöhung von rev.
-- Zwei Funktionen können ohne Änderung enden (save_evening bei falscher Version, save_player beim Löschen
-- eines unbekannten Spielers), und create_invite ändert rev gar nicht: Sie sperren die Zeile der Runde
-- zuerst nur (SELECT … FOR UPDATE) und erhöhen rev erst, wenn feststeht, dass sie etwas ändern.

-- ---------------------------------------------------------------------------------------------------
-- Hilfsfunktionen (intern, Schema private)
-- ---------------------------------------------------------------------------------------------------

/** Liefert die Kennung des angemeldeten Kontos. Ohne Konto im JWT: Abbruch mit SQLSTATE 42501
    („not_authenticated“). Über die Data API ist das nicht erreichbar (anon darf die Funktionen gar nicht
    aufrufen); die Prüfung ist die zweite Sicherung. Abgewiesen werden auch anonyme Konten: Die anonyme
    Anmeldung ist ausgeschaltet (supabase/README.md) – sollte sie versehentlich eingeschaltet werden, könnte
    sonst jeder beliebig viele Konten anlegen und die Obergrenzen je Konto umgehen. */
create function private.require_user()
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  ich uuid := auth.uid();
begin
  if ich is null or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  return ich;
end;
$$;

/** Bereinigt einen Namen: Folgen von Leer- und Steuerzeichen (auch Tabulator und Zeilenumbruch) werden zu
    einem Leerzeichen, Leerzeichen am Anfang und Ende entfallen. NULL wird zum leeren Text. Die Länge prüft
    der Aufrufer. */
create function private.clean_name(p_name text)
returns text
language sql
immutable
set search_path = ''
as $$
  select btrim(regexp_replace(coalesce(p_name, ''), '[[:space:][:cntrl:]]+', ' ', 'g'))
$$;

/** Bildet eine Farbnummer auf den erlaubten Bereich 0 bis 31 ab (Rest beim Teilen durch 32). Die Apps zeigen
    Farben ohnehin modulo 8 an und vergeben bei vielen Spielern auch größere Nummern; die Anzeige bleibt
    dadurch gleich. NULL: „invalid_doc“. */
create function private.clean_color_index(p_color_index integer)
returns integer
language plpgsql
immutable
set search_path = ''
as $$
begin
  if p_color_index is null then
    raise exception 'invalid_doc' using errcode = 'P0001';
  end if;
  return ((p_color_index % 32) + 32) % 32;
end;
$$;

/** Prüft die Mitgliedschaft von p_user in p_round und sperrt die Zeile der Runde bis zum Ende der
    Transaktion. Liefert das aktuelle rev. Kein Mitglied, unbekannte Runde oder NULL: „not_member“ –
    Nicht-Mitglieder erreichen die Sperre gar nicht erst. */
create function private.lock_round(p_round uuid, p_user uuid)
returns bigint
language plpgsql
set search_path = ''
as $$
declare
  aktuell bigint;
begin
  if p_round is null or not exists (
    select 1 from public.round_members m where m.round_id = p_round and m.user_id = p_user
  ) then
    raise exception 'not_member' using errcode = 'P0001';
  end if;

  select r.rev into aktuell from public.rounds r where r.id = p_round for update;

  -- Während des Wartens auf die Sperre kann dasselbe Konto die Runde auf einem anderen Gerät verlassen
  -- haben (oder die Runde wurde gelöscht). Unter der Sperre ändert sich die Mitgliedschaft nicht mehr.
  if aktuell is null or not exists (
    select 1 from public.round_members m where m.round_id = p_round and m.user_id = p_user
  ) then
    raise exception 'not_member' using errcode = 'P0001';
  end if;
  return aktuell;
end;
$$;

/** Erhöht rounds.rev um eins und liefert den neuen Wert, mit dem die geänderten Zeilen gestempelt werden.
    NULL, wenn es die Runde nicht (mehr) gibt. */
create function private.bump_rev(p_round uuid)
returns bigint
language sql
set search_path = ''
as $$
  update public.rounds r set rev = r.rev + 1 where r.id = p_round returning r.rev
$$;

/** Beginnt eine Änderung der Runde: Mitgliedschaft prüfen, Runde sperren, rev erhöhen. Liefert das neue rev. */
create function private.begin_change(p_round uuid, p_user uuid)
returns bigint
language plpgsql
set search_path = ''
as $$
begin
  perform private.lock_round(p_round, p_user);
  return private.bump_rev(p_round);
end;
$$;

/** Reiht gleichzeitige Aufrufe desselben Kontos hintereinander (Advisory-Sperre bis zum Ende der
    Transaktion), damit zwei Geräte die Obergrenze „5 Runden je Konto“ nicht gemeinsam umgehen. */
create function private.lock_user(p_user uuid)
returns void
language plpgsql
set search_path = ''
as $$
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('schafkopftracker.round_members:' || p_user::text, 0)
  );
end;
$$;

/** Ob p_user die Runde angelegt hat. */
create function private.is_creator(p_round uuid, p_user uuid)
returns boolean
language sql
stable
set search_path = ''
as $$
  select coalesce((select r.created_by = p_user from public.rounds r where r.id = p_round), false)
$$;

/** Prüft einen (bereits bereinigten) Spielernamen: 1 bis 24 Zeichen, sonst „invalid_name“; trägt ihn ein
    anderer, nicht gelöschter Spieler der Runde (ohne Groß/Klein), „name_taken“. */
create function private.check_player_name(p_round uuid, p_id text, p_name text)
returns void
language plpgsql
stable
set search_path = ''
as $$
begin
  if char_length(p_name) not between 1 and 24 then
    raise exception 'invalid_name' using errcode = 'P0001';
  end if;
  if exists (
    select 1
    from public.round_players x
    where x.round_id = p_round
      and not x.deleted
      and x.id <> p_id
      and lower(x.name) = lower(p_name)
  ) then
    raise exception 'name_taken' using errcode = 'P0001';
  end if;
end;
$$;

/** Prüft, ob ein weiterer Spieler in die Runde passt: höchstens 40 nicht gelöschte Spieler. Für eine neue
    Zeile (p_new_row) außerdem höchstens 400 Zeilen einschließlich der gelöschten, damit Anlegen und Löschen
    im Wechsel die Tabelle nicht beliebig wachsen lässt. Sonst „limit_reached“. */
create function private.check_player_limit(p_round uuid, p_new_row boolean)
returns void
language plpgsql
stable
set search_path = ''
as $$
begin
  if (select count(*) from public.round_players x where x.round_id = p_round and not x.deleted) >= 40
     or (p_new_row and (select count(*) from public.round_players x where x.round_id = p_round) >= 400) then
    raise exception 'limit_reached' using errcode = 'P0001';
  end if;
end;
$$;

/** Die Position für einen neuen Spieler: die größte der Runde plus eins (der erste bekommt 1). */
create function private.next_position(p_round uuid)
returns integer
language sql
stable
set search_path = ''
as $$
  select coalesce(max(x.position), 0) + 1 from public.round_players x where x.round_id = p_round
$$;

/** hex(sha256(token)) – nur dieser Wert wird gespeichert, nie der Token selbst. */
create function private.invite_hash(p_token text)
returns text
language sql
immutable
set search_path = ''
as $$
  select encode(sha256(convert_to(p_token, 'UTF8')), 'hex')
$$;

/** Liefert die Runde zu einem gültigen Einladungs-Token (32 Zeichen 0-9a-f; Leerzeichen und Zeilenumbrüche
    außen sowie Großbuchstaben werden verziehen). Unbekannt, abgelaufen oder kein Token: „invalid_invite“. */
create function private.invite_round(p_token text)
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  eingabe text := lower(btrim(coalesce(p_token, ''), E' \t\r\n'));
  runde uuid;
begin
  if eingabe ~ '^[0-9a-f]{32}$' then
    select i.round_id into runde
    from public.round_invites i
    where i.token_hash = private.invite_hash(eingabe)
      and i.expires_at > now();
  end if;
  if runde is null then
    raise exception 'invalid_invite' using errcode = 'P0001';
  end if;
  return runde;
end;
$$;

revoke all on function private.require_user() from public;
revoke all on function private.clean_name(text) from public;
revoke all on function private.clean_color_index(integer) from public;
revoke all on function private.lock_round(uuid, uuid) from public;
revoke all on function private.bump_rev(uuid) from public;
revoke all on function private.begin_change(uuid, uuid) from public;
revoke all on function private.lock_user(uuid) from public;
revoke all on function private.is_creator(uuid, uuid) from public;
revoke all on function private.check_player_name(uuid, text, text) from public;
revoke all on function private.check_player_limit(uuid, boolean) from public;
revoke all on function private.next_position(uuid) from public;
revoke all on function private.invite_hash(text) from public;
revoke all on function private.invite_round(text) from public;

-- ---------------------------------------------------------------------------------------------------
-- create_round(p_name text) → uuid
-- ---------------------------------------------------------------------------------------------------

/** Legt eine Runde an und macht den Aufrufer zum (ersten) Mitglied. Der Name wird bereinigt und muss
    1 bis 40 Zeichen haben („invalid_name“). Höchstens 5 Runden je Konto („limit_reached“). Die neue Runde
    hat rev = 1: Das Anlegen mit dem ersten Mitglied ist ihre erste Änderung. */
create function public.create_round(p_name text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  name_neu text := private.clean_name(p_name);
  runde uuid;
begin
  if char_length(name_neu) not between 1 and 40 then
    raise exception 'invalid_name' using errcode = 'P0001';
  end if;

  perform private.lock_user(ich);
  if (select count(*) from public.round_members m where m.user_id = ich) >= 5 then
    raise exception 'limit_reached' using errcode = 'P0001';
  end if;

  insert into public.rounds as r (name, created_by, rev)
  values (name_neu, ich, 1)
  returning r.id into runde;

  insert into public.round_members (round_id, user_id) values (runde, ich);
  return runde;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- my_rounds() → table(id, name, rev, member_count, my_player_id, is_creator)
-- ---------------------------------------------------------------------------------------------------

/** Alle Runden des Aufrufers, älteste Mitgliedschaft zuerst. Liest nur. */
create function public.my_rounds()
returns table (id uuid, name text, rev bigint, member_count integer, my_player_id text, is_creator boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  ich uuid := private.require_user();
begin
  return query
    select r.id,
           r.name,
           r.rev,
           (select count(*)::integer from public.round_members x where x.round_id = r.id),
           m.player_id,
           coalesce(r.created_by = ich, false)
    from public.round_members m
    join public.rounds r on r.id = m.round_id
    where m.user_id = ich
    order by m.joined_at, r.created_at, r.id;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- create_invite(p_round uuid) → text
-- ---------------------------------------------------------------------------------------------------

/** Erzeugt einen neuen Einladungs-Token (32 Zeichen 0-9a-f, 122 zufällige Bit), gültig 14 Tage, und gibt
    ihn zurück; gespeichert wird nur sein SHA-256-Wert. Abgelaufene Einladungen der Runde werden dabei
    gelöscht. Höchstens 20 gültige Einladungen je Runde („limit_reached“). Ändert rev nicht. */
create function public.create_invite(p_round uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  token text;
begin
  perform private.lock_round(p_round, ich);

  delete from public.round_invites i where i.round_id = p_round and i.expires_at <= now();
  if (select count(*) from public.round_invites i where i.round_id = p_round) >= 20 then
    raise exception 'limit_reached' using errcode = 'P0001';
  end if;

  token := replace(gen_random_uuid()::text, '-', '');
  insert into public.round_invites (token_hash, round_id, created_by, created_at, expires_at)
  values (private.invite_hash(token), p_round, ich, now(), now() + interval '14 days');
  return token;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- invite_info(p_token text) → table(round_id, round_name, member_count, already_member)
-- ---------------------------------------------------------------------------------------------------

/** Was eine Einladung verrät, bevor man beitritt: Runde, Name, Zahl der Mitglieder und ob der Aufrufer
    schon dabei ist. Unbekannter oder abgelaufener Token: „invalid_invite“. Liest nur. */
create function public.invite_info(p_token text)
returns table (round_id uuid, round_name text, member_count integer, already_member boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  ich uuid := private.require_user();
  runde uuid := private.invite_round(p_token);
begin
  return query
    select r.id,
           r.name,
           (select count(*)::integer from public.round_members x where x.round_id = r.id),
           exists (select 1 from public.round_members x where x.round_id = r.id and x.user_id = ich)
    from public.rounds r
    where r.id = runde;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- join_round(p_token text) → uuid
-- ---------------------------------------------------------------------------------------------------

/** Macht den Aufrufer zum Mitglied der Runde hinter dem Token und gibt ihre Kennung zurück. Schon Mitglied:
    nichts weiter (rev bleibt). Gehört ihm in der Runde bereits ein Spieler (owner), wird er wieder
    verknüpft. Fehler: „invalid_invite“, „limit_reached“ (5 Runden je Konto, 12 Mitglieder je Runde). */
create function public.join_round(p_token text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  runde uuid := private.invite_round(p_token);
  eigener text;
begin
  perform private.lock_user(ich);

  if exists (select 1 from public.round_members m where m.round_id = runde and m.user_id = ich) then
    return runde;
  end if;

  if (select count(*) from public.round_members m where m.user_id = ich) >= 5 then
    raise exception 'limit_reached' using errcode = 'P0001';
  end if;

  -- Sperrt die Runde und zählt die Änderung (ein Mitglied mehr). Gibt es die Runde inzwischen nicht mehr,
  -- ist auch die Einladung weg.
  if private.bump_rev(runde) is null then
    raise exception 'invalid_invite' using errcode = 'P0001';
  end if;
  if (select count(*) from public.round_members m where m.round_id = runde) >= 12 then
    raise exception 'limit_reached' using errcode = 'P0001';
  end if;

  select p.id into eigener
  from public.round_players p
  where p.round_id = runde and p.owner = ich and not p.deleted;

  insert into public.round_members (round_id, user_id, player_id) values (runde, ich, eigener);
  return runde;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- leave_round(p_round uuid) → void
-- ---------------------------------------------------------------------------------------------------

/** Entfernt die Mitgliedschaft des Aufrufers; sein Spieler bleibt mit owner bestehen (und wird bei einem
    späteren Beitritt wieder verknüpft). Verlässt das letzte Mitglied die Runde, wird sie mit allen
    Spielern, Abenden und Einladungen gelöscht. */
create function public.leave_round(p_round uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
begin
  perform private.begin_change(p_round, ich);

  delete from public.round_members m where m.round_id = p_round and m.user_id = ich;

  if not exists (select 1 from public.round_members m where m.round_id = p_round) then
    delete from public.rounds r where r.id = p_round;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- claim_player(p_round uuid, p_player text, p_name text, p_color_index int) → void
-- ---------------------------------------------------------------------------------------------------

/** „Das bin ich“: Der Aufrufer übernimmt den Spieler p_player oder legt ihn an.
    - Hat er schon einen anderen Spieler: „already_claimed“.
    - Gibt es p_player (nicht gelöscht) und gehört er einem anderen: „player_taken“.
    - Gehört er schon dem Aufrufer (zum Beispiel beim wiederholten Aufruf): Der Name bleibt, wie er ist.
    - Sonst (Gast, neu oder gelöscht): owner = Aufrufer, name = p_name (bereinigt, 1 bis 24 Zeichen, sonst
      „invalid_name“; vergeben: „name_taken“), name_locked = true. Eine gelöschte Zeile lebt dabei wieder auf.
    In jedem Fall werden die Farbe (p_color_index) und round_members.player_id gesetzt. Höchstens 40 nicht
    gelöschte Spieler („limit_reached“). */
create function public.claim_player(p_round uuid, p_player text, p_name text, p_color_index integer)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  neu bigint;
  mein text;
  spieler public.round_players%rowtype;
  vorhanden boolean;
  name_neu text;
  farbe integer;
begin
  neu := private.begin_change(p_round, ich);

  if p_player is null or char_length(p_player) not between 1 and 64 then
    raise exception 'invalid_doc' using errcode = 'P0001';
  end if;

  select m.player_id into mein
  from public.round_members m
  where m.round_id = p_round and m.user_id = ich;

  if (mein is not null and mein <> p_player)
     or exists (
       select 1 from public.round_players x
       where x.round_id = p_round and x.owner = ich and x.id <> p_player
     ) then
    raise exception 'already_claimed' using errcode = 'P0001';
  end if;

  select p.* into spieler
  from public.round_players p
  where p.round_id = p_round and p.id = p_player;
  vorhanden := found;

  if vorhanden and not spieler.deleted and spieler.owner is not null and spieler.owner <> ich then
    raise exception 'player_taken' using errcode = 'P0001';
  end if;

  name_neu := private.clean_name(p_name);
  farbe := private.clean_color_index(p_color_index);

  if vorhanden and not spieler.deleted and spieler.owner = ich then
    -- Schon der eigene Spieler: Der einmal festgelegte Name bleibt.
    update public.round_players p
       set name_locked = true, color_index = farbe, rev = neu
     where p.round_id = p_round and p.id = p_player;
  elsif vorhanden and not spieler.deleted then
    -- Ein Gast wird übernommen.
    perform private.check_player_name(p_round, p_player, name_neu);
    update public.round_players p
       set owner = ich, name = name_neu, name_locked = true, color_index = farbe, rev = neu
     where p.round_id = p_round and p.id = p_player;
  elsif vorhanden then
    -- Eine gelöschte Zeile lebt als eigener Spieler wieder auf.
    perform private.check_player_limit(p_round, false);
    perform private.check_player_name(p_round, p_player, name_neu);
    update public.round_players p
       set owner = ich, name = name_neu, name_locked = true, color_index = farbe, archived = false,
           deleted = false, rev = neu
     where p.round_id = p_round and p.id = p_player;
  else
    perform private.check_player_limit(p_round, true);
    perform private.check_player_name(p_round, p_player, name_neu);
    insert into public.round_players
      (round_id, id, name, color_index, archived, position, owner, name_locked, deleted, rev)
    values
      (p_round, p_player, name_neu, farbe, false, private.next_position(p_round), ich, true, false, neu);
  end if;

  update public.round_members m
     set player_id = p_player
   where m.round_id = p_round and m.user_id = ich;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- save_player(p_round, p_id, p_name, p_color_index, p_archived, p_deleted) → bigint
-- ---------------------------------------------------------------------------------------------------

/** Legt einen Gast an oder ändert einen Spieler und liefert das neue rev.
    - Neu (auch: eine gelöschte Zeile lebt wieder auf): Gast ohne Besitzer, Name bereinigt, 1 bis 24 Zeichen
      („invalid_name“), je Runde eindeutig ohne Groß/Klein („name_taken“); höchstens 40 nicht gelöschte
      Spieler („limit_reached“).
    - Ändern: Farbe und „archiviert“ für alle Spieler. Ein anderer Name bei name_locked: „name_locked“ – nur
      der Ersteller der Runde darf einen festen Namen korrigieren (er bleibt danach fest).
    - p_deleted: nur Gäste (owner NULL), sonst „player_owned“. Die Zeile bleibt mit deleted = true; die
      übrigen Angaben werden dabei nicht beachtet. Gibt es den Spieler nicht oder ist er schon gelöscht,
      passiert nichts: rev bleibt und wird unverändert zurückgegeben.
    p_archived und p_deleted gelten bei NULL als false. */
create function public.save_player(
  p_round uuid,
  p_id text,
  p_name text,
  p_color_index integer,
  p_archived boolean,
  p_deleted boolean
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  aktuell bigint;
  neu bigint;
  spieler public.round_players%rowtype;
  vorhanden boolean;
  name_neu text;
  farbe integer;
begin
  aktuell := private.lock_round(p_round, ich);

  if p_id is null or char_length(p_id) not between 1 and 64 then
    raise exception 'invalid_doc' using errcode = 'P0001';
  end if;

  select p.* into spieler
  from public.round_players p
  where p.round_id = p_round and p.id = p_id;
  vorhanden := found;

  if coalesce(p_deleted, false) then
    if not vorhanden or spieler.deleted then
      return aktuell;
    end if;
    if spieler.owner is not null then
      raise exception 'player_owned' using errcode = 'P0001';
    end if;
    neu := private.bump_rev(p_round);
    update public.round_players p
       set deleted = true, rev = neu
     where p.round_id = p_round and p.id = p_id;
    return neu;
  end if;

  name_neu := private.clean_name(p_name);
  farbe := private.clean_color_index(p_color_index);
  neu := private.bump_rev(p_round);

  if not vorhanden then
    perform private.check_player_limit(p_round, true);
    perform private.check_player_name(p_round, p_id, name_neu);
    insert into public.round_players
      (round_id, id, name, color_index, archived, position, owner, name_locked, deleted, rev)
    values
      (p_round, p_id, name_neu, farbe, coalesce(p_archived, false), private.next_position(p_round),
       null, false, false, neu);
  elsif spieler.deleted then
    -- Eine gelöschte Zeile lebt als Gast wieder auf.
    perform private.check_player_limit(p_round, false);
    perform private.check_player_name(p_round, p_id, name_neu);
    update public.round_players p
       set name = name_neu, color_index = farbe, archived = coalesce(p_archived, false),
           name_locked = false, deleted = false, rev = neu
     where p.round_id = p_round and p.id = p_id;
  else
    if name_neu <> spieler.name then
      if spieler.name_locked and not private.is_creator(p_round, ich) then
        raise exception 'name_locked' using errcode = 'P0001';
      end if;
      perform private.check_player_name(p_round, p_id, name_neu);
    end if;
    update public.round_players p
       set name = name_neu, color_index = farbe, archived = coalesce(p_archived, false), rev = neu
     where p.round_id = p_round and p.id = p_id;
  end if;
  return neu;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- save_rules(p_round uuid, p_rules jsonb) → bigint
-- ---------------------------------------------------------------------------------------------------

/** Setzt das Regelwerk für neue Abende und liefert das neue rev. p_rules muss ein JSON-Objekt sein
    („invalid_doc“) und darf als Text höchstens 20 000 Bytes haben („too_large“): Die Apps lesen den Kopf der
    Runde samt Regelwerk alle paar Sekunden. */
create function public.save_rules(p_round uuid, p_rules jsonb)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  ich uuid := private.require_user();
  neu bigint;
begin
  neu := private.begin_change(p_round, ich);

  if p_rules is null or jsonb_typeof(p_rules) <> 'object' then
    raise exception 'invalid_doc' using errcode = 'P0001';
  end if;
  if octet_length(p_rules::text) > 20000 then
    raise exception 'too_large' using errcode = 'P0001';
  end if;

  update public.rounds r set rules = p_rules where r.id = p_round;
  return neu;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- save_evening(p_round, p_id, p_doc, p_expected_version, p_deleted) → table(version, rev)
-- ---------------------------------------------------------------------------------------------------

/** Speichert einen Abend nach dem Muster „Vergleichen und Ersetzen“.
    - Gibt es den Abend noch nicht, muss p_expected_version 0 sein; die neue Version ist 1.
    - Sonst muss version = p_expected_version gelten; dann werden doc und deleted ersetzt und version um
      eins erhöht. Eine gelöschte Zeile bleibt (mit dem übergebenen Dokument) bestehen.
    - Passt die Version nicht, kommt KEIN Fehler, sondern eine leere Ergebnismenge, und nichts ändert sich
      (auch rev nicht). Der Aufrufer liest den Abend dann neu.
    p_doc muss ein JSON-Objekt mit "id" = p_id sein („invalid_doc“) und darf als Text, wie PostgreSQL ihn
    ausgibt, höchstens 300 000 Bytes haben („too_large“). Höchstens 3000 Abende je Runde, gelöschte
    mitgezählt („limit_reached“). p_deleted gilt bei NULL als false.
    Weil rev erst nach dem Vergleich erhöht wird, sperrt die Funktion die Runde zuerst nur
    (SELECT … FOR UPDATE); die Schreiber einer Runde laufen trotzdem streng hintereinander. */
create function public.save_evening(
  p_round uuid,
  p_id text,
  p_doc jsonb,
  p_expected_version integer,
  p_deleted boolean
)
returns table (version integer, rev bigint)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  ich uuid := private.require_user();
  vorhanden integer;
  neu bigint;
begin
  perform private.lock_round(p_round, ich);

  if p_id is null or char_length(p_id) not between 1 and 64
     or p_doc is null or jsonb_typeof(p_doc) <> 'object'
     or jsonb_typeof(p_doc -> 'id') is distinct from 'string'
     or p_doc ->> 'id' <> p_id then
    raise exception 'invalid_doc' using errcode = 'P0001';
  end if;
  if octet_length(p_doc::text) > 300000 then
    raise exception 'too_large' using errcode = 'P0001';
  end if;

  select e.version into vorhanden
  from public.round_evenings e
  where e.round_id = p_round and e.id = p_id;

  if vorhanden is null then
    if p_expected_version is distinct from 0 then
      return;
    end if;
    if (select count(*) from public.round_evenings e where e.round_id = p_round) >= 3000 then
      raise exception 'limit_reached' using errcode = 'P0001';
    end if;
    neu := private.bump_rev(p_round);
    insert into public.round_evenings (round_id, id, doc, version, deleted, rev, updated_at, updated_by)
    values (p_round, p_id, p_doc, 1, coalesce(p_deleted, false), neu, now(), ich);
    version := 1;
  else
    if p_expected_version is distinct from vorhanden then
      return;
    end if;
    neu := private.bump_rev(p_round);
    update public.round_evenings e
       set doc = p_doc, deleted = coalesce(p_deleted, false), version = vorhanden + 1, rev = neu,
           updated_at = now(), updated_by = ich
     where e.round_id = p_round and e.id = p_id;
    version := vorhanden + 1;
  end if;

  rev := neu;
  return next;
end;
$$;

-- ---------------------------------------------------------------------------------------------------
-- Ausführungsrechte: RPCs nur für authenticated. (Supabase gibt neue Funktionen in public sonst automatisch
-- für anon, authenticated und service_role frei, PostgreSQL außerdem für PUBLIC.)
-- ---------------------------------------------------------------------------------------------------
revoke all on function public.create_round(text) from public, anon, authenticated, service_role;
revoke all on function public.my_rounds() from public, anon, authenticated, service_role;
revoke all on function public.create_invite(uuid) from public, anon, authenticated, service_role;
revoke all on function public.invite_info(text) from public, anon, authenticated, service_role;
revoke all on function public.join_round(text) from public, anon, authenticated, service_role;
revoke all on function public.leave_round(uuid) from public, anon, authenticated, service_role;
revoke all on function public.claim_player(uuid, text, text, integer) from public, anon, authenticated, service_role;
revoke all on function public.save_player(uuid, text, text, integer, boolean, boolean) from public, anon, authenticated, service_role;
revoke all on function public.save_rules(uuid, jsonb) from public, anon, authenticated, service_role;
revoke all on function public.save_evening(uuid, text, jsonb, integer, boolean) from public, anon, authenticated, service_role;

grant execute on function public.create_round(text) to authenticated;
grant execute on function public.my_rounds() to authenticated;
grant execute on function public.create_invite(uuid) to authenticated;
grant execute on function public.invite_info(text) to authenticated;
grant execute on function public.join_round(text) to authenticated;
grant execute on function public.leave_round(uuid) to authenticated;
grant execute on function public.claim_player(uuid, text, text, integer) to authenticated;
grant execute on function public.save_player(uuid, text, text, integer, boolean, boolean) to authenticated;
grant execute on function public.save_rules(uuid, jsonb) to authenticated;
grant execute on function public.save_evening(uuid, text, jsonb, integer, boolean) to authenticated;
