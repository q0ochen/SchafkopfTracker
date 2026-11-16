# Anforderungen (Stand: 15.11.2026)

Quellen: die Vorgabe des Hauptentwicklers (Keep-Notiz, übernommen am 15.11.2026) und die Angaben des
Projektinhabers vom 14. und 15.11.2026. Die technische Umsetzung steht in [ARCHITEKTUR.md](ARCHITEKTUR.md).
Die einzelnen Aufgaben stehen als Issues mit dem Label `Hauptentwickler` im Repository.

## 1. Wozu die App da ist

Eine feste Runde aus vier Spielern spielt regelmäßig Schafkopf im Wirtshaus (langes Blatt). Einer trägt nach jedem
Spiel ein, was gespielt wurde. Die App rechnet auf den Cent genau ab, zeigt Stand und Verlauf des Abends, führt
Statistik über alle Abende, sichert die Daten und lässt sich auch von der Uhr aus bedienen.

## 2. Spieler und Abende

- Genau vier Spieler in einer festen Runde. Die App belegt vier Spieler vor; ihre Namen trägt die Runde selbst ein.
- Spieler lassen sich umbenennen, archivieren (erscheinen nicht mehr in der Auswahl, alte Abende bleiben) und
  löschen (#21). Löschen geht nur bei Spielern, die in keinem Abend vorkommen; sonst verlöre ein Abend seine Namen.
  Ein neuer Spieler bekommt die erste freie Farbe.
- Ein Abend hat ein Datum, ein freies Textfeld für den Spielort (keine Karten-Anbindung) und eine Liste von Spielen.
- Zu Beginn des Abends wird die Sitzreihenfolge festgelegt (für die Geberfolge und die Statistik).
- Ein Abend hat beliebig viele Spiele. Jedes Spiel lässt sich jederzeit ändern oder löschen; alle Stände und
  Statistiken passen sich sofort an.

## 3. Spielarten und Abrechnung

| Spielart | Grundwert | Parteien |
|---|---|---|
| Rufspiel | 10 Cent | zwei gegen zwei |
| Über Kreuz | 10 Cent | zwei gegen zwei, wertet genau wie ein Rufspiel |
| Farbsolo | 50 Cent | einer gegen drei |
| Wenz | 50 Cent | einer gegen drei |
| Geier | 50 Cent | einer gegen drei |

Zu jedem Spiel gehören: Spielart, Laufende (0 bis 8, je 10 Cent), Schneider (10 Cent), Schwarz (weitere 10 Cent;
Schwarz schließt Schneider ein), Bock (verdoppelt), wer gelegt hat (0 bis 4 Spieler, jeder verdoppelt) und die Gewinner.

```
Spielwert T = (Grundwert + Laufende × 10 Cent + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt)
```

- Rufspiel und Über Kreuz: Jeder der zwei Verlierer zahlt T, jeder der zwei Gewinner bekommt T.
- Farbsolo, Wenz, Geier: Der Solist bekommt von jedem der drei Gegner T (zusammen 3 T) oder zahlt jedem T.
- Wahlweise lässt sich in den Einstellungen eine Topf-Teilung wählen (die drei Gegner teilen sich T);
  Standard ist die Schafkopf-Regel.
- Gerechnet wird in ganzen Cent. Jedes Spiel ist ein Nullsummenspiel.

Zusätze aus den Angaben des Projektinhabers: Kontra und Retour (im Regelwerk abschaltbar) sowie Tout und Sie
wie bei sauspiel.de (ein eigener Schalter dafür fehlt noch); „6 Nixer“ lösen eine Bockrunde aus (abschaltbar),
deren Zähler den Bock-Schalter vorbelegt; „zusammengeworfen“ als eigener Eintrag ohne Zahlung.

## 4. Bedienung und Gestaltung

- Dunkles, kontrastreiches Design als Standard (für OLED-Bildschirme und schummrige Wirtshäuser).
- Barrierefrei: Schriftgröße des Systems, Screenreader, Beschreibungen für alle Bedienelemente, hoher Kontrast.
- Tippflächen mindestens 48 × 48 dp, die Hauptaktionen beim Eintragen 56 dp und mehr.
- Eigenes Symbol einer Streichholzschachtel für „gelegt“.
- Alle Grafiken sind frei: selbst gezeichnete Vektorgrafiken oder freie Quellen.

## 5. Statistik und Teilen

Auswertung wahlweise für einen einzelnen Abend oder über alle Abende („ewige Tabelle“):

1. Finanz-Übersicht: Kontostand je Spieler als Tabelle und Vermögensverlauf über die Spiele als Liniendiagramm.
2. Spielart-Analyse: Gewinnrate und durchschnittlicher Gewinn oder Verlust je Spieler und Spielart.
3. „Sollte-er-es-lassen?“-Index: wie oft jeder gelegt hat, Gewinnquote und Netto-Bilanz der gelegten Spiele,
   dazu ein humorvolles Abzeichen (zum Beispiel „Brandstifter“ oder „Sichere Bank“).
4. Weitere Kennzahlen: Bock-König (wer in Bockspielen am meisten gewinnt), Solo-Spezialist und Mauerblümchen
   (wer die meisten Einzelspiele spielt, wer fast nur ruft), Lieblingspartner (erfolgreichstes Zweiergespann).
5. Diagramme sind interaktiv und mit freier Software gezeichnet.
6. Teilen: Jede Statistik und jede Abend-Abrechnung lässt sich als formatierter Text und als Bild über das
   Teilen-Menü von Android weitergeben (Signal, Telegram und andere).

## 6. Sicherung und Zusätze

- **Google Drive:** Nach jedem Eintragen, Ändern oder Löschen wird im Hintergrund eine Sicherung auf Google Drive
  aktualisiert. Ohne Empfang wird sie nachgeholt. In den Einstellungen lässt sich eine Sicherung zurückspielen.
- **Abend-Kommentator:** Auf Wunsch schreibt ein Sprachmodell (Gemini, eigener Schlüssel) aus den Zahlen des Abends
  eine witzige, bairisch gefärbte und leicht stichelnde Zusammenfassung; auch sie lässt sich teilen.
  Ohne Schlüssel entsteht die Zusammenfassung aus Textbausteinen.
- **Uhr (Wear OS):** eine Kachel mit dem Zwischenstand des laufenden Abends und der Schaltfläche „Neues Spiel“ sowie
  eine Schnelleingabe in Schritten für das runde Display; die Eingabe wird mit dem Handy abgeglichen.

## 7. Technik und Vertrieb

- Android, vollständig in Kotlin, Oberfläche mit Jetpack Compose (Material 3). Zielgeräte: Pixel 11 Pro, Pixel Watch 4.
- Clean Architecture (Presentation, Domain, Data) mit MVVM, Koin, Room mit `Flow`.
- Tests: über 90 % Abdeckung im Domain Layer (JUnit 5, MockK, Turbine), dazu Tests für Datenbank, Repositories
  und Sicherung.
- Veröffentlichung auf F-Droid: keine proprietären Bibliotheken in der Ausgabe `fdroid`, keine Tracker, kein
  Firebase. Was nur mit Google Play Services geht (Abgleich mit der Uhr), steckt in der Ausgabe `full`.
- Es wird kein Geld ausgegeben.

## Entschieden

- Die App wird vom Projektinhaber zusammen mit Claude entwickelt (14.11.2026).
- Immer genau vier Spieler; niemand kommt später oder geht früher (14.11.2026).
- Verdoppeln wie bei sauspiel.de; Bockrunde bei 6 Nixern (14.11.2026).
- Grundwerte nach der Vorgabe des Hauptentwicklers: Rufspiel und Über Kreuz 10 Cent, Einzelspiele 50 Cent (15.11.2026).
- Gespeichert werden Ansager, Mitspieler und Ausgang; die Gewinner werden daraus abgeleitet, weil sich nur so
  „wer ruft fast nur“ und „Lieblingspartner“ berechnen lassen (15.11.2026).
- Die Namen der Spieler stehen nicht im Quelltext; die Runde trägt sie in der App ein (15.11.2026).
- Spieler sollen sich löschen lassen (Anmerkung des Hauptentwicklers, 16.11.2026, #21). Umgesetzt mit Schutz:
  Wer in einem Abend vorkommt, lässt sich nur archivieren, bis die Abende gelöscht sind.
- Öffentliches Repository, Lizenz CC0 1.0 (15.11.2026).
- Anmerkungen des Hauptentwicklers werden zu Issues; umgesetzt wird nach Abstimmung mit dem Projektinhaber
  (15.11.2026, siehe README).
- Sprachmodell und Google Drive werden über offene Schnittstellen (REST, OAuth 2) angebunden, nicht über
  proprietäre Bibliotheken (15.11.2026).

## Offene Entscheidungen

- Endgültiger Name der App: „Schafkopf Tracker“ heißt seit Juli 2026 bereits eine andere App in den Stores.
- Kennung der App (`applicationId`), bevor die erste Testversion weitergegeben wird.
- Kontra, Retour, Tout und Sie stehen nicht in der Vorgabe des Hauptentwicklers: eingebaut lassen oder entfernen?
- Bockrunde im Einzelnen: Dauer (angenommen vier Spiele), erneutes Auslösen (angenommen: wird angehängt),
  ob das Blatt mit den 6 Nixern zusammengeworfen wird (angenommen: ja).
- Laufende: Die Eingabe bietet Zahlen erst ab der Schwelle an (Rufspiel, Über Kreuz, Farbsolo ab 3; Wenz, Geier ab 2).
- Google Drive braucht ein kostenloses Google-Cloud-Projekt des Projektinhabers.
