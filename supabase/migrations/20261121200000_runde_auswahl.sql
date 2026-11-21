-- Schafkopf Tracker – Gemeinsame Runde: zwischen Runden wechseln (21.11.2026, Wunsch des Nutzers).
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.3 und 8.7 („Eindeutige Namen“).
--
-- 1. my_rounds() liefert zusätzlich member_names: die Namen der Spieler, die Mitglieder mit „Das bin ich“
--    übernommen haben. Damit lassen sich in der Auswahl der Apps Runden desselben Namens unterscheiden (die gibt es,
--    wenn verschiedene Leute ihre Runden gleich nennen und man beiden beitritt). Ältere Apps lesen die zusätzliche
--    Spalte nicht: Sie übergehen unbekannte Felder.
-- 2. create_round() lehnt einen Namen ab, den der Aufrufer schon für eine seiner Runden hat („name_taken“). Die
--    Apps prüfen das vorher selbst; der Server ist die zweite Sicherung (zum Beispiel für zwei Geräte desselben
--    Kontos, die gleichzeitig anlegen: lock_user reiht sie hintereinander).

-- ---------------------------------------------------------------------------------------------------
-- my_rounds() → table(id, name, rev, member_count, my_player_id, is_creator, member_names)
-- ---------------------------------------------------------------------------------------------------

-- Die Ergebnisspalten ändern sich; das geht nur über Löschen und neu Anlegen (die Rechte kommen unten neu).
drop function public.my_rounds();

/** Alle Runden des Aufrufers, älteste Mitgliedschaft zuerst. Liest nur. member_names: die Namen der Spieler, die
    Mitglieder der Runde übernommen haben (auch der eigene), nach Namen ohne Groß/Klein sortiert; Mitglieder ohne
    Spieler fehlen, Gäste ohnehin. */
create function public.my_rounds()
returns table (
  id uuid,
  name text,
  rev bigint,
  member_count integer,
  my_player_id text,
  is_creator boolean,
  member_names text[]
)
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
           coalesce(r.created_by = ich, false),
           coalesce(
             (select array_agg(p.name order by lower(p.name), p.name)
                from public.round_members x
                join public.round_players p on p.round_id = x.round_id and p.id = x.player_id
               where x.round_id = r.id
                 and not p.deleted),
             '{}'::text[])
    from public.round_members m
    join public.rounds r on r.id = m.round_id
    where m.user_id = ich
    order by m.joined_at, r.created_at, r.id;
end;
$$;

revoke all on function public.my_rounds() from public, anon, authenticated, service_role;
grant execute on function public.my_rounds() to authenticated;

-- ---------------------------------------------------------------------------------------------------
-- create_round(p_name text) → uuid
-- ---------------------------------------------------------------------------------------------------

/** Legt eine Runde an und macht den Aufrufer zum (ersten) Mitglied. Der Name wird bereinigt und muss
    1 bis 40 Zeichen haben („invalid_name“). Gehört der Aufrufer schon einer Runde mit demselben Namen an
    (bereinigt, ohne Groß/Klein), „name_taken“. Höchstens 5 Runden je Konto („limit_reached“). Die neue Runde
    hat rev = 1: Das Anlegen mit dem ersten Mitglied ist ihre erste Änderung. */
create or replace function public.create_round(p_name text)
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
  if exists (
    select 1
      from public.round_members m
      join public.rounds r on r.id = m.round_id
     where m.user_id = ich
       and lower(r.name) = lower(name_neu)
  ) then
    raise exception 'name_taken' using errcode = 'P0001';
  end if;
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
