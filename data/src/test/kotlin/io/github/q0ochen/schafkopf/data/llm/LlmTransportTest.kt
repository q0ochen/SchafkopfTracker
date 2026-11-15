package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.SocketFactory

/**
 * Was für beide Anbieter gleich ist: der HTTP-Client, Abbruch, Zeitlimits, Netzfehler und die Regel, dass nur
 * die Anbindung selbst entscheidet, ob eine Anfrage wiederholt wird. Geprüft wird über die Gemini-Anbindung und,
 * wo es um die Verbindungen selbst geht, an der Netzanbindung allein.
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

    /** Die Netzanbindung allein. Ihr Client weiß, wie viele Verbindungen sie gerade offen hält. */
    private fun transport(client: OkHttpClient = TestHttp.client) = LlmTransport(client, Dispatchers.IO, pause.function)

    /** Ein Aufruf über die Netzanbindung allein, mit Schlüssel; das Ergebnis ist der HTTP-Status der Antwort. */
    private suspend fun LlmTransport.fetch(baseUrl: String): LlmResult<Int> = safely {
        LlmResult.Success(send(Request.Builder().url("$baseUrl/v1beta/models").header("x-goog-api-key", TEST_KEY).build()).status)
    }

    // ---------- HTTP-Client ----------

    @Test
    fun `der Standard-Client hat die verlangten Zeitlimits und keine eigenen Zusaetze`() {
        val client = defaultHttpClient()

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(180_000, client.callTimeoutMillis)
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
        assertEquals(permissive.callTimeoutMillis, strict.callTimeoutMillis)
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

    @Test
    fun `ein Abbruch, waehrend die Antwort eintrifft, beendet den Aufruf und gibt die Verbindung frei`() = runTest {
        // Die Kopfzeilen kommen sofort, der Text tröpfelt: Der Abbruch trifft den Aufruf mitten im Lesen.
        server.enqueue(
            MockResponse.Builder().code(200).body("a".repeat(100_000)).throttleBody(1024, 200, TimeUnit.MILLISECONDS).build(),
        )
        val reading = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val client = TestHttp.client.newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun responseHeadersEnd(call: Call, response: Response) {
                        reading.countDown()
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        failed.countDown()
                    }
                },
            )
            .build()
        val transport = transport(client)

        val running = async(Dispatchers.Default) { transport.fetch(server.baseUrl) }
        assertTrue(reading.await(5, TimeUnit.SECONDS), "Die Antwort hat nicht begonnen")
        running.cancelAndJoin()

        assertTrue(running.isCancelled)
        assertTrue(failed.await(5, TimeUnit.SECONDS), "Der Netzaufruf wurde nicht beendet")
        assertEquals(0, transport.client.connectionPool.connectionCount())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ein Abbruch in der Wartezeit verhindert den zweiten Versuch`() = runTest {
        server.enqueueJson(503, "")
        server.enqueueJson(200, answer)
        val waiting = CompletableDeferred<Unit>()
        val generator = GeminiTextGenerator(TestHttp.client, TEST_KEY, "gemini-test", server.baseUrl, Dispatchers.IO) {
            waiting.complete(Unit)
            awaitCancellation()
        }

        val running = async(Dispatchers.Default) { generator.generate("Anweisung", "Fakten") }
        waiting.await()
        running.cancelAndJoin()

        assertTrue(running.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `eine Abbruch-Ausnahme ohne Abbruch der Coroutine ist ein Fehler und kein Abbruch`() = runTest {
        // Ein Baustein des HTTP-Clients wirft die Ausnahme, mit der sonst ein Abbruch gemeldet wird, zum Beispiel
        // weil er selbst auf etwas Abgebrochenes gewartet hat. Die Coroutine hat niemand abgebrochen: Reichte die
        // Anbindung die Ausnahme durch, endete der Aufrufer stillschweigend, ohne je ein Ergebnis zu sehen.
        val client = TestHttp.client.newBuilder()
            .addInterceptor(interceptor { throw CancellationException("nachgestellt, Schlüssel $TEST_KEY") })
            .build()

        val result = generator(client).generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "CancellationException"), result)
        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "CancellationException"), generator(client).listModels())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nimmt der Dispatcher keine Arbeit mehr an, ist das ein Fehler und kein Abbruch`() = runTest {
        // Ein beendeter Dispatcher lehnt die Arbeit ab. Die Coroutinen-Bibliothek meldet das mit der Ausnahme für
        // einen Abbruch, obwohl die aufrufende Coroutine weiterläuft.
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        server.enqueueJson(200, answer)

        val result = generator(dispatcher = executor.asCoroutineDispatcher()).generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "CancellationException"), result)
        assertEquals(0, server.requestCount)
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

    @Test
    fun `reisst die Verbindung mitten in der Antwort ab, ist das ein Netzfehler`() = runTest {
        val json = headersOf("Content-Type", "application/json")
        val cut = SocketEffect.CloseSocket()
        // Mit Längenangabe, in Stücken gesendet und mitten in einer Fehlermeldung: Immer fehlt der Rest.
        server.enqueue(MockResponse.Builder().code(200).headers(json).body(answer).onResponseBody(cut).build())
        server.enqueue(MockResponse.Builder().code(200).headers(json).chunkedBody(answer, 16).onResponseBody(cut).build())
        server.enqueue(
            MockResponse.Builder().code(403).headers(json).body("""{"error":{"status":"PERMISSION_DENIED"}}""").onResponseBody(cut).build(),
        )

        repeat(3) {
            val result = generator().generate("Anweisung", "Fakten")
            assertTrue(result is LlmResult.Failure && result.error == LlmError.NETWORK, "$result")
            assertTrue(Regex("[A-Za-z]+Exception").matches((result as LlmResult.Failure).detail), result.detail)
        }
        assertEquals(3, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    @Test
    fun `eine verstuemmelte Statuszeile fuehrt zu einem Fehler statt zu einer Ausnahme`() = runTest {
        val lines = listOf(
            "Quatsch", "HTTP/1.1 -12 Minus", "HTTP/1.1 20 Kurz", "HTTP/1.1 2000 Lang", "HTTP/9.9 200 OK", "HTTP/1.1  200 OK",
            "HTTP/1.1 999 Seltsam", "HTTP/1.1 000 Null", "HTTP/1.1 600 Neu",
        )
        for (line in lines) {
            server.enqueue(MockResponse.Builder().status(line).addHeader("Content-Type", "application/json").body(answer).build())

            val result = generator().generate("Anweisung", "Fakten")

            assertTrue(result is LlmResult.Failure && result.error in setOf(LlmError.NETWORK, LlmError.UNKNOWN), "$line: $result")
        }
        assertEquals(lines.size, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
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
    fun `keiner Weiterleitung wird gefolgt, gleich welcher Art`() = runTest {
        val elsewhere = MockWebServer()
        elsewhere.startLocal()
        try {
            val following = TestHttp.client.newBuilder().followRedirects(true).followSslRedirects(true).build()
            val statuses = listOf(300, 301, 302, 303, 305, 307, 308)
            for (status in statuses) {
                // Einmal als Antwort auf generate (POST) und einmal auf listModels (GET).
                repeat(2) {
                    server.enqueue(MockResponse(code = status, headers = headersOf("Location", elsewhere.baseUrl + "/woanders"), body = ""))
                }
                assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP $status"), generator(following).generate("Anweisung", "Fakten"))
                assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP $status"), generator(following).listModels())
            }
            // Auch nicht an eine andere Stelle desselben Dienstes.
            server.enqueue(MockResponse(code = 301, headers = headersOf("Location", "/woanders"), body = ""))
            server.enqueueJson(200, answer)
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 301"), generator(following).generate("Anweisung", "Fakten"))

            assertEquals(2 * statuses.size + 1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
            assertEquals(emptyList<Long>(), pause.waits.toList())
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `ein unverschluesselter Aufruf geht direkt zum Dienst und nie ueber einen Proxy`() = runTest {
        // Der zweite Testserver spielt einen Proxy, wie er im Gerät oder im HTTP-Client eingestellt sein kann. Über
        // ihn liefe die unverschlüsselte Anfrage samt Schlüssel und Zahlen im Klartext an einen weiteren Rechner.
        val proxy = MockWebServer()
        proxy.startLocal()
        try {
            val everything = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(proxy.proxyAddress)

                override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
            }
            val clients = listOf(
                TestHttp.client.newBuilder().proxy(proxy.proxyAddress).build(),
                TestHttp.client.newBuilder().proxySelector(everything).build(),
            )
            for (client in clients) {
                // Auch der Proxy hätte Antworten parat: Landet eine Anfrage bei ihm, hängt der Test nicht.
                repeat(2) { proxy.enqueueJson(200, answer) }
                server.enqueueJson(200, answer)
                server.enqueueJson(200, """{"models":[]}""")

                assertEquals(success, generator(client).generate("Anweisung", "Fakten"))
                assertEquals(LlmResult.Success(emptyList<String>()), generator(client).listModels())
            }

            assertEquals(0, proxy.requestCount)
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals(TEST_KEY, server.nextRequest().headers["x-goog-api-key"]) }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun `eine unsinnige Angabe zur Wartezeit stoert nicht`() = runTest {
        // Die Zahl passt in keine Ganzzahl. Die Netzbibliothek bekommt die Kopfzeile gar nicht erst zu sehen.
        repeat(2) { server.enqueue(MockResponse(code = 503, headers = headersOf("Retry-After", "99999999999999999999"), body = "")) }

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 503"), result)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), pause.waits.toList())
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
    fun `ein Dienst, der seine Antwort nur troepfchenweise schickt, haelt den Aufruf nicht beliebig lange offen`() = runTest {
        // Jedes Stück kommt lange vor dem Leselimit von fünf Sekunden, die ganze Antwort bräuchte aber über zehn
        // Sekunden (ein Byte je Zehntelsekunde). Das Limit für den ganzen Aufruf beendet ihn vorher. Abgefragt wird
        // die Modell-Liste: Diese Anfrage hat keinen Inhalt, den der Testserver ebenso langsam lesen würde.
        assertTrue(answer.length > 100, "Die Antwort muss lang genug sein, damit der Test etwas prüft.")
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .body(answer)
                .throttleBody(1, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val impatient = TestHttp.client.newBuilder().callTimeout(700, TimeUnit.MILLISECONDS).build()

        val started = System.nanoTime()
        val result = generator(client = impatient).listModels()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(result is LlmResult.Failure && result.error == LlmError.NETWORK, "Ergebnis: $result")
        assertFalse(result.toString().contains(TEST_KEY))
        assertTrue(elapsedMillis in 600..4_999, "Der Aufruf dauerte $elapsedMillis ms.")
        // Ohne zweiten Versuch: Eine Zeitüberschreitung ist keine Überlastung.
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
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

    @Test
    fun `ueber HTTP 2 gelten dieselben Regeln, auch nach Fehlern und nach einem Abbruch`() = runTest {
        // Über HTTP/2 spricht Gemini; der Testserver versteht es hier ohne Verschlüsselung. Anders als bei HTTP/1.1
        // bleibt eine HTTP/2-Verbindung von sich aus bestehen, wenn ein Aufruf endet, bevor die Antwort beginnt:
        // Die nächste Anfrage liefe dann über dieselbe, vielleicht längst unbrauchbare Verbindung.
        val modern = MockWebServer()
        modern.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        modern.startLocal()
        try {
            val client = TestHttp.client.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
            val patient = transport(client)
            val impatient = transport(client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build())
            val silence = MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()

            // Eine Antwort, dann keine Antwort bis zum Zeitlimit.
            modern.enqueueJson(200, answer)
            assertEquals(LlmResult.Success(200), patient.fetch(modern.baseUrl))
            assertEquals(0, patient.client.connectionPool.connectionCount())
            modern.enqueue(silence)
            assertEquals(LlmResult.Failure(LlmError.NETWORK, "SocketTimeoutException"), impatient.fetch(modern.baseUrl))
            assertEquals(0, impatient.client.connectionPool.connectionCount())

            // Der Dienst weist die Anfrage ab, ohne sie zu bearbeiten (Fehlernummer 7, REFUSED_STREAM). Die
            // bereitliegende zweite Antwort darf niemand abholen: Wiederholt wird hier nicht.
            modern.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseStream(7)).build())
            val refused = patient.fetch(modern.baseUrl)
            assertTrue(refused is LlmResult.Failure && refused.error == LlmError.NETWORK, "$refused")
            assertEquals(0, patient.client.connectionPool.connectionCount())

            // Die Coroutine wird abgebrochen, während der Dienst schweigt.
            modern.enqueue(silence)
            val running = async(Dispatchers.Default) { patient.fetch(modern.baseUrl) }
            val requests = MutableList(4) { modern.nextRequest() }
            running.cancelAndJoin()
            assertTrue(running.isCancelled)
            assertEquals(0, patient.client.connectionPool.connectionCount())

            // Eine zu große Antwort: Gelesen wird nur bis zur Obergrenze, der Rest verfällt mit der Verbindung.
            modern.enqueueJson(200, "a".repeat(MAX_BODY_BYTES.toInt() + 100_000))
            assertEquals(LlmResult.Success(200), patient.fetch(modern.baseUrl))
            assertEquals(0, patient.client.connectionPool.connectionCount())
            requests += modern.nextRequest()

            // Danach geht es ganz normal weiter.
            modern.enqueueJson(200, answer)
            assertEquals(LlmResult.Success(200), patient.fetch(modern.baseUrl))
            requests += modern.nextRequest()

            assertEquals(List(6) { "HTTP/2" }, requests.map { it.version })
            // Jede Anfrage kam über eine eigene Verbindung.
            assertEquals(listOf(0, 1, 2, 3, 4, 5), requests.map { it.connectionIndex })
            assertEquals(6, modern.requestCount)
            assertEquals(emptyList<Long>(), pause.waits.toList())
        } finally {
            modern.close()
        }
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

    @Test
    fun `ein unbekannter oder verstuemmelter Zeichensatz gilt als UTF-8`() = runTest {
        val greeting = answer.replace("Servus", "Grüß Gott")
        val types = listOf(
            "application/json; charset=x-unbekannt", "application/json; charset=", "application/json; charset=\"UTF-8\"",
            "text/plain; charset", "quatsch", ";;;",
        )
        for (type in types) {
            server.enqueue(MockResponse.Builder().code(200).addHeaderLenient("Content-Type", type).body(greeting).build())

            assertEquals(LlmResult.Success(GeneratedText("Grüß Gott", "gemini-test-001")), generator().generate("Anweisung", "Fakten"), type)
        }

        // Bytes, die im genannten Zeichensatz keinen Sinn ergeben, sind eine unlesbare Antwort und kein Absturz.
        val broken = Buffer().write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0x28, 0x00)).writeUtf8(answer)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=UTF-8").body(broken).build())
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=UTF-32").body(Buffer().writeUtf8(answer)).build(),
        )
        repeat(2) {
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().generate("Anweisung", "Fakten"))
        }
    }

    @Test
    fun `eine gepackte Antwort wird ausgepackt gelesen, die Obergrenze gilt fuer die ausgepackte Groesse`() = runTest {
        fun gzip(write: (BufferedSink) -> Unit): Buffer {
            val packed = Buffer()
            GzipSink(packed).buffer().use(write)
            return packed
        }

        fun packedAnswer(body: Buffer): MockResponse =
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").addHeader("Content-Encoding", "gzip").body(body).build()

        // 64 MiB Leerzeichen und dahinter eine gültige Antwort: gepackt nur wenige Kilobyte. Ohne Obergrenze für die
        // ausgepackte Größe landete alles im Speicher und gälte sogar als Erfolg.
        val megabyteOfSpaces = ByteArray(1024 * 1024) { ' '.code.toByte() }
        val bomb = gzip { sink ->
            repeat(64) { sink.write(megabyteOfSpaces) }
            sink.writeUtf8(answer)
        }
        assertTrue(bomb.size < 1024 * 1024, "gepackt ${bomb.size} Bytes")
        server.enqueue(packedAnswer(gzip { it.writeUtf8(answer) }))
        server.enqueue(packedAnswer(bomb))

        assertEquals(success, generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().generate("Anweisung", "Fakten"))
        // Die Netzbibliothek bietet das Packen von sich aus an und packt auch selbst aus.
        assertEquals("gzip", server.nextRequest().headers["Accept-Encoding"])
    }

    // ---------- Nichts bleibt offen, nichts verrät den Schlüssel ----------

    @Test
    fun `nach jedem Aufruf sind Antwort und Verbindung geschlossen, auch nach Fehlern`() = runTest {
        // Die Netzbibliothek meldet, wenn ein Aufruf eine Verbindung belegt und wieder freigibt. Eine Antwort, die
        // offen bliebe, hielte ihre Verbindung fest, und der Aufruf gälte nie als beendet.
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val finished = AtomicInteger()
        val client = TestHttp.client.newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        acquired.incrementAndGet()
                    }

                    override fun connectionReleased(call: Call, connection: Connection) {
                        released.incrementAndGet()
                    }

                    override fun callEnd(call: Call) {
                        finished.incrementAndGet()
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        finished.incrementAndGet()
                    }
                },
            )
            .build()
        val patient = transport(client)
        // Für die Fälle, in denen der Dienst verstummt: Hier soll das Zeitlimit schnell greifen.
        val impatient = transport(client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build())
        val json = headersOf("Content-Type", "application/json")
        val network = LlmResult.Failure(LlmError.NETWORK, "")
        // Eine Antwort, die mittendrin etwas mit der Verbindung anstellt.
        fun interrupted(effect: SocketEffect): MockResponse =
            MockResponse.Builder().code(200).headers(json).body(answer).onResponseBody(effect).build()
        // Je Fall: was der Dienst schickt, das erwartete Ergebnis (bei Netzfehlern zählt nur die Fehlerart) und die
        // Zahl der Netzaufrufe.
        val answered = listOf(
            Triple(listOf(MockResponse(200, json, answer)), LlmResult.Success(200), 1),
            Triple(listOf(MockResponse(403, json, """{"error":{"status":"PERMISSION_DENIED"}}""")), LlmResult.Success(403), 1),
            // Zu groß: Der Rest der Antwort wird nicht mehr gelesen.
            Triple(listOf(MockResponse(200, json, "a".repeat(MAX_BODY_BYTES.toInt() + 100_000))), LlmResult.Success(200), 1),
            Triple(listOf(MockResponse(307, headersOf("Location", "/woanders"), "weiter")), LlmResult.Success(307), 1),
            Triple(listOf(MockResponse(503, json, "voll"), MockResponse(200, json, answer)), LlmResult.Success(200), 2),
            Triple(listOf(MockResponse(429, json, "voll"), MockResponse(429, json, "voll")), LlmResult.Success(429), 2),
            // Die Verbindung reißt ab: vor der Antwort und mitten in ihr.
            Triple(listOf(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()), network, 1),
            Triple(listOf(interrupted(SocketEffect.CloseSocket())), network, 1),
        )
        // Der Dienst verstummt: vor der Antwort und mitten in ihr.
        val silent = listOf(
            Triple(listOf(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()), network, 1),
            Triple(listOf(interrupted(SocketEffect.Stall)), network, 1),
        )
        var calls = 0
        for ((transport, cases) in listOf(patient to answered, impatient to silent)) {
            for ((responses, expected, attempts) in cases) {
                responses.forEach { server.enqueue(it) }

                val result = transport.fetch(server.baseUrl)

                calls += attempts
                assertEquals(expected, if (result is LlmResult.Failure) result.copy(detail = "") else result)
                assertEquals(calls, acquired.get(), "belegte Verbindungen nach $result")
                assertEquals(calls, released.get(), "freigegebene Verbindungen nach $result")
                assertEquals(calls, finished.get(), "beendete Aufrufe nach $result")
                assertEquals(0, transport.client.connectionPool.connectionCount(), "offene Verbindungen nach $result")
            }
        }
        assertEquals(calls, server.requestCount)
    }

    @Test
    fun `die Netzbibliothek schreibt den Schluessel auch im ausfuehrlichsten Protokoll nicht mit`() = runTest {
        val records = CopyOnWriteArrayList<String>()
        val recorder = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add("${record.message} ${record.parameters?.joinToString()} ${record.thrown}")
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        recorder.level = Level.ALL
        val root = Logger.getLogger("")
        val library = Logger.getLogger("okhttp3")
        val levelBefore = library.level
        root.addHandler(recorder)
        library.level = Level.ALL
        try {
            // Ein Erfolg, eine Fehlermeldung, die den Schlüssel zurückschickt, und ein Abriss der Verbindung.
            server.enqueueJson(200, answer)
            server.enqueueJson(403, """{"error":{"status":"$TEST_KEY","message":"Schlüssel $TEST_KEY abgelehnt"}}""")
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            assertEquals(success, generator().generate("Anweisung", "Fakten"))
            assertEquals(LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403"), generator().generate("Anweisung", "Fakten"))
            val broken = generator().generate("Anweisung", "Fakten")
            assertTrue(broken is LlmResult.Failure && broken.error == LlmError.NETWORK, "$broken")
        } finally {
            root.removeHandler(recorder)
            library.level = levelBefore
        }

        // Das ausführliche Protokoll war wirklich eingeschaltet, und der Schlüssel steht in keinem Eintrag.
        assertTrue(records.isNotEmpty(), "kein Eintrag im Protokoll")
        assertEquals(emptyList<String>(), records.filter { it.contains(TEST_KEY, ignoreCase = true) })
    }

    @Test
    fun `kein Baustein der Anbindungen verraet den Schluessel ueber toString`() {
        val ownPackage = GeminiTextGenerator::class.java.packageName
        val pending = ArrayDeque<Any>()
        pending.add(generator())
        pending.add(OpenAiCompatibleTextGenerator(TestHttp.client, TEST_KEY, "modell-x", server.baseUrl, wait = pause.function))
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            assertFalse(current.toString().contains(TEST_KEY), current.javaClass.name)
            // Weiter geht es nur durch die eigenen Klassen. Der Schlüssel selbst ist ein schlichter Text und bleibt außen vor.
            for (field in current.javaClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                val value = field.get(current) ?: continue
                if (value.javaClass.packageName == ownPackage) pending.add(value)
            }
        }
        // Gefunden wurden nicht nur die beiden Anbindungen, sondern auch ihre Bausteine (mindestens die Netzanbindung).
        assertTrue(seen.any { it is LlmTransport }, seen.map { it.javaClass.simpleName }.toString())
    }
}
