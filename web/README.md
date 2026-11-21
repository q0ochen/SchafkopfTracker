# Schafkopf Tracker – Web-App (Zwischenlösung fürs iPhone)

Die Web-App bringt den Schafkopf Tracker aufs **iPhone** (Safari, „Teilen“ → „Zum Home-Bildschirm“) und in jeden
Browser. Sie rechnet mit **demselben Rechenkern** wie die Android-App (nach TypeScript übertragen und mit
Golden-Tests gegen den Kotlin-Code abgesichert) und schreibt **dieselben Sicherungsdateien**: Eine Sicherung vom
iPhone lässt sich in der Android-App einlesen und umgekehrt.

Adresse: **https://q0ochen.github.io/SchafkopfTracker/** (GitHub Pages, kostenlos; veröffentlicht wird nur von `main`).

- **Start**: laufender Abend, „Neuer Abend“, Statistik, Spieler, Einstellungen, alle Abende mit Rangliste. Ein
  geplanter Abend (nicht beendet, sein Tag liegt nach heute) steht nur in der Liste, mit der Marke „geplant“.
- **Neuer Abend**: vier Spieler wählen (Vorschlag: wie letztes Mal), Sitzordnung, erster Geber, Datum (vorbelegt ist
  heute; ein späterer Tag legt den Abend als geplanten an, Knopf „Abend anlegen“), Ort mit Ortsvorschlägen.
- **Abend**: Stand je Spieler mit Platz und Geber, alle Einträge mit Beträgen, große Knöpfe „Spiel eintragen“,
  „Zusammengeworfen“, „6 Nixer – Bock“ und „Rückgängig“ (mit „Wiederherstellen“). Menü: Statistik, Abend teilen,
  Abend bearbeiten, Regeln dieses Abends, beenden/wieder öffnen, löschen. Der Bildschirm bleibt an, solange der
  Abend läuft.
- **Abend bearbeiten**: Datum (dieselbe Uhrzeit am neuen Tag, auch ein Tag nach heute), Ort (mit Ortsvorschlägen),
  Sitzordnung und erster Geber nachträglich ändern. Eingetragene Spiele und Beträge bleiben; wer gibt, wird neu berechnet. Gespeichert wird
  nur, was hier geändert wurde (`EveningEdit.apply`), damit in der gemeinsamen Runde nichts überschrieben wird, was ein
  anderes Handy inzwischen geändert hat.
- **Spiel eintragen/ändern**: Spielart, wer spielt (Über Kreuz: ein Tipp wählt das Paar), gelegt, Kontra/Retour,
  Laufende, Tout/Sie, Ausgang, Bock, Farbe, Spielwert mit Rechenweg; „Gewonnen“/„Verloren“ speichert sofort.
- **Abend teilen** (bis 21.11.2026 „Abschluss“): Endstand, Vermögensverlauf, „Wer zahlt wem“; als Bild (PNG,
  1080 Pixel breit, in den Farben des Designs) über das Teilen-Menü des iPhones, zum Beispiel direkt in WhatsApp, oder
  als Text. Kann der Browser keine Dateien teilen, wird das Bild heruntergeladen.
- **Statistik** (ein Abend oder alle): Kontostand, Vermögensverlauf (Diagramm mit Fadenkreuz, Tabelle),
  Spielarten, „Sollte er es lassen?“ (Legen), Bock-König, Solo-Spezialist, Mauerblümchen, Lieblingspartner,
  weitere Zahlen und Rekorde. Auswahl, Kontostand und Vermögensverlauf lassen sich als Bild teilen.
- **Einstellungen**: dunkel/hell/wie System, Bildschirm anlassen, Regeln für neue Abende, **Sicherung speichern
  und einlesen**.
- **Gemeinsame Runde** (nur, wenn die App mit einem Server gebaut wurde, siehe unten): Alle tragen vom eigenen
  Handy in dieselben Abende ein.
