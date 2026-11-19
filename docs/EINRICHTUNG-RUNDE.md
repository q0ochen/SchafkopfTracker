# Gemeinsame Runde einrichten – Schritt für Schritt

Stand: 19.11.2026. Diese Anleitung ist für den Projektinhaber. Sie bringt den Server zum Laufen, über den alle vier
vom eigenen Handy in denselben Abend eintragen. Das geht **kostenlos und ohne Kreditkarte**, dauert etwa 30 bis
45 Minuten und ist **einmalig**. Installieren musst du nichts; alles passiert im Browser, am besten an einem Computer.

Die Mitspieler richten nichts ein. Sie brauchen nur ein Google-Konto und die App.

> **Gut zu wissen:** Ohne diese Einrichtung funktionieren beide Apps wie bisher. In der App steht dann unter
> „Gemeinsame Runde“ nur „Der Server ist noch nicht eingerichtet.“

Die Schritte sind fast dieselben wie damals bei Drink With Me. Wichtig ist nur: Der Schafkopf Tracker bekommt ein
**eigenes** Supabase-Projekt.

## Überblick

1. [Supabase-Projekt anlegen](#1-supabase-projekt-anlegen)
2. [Google: Anmeldung erlauben](#2-google-anmeldung-erlauben)
3. [Supabase: Anmeldung einstellen](#3-supabase-anmeldung-einstellen)
4. [GitHub: Werte hinterlegen](#4-github-werte-hinterlegen)
5. [Claude Bescheid geben](#5-claude-bescheid-geben)
6. [Ausprobieren](#6-ausprobieren)
7. [Vor jedem Spielabend](#7-vor-jedem-spielabend)

## Spickzettel: Diese Werte sammelst du unterwegs

**GEHEIM** heißt: nie ins Repository, nie in eine App und **nie in einen Chat** (auch nicht an Claude). Geheime Werte
trägst du selbst bei Supabase oder GitHub ein.

| Wert | Wie er aussieht | Wohin damit | Geheim? |
|---|---|---|---|
| Projekt-Referenz | 20 Kleinbuchstaben und Ziffern | GitHub-Variable `SUPABASE_PROJECT_REF` | nein |
| Projekt-URL | `https://<projekt-referenz>.supabase.co` | GitHub-Variable `SUPABASE_URL` | nein |
| Publishable Key | beginnt mit `sb_publishable_` | GitHub-Variable `SUPABASE_PUBLISHABLE_KEY` | nein |
| Datenbank-Passwort | von Supabase erzeugt | GitHub-Secret `SUPABASE_DB_PASSWORD` | **GEHEIM** |
| Access Token | beginnt mit `sbp_` | GitHub-Secret `SUPABASE_ACCESS_TOKEN` | **GEHEIM** |
| Google Client-ID | endet auf `.apps.googleusercontent.com` | nur bei Supabase (Schritt 3) | nein |
| Google Client-Secret | beginnt mit `GOCSPX-` | nur bei Supabase (Schritt 3) | **GEHEIM** |

## 1. Supabase-Projekt anlegen

1. Auf [supabase.com](https://supabase.com) anmelden (dasselbe Konto wie bei Drink With Me).
2. **New project**:
   - **Name:** zum Beispiel `schafkopftracker`
   - **Database Password:** „Generate a password“, dann kopieren und sicher aufbewahren (Passwort-Manager).
   - **Region:** **Central EU (Frankfurt)**
   - Tarif: **Free**
3. **Create new project** und ein, zwei Minuten warten.
4. Die **Projekt-Referenz** notieren: **Project Settings → General → Project ID**. Die Projekt-URL ist dann
   `https://<projekt-referenz>.supabase.co`.
5. Den **Publishable Key** notieren: **Project Settings → API Keys**, der Schlüssel, der mit `sb_publishable_`
   beginnt. **Nicht** den „secret“-Schlüssel nehmen.

Kostenlos sind zwei Projekte; mit Drink With Me sind es dann genau zwei.

## 2. Google: Anmeldung erlauben

Damit „Mit Google anmelden“ funktioniert, braucht Supabase eine Client-ID und ein Client-Secret von Google. Auch das
kostet nichts. Fragt Google nach einem Rechnungskonto oder einer „kostenlosen Testversion“: ablehnen.

1. [console.cloud.google.com](https://console.cloud.google.com/) öffnen. Oben in der Projektauswahl **Neues
   Projekt**, Name `Schafkopf Tracker`, **Erstellen**. (Gibt es das Projekt schon, zum Beispiel für die Sicherung in
   Google Drive, nimm dieses.) Danach prüfen, dass oben dieses Projekt ausgewählt ist.
2. Menü **Google Auth Platform** (früher „OAuth-Zustimmungsbildschirm“) → **Jetzt starten**: App-Name
   `Schafkopf Tracker`, deine E-Mail-Adresse für Support und Kontakt, Zielgruppe **Extern**.
3. **Branding:** kein Logo hochladen (das würde eine Prüfung durch Google auslösen). Unter **Autorisierte Domains**
   `<projekt-referenz>.supabase.co` eintragen und speichern.
4. **Zielgruppe** → **App veröffentlichen**, bis dort **„In Produktion“** steht. Im Status „Testen“ würde die
   Anmeldung nach 7 Tagen ablaufen.
5. **Clients** → **Client erstellen**:
   - **Anwendungstyp:** **Webanwendung** (auch für die Android-App; die Anmeldung läuft dort im Browser)
   - **Name:** zum Beispiel `Schafkopf Tracker (Supabase)`
   - **Autorisierte Weiterleitungs-URIs** → **URI hinzufügen**, genau diese Adresse mit deiner Referenz:

     ```
     https://<projekt-referenz>.supabase.co/auth/v1/callback
     ```
6. **Erstellen**. **Client-ID und Client-Secret sofort kopieren**; Google zeigt das Secret nur dieses eine Mal.

Beim Anmelden kann Google statt „Schafkopf Tracker“ die Adresse `….supabase.co` anzeigen. Das ist normal.

## 3. Supabase: Anmeldung einstellen

Im Supabase-Dashboard des neuen Projekts:

1. **Authentication → Sign In / Providers:**
   - **Allow new users to sign up:** an lassen
   - **Allow anonymous sign-ins:** aus
   - **Email:** öffnen, ausschalten, speichern
   - **Google:** öffnen, einschalten, **Client-ID** und **Client-Secret** aus Schritt 2 eintragen, **Save**
2. **Authentication → URL Configuration:**
   - **Site URL:** `https://q0ochen.github.io/SchafkopfTracker/`
   - **Redirect URLs** → **Add URL**, genau diese zwei Einträge:

     ```
     schafkopftracker://auth-callback
     https://q0ochen.github.io/SchafkopfTracker/
     ```

     Der erste führt zurück in die Android-App, der zweite in die Web-App.
3. Empfohlen: **Authentication → Audit Logs** → „Write audit logs to the database“ ausschalten. Sonst speichert
   Supabase jede Anmeldung dauerhaft.

## 4. GitHub: Werte hinterlegen

Im Repository unter **Settings → Secrets and variables → Actions**.

Reiter **Secrets** → **New repository secret** (zweimal):

| Name | Wert |
|---|---|
| `SUPABASE_DB_PASSWORD` | das Datenbank-Passwort aus Schritt 1 |
| `SUPABASE_ACCESS_TOKEN` | Supabase-Dashboard → Konto-Menü → **Account** → **Access Tokens** → **Generate new token** |

Reiter **Variables** → **New repository variable** (dreimal):

| Name | Wert |
|---|---|
| `SUPABASE_PROJECT_REF` | die Projekt-Referenz |
| `SUPABASE_URL` | die Projekt-URL, `https://<projekt-referenz>.supabase.co` |
| `SUPABASE_PUBLISHABLE_KEY` | der Publishable Key |

Die drei Variablen sind nicht geheim. Sie stehen später ohnehin in jeder ausgelieferten App.

## 5. Claude Bescheid geben

Schreib Claude: **„Supabase ist eingerichtet.“** Claude spielt dann die Datenbank ein (Workflow „Supabase Deploy“),
baut die Apps mit der Adresse des Servers neu und prüft das Ergebnis.

Selbst geht es auch, sobald der Stand in `main` liegt: **Actions → „Supabase Deploy“ → Run workflow**. Fehlt ein
Wert, bricht der Lauf mit einer deutschen Meldung ab, die sagt, welcher. Danach **Actions → „Android“** und
**„Web-App“** jeweils mit **Run workflow** neu starten, damit die Apps die Adresse des Servers bekommen.

Die Web-App fürs iPhone wird nur aus `main` veröffentlicht. Sie zeigt die gemeinsame Runde also erst, wenn der
Pull Request zusammengeführt ist.

## 6. Ausprobieren

Am besten zu zweit, zum Beispiel ein Android-Handy und ein iPhone. Allein geht es auch mit zwei Browsern.

**Wer legt die Runde an?** Das Handy, auf dem die bisherigen Abende liegen. Alle Abende und Spieler dieses Geräts
kommen mit in die Runde. Bei allen anderen werden die Daten des Geräts durch die der Runde ersetzt (die App fragt
vorher und bietet eine Sicherung an).

1. **A:** App aktualisieren, „Gemeinsame Runde“ öffnen, **Mit Google anmelden**, **Runde anlegen**.
2. **A:** Bei „Wer bist du?“ den eigenen Spieler wählen, den eigenen Namen eintragen, **Das bin ich**. Der Name lässt
   sich danach nicht mehr ändern.
3. **A:** **Einladen** und den Link verschicken (er gilt 14 Tage).
4. **B:** Den Link antippen, anmelden, **Beitreten**, bei „Wer bist du?“ den eigenen Spieler wählen.
5. Beide sehen jetzt dieselben vier Namen.
6. **A** startet einen Abend und trägt ein Spiel ein. Es erscheint nach ein paar Sekunden bei **B**.
7. **B** trägt ein Spiel ein. Es erscheint bei **A**.
8. **Funkloch:** **A** schaltet den Flugmodus ein und trägt ein Spiel ein. Oben steht „Kein Netz – 1 Änderung
   wartet“. Flugmodus aus: „Abgeglichen“, und das Spiel ist bei **B**.
9. **Doppelt:** Beide tragen dasselbe Spiel ein. Beim zweiten steht „Doppelt eingetragen?“. Eines davon löschen.

Über dem Eintragen steht, wer gerade gibt („… gibt und trägt ein.“). Das ist nur ein Hinweis: Eintragen kann jeder.

## 7. Vor jedem Spielabend

Supabase **pausiert kostenlose Projekte nach etwa einer Woche ohne Nutzung** und schickt vorher eine E-Mail. Die
Daten bleiben erhalten. Spielt ihr seltener als einmal pro Woche, ist das Projekt am Spielabend wahrscheinlich
pausiert.

- **Am Tag vorher** im Supabase-Dashboard nachsehen. Steht dort „paused“: **Restore project** und ein paar Minuten
  warten.
- Vergessen? Kein Problem: Die Apps funktionieren ohne Server weiter. In der App steht dann „Server nicht
  erreichbar“. Sobald das Projekt wieder läuft, gleichen sich alle Handys von selbst ab. Bis dahin sollte nur
  **einer** eintragen, sonst stehen Spiele hinterher doppelt da.

## Wenn etwas nicht klappt

| Was du siehst | Ursache | Abhilfe |
|---|---|---|
| „Der Server ist noch nicht eingerichtet.“ | Die App wurde ohne Adresse des Servers gebaut | Variablen aus Schritt 4 prüfen, Apps neu bauen lassen (Schritt 5), App aktualisieren |
| Google meldet „redirect_uri_mismatch“ | Die Weiterleitungs-URI im Google-Client stimmt nicht | Schritt 2, Punkt 5: Adresse genau vergleichen (Referenz, kein Leerzeichen, kein Schrägstrich am Ende) |
| Nach der Anmeldung landet man nicht in der App, oder dort steht „Die Anmeldung hat nicht geklappt.“ | Die Redirect URLs bei Supabase fehlen oder haben einen Tippfehler | Schritt 3, Punkt 2 |
| Anmelden klappt, läuft aber nach einer Woche ab | Der Zustimmungsbildschirm steht noch auf „Testen“ | Schritt 2, Punkt 4 |
| „Server nicht erreichbar“, obwohl Internet da ist | Das Supabase-Projekt ist pausiert | Schritt 7 |
| „Die Einladung ist abgelaufen oder ungültig.“ | Der Link ist älter als 14 Tage oder unvollständig kopiert | Neu einladen |
| „Supabase Deploy“ bricht ab | Ein Secret oder eine Variable fehlt | Die Meldung im Lauf nennt den fehlenden Wert |

## Was auf dem Server liegt

- Die Namen der Spieler, die Abende mit ihren Spielen und das Regelwerk der Runde. Lesen können das nur die
  Mitglieder der Runde und du als Inhaber des Supabase-Projekts.
- Supabase speichert zu jedem Konto die Google-Adresse, mit der sich jemand angemeldet hat. Die Mitspieler sehen sie
  nicht.
- **Runde verlassen** (in der App): Das Handy wird getrennt, die Daten bleiben als Kopie auf dem Handy, der Name
  bleibt in der Runde.
- **Ein Konto ganz löschen:** Supabase-Dashboard → **Authentication → Users**. Der Spieler bleibt als Gast mit seinem
  Namen in der Runde.
- **Alles löschen:** das Supabase-Projekt löschen (**Project Settings → General → Delete project**). Die Daten auf
  den Handys bleiben.

Grenzen: höchstens 12 Mitglieder und 40 Spieler je Runde, 5 Runden je Konto. Für eine Vierer-Runde ist das weit
mehr als nötig.

Technische Einzelheiten stehen in [`supabase/README.md`](../supabase/README.md) und in
[`ARCHITEKTUR.md`](ARCHITEKTUR.md), Abschnitt 8.
