package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.data.llm.TestHttp
import io.github.q0ochen.schafkopf.data.llm.baseUrl
import io.github.q0ochen.schafkopf.data.llm.enqueueHtml
import io.github.q0ochen.schafkopf.data.llm.enqueueJson
import io.github.q0ochen.schafkopf.data.llm.nextRequest
import io.github.q0ochen.schafkopf.data.llm.startLocal
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Anmeldung beim Server der gemeinsamen Runde: PKCE, die Aufrufe der Anmeldung und die Sitzung auf dem Gerät. */
class SupabaseAuthTest {

    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-12-12T18:00:00Z")
    private var time = now
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = time
    }

    /** Die Ablage der Anmeldung im Speicher; zählt mit, wie oft gespeichert wurde. */
    private class MemoryStorage(var session: StoredSession? = null) : SessionStorage {
        var verifier: String? = null
        var saves = 0
        var loads = 0
        var failOnSave = false

        override suspend fun load(): StoredSession? {
            loads++
            return session
        }

        override suspend fun save(session: StoredSession) {
            if (failOnSave) throw IllegalStateException("Schlüsselspeicher gestört")
            saves++
            this.session = session
        }

        override suspend fun clear() {
            session = null
        }

        override suspend fun pendingVerifier(): String? = verifier

        override suspend fun setPendingVerifier(verifier: String?) {
            this.verifier = verifier
        }
    }

    @BeforeEach
    fun startServer() = server.startLocal()

    @AfterEach
    fun stopServer() = server.close()

    private fun auth(address: String = server.baseUrl) =
        SupabaseAuthApi(SupabaseHttp(TestHttp.client, SupabaseConfig(address, "sb_publishable_TEST")), clock)

    private fun session(storage: SessionStorage, address: String = server.baseUrl) =
        RoundSession(auth(address), storage, clock)

    /** Die Adresse eines Servers, der nicht mehr läuft: Jeder Aufruf dorthin scheitert wie ohne Netz. */
    private fun unreachable(): String = MockWebServer().run {
        startLocal()
        baseUrl.also { close() }
    }

    private fun tokens(access: String, refresh: String, expiresIn: Long = 3600, email: String = "konto@example.org") =
        """{"access_token":"$access","token_type":"bearer","expires_in":$expiresIn,"refresh_token":"$refresh",
            "user":{"id":"konto-1","email":"$email","user_metadata":{"full_name":"Wird nicht gebraucht"}}}"""

    private suspend fun errorOf(block: suspend () -> Unit): RoundException? = try {
        block()
        null
    } catch (e: RoundException) {
        e
    }

    // --- PKCE ---

    @Test
    fun `der Verifier ist zufaellig und gueltig, die Challenge sein SHA-256 in base64url`() {
        val first = Pkce.newVerifier()
        val second = Pkce.newVerifier()

        assertEquals(86, first.length)
        assertTrue(Pkce.isValidVerifier(first))
        assertNotEquals(first, second)
        // Das Beispiel aus RFC 7636, Anhang B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertFalse(Pkce.isValidVerifier("zu kurz"))
        assertFalse(Pkce.isValidVerifier("x".repeat(129)))
        assertFalse(Pkce.isValidVerifier("mit leerzeichen ".repeat(4)))
        assertThrows(IllegalArgumentException::class.java) { Pkce.challenge("zu kurz") }
    }

    // --- Adressen und Rückkehr ---

    @Test
    fun `die Anmeldung oeffnet Google ueber den Server und kehrt in die App zurueck`() {
        val url = auth().authorizeUrl("CHALLENGE").toHttpUrl()

        assertEquals("/auth/v1/authorize", url.encodedPath)
        assertEquals("google", url.queryParameter("provider"))
        assertEquals("schafkopftracker://auth-callback", url.queryParameter("redirect_to"))
        assertEquals("CHALLENGE", url.queryParameter("code_challenge"))
        assertEquals("s256", url.queryParameter("code_challenge_method"))
        // Der Schlüssel gehört in die Kopfzeile, nicht in die Adresse, die der Browser sieht.
        assertFalse(url.toString().contains("sb_publishable"))
    }

    @Test
    fun `die Rueckkehr aus dem Browser bringt einen Code oder einen Fehler`() {
        assertEquals(AuthCallback.Code("abc-123"), AuthCallback.parse("schafkopftracker://auth-callback?code=abc-123"))
        assertEquals(AuthCallback.Code("a b"), AuthCallback.parse("SchafkopfTracker://Auth-Callback?x=1&code=a%20b"))
        assertEquals(
            AuthCallback.Failure("access_denied", "Der Nutzer hat abgebrochen"),
            AuthCallback.parse("schafkopftracker://auth-callback?error=access_denied&error_description=Der+Nutzer+hat+abgebrochen"),
        )
        assertEquals(
            AuthCallback.Failure("server_error", null),
            AuthCallback.parse("schafkopftracker://auth-callback#error_code=server_error"),
        )
        assertEquals(AuthCallback.Failure("unknown", null), AuthCallback.parse("schafkopftracker://auth-callback"))
        assertEquals(AuthCallback.Failure("unknown", null), AuthCallback.parse("schafkopftracker://auth-callback?code=&kaputt&=x"))
        // Alles andere ist keine Rückkehr aus der Anmeldung, auch keine kaputt kodierte Adresse.
        assertNull(AuthCallback.parse("schafkopftracker://auth-callback?code=%zz"))
        assertNull(AuthCallback.parse("schafkopftracker://einladung/0123456789abcdef0123456789abcdef"))
        assertNull(AuthCallback.parse("https://example.org/auth-callback?code=abc"))
        assertNull(AuthCallback.parse("kein uri mit leerzeichen"))
        assertNull(AuthCallback.parse(""))
    }

    // --- Aufrufe der Anmeldung ---

    @Test
    fun `der Code wird mit dem Verifier gegen eine Sitzung getauscht`() = runTest {
        server.enqueueJson(200, tokens("ZUGRIFF-1", "ERNEUERN-1"))

        val result = auth().exchangeCode("CODE", "VERIFIER")

        assertEquals(AuthTokens("ZUGRIFF-1", "ERNEUERN-1", now.plusSeconds(3600), Account("konto-1", "konto@example.org")), result)
        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/token", request.url.encodedPath)
        assertEquals("pkce", request.url.queryParameter("grant_type"))
        assertEquals("sb_publishable_TEST", request.headers["apikey"])
        assertNull(request.headers["Authorization"])
        assertEquals(Json.parseToJsonElement("""{"auth_code":"CODE","code_verifier":"VERIFIER"}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun `der Ablauf kommt aus expires_at, sonst aus expires_in, sonst gilt eine Stunde`() {
        val api = auth()
        fun body(extra: String) = """{"access_token":"a","refresh_token":"r",$extra"user":{"id":"konto-1"}}"""

        assertEquals(Instant.ofEpochSecond(1_800_000_000), api.parseTokens(body("\"expires_at\":1800000000,\"expires_in\":5,")).expiresAt)
        assertEquals(now.plusSeconds(120), api.parseTokens(body("\"expires_in\":120,")).expiresAt)
        assertEquals(now.plusSeconds(3600), api.parseTokens(body("")).expiresAt)
        assertEquals(Account("konto-1", null), api.parseTokens(body("")).account)
    }

    @Test
    fun `eine unbrauchbare Antwort der Anmeldung ist ein Fehler des Servers`() = runTest {
        val api = auth()
        for (body in listOf(
            "kein json",
            "[]",
            """{"refresh_token":"r","user":{"id":"k"}}""",
            """{"access_token":"a","user":{"id":"k"}}""",
            """{"access_token":"a","refresh_token":"r"}""",
            """{"access_token":"a","refresh_token":"r","user":{"id":""}}""",
            """{"access_token":5,"refresh_token":"r","user":{"id":"k"}}""",
        )) {
            assertEquals(RoundError.SERVER, assertThrows(RoundException::class.java) { api.parseTokens(body) }.error, body)
        }
        server.enqueueJson(400, """{"error_code":"flow_state_not_found","msg":"..."}""")
        assertEquals("SERVER: Anmeldung: HTTP 400 flow_state_not_found", errorOf { api.exchangeCode("c", "v") }?.message)
    }

    @Test
    fun `beim Erneuern wird unterschieden, ob die Sitzung endgueltig vorbei ist`() = runTest {
        val api = auth()
        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2"))
        server.enqueueJson(400, """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token: Refresh Token Not Found"}""")
        server.enqueueJson(400, """{"error":"invalid_grant","error_description":"Invalid Refresh Token"}""")
        server.enqueueJson(400, """{"error_code":"validation_failed","msg":"..."}""")
        server.enqueueJson(429, """{"error_code":"over_request_rate_limit"}""")
        server.enqueueHtml(503, "<html>pausiert</html>")

        assertEquals("ERNEUERN-2", api.refresh("ERNEUERN-1").refreshToken)
        assertEquals(AuthApi.SESSION_GONE, errorOf { api.refresh("x") }?.takeIf { it.error == RoundError.SIGNED_OUT }?.detail)
        assertEquals(AuthApi.SESSION_GONE, errorOf { api.refresh("x") }?.takeIf { it.error == RoundError.SIGNED_OUT }?.detail)
        // Abgelehnt, aber nicht sicher endgültig: neu anmelden hilft, das Token bleibt aber vorerst.
        assertEquals("SIGNED_OUT: HTTP 400 validation_failed", errorOf { api.refresh("x") }?.message)
        assertEquals(RoundError.SERVER, errorOf { api.refresh("x") }?.error)
        assertEquals(RoundError.SERVER, errorOf { api.refresh("x") }?.error)
        assertEquals(RoundError.OFFLINE, errorOf { auth(unreachable()).refresh("x") }?.error)

        val request = server.nextRequest()
        assertEquals("refresh_token", request.url.queryParameter("grant_type"))
        assertEquals(Json.parseToJsonElement("""{"refresh_token":"ERNEUERN-1"}"""), Json.parseToJsonElement(request.body!!.utf8()))
    }

    @Test
    fun `abmelden beendet die Sitzung auf dem Server - eine schon ungueltige ist kein Fehler`() = runTest {
        val api = auth()
        server.enqueue(MockResponse(code = 204))
        server.enqueueJson(401, """{"error_code":"session_not_found"}""")
        server.enqueueJson(500, "{}")

        api.signOut("ZUGRIFF-1")
        api.signOut("ZUGRIFF-1")
        assertEquals(RoundError.SERVER, errorOf { api.signOut("ZUGRIFF-1") }?.error)

        val request = server.nextRequest()
        assertEquals("/auth/v1/logout", request.url.encodedPath)
        assertEquals("local", request.url.queryParameter("scope"))
        assertEquals("Bearer ZUGRIFF-1", request.headers["Authorization"])
    }

    // --- Die Sitzung auf dem Gerät ---

    @Test
    fun `anmelden - der Verifier uebersteht das Warten im Browser und wird danach verworfen`() = runTest {
        val storage = MemoryStorage()
        val url = session(storage).beginSignIn().toHttpUrl()
        val verifier = checkNotNull(storage.verifier)
        assertEquals(Pkce.challenge(verifier), url.queryParameter("code_challenge"))

        // Android hat die App inzwischen beendet: eine neue Sitzung mit demselben Speicher.
        server.enqueueJson(200, tokens("ZUGRIFF-1", "ERNEUERN-1"))
        val session = session(storage)
        val account = session.completeSignIn("CODE")

        assertEquals(Account("konto-1", "konto@example.org"), account)
        assertEquals(account, session.account.value)
        assertEquals(StoredSession("ERNEUERN-1", "konto-1", "konto@example.org"), storage.session)
        assertNull(storage.verifier)
        assertTrue(server.nextRequest().body!!.utf8().contains(verifier))
        assertEquals("ZUGRIFF-1", session.accessToken())
        assertEquals(1, server.requestCount)
        // Derselbe Code ein zweites Mal: Es gibt keine begonnene Anmeldung mehr.
        assertEquals(RoundError.SIGNED_OUT, errorOf { session.completeSignIn("CODE") }?.error)
    }

    @Test
    fun `scheitert der Tausch, bleibt die begonnene Anmeldung fuer einen neuen Versuch`() = runTest {
        val storage = MemoryStorage()
        val session = session(storage)
        session.beginSignIn()
        server.enqueueHtml(503, "pausiert")

        assertEquals(RoundError.SERVER, errorOf { session.completeSignIn("CODE") }?.error)

        assertTrue(storage.verifier != null)
        assertNull(session.account.value)
    }

    @Test
    fun `eine gespeicherte Anmeldung wird einmal gelesen und erst bei Bedarf erneuert`() = runTest {
        val storage = MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", "konto@example.org"))
        val session = session(storage)
        assertNull(session.account.value)

        assertEquals(Account("konto-1", "konto@example.org"), session.load())
        assertEquals(0, server.requestCount)

        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2", expiresIn = 3600))
        assertEquals("ZUGRIFF-2", session.accessToken())
        assertEquals("ZUGRIFF-2", session.accessToken())
        assertEquals(1, server.requestCount)
        assertEquals("ERNEUERN-2", storage.session?.refreshToken)
        assertEquals(1, storage.loads)

        // Eine Minute vor dem Ablauf gilt das Token schon als abgelaufen.
        time = now.plusSeconds(3600 - 59)
        server.enqueueJson(200, tokens("ZUGRIFF-3", "ERNEUERN-3"))
        assertEquals("ZUGRIFF-3", session.accessToken())
        assertTrue(server.takeRequest().body!!.utf8().contains("ERNEUERN-1"))
        assertTrue(server.takeRequest().body!!.utf8().contains("ERNEUERN-2"))
    }

    @Test
    fun `nach einer Antwort 401 wird erneuert - ausser ein anderer Aufruf war schon schneller`() = runTest {
        val session = session(MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", null)))
        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2"))
        server.enqueueJson(200, tokens("ZUGRIFF-3", "ERNEUERN-3"))

        assertEquals("ZUGRIFF-2", session.accessToken())
        // Ein Aufruf mit einem älteren Token wurde abgelehnt: Das aktuelle Token gilt noch.
        assertEquals("ZUGRIFF-2", session.refreshAfterUnauthorized("ZUGRIFF-1"))
        // Das aktuelle Token selbst wurde abgelehnt: neu holen.
        assertEquals("ZUGRIFF-3", session.refreshAfterUnauthorized("ZUGRIFF-2"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `kennt der Server die Sitzung nicht mehr, ist das Geraet abgemeldet`() = runTest {
        val storage = MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", null))
        val session = session(storage)
        session.load()
        server.enqueueJson(400, """{"error_code":"refresh_token_already_used"}""")

        assertEquals(RoundError.SIGNED_OUT, errorOf { session.accessToken() }?.error)

        assertNull(storage.session)
        assertNull(session.account.value)
        assertEquals(RoundError.SIGNED_OUT, errorOf { session.accessToken() }?.error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `bei Netzfehlern und Stoerungen bleibt das Geraet angemeldet`() = runTest {
        val storage = MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", null))
        val session = session(storage)
        server.enqueueHtml(503, "pausiert")
        server.enqueueJson(400, """{"error_code":"validation_failed"}""")
        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2"))

        assertEquals(RoundError.OFFLINE, errorOf { session(storage, unreachable()).accessToken() }?.error)
        assertEquals(RoundError.SERVER, errorOf { session.accessToken() }?.error)
        assertEquals(RoundError.SIGNED_OUT, errorOf { session.accessToken() }?.error)
        assertEquals("ERNEUERN-1", storage.session?.refreshToken)
        assertEquals(Account("konto-1", null), session.account.value)

        assertEquals("ZUGRIFF-2", session.accessToken())
    }

    @Test
    fun `abmelden vergisst die Anmeldung auf dem Geraet, auch ohne Netz`() = runTest {
        val storage = MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", null))
        val session = session(storage)
        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2"))
        server.enqueue(MockResponse(code = 204))

        session.signOut()

        assertNull(storage.session)
        assertNull(session.account.value)
        assertEquals("/auth/v1/token", server.nextRequest().url.encodedPath)
        assertEquals("/auth/v1/logout", server.nextRequest().url.encodedPath)

        // Ohne Netz: Der Server erfährt nichts, das Gerät ist trotzdem abgemeldet.
        val offline = MemoryStorage(StoredSession("ERNEUERN-9", "konto-1", null))
        session(offline, unreachable()).signOut()
        assertNull(offline.session)
        assertEquals(2, server.requestCount)
        // Wer gar nicht angemeldet ist, kann sich gefahrlos abmelden.
        session(MemoryStorage()).signOut()
    }

    @Test
    fun `streikt der Schluesselspeicher, bleibt die Anmeldung bis zum Beenden der App`() = runTest {
        val storage = MemoryStorage(StoredSession("ERNEUERN-1", "konto-1", null)).apply { failOnSave = true }
        val session = session(storage)
        server.enqueueJson(200, tokens("ZUGRIFF-2", "ERNEUERN-2"))

        assertEquals("ZUGRIFF-2", session.accessToken())

        assertEquals(Account("konto-1", "konto@example.org"), session.account.value)
        assertEquals(0, storage.saves)
    }
}
