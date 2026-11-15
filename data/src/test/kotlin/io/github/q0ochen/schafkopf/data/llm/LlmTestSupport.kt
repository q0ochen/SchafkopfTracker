package io.github.q0ochen.schafkopf.data.llm

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/*
 * Hilfen für die Tests der Sprachmodell-Anbindungen. Der Testserver läuft auf dem eigenen Gerät; nichts
 * verlässt den Rechner.
 */

/**
 * Ein erfundener Schlüssel. Er besteht nur aus Zeichen, die auch in einem Fehlerkürzel stehen dürfen: So zeigen
 * die Tests, dass er selbst dann nicht im Zusatz eines Fehlers landet, wenn ein Dienst ihn als Kürzel zurückschickt.
 */
internal const val TEST_KEY = "TESTSCHLUESSEL-0123456789"

/** Die HTTP-Clients der Tests. Sie werden nur einmal angelegt, weil das Anlegen spürbar Zeit kostet. */
internal object TestHttp {
    /**
     * Der Client der App, aber mit kürzerer Wartezeit auf Antworten: Schickt ein Test durch einen Fehler eine
     * Anfrage zu viel, hängt er fünf Sekunden statt einer Minute.
     */
    val client: OkHttpClient by lazy { defaultHttpClient().newBuilder().readTimeout(5, TimeUnit.SECONDS).build() }
}

/** Merkt sich die verlangten Wartezeiten, statt wirklich zu warten. */
internal class RecordingWait {
    val waits = CopyOnWriteArrayList<Long>()
    val function: suspend (Long) -> Unit = { millis -> waits.add(millis) }
}

/** Ein eigener Baustein für die Netzbibliothek, der bei jeder Anfrage [block] ausführt. */
internal fun interceptor(block: (Interceptor.Chain) -> Response): Interceptor = object : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = block(chain)
}

/**
 * Ein Client, der nichts ins Netz schickt: Er merkt sich jede Anfrage in [seen] und antwortet selbst mit
 * HTTP 200 und [body].
 */
internal fun offlineClient(seen: MutableList<Request>, body: String = "{}"): OkHttpClient =
    TestHttp.client.newBuilder()
        .addInterceptor(
            interceptor { chain ->
                seen.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

/** Startet den Testserver auf dem eigenen Gerät unter der Zahlenadresse 127.0.0.1. */
internal fun MockWebServer.startLocal() {
    start(InetAddress.getByName("127.0.0.1"), 0)
}

/** Die Adresse des Testservers, so wie ein Nutzer sie als Basis-Adresse eintragen würde. */
internal val MockWebServer.baseUrl: String get() = "http://127.0.0.1:$port"

/** Stellt eine Antwort mit JSON-Inhalt bereit. */
internal fun MockWebServer.enqueueJson(status: Int, body: String) {
    enqueue(MockResponse(code = status, headers = headersOf("Content-Type", "application/json; charset=UTF-8"), body = body))
}

/** Stellt eine Antwort bereit, die kein JSON ist, zum Beispiel die Fehlerseite eines vorgeschalteten Servers. */
internal fun MockWebServer.enqueueHtml(status: Int, body: String) {
    enqueue(MockResponse(code = status, headers = headersOf("Content-Type", "text/html; charset=UTF-8"), body = body))
}

/** Die nächste Anfrage, die beim Testserver angekommen ist. */
internal fun MockWebServer.nextRequest(): RecordedRequest =
    checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "Beim Testserver ist keine Anfrage angekommen." }
