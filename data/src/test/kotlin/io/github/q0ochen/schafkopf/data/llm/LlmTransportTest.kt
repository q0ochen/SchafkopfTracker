package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Was für beide Anbieter gleich ist: der HTTP-Client, Abbruch, Zeitlimits, Netzfehler und die Regel, dass nur
 * die Anbindung selbst entscheidet, ob eine Anfrage wiederholt wird. Geprüft wird über die Gemini-Anbindung.
 */
class LlmTransportTest {

    private val server = MockWebServer()
    private val pause = RecordingWait()

    private val answer =
        """{"candidates":[{"content":{"parts":[{"text":"Servus"}]},"finishReason":"STOP"}],"modelVersion":"gemini-test-001"}"""
    private val success = LlmResult.Success(GeneratedText("Servus", "gemini-test-001"))

    @BeforeEach
    fun startServer() {
        server.startLocal()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun generator(
        client: OkHttpClient = TestHttp.client,
        baseUrl: String = server.baseUrl,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = GeminiTextGenerator(client, TEST_KEY, "gemini-test", baseUrl, dispatcher, pause.function)

    // ---------- HTTP-Client ----------

    @Test
    fun `der Standard-Client hat die verlangten Zeitlimits und keine eigenen Zusaetze`() {
        val client = defaultHttpClient()

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(emptyList<Interceptor>(), client.interceptors)
        assertEquals(emptyList<Interceptor>(), client.networkInterceptors)
    }

    @Test
    fun `die Netzbibliothek darf weder Weiterleitungen folgen noch selbst wiederholen`() {
        val permissive = TestHttp.client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val strict = LlmTransport(permissive, Dispatchers.IO, pause.function).client

        assertFalse(strict.followRedirects)
        assertFalse(strict.followSslRedirects)
        assertFalse(strict.retryOnConnectionFailure)
        // Die Zeitlimits bleiben, wie sie übergeben wurden, und der übergebene Client bleibt, wie er war.
        assertEquals(permissive.connectTimeoutMillis, strict.connectTimeoutMillis)
        assertEquals(permissive.readTimeoutMillis, strict.readTimeoutMillis)
        assertTrue(permissive.followRedirects)
        assertTrue(permissive.retryOnConnectionFailure)
    }

    // ---------- Dispatcher und Abbruch ----------

    @Test
    fun `der Netzaufruf laeuft auf dem uebergebenen Dispatcher`() = runTest {
        server.enqueueJson(200, answer)
        val threads = CopyOnWriteArrayList<String>()
        val client = TestHttp.client.newBuilder()
            .addInterceptor(
                interceptor { chain ->
                    threads.add(Thread.currentThread().name)
                    chain.proceed(chain.request())
                },
            )
            .build()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sprachmodell-test") }

        try {
            val result = generator(client, dispatcher = executor.asCoroutineDispatcher()).generate("Anweisung", "Fakten")

            assertEquals(success, result)
            assertEquals(listOf("sprachmodell-test"), threads.toList())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `wird die Coroutine abgebrochen, wird auch der Netzaufruf abgebrochen`() = runTest {
        // Der Testserver nimmt die Anfrage an und antwortet dann nicht mehr.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val canceled = CountDownLatch(1)
        val client = TestHttp.client.newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.countDown()
                    }
                },
            )
            .build()

        val running = async(Dispatchers.Default) { generator(client).generate("Anweisung", "Fakten") }
        server.nextRequest()
        running.cancelAndJoin()

        assertTrue(running.isCancelled)
        assertTrue(canceled.await(5, TimeUnit.SECONDS), "call.cancel() wurde nicht aufgerufen")
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    // ---------- Netzfehler ----------

    @Test
    fun `reisst die Verbindung ab, ist das ein Netzfehler ohne zweiten Versuch`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueueJson(200, answer)

        val result = generator().generate("Anweisung", "Fakten")

        assertTrue(result is LlmResult.Failure && result.error == LlmError.NETWORK, "$result")
        // Als Zusatz dient der Name der Fehlerart, zum Beispiel IOException.
        assertTrue(Regex("[A-Za-z]+Exception").matches((result as LlmResult.Failure).detail), result.detail)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    @Test
    fun `reisst die Verbindung ab, wird auch nicht ueber eine zweite Adresse des Dienstes neu gesendet`() = runTest {
        // Große Dienste sind unter mehreren Adressen erreichbar. Hier führen beide zum Testserver.
        val local = InetAddress.getByName("127.0.0.1")
        val twoAddresses = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(local, local)
        }
        val client = TestHttp.client.newBuilder().dns(twoAddresses).build()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueueJson(200, answer)

        val result = generator(client, baseUrl = "http://localhost:${server.port}").generate("Anweisung", "Fakten")

        assertTrue(result is LlmResult.Failure && result.error == LlmError.NETWORK, "$result")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ist die erste Adresse des Dienstes nicht erreichbar, wird die naechste versucht`() = runTest {
        // Die erste Adresse lässt sich nicht erreichen, so wie in einem Netz, in dem IPv6 nicht funktioniert. Das
        // Verbot, Anfragen von sich aus zu wiederholen, darf den Verbindungsaufbau nicht behindern.
        val unreachable = InetAddress.getByName("192.0.2.1")
        val reachable = InetAddress.getByName("127.0.0.1")
        val twoAddresses = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(unreachable, reachable)
        }
        val sockets = RefusingSocketFactory(unreachable)
        val client = TestHttp.client.newBuilder().dns(twoAddresses).socketFactory(sockets).build()
        server.enqueueJson(200, answer)

        val result = generator(client, baseUrl = "http://localhost:${server.port}").generate("Anweisung", "Fakten")

        assertEquals(success, result)
        assertEquals(1, sockets.refusals.get())
        assertEquals(1, server.requestCount)
    }

    /** Baut Verbindungen wie üblich auf, lässt aber den Aufbau zu einer bestimmten Adresse scheitern und zählt mit. */
    private class RefusingSocketFactory(private val refused: InetAddress) : SocketFactory() {
        val refusals = AtomicInteger()

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                if ((endpoint as? InetSocketAddress)?.address == refused) {
                    refusals.incrementAndGet()
                    throw ConnectException("Verbindung abgelehnt (nachgestellt)")
                }
                super.connect(endpoint, timeout)
            }
        }

        // Die Netzbibliothek benutzt nur die Form ohne Angaben; die übrigen braucht hier niemand.
        override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    @Test
    fun `antwortet der Dienst nicht rechtzeitig, ist das ein Netzfehler`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val impatient = TestHttp.client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        val result = generator(impatient).generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.NETWORK, "SocketTimeoutException"), result)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    @Test
    fun `der Fehlertext eines Netzfehlers kommt nicht in den Zusatz`() = runTest {
        val client = TestHttp.client.newBuilder()
            .addInterceptor(interceptor { throw IOException("Leitung tot, Schlüssel $TEST_KEY") })
            .build()

        val result = generator(client).generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.NETWORK, "IOException"), result)
        assertEquals(LlmResult.Failure(LlmError.NETWORK, "IOException"), generator(client).listModels())
    }

    @Test
    fun `ein unerwarteter Fehler wird zu einem unbekannten Fehler ohne Fehlertext`() = runTest {
        val client = TestHttp.client.newBuilder()
            .addInterceptor(interceptor { throw IllegalStateException("kaputt, Schlüssel $TEST_KEY") })
            .build()

        val result = generator(client).generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "IllegalStateException"), result)
        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "IllegalStateException"), generator(client).listModels())
    }

    // ---------- Nur die Anbindung selbst wiederholt ----------

    @Test
    fun `einer Weiterleitung wird nicht gefolgt, der Schluessel bleibt beim eingestellten Dienst`() = runTest {
        val elsewhere = MockWebServer()
        elsewhere.startLocal()
        try {
            server.enqueue(MockResponse(code = 307, headers = headersOf("Location", elsewhere.baseUrl + "/woanders"), body = ""))
            elsewhere.enqueueJson(200, answer)
            // Ein Client, der Weiterleitungen von sich aus folgen würde.
            val following = TestHttp.client.newBuilder().followRedirects(true).followSslRedirects(true).build()

            val result = generator(following).generate("Anweisung", "Fakten")

            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 307"), result)
            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `auch bei der Aufforderung zum sofortigen Wiederholen bleibt es bei einem zweiten Versuch`() = runTest {
        // „Retry-After: 0“ heißt: sofort noch einmal. Die Netzbibliothek würde dem von sich aus folgen.
        repeat(2) { server.enqueue(MockResponse(code = 503, headers = headersOf("Retry-After", "0"), body = "")) }
        server.enqueueJson(200, answer)

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 503"), result)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), pause.waits.toList())
    }

    @Test
    fun `eine Zeitueberschreitung beim Dienst wird nicht wiederholt`() = runTest {
        // HTTP 408 würde die Netzbibliothek von sich aus noch einmal senden.
        server.enqueue(MockResponse(code = 408, headers = headersOf("Connection", "close"), body = ""))
        server.enqueueJson(200, answer)

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 408"), result)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    @Test
    fun `jede Anfrage bekommt eine eigene Verbindung`() = runTest {
        server.enqueueJson(503, "")
        server.enqueueJson(200, answer)
        server.enqueueJson(200, answer)
        val generator = generator()

        assertEquals(success, generator.generate("Anweisung", "Fakten"))
        assertEquals(success, generator.generate("Anweisung", "Fakten"))

        val requests = List(3) { server.nextRequest() }
        assertEquals(listOf("close", "close", "close"), requests.map { it.headers["Connection"] })
        assertEquals(listOf(0, 1, 2), requests.map { it.connectionIndex })
    }

    // ---------- Antworten aus fremder Hand ----------

    @Test
    fun `eine Antwort bis zur Obergrenze wird gelesen, eine groessere verworfen`() = runTest {
        // Die Antwort wird mit einem unbekannten Feld genau bis zur Obergrenze aufgefüllt.
        val start = """{"fuellung":""""
        val end = """",""" + answer.removePrefix("{")
        val atLimit = start + "a".repeat((MAX_BODY_BYTES - start.length - end.length).toInt()) + end
        assertEquals(MAX_BODY_BYTES, atLimit.length.toLong())
        server.enqueueJson(200, atLimit)
        server.enqueueJson(200, start + "a" + atLimit.removePrefix(start))

        assertEquals(success, generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `eine uebergrosse Fehlermeldung wird nicht gelesen, der HTTP-Status zaehlt trotzdem`() = runTest {
        val tooLarge = """{"error":{"status":"PERMISSION_DENIED","message":"""" + "a".repeat(MAX_BODY_BYTES.toInt()) + """"}}"""
        server.enqueueJson(403, tooLarge)

        assertEquals(LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403"), generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `eine absurd tief verschachtelte Fehlermeldung bringt nichts zum Absturz`() = runTest {
        // So tiefe Verschachtelungen lehnt die JSON-Bibliothek ab; dann zählt nur der HTTP-Status.
        val depth = 100_000
        server.enqueueJson(400, "[".repeat(depth) + "]".repeat(depth))
        server.enqueueJson(400, "[".repeat(depth))

        repeat(2) {
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400"), generator().generate("Anweisung", "Fakten"))
        }
    }

    @Test
    fun `verschachteltes JSON bringt auch einen Thread mit kleinem Stapel nicht zum Absturz`() {
        // 400 Ebenen liest die JSON-Bibliothek normalerweise noch. Einem Thread mit sehr wenig Stapelspeicher kann
        // schon das zu viel sein: Dann gilt der Text als unlesbar, statt dass der Thread mit einem Fehler endet.
        val nested = "[".repeat(400) + "]".repeat(400)
        val crash = AtomicReference<Throwable?>(null)
        val thread = Thread(
            null,
            {
                try {
                    parseOrNull(nested)
                    decodeOrNull(JsonElement.serializer(), nested)
                } catch (t: Throwable) {
                    crash.set(t)
                }
            },
            "kleiner-stapel",
            16 * 1024L,
        )
        thread.start()
        thread.join()

        assertNull(crash.get())
    }

    @Test
    fun `eine unsichtbare Markierung vor der Antwort stoert nicht`() = runTest {
        server.enqueueJson(200, Char(0xFEFF) + answer)

        assertEquals(success, generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `die Antwort wird in dem Zeichensatz gelesen, den der Dienst nennt`() = runTest {
        val latin1 = Buffer().writeString(answer.replace("Servus", "Grüß Gott"), Charsets.ISO_8859_1)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=ISO-8859-1")
                .body(latin1)
                .build(),
        )

        assertEquals(LlmResult.Success(GeneratedText("Grüß Gott", "gemini-test-001")), generator().generate("Anweisung", "Fakten"))
    }
}
