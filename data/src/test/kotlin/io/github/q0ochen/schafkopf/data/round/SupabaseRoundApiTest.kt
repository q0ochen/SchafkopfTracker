package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.data.llm.TestHttp
import io.github.q0ochen.schafkopf.data.llm.baseUrl
import io.github.q0ochen.schafkopf.data.llm.enqueueHtml
import io.github.q0ochen.schafkopf.data.llm.enqueueJson
import io.github.q0ochen.schafkopf.data.llm.nextRequest
import io.github.q0ochen.schafkopf.data.llm.startLocal
import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.RemoteEvening
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundHead
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.SavedEvening
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Die Anbindung an den Server der gemeinsamen Runde gegen einen Testserver auf dem eigenen Rechner: welche Anfragen
 * hinausgehen (docs/ARCHITEKTUR.md, Abschnitt 8.3) und wie Antworten und Fehler gelesen werden.
 */
class SupabaseRoundApiTest {

    private val server = MockWebServer()
    private val round = RoundId("3f0e6f0a-0000-4000-8000-000000000001")
    private val tokens = object : AccessTokenSource {
        var current = "ZUGRIFF-1"
        var refreshed = 0
        var signedOut = false

        override suspend fun accessToken(): String = if (signedOut) throw RoundException(RoundError.SIGNED_OUT) else current

        override suspend fun refreshAfterUnauthorized(rejected: String): String {
            refreshed++
            current = "ZUGRIFF-${refreshed + 1}"
            return current
        }
    }

    @BeforeEach
    fun startServer() = server.startLocal()

    @AfterEach
    fun stopServer() = server.close()

    private fun api() = SupabaseRoundApi(SupabaseHttp(TestHttp.client, SupabaseConfig(server.baseUrl, "sb_publishable_TEST")), tokens)

    private val evening = Evening(
        id = EveningId("abend-1"),
        startedAt = Instant.parse("2026-12-12T18:00:00Z"),
        seating = listOf("spieler-1", "spieler-2", "spieler-3", "spieler-4").map(::PlayerId),
        firstDealer = PlayerId("spieler-1"),
        rules = RuleSet.standard(),
        entries = listOf(
            GameEntry(
                id = EntryId("spiel-1"),
                recordedAt = Instant.parse("2026-12-12T18:05:00.250Z"),
                type = GameType.RUFSPIEL,
                declarer = PlayerId("spieler-1"),
                partner = PlayerId("spieler-2"),
                declarerWon = true,
            ),
        ),
    )
    private val doc: String = StorageCodec.encodeEvening(evening)

    private suspend fun errorOf(block: suspend () -> Unit): RoundException? = try {
        block()
        null
    } catch (e: RoundException) {
        e
    }

    private fun body(request: mockwebserver3.RecordedRequest): JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    // --- Lesen ---

    @Test
    fun `der Kopf der Runde wird mit Schluessel und Token gelesen`() = runTest {
        val rules = StorageCodec.encodeRules(RuleSet.sauspiel())
        server.enqueueJson(200, """[{"id":"${round.value}","name":"Stammtisch","rev":42,"rules":$rules,"created_by":"konto-1","mehr":true}]""")

        val head = api().head(round)

        assertEquals(RoundHead(round, "Stammtisch", rev = 42, rules = RuleSet.sauspiel(), createdBy = "konto-1"), head)
        val request = server.nextRequest()
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/rounds", request.url.encodedPath)
        assertEquals("eq.${round.value}", request.url.queryParameter("id"))
        assertEquals("id,name,rev,rules,created_by", request.url.queryParameter("select"))
        assertEquals("sb_publishable_TEST", request.headers["apikey"])
        assertEquals("Bearer ZUGRIFF-1", request.headers["Authorization"])
        assertFalse(request.url.toString().contains("ZUGRIFF"))
    }

    @Test
    fun `ohne sichtbare Runde gibt es keinen Kopf, ohne Regelwerk keines, und Unlesbares wird gemeldet`() = runTest {
        server.enqueueJson(200, "[]")
        server.enqueueJson(200, """[{"id":"${round.value}","name":"Stammtisch","rev":1,"rules":null,"created_by":null}]""")
        server.enqueueJson(200, """[{"id":"${round.value}","name":"Stammtisch","rev":2,"rules":{"schemaVersion":1,"prices":"kaputt"}}]""")

        assertNull(api().head(round))
        assertEquals(RoundHead(round, "Stammtisch", rev = 1, rules = null, createdBy = null), api().head(round))
        assertEquals(RoundHead(round, "Stammtisch", rev = 2, rules = null, createdBy = null, rulesUnreadable = true), api().head(round))
    }

