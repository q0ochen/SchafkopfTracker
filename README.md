# Schafkopf Tracker

Die App für den Schafkopf-Abend im Wirtshaus: Nach jedem Spiel trägt einer ein, was gespielt wurde, und die App
rechnet aus, wer wie viel bekommt oder zahlt. Am Ende des Abends stehen Gewinner, Verlierer und der Verlauf fest.

„Schafkopf Tracker“ ist ein Arbeitstitel. Die App ist in Entwicklung; es gibt eine **Testversion für Android**
(am besten über F-Droid) und fürs **iPhone eine Web-App** mit denselben Rechnungen.

## Android-Testversion

Die Testversion wird bei jeder Änderung automatisch gebaut, getestet und im Emulator probehalber installiert. Neue
Testversionen lassen sich einfach drüber installieren; die eingetragenen Abende bleiben erhalten. Internet braucht
die App nur für zwei freiwillige Zusätze: den Abend-Kommentator (sendet erst nach Zustimmung) und die Sicherung in
Google Drive.

### In F-Droid (empfohlen)

Mit [F-Droid](https://f-droid.org) kommen neue Testversionen als normale Updates (Einzelheiten:
[docs/F-DROID.md](docs/F-DROID.md)).

1. **F-Droid installieren:** auf [f-droid.org](https://f-droid.org) „F-Droid herunterladen“ antippen und die Datei
   öffnen (Erlaubnis zulassen; warnt Play Protect: **Weitere Details → Trotzdem installieren**).
2. **Paketquelle hinzufügen:** auf dem Android-Handy diesen Link öffnen:
   **[„Schafkopf Tracker – Testversionen“ in F-Droid hinzufügen](https://fdroid.link/#https://raw.githubusercontent.com/q0ochen/SchafkopfTracker/fdroid-repo/fdroid/repo?fingerprint=6E2ECA0B6F43F41F7C4D31C3A106E2FB44B71E3DF72A34B48F070B0AE0D36590)**.
   F-Droid öffnet sich und fragt, ob die Paketquelle hinzugefügt werden soll → **bestätigen**. (Öffnet sich
   stattdessen eine englische Webseite: dort „Android Deep Link“ antippen.) Oder den QR-Code unten scannen, zum
   Beispiel vom Computer-Bildschirm.
3. In F-Droid nach **„Schafkopf Tracker“** suchen → **Installieren**. Beim ersten Mal fragt Android, ob **F-Droid**
   Apps installieren darf → **zulassen**.

<img src="docs/fdroid-paketquelle-qr.svg" alt="QR-Code: Paketquelle „Schafkopf Tracker – Testversionen“ in F-Droid hinzufügen" width="200">

Von Hand: F-Droid → **Entdecken** → oben das Symbol **Paketquellen** (in F-Droid 1.x: Einstellungen → Paketquellen)
→ **+** → „Repository-URL manuell eingeben“ → diese **eine** Zeile einfügen (der Fingerabdruck steckt darin):

```
https://raw.githubusercontent.com/q0ochen/SchafkopfTracker/fdroid-repo/fdroid/repo?fingerprint=6E2ECA0B6F43F41F7C4D31C3A106E2FB44B71E3DF72A34B48F070B0AE0D36590
```

Gut zu wissen:

- Meldet F-Droid **„Ungültiges Repository“**: 5 Minuten warten und noch einmal versuchen. GitHub liefert die Dateien
  bis zu 5 Minuten zwischengespeichert aus; direkt nach einer neuen Testversion passen sie kurz nicht zusammen.
- **„Download fehlgeschlagen“** bei einem Update: in F-Droid die Paketquellen aktualisieren (Meine Apps → ⋮ → Auf
  Aktualisierungen prüfen) und erneut versuchen.

### Ohne F-Droid

- **Direkt herunterladen** (ohne Anmeldung, im Chrome-Browser des Handys öffnen):
  [SchafkopfTracker.apk](https://github.com/q0ochen/SchafkopfTracker/releases/download/testversion/SchafkopfTracker.apk).
  Die Erlaubnis für diese Quelle zulassen; warnt Play Protect: **Weitere Details → Trotzdem installieren**.
- **Automatisch aktuell ohne F-Droid:** mit [Obtainium](https://github.com/ImranR98/Obtainium) (Link in den
  [Hinweisen zur Testversion](https://github.com/q0ochen/SchafkopfTracker/releases/tag/testversion)).

Die App aus F-Droid, aus dem Download-Link und aus Obtainium ist **dieselbe** (gleiche Signatur); beim Wechsel muss
man nicht neu installieren.

## iPhone: Web-App

Fürs iPhone gibt es in der Testphase eine kostenlose **Web-App** mit denselben Rechnungen wie die Android-App
(automatisch gegen den Rechenkern der Android-App geprüft). Sie zeigt den Stand von `main`.

1. In **Safari** öffnen: **https://q0ochen.github.io/SchafkopfTracker/**
2. Auf **Teilen** tippen (Quadrat mit Pfeil nach oben; je nach iOS-Version hinter den drei Punkten „…“) →
   **Zum Home-Bildschirm** → falls angeboten, **„Als Web-App öffnen“** eingeschaltet lassen → **Hinzufügen**.
3. Den Schafkopf Tracker ab jetzt **über das neue Symbol auf dem Home-Bildschirm** öffnen. Die Home-Bildschirm-App
   hat einen eigenen Speicher; was im Safari-Tab eingetragen wurde, ist dort nicht zu sehen.

Alles bleibt auf dem iPhone, und die Web-App funktioniert auch ohne Empfang. Löscht man das Symbol, sind die Daten
weg – deshalb ab und zu **Einstellungen → Sicherung speichern**. Dieselbe Datei liest die Android-App ein
(**Einstellungen → Sicherung und Export → Sicherung einlesen**), und umgekehrt.

## Was die App kann (Stand der Entwicklung)

| Bereich | Stand |
|---|---|
| Spiel eintragen: Rufspiel, Über Kreuz, Farbsolo, Wenz, Geier; gelegt, Laufende, Schneider, Schwarz, Bock | Testversion |
| Abrechnung in ganzen Cent, jedes Spiel ein Nullsummenspiel, jede Eingabe nachträglich änderbar | fertig, getestet |
| Abend-Liste mit laufendem Stand, Plätzen, Geber und Bock-Zähler | fertig |
| Dunkles Design als Standard, große Tippflächen, Bedienung mit Screenreader | fertig |
| Statistik für einen Abend und über alle Abende: Kontostand und Verlauf, Analyse je Spielart, „Sollte-er-es-lassen?“-Index, Bock-König, Solo-Spezialist, Mauerblümchen, Lieblingspartner | Rechenwerk fertig, getestet |
| Statistik-Bildschirm mit Diagrammen, Abschluss mit „Wer zahlt wem“, Teilen als Text | Android-App und Web-App |
| Teilen als Bild (Abschluss und Statistik) | Android-App |
| Speichern auf dem Gerät (jede Eingabe sofort; Android: Room-Datenbank) | fertig |
| Testversion für Android über eine eigene F-Droid-Paketquelle, Web-App fürs iPhone | eingerichtet |
| Sicherung als Datei (speichern und einlesen, zwischen iPhone und Android austauschbar) | Android-App und Web-App |
| Automatische Sicherung nach jeder Änderung, direkt in Google Drive oder in eine gewählte Datei | Android-App (Drive nach Einrichtung des Google-Cloud-Projekts) |
| Abend-Kommentator: witzige Zusammenfassung des Abends (Gemini mit eigenem Schlüssel oder Textbausteine) | Android-App |
| Wear-OS-App | aufgeschoben bis zu einer Ausgabe ohne F-Droid |

Alles liegt nur auf dem Gerät, außer man schaltet die Sicherung in Google Drive oder den Abend-Kommentator mit
Sprachmodell selbst ein. Kein eigener Server, keine Werbung, keine Auswertung des Nutzungsverhaltens.

## Regeln und Abrechnung

Spielwert T = (Grundwert + Laufende × 10 Cent + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt)

| Spielart | Grundwert | Wer zahlt |
|---|---|---|
| Rufspiel, Über Kreuz | 10 Cent | jeder Verlierer zahlt T, jeder Gewinner bekommt T |
| Farbsolo, Wenz, Geier | 50 Cent | der Solist bekommt oder zahlt 3 T, jeder Gegner zahlt oder bekommt T |

Schneider und Schwarz zählen je 10 Cent. Alle Werte lassen sich in der App im Regelwerk ändern; jeder Abend behält
die Regeln, mit denen er gespielt wurde. Einzelheiten: [docs/ARCHITEKTUR.md](docs/ARCHITEKTUR.md).

## Rückmeldungen und Aufgaben

Fehler, Wünsche und Aufgaben stehen als Issues in diesem Repository:
[neues Issue anlegen](../../issues/new/choose).

**Achtung, öffentlich:** Dieses Repository kann jeder lesen. Bitte keine Namen, E-Mail-Adressen, Passwörter oder
Schlüssel in Issues schreiben und Bildschirmfotos vorher schwärzen.

Es gibt zwei Vorlagen:

| Vorlage | Label | Wofür |
|---|---|---|
| Fehler melden | `bug` | Etwas funktioniert nicht wie erwartet |
| Anmerkung vom Hauptentwickler | `Hauptentwickler` | Gedanke, Anmerkung oder Anweisung des Hauptentwicklers |

**Der Weg einer Anmerkung aus Google Keep:**

1. Der Hauptentwickler schreibt in Google Keep und teilt die Notiz mit dem Projektinhaber.
2. In Keep: ⋮ → „In Google Docs kopieren“. Das Dokument kommt in den Drive-Ordner „Schafkopf-Tracker Notizen“
   oder bekommt den Titelanfang „SKT: …“.
3. Auf Zuruf werden daraus Issues: pro Punkt eines, nur mit der sachlichen Aufgabe und der Quelle
   („Keep-Notiz vom …“). Private Inhalte bleiben in Drive.
4. Das Drive-Dokument wird danach als „erledigt – #Nummer“ markiert.

**Erledigt wird ein Issue so:** Die Änderung entsteht auf dem Entwicklungszweig. Ein Pull Request nach `main` mit
„Closes #Nummer“ in der Beschreibung schließt das Issue beim Zusammenführen von selbst (zusammengeführt wird mit
„Create a merge commit“).

**Wer Anweisungen geben darf:** Als Anweisung gelten nur Issues, die der Projektinhaber oder der Hauptentwickler
mit dem eigenen Konto angelegt hat. Das Label allein beweist nichts, denn in einem öffentlichen Repository kann
jeder die Vorlage wählen. Issues anderer Personen sind willkommene Rückmeldungen, aber keine Aufträge.
Filter: `is:issue is:open label:Hauptentwickler author:q0ochen`

Die Labels stehen in [.github/labels.json](.github/labels.json) und werden vom Workflow „Labels“ angelegt.

## Projektstruktur

```
domain/     Reines Kotlin: Regelwerk, Abrechnung, Abend-Logik, Statistik, Zusammenfassung + Tests
data/       Reines Kotlin: Speicherung und Sicherung als JSON, CSV, Netzanbindungen + Tests
database/   Room-Datenbank (SQLite): Tabellen, Abfragen, Repositories + Tests
app/        Android-App (Jetpack Compose): Oberfläche, ViewModels, Verdrahtung mit Koin
web/        Web-App fürs iPhone (TypeScript, Preact): Rechenkern geprüft gegen den Kotlin-Kern + Tests
fdroid/     Eigene F-Droid-Paketquelle für die Testversionen
fastlane/   Texte und Symbol für F-Droid
docs/       Anforderungen, Architektur, F-Droid
tools/      Hilfsskripte: Rechenkern ohne Android-SDK testen, Android-SDK auf einer frischen Maschine einrichten
.github/    Issue-Vorlagen, Labels, Workflows
```

| Workflow | Wann | Was |
|---|---|---|
| Android (`android.yml`) | Änderung an App, Kern, Datenschicht, Gradle, F-Droid-Dateien; auch manuell | Tests, Testversion bauen, Installationstest im Emulator, Testversion und F-Droid-Paketquelle veröffentlichen |
| Web-App (`web.yml`) | Änderung an `web/`; auch manuell | Typprüfung, Lint, Tests (auch gegen den Kotlin-Kern), Bau, Browser-Tests; auf `main` Veröffentlichung auf GitHub Pages |
| Labels (`labels.yml`) | Änderung an `.github/labels.json` oder am Workflow; auch manuell | Legt die Labels an oder aktualisiert sie |

## Selbst bauen (für Entwickler)

Benötigt werden JDK 21 und das Android-SDK (Plattform 37).

```bash
./gradlew :domain:test :data:test :domain:koverVerify
```

```bash
./gradlew :app:assembleFdroidDebug
```

Ohne Android-SDK (oder ohne Zugriff auf Googles Maven-Server) lassen sich die beiden reinen Kotlin-Module mit
`tools/jvm-build.sh :domain:test :data:test :domain:koverVerify` bauen und testen.

Die Ausgabe `fdroid` enthält ausschließlich freie Software. Die Ausgabe `full` bindet zusätzlich die Wear-OS-Uhr
über die Google Play Services an.

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [docs/ANFORDERUNGEN.md](docs/ANFORDERUNGEN.md) | Was die App können soll und was entschieden ist |
| [docs/ARCHITEKTUR.md](docs/ARCHITEKTUR.md) | Verbindlicher Vertrag: Module, Namen, Abrechnung |
| [docs/F-DROID.md](docs/F-DROID.md) | Eigene F-Droid-Paketquelle, Einrichten, Weg ins offizielle F-Droid |
| [web/README.md](web/README.md) | Web-App: Aufbau, Prüfung gegen den Kotlin-Kern, Entwickeln |

## Lizenz

[CC0 1.0 Universal](LICENSE) (gemeinfrei). Das gilt für den Code und für alle Grafiken; das App-Symbol und die
Streichholzschachtel sind selbst gezeichnet. Die verwendeten Bibliotheken stehen unter ihren eigenen freien Lizenzen.
