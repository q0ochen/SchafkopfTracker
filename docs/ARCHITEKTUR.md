# Architektur (verbindlicher Vertrag)

Stand: 16.11.2026. Namen, Pakete, Funktionen und Pfade in diesem Dokument sind exakt so zu verwenden.
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
| Minimale Rechte | Einzige Berechtigung: `INTERNET`, nur für die freiwilligen Zusätze Abend-Kommentator und Sicherung in Google Drive. Die Sicherung in eine Datei schreibt über die Dateiauswahl von Android (Storage Access Framework) und braucht keine. |
| Barrierefrei | Dunkles Design als Standard. Tippziele mindestens 48 dp, Hauptaktionen 56 dp und mehr, Schrift bis 200 %, hoher Kontrast, keine Information nur über Farbe, Diagramme zusätzlich als Text, `contentDescription` bzw. Semantik überall. |
| Sprache | Oberfläche Deutsch, alle Texte in `strings*.xml`. Kommentare Deutsch. Bezeichner Englisch mit deutschen Fachbegriffen (Rufspiel, Laufende, Bock; Abend heißt `Evening`). |
| Keine Namen | Namen von Personen stehen nie im Quelltext, in Tests, Issues oder Commit-Nachrichten. Tests verwenden Beispielnamen. |
| Eigene Grafiken | Symbole sind selbst gezeichnete Vektorgrafiken (CC0). |

## 1. Module

| Modul | Art | Paket | Inhalt |
|---|---|---|---|
| `:domain` | Kotlin/JVM | `io.github.q0ochen.schafkopf.domain` | Modelle, Regelwerk, `ScoreCalculator`, `PayoutCalculator`, Abend-Logik, `StatisticEngine`, Zusammenfassung, Ports |
| `:data` | Kotlin/JVM | `io.github.q0ochen.schafkopf.data` | Sicherung als JSON, CSV, Netzanbindungen (Sprachmodell, Google Drive REST v3), Dateiformat der ersten Testversionen (`FileStorage`) |
| `:database` | Android-Bibliothek | `io.github.q0ochen.schafkopf.database` | Room: Tabellen, DAOs mit `Flow`, Repositories |
| `:app` | Android-App | `io.github.q0ochen.schafkopf.app` | Compose-Oberfläche, ViewModels (MVVM), Navigation, Koin-Module, Android-Adapter |
| `:wear` | Wear-OS-App (geplant) | `io.github.q0ochen.schafkopf.wear` | Kachel und Schnelleingabe auf der Uhr |
| `web/` | Web-App (TypeScript, Preact; kein Gradle-Modul) | `web/src` | Zwischenlösung fürs iPhone: Port von `:domain` und vom Dateiformat aus `:data`, eigene Oberfläche, Speicher im Browser (Abschnitt 7) |

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

Tabellen `players`, `evenings`, `entries` (Fremdschlüssel auf den Abend, Löschen mit `CASCADE`) und `settings`
(eine Zeile, die Einstellungen als JSON in der Schreibweise der früheren `settings.json`, `SettingsJson`). DAOs liefern
`Flow`s, sodass sich jede Änderung von selbst bei allen Bildschirmen meldet. Ein Abend wird samt Einträgen in einer
Transaktion gespeichert. Aufzählungen stehen mit ihrem Namen in der Datenbank: Die Namen der Enum-Werte sind Teil des
Schemas. Das Schema jeder Version liegt unter `database/schemas/`; jede Änderung braucht eine Migration mit Test.

Die ersten Testversionen speicherten JSON-Dateien im Ordner `daten` (`FileStorage` in `:data`). Beim Start übernimmt
`LegacyFileImport` (`app/storage`) sie einmalig mit `importMissing`: in einer Transaktion, ohne Vorhandenes zu
überschreiben, bei einem Fehler beim nächsten Start erneut. Danach heißt der Ordner `daten-uebernommen` und bleibt
als Rückfall liegen.

### Sicherung (`:data`)

Eine UTF-8-JSON-Datei mit Kopf (`format`, `schemaVersion`, `appVersion`, `exportedAt`), festen Schlüsseln und
Beträgen in Cent. Der Import führt zusammen und löscht nichts; ein Abend, der in sich nicht stimmig ist (falsche
Sitzordnung, unbekannte Spieler, Buchungen, die nicht aufgehen, Beträge außerhalb des Bereichs, mit dem die App
rechnet), wird mit Begründung ausgelassen (`EveningCheck`, `EveningProblem`). Schlüssel und Anmeldedaten stehen nie in
der Sicherung.

Automatische Sicherung (`app/backup`): Der Nutzer wählt einmal eine Datei in der Dateiauswahl von Android, zum
Beispiel in Google Drive (`DocumentAccess`, dauerhaftes Lese- und Schreibrecht). Jede Änderung an Spielern, Abenden
oder der Regel-Vorlage stößt `AutoBackupWorker` an (WorkManager, eindeutiger Auftrag, 3 Sekunden gebündelt, bei
Fehlern mit wachsendem Abstand ab 30 Sekunden, höchstens 10 Versuche, beim nächsten Start erneut). Das Hochladen
übernimmt die App des Anbieters, auch später ohne Netz. Ziel und Verlauf stehen nur auf dem Gerät
(`AutoBackupStore`), nie in der Sicherung.

Google Drive direkt (`AutoBackupKind.GOOGLE_DRIVE`): Anmeldung im Browser mit AppAuth (`app/drive/DriveAccount.kt`;
OAuth 2.0 mit PKCE, Android-Client mit eigenem URI-Schema `io.github.q0ochen.schafkopf:/oauth2redirect`, Freigaben
`openid`, `email`, `drive.file`), Aufrufe über `GoogleDriveClient` und `DriveBackup` (`:data`, Ordner
`SchafkopfTracker_Backups`, Datei `schafkopf-sicherung.json`, Ersetzen per `PATCH`). Der Auftrag an WorkManager wartet
auf Netz. Einrichtung des Google-Cloud-Projekts: [GOOGLE-CLOUD.md](GOOGLE-CLOUD.md).

