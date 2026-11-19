-- Schafkopf Tracker – Gemeinsame Runde: Was passiert, wenn ein Konto gelöscht wird.
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.2 (Fremdschlüssel auf auth.users, Bedeutung von rev).
--
-- Die Fremdschlüssel räumen von selbst auf: Mitgliedschaften und Einladungen des Kontos verschwinden
-- (ON DELETE CASCADE), rounds.created_by, round_players.owner und round_evenings.updated_by werden NULL
-- (ON DELETE SET NULL). Das allein hätte zwei Lücken, weil dabei keine der Funktionen aus Abschnitt 8.3 läuft:
--   1. rounds.rev würde nicht erhöht und die Spielerzeile nicht gestempelt – die anderen Geräte erführen nie,
--      dass ein Mitglied fehlt und sein Spieler jetzt ein Gast ist.
--   2. War das Konto das letzte Mitglied, bliebe die Runde mit Namen und Abenden liegen, ohne dass sie noch
--      jemand lesen oder löschen könnte.
-- Deshalb erledigt ein Trigger auf auth.users beides, bevor die Zeile des Kontos verschwindet.

/** Läuft vor dem Löschen eines Kontos, für jede Runde, in der es Mitglied ist oder einen Spieler besitzt:
    rev erhöhen (sperrt die Runde wie jede schreibende Funktion), den eigenen Spieler zum Gast machen und
    stempeln (der Name bleibt fest), die Mitgliedschaft entfernen und die Runde löschen, wenn danach niemand
    mehr Mitglied ist. SECURITY DEFINER, weil der Auth-Server Konten als supabase_auth_admin löscht und in
    public keine Rechte hat. */
create function private.handle_user_deleted()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  runde uuid;
  neu bigint;
begin
  for runde in
    select x.round_id
    from (
      select m.round_id from public.round_members m where m.user_id = old.id
      union
      select p.round_id from public.round_players p where p.owner = old.id
    ) x
    order by x.round_id
  loop
    neu := private.bump_rev(runde);
    continue when neu is null;

    update public.round_players p
       set owner = null, rev = neu
     where p.round_id = runde and p.owner = old.id;

    delete from public.round_members m where m.round_id = runde and m.user_id = old.id;

    if not exists (select 1 from public.round_members m where m.round_id = runde) then
      delete from public.rounds r where r.id = runde;
    end if;
  end loop;
  return old;
end;
$$;

revoke all on function private.handle_user_deleted() from public;

create trigger on_auth_user_deleted
  before delete on auth.users
  for each row execute function private.handle_user_deleted();
