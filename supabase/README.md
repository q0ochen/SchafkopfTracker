# Supabase-Backend des Schafkopf Trackers

Der Server ist nur für die **gemeinsame Runde** da: Mehrere Handys tragen in dieselben Abende ein und gleichen sich
über ihn ab. Jedes Gerät hält weiterhin alles selbst und funktioniert ohne Netz und ohne Server. Auf dem Server
liegen nur Tatsachen: die Spieler einer Runde, die Abende im Dateiformat der Sicherung und das Regelwerk für neue
Abende. Stand, Geber und Statistik rechnet jedes Gerät selbst.

Verbindlicher Vertrag: [`docs/ARCHITEKTUR.md`](../docs/ARCHITEKTUR.md), Abschnitt 8. Kosten: **Supabase Free**, ohne
Kreditkarte. Es gibt keine Edge Functions und außer der Google-Anmeldung keine Geheimnisse.

## Inhalt

| Pfad | Inhalt |
|---|---|
| `config.toml` | Einstellungen für die Supabase CLI (lokal und CI): nur Google-Anmeldung, keine E-Mail- oder anonyme Anmeldung |
| `migrations/` | Datenbank: Tabellen, Zugriffsrechte (Row Level Security), die Funktionen aus Abschnitt 8.3 und ein Trigger für gelöschte Konten. Änderungen kommen immer als **neue** Migration dazu (nie eine schon angewendete ändern). |
| `tests/*.test.sql` | pgTAP-Tests: Schema und Rechte, wer was lesen darf, jede Funktion mit jedem Fehlercode und jeder Obergrenze, die Zählung über `rev`, Konto löschen |
| `tests/lokal/` | Prüfung **ohne Docker**: `pruefen.sh` (startet eine Wegwerf-Datenbank), `supabase-attrappe.sql` (bildet das Grundgerüst von Supabase nach), `nebenlaeufig.sh` (gleichzeitige Schreiber) |

## Testen

**Mit Supabase CLI und Docker** (so prüft auch die CI; sie legt die CLI auf Version 2.122.1 fest):

```bash
# im Hauptverzeichnis des Repositorys
supabase start          # startet Postgres, Auth, REST usw. in Docker und wendet die Migrationen an
supabase db reset       # Datenbank leeren und alle Migrationen neu anwenden
supabase test db        # pgTAP-Tests aus supabase/tests
```

**Ohne Docker** (zum Beispiel in der Cloud von Claude Code): Es genügen PostgreSQL, pgTAP und `pg_prove`.

```bash
supabase/tests/lokal/pruefen.sh                  # alles; Ergebnis in der letzten Zeile
supabase/tests/lokal/pruefen.sh --installieren   # holt fehlende Pakete vorher mit apt-get (nur als root)
supabase/tests/lokal/pruefen.sh --hilfe
```

Das Skript legt unter `/var/lib/postgresql/skt-test` eine Wegwerf-Datenbank an, spielt die Attrappe und alle
Migrationen ein (als Rolle `postgres`, die wie bei Supabase kein Superuser ist), führt dieselben pgTAP-Tests aus und
prüft zusätzlich zwei Dinge, die pgTAP nicht kann: Konto löschen mit den Rechten des Auth-Servers und gleichzeitige
Schreiber. Es ersetzt die CI nicht: Die Attrappe ist nachgebaut, das echte Supabase prüft erst der Workflow.

**CI:** `.github/workflows/supabase.yml` läuft bei jeder Änderung unter `supabase/` und prüft auch, dass
mindestens so viele Tests gelaufen sind wie erwartet (die Zahl steht im Workflow und wächst mit den Tests).

## Einrichtung des echten Projekts (einmalig)

### 1. Projekt anlegen

1. Auf [supabase.com](https://supabase.com) anmelden und ein **eigenes** Projekt anlegen (nicht das von
   DrinkWithMe mitbenutzen): **New project**, Name zum Beispiel `schafkopftracker`, Region
   **Frankfurt (eu-central-1)**, Tarif **Free**. Ein sicheres **Datenbank-Passwort** vergeben und aufbewahren.