    @Test
    fun `Mitglieder und Spieler werden in der Reihenfolge des Servers gelesen`() = runTest {
        server.enqueueJson(
            200,
            """[{"user_id":"konto-1","player_id":"spieler-1","joined_at":"2026-12-01T10:00:00+00:00"},
                {"user_id":"konto-2","player_id":null,"joined_at":"2026-12-02T10:00:00+00:00"}]""",
        )
        server.enqueueJson(
            200,
            """[{"id":"spieler-1","name":"Nord","color_index":0,"archived":false,"position":1,"owner":"konto-1","name_locked":true,"deleted":false,"rev":7},
                {"id":"gast","name":"Wirt","color_index":4,"archived":true,"position":5,"owner":null,"name_locked":false,"deleted":true,"rev":9}]""",
        )

        assertEquals(listOf(RoundMember("konto-1", PlayerId("spieler-1")), RoundMember("konto-2", null)), api().members(round))
        assertEquals(
            listOf(
                RemotePlayer(PlayerId("spieler-1"), "Nord", 0, archived = false, position = 1, owner = "konto-1", nameLocked = true, rev = 7),
                RemotePlayer(PlayerId("gast"), "Wirt", 4, archived = true, position = 5, deleted = true, rev = 9),
            ),
            api().playersSince(round, 6),
        )

        val members = server.nextRequest()
        assertEquals("/rest/v1/round_members", members.url.encodedPath)
        assertEquals("eq.${round.value}", members.url.queryParameter("round_id"))
        assertEquals("joined_at.asc", members.url.queryParameter("order"))
        val players = server.nextRequest()
        assertEquals("/rest/v1/round_players", players.url.encodedPath)
        assertEquals("gt.6", players.url.queryParameter("rev"))
        assertEquals("rev.asc", players.url.queryParameter("order"))
        assertEquals("id,name,color_index,archived,position,owner,name_locked,deleted,rev", players.url.queryParameter("select"))
    }

    @Test
    fun `Abende kommen als Dokumente - Unlesbares und falsch Beschriftetes bleibt ohne Inhalt`() = runTest {
        val newer = doc.replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        server.enqueueJson(
            200,
            """[{"id":"abend-1","doc":$doc,"version":3,"deleted":false,"rev":11},
                {"id":"abend-2","doc":$newer,"version":1,"deleted":false,"rev":12},
                {"id":"abend-3","doc":$doc,"version":1,"deleted":false,"rev":13},
                {"id":"abend-4","doc":{"id":"abend-4"},"version":2,"deleted":true,"rev":14}]""",
        )

        val rows = api().eveningsSince(round, 10)

        assertEquals(
            listOf(
                RemoteEvening(EveningId("abend-1"), version = 3, deleted = false, rev = 11, evening = evening),
                // Von einer neueren App geschrieben.
                RemoteEvening(EveningId("abend-2"), version = 1, deleted = false, rev = 12, evening = null),
                // Das Dokument trägt eine andere Kennung als die Zeile.
                RemoteEvening(EveningId("abend-3"), version = 1, deleted = false, rev = 13, evening = null),
                RemoteEvening(EveningId("abend-4"), version = 2, deleted = true, rev = 14, evening = null),
            ),
            rows,
        )
        val request = server.nextRequest()
        assertEquals("/rest/v1/round_evenings", request.url.encodedPath)
        assertEquals("gt.10", request.url.queryParameter("rev"))
        assertEquals("rev.asc", request.url.queryParameter("order"))
        assertEquals("100", request.url.queryParameter("limit"))
        assertEquals("id,doc,version,deleted,rev", request.url.queryParameter("select"))
    }