Geheimnisse (Schlüssel des Abend-Kommentators, Anmeldestand bei Google) liegen AES-GCM-verschlüsselt mit einem
Schlüssel aus dem Android Keystore in `geheimnisse.xml` (`app/security`); diese Datei und das Ziel der automatischen
Sicherung sind von der Datensicherung von Android ausgenommen (`res/xml/backup_rules.xml`,
`data_extraction_rules.xml`).

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
- Bildschirme: Start, Neuer Abend, Abend, Spiel eintragen, Abschluss, Statistik (ein Abend oder alle Abende, oben
  wählbar; Vermögensverlauf als selbst gezeichnetes Liniendiagramm mit Tabelle als Alternative; Spielarten als
  Balkendiagramm `GameModeChart` mit eigenem Maßstab je Spielart, `BarScale`), Spieler, Einstellungen, Regelwerk,
  Sicherung, Abend-Kommentator.
- Abend-Kommentator (`app/ai`): `Commentator` mit `SummaryService` und `TemplateSummary`; Zustimmung je Dienst
  (`AiSettings.consentHost`), Platzhalter statt Namen als Standard, Standardmodell `gemini-flash-latest`. Für einen
  Abend `SummaryFactsBuilder` und `SummaryPrompt.SYSTEM_PROMPT`, für alle Abende `HistoryFactsBuilder` (auf der
  `StatisticEngine`) und `SummaryPrompt.HISTORY_SYSTEM_PROMPT`. Abschluss und Statistik teilen sich den Ablauf
  (`ui/ai/Commentary.kt`: `CommentaryFlow`, `CommentarySection`, Zustimmungsdialog).
- Teilen: Abschluss und Statistik als Text (`ACTION_SEND`) und als Bild (Compose-Ansicht über `GraphicsLayer` als PNG
  im Cache, weitergegeben über `FileProvider`, nur der Ordner `cache/geteilt`).
- Texte der Oberfläche stehen in `res/values/strings*.xml`; Beträge formatiert ausschließlich `:app`.

## 5. Tests

- `:domain`: JUnit Jupiter, MockK, Turbine; Ziel über 90 % Abdeckung (gemessen mit Kover).
  Pflicht: Preisberechnung aller Spielarten, Potenzen aus Bock und Gelegt, Verteilung der Spielschuld, Nullsumme.
- `:data`: Sicherung hin und zurück, Import-Prüfungen, CSV, Netzanbindungen gegen einen Testserver.
- `:database`: Room mit SQLite im Arbeitsspeicher (Robolectric), DAOs, Repositories, Einstellungen, Übernahme,
  Schema-Wächter.
- `:app`: Bildschirmfotos der wichtigsten Bildschirme (Roborazzi) als Sichtprüfung; eigene UI-Tests sind nicht
  verlangt. Dazu Tests einzelner ViewModels ohne Oberfläche, mit Ablagen im Arbeitsspeicher (`GameFormViewModelTest`).

## 6. Bauen und prüfen

```bash
./gradlew :domain:test :data:test :domain:koverVerify
```

`koverVerify` schlägt fehl, wenn die Tests weniger als 90 % der Zeilen von `:domain` durchlaufen. Sobald `:database`
eingebunden ist, kommt `:database:testDebugUnitTest` dazu.

```bash
./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug
```

Der Build-Cache von Gradle ist ausgeschaltet (`gradle.properties`), weil mit ihm nach Änderungen an öffentlichen
Schnittstellen Tests unbemerkt wegfielen. Ohne Android-SDK lassen sich `:domain` und `:data` mit
`tools/jvm-build.sh` bauen und testen; `tools/cloud-setup.sh` richtet das SDK auf einer frischen Linux-Maschine ein.

## 7. Web-App (`web/`)

Zwischenlösung fürs iPhone, solange es nur die Android-App gibt. Sie läuft im Browser (Safari: „Zum
Home-Bildschirm“), braucht keinen Server und wird von `main` aus auf GitHub Pages veröffentlicht
(`.github/workflows/web.yml`). Einzelheiten und Befehle: [web/README.md](../web/README.md).

| Regel | Umsetzung |
|---|---|
| Gleiche Rechnung | `web/src/core` ist ein Port von `:domain` (Regelwerk, `ScoreCalculator`, `PayoutCalculator`, `EveningCalculator`, `SettleUp`, `StatisticEngine`) mit denselben Namen. Kotlin-Objekte werden gleichnamige Objekte, abgeleitete Eigenschaften fertig berechnete Felder, Maps `ReadonlyMap` in derselben Reihenfolge; wo Kotlin mit `Int` überläuft, läuft der Port genauso über. |
| Gleiche Dateien | `web/src/data/format` ist ein Port des Dateiformats und der Sicherung aus `:data` (gleiche Texte, gleiche Prüfungen, gleiche Meldungen). Eine Sicherung vom iPhone liest die Android-App und umgekehrt. |
| Golden-Tests | Kotlin-Tests (`WebGoldenTest` in `:domain`, `WebFormatGoldenTest` in `:data`) lassen den echten Kotlin-Code viele Fälle rechnen und schreiben die Ergebnisse nach `web/src/core/golden/kotlin-golden.json` bzw. `web/src/data/golden/kotlin-format-golden.json`. Die TypeScript-Tests vergleichen jedes Feld. Ändert sich der Kotlin-Code, schlägt der Kotlin-Test fehl, bis die Datei mit `-PgoldenWrite=true` neu erzeugt und der Port angepasst ist. |
| Speicher | IndexedDB (`web/src/data/repository.ts`): je Abend, für die Spielerliste und für die Einstellungen ein JSON-Text im Dateiformat der Android-App. Nichts verlässt das Gerät. |
| Gleiche Spieler | Die vorbelegte Runde hat dieselben Kennungen wie in der Android-App (`spieler-1` bis `spieler-4`). |
| Oberfläche | Logik der Bildschirme als reine Funktionen nach dem Vorbild der ViewModels (`web/src/ui/gameForm.ts`, `newEvening.ts`, `board.ts`), Oberflächen-Tests mit Playwright in iPhone-Größe und Prüfung mit axe. Spielerfarben aus einer auf Farbfehlsichtigkeit geprüften Palette, als Punkt neben dem Namen. |
| Leitplanken | Dieselben wie oben, sinngemäß: nur freie Bibliotheken (`preact`, `idb`), keine Analyse, Content-Security-Policy ohne fremde Quellen, Start ohne Netz über den Service Worker. Die KI-Zusammenfassung gibt es in der Web-App nicht. |


