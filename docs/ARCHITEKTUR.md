# Architektur (verbindlicher Vertrag)

Stand: 15.11.2026. Namen, Pakete, Funktionen und Pfade in diesem Dokument sind exakt so zu verwenden.
Wer davon abweichen muss, ändert zuerst dieses Dokument. Was die App können soll, steht in
[ANFORDERUNGEN.md](ANFORDERUNGEN.md).

## 0. Leitplanken (nicht verhandelbar)

| Regel | Folge |
|---|---|
| F-Droid-tauglich | In der Ausgabe `fdroid` nur freie Bibliotheken von Maven Central und Google Maven. Verboten: Firebase, Google Play Services (`com.google.android.gms`), ML Kit, AICore, Play-Bibliotheken, Analyse-, Absturz- und Werbe-SDKs, das Plugin `foojay-resolver`, eingecheckte JAR/AAR-Dateien (außer `gradle-wrapper.jar`). |
| Kostenlos | Kein eigener Server. Alles außer Cloud-Sicherung und Abend-Kommentator arbeitet ohne Internet. |
| Tatsachen speichern, Rest berechnen | Gespeichert werden die Einträge eines Abends. Beträge, Salden, Plätze, Geber und Bock-Zähler berechnet `EveningCalculator` jedes Mal neu. Statistiken werden nie gespeichert. |
| Ganze Cent | Beträge sind `Int` in Cent. Kein Gleitkomma in der Abrechnung. Jedes Spiel ist ein Nullsummenspiel. In Euro formatiert wird nur in der Oberfläche (`:app`). |
| Nichts geht verloren | Jede Eingabe wird sofort in einer Datenbank-Transaktion gespeichert. Fehler beim Lesen führen nie zum Absturz. |
| Regelwerk je Abend | Jeder Abend trägt eine eigene Kopie des Regelwerks. Änderungen an der Vorlage verändern alte Abende nicht. |
| Minimale Rechte | Einzige Berechtigung: `INTERNET` (Cloud-Sicherung und Abend-Kommentator). |
| Barrierefrei | Dunkles Design als Standard. Tippziele mindestens 48 dp, Hauptaktionen 56 dp und mehr, Schrift bis 200 %, hoher Kontrast, keine Information nur über Farbe, Diagramme zusätzlich als Text, `contentDescription` bzw. Semantik überall. |
| Sprache | Oberfläche Deutsch, alle Texte in `strings*.xml`. Kommentare Deutsch. Bezeichner Englisch mit deutschen Fachbegriffen (Rufspiel, Laufende, Bock; Abend heißt `Evening`). |
| Keine Namen | Namen von Personen stehen nie im Quelltext, in Tests, Issues oder Commit-Nachrichten. Tests verwenden Beispielnamen. |
| Eigene Grafiken | Symbole sind selbst gezeichnete Vektorgrafiken (CC0). |

## 1. Module

| Modul | Art | Paket | Inhalt |
|---|---|---|---|
| `:domain` | Kotlin/JVM | `io.github.q0ochen.schafkopf.domain` | Modelle, Regelwerk, `ScoreCalculator`, `PayoutCalculator`, Abend-Logik, `StatisticEngine`, Zusammenfassung, Ports |
| `:data` | Kotlin/JVM | `io.github.q0ochen.schafkopf.data` | Sicherung als JSON, CSV, Netzanbindungen (Sprachmodell, Google Drive) |
| `:database` | Android-Bibliothek | `io.github.q0ochen.schafkopf.database` | Room: Tabellen, DAOs mit `Flow`, Repositories |
| `:app` | Android-App | `io.github.q0ochen.schafkopf.app` | Compose-Oberfläche, ViewModels (MVVM), Navigation, Koin-Module, Android-Adapter |
| `:wear` | Wear-OS-App (geplant) | `io.github.q0ochen.schafkopf.wear` | Kachel und Schnelleingabe auf der Uhr |

Abhängigkeiten: `:app` → `:database`, `:data`, `:domain`; `:database` → `:domain`; `:data` → `:domain`;
`:domain` hängt von keinem Projektmodul ab und kennt kein Android.

- **Dependency Injection:** Koin. Verdrahtet wird ausschließlich in `app/di/` (Kompositionswurzel). Alle Klassen
  bekommen ihre Abhängigkeiten über den Konstruktor; `:domain`, `:data` und `:database` kennen Koin nicht.
- **Uhrzeit und Kennungen** werden injiziert (`java.time.Clock`, `IdGenerator`); in der Fachlogik steht nirgends
  `Instant.now()` oder `UUID.randomUUID()`.
- **Ausgaben (Flavors)** der App: `fdroid` (nur freie Software) und `full` (zusätzlich Abgleich mit der Uhr über
  Google Play Services).
- `:domain`, `:data` und `:database` bauen mit „Warnungen sind Fehler“.

## 2. Fachlogik (`:domain`)

Paket `domain.model`: `PlayerId`, `EveningId`, `EntryId` (Wertklassen um `String`), `IdGenerator`, `Player`,
`AppSettings`, `AiSettings`, `ThemeMode`, `AiProvider`.

Paket `domain.rules`: `GameType` (RUFSPIEL, UEBER_KREUZ, FARBSOLO, WENZ, GEIER; `isSolo`), `Suit`, `ResultLevel`
(NORMAL, SCHNEIDER, SCHWARZ), `Announcement` (NONE, TOUT, SIE), `KontraLevel` (NONE, KONTRA, RETOUR),
`BockRules`, `BockStacking`, `PayoutRule`, `RuleSet` (Vorlagen `RuleSet.standard()` und `RuleSet.sauspiel()`),
`GameFacts`, `ValueBreakdown`, `RuleViolation`, `ScoreCalculator`, `PayoutCalculator` mit `SchafkopfPayout` und
`PotSplitPayout`.

