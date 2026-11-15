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
`AppSettings`, `AiSettings`, `ThemeMode`, `AiProvider`, `AmountFormatter` (macht aus Cent einen Text; die Umsetzung
`Money` liegt in `:app`, weil in Euro nur in der Oberfläche formatiert wird).

Paket `domain.rules`: `GameType` (RUFSPIEL, UEBER_KREUZ, FARBSOLO, WENZ, GEIER; `isSolo`, `hasDeclarer`), `Suit`, `ResultLevel`
(NORMAL, SCHNEIDER, SCHWARZ), `Announcement` (NONE, TOUT, SIE), `KontraLevel` (NONE, KONTRA, RETOUR),
`BockRules`, `BockStacking`, `PayoutRule`, `RuleSet` (Vorlagen `RuleSet.standard()` und `RuleSet.sauspiel()`),
`GameFacts`, `ValueBreakdown`, `RuleViolation`, `ScoreCalculator`, `PayoutCalculator` mit `SchafkopfPayout` und
`PotSplitPayout`.

Paket `domain.evening`: `EveningEntry` (versiegelt) mit `GameEntry`, `ThrownInEntry`, `BockTriggerEntry`,
`ManualEntry`; `Evening`; `EveningCalculator.compute(evening): EveningState`; `EveningEditor`; `SettleUp`.

Paket `domain.stats`: `StatisticEngine` als Einstieg für alle Auswertungen (ein Abend oder alle Abende, siehe
Abschnitt „Statistik“); darunter `EveningStatistics.compute(state)`, `HistoryStatistics.compute(evenings)`,
`Progression` (Verlauf von Saldo und Platz eines Abends) und `PlayerStats`.

Paket `domain.summary`: Fakten eines Abends (`SummaryFactsBuilder.build(state, names, formatter[, zone])`),
Zusammenfassung aus Textbausteinen (`TemplateSummary`), Anfrage an ein Sprachmodell (`SummaryPrompt`, `SummaryService`).

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
| Kontra, Ansage | Kontra ×2, Retour ×4 (beide im Regelwerk abschaltbar); Tout ×2 und Sie (ohne Schneider und Schwarz; ein eigener Schalter fehlt noch) |

Verteilung (`PayoutCalculator`):

- `SchafkopfPayout` (Standard): zwei gegen zwei je ±T; einer gegen drei: Solist ±3 T, jeder Gegner ∓T.
- `PotSplitPayout`: einer gegen drei: Solist ±T, die drei Gegner teilen sich T (Rest-Cent an die vorderen Plätze).

Referenzfälle für Tests: Rufspiel ohne Zusätze = je ±10. Farbsolo mit 3 Laufenden, schwarz, zweimal gelegt
= (50 + 30 + 20) × 4 = 400 je Gegner, 1200 für den Solisten. Weitere von Hand gerechnete Fälle stehen in
`HandCalculatedCasesTest` und `HandCalculatedEveningTest`.

Beschädigte Daten (zum Beispiel aus einer verfälschten Datei) bringen die Abrechnung weder zum Absturz noch zu
falschen Summen:

- Der Spielwert wird ohne Überlauf gerechnet. Liegt er unter 0 oder über `ScoreCalculator.MAX_VALUE` (ein Drittel
  des größten `Int`, damit auch der dreifache Betrag des Solisten passt), meldet `ScoreCalculator.validate`
  `RuleViolation.VALUE_OUT_OF_RANGE`, und `ScoreCalculator.amounts` verteilt nichts.
- Jede Verteilung ist ein Nullsummenspiel. Wo sich nichts sinnvoll verteilen lässt (keine Gegenseite, ein Spieler
  doppelt genannt), bekommen alle 0. Geht eine Summe nicht auf (nur bei falscher Spielerzahl möglich), wird der Rest
  vorn beginnend centweise verteilt.

### Abend

- Geber: beginnt bei `firstDealer`, wechselt nach jedem Spiel im Uhrzeigersinn (`seating`).
- Bock: Jedes Spiel hat einen Bock-Schalter. Der Bock-Zähler belegt ihn vor: „6 Nixer“ (`BockTriggerEntry`) legt
  `gamesPerTrigger` Bockspiele in den Zähler, jedes gespielte Spiel verbraucht eines. Von Hand lässt sich der
  Schalter je Spiel umstellen (`BockOverride`).