2. Die **Projekt-Referenz** ist der Teil vor `.supabase.co` in der Projekt-URL (Project Settings → General →
   Project ID), zum Beispiel `abcdefghijklmnopqrst`.

Ein kostenloses Projekt pausiert nach etwa einer Woche ohne Nutzung. Die Apps arbeiten dann einfach ohne Abgleich
weiter; im Dashboard lässt es sich mit „Restore“ wieder starten.

### 2. Google-Anmeldung

1. In der [Google Cloud Console](https://console.cloud.google.com/) ein Projekt anlegen (kostenlos).
2. **OAuth-Zustimmungsbildschirm**: Typ „Extern“, Bereiche nur `openid`, `email`, `profile`. Danach **„In
   Produktion“** veröffentlichen (für diese Bereiche ist keine Prüfung durch Google nötig; im Testmodus liefe die
   Anmeldung nach 7 Tagen ab).
3. **Anmeldedaten → OAuth-Client-ID erstellen**, Typ **Webanwendung**. Autorisierte Weiterleitungs-URI:
   `https://<projekt-ref>.supabase.co/auth/v1/callback`.
4. Im Supabase-Dashboard unter **Authentication → Sign In / Providers**: **Google** einschalten und Client-ID und
   Client-Secret eintragen; **Email** ausschalten; **Allow anonymous sign-ins** aus; Phone aus.
5. Unter **Authentication → URL Configuration**:
   - **Site URL**: `https://q0ochen.github.io/SchafkopfTracker/`
   - **Redirect URLs**: `schafkopftracker://auth-callback` (Android) und
     `https://q0ochen.github.io/SchafkopfTracker/` (Web-App)
6. Unter **Authentication → Audit Logs** „Write audit logs to the database“ ausschalten, damit Anmeldungen nicht
   dauerhaft in der Datenbank protokolliert werden.

Bitte **kein** `supabase config push` ausführen: `config.toml` enthält für Google nur Platzhalter und würde die
Einstellungen aus Schritt 4 überschreiben.

### 3. Bereitstellen über GitHub Actions

Im GitHub-Repository unter **Settings → Secrets and variables → Actions**:

| Art | Name | Wert |
|---|---|---|
| Secret | `SUPABASE_ACCESS_TOKEN` | Supabase-Dashboard → Account → **Access Tokens** → Generate new token |
| Secret | `SUPABASE_DB_PASSWORD` | Datenbank-Passwort aus Schritt 1 |
| Variable | `SUPABASE_PROJECT_REF` | Projekt-Referenz aus Schritt 1 |

Dann **Actions → „Supabase Deploy“ → Run workflow**. Der Workflow bricht mit einer deutschen Fehlermeldung ab, wenn
etwas fehlt, und führt sonst aus:

```bash
supabase link --project-ref "$SUPABASE_PROJECT_REF"
supabase db push            # nur die noch nicht angewendeten Migrationen
```

Er läuft nie von selbst. Nach jeder neuen Migration muss er einmal gestartet werden.

### 4. Werte für die Apps

Öffentlich und unkritisch: die Projekt-URL `https://<projekt-ref>.supabase.co` und der **Publishable Key**
(Project Settings → API Keys). Sie kommen in die GitHub-Variablen `SUPABASE_URL` und `SUPABASE_PUBLISHABLE_KEY`
(Web-App) und in `app/supabase.properties` (`supabaseUrl`, `supabasePublishableKey`, Android).

**Niemals** in Apps oder ins Repository: Secret Key (früher „service_role“), Datenbank-Passwort, Zugriffstoken,
Google-Client-Secret.

## Schnittstellen für die Apps

Alle Aufrufe mit `apikey: <Publishable Key>` und `Authorization: Bearer <Access-Token>`. Einzelheiten und das
Verhalten jeder Funktion stehen im Vertrag (Abschnitt 8.2 und 8.3); hier nur, was man beim Aufrufen wissen muss.

**Lesen** (`GET /rest/v1/<tabelle>`): `rounds`, `round_members`, `round_players`, `round_evenings` – jeweils nur die
Zeilen der Runden, in denen das Konto Mitglied ist. `round_invites` liest niemand. Direktes Schreiben gibt es nicht.

**Funktionen** (`POST /rest/v1/rpc/<name>`, alle Parameter als JSON-Schlüssel angeben):

| Funktion | Parameter | Antwort |
|---|---|---|
| `create_round` | `p_name` | Kennung der Runde als JSON-Text; die neue Runde hat `rev` = 1 |
| `my_rounds` | – | Liste `[{ id, name, rev, member_count, my_player_id, is_creator }]` |
| `create_invite` | `p_round` | Token als JSON-Text (32 Zeichen `0-9a-f`) |
| `invite_info` | `p_token` | Liste mit einem Eintrag `{ round_id, round_name, member_count, already_member }` |
| `join_round` | `p_token` | Kennung der Runde als JSON-Text |
| `leave_round` | `p_round` | leer (HTTP 204) |
| `claim_player` | `p_round`, `p_player`, `p_name`, `p_color_index` | leer (HTTP 204) |
| `save_player` | `p_round`, `p_id`, `p_name`, `p_color_index`, `p_archived`, `p_deleted` | das neue `rev` als Zahl |
| `save_rules` | `p_round`, `p_rules` | das neue `rev` als Zahl |
| `save_evening` | `p_round`, `p_id`, `p_doc`, `p_expected_version`, `p_deleted` | `[{ version, rev }]` – oder `[]`, wenn die erwartete Version nicht passt |

**Fehler** kommen mit HTTP 400 und `{"code": "P0001", "message": "<code>"}`: `not_member`, `invalid_name`,
`name_taken`, `name_locked`, `already_claimed`, `player_taken`, `player_owned`, `invalid_invite`, `limit_reached`,
`too_large`, `invalid_doc`. Ohne gültige Anmeldung antwortet schon die Data API mit 401.

**Obergrenzen:** 5 Runden je Konto, 12 Mitglieder, 40 nicht gelöschte Spieler (400 Zeilen einschließlich der
gelöschten), 3000 Abende je Runde (gelöschte zählen mit), 20 gültige Einladungen je Runde; ein Abend-Dokument
höchstens 300 000 Bytes, ein Regelwerk höchstens 20 000 Bytes (gemessen am JSON-Text, wie PostgreSQL ihn ausgibt).

## Sicherheit und Datenschutz

- Row Level Security auf **jeder** Tabelle. Nicht angemeldete Besucher (`anon`) haben keinerlei Rechte; angemeldete
  Konten lesen nur ihre eigenen Runden und schreiben ausschließlich über die Funktionen.
- Alle Funktionen sind `SECURITY DEFINER` mit `search_path = ''` und vollständig qualifizierten Namen. Jede prüft
  zuerst die Anmeldung (anonyme Konten werden abgewiesen, falls die anonyme Anmeldung je versehentlich
  eingeschaltet würde) und die Mitgliedschaft. Wer kein Mitglied ist, bekommt immer `not_member` – auch für Runden,
  die es nicht gibt. Hilfsfunktionen liegen im Schema `private`, das die Data API nicht ausliefert.
- Jede Änderung erhöht zuerst `rounds.rev` und stempelt die geänderten Zeilen. Die Zeilensperre dabei reiht alle
  Schreiber einer Runde hintereinander; so entgeht einem Gerät, das „alles mit `rev` größer als …“ liest, nichts.
- Von einer Einladung wird nur der SHA-256-Wert gespeichert; sie gilt 14 Tage.
- Auf dem Server liegen die Namen, die die Runde ihren Spielern gibt, und die Abende mit ihren Spielen. Lesen können
  sie nur die Mitglieder der Runde (und der Inhaber des Supabase-Projekts über das Dashboard). Supabase selbst
  speichert zu jedem Konto die Google-Adresse; die Tabellen dieser Anwendung enthalten sie nicht.
- **Konto löschen** (Dashboard → Authentication → Users): Die Mitgliedschaften und Einladungen des Kontos
  verschwinden, sein Spieler wird zum Gast (der Name bleibt), und die anderen Geräte erfahren davon. War es das
  letzte Mitglied einer Runde, wird die Runde gelöscht. Dasselbe gilt, wenn das letzte Mitglied die Runde verlässt.