- **Ortsvorschläge** beim Feld „Ort“ (Vertrag: `docs/ARCHITEKTUR.md`, Abschnitt 4.3): ab drei getippten Zeichen Orte
  aus OpenStreetMap über Photon von komoot (`photon.komoot.io`, kostenlos, ohne Schlüssel). Gesendet wird nur der
  eingetippte Text, ohne Kennung von App, Gerät oder Nutzer; ohne Netz steht nur „Ortsvorschläge gibt es nur mit
  Internet.“ da, und der Ort lässt sich trotzdem frei eintippen.

Ohne gemeinsame Runde bleibt alles auf dem Gerät (IndexedDB): kein Server, keine Werbung, keine Analyse. Nur was im
Feld „Ort“ getippt wird, geht für die Ortsvorschläge an photon.komoot.io. Die App startet nach dem ersten Besuch auch
ohne Netz (Service Worker). Die KI-Zusammenfassung der Android-App gibt es hier nicht.

## Aufbau

```
web/
├── src/core/        Rechenkern aus dem Kotlin-Modul domain (Regeln, Abrechnung, Abend, Statistik)
│   ├── golden/      vom Kotlin-Code erzeugte Erwartungen (kotlin-golden.json) und ihr Test
│   └── round/       gemeinsame Runde: Zusammenführen, Abgleich, Verbinden, Server im Speicher (aus domain.round)
├── src/data/        Speicher im Browser (repository.ts, IndexedDB über idb)
│   ├── format/      Dateiformat und Sicherung aus dem Kotlin-Modul data (gleiche Texte, gleiche Prüfungen)
│   ├── place/       Ortsvorschläge über Photon (Port von data/place, Golden-Abschnitt `places`)
│   └── golden/      vom Kotlin-Code erzeugte Erwartungen (kotlin-format-golden.json) und ihr Test
├── src/app/         Zustand (store.ts), Router (Anker-Adressen), Sicherung, Anbindung des Dateiformats
├── src/round/       gemeinsame Runde im Browser: Steuerung, Planer, Speicher (IndexedDB), Texte
│   ├── supabase/    Anbindung an den Server mit supabase-js (wird nur bei Bedarf geladen)
│   └── testing/     nachgestelltes Supabase-Projekt und nachgestellte Handys für Tests
├── src/platform/    Browser-Funktionen: Bildschirm anlassen, Teilen/Herunterladen, dauerhafter Speicher
├── src/ui/          Preact-Oberfläche: Bildschirme, Bausteine, Diagramm, Logik der Formulare (reine Funktionen)
├── src/sw/          Service Worker (Start ohne Netz, Updates erst nach „Neu laden“)
├── scripts/         App-Symbole aus den Vektorgrafiken der Android-App, Vite-Plugins (CSP, Service Worker)
└── e2e/             Playwright-Tests in iPhone-Größe und am Desktop (inkl. axe-Prüfung auf Barrierefreiheit)
```

- Gespeichert wird wie in der Android-App: je Abend, für die Spielerliste und für die Einstellungen ein JSON-Text im
  Dateiformat der Android-App (`src/data/repository.ts`).
- Die vier vorbelegten Spieler haben dieselben Kennungen wie in der Android-App (`spieler-1` … `spieler-4`), damit
  Sicherungen beider Apps dieselben Spieler meinen.
- Die Logik der Bildschirme (zum Beispiel `src/ui/gameForm.ts`) ist eine Übertragung der ViewModels der
  Android-App als reine Funktionen und eigens getestet.
- Spielerfarben: geprüfte Palette für Diagramme (Farbfehlsichtigkeit, Kontrast). Die Farbe steht als Punkt neben
  dem Namen; Text bleibt in Textfarbe. Sie weicht deshalb von den Farben der Android-App ab.
- Laufzeit-Abhängigkeiten (frei): `preact` (MIT), `idb` (ISC) und, nur mit gemeinsamer Runde geladen,
  `@supabase/supabase-js` (MIT). Content-Security-Policy im Build: Anfragen gehen nur an die eigene Herkunft, an
  `https://photon.komoot.io` (Ortsvorschläge, in jedem Build) und mit gemeinsamer Runde an deren Server.