## 8. Gemeinsame Runde (Abgleich über Supabase)

Verbindlicher Vertrag für Server (`supabase/`), Kotlin (`:domain`, `:data`, `:database`, `:app`) und Web-App.
**Namen, Tabellen, Spalten, Funktionen und Fehlercodes sind genau so zu verwenden.** Vorbild für Server und
Anmeldung ist das Schwesterprojekt DrinkWithMe (dort `docs/ARCHITEKTUR.md`, Abschnitt 2 bis 4).

### 8.1 Grundsätze

| Regel | Folge |
|---|---|
| Lokal zuerst | Jedes Gerät hält alle Abende und Spieler selbst und funktioniert ohne Netz und ohne Server. Abgeglichen wird im Hintergrund. Ein pausiertes oder unerreichbares Supabase-Projekt hält einen Abend nie auf. |
| Server ist die gemeinsame Fassung | Je Abend liegt auf dem Server **ein** JSON-Dokument mit Versionsnummer. Geschrieben wird nur mit „erwartete Version“ (Vergleichen und Ersetzen). Wer zu spät kommt, führt zusammen (8.5) und versucht es erneut. Alle Geräte übernehmen am Ende die Fassung des Servers. |
| Nur Tatsachen | Hochgeladen werden Abende im Dateiformat der Sicherung (`StorageCodec.encodeEvening`), Spieler und das Regelwerk für neue Abende. Stand, Geber, Bock-Zähler und Statistik rechnet jedes Gerät selbst. |
| Anmeldung | Nur Google über Supabase Auth (PKCE im Browser, nie WebView). Zustimmungsbildschirm bei Google „In Produktion“, Bereiche nur `openid email profile`. |
| Eine Runde je Gerät | Ein Gerät hängt an höchstens einer Runde. Wer eine Runde anlegt, bringt die Daten seines Geräts mit; wer beitritt, übernimmt die Daten der Runde (die eigenen werden nach Rückfrage ersetzt). |
| Eigener Name | Jedes Mitglied legt seinen Namen selbst fest („Das bin ich“). Danach ist er fest; andere können ihn nicht ändern. Spieler ohne Konto (Gäste) kann jedes Mitglied anlegen und umbenennen. |
| Jeder darf eintragen | Es gibt keine Sperre. Die App zeigt nur, wer gibt („Du gibst – trag dieses Spiel ein“), und weist auf vermutlich doppelt eingetragene Spiele hin. |
| Kein Realtime | Im Vordergrund fragt die App alle 4 Sekunden die Versionsnummer der Runde ab (eine kleine Anfrage). |
| Freie Bausteine | Android: OkHttp und `androidx.browser`, keine Supabase-Bibliothek. Web: `@supabase/supabase-js`. |

Öffentliche Konfiguration (kein Geheimnis): Projekt-URL und Publishable Key. Android: `app/supabase.properties`
(`supabaseUrl`, `supabasePublishableKey`) → `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_PUBLISHABLE_KEY`. Web:
`VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY` (im Workflow aus den GitHub-Variablen `SUPABASE_URL` und
`SUPABASE_PUBLISHABLE_KEY`). Fehlt die Konfiguration, zeigt „Gemeinsame Runde“ nur „Der Server ist noch nicht
eingerichtet“; alles andere funktioniert.

Adressen: Rückkehr aus der Anmeldung Android `schafkopftracker://auth-callback`, Web die Start-Adresse der Web-App
(`https://q0ochen.github.io/SchafkopfTracker/`). Einladung: `<Web-App>#/einladung/<token>`; die Seite bietet „In der
Android-App öffnen“ (`schafkopftracker://einladung/<token>`) und „Hier beitreten“. In beiden Apps lässt sich der Link
oder der bloße Code auch einfügen (`InviteLinks.tokenFrom(text)`: die erste Folge aus genau 32 Zeichen `0-9a-f`
im Text, also beide Link-Formen und der bloße Code, auch mitten in einer Nachricht; sonst `null`).

### 8.2 Tabellen (Schema `public`, RLS auf jeder Tabelle, Schreiben nur über die Funktionen aus 8.3)

