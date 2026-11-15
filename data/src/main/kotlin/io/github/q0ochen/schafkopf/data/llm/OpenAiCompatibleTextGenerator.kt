package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ein beliebiger Dienst mit OpenAI-kompatibler Schnittstelle, zum Beispiel ein Sprachmodell auf dem eigenen
 * Rechner (Ollama, LM Studio) oder ein Anbieter im Netz.
 *
 * - Ein Schlüssel ist freiwillig: Nur wenn einer hinterlegt ist, wird er als `Authorization: Bearer …`
 *   mitgeschickt. Er steht in keinem Fehler und nicht in [toString].
 * - Ist der Dienst überlastet (HTTP 429, 500, 502, 503, 504), folgt nach zwei Sekunden genau ein zweiter
 *   Versuch. In allen anderen Fällen wird nie wiederholt.
 * - Fehler kommen nie als Exception, sondern als [LlmResult.Failure] mit einer Fehlerart aus [LlmError] und
 *   einem kurzen Zusatz wie „HTTP 401 invalid_api_key“.
 *
 * Die Reihenfolge der Angaben ist dieselbe wie beim [GeminiTextGenerator].
 *
 * @param client Der gemeinsame HTTP-Client der App, siehe [defaultHttpClient].
 * @param apiKey Der Schlüssel des Nutzers; `null` oder leer, wenn der Dienst keinen verlangt.
 * @param model Name des Modells, zum Beispiel aus [listModels]. Für [listModels] selbst darf er leer sein.
 * @param baseUrl Adresse des Dienstes bis vor `/chat/completions`, zum Beispiel `http://192.168.1.20:11434/v1`.
 *   Sie muss die [EndpointPolicy] erfüllen, sonst scheitert jede Anfrage mit [LlmError.INVALID_ENDPOINT], ohne
 *   dass etwas gesendet wird.
 * @param dispatcher Darauf laufen die Netzaufrufe (sie blockieren ihren Thread).
 * @param wait Wartet die angegebene Zahl von Millisekunden vor dem zweiten Versuch.
 */