- Bild zum Teilen (Vertrag: `docs/ARCHITEKTUR.md`, Abschnitt 4.4): `src/ui/shareImage.ts` baut Inhalt und Anordnung
  als reine Funktionen (Texte wie auf dem Bildschirm, Achsen, Legende, Kürzen und Umbrechen langer Namen und Orte);
  `src/ui/shareImageCanvas.ts` zeichnet das in ein Canvas, in den Farben der CSS-Variablen der Seite. Zwischen dem
  Antippen und `navigator.share` wird auf nichts gewartet (`toDataURL` statt `toBlob`), denn Safari öffnet das
  Teilen-Menü nur unmittelbar nach dem Antippen. Linien, die genau gleich verlaufen (Partner im Rufspiel), liegen im
  Bild nebeneinander, damit jede Farbe zu sehen bleibt. Die Bildschirmfotos (`screenshots`) speichern auch die Bilder.
- Ortsvorschläge: `src/data/place/photon.ts` macht aus der Antwort von Photon die Vorschläge (gelesen wie in Kotlin
  mit dem Nachbau von kotlinx.serialization); `src/ui/placeSuggestions.ts` steuert Warten (400 ms), Verwerfen und
  Hinweis, `src/ui/LocationField.tsx` zeigt das Feld mit der Liste. Die Oberflächen-Tests nehmen `test` aus
  `e2e/support.ts`: Dort geht keine Anfrage an Photon hinaus (abgebrochen wie ohne Netz oder vom Test beantwortet).

## Gemeinsame Runde

Mehrere Handys (Android-App und Web-App gemischt) tragen in dieselben Abende ein und gleichen sich über einen
Supabase-Server ab. Der Vertrag steht in `docs/ARCHITEKTUR.md`, Abschnitt 8; wie der Server eingerichtet wird, in
`docs/EINRICHTUNG-RUNDE.md`.

- **Einschalten:** Beim Bauen müssen `VITE_SUPABASE_URL` (Adresse des Projekts) und `VITE_SUPABASE_PUBLISHABLE_KEY`
  (öffentlicher Schlüssel) gesetzt sein, zum Beispiel in einer Datei `web/.env.local` (wird nicht eingecheckt). Beides
  ist kein Geheimnis. Auf GitHub reicht der Workflow „Web-App“ die Repository-Variablen `SUPABASE_URL` und
  `SUPABASE_PUBLISHABLE_KEY` an den Build weiter. **Fehlt eines davon, gibt es keine gemeinsame Runde:** Die Zeile
  „Gemeinsame Runde“ zeigt dann nur „Der Server ist noch nicht eingerichtet.“, die Bibliothek für den Server ist
  nicht im Build, und die App spricht mit keinem Server.
- **Anmeldung:** nur mit Google, über Supabase (PKCE). Die Seite wird dafür verlassen und kehrt auf die
  Start-Adresse zurück (`…/SchafkopfTracker/?code=…`); die App löst den Code ein und nimmt ihn sofort aus der
  Adresse. Diese Start-Adresse muss beim Server als Rückkehr-Adresse eingetragen sein. Mit verbundener Runde spricht
  das Gerät nur als das Konto der Runde mit dem Server: Wer sich mit einem anderen Konto anmeldet, wird gleich wieder
  abgemeldet; die Runde bleibt verbunden, und nichts Wartendes geht verloren.
- **Lokal zuerst:** Das Gerät bleibt vollständig benutzbar. Abgeglichen wird beim Öffnen, eine halbe Sekunde nach
  jeder Änderung und alle vier Sekunden, solange die App zu sehen ist; nach Fehlern mit wachsendem Abstand. Was
  nicht hochgeladen werden konnte, wartet.
- **Wo was liegt:** `src/core/round` ist die Übertragung von `domain.round` (mit Golden-Tests für Zusammenführen,
  Dubletten und Einladungslinks). `src/round/controller.ts` und `scheduler.ts` entsprechen dem `RoundCoordinator`
  der Android-App, `screenModel.ts` dem `RoundViewModel`. Was sich das Gerät zur Runde merkt, steht in drei neuen
  Ablagen derselben IndexedDB (Version 2; vorhandene Daten bleiben beim Übergang erhalten). Die Sitzung liegt im
  `localStorage` des Browsers.
