# Schafkopf Tracker

Die App für den Schafkopf-Abend im Wirtshaus: Nach jedem Spiel trägt einer ein, was gespielt wurde, und die App
rechnet aus, wer wie viel bekommt oder zahlt. Am Ende des Abends stehen Gewinner, Verlierer und der Verlauf fest.

„Schafkopf Tracker“ ist ein Arbeitstitel. Die App ist in Entwicklung; eine Testversion gibt es noch nicht.

## Was die App kann (Stand der Entwicklung)

| Bereich | Stand |
|---|---|
| Spiel eintragen: Rufspiel, Über Kreuz, Farbsolo, Wenz, Geier; gelegt, Laufende, Schneider, Schwarz, Bock | in Arbeit |
| Abrechnung in ganzen Cent, jedes Spiel ein Nullsummenspiel, jede Eingabe nachträglich änderbar | fertig, getestet |
| Abend-Liste mit laufendem Stand, Plätzen, Geber und Bock-Zähler | fertig |
| Dunkles Design als Standard, große Tippflächen, Bedienung mit Screenreader | fertig |
| Statistik, Verlauf als Diagramm, Teilen als Text und Bild | geplant |
| Sicherung (Datei, Google Drive), Stammtisch-Zusammenfassung, Wear-OS-App | geplant |

Alles liegt nur auf dem Gerät. Kein Konto, kein Server, keine Werbung, keine Auswertung des Nutzungsverhaltens.

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
data/       Reines Kotlin: Sicherung als JSON, CSV, Netzanbindungen + Tests
database/   Room-Datenbank (SQLite): Tabellen, Abfragen, Repositories + Tests
app/        Android-App (Jetpack Compose): Oberfläche, ViewModels, Verdrahtung mit Koin
docs/       Anforderungen und Architektur
.github/    Issue-Vorlagen, Labels, Workflows
```

| Workflow | Wann | Was |
|---|---|---|
| Labels (`labels.yml`) | Änderung an `.github/labels.json` oder am Workflow; auch manuell | Legt die Labels an oder aktualisiert sie |

## Selbst bauen (für Entwickler)

Benötigt werden JDK 21 und das Android-SDK (Plattform 37).

```bash
./gradlew :domain:test :data:test
```

```bash
./gradlew :app:assembleFdroidDebug
```

Die Ausgabe `fdroid` enthält ausschließlich freie Software. Die Ausgabe `full` bindet zusätzlich die Wear-OS-Uhr
über die Google Play Services an.

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [docs/ANFORDERUNGEN.md](docs/ANFORDERUNGEN.md) | Was die App können soll und was entschieden ist |
| [docs/ARCHITEKTUR.md](docs/ARCHITEKTUR.md) | Verbindlicher Vertrag: Module, Namen, Abrechnung |

## Lizenz

[CC0 1.0 Universal](LICENSE) (gemeinfrei). Das gilt für den Code und für alle Grafiken; das App-Symbol und die
Streichholzschachtel sind selbst gezeichnet. Die verwendeten Bibliotheken stehen unter ihren eigenen freien Lizenzen.