class OpenAiCompatibleTextGenerator(
    client: OkHttpClient,
    apiKey: String?,
    model: String,
    baseUrl: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    wait: suspend (Long) -> Unit = { delay(it) },
) : TextGenerator {

    private val transport = LlmTransport(client, dispatcher, wait)
    private val key: String = cleanKey(apiKey)
    private val model: String = model.trim()
    private val endpoint: HttpUrl? = EndpointPolicy.parse(baseUrl)

    /**
     * Lässt das Modell einen Text schreiben. Zwischenüberlegungen in `<think>…</think>` werden entfernt; als
     * Modell wird gemeldet, welches laut Dienst tatsächlich geantwortet hat.
     */
    override suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText> {
        val base = endpoint ?: return LlmResult.Failure(LlmError.INVALID_ENDPOINT)
        if (!isSendable(key)) return LlmResult.Failure(LlmError.INVALID_KEY)
        if (model.isEmpty()) return LlmResult.Failure(LlmError.MODEL_UNAVAILABLE)

        val url = base.newBuilder().addPathSegments("chat/completions").build()
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userText)
                }
            }
        }
        return transport.safely {
            val answer = transport.send(request(url).post(jsonBody(body)).build())
            if (answer.isSuccess) readText(answer) else httpFailure(answer)
        }
    }

    /** Die Modelle, die der Dienst anbietet, alphabetisch. */
    override suspend fun listModels(): LlmResult<List<String>> {
        val base = endpoint ?: return LlmResult.Failure(LlmError.INVALID_ENDPOINT)
        if (!isSendable(key)) return LlmResult.Failure(LlmError.INVALID_KEY)

        val url = base.newBuilder().addPathSegment("models").build()
        return transport.safely {
            val answer = transport.send(request(url).get().build())
            if (!answer.isSuccess) return@safely httpFailure(answer)
            val entries = modelEntries(parseOrNull(answer.body)) ?: return@safely failure(LlmError.UNKNOWN, answer)
            LlmResult.Success(sortedModelNames(entries.mapNotNull { (it as? JsonObject)?.text("id") }))
        }
    }

    /** Beschreibt die Anbindung, ohne den Schlüssel zu verraten. */
    override fun toString(): String = "OpenAiCompatibleTextGenerator(model=$model, host=${endpoint?.host})"

    private fun request(url: HttpUrl): Request.Builder {
        val request = Request.Builder().url(url)
        if (key.isNotEmpty()) request.header("Authorization", "Bearer $key")
        return request
    }

    /** Wertet eine angenommene Anfrage aus: blockiert, leer, abgeschnitten oder ein brauchbarer Text. */
    private fun readText(answer: HttpAnswer): LlmResult<GeneratedText> {
        val response = decodeOrNull(ChatResponse.serializer(), answer.body)
            ?: return failure(LlmError.UNKNOWN, answer)
        val choice = response.choices.firstOrNull()
        val finishReason = choice?.finishReason
        if (finishReason == FILTERED) return failure(LlmError.BLOCKED, answer, finishReason)

        val text = withoutThinking(contentText(choice?.message?.content))
        if (text.isEmpty() || finishReason == TRUNCATED) {
            return failure(LlmError.EMPTY_OR_TRUNCATED, answer, finishReason)
        }

        return LlmResult.Success(GeneratedText(text, response.model?.takeIf { it.isNotBlank() } ?: model))
    }

    /** Ordnet eine abgelehnte Anfrage anhand des HTTP-Status einer Fehlerart zu. */
    private fun httpFailure(answer: HttpAnswer): LlmResult.Failure {
        val kind = when (answer.status) {
            401 -> LlmError.INVALID_KEY
            403 -> LlmError.ACCESS_DENIED
            404 -> LlmError.MODEL_UNAVAILABLE
            429 -> LlmError.QUOTA_EXCEEDED
            in 500..599 -> LlmError.SERVER_UNAVAILABLE
            else -> LlmError.UNKNOWN
        }
        val error = errorObject(answer.body)
        return failure(kind, answer, error?.text("code"), error?.text("type"))
    }

    private fun failure(error: LlmError, answer: HttpAnswer, vararg codes: String?): LlmResult.Failure =
        LlmResult.Failure(error, failureDetail(answer.status, key, *codes))

    /** Die Liste der Modelle: Üblich ist `{"data":[…]}`, manche Dienste liefern die Liste ohne diese Hülle. */
    private fun modelEntries(root: JsonElement?): JsonArray? = when (root) {
        is JsonArray -> root
        is JsonObject -> root["data"] as? JsonArray
        else -> null
    }

    /**
     * Der Text einer Antwort. Meist steht er als schlichter Text da; manche Dienste liefern stattdessen eine
     * Liste von Teilen, von denen nur die Textteile zählen.
     */
    private fun contentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.joinToString("") { part -> textPart(part) }
        else -> ""
    }

    private fun textPart(part: JsonElement): String {
        val fields = part as? JsonObject ?: return ""
        val type = fields.text("type")
        return if (type == null || type == "text") fields.text("text").orEmpty() else ""
    }

    private companion object {
        /** Der Dienst hat die Antwort aus inhaltlichen Gründen zurückgehalten. */
        const val FILTERED = "content_filter"

        /** Die Antwort wurde abgeschnitten, weil die Obergrenze für ihre Länge erreicht war. */
        const val TRUNCATED = "length"
    }
}

private const val THINK_END = "</think>"

/** Eine Zwischenüberlegung von `<think>` bis `</think>`; fehlt das Ende, reicht sie bis zum Schluss des Textes. */
private val THINK_BLOCK =
    Regex("""<think>.*?(?:</think>|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * Entfernt die Zwischenüberlegungen eines Modells, die als `<think>…</think>` mitten in der Antwort stehen, und
 * den Leerraum, der dabei an den Rändern übrig bleibt.
 *
 * Zwei Sonderfälle: Bricht die Antwort mitten in einer Überlegung ab, gehört der ganze Rest dazu. Steht nur das
 * Ende `</think>` da (manche Dienste verschlucken den Anfang), gehört alles davor dazu.
 */
internal fun withoutThinking(answer: String): String {
    val visible = THINK_BLOCK.replace(answer, "")
    val strayEnd = visible.lastIndexOf(THINK_END, ignoreCase = true)
    return (if (strayEnd < 0) visible else visible.substring(strayEnd + THINK_END.length)).trim()
}

/*
 * Die Teile der Antwort eines OpenAI-kompatiblen Dienstes, die die App liest. Alle übrigen Felder werden überlesen.
 */

@Serializable
private class ChatResponse(val choices: List<ChatChoice> = emptyList(), val model: String? = null)

/** Ein Antwortvorschlag; die App verwendet nur den ersten. */
@Serializable
private class ChatChoice(
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** [content] ist ein Text oder eine Liste von Teilen, siehe `contentText`. */
@Serializable
private class ChatMessage(val content: JsonElement? = null)
