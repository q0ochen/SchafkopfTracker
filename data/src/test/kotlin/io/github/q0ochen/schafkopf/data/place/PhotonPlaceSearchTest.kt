package io.github.q0ochen.schafkopf.data.place

import io.github.q0ochen.schafkopf.data.llm.TestHttp
import io.github.q0ochen.schafkopf.data.llm.baseUrl
import io.github.q0ochen.schafkopf.data.llm.enqueueHtml
import io.github.q0ochen.schafkopf.data.llm.enqueueJson
import io.github.q0ochen.schafkopf.data.llm.nextRequest
import io.github.q0ochen.schafkopf.data.llm.startLocal
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class PhotonPlaceSearchTest {

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.startLocal()

    @AfterEach
    fun stopServer() = server.close()

    private fun search(timeoutMillis: Long = PhotonPlaceSearch.DEFAULT_TIMEOUT_MILLIS) =
        PhotonPlaceSearch(TestHttp.client, baseUrl = (server.baseUrl + "/").toHttpUrl(), timeoutMillis = timeoutMillis)

    @Test
    fun `fragt genau die vereinbarte Adresse ab, ohne Kennung der App`() = runBlocking {
        server.enqueueJson(200, """{"features":[{"properties":{"name":"Klinikum Bamberg","city":"Bamberg"}}]}""")

        val result = search().search("Klinikum Bamberg & Co")

        assertEquals(listOf("Klinikum Bamberg, Bamberg"), result.map { it.text })
        val request = server.nextRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/", request.url.encodedPath)
        assertEquals("q=Klinikum%20Bamberg%20%26%20Co&lang=de&limit=8", request.url.encodedQuery)
        // Keine eigenen Kopfzeilen: nichts, was App, Gerät oder Nutzer erkennen ließe.
        assertNull(request.headers["Authorization"])
        assertEquals(setOf("host", "connection", "accept-encoding", "user-agent"), request.headers.names().map { it.lowercase() }.toSet())
        assertTrue(request.headers["User-Agent"].orEmpty().startsWith("okhttp/"))
    }

    @Test
    fun `der oeffentliche Dienst ist photon komoot io mit zehn Sekunden Frist`() {
        assertEquals("https://photon.komoot.io/", PhotonPlaceSearch.DEFAULT_BASE_URL)
        assertEquals(10_000L, PhotonPlaceSearch.DEFAULT_TIMEOUT_MILLIS)
    }

    @Test
    fun `ein anderer Status als 200 ist ein Fehler`() {
        server.enqueueJson(503, """{"features":[]}""")
        assertThrows<IOException> { runBlocking { search().search("Post") } }
    }

    @Test
    fun `eine unbrauchbare Antwort ist ein Fehler`() {
        server.enqueueHtml(200, "<html>Wartung</html>")
        assertThrows<UnusablePlaceAnswerException> { runBlocking { search().search("Post") } }
    }

    @Test
    fun `eine zu grosse Antwort ist ein Fehler`() {
        server.enqueueJson(200, """{"features":[],"x":"${"y".repeat(1_000_001)}"}""")
        assertThrows<UnusablePlaceAnswerException> { runBlocking { search().search("Post") } }
    }

    @Test
    fun `nach der Frist ist Schluss`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        assertThrows<IOException> { runBlocking { search(timeoutMillis = 300).search("Post") } }
    }

    @Test
    fun `ohne Server ist es ein Fehler`() {
        val search = search()
        server.close()
        assertThrows<IOException> { runBlocking { search.search("Post") } }
    }
}
