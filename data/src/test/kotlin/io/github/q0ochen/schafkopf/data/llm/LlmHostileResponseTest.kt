package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Antworten aus fremder Hand, geprüft an beiden Anbindungen: Was auch immer ein Dienst schickt, heraus kommt ein
 * Ergebnis und nie eine Ausnahme, und der Schlüssel steht in keinem Fehler.
 *
 * Die Tests brauchen kein Netz: Der HTTP-Client beantwortet jede Anfrage selbst mit [status] und [body].
 */
class LlmHostileResponseTest {

    @Volatile
    private var status = 200

    @Volatile
    private var body = "{}"

    private val client: OkHttpClient = TestHttp.client.newBuilder()
        .addInterceptor(
            interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("Nachgestellt")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    // Ohne Wechsel des Threads und ohne Wartezeit vor dem zweiten Versuch: So laufen viele Aufrufe in kurzer Zeit.
    private val gemini = GeminiTextGenerator(client, TEST_KEY, "gemini-test", "https://dienst.example", Dispatchers.Unconfined) { }
    private val openAi = OpenAiCompatibleTextGenerator(client, TEST_KEY, "modell-x", "https://dienst.example/v1", Dispatchers.Unconfined) { }

    /** Dieselbe Antwort des Dienstes auf alle vier Aufrufe. */
    private suspend fun results(): List<LlmResult<*>> = listOf(
        gemini.generate("Anweisung", "Fakten"),
        gemini.listModels(),
        openAi.generate("Anweisung", "Fakten"),
        openAi.listModels(),
    )

    @Test
    fun `ein nackter JSON-Wert statt einer Antwort ist unlesbar`() = runTest {
        val values = listOf("null", "true", "false", "0", "-1.5e3", "\"\"", "\"Servus\"", " ", Char(0).toString())
        for (value in values) {
            body = value

            status = 200
            assertEquals(List(4) { LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200") }, results(), value)
            // Bei einer abgelehnten Anfrage zählt dann allein der HTTP-Status.
            status = 403
            assertEquals(List(4) { LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403") }, results(), value)
        }
    }

    /** Echte Antworten beider Dienste, aus denen die verstümmelten entstehen. */
    private val seeds = listOf(
        """{"candidates":[{"content":{"parts":[{"text":"Servus","thought":false},{"text":"x","thought":true}],"role":"model"},""" +
            """"finishReason":"STOP","index":0}],"promptFeedback":{"blockReason":"SAFETY"},"modelVersion":"gemini-test-001",""" +
            """"usageMetadata":{"a":[1,2,{"b":null}]}}""",
        """{"error":{"code":400,"message":"x","status":"INVALID_ARGUMENT","details":[{"@type":"t","reason":"API_KEY_INVALID"}]}}""",
        """[{"error":{"code":403,"status":"PERMISSION_DENIED"}}]""",
        """{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/b"}],""" +
            """"nextPageToken":""}""",
        """{"id":"c","model":"modell-x-2026","choices":[{"index":0,"message":{"role":"assistant",""" +
            """"content":"<think>a</think>Servus","refusal":null},"finish_reason":"stop"}],"usage":{"p":1}}""",
        """{"model":"m","choices":[{"message":{"content":[{"type":"text","text":"Servus "},{"text":"b"},""" +
            """{"type":"image_url","image_url":{"url":"u"}},7]},"finish_reason":"length"}]}""",
        """{"error":{"message":"x","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}""",
        """{"object":"list","data":[{"id":"a","object":"model"},{"id":"B"},{"id":7},"x"]}""",
    )

    /** Bruchstücke, die in die Antworten gestreut werden: JSON-Zeichen, halbe Wörter, Sonderzeichen, der Schlüssel. */
    private val pieces = listOf(
        "{", "}", "[", "]", "\"", ":", ",", "null", "true", "false", "1e999", "-0", "\\u", "\\ud800", "\\", "tru", "nul",
        "99999999999999999999999999", "1E+", "0x10", "NaN", "'", "/*", "//", "\n", "\t", " ", "ä", "\"\"", "{}", "[]", "\\\"",
        Char(0).toString(), Char(0x1F).toString(), Char(0xFEFF).toString(), Char(0xD800).toString(), "<think>", "</think>",
        "\"thought\":", "\"text\":", "\"content\":", "\"error\":", "\"choices\":", "\"candidates\":", "\"parts\":", "\"data\":",
        "\"models\":", "\"finishReason\":", "\"finish_reason\":", "\"status\":", "\"code\":", "\"type\":", "\"blockReason\":",
        TEST_KEY, "\"$TEST_KEY\"", "\"status\":\"$TEST_KEY\",", "\"code\":\"$TEST_KEY\",", "\"finishReason\":\"$TEST_KEY\",",
    )

    /** Verstümmelt eine Antwort an ein bis vier Stellen: abschneiden, löschen, verdoppeln, Bruchstücke einstreuen. */
    private fun mutate(random: Random, seed: String): String {
        var text = seed
        repeat(random.nextInt(1, 5)) {
            val at = random.nextInt(text.length + 1)
            val end = (at + random.nextInt(1, 30)).coerceAtMost(text.length)
            text = when (random.nextInt(6)) {
                0 -> text.substring(0, at)
                1 -> text.substring(at)
                2 -> text.substring(0, at) + text.substring(end)
                3 -> text.substring(0, end) + text.substring(at)
                4 -> text.substring(0, at) + pieces.random(random) + text.substring(end)
                else -> text.substring(0, at) + pieces.random(random).repeat(random.nextInt(1, 40)) + text.substring(at)
            }
        }
        return text
    }

    @Test
    fun `verstuemmelte Antworten ergeben immer ein Ergebnis und nie eine Ausnahme`() = runTest {
        // Immer dieselbe Folge von „Zufallszahlen“: Der Test läuft jedes Mal gleich ab.
        val random = Random(4711)
        val statuses = listOf(200, 200, 200, 201, 302, 400, 401, 403, 404, 429, 500)
        // Der Zusatz ist „HTTP 403“ oder „HTTP 403 KUERZEL“, nie der Name einer Ausnahme und nie ein freier Text.
        val plainDetail = Regex("""HTTP \d{3}( [A-Za-z0-9_.-]+)?""")
        var successes = 0
        repeat(5_000) {
            body = mutate(random, seeds.random(random))
            status = statuses.random(random)

            // Eine Ausnahme aus einem der vier Aufrufe ließe den Test hier scheitern.
            for (result in results()) {
                when (result) {
                    is LlmResult.Success -> successes++
                    is LlmResult.Failure -> {
                        assertTrue(plainDetail.matches(result.detail)) { "Zusatz „${result.detail}“ bei HTTP $status mit $body" }
                        assertTrue(result.detail.length <= MAX_DETAIL_LENGTH) { "Zusatz zu lang bei HTTP $status mit $body" }
                        assertFalse(result.detail.contains(TEST_KEY, ignoreCase = true)) { "Schlüssel im Zusatz bei HTTP $status mit $body" }
                    }
                }
            }
        }
        // Nicht alles wurde unlesbar: Auch leicht beschädigte, noch brauchbare Antworten waren dabei.
        assertTrue(successes > 100, "nur $successes brauchbare Antworten")
    }
}
