#!/usr/bin/env bash
# Prüft das Supabase-Backend OHNE Docker und ohne Supabase CLI – für Umgebungen, in denen „supabase start“ nicht
# geht (zum Beispiel die Cloud von Claude Code). Die CI (.github/workflows/supabase.yml) nutzt dieses Skript NICHT;
# sie prüft mit dem echten lokalen Supabase.
#
# Ablauf:
#   1. Wegwerf-Cluster von PostgreSQL anlegen und starten (nur beim ersten Mal; lauscht nur auf einem Unix-Socket)
#   2. frische Datenbank, darin die Attrappe des Supabase-Grundgerüsts (supabase-attrappe.sql)
#   3. alle Migrationen aus supabase/migrations der Reihe nach – als Rolle postgres, die wie bei Supabase KEIN
#      Superuser ist
#   4. pgTAP-Tests aus supabase/tests mit pg_prove, genau wie „supabase test db“
#   5. Zusatzprüfung: Konto löschen als supabase_auth_admin (so löscht der Auth-Server von Supabase)
#   6. Zusatzprüfung: gleichzeitige Schreiber (nebenlaeufig.sh) – die Sperre, die alle Änderungen einer Runde
#      hintereinander reiht
#
# Aufruf:   supabase/tests/lokal/pruefen.sh [Optionen] [Testdatei …]
#   -v, --ausfuehrlich   jeden einzelnen Test anzeigen
#   --installieren       fehlende Pakete mit apt-get holen (nur als root): PostgreSQL, pgTAP, pg_prove
#   --laufen-lassen      den Cluster am Ende nicht stoppen (zum Nachsehen mit psql; der Befehl wird angezeigt)
#   --aufraeumen         Cluster stoppen und samt Daten löschen, sonst nichts tun
#   -h, --hilfe          diese Hilfe
# Umgebungsvariablen: SKT_PG_BIN (Verzeichnis mit initdb, pg_ctl, psql), SKT_PG_DIR (Arbeitsverzeichnis,
# Vorgabe /var/lib/postgresql/skt-test), SKT_PG_PORT (Vorgabe 54329).
#
# Ende mit 0, wenn alles bestanden ist, sonst mit 1 (Tests) oder 2 (Voraussetzungen, Migrationen).
set -euo pipefail

ausfuehrlich=0
installieren=0
laufen_lassen=0
aufraeumen=0
testdateien=()
while [ $# -gt 0 ]; do
  case "$1" in
    -v | --ausfuehrlich) ausfuehrlich=1 ;;
    --installieren) installieren=1 ;;
    --laufen-lassen) laufen_lassen=1 ;;
    --aufraeumen) aufraeumen=1 ;;
    -h | --hilfe | --help)
      sed -n '2,/^set -euo pipefail$/{/^set -euo pipefail$/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    -*)
      echo "Unbekannte Option: $1 (Hilfe: $0 --hilfe)" >&2
      exit 2
      ;;
    *) testdateien+=("$1") ;;
  esac
  shift
done

verzeichnis="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
supabase="$(cd "$verzeichnis/../.." && pwd)"
port="${SKT_PG_PORT:-54329}"
datenbank="skt_test"

abbruch() {
  echo "FEHLER: $*" >&2
  exit 2
}

# ---------------------------------------------------------------------------------------------------
# Als wer läuft der Server? PostgreSQL verweigert den Start als root; dann übernimmt der Benutzer postgres.
# ---------------------------------------------------------------------------------------------------
if [ "$(id -u)" -eq 0 ]; then
  id postgres > /dev/null 2>&1 || [ "$installieren" -eq 1 ] ||
    abbruch "Als root braucht das Skript den Benutzer „postgres“ (kommt mit dem Paket postgresql). Mit --installieren holt es die Pakete selbst."
  als_server() { runuser -u postgres -- "$@"; }
  arbeit="${SKT_PG_DIR:-/var/lib/postgresql/skt-test}"
else
  als_server() { "$@"; }
  arbeit="${SKT_PG_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/skt-test}"
fi
daten="$arbeit/daten"
protokoll="$arbeit/postgres.log"

# ---------------------------------------------------------------------------------------------------
# Voraussetzungen: PostgreSQL (Server), pgTAP und pg_prove
# ---------------------------------------------------------------------------------------------------
finde_bin() {
  if [ -n "${SKT_PG_BIN:-}" ]; then
    echo "$SKT_PG_BIN"
    return
  fi
  local kandidat
  # Die neueste installierte Version unter /usr/lib/postgresql (Debian/Ubuntu), sonst was im PATH liegt.
  kandidat="$(ls -d /usr/lib/postgresql/*/bin 2> /dev/null | sort -t/ -k5 -n | tail -n 1 || true)"
  if [ -n "$kandidat" ] && [ -x "$kandidat/initdb" ]; then
    echo "$kandidat"
  elif command -v initdb > /dev/null 2>&1; then
    dirname "$(command -v initdb)"
  fi
}

