package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

/** Bei diesen Antworten ist der Dienst überlastet oder kurz nicht erreichbar: Ein zweiter Versuch lohnt sich. */
private val RETRY_STATUSES = setOf(429, 500, 502, 503, 504)

/** So lange wird vor dem zweiten Versuch gewartet. */
internal const val RETRY_DELAY_MILLIS = 2000L

/** Längere Zusätze zu einem Fehler werden abgeschnitten. */
internal const val MAX_DETAIL_LENGTH = 120

/** Größere Antworten werden nicht gelesen. Eine Zusammenfassung oder Modell-Liste ist um Größenordnungen kleiner. */
internal const val MAX_BODY_BYTES = 8L * 1024 * 1024

private const val RETRY_AFTER = "Retry-After"

private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

/**
 * Unsichtbare Markierung (BOM, das Zeichen U+FEFF), die manche Dienste vor ihre Antwort setzen. Bewusst über
 * die Zeichennummer gebildet, damit kein unsichtbares Zeichen im Quelltext steht.
 */
private val BYTE_ORDER_MARK: String = Char(0xFEFF).toString()

/**
 * Einstellungen für das Lesen von Antworten: Unbekannte Felder werden überlesen, leere (null) Angaben und
 * Zahlen an Stellen, an denen Text erwartet wird, führen nicht zu einem Fehler.
 */
private val LENIENT_JSON: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** Antwort eines Dienstes: der HTTP-Status und der Text dahinter (`null`, wenn er zu groß war). */
internal class HttpAnswer(val status: Int, val body: String?) {
    /** Ob der Dienst die Anfrage angenommen hat (HTTP 200 bis 299). */
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Die gemeinsame Netzanbindung beider Sprachmodell-Anbieter. Sie sorgt dafür, dass
 *
 * - Netzaufrufe auf dem übergebenen Dispatcher laufen und zusammen mit der Coroutine abgebrochen werden,
 * - bei Überlastung (HTTP 429, 500, 502, 503, 504) genau ein zweiter Versuch folgt und sonst nie einer,
 * - der Schlüssel nur an die eingestellte Adresse geht (Weiterleitungen werden nicht befolgt),
 * - aus jedem Fehler ein [LlmResult.Failure] wird statt einer Exception.
 *
 * @param baseClient Der gemeinsame HTTP-Client der App.
 * @param dispatcher Darauf laufen die Netzaufrufe und das Lesen der Antworten.
 * @param wait Wartet die angegebene Zahl von Millisekunden.
 */
internal class LlmTransport(
    baseClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val wait: suspend (Long) -> Unit,
) {
    /**
     * Der übergebene Client mit strengeren Regeln: Er folgt keiner Weiterleitung und sendet von sich aus nichts
     * ein zweites Mal (auch nicht über eine andere Adresse desselben Dienstes, wenn die Verbindung mitten in der
     * Antwort abreißt). Zeitlimits und alles Übrige übernimmt er vom übergebenen Client.
     */
    val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor(DropRetryAfter)
        .build()

    /**
     * Führt [work] auf dem Dispatcher aus und macht aus allem, was dabei schiefgeht, ein Ergebnis: Netzfehler
     * und Zeitüberschreitungen werden zu [LlmError.NETWORK], alles Unerwartete zu [LlmError.UNKNOWN]. Als
     * Zusatz dient nur der Name der Fehlerart, nie ihr Text. Ein Abbruch der Coroutine wird durchgereicht.
     */
    suspend fun <T> safely(work: suspend () -> LlmResult<T>): LlmResult<T> =
        try {
            withContext(dispatcher) { work() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LlmResult.Failure(LlmError.NETWORK, e.shortName())
        } catch (e: Exception) {
            LlmResult.Failure(LlmError.UNKNOWN, e.shortName())
        }

    /**
     * Schickt die Anfrage ab. Meldet der Dienst Überlastung, folgt nach [RETRY_DELAY_MILLIS] genau ein zweiter
     * Versuch; dessen Antwort gilt, wie auch immer sie ausfällt.
     *
     * Jede Anfrage bekommt eine eigene Verbindung (`Connection: close`). So kann sie nie an einer alten
     * Verbindung scheitern, die der Dienst inzwischen geschlossen hat.
     *
     * @throws IOException wenn keine Antwort zustande kommt.
     */
    suspend fun send(request: Request): HttpAnswer {
        val single = request.newBuilder().header("Connection", "close").build()
        val first = execute(single)
        if (first.status !in RETRY_STATUSES) return first
        wait(RETRY_DELAY_MILLIS)
        return execute(single)
    }

    /**
     * Ein einzelner Netzaufruf. Er blockiert den Thread des Dispatchers, bis die Antwort ganz gelesen ist. Wird
     * die Coroutine währenddessen abgebrochen, beendet `call.cancel()` den Aufruf sofort.
     */
    private suspend fun execute(request: Request): HttpAnswer {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            continuation.resumeWith(
                runCatching { call.execute().use { response -> HttpAnswer(response.code, readBody(response.body)) } },
            )
        }
    }
}

/**
 * Entfernt aus jeder Antwort die Kopfzeile `Retry-After`. Mit „503, sofort noch einmal versuchen“
 * (`Retry-After: 0`) würde die Netzbibliothek die Anfrage sonst selbst wiederholen; ob wiederholt wird,
 * entscheidet aber allein [LlmTransport.send].
 */
private object DropRetryAfter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request()).newBuilder().removeHeader(RETRY_AFTER).build()
}

