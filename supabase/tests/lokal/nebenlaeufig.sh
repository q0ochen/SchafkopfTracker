#!/usr/bin/env bash
# Prüft mit je zwei gleichzeitigen Sitzungen, was pgTAP (eine Sitzung) nicht zeigen kann: Die Zeilensperre auf
# public.rounds reiht alle Schreiber einer Runde hintereinander (docs/ARCHITEKTUR.md, Abschnitt 8.2).
# Wird von pruefen.sh aufgerufen; die Verbindung kommt aus der Umgebung:
#   SKT_PSQL (Pfad zu psql), SKT_HOST (Socket-Verzeichnis), SKT_PORT, SKT_DB
# Die Datenbank muss die Supabase-Attrappe und alle Migrationen enthalten. Die Konten des Skripts (und mit ihnen
# ihre Runden) werden am Anfang und am Ende gelöscht.
#
# Ablauf jedes Falls: Sitzung A schreibt in einer offenen Transaktion und hält damit die Sperre. Sie meldet das
# über eine Advisory-Sperre und schließt ihre Transaktion erst ab, wenn sie Sitzung B auf die Sperre warten sieht
# (höchstens 10 Sekunden – wartet niemand, scheitert der Fall). Es gibt also keine Wettläufe mit festen Pausen.
set -uo pipefail

psql=("${SKT_PSQL:?}" -X -q -At -v ON_ERROR_STOP=1 -h "${SKT_HOST:?}" -p "${SKT_PORT:?}" -d "${SKT_DB:?}")
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fehler=0
signal=4711

admin() { "${psql[@]}" -U supabase_admin -c "$1"; }
konto() { printf '99999999-9999-4999-8999-%012d' "$1"; }
anmelden() {
  cat << SQL
set role authenticated;
select set_config('request.jwt.claims', '{"sub": "$(konto "$1")", "role": "authenticated"}', false) is null;
\\set VERBOSITY terse
SQL
}
# Führt als Konto $1 den SQL-Ausdruck $2 aus und gibt sein Ergebnis aus (ohne Nebenläufigkeit).
als() {
  "${psql[@]}" -U postgres << SQL | tail -n 1
$(anmelden "$1")
select $2;
SQL
}
# Sitzung A: Konto $1 wertet den Ausdruck $2 in einer Transaktion aus, die offen bleibt, bis jemand wartet.
starte_a() {
  "${psql[@]}" -U postgres > "$tmp/a.out" 2>&1 << SQL &
$(anmelden "$1")
begin;
select 'A=' || coalesce(($2)::text, 'leer');
select pg_advisory_lock($signal) is null;
do \$\$
begin
  for i in 1..400 loop
    if exists (select 1 from pg_catalog.pg_locks l where not l.granted) then
      return;
    end if;
    perform pg_sleep(0.025);
  end loop;
  raise exception 'Niemand hat auf die Sperre gewartet';
end
\$\$;
commit;
SQL
  a_pid=$!
  local i
  for i in $(seq 1 200); do
    [ "$(admin "select count(*) from pg_locks where locktype = 'advisory' and objid = $signal and granted")" = "1" ] && return 0
    kill -0 "$a_pid" 2> /dev/null || break
    sleep 0.05
  done
  echo "   Sitzung A hat die Sperre nicht bekommen: $(tail -n 1 "$tmp/a.out")"
  return 1
}
# Sitzung B: Konto $1 wertet den Ausdruck $2 aus (wartet dabei auf A); danach wird auf das Ende von A gewartet.
laufe_b() {
  "${psql[@]}" -U postgres > "$tmp/b.out" 2>&1 << SQL || true
$(anmelden "$1")
select 'B=' || coalesce(($2)::text, 'leer');
SQL
  wait "$a_pid" || true
}
# Was eine Sitzung geliefert hat: den Wert hinter „A=“ bzw. „B=“ oder die Fehlermeldung.
von() {
  local zeile
  zeile="$(grep -E "^($1=|ERROR:)" "$tmp/$2.out" | head -n 1)"
  case "$zeile" in
    "$1="*) echo "${zeile#"$1="}" ;;
    *) echo "${zeile#ERROR:  }" ;;
  esac
}
pruefe() { # $1 = Beschreibung, $2 = erwartet, $3 = erhalten
  if [ "$2" = "$3" ]; then
    echo "   ok – $1"
  else
    echo "   FEHLER – $1: erwartet „$2“, erhalten „$3“"
    fehler=1
  fi
}

