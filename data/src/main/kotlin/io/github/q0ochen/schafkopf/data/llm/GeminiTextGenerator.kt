package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gemini von Google, angesprochen über die offene REST-Schnittstelle (ohne Googles eigene Bibliothek).
 *
 * - Der Schlüssel reist nur in der Kopfzeile `x-goog-api-key`, nie in der Adresse. Er steht in keinem Fehler
 *   und nicht in [toString].
 * - Ist der Dienst überlastet (HTTP 429, 500, 502, 503, 504), folgt nach zwei Sekunden genau ein zweiter
 *   Versuch. In allen anderen Fällen wird nie wiederholt.
 * - Fehler kommen nie als Exception, sondern als [LlmResult.Failure] mit einer Fehlerart aus [LlmError] und
 *   einem kurzen Zusatz wie „HTTP 429 RESOURCE_EXHAUSTED“.
 *
 * @param client Der gemeinsame HTTP-Client der App, siehe [defaultHttpClient].
 * @param apiKey Der Schlüssel des Nutzers. Fehlt er, scheitert jede Anfrage mit [LlmError.INVALID_KEY], ohne
 *   dass etwas gesendet wird.
 * @param model Name des Modells, zum Beispiel aus [listModels]. Für [listModels] selbst darf er leer sein.
 * @param baseUrl Adresse des Dienstes. Sie muss die [EndpointPolicy] erfüllen, sonst scheitert jede Anfrage mit
 *   [LlmError.INVALID_ENDPOINT], ohne dass etwas gesendet wird.
 * @param dispatcher Darauf laufen die Netzaufrufe (sie blockieren ihren Thread).
 * @param wait Wartet die angegebene Zahl von Millisekunden vor dem zweiten Versuch.
 */