```sql
rounds(id uuid PK default gen_random_uuid(), name text 1..40, created_by uuid null → auth.users ON DELETE SET NULL,
       created_at timestamptz default now(), rev bigint not null default 0, rules jsonb null)
  -- rev: zählt jede Änderung der Runde (Abende, Spieler, Regeln, Mitglieder). rules: Regelwerk für neue Abende
  -- im Format von RuleSetJson.encode.
round_players(round_id uuid → rounds ON DELETE CASCADE, id text 1..64, name text 1..24, color_index int 0..31,
       archived boolean, position int, owner uuid null → auth.users ON DELETE SET NULL, name_locked boolean,
       deleted boolean, rev bigint, PK (round_id, id))
  -- owner: das Mitglied, dem der Spieler gehört (null = Gast). Namen sind je Runde eindeutig, ohne Groß/Klein
  -- (eindeutiger Index auf (round_id, lower(name)) für nicht gelöschte Zeilen). position: Reihenfolge des Anlegens.
round_members(round_id uuid → rounds ON DELETE CASCADE, user_id uuid → auth.users ON DELETE CASCADE,
       player_id text null, joined_at timestamptz default now(), PK (round_id, user_id),
       UNIQUE (round_id, player_id), FK (round_id, player_id) → round_players)
round_evenings(round_id uuid → rounds ON DELETE CASCADE, id text 1..64, doc jsonb, version int, deleted boolean,
       rev bigint, updated_at timestamptz, updated_by uuid null → auth.users ON DELETE SET NULL, PK (round_id, id))
round_invites(token_hash text PK, round_id uuid → rounds ON DELETE CASCADE, created_by uuid → auth.users ON DELETE
       CASCADE, created_at timestamptz default now(), expires_at timestamptz)
  -- token_hash = hex(sha256(token)); der Token selbst wird nie gespeichert. Keine Zugriffsregel: nur über Funktionen.
```

Lesen (RLS `SELECT` für `authenticated`): nur Mitglieder der jeweiligen Runde (`is_round_member(round_id)`,
`SECURITY DEFINER`). `anon` darf nichts. Direkte `INSERT`/`UPDATE`/`DELETE` gibt es für niemanden.

Jede schreibende Funktion erhöht zuerst `rounds.rev` (`UPDATE rounds SET rev = rev + 1 … RETURNING rev`; die
Zeilensperre reiht alle Schreiber einer Runde hintereinander) und stempelt die geänderten Zeilen mit diesem Wert.
Dadurch gilt: Wer Zeilen mit `rev > n` liest, hat danach alles bis zum größten gelesenen `rev` vollständig.

Grenzen: höchstens 5 Runden je Konto, 12 Mitglieder, 40 nicht gelöschte Spieler und 3000 Abende je Runde, ein
Abend-Dokument höchstens 300 000 Bytes, höchstens 20 gültige Einladungen je Runde.

### 8.3 Funktionen (`SECURITY DEFINER`, `search_path = ''`, nur für `authenticated`)

Fehler: `RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = '<code>'` (PostgREST antwortet mit 400 und
`{"code":"P0001","message":"<code>"}`). Codes: `not_member`, `invalid_name`, `name_taken`, `name_locked`,
`already_claimed`, `player_taken`, `player_owned`, `invalid_invite`, `limit_reached`, `too_large`, `invalid_doc`.
Wer kein Mitglied der genannten Runde ist, bekommt immer `not_member`.

| Funktion | Verhalten |
|---|---|
| `create_round(p_name text) → uuid` | Legt die Runde an (Name beschnitten, 1 bis 40 Zeichen, sonst `invalid_name`) und macht den Aufrufer zum Mitglied. |
| `my_rounds() → table(id uuid, name text, rev bigint, member_count int, my_player_id text, is_creator boolean)` | Alle Runden des Aufrufers, älteste Mitgliedschaft zuerst. |
| `create_invite(p_round uuid) → text` | Neuer Token (32 Zeichen `0-9a-f`), gültig 14 Tage; abgelaufene Einladungen der Runde werden dabei gelöscht. |
| `invite_info(p_token text) → table(round_id uuid, round_name text, member_count int, already_member boolean)` | Unbekannt oder abgelaufen → `invalid_invite`. |
| `join_round(p_token text) → uuid` | Macht den Aufrufer zum Mitglied (schon Mitglied: nichts weiter). Gehört ihm in der Runde bereits ein Spieler (`owner`), wird er wieder verknüpft. |
| `leave_round(p_round uuid) → void` | Entfernt die Mitgliedschaft; der eigene Spieler bleibt mit `owner`. Verlässt das letzte Mitglied die Runde, wird sie gelöscht. |
| `claim_player(p_round uuid, p_player text, p_name text, p_color_index int) → void` | „Das bin ich“: Hat der Aufrufer schon einen Spieler → `already_claimed`. Gibt es `p_player` (nicht gelöscht): gehört er einem anderen → `player_taken`, sonst wird er übernommen. Gibt es ihn nicht, wird er angelegt. Gesetzt werden `owner`, `name = p_name` (1 bis 24 Zeichen nach Beschneiden, sonst `invalid_name`; vergeben → `name_taken`), `name_locked = true` und `round_members.player_id`. Gehörte der Spieler dem Aufrufer schon, bleibt der Name. |
| `save_player(p_round uuid, p_id text, p_name text, p_color_index int, p_archived boolean, p_deleted boolean) → bigint` | Gäste anlegen und ändern; für alle Spieler Farbe und „archiviert“. Namensänderung bei `name_locked` → `name_locked` (Ausnahme: der Ersteller der Runde darf korrigieren). `p_deleted`: nur Gäste (`owner` null), sonst `player_owned`; die Zeile bleibt mit `deleted = true`. Liefert das neue `rev`. |
| `save_rules(p_round uuid, p_rules jsonb) → bigint` | Setzt `rounds.rules` (muss ein JSON-Objekt sein, sonst `invalid_doc`). |
| `save_evening(p_round uuid, p_id text, p_doc jsonb, p_expected_version int, p_deleted boolean) → table(version int, rev bigint)` | Gibt es den Abend noch nicht: `p_expected_version` muss 0 sein, die neue Version ist 1. Sonst muss `version = p_expected_version` gelten; dann `doc`, `deleted`, `version + 1`. Passt die Version nicht, kommt **kein Fehler**, sondern eine leere Ergebnismenge (der Aufrufer liest den Abend neu). `p_doc` muss ein JSON-Objekt mit `"id" = p_id` sein (`invalid_doc`). |