# ---------------------------------------------------------------------------------------------------
# Ausgangslage: 14 Konten; Runde mit Konto 1 (Ersteller) und Konto 2
# ---------------------------------------------------------------------------------------------------
aufraeumen() {
  admin "delete from auth.users where id::text like '99999999-9999-4999-8999-%'" > /dev/null
}
aufraeumen
admin "insert into auth.users (id, aud, role, email)
       select ('99999999-9999-4999-8999-' || lpad(n::text, 12, '0'))::uuid, 'authenticated', 'authenticated',
              'nebenlaeufig' || n || '@example.org'
       from generate_series(1, 14) n" > /dev/null
runde="$(als 1 "public.create_round('Nebenläufig')")"
token="$(als 1 "public.create_invite('$runde')")"
als 2 "public.join_round('$token')" > /dev/null
rev() { admin "select rev from public.rounds where id = '$runde'"; }

echo "1. Zwei Schreiber gleichzeitig: Der zweite wartet und bekommt das nächste rev"
vorher="$(rev)"
starte_a 1 "select version || '/' || rev from public.save_evening('$runde', 'abend-a', '{\"id\": \"abend-a\"}', 0, false)" || fehler=1
pruefe "Solange A nicht abgeschlossen hat, sehen Leser das alte rev" "$vorher" "$(rev)"
laufe_b 2 "public.save_player('$runde', 'gast-b', 'Gast B', 1, false, false)"
pruefe "A (save_evening) bekommt rev + 1" "1/$((vorher + 1))" "$(von A a)"
pruefe "B (save_player) hat gewartet und bekommt rev + 2" "$((vorher + 2))" "$(von B b)"
pruefe "Die Zeilen sind in dieser Reihenfolge gestempelt" "abend-a=$((vorher + 1)) gast-b=$((vorher + 2))" \
  "$(admin "select string_agg(x, ' ' order by x) from (
              select 'abend-a=' || rev as x from public.round_evenings where round_id = '$runde' and id = 'abend-a'
              union all select 'gast-b=' || rev from public.round_players where round_id = '$runde' and id = 'gast-b') t")"

echo "2. Derselbe neue Abend von zwei Geräten: Der zweite bekommt eine leere Antwort"
vorher="$(rev)"
starte_a 1 "select version || '/' || rev from public.save_evening('$runde', 'gleich', '{\"id\": \"gleich\", \"von\": \"a\"}', 0, false)" || fehler=1
laufe_b 2 "select version || '/' || rev from public.save_evening('$runde', 'gleich', '{\"id\": \"gleich\", \"von\": \"b\"}', 0, false)"
pruefe "A legt den Abend an" "1/$((vorher + 1))" "$(von A a)"
pruefe "B bekommt keine Zeile" "leer" "$(von B b)"
pruefe "Es gilt die Fassung von A, und rev ist nur einmal gestiegen" "a/1/$((vorher + 1))" \
  "$(admin "select (e.doc ->> 'von') || '/' || e.version || '/' || r.rev
              from public.round_evenings e join public.rounds r on r.id = e.round_id
             where e.round_id = '$runde' and e.id = 'gleich'")"

echo "3. Dasselbe Konto verlässt die Runde auf einem Gerät und schreibt auf einem anderen"
starte_a 2 "select public.leave_round('$runde') is null" || fehler=1
laufe_b 2 "public.save_rules('$runde', '{\"von\": \"b\"}')"
pruefe "Das Verlassen gelingt" "false" "$(von A a)"
pruefe "Der Schreiber ist danach kein Mitglied mehr" "not_member" "$(von B b)"
pruefe "Das Regelwerk ist unverändert" "leer" "$(admin "select coalesce(rules::text, 'leer') from public.rounds where id = '$runde'")"