- **Speicher des Browsers:** Der Abgleich arbeitet nur mit dem, was wirklich in IndexedDB liegt: Er wartet vor jedem
  Lesen, bis alles gespeichert ist, und bricht ab, wenn das Speichern scheitert (Statuszeile „Speichern auf diesem
  Gerät gescheitert – bitte die App neu laden“). Was sich auf dem Gerät nicht lesen lässt, gilt nie als gelöscht:
  Ein unlesbarer Abend bleibt in der Runde, und ist die Spielerliste oder sind die Einstellungen unlesbar, ruht der
  Abgleich ganz (`src/round/storeAdapters.ts`).
- **Mehrere Fenster:** Es gleicht höchstens ein Fenster gleichzeitig ab (`navigator.locks`), und vorher liest es den
  Stand frisch aus IndexedDB; ein Zähler in der Datenbank verrät, ob ein anderes Fenster gespeichert hat. Jedes
  Speichern meldet sich bei den anderen Fenstern (`BroadcastChannel`), die dann neu lesen (`src/round/windows.ts`).
  Wer denselben Abend in zwei Fenstern gleichzeitig bearbeitet, kann trotzdem eine Eingabe verlieren.
- **Einladungen:** Die Einladungsseite zeigt den Code zum Kopieren; am iPhone im Browser steht dabei, dass er in der
  App vom Home-Bildschirm unter „Einladung einlösen“ einzufügen ist (sie hat einen eigenen Speicher). „Einladen“ bietet
  die zuletzt erzeugte Einladung wieder an, solange sie jünger als 13 Tage ist.
- **Tests:** Die Unit-Tests stellen Server und Anmeldung nach (`src/round/testing`); die Oberflächen-Tests bauen die
  App ein zweites Mal mit der Adresse eines Servers, den es nicht gibt, und beantworten dessen Anfragen selbst.
  Gegen ein echtes Supabase prüft `npm run test:live` (siehe unten).

## Entwickeln

Voraussetzung: Node.js 22.

```bash
cd web
npm ci
npm run dev    # http://127.0.0.1:5173/SchafkopfTracker/
```

| Befehl | Zweck |
|---|---|
| `npm run typecheck` | TypeScript prüfen |
| `npm run lint` | ESLint |
| `npm test` | Unit-Tests (Vitest), inkl. Golden-Tests gegen den Kotlin-Code |
| `npm run build` | Produktions-Build nach `dist/` |
| `npm run test:e2e` | Playwright-Tests gegen `vite preview` (iPhone-Größe und Desktop), ohne und mit gemeinsamer Runde |
| `SCREENSHOT_DIR=/pfad npx playwright test screenshots --project handy` | Bildschirmfotos zum Ansehen |
| `npm run test:live` | Probe der Anbindung gegen ein echtes Supabase (nur mit den Angaben unten) |

`npm run test:live` prüft die Anbindung (`src/round/supabase`) gegen den lokalen Stack der Supabase CLI: jede
Funktion des Servers, die Fehlercodes, 205 Abende seitenweise, Anmeldung und Abmeldung und einen Abgleich mit zwei
nachgestellten Geräten. Die Probe braucht `SKT_LIVE_URL` (zum Beispiel `http://127.0.0.1:54321`), `SKT_LIVE_KEY`
(öffentlicher Schlüssel) und `SKT_LIVE_ADMIN_KEY` (Verwaltungsschlüssel des **lokalen** Stacks, nur zum Anlegen der
Testkonten, die sich mit E-Mail und Passwort anmelden). Sie läuft im Workflow „Supabase“ auf GitHub. Ohne diese
Angaben scheitert `npm run test:live` mit einem Hinweis; `npm test` überspringt die Probe.

Ändert sich der Kotlin-Code, schlägt dessen Golden-Test fehl, bis die Erwartungen neu erzeugt sind:

```bash
tools/jvm-build.sh :domain:test --tests '*WebGoldenTest' -PgoldenWrite=true
```

```bash
tools/jvm-build.sh :data:test --tests '*WebFormatGoldenTest' -PgoldenWrite=true
```

Danach `web/src/core` bzw. `web/src/data/format` anpassen, bis `npm test` wieder grün ist.
