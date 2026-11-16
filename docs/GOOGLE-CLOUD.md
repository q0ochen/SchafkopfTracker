# Google-Cloud-Projekt für die Sicherung in Google Drive

Stand: 16.11.2026. Einmalig vom Projektinhaber einzurichten, kostenlos, etwa 10 Minuten. Die Menünamen der
Google-Cloud-Konsole ändern sich gelegentlich; gemeint ist jeweils der Punkt mit dieser Bedeutung.

Die App meldet sich damit im Browser bei Google an (OAuth 2.0 mit PKCE, Bibliothek AppAuth) und legt im Drive des
Nutzers den Ordner `SchafkopfTracker_Backups` mit der Datei `schafkopf-sicherung.json` an. Sie verlangt nur die
Freigabe `drive.file`: Sie sieht ausschließlich die Dateien, die sie selbst angelegt hat. Google stuft diese Freigabe als
„nicht sensibel“ ein; eine Prüfung der App durch Google ist dafür nicht nötig.

## Schritte

1. [console.cloud.google.com](https://console.cloud.google.com) mit dem eigenen Google-Konto öffnen. Oben „Projekt
   auswählen“ → „Neues Projekt“, Name „Schafkopf Tracker“ → „Erstellen“. Eine Zahlungsmethode ist nicht nötig.
2. Im Suchfeld oben „Google Drive API“ eingeben → „Aktivieren“.
3. Menü „Google Auth Platform“ (früher „OAuth-Zustimmungsbildschirm“) → „Jetzt starten“: App-Name
   „Schafkopf Tracker“, eigene E-Mail als Support- und Kontaktadresse, Zielgruppe „Extern“.
4. „Datenzugriff“ → „Bereiche hinzufügen“: nur `https://www.googleapis.com/auth/drive.file` („Dateien, die mit dieser
   App erstellt wurden“) → speichern. `openid` und `email` braucht man nicht eigens hinzuzufügen.
5. „Zielgruppe“ → „App veröffentlichen“ (Status „In Produktion“). Im Status „Test“ verfällt die Anmeldung nach
   7 Tagen, und nur eingetragene Testnutzer können sich anmelden.
6. „Clients“ → „Client erstellen“: Typ **Android**, Paketname `io.github.q0ochen.schafkopf`,
   SHA-1-Fingerabdruck des öffentlichen Test-Schlüssels (`app/debug.keystore`):
   `A9:2A:3F:4C:38:81:56:1C:1A:A8:45:14:70:0D:DB:2E:D0:5A:2F:5C`.
7. Den neuen Client öffnen → „Erweiterte Einstellungen“ → „Benutzerdefiniertes URI-Schema aktivieren“ → speichern.
   Ohne diese Einstellung lehnt Google die Anmeldung ab („Custom URI scheme is not enabled for your Android client“).
   Es kann einige Minuten bis wenige Stunden dauern, bis sie wirkt.
8. Die **Client-ID** (endet auf `.apps.googleusercontent.com`) in `app/src/main/res/values/google.xml` eintragen
   (`google_client_id`). Sie ist kein Geheimnis und darf im öffentlichen Repository stehen.

## Technische Eckdaten

| Was | Wert |
|---|---|
| Anmeldung | `https://accounts.google.com/o/oauth2/v2/auth`, Token: `https://oauth2.googleapis.com/token` |
| Rücksprung in die App | `io.github.q0ochen.schafkopf:/oauth2redirect` (eigenes URI-Schema = Paketname) |
| Freigaben | `openid`, `email` (Anzeige des Kontos), `https://www.googleapis.com/auth/drive.file` |
| Schnittstelle | Drive REST v3 über OkHttp (`data/drive/GoogleDriveClient.kt`), ohne Google-Bibliothek |
| Gespeichert auf dem Gerät | Anmeldestand (mit Token zum Erneuern) verschlüsselt (Android Keystore), von der Datensicherung von Android ausgenommen |

Wird die App später mit einem anderen Schlüssel signiert (zum Beispiel vom offiziellen F-Droid), braucht es einen
weiteren Android-Client mit dessen SHA-1-Fingerabdruck.
