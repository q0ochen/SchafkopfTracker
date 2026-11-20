-- Schafkopf Tracker – Gemeinsame Runde: Zugriffsrechte (GRANTs) und Row-Level-Security-Richtlinien.
-- Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.2.
--
-- anon (nicht angemeldet) bekommt auf keine Tabelle Rechte. authenticated darf vier Tabellen LESEN, und zwar
-- nur die Zeilen der Runden, in denen das Konto Mitglied ist. round_invites liest niemand. Direkte
-- INSERT/UPDATE/DELETE gibt es für niemanden: Geschrieben wird nur über die Funktionen aus Abschnitt 8.3.
-- service_role wird nicht gebraucht (es gibt keine Server-Komponente) und bekommt deshalb ebenfalls nichts.

-- ---------------------------------------------------------------------------------------------------
-- is_round_member(p_round) → boolean
-- ---------------------------------------------------------------------------------------------------

/** Ob das angemeldete Konto Mitglied der Runde ist. SECURITY DEFINER, damit die Richtlinien (auch die von
    round_members selbst) die Mitgliedschaft prüfen können, ohne sich im Kreis aufzurufen. Verrät nichts über
    fremde Runden: Für eine unbekannte Runde und für eine Runde ohne eigene Mitgliedschaft kommt beide Male
    false. */
create function public.is_round_member(p_round uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.round_members m
    where m.round_id = p_round
      and m.user_id = (select auth.uid())
  )
$$;

revoke all on function public.is_round_member(uuid) from public, anon, authenticated, service_role;
grant execute on function public.is_round_member(uuid) to authenticated;

-- ---------------------------------------------------------------------------------------------------
-- Lesen: nur Mitglieder der jeweiligen Runde
-- ---------------------------------------------------------------------------------------------------
grant select on table
  public.rounds, public.round_players, public.round_members, public.round_evenings
  to authenticated;

create policy "Runden lesen: nur Mitglieder"
  on public.rounds for select to authenticated
  using (public.is_round_member(id));

create policy "Spieler lesen: nur Mitglieder der Runde"
  on public.round_players for select to authenticated
  using (public.is_round_member(round_id));

create policy "Mitglieder lesen: nur Mitglieder der Runde"
  on public.round_members for select to authenticated
  using (public.is_round_member(round_id));

create policy "Abende lesen: nur Mitglieder der Runde"
  on public.round_evenings for select to authenticated
  using (public.is_round_member(round_id));

-- ---------------------------------------------------------------------------------------------------
-- round_invites: kein Client-Zugriff (keine GRANTs, keine Richtlinie) – nur über Funktionen.
-- ---------------------------------------------------------------------------------------------------