    @Test
    fun `viele Abende werden seitenweise gelesen, bis eine Seite nicht mehr voll ist`() = runTest {
        fun page(from: Int, count: Int) = (from until from + count).joinToString(",", "[", "]") { number ->
            """{"id":"abend-$number","doc":{"id":"abend-$number"},"version":1,"deleted":true,"rev":$number}"""
        }
        server.enqueueJson(200, page(1, 100))
        server.enqueueJson(200, page(101, 100))
        server.enqueueJson(200, page(201, 7))

        val rows = api().eveningsSince(round, 0)

        assertEquals(207, rows.size)
        assertEquals((1L..207L).toList(), rows.map { it.rev })
        assertEquals("gt.0", server.nextRequest().url.queryParameter("rev"))
        assertEquals("gt.100", server.nextRequest().url.queryParameter("rev"))
        assertEquals("gt.200", server.nextRequest().url.queryParameter("rev"))
    }

    @Test
    fun `ein Server, der immer dieselbe volle Seite schickt, haelt das Lesen nicht ewig auf`() = runTest {
        val page = (1..100).joinToString(",", "[", "]") { """{"id":"a$it","doc":{},"version":1,"deleted":true,"rev":5}""" }
        server.enqueueJson(200, page)

        assertEquals(100, api().eveningsSince(round, 5).size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ein einzelner Abend wird ueber seine Kennung gelesen`() = runTest {
        server.enqueueJson(200, """[{"id":"abend-1","doc":$doc,"version":4,"deleted":false,"rev":20}]""")
        server.enqueueJson(200, "[]")

        assertEquals(RemoteEvening(evening.id, 4, deleted = false, rev = 20, evening = evening), api().evening(round, evening.id))
        assertNull(api().evening(round, EveningId("mit leer & zeichen")))

        assertEquals("eq.abend-1", server.nextRequest().url.queryParameter("id"))
        assertEquals("eq.mit leer & zeichen", server.nextRequest().url.queryParameter("id"))
    }

    // --- Schreiben ---

    @Test
    fun `ein Abend geht als Dokument mit erwarteter Fassung hoch`() = runTest {
        server.enqueueJson(200, """[{"version":4,"rev":21}]""")

        assertEquals(SavedEvening(version = 4, rev = 21), api().saveEvening(round, evening, expectedVersion = 3))

        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/save_evening", request.url.encodedPath)
        assertEquals("Bearer ZUGRIFF-1", request.headers["Authorization"])
        val sent = body(request)
        assertEquals(setOf("p_round", "p_id", "p_doc", "p_expected_version", "p_deleted"), sent.keys)
        assertEquals(JsonPrimitive(round.value), sent["p_round"])
        assertEquals(JsonPrimitive("abend-1"), sent["p_id"])
        assertEquals(JsonPrimitive(3), sent["p_expected_version"])
        assertEquals(JsonPrimitive(false), sent["p_deleted"])
        // Das Dokument ist genau der Abend im Dateiformat der Sicherung und lässt sich wieder lesen.
        assertEquals(Json.parseToJsonElement(doc), sent["p_doc"])
        assertEquals(evening, StorageCodec.decodeEveningElement(sent.getValue("p_doc")))
    }

    @Test
    fun `passt die Fassung nicht, kommt nichts zurueck - und Loeschen ist ein Speichern mit Markierung`() = runTest {
        server.enqueueJson(200, "[]")
        server.enqueueJson(200, """[{"version":5,"rev":30}]""")

        assertNull(api().saveEvening(round, evening, expectedVersion = 1))
        assertEquals(SavedEvening(5, 30), api().saveEvening(round, evening, expectedVersion = 4, deleted = true))

        server.nextRequest()
        assertEquals(JsonPrimitive(true), body(server.nextRequest())["p_deleted"])
    }

    @Test
    fun `Spieler und Regelwerk gehen ueber die Funktionen des Servers`() = runTest {
        server.enqueueJson(200, "17")
        server.enqueueJson(200, "18")
        server.enqueueJson(200, "19")

        assertEquals(17L, api().savePlayer(round, Player(PlayerId("gast"), "Wirt", colorIndex = 4, archived = true)))
        assertEquals(18L, api().savePlayer(round, Player(PlayerId("gast"), "Wirt", colorIndex = 4), deleted = true))
        assertEquals(19L, api().saveRules(round, RuleSet.sauspiel()))

        val save = server.nextRequest()
        assertEquals("/rest/v1/rpc/save_player", save.url.encodedPath)
        assertEquals(
            Json.parseToJsonElement("""{"p_round":"${round.value}","p_id":"gast","p_name":"Wirt","p_color_index":4,"p_archived":true,"p_deleted":false}"""),
            body(save),
        )
        assertEquals(JsonPrimitive(true), body(server.nextRequest())["p_deleted"])
        val rules = server.nextRequest()
        assertEquals("/rest/v1/rpc/save_rules", rules.url.encodedPath)
        assertEquals(RuleSet.sauspiel(), StorageCodec.decodeRulesElement(body(rules).getValue("p_rules")))
    }

    @Test
    fun `Runden anlegen, auflisten, einladen, beitreten, verlassen und Das bin ich`() = runTest {
        server.enqueueJson(200, "\"${round.value}\"")
        server.enqueueJson(
            200,
            """[{"id":"${round.value}","name":"Stammtisch","rev":9,"member_count":3,"my_player_id":"spieler-2","is_creator":false},
                {"id":"andere","name":"Zweite","rev":1,"member_count":1,"my_player_id":null,"is_creator":true}]""",
        )
        server.enqueueJson(200, "\"0123456789abcdef0123456789abcdef\"")
        server.enqueueJson(200, """[{"round_id":"${round.value}","round_name":"Stammtisch","member_count":3,"already_member":true}]""")
        server.enqueueJson(200, "\"${round.value}\"")
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))

        val api = api()
        assertEquals(round, api.createRound("Stammtisch"))
        assertEquals(
            listOf(
                RoundSummary(round, "Stammtisch", rev = 9, memberCount = 3, myPlayerId = PlayerId("spieler-2"), isCreator = false),
                RoundSummary(RoundId("andere"), "Zweite", rev = 1, memberCount = 1, myPlayerId = null, isCreator = true),
            ),
            api.myRounds(),
        )
        assertEquals("0123456789abcdef0123456789abcdef", api.createInvite(round))
        assertEquals(InviteInfo(round, "Stammtisch", memberCount = 3, alreadyMember = true), api.inviteInfo("0123456789abcdef0123456789abcdef"))
        assertEquals(round, api.joinRound("0123456789abcdef0123456789abcdef"))
        api.leaveRound(round)
        api.claimPlayer(round, PlayerId("spieler-2"), "Zwei", colorIndex = 1)

        fun next(path: String, expected: String) {
            val request = server.nextRequest()
            assertEquals("POST", request.method)
            assertEquals("/rest/v1/rpc/$path", request.url.encodedPath)
            assertEquals(Json.parseToJsonElement(expected), body(request))
        }
        next("create_round", """{"p_name":"Stammtisch"}""")
        next("my_rounds", "{}")
        next("create_invite", """{"p_round":"${round.value}"}""")
        next("invite_info", """{"p_token":"0123456789abcdef0123456789abcdef"}""")
        next("join_round", """{"p_token":"0123456789abcdef0123456789abcdef"}""")
        next("leave_round", """{"p_round":"${round.value}"}""")
        next("claim_player", """{"p_round":"${round.value}","p_player":"spieler-2","p_name":"Zwei","p_color_index":1}""")
    }

    // --- Fehler ---

    @Test
    fun `die Fehlercodes der Funktionen werden erkannt`() = runTest {
        val codes = mapOf(
            "not_member" to RoundError.NOT_MEMBER,
            "invalid_name" to RoundError.INVALID_NAME,
            "name_taken" to RoundError.NAME_TAKEN,
            "name_locked" to RoundError.NAME_LOCKED,
            "already_claimed" to RoundError.ALREADY_CLAIMED,
            "player_taken" to RoundError.PLAYER_TAKEN,
            "player_owned" to RoundError.PLAYER_OWNED,
            "invalid_invite" to RoundError.INVALID_INVITE,
            "limit_reached" to RoundError.LIMIT_REACHED,
            "too_large" to RoundError.TOO_LARGE,
            "invalid_doc" to RoundError.INVALID_DOC,
        )
        for ((code, error) in codes) {
            server.enqueueJson(400, """{"code":"P0001","details":null,"hint":null,"message":"$code"}""")
            assertEquals(error, errorOf { api().joinRound("x") }?.error, code)
        }
    }

    @Test
    fun `andere Fehler des Servers sind kein Grund, etwas am Geraet zu aendern`() = runTest {
        server.enqueueJson(400, """{"code":"P0001","message":"etwas_neues"}""")
        server.enqueueJson(403, """{"code":"42501","message":"permission denied for table rounds"}""")
        server.enqueueJson(503, """{"message":"Service Unavailable"}""")
        server.enqueueHtml(540, "<html>Project paused</html>")
        server.enqueueJson(200, "kein json")
        server.enqueueJson(200, """{"unerwartet":true}""")

        assertEquals("SERVER: HTTP 400 etwas_neues", errorOf { api().createRound("x") }?.message)
        assertEquals("SERVER: HTTP 403 42501", errorOf { api().head(round) }?.message)
        assertEquals("SERVER: HTTP 503", errorOf { api().head(round) }?.message)
        assertEquals("SERVER: HTTP 540", errorOf { api().myRounds() }?.message)
        assertEquals("SERVER: Antwort nicht lesbar", errorOf { api().myRounds() }?.message)
        assertEquals("SERVER: Antwort nicht lesbar", errorOf { api().head(round) }?.message)
    }

    @Test
    fun `nach einer Antwort 401 wird einmal mit erneuertem Token wiederholt`() = runTest {
        server.enqueueJson(401, """{"code":"PGRST301","message":"JWT expired"}""")
        server.enqueueJson(200, "[]")

        assertNull(api().head(round))

        assertEquals("Bearer ZUGRIFF-1", server.nextRequest().headers["Authorization"])
        assertEquals("Bearer ZUGRIFF-2", server.nextRequest().headers["Authorization"])
        assertEquals(1, tokens.refreshed)
    }

    @Test
    fun `lehnt der Server auch das neue Token ab, ist die Anmeldung abgelaufen`() = runTest {
        server.enqueueJson(401, """{"code":"PGRST301","message":"JWT expired"}""")
        server.enqueueJson(401, """{"code":"PGRST301","message":"JWT expired"}""")

        assertEquals(RoundError.SIGNED_OUT, errorOf { api().saveEvening(round, evening, 0) }?.error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ohne Anmeldung geht keine Anfrage hinaus`() = runTest {
        tokens.signedOut = true

        assertEquals(RoundError.SIGNED_OUT, errorOf { api().head(round) }?.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ohne Netz ist der Fehler ein anderer als bei einem Fehler des Servers`() = runTest {
        // Ein Server, der nicht mehr läuft: Die Verbindung kommt nicht zustande.
        val dead = MockWebServer().run {
            startLocal()
            baseUrl.also { close() }
        }
        val api = SupabaseRoundApi(SupabaseHttp(TestHttp.client, SupabaseConfig(dead, "sb_publishable_TEST")), tokens)

        val error = errorOf { api.head(round) }

        assertEquals(RoundError.OFFLINE, error?.error)
        assertEquals(0, server.requestCount)
        assertFalse(error?.message.orEmpty().contains("ZUGRIFF"))
    }

    @Test
    fun `eine Weiterleitung wird nicht befolgt - das Token geht nirgendwo anders hin`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", server.baseUrl + "/anderswo")))

        assertEquals(RoundError.SERVER, errorOf { api().head(round) }?.error)
        assertEquals(1, server.requestCount)
    }

    // --- Konfiguration ---

    @Test
    fun `die Konfiguration verlangt eine https-Adresse und einen Schluessel`() {
        assertEquals(SupabaseConfig("https://abc.supabase.co", "sb_publishable_x"), SupabaseConfig.of(" https://abc.supabase.co/ ", " sb_publishable_x\n"))
        assertNull(SupabaseConfig.of("", ""))
        assertNull(SupabaseConfig.of("https://abc.supabase.co", " "))
        assertNull(SupabaseConfig.of("http://abc.supabase.co", "sb_publishable_x"))
        assertNull(SupabaseConfig.of("abc.supabase.co", "sb_publishable_x"))
        assertNull(SupabaseConfig.of("keine adresse", "sb_publishable_x"))
        assertTrue(SupabaseHttp(TestHttp.client, SupabaseConfig("https://abc.supabase.co", "k")).url("rest/v1/rounds").toString().endsWith("/rest/v1/rounds"))
    }
}