echo "4. Zwei Beitritte auf den letzten freien Platz (11 Mitglieder vorhanden)"
for n in 2 3 4 5 6 7 8 9 10 11; do als "$n" "public.join_round('$token')" > /dev/null; done
pruefe "Die Runde hat 11 Mitglieder" "11" "$(admin "select count(*) from public.round_members where round_id = '$runde'")"
starte_a 12 "public.join_round('$token')" || fehler=1
laufe_b 13 "public.join_round('$token')"
pruefe "Der erste Beitritt gelingt" "$runde" "$(von A a)"
pruefe "Der zweite wartet und wird abgewiesen" "limit_reached" "$(von B b)"
pruefe "Die Runde hat genau 12 Mitglieder" "12" "$(admin "select count(*) from public.round_members where round_id = '$runde'")"

echo "5. Dasselbe Konto legt auf zwei Geräten gleichzeitig seine fünfte und sechste Runde an"
for n in 1 2 3 4; do als 14 "public.create_round('Runde $n')" > /dev/null; done
starte_a 14 "select public.create_round('Die fünfte') is not null" || fehler=1
laufe_b 14 "select public.create_round('Die sechste') is not null"
pruefe "Die fünfte Runde entsteht" "true" "$(von A a)"
pruefe "Die sechste wartet und wird abgewiesen" "limit_reached" "$(von B b)"
pruefe "Das Konto hat genau 5 Runden" "5" "$(admin "select count(*) from public.round_members where user_id = '$(konto 14)'")"

echo "6. Die letzten beiden Mitglieder verlassen die Runde gleichzeitig"
for n in 3 4 5 6 7 8 9 10 11 12; do als "$n" "public.leave_round('$runde') is null" > /dev/null; done
pruefe "Es sind noch zwei Mitglieder" "2" "$(admin "select count(*) from public.round_members where round_id = '$runde'")"
starte_a 1 "select public.leave_round('$runde') is null" || fehler=1
laufe_b 2 "select public.leave_round('$runde') is null"
pruefe "Beide können gehen" "false false" "$(von A a) $(von B b)"
pruefe "Danach gibt es die Runde nicht mehr (auch keine Abende und Spieler)" "0 0 0" \
  "$(admin "select (select count(*) from public.rounds where id = '$runde') || ' ' ||
                   (select count(*) from public.round_evenings where round_id = '$runde') || ' ' ||
                   (select count(*) from public.round_players where round_id = '$runde')")"

# Seit dem 21.11.2026 hat ein Konto keine zwei Runden desselben Namens (docs/ARCHITEKTUR.md, 8.7); lock_user reiht
# zwei Geräte desselben Kontos hintereinander, so sieht das zweite die Runde des ersten.
echo "7. Dasselbe Konto legt auf zwei Geräten gleichzeitig zwei Runden desselben Namens an"
starte_a 13 "select public.create_round('Doppelt') is not null" || fehler=1
laufe_b 13 "select public.create_round('doppelt') is not null"
pruefe "Die erste Runde entsteht" "true" "$(von A a)"
pruefe "Die zweite wartet und wird abgewiesen" "name_taken" "$(von B b)"
pruefe "Das Konto hat genau eine Runde dieses Namens" "1" \
  "$(admin "select count(*) from public.round_members m join public.rounds r on r.id = m.round_id
             where m.user_id = '$(konto 13)' and lower(r.name) = 'doppelt'")"

# Die Konten löschen: Der Trigger auf auth.users entfernt dabei ihre Mitgliedschaften und die leeren Runden.
runden="$(admin "select coalesce(string_agg(distinct quote_literal(round_id), ', '), 'null')
                   from public.round_members where user_id::text like '99999999-9999-4999-8999-%'")"
aufraeumen
pruefe "Nach dem Löschen der Konten ist von ihren Runden nichts übrig" "0" \
  "$(admin "select count(*) from public.rounds where id::text in ($runden)")"

exit "$fehler"
