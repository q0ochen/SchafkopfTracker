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
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class GeminiTextGeneratorTest {

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
        model: String = "gemini-test",
        baseUrl: String = server.baseUrl,
        client: OkHttpClient = TestHttp.client,
    ) = GeminiTextGenerator(client, apiKey, model, baseUrl, wait = pause.function)

    /** Eine Antwort mit einem einzigen Textteil. */
    private fun textAnswer(text: String, finishReason: String = "STOP"): String =
        """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(text)}}],"role":"model"},""" +
            """"finishReason":"$finishReason","index":0}],"modelVersion":"gemini-test-001"}"""

    /** Eine Fehlermeldung in der Form, in der Google sie schickt. */
    private fun errorBody(code: Int, status: String): String =
        """{"error":{"code":$code,"message":"Erfundener Fehlertext.","status":"$status"}}"""

    /** Ein Eintrag der Modell-Liste. */
    private fun model(name: String, vararg methods: String): String =
        """{"name":"$name","displayName":"Testmodell","supportedGenerationMethods":[${methods.joinToString(",") { "\"$it\"" }}]}"""

    private fun assertNothingRepeated(expectedRequests: Int) {
        assertEquals(expectedRequests, server.requestCount)
        assertEquals(emptyList<Long>(), pause.waits.toList())
    }

    // ---------- Anfrage ----------

    @Test
    fun `generate schickt Anweisung und Fakten als POST an generateContent`() = runTest {
        server.enqueueJson(200, textAnswer("Servus beinand"))

        generator().generate("Du bist der Chronist", "Fakten des Abends")

        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1beta/models/gemini-test:generateContent", request.target)
        assertEquals("application/json", request.headers["Content-Type"])
        // Genau diese Felder und keine weiteren: kein temperature, topP, topK und keine safetySettings.
        assertEquals(
            """{"systemInstruction":{"parts":[{"text":"Du bist der Chronist"}]},""" +
                """"contents":[{"role":"user","parts":[{"text":"Fakten des Abends"}]}],""" +
                """"generationConfig":{"maxOutputTokens":4096}}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `der Schluessel steht in der Kopfzeile x-goog-api-key und nie in der Adresse`() = runTest {
        server.enqueueJson(200, textAnswer("Servus"))
        server.enqueueJson(200, """{"models":[]}""")

        generator().generate("Anweisung", "Fakten")
        generator().listModels()

        repeat(2) {
            val request = server.nextRequest()
            assertEquals(TEST_KEY, request.headers["x-goog-api-key"])
            assertFalse(request.url.toString().contains(TEST_KEY), request.url.toString())
            assertFalse(request.target.contains("key", ignoreCase = true), request.target)
        }
    }

    @Test
    fun `Sonderzeichen in Anweisung und Fakten kommen unveraendert an`() = runTest {
        server.enqueueJson(200, textAnswer("Servus"))
        val system = "Zeile 1\nZeile 2 mit \"Anführungszeichen\", Rückstrich \\ und Tabulator\t."
        val facts = """{"datum":"14.11.2026","spieler":["[[S1]]","[[S2]]"],"stand":"−4,20 €"}"""

        generator().generate(system, facts)

        val body = Json.parseToJsonElement(checkNotNull(server.nextRequest().body).utf8()).jsonObject
        val sentSystem = body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray[0].jsonObject
        val sentContent = body.getValue("contents").jsonArray[0].jsonObject
        assertEquals(system, sentSystem.getValue("text").jsonPrimitive.content)
        assertEquals("user", sentContent.getValue("role").jsonPrimitive.content)
        assertEquals(facts, sentContent.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `ohne eigene Adresse geht die Anfrage an die Adresse von Google`() = runTest {
        val seen = CopyOnWriteArrayList<Request>()
        val generator = GeminiTextGenerator(offlineClient(seen, textAnswer("Servus")), TEST_KEY, "gemini-test")

        val result = generator.generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Success(GeneratedText("Servus", "gemini-test-001")), result)
        assertEquals("https://generativelanguage.googleapis.com", GeminiTextGenerator.DEFAULT_BASE_URL)
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"),
            seen.map { it.url.toString() },
        )
    }

    @Test
    fun `ein Modellname mit dem Praefix models wird ohne das Praefix verwendet`() = runTest {
        server.enqueueJson(200, """{"candidates":[{"content":{"parts":[{"text":"Servus"}]},"finishReason":"STOP"}]}""")

        val result = generator(model = " models/gemini-test ").generate("Anweisung", "Fakten")

        assertEquals("/v1beta/models/gemini-test:generateContent", server.nextRequest().target)
        // Nennt die Antwort kein Modell, gilt das angefragte.
        assertEquals(LlmResult.Success(GeneratedText("Servus", "gemini-test")), result)
    }

    @Test
    fun `ein Modellname mit Sonderzeichen kann die Adresse nicht veraendern`() = runTest {
        server.enqueueJson(200, textAnswer("Servus"))

        generator(model = "x/../y?key=1#z mit leer").generate("Anweisung", "Fakten")

        // Schrägstrich, Fragezeichen, Raute und Leerzeichen werden verschlüsselt: Der Name bleibt ein einziges
        // Stück des Pfads und kann weder den Pfad wechseln noch eine Abfrage anhängen.
        val request = server.nextRequest()
        assertEquals("/v1beta/models/x%2F..%2Fy%3Fkey=1%23z%20mit%20leer:generateContent", request.target)
        assertNull(request.url.query)
    }

    @Test
    fun `eine Basis-Adresse mit Pfad und Schraegstrich am Ende wird richtig verlaengert`() = runTest {
        server.enqueueJson(200, textAnswer("Servus"))
        server.enqueueJson(200, """{"models":[]}""")
        val generator = generator(baseUrl = server.baseUrl + "/umleitung/gemini/")

        generator.generate("Anweisung", "Fakten")
        generator.listModels()

        assertEquals("/umleitung/gemini/v1beta/models/gemini-test:generateContent", server.nextRequest().target)
        assertEquals("/umleitung/gemini/v1beta/models?pageSize=1000", server.nextRequest().target)
    }

    // ---------- Antwort ----------

    @Test
    fun `aus einer vollstaendigen Antwort werden Text und Modell gelesen`() = runTest {
        server.enqueueJson(
            200,
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "Schafkopf am 14.11.2026\n\n" },
                      { "text": "[[S3]] hat heute alle abgeräumt." }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0,
                  "safetyRatings": [
                    { "category": "HARM_CATEGORY_HARASSMENT", "probability": "NEGLIGIBLE" }
                  ]
                },
                {
                  "content": { "parts": [ { "text": "Ein zweiter Vorschlag, der nicht zählt." } ], "role": "model" },
                  "finishReason": "STOP",
                  "index": 1
                }
              ],
              "usageMetadata": {
                "promptTokenCount": 412,
                "candidatesTokenCount": 187,
                "totalTokenCount": 1203,
                "thoughtsTokenCount": 604,
                "promptTokensDetails": [ { "modality": "TEXT", "tokenCount": 412 } ]
              },
              "modelVersion": "gemini-test-001",
              "responseId": "antwort-1"
            }
            """.trimIndent(),
        )

        val result = generator().generate("Anweisung", "Fakten")

        // Der Text ist die Verkettung aller Teile des ersten Vorschlags, das Modell steht in modelVersion.
        assertEquals(
            LlmResult.Success(GeneratedText("Schafkopf am 14.11.2026\n\n[[S3]] hat heute alle abgeräumt.", "gemini-test-001")),
            result,
        )
        assertNothingRepeated(expectedRequests = 1)
    }

    @Test
    fun `Zwischenueberlegungen des Modells gehoeren nicht zum Text`() = runTest {
        server.enqueueJson(
            200,
            """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "Ich sehe mir zuerst die Rangliste an.", "thought": true },
                      { "text": "Servus " },
                      { "thought": true, "text": "Jetzt noch der Zahlmeister." },
                      { "text": "beinand", "thoughtSignature": "c2lnbmF0dXI=" },
                      { "inlineData": { "mimeType": "image/png", "data": "AAAA" } },
                      { "text": "!", "thought": false }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ],
              "modelVersion": "gemini-test-001"
            }
            """.trimIndent(),
        )

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Success(GeneratedText("Servus beinand!", "gemini-test-001")), result)
    }

    @Test
    fun `ein unbekannter Abschlussgrund mit Text ist ein Erfolg`() = runTest {
        server.enqueueJson(200, textAnswer("Servus", finishReason = "GANZ_NEUER_GRUND"))
        // Gar kein Abschlussgrund.
        server.enqueueJson(200, """{"candidates":[{"content":{"parts":[{"text":"Habe die Ehre"}]}}],"modelVersion":"gemini-test-001"}""")
        // Eine Zahl, wo sonst ein Wort steht.
        server.enqueueJson(200, """{"candidates":[{"content":{"parts":[{"text":"Pfiat di"}]},"finishReason":12}],"modelVersion":"gemini-test-001"}""")

        assertEquals(LlmResult.Success(GeneratedText("Servus", "gemini-test-001")), generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Success(GeneratedText("Habe die Ehre", "gemini-test-001")), generator().generate("Anweisung", "Fakten"))
        assertEquals(LlmResult.Success(GeneratedText("Pfiat di", "gemini-test-001")), generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `eine blockierte Anfrage ohne Antwortvorschlag wird als blockiert gemeldet`() = runTest {
        server.enqueueJson(
            200,
            """
            {
              "promptFeedback": {
                "blockReason": "PROHIBITED_CONTENT",
                "safetyRatings": [ { "category": "HARM_CATEGORY_HARASSMENT", "probability": "HIGH" } ]
              },
              "usageMetadata": { "promptTokenCount": 412, "totalTokenCount": 412 },
              "modelVersion": "gemini-test-001"
            }
            """.trimIndent(),
        )

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.BLOCKED, "HTTP 200 PROHIBITED_CONTENT"), result)
        assertNothingRepeated(expectedRequests = 1)
    }

    @Test
    fun `eine zurueckgehaltene Antwort wird als blockiert gemeldet`() = runTest {
        val reasons = listOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")
        for (reason in reasons) {
            // Einmal ganz ohne Text und einmal mit einem Anfang, nach dem der Dienst abgebrochen hat.
            server.enqueueJson(200, """{"candidates":[{"finishReason":"$reason","index":0}],"modelVersion":"gemini-test-001"}""")
            server.enqueueJson(200, textAnswer("Schafkopf am", finishReason = reason))

            repeat(2) {
                val result = generator().generate("Anweisung", "Fakten")
                assertEquals(LlmResult.Failure(LlmError.BLOCKED, "HTTP 200 $reason"), result)
            }
        }
        assertNothingRepeated(expectedRequests = 2 * reasons.size)
    }

    @Test
    fun `eine abgeschnittene Antwort gilt als unbrauchbar, mit und ohne Text`() = runTest {
        // Das Modell hat die ganze erlaubte Länge für Zwischenüberlegungen verbraucht: Es kommt gar kein Text.
        server.enqueueJson(
            200,
            """{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS","index":0}],""" +
                """"usageMetadata":{"thoughtsTokenCount":4093},"modelVersion":"gemini-test-001"}""",
        )
        server.enqueueJson(200, textAnswer("Schafkopf am 14.11.2026, da hat", finishReason = "MAX_TOKENS"))

        repeat(2) {
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED, "HTTP 200 MAX_TOKENS"), result)
        }
        assertNothingRepeated(expectedRequests = 2)
    }

    @Test
    fun `eine Antwort ohne Text gilt als unbrauchbar`() = runTest {
        val empties = listOf(
            "{}" to "HTTP 200",
            """{"candidates":[]}""" to "HTTP 200",
            """{"candidates":[{"index":0}]}""" to "HTTP 200",
            """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""" to "HTTP 200 STOP",
            """{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}]}""" to "HTTP 200 STOP",
            // Nur Leerraum.
            """{"candidates":[{"content":{"parts":[{"text":" \n\t "}]},"finishReason":"STOP"}]}""" to "HTTP 200 STOP",
            // Nur eine Zwischenüberlegung, keine Antwort.
            """{"candidates":[{"content":{"parts":[{"text":"Ich überlege noch.","thought":true}]},"finishReason":"STOP"}]}""" to
                "HTTP 200 STOP",
            // Ein Teil ohne Text, zum Beispiel ein Bild.
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]},"finishReason":"OTHER"}]}""" to
                "HTTP 200 OTHER",
            // Leere Angaben (null) an Stellen, an denen sonst etwas steht.
            """{"candidates":null,"promptFeedback":null,"modelVersion":null}""" to "HTTP 200",
            """{"candidates":[{"content":null,"finishReason":null}]}""" to "HTTP 200",
            """{"candidates":[{"content":{"parts":null}}],"promptFeedback":{"blockReason":null}}""" to "HTTP 200",
        )
        for ((body, detail) in empties) {
            server.enqueueJson(200, body)
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED, detail), result, body)
        }
        assertNothingRepeated(expectedRequests = empties.size)
    }

    @Test
    fun `eine angenommene Anfrage mit unlesbarer Antwort ist ein unbekannter Fehler`() = runTest {
        val unreadable = listOf(
            "",
            "Servus",
            "<html><body>Bitte erst im WLAN anmelden</body></html>",
            "{ kaputt",
            "[1,2,3]",
            "\"nur ein Text\"",
            """{"candidates":"keine Liste"}""",
        )
        for (body in unreadable) {
            server.enqueueHtml(200, body)
            val result = generator().generate("Anweisung", "Fakten")
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200"), result, body)
        }
        assertNothingRepeated(expectedRequests = unreadable.size)
    }

    // ---------- Schlüssel, Modell und Adresse: Fehler ohne Netzaufruf ----------

    @Test
    fun `ohne Schluessel wird nichts gesendet`() = runTest {
        for (missing in listOf(null, "", "   ", "\n")) {
            val generator = generator(apiKey = missing)
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.generate("Anweisung", "Fakten"))
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.listModels())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Leerraum um den Schluessel wird entfernt`() = runTest {
        server.enqueueJson(200, textAnswer("Servus"))

        generator(apiKey = "  $TEST_KEY\r\n").generate("Anweisung", "Fakten")

        assertEquals(TEST_KEY, server.nextRequest().headers["x-goog-api-key"])
    }

    @Test
    fun `ein Schluessel mit unzulaessigen Zeichen gilt als ungueltig und wird nicht gesendet`() = runTest {
        val broken = listOf("schlüssel-mit-umlaut", "zwei teile", "zeile1\nzeile2", "tab\tdrin", "null" + Char(0) + "zeichen")
        for (key in broken) {
            val generator = generator(apiKey = key)
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.generate("Anweisung", "Fakten"))
            assertEquals(LlmResult.Failure(LlmError.INVALID_KEY), generator.listModels())
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ohne Modellname wird nichts gesendet, die Modell-Liste geht trotzdem`() = runTest {
        server.enqueueJson(200, """{"models":[${model("models/gemini-test", "generateContent")}]}""")
        val generator = generator(model = "  ")

        assertEquals(LlmResult.Failure(LlmError.MODEL_UNAVAILABLE), generator.generate("Anweisung", "Fakten"))
        assertEquals(0, server.requestCount)

        assertEquals(LlmResult.Success(listOf("gemini-test")), generator.listModels())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `eine unzulaessige Adresse wird abgelehnt, ohne dass etwas gesendet wird`() = runTest {
        val seen = CopyOnWriteArrayList<Request>()
        val client = offlineClient(seen)
        val addresses = listOf(
            "http://example.com",
            "http://generativelanguage.googleapis.com",
            "http://127.0.0.1.example.com",
            "http://localhost.example.com",
            "https://nutzer:geheim@generativelanguage.googleapis.com",
            "ftp://example.com",
            "generativelanguage.googleapis.com",
            "",
        )
        for (address in addresses) {
            val generator = GeminiTextGenerator(client, TEST_KEY, "gemini-test", address)
            assertEquals(LlmResult.Failure(LlmError.INVALID_ENDPOINT), generator.generate("Anweisung", "Fakten"), address)
            assertEquals(LlmResult.Failure(LlmError.INVALID_ENDPOINT), generator.listModels(), address)
        }
        assertEquals(emptyList<Request>(), seen.toList())
    }

    // ---------- Abgelehnte Anfragen ----------

    @Test
    fun `jede abgelehnte Anfrage wird der passenden Fehlerart zugeordnet`() = runTest {
        val keyInvalid =
            """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com",
                    "metadata": { "service": "generativelanguage.googleapis.com" }
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.LocalizedMessage",
                    "locale": "en-US",
                    "message": "API key not valid. Please pass a valid API key."
                  }
                ]
              }
            }
            """.trimIndent()
        val otherReason =
            """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"FIELD_VIOLATION"},"kein Objekt",{"reason":7}]}}"""
        val cases = listOf(
            Triple(401, errorBody(401, "UNAUTHENTICATED"), LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 401 UNAUTHENTICATED")),
            Triple(400, keyInvalid, LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 400 INVALID_ARGUMENT")),
            Triple(400, errorBody(400, "UNAUTHENTICATED"), LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 400 UNAUTHENTICATED")),
            Triple(400, errorBody(400, "FAILED_PRECONDITION"), LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 400 FAILED_PRECONDITION")),
            Triple(403, errorBody(403, "PERMISSION_DENIED"), LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403 PERMISSION_DENIED")),
            Triple(404, errorBody(404, "NOT_FOUND"), LlmResult.Failure(LlmError.MODEL_UNAVAILABLE, "HTTP 404 NOT_FOUND")),
            // Alles andere ist ein unbekannter Fehler.
            Triple(400, errorBody(400, "INVALID_ARGUMENT"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 INVALID_ARGUMENT")),
            Triple(400, otherReason, LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 INVALID_ARGUMENT")),
            Triple(402, errorBody(402, "PAYMENT_REQUIRED"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 402 PAYMENT_REQUIRED")),
            Triple(409, errorBody(409, "ABORTED"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 409 ABORTED")),
            Triple(418, "", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 418")),
            Triple(499, errorBody(499, "CANCELLED"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 499 CANCELLED")),
            Triple(501, errorBody(501, "UNIMPLEMENTED"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 501 UNIMPLEMENTED")),
            Triple(505, "", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 505")),
        )
        for ((status, body, expected) in cases) {
            server.enqueueJson(status, body)
            assertEquals(expected, generator().generate("Anweisung", "Fakten"), "HTTP $status mit $body")
        }
        // Keine dieser Antworten löst einen zweiten Versuch aus.
        assertNothingRepeated(expectedRequests = cases.size)
    }

    @Test
    fun `eine Fehlermeldung als JSON-Liste wird genauso gelesen`() = runTest {
        val keyInvalid =
            """[{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",""" +
                """"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}]"""
        // Vor der eigentlichen Meldung steht etwas anderes in der Liste.
        val notFirst = """[{"hinweis":"kein Fehler"},7,{"error":{"code":403,"status":"PERMISSION_DENIED"}}]"""
        val cases = listOf(
            Triple(400, keyInvalid, LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 400 INVALID_ARGUMENT")),
            Triple(400, "[" + errorBody(400, "FAILED_PRECONDITION") + "]", LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 400 FAILED_PRECONDITION")),
            Triple(404, "[" + errorBody(404, "NOT_FOUND") + "]", LlmResult.Failure(LlmError.MODEL_UNAVAILABLE, "HTTP 404 NOT_FOUND")),
            Triple(403, notFirst, LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403 PERMISSION_DENIED")),
            Triple(400, "[]", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
        )
        for ((status, body, expected) in cases) {
            server.enqueueJson(status, body)
            assertEquals(expected, generator().generate("Anweisung", "Fakten"), "HTTP $status mit $body")
        }
    }

    @Test
    fun `ist die Fehlermeldung kein JSON, zaehlt nur der HTTP-Status`() = runTest {
        val page = "<html><head><title>Fehler</title></head><body>Zugriff nicht möglich</body></html>"
        val cases = listOf(
            Triple(401, "Unauthorized", LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 401")),
            Triple(403, page, LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403")),
            Triple(404, "{ kaputt", LlmResult.Failure(LlmError.MODEL_UNAVAILABLE, "HTTP 404")),
            Triple(400, page, LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
            Triple(400, "", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
            // JSON, aber nicht in der Form einer Fehlermeldung: Die Wörter darin zählen nicht.
            Triple(400, """{"fehler":"API_KEY_INVALID"}""", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
            Triple(400, """{"error":"API_KEY_INVALID"}""", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
            Triple(400, "\"UNAUTHENTICATED\"", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
            Triple(400, "null", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400")),
        )
        for ((status, body, expected) in cases) {
            server.enqueueHtml(status, body)
            assertEquals(expected, generator().generate("Anweisung", "Fakten"), "HTTP $status mit $body")
        }

        // Auch bei Überlastung: Die Fehlerseite eines vorgeschalteten Servers kommt bei beiden Versuchen.
        repeat(2) { server.enqueueHtml(502, page) }
        assertEquals(LlmResult.Failure(LlmError.SERVER_UNAVAILABLE, "HTTP 502"), generator().generate("Anweisung", "Fakten"))
    }

    @Test
    fun `der Zusatz uebernimmt nur schlichte Kuerzel und ist hoechstens 120 Zeichen lang`() = runTest {
        val cases = listOf(
            // „HTTP 400 “ sind 9 Zeichen, es bleiben 111 für das Kürzel.
            errorBody(400, "A".repeat(300)) to "HTTP 400 " + "A".repeat(111),
            errorBody(400, "Bitte später <b>noch einmal</b> versuchen") to "HTTP 400",
            errorBody(400, "") to "HTTP 400",
            """{"error":{"code":400,"status":429}}""" to "HTTP 400",
            """{"error":{"code":400,"status":"  INVALID_ARGUMENT  "}}""" to "HTTP 400 INVALID_ARGUMENT",
        )
        for ((body, detail) in cases) {
            server.enqueueJson(400, body)
            assertEquals(LlmResult.Failure(LlmError.UNKNOWN, detail), generator().generate("Anweisung", "Fakten"), body)
        }
        assertEquals(120, ("HTTP 400 " + "A".repeat(111)).length)
    }

    @Test
    fun `der Schluessel steht in keinem Zusatz, auch wenn der Dienst ihn zurueckschickt`() = runTest {
        val echoes = listOf(
            Triple(400, """{"error":{"code":400,"message":"API key $TEST_KEY not valid.","status":"$TEST_KEY"}}""", "HTTP 400"),
            Triple(
                400,
                """{"error":{"code":400,"message":"$TEST_KEY","status":"INVALID_ARGUMENT","details":[{"reason":"$TEST_KEY"}]}}""",
                "HTTP 400 INVALID_ARGUMENT",
            ),
            Triple(403, """{"error":{"code":403,"status":"KEY_${TEST_KEY}_DENIED"}}""", "HTTP 403"),
            Triple(403, """{"error":{"code":403,"status":"${TEST_KEY.lowercase()}"}}""", "HTTP 403"),
            Triple(404, TEST_KEY, "HTTP 404"),
            Triple(200, """{"promptFeedback":{"blockReason":"$TEST_KEY"}}""", "HTTP 200"),
            Triple(200, """{"candidates":[{"finishReason":"$TEST_KEY"}]}""", "HTTP 200"),
            Triple(200, TEST_KEY, "HTTP 200"),
        )
        for ((status, body, detail) in echoes) {
            // Einmal als Antwort auf generate, einmal auf listModels.
            repeat(2) { server.enqueueJson(status, body) }
            val failures = listOf(generator().generate("Anweisung", "Fakten"), generator().listModels())
                .filterIsInstance<LlmResult.Failure>()

            for (failure in failures) {
                assertFalse(failure.detail.contains(TEST_KEY, ignoreCase = true), failure.detail)
            }
            assertEquals(detail, failures.first().detail, body)
        }
    }

    @Test
    fun `toString verraet den Schluessel nicht`() {
        val text = generator().toString()

        assertEquals("GeminiTextGenerator(model=gemini-test, host=127.0.0.1)", text)
        assertFalse(text.contains(TEST_KEY))
    }

    // ---------- Wiederholung ----------

    @Test
    fun `bei 503 folgt nach zwei Sekunden ein zweiter Versuch, der gelingen kann`() = runTest {
        server.enqueueJson(503, errorBody(503, "UNAVAILABLE"))
        server.enqueueJson(200, textAnswer("Servus"))

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Success(GeneratedText("Servus", "gemini-test-001")), result)
        assertEquals(listOf(2000L), pause.waits.toList())
        assertEquals(2, server.requestCount)
        // Beide Versuche sind dieselbe Anfrage.
        val first = server.nextRequest()
        val second = server.nextRequest()
        assertEquals(first.requestLine, second.requestLine)
        assertEquals(first.body, second.body)
        assertEquals(TEST_KEY, second.headers["x-goog-api-key"])
    }

    @Test
    fun `Ueberlastung und erschoepftes Kontingent werden nach genau einem weiteren Versuch gemeldet`() = runTest {
        val cases = listOf(
            Triple(429, "RESOURCE_EXHAUSTED", LlmError.QUOTA_EXCEEDED),
            Triple(500, "INTERNAL", LlmError.SERVER_UNAVAILABLE),
            Triple(502, "BAD_GATEWAY", LlmError.SERVER_UNAVAILABLE),
            Triple(503, "UNAVAILABLE", LlmError.SERVER_UNAVAILABLE),
            Triple(504, "DEADLINE_EXCEEDED", LlmError.SERVER_UNAVAILABLE),
        )
        for ((index, case) in cases.withIndex()) {
            val (status, apiStatus, expected) = case
            // Genau zwei Antworten stehen bereit: die auf den ersten und die auf den zweiten Versuch.
            // Ein dritter Versuch bekäme keine Antwort und fiele als Netzfehler auf.
            server.enqueueJson(status, errorBody(status, "ERSTER_VERSUCH"))
            server.enqueueJson(status, errorBody(status, apiStatus))

            val result = generator().generate("Anweisung", "Fakten")

            // Gemeldet wird die Antwort auf den zweiten Versuch.
            assertEquals(LlmResult.Failure(expected, "HTTP $status $apiStatus"), result)
            assertEquals(2 * (index + 1), server.requestCount)
            assertEquals(List(index + 1) { 2000L }, pause.waits.toList())
        }
    }

    @Test
    fun `bei 400 gibt es keinen zweiten Versuch`() = runTest {
        server.enqueueJson(400, errorBody(400, "INVALID_ARGUMENT"))
        server.enqueueJson(200, textAnswer("Diese Antwort darf niemand abholen"))

        val result = generator().generate("Anweisung", "Fakten")

        assertEquals(LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 INVALID_ARGUMENT"), result)
        assertNothingRepeated(expectedRequests = 1)
    }

    // ---------- Modell-Liste ----------

    @Test
    fun `listModels folgt den Seiten und liefert nur Modelle, die Text schreiben`() = runTest {
        server.enqueueJson(
            200,
            """
            {
              "models": [
                ${model("models/gemini-2.5-pro", "generateContent", "countTokens", "createCachedContent", "batchGenerateContent")},
                ${model("models/gemini-embedding-001", "embedContent", "countTextTokens")},
                ${model("models/gemini-nur-zaehlen", "countTokens")},
                ${model("models/gemini-2.5-flash-image", "generateContent", "countTokens")},
                ${model("models/gemini-2.5-flash-preview-tts", "countTokens", "generateContent")},
                ${model("models/gemini-2.5-flash", "generateContent", "countTokens")},
                {"name":"models/gemini-ohne-methoden"}
              ],
              "nextPageToken": "seite 2/+=&"
            }
            """.trimIndent(),
        )
        server.enqueueJson(
            200,
            """
            {
              "models": [
                ${model("models/gemma-3-27b-it", "generateContent", "countTokens")},
                ${model("models/gemini-2.5-flash", "generateContent")},
                ${model("models/text-embedding-004", "generateContent", "embedContent")},
                ${model("models/gemini-2.5-flash-native-audio-dialog", "generateContent")},
                ${model("models/gemini-live-2.5-flash-preview", "generateContent")},
                ${model("models/veo-3.0-generate-001", "generateContent", "predictLongRunning")},
                ${model("models/imagen-4.0-generate-001", "generateContent", "predict")},
                ${model("models/aqa", "generateContent", "generateAnswer")},
                ${model("models/gemini-robotics-er-1.5-preview", "generateContent")},
                ${model("models/gemini-2.5-computer-use-preview", "generateContent")},
                ${model("models/Gemini-2.5-Flash-LIVE-gross", "generateContent")},
                ${model("models/gemini-2.5-flash-lite", "generateContent")},
                ${model("gemini-ohne-praefix", "generateContent")}
              ]
            }
            """.trimIndent(),
        )

        val result = generator().listModels()

        // Übrig bleiben die Modelle mit generateContent, deren Name keines der ausgeschlossenen Wörter enthält:
        // ohne „models/“, ohne Dubletten, alphabetisch.
        assertEquals(
            LlmResult.Success(
                listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-ohne-praefix", "gemma-3-27b-it"),
            ),
            result,
        )

        val first = server.nextRequest()
        assertEquals("GET", first.method)
        assertEquals("/v1beta/models?pageSize=1000", first.target)
        assertEquals(TEST_KEY, first.headers["x-goog-api-key"])
        val second = server.nextRequest()
        assertEquals("GET", second.method)
        assertEquals("/v1beta/models", second.url.encodedPath)
        assertEquals("1000", second.url.queryParameter("pageSize"))
        assertEquals("seite 2/+=&", second.url.queryParameter("pageToken"))
        assertEquals(TEST_KEY, second.headers["x-goog-api-key"])
        assertNothingRepeated(expectedRequests = 2)
    }

    @Test
    fun `listModels folgt hoechstens fuenf Seiten`() = runTest {
        // Sieben Seiten stehen bereit, jede verweist auf eine weitere.
        for (page in 1..7) {
            server.enqueueJson(
                200,
                """{"models":[${model("models/gemini-seite-$page", "generateContent")}],"nextPageToken":"seite-${page + 1}"}""",
            )
        }

        val result = generator().listModels()

        assertEquals(
            LlmResult.Success(listOf("gemini-seite-1", "gemini-seite-2", "gemini-seite-3", "gemini-seite-4", "gemini-seite-5")),
            result,
        )
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `eine leere Modell-Liste ist ein Erfolg`() = runTest {
        // Google lässt leere Listen in der Antwort ganz weg.
        server.enqueueJson(200, "{}")
        server.enqueueJson(200, """{"models":[],"nextPageToken":""}""")
        server.enqueueJson(200, """{"models":null}""")

        repeat(3) {
            assertEquals(LlmResult.Success(emptyList<String>()), generator().listModels())
        }
        assertNothingRepeated(expectedRequests = 3)
    }

    @Test
    fun `listModels meldet Fehler wie generate`() = runTest {
        val keyInvalid =
            """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",""" +
                """"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}"""
        val cases = listOf(
            Triple(400, keyInvalid, LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 400 INVALID_ARGUMENT")),
            Triple(401, errorBody(401, "UNAUTHENTICATED"), LlmResult.Failure(LlmError.INVALID_KEY, "HTTP 401 UNAUTHENTICATED")),
            Triple(400, errorBody(400, "FAILED_PRECONDITION"), LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 400 FAILED_PRECONDITION")),
            Triple(403, errorBody(403, "PERMISSION_DENIED"), LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403 PERMISSION_DENIED")),
            Triple(404, errorBody(404, "NOT_FOUND"), LlmResult.Failure(LlmError.MODEL_UNAVAILABLE, "HTTP 404 NOT_FOUND")),
            Triple(400, errorBody(400, "INVALID_ARGUMENT"), LlmResult.Failure(LlmError.UNKNOWN, "HTTP 400 INVALID_ARGUMENT")),
            Triple(200, "<html>keine Liste</html>", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200")),
            Triple(200, """{"models":"keine Liste"}""", LlmResult.Failure(LlmError.UNKNOWN, "HTTP 200")),
        )
        for ((status, body, expected) in cases) {
            server.enqueueJson(status, body)
            assertEquals(expected, generator().listModels(), "HTTP $status mit $body")
        }
        assertNothingRepeated(expectedRequests = cases.size)
    }

    @Test
    fun `listModels versucht es bei Ueberlastung genau ein zweites Mal`() = runTest {
        // Erst überlastet, dann klappt es.
        server.enqueueJson(503, errorBody(503, "UNAVAILABLE"))
        server.enqueueJson(200, """{"models":[${model("models/gemini-test", "generateContent")}]}""")
        assertEquals(LlmResult.Success(listOf("gemini-test")), generator().listModels())
        assertEquals(listOf(2000L), pause.waits.toList())

        // Zweimal hintereinander das Kontingent erschöpft: Der zweite Versuch entscheidet.
        repeat(2) { server.enqueueJson(429, errorBody(429, "RESOURCE_EXHAUSTED")) }
        assertEquals(LlmResult.Failure(LlmError.QUOTA_EXCEEDED, "HTTP 429 RESOURCE_EXHAUSTED"), generator().listModels())
        assertEquals(listOf(2000L, 2000L), pause.waits.toList())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `scheitert eine spaetere Seite der Modell-Liste, scheitert die ganze Abfrage`() = runTest {
        server.enqueueJson(200, """{"models":[${model("models/gemini-a", "generateContent")}],"nextPageToken":"weiter"}""")
        server.enqueueJson(403, errorBody(403, "PERMISSION_DENIED"))

        val result = generator().listModels()

        assertEquals(LlmResult.Failure(LlmError.ACCESS_DENIED, "HTTP 403 PERMISSION_DENIED"), result)
        assertNull(server.nextRequest().url.queryParameter("pageToken"))
        assertEquals("weiter", server.nextRequest().url.queryParameter("pageToken"))
    }
}