- Gewinner eines Spiels (`GameEntry.winners(seating)`) ergeben sich aus Ansager, Mitspieler und Ausgang.
- Über Kreuz kennt keinen Ansager (`GameType.hasDeclarer` ist `false`): `declarer` und `partner` bezeichnen dort nur
  das eine der beiden Paare, `declarerWon` sagt, ob dieses Paar gewonnen hat.
- Ein Spiel, bei dem nicht feststeht, wer auf welcher Seite stand (Ansager unbekannt, Mitspieler fehlt oder ist der
  Ansager selbst), bekommt eine Nummer, bucht aber nichts und wird als Regelverstoß angezeigt. Dasselbe gilt, wenn
  jemand doppelt am Tisch sitzt oder der Spielwert außerhalb des Bereichs liegt (`VALUE_OUT_OF_RANGE`).
- `EveningCalculator.compute` wirft nie, auch nicht bei leerer oder falscher Sitzordnung; ohne Sitzordnung gilt
  `firstDealer` als Geber. Jeder Eintrag bucht entweder ein Nullsummenspiel oder nichts; was nichts bucht, ist
  markiert (`EntryResult.issues`). Nur eine als fehlerhaft markierte freie Buchung kann von der Nullsumme abweichen.
- `EntryIssue`: `RULE_VIOLATION`, `AMOUNTS_NOT_ZERO_SUM`, `UNKNOWN_PLAYER` (freie Buchung an jemanden oder gelegt von
  jemandem, der nicht am Tisch sitzt) und `BALANCE_OUT_OF_RANGE` (mit dem Eintrag verließe ein Saldo den
  Zahlenbereich; er wird dann nicht gebucht).
- `SettleUp.transfers` („wer zahlt wem“) wirft nie und rechnet ohne Überlauf; `SettleUp.isBalanced` sagt, ob die
  Salden aufgehen.

### Statistik (`StatisticEngine`, Issues #10 bis #13)

```kotlin
object StatisticEngine {
    fun forEvening(evening: Evening): Statistics            // ein einzelner Abend
    fun forEvenings(evenings: List<Evening>): Statistics    // „ewige Tabelle“; Reihenfolge der Liste egal
}
```

`Statistics` enthält (alle Beträge in Cent; Quoten als `Double?`, `null` ohne Fälle; alles deterministisch).
Ausgewertet wird in zeitlicher Reihenfolge, bei gleichem Beginn nach der Kennung. Derselbe Abend doppelt in der
Liste zählt nur einmal; jede Kennung darf nur einen Abend bezeichnen (von zwei verschiedenen Fassungen unter einer
Kennung zählt die zuerst übergebene).

- `eveningCount` (Abende mit mindestens einem abgerechneten Spiel), `gameCount` (nur abgerechnete Spiele) und
  `players` (alle, die an einem der Abende am Tisch saßen): bei `forEvening` in Sitzreihenfolge, bei `forEvenings`
  Saldo absteigend und bei Gleichstand nach Kennung, auch wenn die Liste nur einen Abend enthält. Alle Listen
  „je Spieler“ haben diese Reihenfolge (nur `history.playerStats` steht immer nach Saldo); wo unten „weiter vorn“
  steht, ist diese Reihenfolge gemeint.
- `finance: FinanceOverview` (Finanz-Übersicht): `standings` (`Standing` mit `player`, `balance`, `rank`; Gleichstand
  teilt den Platz); `balanceSeries: Map<PlayerId, List<Int>>` für das Liniendiagramm (Index 0 ist 0, danach der Stand
  nach jedem abgerechneten Spiel über alle Abende in zeitlicher Reihenfolge; Länge `gameCount + 1`; letzter Punkt =
  Saldo; wer an einem Abend nicht am Tisch saß, bleibt waagerecht); `eveningStarts` und `eveningIds` (wo im Verlauf ein
  Abend beginnt: der Index des Punktes vor seinem ersten Spiel; nur Abende mit abgerechnetem Spiel); `turnover`.
  Freie Buchungen zählen zum nächsten Spiel desselben Abends, hinter dem letzten Spiel zu dessen Punkt. Buchungen
  eines Abends ohne Spiel zählen zum nächsten Spiel eines späteren Abends, sonst zum letzten Punkt (ohne ein
  einziges Spiel ist das der einzige Punkt der Reihe); nur dadurch kann sich die Linie eines Spielers an einem
  Abend bewegen, an dem er nicht am Tisch saß.