Paket `domain.evening`: `EveningEntry` (versiegelt) mit `GameEntry`, `ThrownInEntry`, `BockTriggerEntry`,
`ManualEntry`; `Evening`; `EveningCalculator.compute(evening): EveningState`; `EveningEditor`; `SettleUp`.

Paket `domain.stats`: `StatisticEngine` als Einstieg für alle Auswertungen (ein Abend oder alle Abende).

Paket `domain.summary`: Fakten eines Abends, Zusammenfassung aus Textbausteinen, Anfrage an ein Sprachmodell.

Paket `domain.ports`: `PlayerRepository`, `EveningRepository`, `SettingsRepository`, `SecretStore`, `TextGenerator`.

### Abrechnung

```
Spielwert T = (Grundwert + Laufende × Einheit + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt) × Kontra × Ansage
```

| Größe | Standard (`RuleSet.standard()`) |
|---|---|
| Grundwert | Rufspiel 10, Über Kreuz 10, Farbsolo 50, Wenz 50, Geier 50 Cent |
| Einheit | 10 Cent je Laufendem, 10 für Schneider, weitere 10 für Schwarz |
| Laufende | 0 bis 8 (Wenz und Geier bis 4); gezählt ab 3 bzw. ab 2 bei Wenz und Geier |
| Bock | Faktor 2 |
| Gelegt | jeder der 0 bis 4 Spieler, die gelegt haben, verdoppelt |
| Kontra, Ansage | Kontra ×2, Retour ×4, Tout ×2 (ohne Schneider und Schwarz); abschaltbar |

Verteilung (`PayoutCalculator`):

- `SchafkopfPayout` (Standard): zwei gegen zwei je ±T; einer gegen drei: Solist ±3 T, jeder Gegner ∓T.
- `PotSplitPayout`: einer gegen drei: Solist ±T, die drei Gegner teilen sich T (Rest-Cent an die vorderen Plätze).

Referenzfälle für Tests: Rufspiel ohne Zusätze = je ±10. Farbsolo mit 3 Laufenden, schwarz, zweimal gelegt
= (50 + 30 + 20) × 4 = 400 je Gegner, 1200 für den Solisten.

### Abend

- Geber: beginnt bei `firstDealer`, wechselt nach jedem Spiel im Uhrzeigersinn (`seating`).
- Bock: Jedes Spiel hat einen Bock-Schalter. Der Bock-Zähler belegt ihn vor: „6 Nixer“ (`BockTriggerEntry`) legt
  `gamesPerTrigger` Bockspiele in den Zähler, jedes gespielte Spiel verbraucht eines. Von Hand lässt sich der
  Schalter je Spiel umstellen (`BockOverride`).
- Gewinner eines Spiels (`GameEntry.winners`) ergeben sich aus Ansager, Mitspieler und Ausgang.

## 3. Datenhaltung

### Room (`:database`)

Tabellen `players`, `evenings`, `entries` (Fremdschlüssel auf den Abend, Löschen mit `CASCADE`). DAOs liefern
`Flow`s, sodass sich jede Änderung von selbst bei allen Bildschirmen meldet. Ein Abend wird samt Einträgen in einer
Transaktion gespeichert. Aufzählungen stehen mit ihrem Namen in der Datenbank: Die Namen der Enum-Werte sind Teil des
Schemas. Das Schema jeder Version liegt unter `database/schemas/`; jede Änderung braucht eine Migration mit Test.

### Sicherung (`:data`)

Eine UTF-8-JSON-Datei mit Kopf (`format`, `schemaVersion`, `appVersion`, `exportedAt`), festen Schlüsseln und
Beträgen in Cent. Der Import führt zusammen und löscht nichts. Dieselbe Datei wird auf Google Drive gesichert
(Hintergrundarbeit mit `WorkManager`, ohne Empfang nachgeholt). Schlüssel und Anmeldedaten stehen nie in der Sicherung.

## 4. App (`:app`)

- MVVM: je Bildschirm ein ViewModel mit `StateFlow` als Zustand; die Oberfläche liest nur diesen Zustand.
- Navigation: eigener Stapel aus `Screen`-Werten (`Navigator`), je Eintrag ein eigener ViewModel-Speicher.
- Bildschirme: Start, Neuer Abend, Abend, Spiel eintragen, Verlauf, Abschluss, Statistik, Spieler, Einstellungen,
  Regelwerk, Sicherung, Abend-Kommentator.
- Texte der Oberfläche stehen in `res/values/strings*.xml`; Beträge formatiert ausschließlich `:app`.

## 5. Tests

- `:domain`: JUnit Jupiter, MockK, Turbine; Ziel über 90 % Abdeckung (gemessen mit Kover).
  Pflicht: Preisberechnung aller Spielarten, Potenzen aus Bock und Gelegt, Verteilung der Spielschuld, Nullsumme.
- `:data`: Sicherung hin und zurück, Import-Prüfungen, CSV, Netzanbindungen gegen einen Testserver.
- `:database`: Room mit SQLite im Arbeitsspeicher (Robolectric), DAOs, Repositories, Schema-Wächter.
- `:app`: Bildschirmfotos der wichtigsten Bildschirme (Roborazzi) als Sichtprüfung; eigene UI-Tests sind nicht verlangt.

## 6. Bauen und prüfen

```bash
./gradlew :domain:test :data:test :database:testDebugUnitTest
```

```bash
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug
```