bin="$(finde_bin)"
fehlt=()
if [ -z "$bin" ] || [ ! -x "$bin/initdb" ] || [ ! -x "$bin/pg_ctl" ] || [ ! -x "$bin/psql" ]; then
  fehlt+=("postgresql")
else
  version="$("$bin/pg_config" --version 2> /dev/null | sed -E 's/^PostgreSQL ([0-9]+).*/\1/' || true)"
  [ -n "$version" ] || version="$("$bin/psql" --version | sed -E 's/.* ([0-9]+)\..*/\1/')"
  freigabe="$("$bin/pg_config" --sharedir 2> /dev/null || echo "/usr/share/postgresql/$version")"
  [ -f "$freigabe/extension/pgtap.control" ] || fehlt+=("postgresql-$version-pgtap")
fi
command -v pg_prove > /dev/null 2>&1 || fehlt+=("libtap-parser-sourcehandler-pgtap-perl")

if [ "${#fehlt[@]}" -gt 0 ]; then
  if [ "$installieren" -eq 1 ]; then
    [ "$(id -u)" -eq 0 ] || abbruch "--installieren geht nur als root. Es fehlt: ${fehlt[*]}"
    command -v apt-get > /dev/null 2>&1 || abbruch "--installieren braucht apt-get. Es fehlt: ${fehlt[*]}"
    # Ohne Server ist die Version noch unbekannt: Das Paket „pgtap“ zieht die passende Erweiterung mit.
    pakete=("${fehlt[@]}")
    [[ " ${fehlt[*]} " == *" postgresql "* ]] && pakete+=("pgtap")
    echo "== Installiere fehlende Pakete: ${pakete[*]}"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q "${pakete[@]}"
    weiter=()
    [ "$ausfuehrlich" -eq 1 ] && weiter+=(-v)
    [ "$laufen_lassen" -eq 1 ] && weiter+=(--laufen-lassen)
    exec "$0" ${weiter[@]+"${weiter[@]}"} ${testdateien[@]+"${testdateien[@]}"}
  fi
  abbruch "Es fehlt: ${fehlt[*]}.
  Als root:  $0 --installieren
  oder:      apt-get install -y postgresql postgresql-<Version>-pgtap libtap-parser-sourcehandler-pgtap-perl"
fi

psql_admin=("$bin/psql" -X -q -v ON_ERROR_STOP=1 -h "$arbeit" -p "$port" -U supabase_admin)
psql_postgres=("$bin/psql" -X -q -v ON_ERROR_STOP=1 -h "$arbeit" -p "$port" -U postgres)

laeuft() { als_server "$bin/pg_ctl" -D "$daten" status > /dev/null 2>&1; }
stoppen() { als_server "$bin/pg_ctl" -D "$daten" -m fast -w stop > /dev/null 2>&1 || true; }

if [ "$aufraeumen" -eq 1 ]; then
  if [ -f "$daten/PG_VERSION" ]; then
    stoppen
    als_server rm -rf "$arbeit"
    echo "Cluster gestoppt und gelöscht: $arbeit"
  else
    echo "Nichts aufzuräumen: $arbeit"
  fi
  exit 0
fi

# ---------------------------------------------------------------------------------------------------
# 1. Wegwerf-Cluster. Der Superuser heißt wie bei Supabase supabase_admin; „postgres“ legt erst die Attrappe
#    an, als gewöhnliche Rolle. Kein TCP, nur ein Unix-Socket im Arbeitsverzeichnis, nur für den Eigentümer.
# ---------------------------------------------------------------------------------------------------
if [ ! -f "$daten/PG_VERSION" ]; then
  echo "== Lege den Wegwerf-Cluster an: $arbeit (PostgreSQL $version)"
  als_server mkdir -p -m 700 "$arbeit"
  # Eine UTF-8-Umgebung, damit lower() auch Umlaute umwandelt (wie bei Supabase). Fehlt sie, bleibt es bei der
  # Vorgabe des Systems; der eine Test zu Umlauten meldet sich dann als übersprungen.
  sprache=()
  for kandidat in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
    if locale -a 2> /dev/null | grep -qxF "$kandidat"; then
      sprache=(--locale="$kandidat")
      break
    fi
  done
  als_server "$bin/initdb" -D "$daten" -U supabase_admin --auth=trust --encoding=UTF8 \
    ${sprache[@]+"${sprache[@]}"} > /dev/null