Lesen über PostgREST (`apikey` = Publishable Key, `Authorization: Bearer <Access-Token>`):

- `rounds?id=eq.<runde>&select=id,name,rev,rules,created_by` (leer: Runde gelöscht oder kein Mitglied mehr)
- `round_members?round_id=eq.<runde>&select=user_id,player_id,joined_at&order=joined_at.asc`
- `round_players?round_id=eq.<runde>&rev=gt.<n>&select=id,name,color_index,archived,position,owner,name_locked,deleted,rev&order=rev.asc`
- `round_evenings?round_id=eq.<runde>&rev=gt.<n>&select=id,doc,version,deleted,rev&order=rev.asc&limit=100`
  (seitenweise: solange 100 Zeilen kommen, mit dem größten gelesenen `rev` weiter) und einzeln mit `&id=eq.<abend>`.

### 8.4 Gerätezustand (`RoundStore`: Room bzw. IndexedDB)

- `RoundLink(roundId, roundName, userId, playerId?, isCreator, cursor, rulesBase?, members)`: die verbundene Runde;
  `cursor` ist das größte verarbeitete `rev`, `rulesBase` das zuletzt abgeglichene Regelwerk (JSON-Text),
  `members` die Mitglieder (`userId`, `playerId?`).
- `EveningBase(eveningId, version, evening)`: je Abend die zuletzt gesehene Fassung des Servers.
- Je Spieler der letzte Stand des Servers (`RemotePlayer`: `id`, `name`, `colorIndex`, `archived`, `position`,
  `owner?`, `nameLocked`, `rev`).

Ein Abend ist „geändert“, wenn er von seiner Basis abweicht oder keine hat; „gelöscht“, wenn es die Basis noch gibt,
den Abend aber nicht mehr. Für Spieler gilt dasselbe. Die Sitzung (Refresh-Token) liegt getrennt und verschlüsselt.

### 8.5 Zusammenführen (`EveningMerge.merge3(base, local, remote)`, reine Funktion, Port in `web/src/core/round`)

Kopf (`startedAt`, `finishedAt`, `location`, `seating`, `firstDealer`, `rules`), je Feld: ohne Basis das Feld des
Servers; ist das lokale Feld gleich der Basis, das des Servers; sonst das lokale (eine lokale Änderung gewinnt).

Einträge, mit `B`, `L`, `R` als Einträge von Basis, lokal und Server nach Kennung; „gleich“ heißt: alle Felder gleich.

1. Für jeden Eintrag `r` des Servers in dessen Reihenfolge, `l = L[r.id]`, `b = B[r.id]`:
   - `l` vorhanden: `l == r` → `l`; `b` vorhanden und `l == b` → `r`; sonst `l`.
   - `l` fehlt: `b` vorhanden und `r == b` → entfällt (lokal gelöscht); sonst `r` (neu vom Server, oder die Änderung
     des Servers gewinnt gegen das lokale Löschen).
2. Danach jeder lokale Eintrag `l` in lokaler Reihenfolge, den der Server nicht hat, `b = B[l.id]`:
   - `b` vorhanden und `l == b` → entfällt (auf dem Server gelöscht).
   - sonst einfügen (lokal neu, oder die lokale Änderung gewinnt gegen das Löschen): hinter den letzten Eintrag des
     Ergebnisses, der in der lokalen Liste vor `l` steht (sonst an den Anfang); von dort weiter nach hinten über alle
     direkt folgenden Einträge, die neu vom Server sind (Kennung weder in `L` noch in `B`) und nach
     `(recordedAt, id)` vor `l` liegen.

Bei doppelten Kennungen in einer Liste (beschädigte Daten) zählt jeweils der erste Eintrag; das Ergebnis enthält
jede Kennung höchstens einmal. Ohne Basis bleibt damit alles von beiden Seiten, bei gleicher Kennung der Eintrag von
hier.

Es gilt unter anderem: `merge3(b, l, l) == l`, `merge3(b, b, r) == r`, `merge3(b, l, b) == l` (solange die
gemeinsamen Einträge in derselben Reihenfolge stehen; sonst gilt für sie die des Servers); nichts, was auf einer
Seite neu ist, geht verloren; das Ergebnis erneut mit dem Server zusammengeführt ändert sich nicht
(`merge3(b, m, r) == m` und `merge3(r, m, r) == m` für `m = merge3(b, l, r)`); ohne Konflikt ist es gleich, welches
Gerät zuerst abgleicht, wenn jedes seine neuen Einträge mit fortlaufender Zeit aufschreibt.

`DuplicateCheck.suspects(evening): Set<EntryId>`: Zwei in der Liste direkt aufeinander folgende Spiele, die bis auf
`id`, `recordedAt` und `note` gleich sind und deren `recordedAt` höchstens 120 Sekunden auseinanderliegen; gemeldet
wird das hintere.

### 8.6 Ein Abgleich (`RoundSync.sync(quick)`, höchstens einer gleichzeitig)

Ein Abgleich besteht aus höchstens zwei Durchgängen. Ein Durchgang:

1. Ohne `RoundLink` → `NotLinked`. Kopf der Runde lesen. Gibt es ihn nicht, `my_rounds()` gegenprüfen: Fehlt die
   Runde auch dort → `Detached` (Verbindung und Basen löschen, die Daten bleiben auf dem Gerät); steht sie dort →
   `Failed`. Mit `quick` und `rev == cursor` endet der Abgleich hier mit `Unchanged` (eine Anfrage; ob auf dem
   Gerät etwas wartet, wird dann nicht geprüft).
