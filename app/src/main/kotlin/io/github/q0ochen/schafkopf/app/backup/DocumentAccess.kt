package io.github.q0ochen.schafkopf.app.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Lesen und Schreiben von Dateien, die der Nutzer in der Dateiauswahl von Android gewählt hat (Storage Access
 * Framework): auf dem Gerät, auf einer Speicherkarte oder bei einem Cloud-Dienst wie Google Drive, wenn dessen App
 * installiert ist. Die App braucht dafür weder eine Berechtigung für den ganzen Speicher noch Internet; das Hochladen
 * übernimmt die App des Cloud-Dienstes, auch später, wenn gerade kein Netz da ist.
 *
 * Als Schnittstelle, damit sich die Abläufe ohne Gerät prüfen lassen.
 */
interface DocumentAccess {
    /** Liest die ganze Datei als UTF-8-Text. @throws TooLargeException über [maxBytes]. */
    suspend fun read(uri: String, maxBytes: Long = MAX_BACKUP_BYTES): String

    /** Ersetzt den Inhalt der Datei durch [text] (UTF-8). */
    suspend fun write(uri: String, text: String)

    /** Der Name der Datei, wie der Anbieter ihn anzeigt; `null`, wenn er ihn nicht verrät. */
    fun displayName(uri: String): String?

    /** Merkt sich das Recht, die Datei auch nach einem Neustart zu lesen und zu schreiben. */
    fun keepAccess(uri: String)

    /** Gibt dieses Recht wieder ab. */
    fun releaseAccess(uri: String)

    class TooLargeException : IOException("Datei zu groß")

    companion object {
        /** Größere Dateien sind sicher keine Sicherung dieser App (wie in der Web-App). */
        const val MAX_BACKUP_BYTES: Long = 50L * 1024 * 1024
    }
}

/** [DocumentAccess] über den `ContentResolver` von Android. */
class ContentResolverDocuments(context: Context) : DocumentAccess {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String, maxBytes: Long): String = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)
        stream.use { readLimited(it, maxBytes) }.toString(Charsets.UTF_8)
    }

    override suspend fun write(uri: String, text: String) = withContext(Dispatchers.IO) {
        val target = Uri.parse(uri)
        val bytes = text.toByteArray(Charsets.UTF_8)
        // „wt“ ersetzt den Inhalt. Manche Anbieter kennen nur „w“, und das kürzt eine längere alte Datei nicht immer:
        // Dann füllen Leerzeichen den Rest auf. Für JSON ist das unschädlich.
        val truncating = try {
            resolver.openOutputStream(target, "wt")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: FileNotFoundException) {
            null
        }
        if (truncating != null) {
            truncating.use { it.write(bytes) }
        } else {
            val padded = padWithSpaces(bytes, sizeOf(target) ?: 0L)
            val stream = resolver.openOutputStream(target, "w") ?: throw IOException("Datei lässt sich nicht öffnen")
            stream.use { it.write(padded) }
        }
    }

    override fun displayName(uri: String): String? = query(Uri.parse(uri), OpenableColumns.DISPLAY_NAME)

    override fun keepAccess(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), READ_WRITE)
    }

    override fun releaseAccess(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), READ_WRITE)
        } catch (_: SecurityException) {
            // Das Recht war schon weg (zum Beispiel weil die Datei gelöscht wurde); nichts zu tun.
        }
    }

    private fun sizeOf(uri: Uri): Long? = query(uri, OpenableColumns.SIZE)?.toLongOrNull()

    private fun query(uri: Uri, column: String): String? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        // Ein Anbieter, der die Frage nicht beantwortet, ist kein Grund zum Abbruch.
        null
    }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** Liest höchstens [maxBytes] Bytes; ist der Datenstrom länger, ist es keine Sicherung dieser App. */
internal fun readLimited(stream: InputStream, maxBytes: Long): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw DocumentAccess.TooLargeException()
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

/** Füllt [bytes] mit Leerzeichen auf mindestens [oldSize] Bytes auf, damit kein Rest einer längeren Datei bleibt. */
internal fun padWithSpaces(bytes: ByteArray, oldSize: Long): ByteArray {
    if (oldSize <= bytes.size || oldSize > Int.MAX_VALUE) return bytes
    return bytes + ByteArray((oldSize - bytes.size).toInt()) { ' '.code.toByte() }
}