fi

if ! laeuft; then
  als_server "$bin/pg_ctl" -D "$daten" -l "$protokoll" -w -t 60 \
    -o "-c listen_addresses='' -c port=$port -c unix_socket_directories='$arbeit' -c unix_socket_permissions=0700 -c fsync=off -c lc_messages=C" \
    start > /dev/null || abbruch "PostgreSQL startet nicht. Protokoll: $protokoll"
fi
# Am Ende läuft nichts weiter – außer es ist ausdrücklich gewünscht.
beenden() {
  if [ "$laufen_lassen" -eq 0 ]; then
    stoppen
  fi
}
trap beenden EXIT

# ---------------------------------------------------------------------------------------------------
# 2. und 3. Frische Datenbank: Attrappe (als Superuser), dann die Migrationen (als postgres, je Datei eine
#    Transaktion – wie „supabase db reset“ und „supabase db push“).
# ---------------------------------------------------------------------------------------------------
echo "== Frische Datenbank „$datenbank“ mit der Supabase-Attrappe"
PGOPTIONS="-c client_min_messages=warning" "${psql_admin[@]}" -d postgres \
  -c "drop database if exists $datenbank with (force)" -c "create database $datenbank" ||
  abbruch "Die Datenbank lässt sich nicht anlegen. Protokoll: $protokoll"
"${psql_admin[@]}" -d "$datenbank" -v attrappe=1 -f "$verzeichnis/supabase-attrappe.sql" ||
  abbruch "Die Supabase-Attrappe lässt sich nicht einspielen."

anzahl=0
for migration in "$supabase"/migrations/*.sql; do
  [ -e "$migration" ] || abbruch "Keine Migrationen in $supabase/migrations gefunden."
  echo "== Migration $(basename "$migration")"
  "${psql_postgres[@]}" -d "$datenbank" --single-transaction -f "$migration" ||
    abbruch "Die Migration $(basename "$migration") ist gescheitert."
  anzahl=$((anzahl + 1))
done

# ---------------------------------------------------------------------------------------------------
# 4. pgTAP-Tests. „supabase test db“ legt pgTAP im Schema extensions an und ruft pg_prove mit denselben
#    Schaltern für das ganze Verzeichnis supabase/tests auf; die Tests laufen als Rolle postgres.
# ---------------------------------------------------------------------------------------------------
"${psql_admin[@]}" -d "$datenbank" -c "create extension if not exists pgtap with schema extensions" ||
  abbruch "pgTAP lässt sich nicht einrichten."

[ "${#testdateien[@]}" -gt 0 ] || testdateien=("$supabase/tests")
schalter=(--ext .pg --ext .sql -r)
[ "$ausfuehrlich" -eq 1 ] && schalter+=(-v)
echo "== pgTAP-Tests (pg_prove)"
ausgabe="$(mktemp)"
tests_ok=1
pg_prove -b "$bin/psql" -h "$arbeit" -p "$port" -U postgres -d "$datenbank" "${schalter[@]}" "${testdateien[@]}" 2>&1 |
  tee "$ausgabe" || tests_ok=0
# pg_prove zählt die Attrappe als (übersprungene) Datei mit, weil auch „supabase test db“ sie aufruft.
zusammenfassung="$(grep -E '^Files=[0-9]+, Tests=[0-9]+' "$ausgabe" | tail -n 1 | sed -E 's/^Files=([0-9]+), Tests=([0-9]+).*/\2 Tests in \1 Dateien/' || true)"
grep -q '^Result: PASS' "$ausgabe" || tests_ok=0
rm -f "$ausgabe"

# ---------------------------------------------------------------------------------------------------
# 5. Zusatzprüfung, die nur hier möglich ist: Der Auth-Server von Supabase löscht Konten als Rolle
#    supabase_auth_admin, die in public nichts darf. Der Trigger auf auth.users muss trotzdem aufräumen.
#    (In „supabase test db“ laufen die Tests als postgres; dort lässt sich die Rolle nicht annehmen.)
# ---------------------------------------------------------------------------------------------------
echo "== Zusatzprüfung: Konto löschen als supabase_auth_admin"
zusatz_ok=1
"${psql_admin[@]}" -d "$datenbank" > /dev/null << 'SQL' || zusatz_ok=0
begin;
insert into auth.users (id, aud, role, email) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'authenticated', 'authenticated', 'anna@example.org'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'authenticated', 'authenticated', 'ben@example.org');
create temp table merkzettel (stichwort text primary key, wert text not null);
grant all on merkzettel to public;

