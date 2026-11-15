package io.github.q0ochen.schafkopf.data.llm

import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class OpenAiCompatibleTextGeneratorTest {

    private val server = MockWebServer()
    private val pause = RecordingWait()

    @BeforeEach
    fun startServer() {
        server.startLocal()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun generator(
        apiKey: String? = TEST_KEY,
        model: String = "modell-x",
        baseUrl: String = server.baseUrl + "/v1",
        client: OkHttpClient = TestHttp.client,
    ) = OpenAiCompatibleTextGenerator(client, apiKey, model, baseUrl, wait = pause.function)

    /** Eine Antwort in der Form, in der OpenAI-kompatible Dienste sie schicken. */
    private fun chatAnswer(content: String, finishReason: String = "stop"): String =
        """{"id":"chatcmpl-1","object":"chat.completion","created":1794700000,"model":"modell-x-2026",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(content)},"refusal":null},""" +
            """"logprobs":null,"finish_reason":"$finishReason"}],""" +
            """"usage":{"prompt_tokens":120,"completion_tokens":40,"total_tokens":160},"system_fingerprint":"fp_test"}"""

    /** Eine Fehlermeldung in der Form, in der OpenAI sie schickt. */
    private fun errorBody(type: String, code: String?): String =
        """{"error":{"message":"Erfundener Fehlertext.","type":"$type","param":null,"code":${code?.let { "\"$it\"" }}}}"""

    private fun assertNothingRepeated(expectedRequests: Int) {
        assertEquals(expectedRequests, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    // ---------- Anfrage ----------

    @Test
    fun `generate schickt Anweisung und Fakten als POST an chat completions`() = runTest {
        server.enqueueJson(200, chatAnswer("Servus beinand"))

        val result = generator().generate("Du bist der Chronist", "Fakten des Abends")

        assertEquals(LlmResult.Success(GeneratedText("Servus beinand", "modell-x-2026")), result)
        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.target)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(
            """{"model":"modell-x","messages":[{"role":"system","content":"Du bist der Chronist"},""" +
                """{"role":"user","content":"Fakten des Abends"}]}""",
            request.body?.utf8(),
        )
        assertNothingRepeated(expectedRequests = 1)
    }

    @Test
    fun `ohne Schluessel wird keine Authorization-Kopfzeile gesendet`() = runTest {
        for (missing in listOf(null, "", "   ")) {
            server.enqueueJson(200, chatAnswer("Servus"))
            server.enqueueJson(200, """{"object":"list","data":[{"id":"modell-x","object":"model"}]}""")
            val generator = generator(apiKey = missing)

            assertEquals(LlmResult.Success(GeneratedText("Servus", "modell-x-2026")), generator.generate("Anweisung", "Fakten"))
            assertEquals(LlmResult.Success(listOf("modell-x")), generator.listModels())

            assertNull(server.nextRequest().headers["Authorization"])
            assertNull(server.nextRequest().headers["Authorization"])
        }
    }

    @Test
    fun `mit Schluessel bekommt auch die Modell-Liste die Authorization-Kopfzeile`() = runTest {
        server.enqueueJson(200, """{"object":"list","data":[]}""")

        // Leerraum um den Schlüssel (vom Einfügen) wird entfernt.
        generator(apiKey = " $TEST_KEY\n").listModels()

        val request = server.nextRequest()
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertFalse(request.url.toString().contains(TEST_KEY))
    }

    @Test
    fun `Sonderzeichen in Anweisung und Fakten kommen unveraendert an`() = runTest {
        server.enqueueJson(200, chatAnswer("Servus"))
        val system = "Zeile 1\nZeile 2 mit \"Anführungszeichen\" und Rückstrich \\"
        val facts = """{"datum":"14.11.2026","spieler":["[[S1]]","[[S2]]"],"stand":"−4,20 €"}"""

        generator(model = "qwen3:8b").generate(system, facts)

        val body = Json.parseToJsonElement(checkNotNull(server.nextRequest().body).utf8()).jsonObject
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("qwen3:8b", body.getValue("model").jsonPrimitive.content)
        assertEquals(listOf("system", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(listOf(system, facts), messages.map { it.getValue("content").jsonPrimitive.content })
    }

    @Test
    fun `die Aufrufe werden an die Basis-Adresse angehaengt, mit und ohne Pfad`() = runTest {
        repeat(2) {
            server.enqueueJson(200, chatAnswer("Servus"))
            server.enqueueJson(200, """{"data":[]}""")
        }
        val withPath = generator(baseUrl = server.baseUrl + "/api/v1/")
        val withoutPath = generator(baseUrl = server.baseUrl)

        withPath.generate("Anweisung", "Fakten")
        withPath.listModels()
        withoutPath.generate("Anweisung", "Fakten")
        withoutPath.listModels()

        assertEquals(
            listOf("POST /api/v1/chat/completions", "GET /api/v1/models", "POST /chat/completions", "GET /models"),
            List(4) { server.nextRequest() }.map { "${it.method} ${it.target}" },
        )
    }

    // ---------- Antwort ----------

    @Test
    fun `ein think-Block wird aus der Antwort entfernt`() = runTest {
        server.enqueueJson(200, chatAnswer("<think>\nErst die Rangliste ansehen, dann frotzeln.\n</think>\n\nServus beinand"))

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Success(GeneratedText("Servus beinand", "modell-x-2026")), result)
    }

    @Test
    fun `Zwischenueberlegungen werden entfernt, wo auch immer sie stehen`() {
        assertEquals("Servus", withoutThinking("Servus"))
        assertEquals("Servus", withoutThinking("  \nServus\n\n"))
        assertEquals("Servus beinand", withoutThinking("<think>Überlegung</think>Servus beinand"))
        assertEquals("Servus beinand", withoutThinking("<think>\nZeile 1\nZeile 2\n</think>\n\nServus beinand\n"))
        // Mehrere Blöcke, auch mitten im Text und in Großbuchstaben.
        assertEquals("Servus beinand!", withoutThinking("<think>a</think>Servus <THINK>b</THINK>beinand<think></think>!"))
        // Die Antwort bricht mitten in der Überlegung ab: Der Rest gehört dazu.
        assertEquals("Servus", withoutThinking("Servus <think>und jetzt noch"))
        assertEquals("", withoutThinking("<think>nur eine Überlegung, die nie endet"))
        // Nur das Ende der Überlegung ist da: Alles davor gehört dazu.
        assertEquals("Servus beinand", withoutThinking("Erst die Rangliste ansehen.</think>\n\nServus beinand"))
        assertEquals("", withoutThinking("<think>nur eine Überlegung</think>"))
        assertEquals("", withoutThinking(""))
        // Spitze Klammern, die keine Überlegung einleiten, bleiben stehen.
        assertEquals("3 < 4 und <thinking> bleibt", withoutThinking("3 < 4 und <thinking> bleibt"))
    }

    @Test
    fun `nennt die Antwort kein Modell, gilt das angefragte`() = runTest {
        server.enqueueJson(200, """{"choices":[{"message":{"role":"assistant","content":"Servus"},"finish_reason":"stop"}]}""")
        server.enqueueJson(200, """{"model":"  ","choices":[{"message":{"content":"Servus"}}]}""")

        repeat(2) {
            assertEquals(LlmResult.Success(GeneratedText("Servus", "modell-x")), generator().generate("Anweisung", "Fakten"))
        }
    }

    @Test
    fun `ein Text aus mehreren Teilen wird zusammengesetzt`() = runTest {
        // Manche Dienste schicken statt eines Textes eine Liste von Teilen; nur die Textteile zählen.
        server.enqueueJson(
            200,
            """
            {
              "model": "modell-x-2026",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": [
                      { "type": "thinking", "thinking": [ { "type": "text", "text": "Erst die Rangliste." } ] },
                      { "type": "text", "text": "Servus " },
                      { "type": "image_url", "image_url": { "url": "https://example.com/bild.png" } },
                      { "text": "beinand" },
                      "kein Objekt"
                    ]
                  },
                  "finish_reason": "stop"
                }
              ]
            }
            """.trimIndent(),
        )

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Success(GeneratedText("Servus beinand", "modell-x-2026")), result)
    }

    @Test
    fun `eine Antwort ohne Text gilt als unbrauchbar`() = runTest {
        val empties = listOf(
            "{}" to "HTTP 200",
            """{"choices":[]}""" to "HTTP 200",
            """{"choices":null,"model":null}""" to "HTTP 200",
            """{"choices":[{"index":0}]}""" to "HTTP 200",
            """{"choices":[{"message":null,"finish_reason":null}]}""" to "HTTP 200",
            chatAnswer("") to "HTTP 200 stop",
            chatAnswer(" \n ") to "HTTP 200 stop",
            // Nur eine Zwischenüberlegung, keine Antwort.
            chatAnswer("<think>Ich überlege noch.</think>") to "HTTP 200 stop",
            // Statt eines Textes will das Modell ein Werkzeug aufrufen.
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"aufruf-1"}]},"finish_reason":"tool_calls"}]}""" to
                "HTTP 200 tool_calls",
            // Eine Zahl, wo sonst Text steht.
            """{"choices":[{"message":{"content":42},"finish_reason":"stop"}]}""" to "HTTP 200 stop",
            // Ein Fehler, verpackt in eine angenommene Anfrage.
            """{"error":{"message":"Erfundener Fehlertext.","code":502}}""" to "HTTP 200",
        )
        for ((body, detail) in empties) {
            server.enqueueJson(200, body)
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED, detail), result, body)
        }
        assertNothingRepeated(expectedRequests = empties.size)
    }

    @Test
    fun `eine abgeschnittene Antwort gilt als unbrauchbar, eine zurueckgehaltene als blockiert`() = runTest {
        server.enqueueJson(200, chatAnswer("Schafkopf am 14.11.2026, da hat", finishReason = "length"))
        server.enqueueJson(200, chatAnswer("", finishReason = "length"))
        server.enqueueJson(200, chatAnswer("", finishReason = "content_filter"))
        server.enqueueJson(200, chatAnswer("Schafkopf am", finishReason = "content_filter"))

        repeat(2) {
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED, "HTTP 200 length"), result)
        }
        repeat(2) {
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.BLOCKED, "HTTP 200 content_filter"), result)
        }
        assertNothingRepeated(expectedRequests = 4)
    }

    @Test
    fun `ein unbekannter Abschlussgrund mit Text ist ein Erfolg`() = runTest {
        server.enqueueJson(200, chatAnswer("Servus", finishReason = "ganz_neuer_grund"))

        assertEquals(LlmResult.Success(GeneratedText("Servus", "modell-x-2026")), generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `eine angenommene Anfrage mit unlesbarer Antwort ist ein unbekannter Fehler`() = runTest {
        val unreadable = listOf(
            "",
            "Servus",
            "<html><body>Hier läuft etwas anderes</body></html>",
            "{ kaputt",
            "[1,2,3]",
            """{"choices":"keine Liste"}""",
        )
        for (body in unreadable) {
            server.enqueueHtml(200, body)
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().generate("Anweisung", "Fakten"), body)
        }
        assertNothingRepeated(expectedRequests = unreadable.size)
    }

    @Test
    fun `ein absurd tief verschachtelter Text bringt nichts zum Absturz`() = runTest {
        // So tiefe Verschachtelungen lehnt die JSON-Bibliothek ab: Die Antwort gilt als unlesbar.
        val depth = 100_000
        server.enqueueJson(200, """{"choices":[{"message":{"content":""" + "[".repeat(depth) + "]".repeat(depth) + "}}]}")
        // Eine Liste in einer Liste lässt sich lesen, enthält aber keinen Text.
        server.enqueueJson(200, """{"choices":[{"message":{"content":[[[{"type":"text","text":"Servus"}]]]}}]}""")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED, "HTTP 200"), generator().generate("Anweisung", "Fakten"))
    }

    // ---------- Fehler ohne Netzaufruf ----------

    @Test
    fun `eine unzulaessige Adresse wird abgelehnt, ohne dass etwas gesendet wird`() = runTest {
        val seen = CopyOnWriteArrayList<Request>()
        val client = offlineClient(seen)
        val addresses = listOf(
            "http://api.example.com/v1",
            "http://127.0.0.1.example.com:11434/v1",
            "http://localhost.example.com/v1",
            "http://nutzer@localhost:11434/v1",
            "https://nutzer:geheim@api.example.com/v1",
            "localhost:11434",
            "",
        )
        for (address in addresses) {
            val generator = OpenAiCompatibleTextGenerator(client, TEST_KEY, "modell-x", address)
            assertEquals(LlmResult.Failure(LlmError.INVALID_ENDPOINT), generator.generate("Anweisung", "Fakten"), address)
            assertEquals(LlmResult.Failure(LlmError.INVALID_ENDPOINT), generator.listModels(), address)
        }
        assertEquals(emptyList<Request>(), seen.toList())
    }

    @Test
    fun `https ist fuer jeden Dienst erlaubt, http nur im eigenen Netz`() = runTest {
        val seen = CopyOnWriteArrayList<Request>()
        val client = offlineClient(seen, chatAnswer("Servus"))
        val addresses = listOf("https://api.example.com/v1", "http://192.168.1.20:11434/v1/", "http://localhost:1234/v1", "http://[::1]:8080")

        for (address in addresses) {
            val result = OpenAiCompatibleTextGenerator(client, null, "modell-x", address).generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Success(GeneratedText("Servus", "modell-x-2026")), result, address)
        }

        assertEquals(
            listOf(
                "https://api.example.com/v1/chat/completions",
                "http://192.168.1.20:11434/v1/chat/completions",
                "http://localhost:1234/v1/chat/completions",
                "http://[::1]:8080/chat/completions",
            ),
            seen.map { it.url.toString() },
        )
    }

    @Test
    fun `ein Schluessel mit unzulaessigen Zeichen gilt als ungueltig und wird nicht gesendet`() = runTest {
        for (key in listOf("schlüssel-mit-umlaut", "zwei teile", "zeile1\nzeile2")) {
            val generator = generator(apiKey = key)
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.generate("Anweisung", "Fakten"))
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.listModels())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ohne Modellname wird nichts gesendet, die Modell-Liste geht trotzdem`() = runTest {
        server.enqueueJson(200, """{"object":"list","data":[{"id":"modell-x"}]}""")
        val generator = generator(model = "")

        assertEquals(LlmResult.Failure(LlmError.MODEL_UNAVAILABLE), generator.generate("Anweisung", "Fakten"))
        assertEquals(0, server.requestCount)

        assertEquals(LlmResult.Success(listOf("modell-x")), generator.listModels())
        assertEquals(1, server.requestCount)
    }

    // ---------- Abgelehnte Anfragen ----------

    @Test
    fun `jede abgelehnte Anfrage wird der passenden Fehlerart zugeordnet`() = runTest {
        val cases = listOf(
            Triple(401, errorBody("invalid_request_error", "invalid_api_key"), LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 401 invalid_api_key")),
            Triple(
                403,
                errorBody("request_forbidden", "unsupported_country_region_territory"),
                LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403 unsupported_country_region_territory"),
            ),
            Triple(404, errorBody("invalid_request_error", "model_not_found"), LlmResult.Failure(LlmError.MODEL_UNAVAILABLE, "HTTP 404 model_not_found")),
            // Alles andere unterhalb von 500 ist ein unbekannter Fehler.
            Triple(400, errorBody("invalid_request_error", null), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 invalid_request_error")),
            Triple(402, """{"error":{"code":402,"message":"Erfundener Fehlertext."}}""", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 402")),
            Triple(422, """{"detail":[{"msg":"Erfundener Fehlertext."}]}""", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 422")),
            // Jeder Status ab 500 heißt: Der Dienst ist gerade nicht erreichbar. Wiederholt wird hier nicht.
            Triple(501, errorBody("server_error", null), LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 501 server_error")),
            Triple(507, "", LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 507")),
            Triple(599, """{"error":"Erfundener Fehlertext."}""", LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 599")),
        )
        for ((status, body, expected) in cases) {
            server.enqueueJson(status, body)
            assertEquals(expected, generator().generate("Anweisung", "Fakten"), "HTTP $status mit $body")
            server.enqueueJson(status, body)
            assertEquals(expected, generator().listModels(), "HTTP $status mit $body")
        }
        assertNothingRepeated(expectedRequests = 2 * cases.size)
    }

    @Test
    fun `Ueberlastung und erschoepftes Kontingent werden nach genau einem weiteren Versuch gemeldet`() = runTest {
        val cases = listOf(
            Triple(429, "insufficient_quota", LlmError.QUOTA_EXCEEDED),
            Triple(500, "server_error", LlmError.SERVER_UNAVAILABLE),
            Triple(502, "bad_gateway", LlmError.SERVER_UNAVAILABLE),
            Triple(503, "overloaded", LlmError.SERVER_UNAVAILABLE),
            Triple(504, "timeout", LlmError.SERVER_UNAVAILABLE),
        )
        for ((index, case) in cases.withIndex()) {
            val (status, code, expected) = case
            // Genau zwei Antworten stehen bereit: die auf den ersten und die auf den zweiten Versuch.
            server.enqueueJson(status, errorBody("server_error", "erster_versuch"))
            server.enqueueJson(status, errorBody("server_error", code))

            val result = generator().generate("Anweisung", "Fakten")

            // Gemeldet wird die Antwort auf den zweiten Versuch.
            assertEquals(LlmResult.Failure(expected, "HTTP $status $code"), result)
            assertEquals(2 * (index + 1), server.requestCount)
            assertEquals(List(index + 1) { 2000L }, pause.waits.toList())
        }
    }

    @Test
    fun `bei 503 folgt nach zwei Sekunden ein zweiter Versuch, der gelingen kann`() = runTest {
        server.enqueueHtml(503, "Service Unavailable")
        server.enqueueJson(200, chatAnswer("Servus"))
        server.enqueueJson(503, """{"error":"server busy"}""")
        server.enqueueJson(200, """{"data":[{"id":"modell-x"}]}""")

        assertEquals(LlmResult.Success(GeneratedText("Servus", "modell-x-2026")), generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Success(listOf("modell-x")), generator().listModels())

        assertEquals(listOf(2000L, 2000L), pause.waits.toList())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `bei 400 gibt es keinen zweiten Versuch`() = runTest {
        server.enqueueJson(400, errorBody("invalid_request_error", null))
        server.enqueueJson(200, chatAnswer("Diese Antwort darf niemand abholen"))

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 invalid_request_error"), result)
        assertNothingRepeated(expectedRequests = 1)
    }

    @Test
    fun `reisst die Verbindung ab, ist das ein Netzfehler ohne zweiten Versuch`() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }

        val results = listOf(generator().generate("Anweisung", "Fakten"), generator().listModels())

        for (result in results) {
            assertTrue(result is LlmResult.Failure && result.error == LlmError.NETWORK, "$result")
        }
        assertNothingRepeated(expectedRequests = 2)
    }

    @Test
    fun `der Schluessel steht in keinem Zusatz, auch wenn der Dienst ihn zurueckschickt`() = runTest {
        val echoes = listOf(
            Triple(401, """{"error":{"message":"Incorrect API key provided: $TEST_KEY.","type":"$TEST_KEY","code":"$TEST_KEY"}}""", "HTTP 401"),
            // Das erste Kürzel enthält den Schlüssel, das zweite ist brauchbar.
            Triple(
                401,
                """{"error":{"message":"$TEST_KEY","type":"invalid_request_error","code":"key_${TEST_KEY.lowercase()}"}}""",
                "HTTP 401 invalid_request_error",
            ),
            Triple(403, """{"error":"$TEST_KEY"}""", "HTTP 403"),
            Triple(400, TEST_KEY, "HTTP 400"),
            Triple(200, chatAnswer("", finishReason = TEST_KEY), "HTTP 200"),
            Triple(200, TEST_KEY, "HTTP 200"),
        )
        for ((status, body, detail) in echoes) {
            // Einmal als Antwort auf generate, einmal auf listModels.
            repeat(2) { server.enqueueJson(status, body) }
            val failures = listOf(generator().generate("Anweisung", "Fakten"), generator().listModels())
                .filterIsInstance<LlmResult.Failure>()

            assertEquals(2, failures.size, body)
            for (failure in failures) {
                assertFalse(failure.detail.contains(TEST_KEY, ignoreCase = true), failure.detail)
            }
            assertEquals(detail, failures.first().detail, body)
        }
    }

    @Test
    fun `der Zusatz uebernimmt nur schlichte Kuerzel`() = runTest {
        val cases = listOf(
            // Ein Kürzel mit Leerzeichen taugt nicht; dann zählt das nächste.
            """{"error":{"message":"x","type":"invalid_request_error","code":"model not found"}}""" to "HTTP 400 invalid_request_error",
            """{"error":{"message":"x","type":"Bitte später <b>noch einmal</b>","code":""}}""" to "HTTP 400",
            // Zahlen sind keine Kürzel.
            """{"error":{"message":"x","type":7,"code":400}}""" to "HTTP 400",
            """[{"error":{"message":"x","type":"api_error","code":"rate.limit-1"}}]""" to "HTTP 400 rate.limit-1",
        )
        for ((body, detail) in cases) {
            server.enqueueJson(400, body)
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, detail), generator().generate("Anweisung", "Fakten"), body)
        }
    }

    @Test
    fun `toString verraet den Schluessel nicht`() {
        val text = generator().toString()

        assertEquals("OpenAiCompatibleTextGenerator(model=modell-x, host=127.0.0.1)", text)
        assertFalse(text.contains(TEST_KEY))
    }

    // ---------- Modell-Liste ----------

    @Test
    fun `listModels liefert die Namen der Modelle alphabetisch`() = runTest {
        server.enqueueJson(
            200,
            """
            {
              "object": "list",
              "data": [
                { "id": "qwen3:8b", "object": "model", "created": 1794700000, "owned_by": "library" },
                { "id": "Llama3.2:latest", "object": "model", "created": 1794700000, "owned_by": "library" },
                { "id": "gemma3:4b", "object": "model" },
                { "id": "gemma3:4b", "object": "model" },
                { "object": "model" },
                { "id": "" },
                { "id": 7 },
                "kein Objekt"
              ]
            }
            """.trimIndent(),
        )

        val result = generator().listModels()

        // Ohne Dubletten und Leereinträge; Groß- und Kleinschreibung spielt für die Reihenfolge keine Rolle.
        assertEquals(LlmResult.Success(listOf("gemma3:4b", "Llama3.2:latest", "qwen3:8b")), result)
        val request = server.nextRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.target)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertNothingRepeated(expectedRequests = 1)
    }

    @Test
    fun `listModels versteht auch eine Liste ohne Huelle`() = runTest {
        server.enqueueJson(200, """[{"id":"modell-b","object":"model"},{"id":"modell-a","object":"model"}]""")
        server.enqueueJson(200, "[]")

        assertEquals(LlmResult.Success(listOf("modell-a", "modell-b")), generator().listModels())
        assertEquals(LlmResult.Success(emptyList<String>()), generator().listModels())
    }

    @Test
    fun `ist die Antwort keine Modell-Liste, ist das ein unbekannter Fehler`() = runTest {
        val unreadable = listOf(
            "",
            "<html><body>Hier läuft etwas anderes</body></html>",
            "{ kaputt",
            """{"object":"list"}""",
            """{"data":"keine Liste"}""",
            """{"models":[{"name":"modell-x"}]}""",
            "\"modell-x\"",
        )
        for (body in unreadable) {
            server.enqueueHtml(200, body)
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), generator().listModels(), body)
        }
        assertNothingRepeated(expectedRequests = unreadable.size)
    }
}
