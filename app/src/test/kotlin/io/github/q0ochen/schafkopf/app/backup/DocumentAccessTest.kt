package io.github.q0ochen.schafkopf.app.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/** Lesen und Schreiben über den ContentResolver, hier mit einer gewöhnlichen Datei statt eines Cloud-Anbieters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DocumentAccessTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val documents = ContentResolverDocuments(ApplicationProvider.getApplicationContext())

    @Test
    fun `schreibt, ersetzt einen laengeren alten Inhalt vollstaendig und liest wieder`() = runBlocking {
        val file = File(folder.root, "sicherung.json")
        val uri = Uri.fromFile(file).toString()
        documents.write(uri, "{\"lang\": \"" + "x".repeat(500) + "\"}")
        documents.write(uri, "{\"kurz\": \"ä\"}")
        assertEquals("{\"kurz\": \"ä\"}", file.readText())
        assertEquals("{\"kurz\": \"ä\"}", documents.read(uri))
    }

    @Test
    fun `zu grosse Dateien sind keine Sicherung`() {
        assertThrows(DocumentAccess.TooLargeException::class.java) {
            readLimited(ByteArrayInputStream(ByteArray(200_000)), maxBytes = 100_000)
        }
        assertEquals(100_000, readLimited(ByteArrayInputStream(ByteArray(100_000)), maxBytes = 100_000).size)
    }

    @Test
    fun `ohne Kuerzen fuellen Leerzeichen den Rest der alten Datei auf`() {
        val bytes = "{}".toByteArray()
        assertArrayEquals("{}   ".toByteArray(), padWithSpaces(bytes, oldSize = 5))
        assertArrayEquals(bytes, padWithSpaces(bytes, oldSize = 1))
        assertArrayEquals(bytes, padWithSpaces(bytes, oldSize = 0))
    }

    @Test
    fun `der Name einer gewoehnlichen Datei ist nicht abfragbar, das ist kein Fehler`() {
        assertNull(documents.displayName(Uri.fromFile(File(folder.root, "x.json")).toString()))
    }
}