set local role authenticated;
select set_config('request.jwt.claims', '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}', true);
insert into merkzettel values ('gemeinsam', public.create_round('Gemeinsam')::text);
insert into merkzettel values ('allein', public.create_round('Allein')::text);
insert into merkzettel
  select 'token', public.create_invite(wert::uuid) from merkzettel where stichwort = 'gemeinsam';
select public.claim_player(wert::uuid, 'spieler-a', 'Anna', 0) from merkzettel where stichwort = 'gemeinsam';
select set_config('request.jwt.claims', '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}', true);
select public.join_round(wert) from merkzettel where stichwort = 'token';

reset role;
set local role supabase_auth_admin;
do $$
begin
  -- Gegenprobe: Die Rolle selbst darf in public und private wirklich nichts.
  begin
    perform 1 from public.rounds;
    raise exception 'supabase_auth_admin kann public.rounds lesen – die Zusatzprüfung wäre wertlos';
  exception when insufficient_privilege then
    null;
  end;
end
$$;
delete from auth.users where id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

reset role;
do $$
declare
  gemeinsam uuid := (select wert::uuid from merkzettel where stichwort = 'gemeinsam');
  allein uuid := (select wert::uuid from merkzettel where stichwort = 'allein');
begin
  assert (select count(*) from public.round_members where round_id = gemeinsam) = 1,
    'In der gemeinsamen Runde bleibt genau ein Mitglied';
  assert (select rev from public.rounds where id = gemeinsam) = 4,
    'rev der gemeinsamen Runde ist gestiegen (anlegen, „Das bin ich“, Beitritt, Konto gelöscht)';
  assert (select owner is null and name_locked and rev = 4 from public.round_players where round_id = gemeinsam),
    'Der Spieler des gelöschten Kontos ist ein Gast und gestempelt';
  assert not exists (select 1 from public.rounds where id = allein),
    'Die Runde ohne Mitglieder ist gelöscht';
  assert not exists (select 1 from public.round_invites where round_id = gemeinsam),
    'Die Einladung des gelöschten Kontos ist gelöscht';
end
$$;
rollback;
SQL
if [ "$zusatz_ok" -eq 1 ]; then echo "ok"; else echo "FEHLGESCHLAGEN"; fi

# ---------------------------------------------------------------------------------------------------
# 6. Zusatzprüfung mit je zwei gleichzeitigen Sitzungen (pgTAP kennt nur eine): zwei Schreiber, derselbe neue
#    Abend von zwei Geräten, Verlassen und Schreiben zugleich, Wettlauf um den letzten Platz. Läuft zuletzt,
#    weil sie Daten in der Wegwerf-Datenbank hinterlässt.
# ---------------------------------------------------------------------------------------------------
echo "== Zusatzprüfung: gleichzeitige Schreiber"
neben_ok=1
SKT_PSQL="$bin/psql" SKT_HOST="$arbeit" SKT_PORT="$port" SKT_DB="$datenbank" "$verzeichnis/nebenlaeufig.sh" || neben_ok=0

# ---------------------------------------------------------------------------------------------------
# Zusammenfassung
# ---------------------------------------------------------------------------------------------------
echo
echo "== Zusammenfassung"
echo "Migrationen: $anzahl angewendet (frische Datenbank, als Rolle postgres ohne Superuser-Rechte)"
echo "pgTAP:       ${zusammenfassung:-keine Zusammenfassung von pg_prove} – $([ "$tests_ok" -eq 1 ] && echo bestanden || echo FEHLGESCHLAGEN)"
echo "Zusatz:      Konto löschen als supabase_auth_admin – $([ "$zusatz_ok" -eq 1 ] && echo bestanden || echo FEHLGESCHLAGEN)"
echo "Zusatz:      gleichzeitige Schreiber – $([ "$neben_ok" -eq 1 ] && echo bestanden || echo FEHLGESCHLAGEN)"
if [ "$laufen_lassen" -eq 1 ]; then
  echo "Der Cluster läuft weiter:  $bin/psql -h $arbeit -p $port -U postgres -d $datenbank"
  echo "Stoppen und löschen:       $0 --aufraeumen"
fi
if [ "$tests_ok" -eq 1 ] && [ "$zusatz_ok" -eq 1 ] && [ "$neben_ok" -eq 1 ]; then
  echo "ERGEBNIS: BESTANDEN"
  exit 0
fi
echo "ERGEBNIS: FEHLGESCHLAGEN"
exit 1
