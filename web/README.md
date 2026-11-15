# Schafkopf Tracker – Web-App (Zwischenlösung fürs iPhone)

Die Web-App bringt den Schafkopf Tracker aufs **iPhone** (Safari, „Teilen“ → „Zum Home-Bildschirm“) und in jeden
Browser. Sie rechnet mit **demselben Rechenkern** wie die Android-App (nach TypeScript übertragen und mit
Golden-Tests gegen den Kotlin-Code abgesichert) und schreibt **dieselben Sicherungsdateien**: Eine Sicherung vom
iPhone lässt sich in der Android-App einlesen und umgekehrt.

Adresse: **https://q0ochen.github.io/SchafkopfTracker/** (GitHub Pages, kostenlos; veröffentlicht wird nur von `main`).

- **Start**: laufender Abend, „Neuer Abend“, Statistik, Spieler, Einstellungen, alle Abende mit Rangliste.
- **Neuer Abend**: vier Spieler wählen (Vorschlag: wie letztes Mal), Sitzordnung, erster Geber, Ort.
- **Abend**: Stand je Spieler mit Platz und Geber, alle Einträge mit Beträgen, große Knöpfe „Spiel eintragen“,
  „Zusammengeworfen“, „6 Nixer – Bock“ und „Rückgängig“ (mit „Wiederherstellen“). Menü: Statistik, Abschluss,
  Regeln dieses Abends, beenden/wieder öffnen, löschen. Der Bildschirm bleibt an, solange der Abend läuft.
- **Spiel eintragen/ändern**: Spielart, wer spielt (Über Kreuz: ein Tipp wählt das Paar), gelegt, Kontra/Retour,
  Laufende, Tout/Sie, Ausgang, Bock, Farbe, Spielwert mit Rechenweg; „Gewonnen“/„Verloren“ speichert sofort.
- **Abschluss**: Endstand, „Wer zahlt wem“, als Text teilen.
- **Statistik** (ein Abend oder alle): Kontostand, Vermögensverlauf (Diagramm mit Fadenkreuz, Tabelle),
  Spielarten, „Sollte er es lassen?“ (Legen), Bock-König, Solo-Spezialist, Mauerblümchen, Lieblingspartner,
  weitere Zahlen und Rekorde.
- **Einstellungen**: dunkel/hell/wie System, Bildschirm anlassen, Regeln für neue Abende, **Sicherung speichern
  und einlesen**.

Alles bleibt auf dem Gerät (IndexedDB); es gibt keinen Server, keine Werbung, keine Analyse. Die App startet nach
dem ersten Besuch auch ohne Netz (Service Worker). Die KI-Zusammenfassung der Android-App gibt es hier nicht.

## Aufbau

```
web/
├── src/core/        Rechenkern aus dem Kotlin-Modul domain (Regeln, Abrechnung, Abend, Statistik)
│   └── golden/      vom Kotlin-Code erzeugte Erwartungen (kotlin-golden.json) und ihr Test
├── src/data/        Speicher im Browser (repository.ts, IndexedDB über idb)
│   ├── format/      Dateiformat und Sicherung aus dem Kotlin-Modul data (gleiche Texte, gleiche Prüfungen)
│   └── golden/      vom Kotlin-Code erzeugte Erwartungen (kotlin-format-golden.json) und ihr Test
├── src/app/         Zustand (store.ts), Router (Anker-Adressen), Sicherung, Anbindung des Dateiformats
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
- Laufzeit-Abhängigkeiten (frei): `preact` (MIT), `idb` (ISC). Content-Security-Policy im Build.

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
| `npm run test:e2e` | Playwright-Tests gegen `vite preview` (iPhone-Größe und Desktop) |
| `SCREENSHOT_DIR=/pfad npx playwright test screenshots --project handy` | Bildschirmfotos zum Ansehen |

Ändert sich der Kotlin-Code, schlägt dessen Golden-Test fehl, bis die Erwartungen neu erzeugt sind:

```bash
tools/jvm-build.sh :domain:test --tests '*WebGoldenTest' -PgoldenWrite=true
```

```bash
tools/jvm-build.sh :data:test --tests '*WebFormatGoldenTest' -PgoldenWrite=true
```

Danach `web/src/core` bzw. `web/src/data/format` anpassen, bis `npm test` wieder grün ist.
