package io.github.q0ochen.schafkopf.app.backup

import java.io.IOException

/** Dateien im Arbeitsspeicher statt der Dateiauswahl von Android; Fehler lassen sich gezielt auslösen. */
class FakeDocuments : DocumentAccess {
    val files = HashMap<String, String>()
    val kept = LinkedHashSet<String>()
    var failWrites: Exception? = null
    var failReads: Exception? = null
    var refuseAccess = false

    override suspend fun read(uri: String, maxBytes: Long): String {
        failReads?.let { throw it }
        val text = files[uri] ?: throw IOException("gibt es nicht: $uri")
        if (text.length > maxBytes) throw DocumentAccess.TooLargeException()
        return text
    }

    override suspend fun write(uri: String, text: String) {
        failWrites?.let { throw it }
        files[uri] = text
    }

    override fun displayName(uri: String): String = uri.substringAfterLast('/')

    override fun keepAccess(uri: String) {
        if (refuseAccess) throw SecurityException("kein dauerhaftes Recht")
        kept += uri
    }

    override fun releaseAccess(uri: String) {
        kept -= uri
    }
}
