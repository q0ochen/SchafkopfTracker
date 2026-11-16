# Schafkopf Tracker in F-Droid

[F-Droid](https://f-droid.org) ist ein App-Katalog nur für **freie Software**. Für den Schafkopf Tracker ist das der
geplante Weg auf Android-Handys (siehe [ANFORDERUNGEN.md](ANFORDERUNGEN.md), Abschnitt 7).

Bis die App im offiziellen F-Droid steht, gibt es die Testversionen über eine **eigene F-Droid-Paketquelle**: Wer
die F-Droid-App nutzt, fügt sie einmal hinzu und installiert und aktualisiert den Schafkopf Tracker dann wie jede
andere App aus F-Droid. Vorbild ist das Schwesterprojekt Drink With Me, wo das seit dem 12.11.2026 so läuft.

> Stand: 15. November 2026. Die Regeln des offiziellen F-Droid stammen aus der Recherche für Drink With Me vom
> 10.11.2026 (dort `docs/F-DROID.md`); unsichere Punkte sind mit [unsicher] markiert. Vor einem Antrag bitte neu
> prüfen.

## 1. Kurz zusammengefasst

| Thema | Stand |
|---|---|
| Eigene F-Droid-Paketquelle | **online** seit 15.11.2026 (Secret `FDROID_REPO_KEYSTORE` gesetzt, Abschnitt 3) |
| Testversion zum direkten Herunterladen | **eingerichtet** (Release `testversion`, ohne Anmeldung) |
| Freie Lizenz (CC0 1.0), keine proprietären Bibliotheken in der Ausgabe `fdroid` | **erfüllt** |
| Anti-Features (Werbung, Tracking, nicht-freie Netzdienste) | **NonFreeNet** (in `fdroid/metadata/` eingetragen): Abend-Kommentator mit Google Gemini und Sicherung in Google Drive, beides freiwillig. Keine Werbung, kein Tracking |
| Store-Texte und Symbol (Fastlane) | **teilweise:** Titel, Kurz- und Langbeschreibung (`de-DE`, `en-US`) und Symbol unter `fastlane/metadata/android/`; Bildschirmfotos und Änderungshinweise fehlen noch |
| Eigener geheimer Release-Schlüssel, reproduzierbare Builds | **fehlt noch** (Abschnitt 5) |
| Offizielles F-Droid | Antrag frühestens **ab Mitte Februar 2027** (drei Monate sichtbare Entwicklung seit dem öffentlichen Repository am 15.11.2026) [unsicher, Abschnitt 6] |

## 2. Die eigene Paketquelle

| Was | Wie |
|---|---|
| Adresse | `https://raw.githubusercontent.com/q0ochen/SchafkopfTracker/fdroid-repo/fdroid/repo` |
| Fingerabdruck (SHA-256) | `6e2eca0b6f43f41f7c4d31c3a106e2fb44b71e3df72a34b48f070b0ae0d36590` |
| Link zum Hinzufügen | [`https://fdroid.link/#…?fingerprint=6E2ECA0B…`](https://fdroid.link/#https://raw.githubusercontent.com/q0ochen/SchafkopfTracker/fdroid-repo/fdroid/repo?fingerprint=6E2ECA0B6F43F41F7C4D31C3A106E2FB44B71E3DF72A34B48F070B0AE0D36590) (F-Droid 2.0 fängt nur `fdroid.link` und `fdroidrepos://` ab, keine normalen https-Links); QR-Code: [`fdroid-paketquelle-qr.svg`](fdroid-paketquelle-qr.svg) |
| Gebaut von | Job „Eigene F-Droid-Paketquelle aktualisieren“ in `.github/workflows/android.yml` mit `fdroid/build-repo.sh` und `fdroidserver` 2.4.5 (`fdroid update`), installiert mit festen Prüfsummen (`fdroid/requirements.txt`) |
| Wann | bei jedem Push auf `main`, `entwicklung` oder einen Arbeitszweig `claude/…`, der die App betrifft (und bei „Run workflow“) – erst nach allen Tests und dem Installationstest im Emulator, und nie von einem älteren Lauf |
| Inhalt | signierter Index (`entry.jar`, `index-v2.json`, für alte Clients `index-v1.jar`), die **aktuelle und die vorige** Testversion (`archive_older: 2`), Texte und Symbol aus `fastlane/metadata/android/`, Angaben aus `fdroid/metadata/io.github.q0ochen.schafkopf.yml` |
| Ort | Zweig `fdroid-repo` – bei jedem Build mit **einem einzigen** Commit ersetzt, damit alte APKs das Repository nicht wachsen lassen |
| Signatur des Index | eigener geheimer Schlüssel im Secret `FDROID_REPO_KEYSTORE` – **nicht** `app/debug.keystore`. Der Job prüft vor und nach dem Bauen, dass Schlüssel und Index zum Fingerabdruck passen und dass README, QR-Code und dieses Dokument denselben Fingerabdruck nennen; fehlt das Secret, wird der Job übersprungen |
| Signatur der APK | der öffentliche Test-Schlüssel `app/debug.keystore`: Die App aus F-Droid, aus dem Download-Link und aus Obtainium ist dieselbe und lässt sich ohne Datenverlust drüberinstallieren |
| Versionen | F-Droid bietet ein Update nur bei höherem `versionCode` an. Testversionen haben `versionCode` **1000 + Laufnummer** und `versionName` `0.1.0-test.<Laufnummer>` (`-PciBuildNumber` in `app/build.gradle.kts`); sie werden als Release gebaut (nicht „debuggable“) |

Grenzen und Hinweise:

- Das ist **nicht** das offizielle F-Droid: F-Droid baut hier nichts selbst und prüft nichts. Die Paketquelle
  verteilt genau die APK, die GitHub Actions gebaut und getestet hat.
- Warum nicht GitHub Pages? Pages ist durch die Web-App fürs iPhone belegt (ein Repository hat nur eine Pages-Seite,
  jedes Veröffentlichen ersetzt sie). `raw.githubusercontent.com` ist der übliche Weg für solche Paketquellen.
- `raw.githubusercontent.com` hält Dateien bis zu 5 Minuten im Zwischenspeicher. Direkt nach einer neuen
  Testversion können `entry.jar` und `index-v2.json` kurz aus verschiedenen Builds stammen; F-Droid meldet dann
  „Ungültiges Repository“. Abhilfe: einige Minuten warten.
- F-Droid 2.0 aktualisiert die Paketquellen standardmäßig nur alle paar Stunden (und nur im WLAN). Deshalb bleibt
  die vorige APK erhalten; ist eine Liste noch älter, hilft „Auf Aktualisierungen prüfen“.
- Den Schlüssel der Paketquelle **nie verlieren oder austauschen**: Ein neuer Schlüssel heißt, dass alle die
  Paketquelle neu hinzufügen müssen.

## 3. Einrichten (einmalig, geht am Handy)

Damit die Paketquelle entsteht, braucht GitHub einmal den geheimen Schlüssel, mit dem sie unterschrieben wird.
Claude hat ihn am 15.11.2026 erzeugt und dem Projektinhaber als Datei `FDROID_REPO_KEYSTORE.txt` gegeben (eine
einzige lange Zeile Text). Er liegt nirgends im Repository.

1. Auf GitHub im Repository: **Settings → Secrets and variables → Actions** → Reiter **Secrets** →
   **New repository secret**.
2. **Name:** `FDROID_REPO_KEYSTORE`
3. **Secret:** den ganzen Inhalt der Datei einfügen (eine lange Zeile; Leerzeichen und Zeilenumbrüche, die beim
   Kopieren am Handy entstehen, stören nicht).
4. **Add secret**. Danach die Paketquelle einmal bauen lassen:
   - Liegt der Workflow schon in `main`: **Actions → Android → Run workflow** → Branch wählen (den Zweig mit dem
     neuesten Stand) → **Run workflow**.
   - Solange er nur auf einem Arbeitszweig liegt, zeigt GitHub den Knopf „Run workflow“ **nicht** an (er erscheint
     erst, wenn der Workflow im Hauptzweig `main` liegt). Dann Claude bitten, den Lauf zu starten, oder den neuesten
     Lauf von „Android“ öffnen und **Re-run all jobs** antippen (am Handy hinter „…“).

   Nach etwa 15 Minuten steht die Paketquelle bereit. Am 15.11.2026 hat Claude den ersten Lauf mit Schlüssel
   gestartet (Lauf 4 auf dem Zweig `claude/gracious-keller-48tcn0`).

Danach baut jede neue Testversion die Paketquelle automatisch neu. Fehlt das Secret, überspringt der Workflow nur
diesen Teil; Tests, Installationstest und die Testversion zum Herunterladen laufen trotzdem.

> **Wichtig:** Die Datei zusätzlich im Passwort-Manager sichern und danach aus Downloads und Chat löschen. Geht sie
> verloren, muss ein neuer Schlüssel her, und alle Tester müssen die Paketquelle neu hinzufügen.
>
> **Selbst einen neuen Schlüssel erzeugen** (nur wenn nötig, am Computer mit Java): `keytool -genkeypair -keystore
> repo.p12 -storetype PKCS12 -storepass schafkopf-fdroid -keypass schafkopf-fdroid -alias schafkopf-fdroid -keyalg
> RSA -keysize 4096 -validity 10000 -dname "CN=Schafkopf Tracker F-Droid Repo"`, dann die Datei als Base64-Text ins
> Secret (Linux: `base64 -w0 repo.p12`, Windows/PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("repo.p12"))`).
> Das Passwort ist absichtlich nicht geheim – geheim ist die Datei. Den neuen Fingerabdruck (`keytool -list -v
> -keystore repo.p12 -storepass schafkopf-fdroid`, Zeile „SHA256“, ohne Doppelpunkte) dann **überall** übernehmen:
> `FDROID_REPO_FINGERPRINT` in `.github/workflows/android.yml` (klein geschrieben), Link und Zeile „Von Hand“ in der
> README, Tabelle oben und den QR-Code `docs/fdroid-paketquelle-qr.svg` neu erzeugen (Claude kann das erledigen).

## 4. Für Testerinnen und Tester

Die Anleitung mit Link und QR-Code steht in der [README](../README.md#android-testversion).

## 5. Bis zur ersten echten Veröffentlichung

- **Geheimer Release-Schlüssel:** `app/debug.keystore` ist öffentlich und nur für Testversionen. Für
  Veröffentlichungen braucht es einen eigenen, geheimen Schlüssel (doppelt sichern; geht er verloren, kann niemand
  mehr Updates veröffentlichen).
- **Reproduzierbare Builds** [unsicher, muss ausprobiert werden]: F-Droid baut die App selbst und verteilt sie mit
  unserer Signatur, wenn das Ergebnis Byte für Byte gleich ist. Sonst signiert F-Droid mit einem eigenen Schlüssel.
- **Wechsel für Tester:** Wer die Testversion (öffentlicher Schlüssel) hat, muss für die erste echte Version **einmal
  deinstallieren**. Damit dabei nichts verloren geht, braucht die Android-App vorher „Sicherung speichern/einlesen“
  (in der Web-App gibt es das schon; das Dateiformat ist dasselbe).
- **Versionsnummern:** Testversionen `1000 + Laufnummer`; Veröffentlichungen **Major × 1.000.000 + Minor × 1.000 +
  Patch** (die erste also 1.0.0 = 1000000), fest eingecheckt, damit sie immer über den Testversionen liegen. Git-Tag
  `v` + `versionName`. Der feste Tag `testversion` passt nicht zum Muster `v…`, deshalb hält F-Droid ihn nicht für
  eine Veröffentlichung.
- **Bildschirmfotos und Änderungshinweise** unter `fastlane/metadata/android/`.

## 6. Offizielles F-Droid

- **Aufnahmeregeln** [unsicher]: freie Lizenz, keine proprietären Abhängigkeiten, öffentlicher Quellcode mit
  Fehler-Tracker, und seit Oktober 2026 **mindestens drei Monate sichtbare Entwicklung** seit dem öffentlichen
  Repository. Für den Schafkopf Tracker (öffentlich seit 15.11.2026) also frühestens Mitte Februar 2027.
- **KI-Regeln:** Das offizielle F-Droid hatte am 10.11.2026 keine Regel zu mit KI geschriebenem Code; diskutiert wird
  eine Kennzeichnung [unsicher]. Der Schafkopf Tracker ist zum größten Teil mit Claude Code geschrieben; im Antrag
  offen angeben.
- **IzzyOnDroid** kommt wegen seiner Richtlinie gegen KI-erstellte Apps praktisch nicht in Frage (wie bei Drink With
  Me).
- **Eingereicht** wird per Merge Request bei `fdroiddata` auf GitLab (kostenloses Konto nötig); die Warteschlange war
  im November 2026 lang (Monate) [unsicher].