- `gameModes: List<PlayerGameModes>` (Analyse je Spielart, je Spieler): `atTable` und `asPlayer` als
  `Map<GameType, ModeRecord>` (jede Spielart enthalten) mit `games`, `won`, `balance`, `winRate` und `averageAmount`
  (`Int?`, kaufmännisch gerundet, `null` ohne Spiel). `asPlayer` zählt nur selbst gespielte Spiele (Ansager,
  Mitspieler, Alleinspieler); bei Über Kreuz ist es gleich `atTable`.
- `gelegt: List<GelegtRecord>` („Sollte-er-es-lassen?“-Index, je Spieler): `count`, `won`, `winRate`, `netBalance`
  (Summe der eigenen Beträge aus Spielen, in denen er gelegt hat), `effect` (was das eigene Legen gekostet oder
  gebracht hat, exakt nachgerechnet), `badge` und `shouldLeaveIt`. Abzeichen (`GelegtBadge.of`, Quoten ganzzahlig
  verglichen): `count == 0` STREICHHOLZ_SPARER; `count < 3` NOCH_OFFEN; `winRate >= 0.6` und `netBalance >= 0`
  SICHERE_BANK; `winRate <= 0.4` und `netBalance < 0` BRANDSTIFTER (nur dann `shouldLeaveIt`); sonst bei
  `netBalance > 0` GLUECKSRITTER; sonst WACKELKANDIDAT.
- `bock: BockOverview` (Bock-König): `records` (`BockRecord` je Spieler mit `games`, `won`, `balance` aus
  Bockspielen); `king` = höchster Saldo, nur wenn über 0; bei Gleichstand der weiter vorn Stehende.
- `solo: SoloOverview` (Solo-Spezialist und Mauerblümchen): `records` (`SoloRecord` je Spieler mit `solos`, `calls` =
  Rufspiele als Ansager, `announced`, `soloShare`); `specialist` = meiste Einzelspiele (mindestens 1; bei Gleichstand
  der weiter vorn Stehende); `wallflower` = kleinster Solo-Anteil unter den Spielern mit mindestens 3 angesagten
  Spielen ohne den Spezialisten, nur wenn höchstens 0,2 (bei gleichem Anteil der mit mehr angesagten Spielen, dann
  der weiter vorn Stehende).
- `partners: PartnerOverview` (Lieblingspartner): `pairs` (`PartnerPair` mit `first`, `second`, `games`, `won`,
  `winRate`, `balance`): alle Zweiergespanne aus Rufspiel und Über Kreuz, die auf derselben Seite standen (auch als
  Gegenspielerpaar); sortiert nach Saldo, dann Quote, dann Kennungen; `favorite` = das erste Gespann mit mindestens
  3 gemeinsamen Spielen, nur wenn sein Saldo über 0 liegt („erfolgreichstes Zweiergespann“).
- `history: HistoryStats`: die übrigen Kennzahlen (Rollen, Serien, Schneider, teuerstes Spiel und so weiter).

Für die gesamte Statistik (`EveningStatistics`, `HistoryStatistics`, `StatisticEngine`) gilt:

- `klopferEffect` bzw. `effect` wird exakt nachgerechnet (`ScoreCalculator.amounts` mit einem Leger weniger), nicht
  halbiert: Mit der Topf-Teilung stimmt die Hälfte nicht auf den Cent.
- Über Kreuz kennt keinen Ansager und zählt deshalb nicht in `asDeclarer`, `asPartner`, `asOpponent`,
  `declarerByType` und `toutPlayed`.
- Ein Spiel, das sich nicht abrechnen ließ, zählt nirgends mit, auch nicht bei den Führungswechseln. (Der Verlauf
  des Abend-Bildschirms, `Progression`, folgt dagegen den Spielnummern und hat für ein solches Spiel einen Punkt.)