2. Ist `rev` größer als `cursor`: Mitglieder, geänderte Spieler und geänderte Abende lesen (`rev > cursor`).
   Ist `rev` kleiner als `cursor` (Server neu aufgesetzt), vorher `cursor = 0`.
3. **Spieler vom Server:** Zeilen in der Reihenfolge `position`. Weicht eine Zeile inhaltlich (Name, Farbe,
   „archiviert“) von der Basis ab oder gibt es keine Basis, gilt der Server (auch gegen eine Änderung von hier);
   stimmt sie mit der Basis überein, bleibt eine Änderung von hier bestehen. Die Basis ist danach die Zeile.
   Gelöschte Zeilen werden vorgemerkt (Schritt 7).
4. **Spieler von hier:** Was von der Basis abweicht oder keine hat, hochladen (`save_player`; Farbnummern außerhalb
   0 bis 31 vorher auf `colorIndex mod 32` umstellen). Lehnt der Server ab (`name_locked`, `name_taken`,
   `invalid_name`, `player_owned`, `limit_reached`): mit Basis zurück auf die Basis; ohne Basis und in keinem Abend
   gebraucht → Spieler entfernen; ohne Basis, gebraucht und `name_taken` → umbenennen („Name 2“, „Name 3“ …,
   auf 24 Zeichen gekürzt) und im nächsten Durchgang hochladen; sonst bleibt er und wird in dieser Sitzung nicht
   erneut versucht. Jede Ablehnung ergibt einen Hinweis (`SyncNotice`).
5. **Regelwerk für neue Abende:** Kann die App das Regelwerk des Servers nicht lesen, passiert nichts. Hat der
   Server keines → das lokale hochladen. Hat er ein anderes als `rulesBase` → übernehmen (Server gewinnt). Sonst
   ein lokal geändertes hochladen (`save_rules`).
6. **Abende**, für jeden vom Server geänderten, lokal geänderten oder lokal gelöschten Abend (höchstens 3 Versuche;
   kommt ein Abend beim Lesen mehrfach, zählt die höchste Version):
   - Nicht mitgelesen (der Server hat noch die Basis oder kennt den Abend nicht): lokal vorhanden und geändert →
     `save_evening` mit der Version der Basis (ohne Basis 0); lokal gelöscht → als gelöscht hochladen.
   - Mitgelesen, nicht gelöscht: unter der Sperre des `EveningEditor` lokal durch `merge3(basis, lokal, server)`
     ersetzen, **danach** die Basis auf die Fassung des Servers setzen, dann – weicht das Ergebnis vom Server ab –
     hochladen mit der Version des Servers. Lokal gelöscht (Basis da, Abend weg): hat der Server noch die Version
     der Basis → als gelöscht hochladen; sonst die Fassung des Servers wieder anlegen. Lokal unbekannt → anlegen.
     Ist die Version des Servers kleiner als die der Basis, wird ohne Basis zusammengeführt.
   - Mitgelesen, gelöscht: lokal unverändert oder schon weg → lokal löschen; lokal geändert → wieder hochladen
     (`deleted = false`, Version des Servers).
   - Leere Antwort von `save_evening` (Version passt nicht): den Abend einzeln neu lesen und wiederholen; kennt
     der Server ihn gar nicht, die Basis löschen. Danach folgt in jedem Fall ein zweiter Durchgang.
   - Nach jedem Erfolg ist die Basis die hochgeladene Fassung. Ein Dokument, das sich nicht lesen lässt, wird
     übersprungen und gezählt; es wird nie überschrieben.
7. **Spieler löschen**, erst jetzt und nie, solange ein Abend auf dem Gerät den Spieler nennt: Vom Server als
   gelöscht gemeldet → lokal löschen; wird er gebraucht, bleibt er, verliert die Basis und geht im nächsten
   Durchgang als neu wieder hoch. Lokal gelöscht (Basis da, Spieler weg) → `save_player(…, p_deleted = true)`;
   wird er inzwischen gebraucht oder lehnt der Server ab, kommt er aus der Basis zurück.
8. `cursor`: der beim Lesen des Kopfes gesehene `rev`, erhöht um die eigenen Änderungen, solange deren `rev`
   lückenlos anschließt (dann hat niemand dazwischen geschrieben). Schließt ein `rev` nicht an, wurde ein
   Hochladen abgelehnt oder ist in Schritt 4 oder 7 etwas für den nächsten Durchgang entstanden, folgt der zweite
   Durchgang. Ein eingetragenes Spiel kostet so zwei Anfragen (Kopf und `save_evening`).

Ergebnis: `Synced(pulled, pushed, pending, unreadable, notices)`, `Unchanged`, `NotLinked`, `Detached`, `SignedOut`
(Sitzung ungültig), `Offline` (keine Verbindung), `Failed` (Server antwortet mit Fehler, zum Beispiel pausiert).
`pendingCount()` zählt geänderte und gelöschte Abende und Spieler sowie ein geändertes Regelwerk.

Bricht ein Abgleich mittendrin ab, ist nichts verloren: `cursor` steht dann noch auf dem alten Wert, das Gelesene
kommt noch einmal und ändert nichts. Bekannte Grenze: Geht die Antwort auf ein Hochladen verloren und löscht ein
anderes Gerät vor dem nächsten Abgleich einen dabei neu angelegten Eintrag, taucht dieser Eintrag einmal wieder auf
(das Gerät kann nicht wissen, dass er schon auf dem Server war; im Zweifel bleibt ein Spiel lieber erhalten).

