package io.github.q0ochen.schafkopf.data.drive

/**
 * Die Sicherung in Google Drive: eine Datei [FILE_NAME] im Ordner [FOLDER_NAME] im Drive des Nutzers. Jede Sicherung
 * ersetzt ihren Inhalt; Drive hebt die früheren Fassungen eine Zeit lang als Versionen auf.
 */
class DriveBackup(private val drive: GoogleDriveClient) {

    /** Schreibt die Sicherung; legt Ordner und Datei beim ersten Mal an und eine verschwundene Datei neu. */
    suspend fun save(json: String): DriveResult<DriveFile> {
        val folder = when (val result = folder(create = true)) {
            is DriveResult.Failure -> return result
            is DriveResult.Success -> result.value ?: return DriveResult.Failure(DriveError.UNKNOWN, "kein Ordner")
        }
        val existing = when (val result = drive.findFiles(folder.id, FILE_NAME)) {
            is DriveResult.Failure -> return result
            is DriveResult.Success -> result.value.firstOrNull()
        }
        if (existing != null) {
            val updated = drive.updateFile(existing.id, json)
            // Zwischen Suchen und Schreiben gelöscht: dann eben neu anlegen.
            if (!(updated is DriveResult.Failure && updated.error == DriveError.NOT_FOUND)) return updated
        }
        return drive.createFile(folder.id, FILE_NAME, json)
    }

    /** Die neueste Sicherung dieser App in Drive; `null`, wenn es noch keine gibt. */
    suspend fun latest(): DriveResult<DriveFile?> {
        val folder = when (val result = folder(create = false)) {
            is DriveResult.Failure -> return result
            is DriveResult.Success -> result.value ?: return DriveResult.Success(null)
        }
        return when (val files = drive.findFiles(folder.id, FILE_NAME)) {
            is DriveResult.Failure -> files
            is DriveResult.Success -> DriveResult.Success(files.value.firstOrNull())
        }
    }

    /** Lädt eine Sicherung herunter. */
    suspend fun read(file: DriveFile): DriveResult<String> = drive.download(file.id, MAX_BACKUP_BYTES)

    private suspend fun folder(create: Boolean): DriveResult<DriveFile?> {
        when (val found = drive.findFolder(FOLDER_NAME)) {
            is DriveResult.Failure -> return found
            is DriveResult.Success -> if (found.value != null || !create) return found
        }
        return drive.createFolder(FOLDER_NAME)
    }

    companion object {
        /** Der Ordner, wie ihn der Hauptentwickler genannt hat (Issue #15). */
        const val FOLDER_NAME = "SchafkopfTracker_Backups"
        const val FILE_NAME = "schafkopf-sicherung.json"

        /** Größere Dateien sind sicher keine Sicherung dieser App (wie beim Einlesen einer Datei). */
        const val MAX_BACKUP_BYTES: Long = 50L * 1024 * 1024
    }
}
