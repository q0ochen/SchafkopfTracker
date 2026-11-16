package io.github.q0ochen.schafkopf.data.drive

import io.github.q0ochen.schafkopf.data.llm.TestHttp
import io.github.q0ochen.schafkopf.data.llm.baseUrl
import io.github.q0ochen.schafkopf.data.llm.enqueueJson
import io.github.q0ochen.schafkopf.data.llm.startLocal
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** Die Drive-Anbindung gegen einen Testserver auf dem eigenen Rechner; nichts geht zu Google. */
class GoogleDriveClientTest {

    private val server = MockWebServer()
    private var token: String? = "TESTTOKEN-123"

    @BeforeEach
    fun startServer() = server.startLocal()

    @AfterEach
    fun stopServer() = server.close()

    private fun client() = GoogleDriveClient(TestHttp.client, { token }, server.baseUrl + "/")
    private fun backup() = DriveBackup(client())

    private fun files(vararg entries: String) = """{"files":[${entries.joinToString(",")}]}"""
    private fun file(id: String, name: String, modified: String = "2026-11-16T20:00:00.000Z") =
        """{"id":"$id","name":"$name","modifiedTime":"$modified","size":"12"}"""

    @Test
    fun `die erste Sicherung legt Ordner und Datei an, mit Token nur in der Kopfzeile`() = runTest {
        server.enqueueJson(200, files())
        server.enqueueJson(200, file("ordner-1", DriveBackup.FOLDER_NAME))
        server.enqueueJson(200, files())
        server.enqueueJson(200, file("datei-1", DriveBackup.FILE_NAME))

        val result = backup().save("""{"format":"schafkopf-tracker-backup"}""")
        assertEquals(DriveResult.Success(DriveFile("datei-1", DriveBackup.FILE_NAME, Instant.parse("2026-11-16T20:00:00Z"), 12)), result)

        val findFolder = server.takeRequest()
        assertEquals("GET", findFolder.method)
        assertEquals("Bearer TESTTOKEN-123", findFolder.headers["Authorization"])
        val q = findFolder.url.queryParameter("q").orEmpty()
        assertTrue(q.contains("name = 'SchafkopfTracker_Backups'") && q.contains("trashed = false"), q)
        assertFalse(findFolder.url.toString().contains("TESTTOKEN"))

        val createFolder = server.takeRequest()
        assertEquals("POST", createFolder.method)
        assertEquals("/drive/v3/files", createFolder.url.encodedPath)
        assertTrue(createFolder.body!!.utf8().contains("application/vnd.google-apps.folder"))

        val findFile = server.takeRequest()
        assertTrue(findFile.url.queryParameter("q").orEmpty().contains("'ordner-1' in parents"))

        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/upload/drive/v3/files", upload.url.encodedPath)
        assertEquals("multipart", upload.url.queryParameter("uploadType"))
        val body = upload.body!!.utf8()
        assertTrue(body.contains("\"parents\":[\"ordner-1\"]") && body.contains("schafkopf-tracker-backup"), body)
        assertTrue(upload.headers["Content-Type"].orEmpty().startsWith("multipart/related"))
    }

    @Test
    fun `spaeter wird dieselbe Datei ersetzt, eine verschwundene neu angelegt`() = runTest {
        server.enqueueJson(200, files(file("ordner-1", DriveBackup.FOLDER_NAME)))
        server.enqueueJson(200, files(file("datei-1", DriveBackup.FILE_NAME)))
        server.enqueueJson(200, file("datei-1", DriveBackup.FILE_NAME))
        assertTrue(backup().save("{}") is DriveResult.Success)
        server.takeRequest()
        server.takeRequest()
        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/upload/drive/v3/files/datei-1", update.url.encodedPath)
        assertEquals("media", update.url.queryParameter("uploadType"))
        assertEquals("{}", update.body!!.utf8())

        // Die Datei wurde inzwischen im Papierkorb geleert.
        server.enqueueJson(200, files(file("ordner-1", DriveBackup.FOLDER_NAME)))
        server.enqueueJson(200, files(file("datei-1", DriveBackup.FILE_NAME)))
        server.enqueueJson(404, """{"error":{"code":404,"message":"File not found"}}""")
        server.enqueueJson(200, file("datei-2", DriveBackup.FILE_NAME))
        assertEquals("datei-2", (backup().save("{}") as DriveResult.Success).value.id)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `die neueste Sicherung finden und herunterladen`() = runTest {
        server.enqueueJson(200, files(file("ordner-1", DriveBackup.FOLDER_NAME)))
        server.enqueueJson(200, files(file("neu", DriveBackup.FILE_NAME), file("alt", DriveBackup.FILE_NAME, "2026-11-01T20:00:00Z")))
        server.enqueueJson(200, """{"format":"schafkopf-tracker-backup"}""")
        val latest = (backup().latest() as DriveResult.Success).value!!
        assertEquals("neu", latest.id)
        assertEquals(DriveResult.Success("""{"format":"schafkopf-tracker-backup"}"""), backup().read(latest))
        server.takeRequest()
        val list = server.takeRequest()
        assertEquals("modifiedTime desc", list.url.queryParameter("orderBy"))
        val download = server.takeRequest()
        assertEquals("/drive/v3/files/neu", download.url.encodedPath)
        assertEquals("media", download.url.queryParameter("alt"))
    }

    @Test
    fun `ohne Ordner gibt es noch keine Sicherung, und es wird nichts angelegt`() = runTest {
        server.enqueueJson(200, files())
        assertEquals(DriveResult.Success(null), backup().latest())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Fehler werden zu verstaendlichen Gruenden, ohne Token im Zusatz`() = runTest {
        val cases = listOf(
            401 to DriveError.SIGN_IN_REQUIRED,
            403 to DriveError.ACCESS_DENIED,
            404 to DriveError.NOT_FOUND,
            429 to DriveError.RATE_LIMITED,
            503 to DriveError.SERVER_UNAVAILABLE,
            418 to DriveError.UNKNOWN,
        )
        for ((status, error) in cases) {
            server.enqueueJson(status, """{"error":{"code":$status,"message":"TESTTOKEN-123"}}""")
            val result = client().findFolder("x")
            assertEquals(DriveResult.Failure(error, "HTTP $status"), result)
        }
        server.enqueueJson(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")
        assertEquals(DriveError.STORAGE_FULL, (client().createFolder("x") as DriveResult.Failure).error)
        server.enqueueJson(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        assertEquals(DriveError.RATE_LIMITED, (client().createFolder("x") as DriveResult.Failure).error)
    }

    @Test
    fun `ohne Anmeldung wird nichts gesendet`() = runTest {
        token = null
        assertEquals(DriveResult.Failure(DriveError.SIGN_IN_REQUIRED), backup().save("{}"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ein abgerissenes Netz ist ein Netzfehler, eine zu grosse Datei wird nicht gelesen`() = runTest {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertEquals(DriveError.NETWORK, (client().findFolder("x") as DriveResult.Failure).error)

        server.enqueueJson(200, "x".repeat(2_000))
        assertEquals(DriveResult.Failure(DriveError.TOO_LARGE), client().download("datei", maxBytes = 1_000))
    }

    @Test
    fun `Hochkommas in Namen werden fuer die Suche geschuetzt`() {
        assertEquals("""O\'Brien \\ Co""", GoogleDriveClient.escape("""O'Brien \ Co"""))
    }
}
