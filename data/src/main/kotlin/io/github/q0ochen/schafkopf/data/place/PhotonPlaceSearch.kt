package io.github.q0ochen.schafkopf.data.place

import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ortsvorschläge aus OpenStreetMap über Photon von komoot (docs/ARCHITEKTUR.md, Abschnitt 4.3): kostenlos, ohne
 * Schlüssel und ohne Konto. Gesendet wird nur der eingetippte Text; die Anfrage trägt keine Kennung von App, Gerät
 * oder Nutzer.
 *
 * @param client Der gemeinsame HTTP-Client der App; für die Suche gilt zusätzlich ein Gesamtlimit von [timeoutMillis].
 * @param baseUrl Adresse des Dienstes (in Tests ein Server im Speicher).
 */
class PhotonPlaceSearch(
    client: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaceSearch {

    private val client: OkHttpClient = client.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    /**
     * Sucht nach [query] (`GET …/api/?q=…&lang=de&limit=8`).
     *
     * @throws IOException ohne Antwort, bei einem anderen Status als 200 oder einer unbrauchbaren Antwort.
     */
    override suspend fun search(query: String): List<PlaceSuggestion> {
        val url = baseUrl.newBuilder()
            .addPathSegment("api")
            .addPathSegment("")
            .addQueryParameter("q", query)
            .addQueryParameter("lang", "de")
            .addQueryParameter("limit", REQUESTED.toString())
            .build()
        val body = withContext(dispatcher) { fetch(Request.Builder().url(url).get().build()) }
        return PhotonPlaces.suggestions(body)
    }

    /**
     * Ein einzelner Netzaufruf; er blockiert den Thread des Dispatchers, bis die Antwort ganz gelesen ist. Wird die
     * Coroutine währenddessen abgebrochen, beendet `call.cancel()` den Aufruf sofort.
     */
    private suspend fun fetch(request: Request): String {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            continuation.resumeWith(
                runCatching {
                    call.execute().use { response ->
                        if (response.code != HTTP_OK) throw IOException("Photon antwortet mit ${response.code}")
                        readBody(response.body)
                    }
                },
            )
        }
    }

    /** Der Text der Antwort, höchstens [MAX_BODY_BYTES]; eine größere Antwort gilt als unbrauchbar. */
    private fun readBody(body: ResponseBody): String {
        val source = body.source()
        if (source.request(MAX_BODY_BYTES + 1)) throw UnusablePlaceAnswerException("Antwort zu groß")
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return source.readString(charset)
    }

    companion object {
        /** Der öffentliche Photon-Server von komoot. */
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/"

        /** Höchstens so lange dauert eine Suche (Abschnitt 4.3: 10 Sekunden). */
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        /** So viele Treffer fragt die App an; nach dem Entfernen doppelter bleiben höchstens fünf. */
        const val REQUESTED = 8

        private const val HTTP_OK = 200
        private const val MAX_BODY_BYTES = 1_000_000L
    }
}