Ausgelöst wird: beim Öffnen der App, 0,5 Sekunden nach jeder lokalen Änderung, alle 4 Sekunden im Vordergrund
(`quick`, solange auf dem Gerät nichts wartet), bei Fehlern mit wachsendem Abstand (4, 8, 16, 32, 60 Sekunden).
Android zusätzlich über WorkManager (mit Netz), damit Wartendes auch nach dem Schließen ankommt; Web bei `online`
und wenn die App wieder sichtbar wird.

### 8.7 Verbinden und Trennen

Umgesetzt in `RoundConnection` (`create`, `join`, `use`, `claim`, `leave`, `disconnect`, `hasOwnData`).

- **Runde anlegen:** Vorbelegte Spieler (`spieler-1` bis `spieler-4`), die noch „Spieler N“ heißen und in keinem
  Abend vorkommen, werden lokal entfernt. `create_round`, `RoundLink` mit `cursor = 0` ohne Basen, dann Abgleich:
  Alles Lokale geht als neu hoch.
- **Einer Runde beitreten / eine Runde des Kontos auf diesem Gerät verwenden:** Hat das Gerät Abende oder
  umbenannte Spieler, wird nachgefragt („… werden durch die Daten der Runde ersetzt“). Dann lokale Abende, Spieler
  und Basen löschen, `RoundLink` mit `cursor = 0`, Abgleich.
- **Das bin ich:** Solange `playerId` fehlt, fragt die App nach: einen Spieler ohne Besitzer wählen oder „Ich bin
  neu“, dann den eigenen Namen eingeben und bestätigen („lässt sich danach nicht mehr ändern“) → `claim_player`.
- **Abmelden / Runde verlassen:** `RoundLink` und Basen löschen, die Daten bleiben als örtliche Kopie auf dem Gerät.
  Wartende Änderungen gehen dabei verloren (Rückfrage, wenn `pendingCount() > 0`).
- Mit verbundener Runde legt die App keine vorbelegten Spieler mehr an.

### 8.8 Kotlin und Web

- `:domain`, Paket `domain.round`: Modelle, `RoundApi` und `RoundStore` (Ports), `EveningMerge`, `DuplicateCheck`,
  `InviteLinks`, `RoundSync`, `RoundConnection`, dazu `InMemoryRoundStore` und `InMemoryRoundServer` (ein Server im
  Speicher nach 8.2 und 8.3). Tests mit mehreren nachgestellten Geräten, Funklöchern, verlorenen Antworten und
  Zufallsläufen (`RoundSimulationTest`).
- `:data`, Paket `data.round`: `SupabaseAuthApi` (wie DrinkWithMe `AuthApi`: `authorize`, `token?grant_type=pkce`,
  `token?grant_type=refresh_token`, `logout?scope=local`), `SupabaseRoundApi` (PostgREST und Funktionen aus 8.3).
- `:database`: Room-Version 2 mit `round_link`, `evening_bases`, `player_bases` (`RoomRoundStore`), Migration 1 → 2.
- `:app`: `app.round` (Sitzung verschlüsselt über `EncryptedValues`, Rückkehr aus dem Browser in `MainActivity`
  mit `launchMode="singleTask"`, Abgleich im Vordergrund und per WorkManager), Bildschirm „Gemeinsame Runde“
  (`Screen.Round`), Hinweise auf Start-Bildschirm und Abend.
- Web: `web/src/core/round` (Port von `EveningMerge`, `DuplicateCheck`, `InviteLinks`, `RoundSync` mit Golden-Tests
  aus `WebGoldenTest`), `web/src/round` (Supabase-Zugriff, `RoundStore` in IndexedDB), Seiten `#/runde` und
  `#/einladung/<token>`; Anmeldung mit `signInWithOAuth` (PKCE), Rückkehr auf die Start-Adresse.

### 8.9 Oberfläche (Android-App und Web-App gleich, Texte wörtlich)

**Einstieg:** Auf dem Start-Bildschirm und in den Einstellungen eine Zeile „Gemeinsame Runde“. Ohne Runde mit dem
Untertitel „Alle tragen vom eigenen Handy ein“, mit Runde „Runde: ‹Name›“ und der Statuszeile.

**Statuszeile** (auch als Beschreibung des Wolken-Symbols in der Kopfzeile eines Abends; Tippen öffnet „Gemeinsame
Runde“). Es gilt die erste zutreffende Zeile:

| Lage | Text |
|---|---|
| Anmeldung abgelaufen | „Anmeldung abgelaufen – bitte neu anmelden“ |
| Abende, die diese Version nicht lesen kann | „‹n› Abende kann diese Version nicht lesen – bitte die App aktualisieren“ (bei 1: „1 Abend kann …“) |
| keine Verbindung | „Kein Netz – ‹n› Änderungen warten“ (bei 1: „… 1 Änderung wartet“, bei 0: „Kein Netz“) |
| Server antwortet mit Fehler | „Server nicht erreichbar – ‹n› Änderungen warten“ (bei 0: „Server nicht erreichbar“) |
| es wartet etwas | „‹n› Änderungen warten“ (bei 1: „1 Änderung wartet“) |
| sonst | „Abgeglichen“ |

**Bildschirm „Gemeinsame Runde“**, je nach Lage:

- *Kein Server eingerichtet:* „Der Server ist noch nicht eingerichtet.“ – „Sobald er eingerichtet ist, können alle
  vom eigenen Handy eintragen.“
- *Nicht angemeldet:* Überschrift „Gemeinsam eintragen“. „Alle in der Runde tragen vom eigenen Handy ein und sehen
  denselben Stand.“ – „Namen, Abende und Beträge der Runde liegen dafür auf einem Server in Frankfurt (Supabase).
  Zum Anmelden brauchst du ein Google-Konto.“ Knopf „Mit Google anmelden“. Wartet eine Einladung: „Du bist
  eingeladen. Melde dich an, um beizutreten.“