/**
 * Liest den Text einer Antwort, höchstens [MAX_BODY_BYTES]. Eine größere Antwort wird verworfen (`null`): So
 * kann ein fehlerhafter Dienst die App nicht mit einer endlosen Antwort zum Absturz bringen.
 */
private fun readBody(body: ResponseBody): String? {
    val source = body.source()
    if (source.request(MAX_BODY_BYTES + 1)) return null
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return source.readString(charset).removePrefix(BYTE_ORDER_MARK)
}

/** Der Name der Fehlerart, zum Beispiel `SocketTimeoutException`. Der Fehlertext bleibt bewusst außen vor. */
private fun Exception.shortName(): String = javaClass.simpleName.take(MAX_DETAIL_LENGTH)

/** Verpackt JSON als Anfragetext mit der Kopfzeile `Content-Type: application/json` (immer UTF-8). */
internal fun jsonBody(json: JsonObject): RequestBody =
    json.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE)

/** Liest eine Antwort in die erwartete Form; `null`, wenn sie fehlt, kein JSON ist oder nicht dazu passt. */
internal fun <T> decodeOrNull(strategy: DeserializationStrategy<T>, body: String?): T? {
    if (body == null) return null
    return readingJson { LENIENT_JSON.decodeFromString(strategy, body) }
}

/** Liest beliebiges JSON als Baum; `null`, wenn der Text fehlt oder kein JSON ist. */
internal fun parseOrNull(body: String?): JsonElement? {
    if (body == null) return null
    return readingJson { Json.parseToJsonElement(body) }
}

/**
 * Fängt ab, was beim Lesen von JSON aus fremder Hand passieren kann: kaputter oder unpassender Inhalt und eine
 * Verschachtelung, die tiefer ist, als der Arbeitsspeicher des Lesers (der Stapel) reicht.
 */
private inline fun <T> readingJson(block: () -> T): T? =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: StackOverflowError) {
        null
    }

/** Der Inhalt eines Feldes, wenn dort ein Text steht; sonst `null`. */
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Das Feld `error` aus der Fehlermeldung eines Dienstes. Die Meldung kommt als Objekt `{"error":{…}}` oder als
 * Liste `[{"error":{…}}]`; `null`, wenn sie anders aussieht oder kein JSON ist.
 */
internal fun errorObject(body: String?): JsonObject? {
    val root = parseOrNull(body)
    val holder = if (root is JsonArray) root.firstOrNull { (it as? JsonObject)?.get("error") is JsonObject } else root
    return (holder as? JsonObject)?.get("error") as? JsonObject
}

/**
 * Der kurze technische Zusatz zu einem Fehler: „HTTP 429“ und, wenn der Dienst eines mitteilt, sein Kürzel für
 * den Fehler („HTTP 429 RESOURCE_EXHAUSTED“). Höchstens [MAX_DETAIL_LENGTH] Zeichen.
 *
 * Übernommen wird das erste brauchbare Kürzel aus [candidates]: nur Buchstaben, Ziffern, Unterstrich, Punkt
 * und Bindestrich. Freie Fehlertexte kommen nie in den Zusatz, denn manche Dienste wiederholen darin den
 * Schlüssel. Ein Kürzel, in dem der Schlüssel steht, wird weggelassen.
 */
internal fun failureDetail(status: Int, apiKey: String, vararg candidates: String?): String {
    val code = candidates.firstNotNullOfOrNull { candidate -> candidate?.trim()?.takeIf { isUsableCode(it, apiKey) } }
    return (if (code == null) "HTTP $status" else "HTTP $status $code").take(MAX_DETAIL_LENGTH)
}

private fun isUsableCode(code: String, apiKey: String): Boolean =
    code.isNotEmpty() && code.all(::isCodeChar) && (apiKey.isEmpty() || !code.contains(apiKey, ignoreCase = true))

private fun isCodeChar(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '.' || char == '-'

/**
 * Der Schlüssel ohne Leerraum an den Rändern (beim Einfügen rutscht leicht ein Zeilenumbruch mit); leer, wenn
 * keiner hinterlegt ist.
 */
internal fun cleanKey(apiKey: String?): String = apiKey?.trim().orEmpty()

/**
 * Ob der Schlüssel in eine Kopfzeile passt: nur sichtbare ASCII-Zeichen. Alles andere kann kein gültiger
 * Schlüssel sein. Die Prüfung verhindert außerdem, dass die Netzbibliothek einen unzulässigen Wert samt
 * Schlüssel in eine Fehlermeldung schreibt. Ein leerer Schlüssel besteht die Prüfung.
 */
internal fun isSendable(key: String): Boolean = key.all { it in '!'..'~' }

/** Modellnamen ohne Leereinträge und Dubletten, alphabetisch (Groß- und Kleinschreibung spielt keine Rolle). */
internal fun sortedModelNames(names: Collection<String>): List<String> =
    names.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