- Ein Abend ohne abgerechnetes Spiel zählt nicht als gespielter Abend (`eveningCount`, `evenings`, Abendsiege, bester
  und schlechtester Abend). Was dort eingetragen wurde, zählt aber mit, damit kein gebuchter Betrag verloren geht:
  freie Buchungen (Saldo und Umsatz), zusammengeworfene Blätter und ausgelöste Bockrunden. So lässt sich auch ein
  alter Kontostand als Abend mit einer einzigen freien Buchung übernehmen.

## 3. Datenhaltung

### Room (`:database`)

Tabellen `players`, `evenings`, `entries` (Fremdschlüssel auf den Abend, Löschen mit `CASCADE`). DAOs liefern
`Flow`s, sodass sich jede Änderung von selbst bei allen Bildschirmen meldet. Ein Abend wird samt Einträgen in einer
Transaktion gespeichert. Aufzählungen stehen mit ihrem Namen in der Datenbank: Die Namen der Enum-Werte sind Teil des
Schemas. Das Schema jeder Version liegt unter `database/schemas/`; jede Änderung braucht eine Migration mit Test.

### Sicherung (`:data`)

Eine UTF-8-JSON-Datei mit Kopf (`format`, `schemaVersion`, `appVersion`, `exportedAt`), festen Schlüsseln und
Beträgen in Cent. Der Import führt zusammen und löscht nichts; ein Abend, der in sich nicht stimmig ist (falsche
Sitzordnung, unbekannte Spieler, Buchungen, die nicht aufgehen, Beträge außerhalb des Bereichs, mit dem die App
rechnet), wird mit Begründung ausgelassen (`EveningCheck`, `EveningProblem`). Dieselbe Datei wird auf Google Drive gesichert
(Hintergrundarbeit mit `WorkManager`, ohne Empfang nachgeholt). Schlüssel und Anmeldedaten stehen nie in der Sicherung.

### Sprachmodell (`:data`, Paket `data.llm`)

`GeminiTextGenerator` und `OpenAiCompatibleTextGenerator` setzen den Port `TextGenerator` über REST um (OkHttp, ohne
Bibliothek eines Anbieters); die gemeinsame Netzanbindung ist `LlmTransport`. Zugesagt und mit Tests belegt:

- Der Schlüssel reist nur in einer Kopfzeile, nie in der Adresse. Er steht in keinem Fehler, keinem `toString` und
  keinem Protokoll; als Zusatz zu einem Fehler dient nur ein kurzes Kürzel wie „HTTP 429 RESOURCE_EXHAUSTED“.
- Gesendet wird nur an Adressen, die die `EndpointPolicy` erlaubt: `https` überall, `http` nur zum eigenen Gerät und
  ins eigene Netz (als Zahlenadresse). Weiterleitungen werden nie befolgt, und unverschlüsselte Aufrufe laufen nie
  über einen Proxy.
- Aus jedem Fehler wird ein `LlmResult.Failure`, nie eine Ausnahme; nur ein echter Abbruch der Coroutine wird
  durchgereicht. Bei Überlastung (HTTP 429, 500, 502, 503, 504) folgt genau ein zweiter Versuch.
- Antworten über 8 MiB werden verworfen. Jede Verbindung wird nach ihrem Aufruf geschlossen, auch über HTTP/2.
- Zeitlimits (`defaultHttpClient`): 10 Sekunden Verbindungsaufbau, 60 Sekunden je Lesevorgang, 3 Minuten für einen
  Aufruf insgesamt.

Wichtig für später: An dem gemeinsamen Client darf nie ein Baustein hängen, der Anfragen mitschreibt (die
Kopfzeile `x-goog-api-key` wird von OkHttp nicht geschwärzt).

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
./gradlew :domain:test :data:test :domain:koverVerify
```

`koverVerify` schlägt fehl, wenn die Tests weniger als 90 % der Zeilen von `:domain` durchlaufen. Sobald `:database`
eingebunden ist, kommt `:database:testDebugUnitTest` dazu.

```bash
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug
```