- *Angemeldet, ohne Runde:* „Angemeldet als ‹E-Mail›“. Wartet eine Einladung: Karte „Einladung in die Runde
  ‹Name›“ (‹n› Mitglieder), Knopf „Beitreten“. Hat das Konto Runden: „Deine Runden“, je Runde ‹Name› und
  „‹n› Mitglieder“, Knopf „Auf diesem Gerät verwenden“. „Neue Runde anlegen“: Feld „Name der Runde“ (Vorgabe
  „Schafkopfrunde“), Hinweis „Alle Abende und Spieler dieses Geräts kommen mit in die Runde.“, Knopf „Runde
  anlegen“. „Einladung einlösen“: Feld „Link oder Code“, Knopf „Beitreten“. Unten „Abmelden“.
- *Rückfrage vor dem Beitreten oder Verwenden*, wenn `hasOwnData`: „Daten dieses Geräts ersetzen?“ – „Auf diesem
  Gerät liegen ‹n› Abende. Sie werden durch die Daten der Runde ersetzt. Sichere sie vorher, wenn du sie behalten
  willst.“ (ohne Abende: „Die Spieler dieses Geräts werden durch die der Runde ersetzt.“) Knöpfe „Ersetzen“,
  „Zur Sicherung“, „Abbrechen“.
- *Verbunden, „Das bin ich“ steht aus:* Karte „Wer bist du?“ mit den Spielern ohne Besitzer (nicht archiviert) und
  „Ich bin neu“ zur Auswahl, Feld „Dein Name“ (vorbelegt mit dem Namen des gewählten Spielers, außer er heißt noch
  „Spieler ‹N›“), Hinweis „Dein Name lässt sich danach nicht mehr ändern.“, Knopf „Das bin ich“. Rückfrage: „Als
  ‹Name› festlegen?“ – „Der Name lässt sich danach nicht mehr ändern.“ Knöpfe „Festlegen“, „Abbrechen“.
- *Verbunden:* ‹Name der Runde›, Statuszeile, Knopf „Jetzt abgleichen“. „Mitglieder“: je Mitglied der Name seines
  Spielers, das eigene mit „(du)“, ohne Spieler „Noch ohne Namen“. Knopf „Einladen“: erzeugt den Link und gibt ihn
  weiter (Android: Teilen; Web: Teilen oder Kopieren) mit dem Text „Komm in unsere Schafkopfrunde ‹Name›: ‹Link›“;
  darunter „Der Link gilt 14 Tage.“ Unten „Runde verlassen“ (Rückfrage „Runde verlassen?“ – „Die Daten bleiben als
  Kopie auf diesem Gerät. Dein Name bleibt in der Runde.“) und „Abmelden“ (Rückfrage „Abmelden?“ – „Dieses Gerät
  wird von der Runde getrennt. Die Daten bleiben als Kopie hier.“). Wartet noch etwas, steht in beiden Rückfragen
  zusätzlich: „‹n› Änderungen sind noch nicht hochgeladen und kommen nicht mehr in der Runde an.“

**Fehlertexte:** `invalid_invite` „Die Einladung ist abgelaufen oder ungültig.“; kein Code im eingefügten Text „Das
ist kein gültiger Einladungslink.“; `name_taken` „Den Namen gibt es in der Runde schon.“; `invalid_name` „Bitte
einen Namen mit 1 bis 24 Zeichen eingeben.“ (Runde: „… 1 bis 40 Zeichen …“); `player_taken` „Diesen Spieler hat
gerade jemand anders gewählt.“; `already_claimed` „Du hast schon einen Namen in dieser Runde.“; `limit_reached`
„Mehr geht nicht: Die Grenze für Runden, Mitglieder oder Einladungen ist erreicht.“; keine Verbindung „Keine
Verbindung. Bitte später noch einmal versuchen.“; sonst „Das hat nicht geklappt. Bitte später noch einmal
versuchen.“

**Hinweise aus dem Abgleich** (`SyncNotice`, als kurze Meldung und auf dem Bildschirm „Gemeinsame Runde“):
`name_locked` „‹nowName› hat den Namen selbst festgelegt. Er bleibt.“; `name_taken` ohne `nowName` „Den Namen
‹name› gibt es in der Runde schon. Der neue Spieler wurde nicht angelegt.“; `name_taken` mit `nowName` „Den Namen
‹name› gibt es in der Runde schon. Der Spieler heißt jetzt ‹nowName›.“; `player_owned` „‹name› gehört zu einem
Mitglied und lässt sich nicht löschen.“; `limit_reached` „Die Runde hat schon 40 Spieler. ‹name› wurde nicht
hochgeladen.“; `invalid_name` „Der Name ‹name› ist nicht erlaubt.“

**Abend:** Mit Runde und laufendem Abend steht über der Eingabe, wer gibt: ist es der eigene Spieler
(`RoundLink.playerId == EveningState.nextDealer`), „Du gibst – trag dieses Spiel ein.“, sonst „‹Geber› gibt und trägt
ein.“ Eintragen kann trotzdem jeder. Spiele aus `DuplicateCheck.suspects` tragen in der Liste die Markierung
„Doppelt eingetragen?“ (als Text, nicht nur als Farbe).

**Spieler:** Ein fester Name (`nameLocked`) lässt sich nicht bearbeiten, darunter steht „Name selbst festgelegt“;
nur der Ersteller der Runde darf ihn korrigieren. Löschen lassen sich nur Spieler ohne Besitzer.

**Einladungsseite der Web-App** (`#/einladung/‹Code›`): „Einladung in eine Schafkopfrunde“, Knöpfe „In der
Android-App öffnen“ und „Hier beitreten“ (weiter nach `#/runde`, der Code wird gemerkt). Ohne gültigen Code: „Das
ist kein gültiger Einladungslink.“