class GeminiTextGenerator(
    client: OkHttpClient,
    apiKey: String?,
    model: String,
    baseUrl: String = DEFAULT_BASE_URL,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    wait: suspend (Long) -> Unit = { delay(it) },
) : TextGenerator {

    private val transport = LlmTransport(client, dispatcher, wait)
    private val key: String = cleanKey(apiKey)
    private val model: String = model.trim().removePrefix(MODEL_PREFIX)
    private val endpoint: HttpUrl? = EndpointPolicy.parse(baseUrl)

    /**
     * Lässt das Modell einen Text schreiben. Der Text ist die Antwort ohne die Zwischenüberlegungen des Modells;
     * als Modell wird gemeldet, welches laut Dienst tatsächlich geantwortet hat.
     */
    override suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText> {
        val base = endpoint ?: return LlmResult.Failure(LlmError.INVALID_ENDPOINT)
        if (!hasUsableKey()) return LlmResult.Failure(LlmError.INVALID_KEY)
        if (model.isEmpty()) return LlmResult.Failure(LlmError.MODEL_UNAVAILABLE)

        val url = base.newBuilder().addPathSegments(MODELS_PATH).addPathSegment("$model:$GENERATE_METHOD").build()
        val body = generateRequest(systemPrompt, userText)
        return transport.safely {
            val answer = transport.send(request(url).post(jsonBody(body)).build())
            if (answer.isSuccess) readText(answer) else httpFailure(answer)
        }
    }

    /**
     * Die Modelle des Dienstes, die Text schreiben können, alphabetisch und ohne das Präfix `models/`. Modelle
     * für Bilder, Ton, Video und andere Sonderaufgaben fehlen in der Liste.
     */
    override suspend fun listModels(): LlmResult<List<String>> {
        val base = endpoint ?: return LlmResult.Failure(LlmError.INVALID_ENDPOINT)
        if (!hasUsableKey()) return LlmResult.Failure(LlmError.INVALID_KEY)

        return transport.safely {
            val names = ArrayList<String>()
            var pageToken: String? = null
            var pagesLeft = MAX_MODEL_PAGES
            do {
                val answer = transport.send(request(modelsUrl(base, pageToken)).get().build())
                if (!answer.isSuccess) return@safely httpFailure(answer)
                val page = decodeOrNull(GeminiModelList.serializer(), answer.body)
                    ?: return@safely failure(LlmError.UNKNOWN, answer)
                page.models
                    .filter { GENERATE_METHOD in it.supportedGenerationMethods }
                    .mapTo(names) { it.name.removePrefix(MODEL_PREFIX) }
                pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
                pagesLeft--
            } while (pageToken != null && pagesLeft > 0)
            LlmResult.Success(sortedModelNames(names.filter(::writesText)))
        }
    }

    /** Beschreibt die Anbindung, ohne den Schlüssel zu verraten. */
    override fun toString(): String = "GeminiTextGenerator(model=$model, host=${endpoint?.host})"

    private fun hasUsableKey(): Boolean = key.isNotEmpty() && isSendable(key)

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).header(KEY_HEADER, key)

    private fun modelsUrl(base: HttpUrl, pageToken: String?): HttpUrl {
        val url = base.newBuilder().addPathSegments(MODELS_PATH).addQueryParameter("pageSize", MODEL_PAGE_SIZE)
        if (pageToken != null) url.addQueryParameter("pageToken", pageToken)
        return url.build()
    }

    /** Die Anfrage: Anweisung, Fakten und die Obergrenze für die Länge der Antwort. Sonst nichts. */
    private fun generateRequest(systemPrompt: String, userText: String): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", userText) } }
            }
        }
        putJsonObject("generationConfig") { put("maxOutputTokens", MAX_OUTPUT_TOKENS) }
    }

    /** Wertet eine angenommene Anfrage aus: blockiert, leer, abgeschnitten oder ein brauchbarer Text. */
    private fun readText(answer: HttpAnswer): LlmResult<GeneratedText> {
        val response = decodeOrNull(GeminiResponse.serializer(), answer.body)
            ?: return failure(LlmError.UNKNOWN, answer)

        // Schon die Anfrage wurde abgelehnt: Dann gibt es gar keine Antwort.
        val blockReason = response.promptFeedback?.blockReason
        if (!blockReason.isNullOrBlank()) return failure(LlmError.BLOCKED, answer, blockReason)

        val candidate = response.candidates.firstOrNull()
        val finishReason = candidate?.finishReason
        if (finishReason in BLOCKING_FINISH_REASONS) return failure(LlmError.BLOCKED, answer, finishReason)

        // Zwischenüberlegungen des Modells (thought) gehören nicht zur Antwort.
        val text = candidate?.content?.parts.orEmpty().filterNot { it.thought }.joinToString("") { it.text.orEmpty() }
        if (text.isBlank() || finishReason == TRUNCATED) {
            return failure(LlmError.EMPTY_OR_TRUNCATED, answer, finishReason)
        }

        return LlmResult.Success(GeneratedText(text, response.modelVersion?.takeIf { it.isNotBlank() } ?: model))
    }

    /** Ordnet eine abgelehnte Anfrage anhand von HTTP-Status und Fehlermeldung einer Fehlerart zu. */
    private fun httpFailure(answer: HttpAnswer): LlmResult.Failure {
        val error = errorObject(answer.body)
        val apiStatus = error?.text("status")
        val reasons = (error?.get("details") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("reason") }
        val kind = when (answer.status) {
            401 -> LlmError.INVALID_KEY
            400 -> when {
                KEY_INVALID_REASON in reasons || apiStatus == "UNAUTHENTICATED" -> LlmError.INVALID_KEY
                apiStatus == "FAILED_PRECONDITION" -> LlmError.ACCESS_DENIED
                else -> LlmError.UNKNOWN
            }
            403 -> LlmError.ACCESS_DENIED
            404 -> LlmError.MODEL_UNAVAILABLE
            429 -> LlmError.QUOTA_EXCEEDED
            500, 502, 503, 504 -> LlmError.SERVER_UNAVAILABLE
            else -> LlmError.UNKNOWN
        }
        return failure(kind, answer, apiStatus)
    }

    private fun failure(error: LlmError, answer: HttpAnswer, apiStatus: String? = null): LlmResult.Failure =
        LlmResult.Failure(error, failureDetail(answer.status, key, apiStatus))

    /** Ob ein Modell dem Namen nach Text schreibt und nicht Bilder, Ton, Video oder andere Sonderaufgaben erledigt. */
    private fun writesText(name: String): Boolean {
        val lowercase = name.lowercase()
        return EXCLUDED_NAME_PARTS.none { it in lowercase }
    }

    companion object {
        /** Die Adresse der Gemini API. */
        const val DEFAULT_BASE_URL: String = "https://generativelanguage.googleapis.com"

        private const val KEY_HEADER = "x-goog-api-key"
        private const val MODELS_PATH = "v1beta/models"
        private const val MODEL_PREFIX = "models/"
        private const val GENERATE_METHOD = "generateContent"
        private const val MAX_OUTPUT_TOKENS = 4096

        /** So viele Modelle je Seite verlangt die App; mehr als [MAX_MODEL_PAGES] Seiten ruft sie nie ab. */
        private const val MODEL_PAGE_SIZE = "1000"
        private const val MAX_MODEL_PAGES = 5

        private const val KEY_INVALID_REASON = "API_KEY_INVALID"

        /** Mit diesen Begründungen hat der Dienst die Antwort aus inhaltlichen Gründen zurückgehalten. */
        private val BLOCKING_FINISH_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

        /** Die Antwort wurde abgeschnitten, weil die Obergrenze für ihre Länge erreicht war. */
        private const val TRUNCATED = "MAX_TOKENS"

        /** Modelle mit einem dieser Wörter im Namen schreiben keinen Text und fehlen deshalb in der Auswahl. */
        private val EXCLUDED_NAME_PARTS = listOf(
            "embedding", "image", "audio", "tts", "live", "veo", "imagen", "aqa", "robotics", "computer-use",
        )
    }
}

/*
 * Die Teile der Antworten von Gemini, die die App liest. Alle übrigen Felder werden überlesen.
 */

@Serializable
private class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val modelVersion: String? = null,
)

/** Ein Antwortvorschlag; die App verwendet nur den ersten. */
@Serializable
private class GeminiCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
private class GeminiContent(val parts: List<GeminiPart> = emptyList())

/** Ein Stück der Antwort. [thought] kennzeichnet eine Zwischenüberlegung des Modells. */
@Serializable
private class GeminiPart(val text: String? = null, val thought: Boolean = false)

/** Auskunft des Dienstes zur Anfrage selbst; [blockReason] ist gesetzt, wenn er sie abgelehnt hat. */
@Serializable
private class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
private class GeminiModelList(val models: List<GeminiModel> = emptyList(), val nextPageToken: String? = null)

@Serializable
private class GeminiModel(val name: String = "", val supportedGenerationMethods: List<String> = emptyList())
