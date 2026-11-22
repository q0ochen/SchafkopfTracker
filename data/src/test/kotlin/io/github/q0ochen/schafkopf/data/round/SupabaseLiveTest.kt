package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundStore
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import io.github.q0ochen.schafkopf.domain.round.RemoteEvening
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundConnection
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.RoundSync
import io.github.q0ochen.schafkopf.domain.round.SavedEvening
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Die Anbindung an den Server der gemeinsamen Runde gegen ein **echtes** Supabase: den lokalen Stack der Supabase
 * CLI, den der Workflow „Supabase“ auf GitHub startet. Geprüft wird, was sich mit einem nachgestellten Server nicht
 * prüfen lässt: wie die Datenschnittstelle (PostgREST) und der Anmelde-Server wirklich antworten und ob die App das
 * richtig liest.
 *
 * Läuft nur mit der Marke `live` (siehe `data/build.gradle.kts`), also wenn diese Umgebungsvariablen gesetzt sind:
 * - `SKT_LIVE_URL`: Adresse des Stacks, zum Beispiel `http://127.0.0.1:54321`
 * - `SKT_LIVE_KEY`: der öffentliche Schlüssel (anon bzw. Publishable Key)
 * - `SKT_LIVE_ADMIN_KEY`: der Verwaltungsschlüssel des **lokalen** Stacks, nur um Testkonten anzulegen
 *
 * Testkonten melden sich mit E-Mail und Passwort an (dafür schaltet der Workflow diese Anmeldung im lokalen Stack
 * ein); die Anmeldung mit Google selbst lässt sich so nicht prüfen.
 */
@Tag("live")
class SupabaseLiveTest {

    private val url = System.getenv("SKT_LIVE_URL").orEmpty().trimEnd('/')
    private val key = System.getenv("SKT_LIVE_KEY").orEmpty()
    private val adminKey = System.getenv("SKT_LIVE_ADMIN_KEY").orEmpty()
    private val client = OkHttpClient()
    private val http = SupabaseHttp(client, SupabaseConfig(url, key))

    // --- Testkonten ---

    private class MemoryStorage(var current: StoredSession?) : SessionStorage {
        private var verifier: String? = null

        override suspend fun load(): StoredSession? = current

        override suspend fun save(session: StoredSession) {
            current = session
        }

        override suspend fun clear() {
            current = null
        }

        override suspend fun pendingVerifier(): String? = verifier

        override suspend fun setPendingVerifier(verifier: String?) {
            this.verifier = verifier
        }
    }

    private class TestAccount(
        val userId: String,
        val email: String,
        val firstRefreshToken: String,
        val storage: MemoryStorage,
        val session: RoundSession,
        val api: SupabaseRoundApi,
    )

    /** Schickt JSON an den Anmelde-Server des lokalen Stacks und liefert die Antwort. */
    private fun post(path: String, apiKey: String, bearer: String?, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("$url/$path")
            .header("apikey", apiKey)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) { "POST $path: HTTP ${response.code} $text" }
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content

    /** Legt ein neues Konto an und meldet es an; die App bekommt davon nur, was sie auch sonst behält: das Refresh-Token. */
    private fun newAccount(): TestAccount {
        val email = "probe-${UUID.randomUUID()}@example.org"
        val password = UUID.randomUUID().toString()
        val created = post(
            "auth/v1/admin/users",
            adminKey,
            adminKey,
            buildJsonObject {
                put("email", email)
                put("password", password)
                put("email_confirm", true)
            },
        )
        val signedIn = post(
            "auth/v1/token?grant_type=password",
            key,
            null,
            buildJsonObject {
                put("email", email)
                put("password", password)
            },
        )
        val userId = created.text("id")
        val refreshToken = signedIn.text("refresh_token")
        val storage = MemoryStorage(StoredSession(refreshToken, userId, email))
        val session = RoundSession(SupabaseAuthApi(http), storage)
        return TestAccount(userId, email, refreshToken, storage, session, SupabaseRoundApi(http, session))
    }

    private suspend fun errorOf(block: suspend () -> Unit): RoundException? = try {
        block()
        null
    } catch (e: RoundException) {
        e
    }

    // --- Testdaten ---

    private val seating = listOf("spieler-1", "spieler-2", "spieler-3", "spieler-4").map(::PlayerId)
    private val start: Instant = Instant.parse("2026-12-12T18:00:00Z")

    private fun game(id: String, seconds: Long, note: String = ""): GameEntry = GameEntry(
        id = EntryId(id),
        recordedAt = start.plusSeconds(seconds).plusMillis(250),
        type = GameType.RUFSPIEL,
        declarer = seating[0],
        partner = seating[1],
        declarerWon = true,
        laufende = 3,
        note = note,
    )

    private fun evening(id: String, vararg entries: GameEntry): Evening = Evening(
        id = EveningId(id),
        startedAt = start,
        location = "Wirtshaus „Zum Ober“",
        seating = seating,
        firstDealer = seating[0],
        rules = RuleSet.standard(),
        entries = entries.toList(),
    )

    private suspend fun SupabaseRoundApi.savePlaceholders(round: RoundId) {
        seating.forEachIndexed { index, id -> savePlayer(round, Player(id, "Spieler ${index + 1}", colorIndex = index)) }
    }

    // --- Anmeldung ---

    @Test
    fun `die Anmeldung laesst sich erneuern und beenden`() = runBlocking {
        val account = newAccount()

        // Die App hat nur das Refresh-Token: Der erste Aufruf holt damit ein Zugriffstoken.
        assertEquals(emptyList<RoundSummary>(), account.api.myRounds())

        assertEquals(Account(account.userId, account.email), account.session.account.value)
        val renewed = checkNotNull(account.storage.current)
        assertEquals(account.userId, renewed.userId)
        assertNotEquals(account.firstRefreshToken, renewed.refreshToken, "Das Refresh-Token wird bei jeder Erneuerung ersetzt.")

        // Noch einmal erneuern, wie nach einer Antwort 401.
        val token = account.session.accessToken()
        val again = account.session.refreshAfterUnauthorized(token)
        assertTrue(again.isNotBlank())
        assertEquals(emptyList<RoundSummary>(), account.api.myRounds())
        val beforeSignOut = checkNotNull(account.storage.current).refreshToken

        account.session.signOut()

        assertNull(account.session.account.value)
        assertNull(account.storage.current)
        // Der Server kennt die Sitzung nicht mehr: Das gilt als endgültig abgemeldet, nicht als Störung.
        val gone = errorOf { SupabaseAuthApi(http).refresh(beforeSignOut) }
        assertEquals(RoundError.SIGNED_OUT, gone?.error)
        assertEquals(AuthApi.SESSION_GONE, gone?.detail)
        // Ein Token, das der Server nie ausgegeben hat, lehnt er schon wegen seiner Form ab („validation_failed“):
        // Die App bittet dann um eine neue Anmeldung, wirft die gespeicherte Sitzung aber nicht von sich aus weg.
        val unknown = errorOf { SupabaseAuthApi(http).refresh("dieses-token-gab-es-nie") }
        assertEquals(RoundError.SIGNED_OUT, unknown?.error)
        assertNotEquals(AuthApi.SESSION_GONE, unknown?.detail)
        assertEquals(RoundError.SIGNED_OUT, errorOf { account.api.myRounds() }?.error)
    }

    @Test
    fun `ein abgelehntes Zugriffstoken wird genau einmal erneuert`() = runBlocking {
        val account = newAccount()
        val recovering = object : AccessTokenSource {
            var rejected: String? = null

            override suspend fun accessToken(): String = "kein.gueltiges.token"

            override suspend fun refreshAfterUnauthorized(rejected: String): String {
                this.rejected = rejected
                return account.session.accessToken()
            }
        }

        assertEquals(emptyList<RoundSummary>(), SupabaseRoundApi(http, recovering).myRounds())
        assertEquals("kein.gueltiges.token", recovering.rejected)

        val broken = object : AccessTokenSource {
            var refreshed = 0

            override suspend fun accessToken(): String = "kein.gueltiges.token"

            override suspend fun refreshAfterUnauthorized(rejected: String): String {
                refreshed++
                return "immer.noch.keins"
            }
        }
        assertEquals(RoundError.SIGNED_OUT, errorOf { SupabaseRoundApi(http, broken).myRounds() }?.error)
        assertEquals(1, broken.refreshed)
    }

    @Test
    fun `die Adresse fuer den Browser fuehrt zur Anmeldung bei Google`() {
        val challenge = Pkce.challenge(Pkce.newVerifier())
        val address = SupabaseAuthApi(http).authorizeUrl(challenge)

        val request = Request.Builder().url(address).header("apikey", key).build()
        val noRedirects = client.newBuilder().followRedirects(false).build()
        noRedirects.newCall(request).execute().use { response ->
            // Der Anmelde-Server nimmt die Angaben an (Anbieter, PKCE) und schickt den Browser weiter zu Google. Der
            // Workflow hinterlegt dafür im lokalen Stack erfundene Zugangsdaten; angemeldet wird hier niemand.
            assertTrue(response.code in 300..399, "HTTP ${response.code} statt einer Weiterleitung: ${response.body.string().take(300)}")
            val target = response.header("Location").orEmpty()
            assertTrue(target.startsWith("https://accounts.google.com/"), "Weiterleitung nach: ${target.take(80)}")
        }
    }

    // --- Runde, Einladung, Mitglieder, Spieler ---

    @Test
    fun `eine Runde anlegen einladen beitreten und den eigenen Namen festlegen`() = runBlocking {
        val anna = newAccount()
        val bert = newAccount()
        val stranger = newAccount()

        val round = anna.api.createRound("  Stammtisch   am  Freitag ")

        assertTrue(Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$").matches(round.value), "Kennung: ${round.value}")
        val created = checkNotNull(anna.api.head(round))
        assertEquals("Stammtisch am Freitag", created.name)
        assertEquals(1L, created.rev)
        assertNull(created.rules)
        assertFalse(created.rulesUnreadable)
        assertEquals(anna.userId, created.createdBy)
        assertEquals(listOf(RoundSummary(round, "Stammtisch am Freitag", 1, 1, null, isCreator = true)), anna.api.myRounds())
        assertEquals(listOf(RoundMember(anna.userId, null)), anna.api.members(round))

        // Wer nicht dazugehört, sieht nichts und darf nichts.
        assertNull(stranger.api.head(round))
        assertEquals(emptyList<RoundMember>(), stranger.api.members(round))
        assertEquals(emptyList<RemotePlayer>(), stranger.api.playersSince(round, 0))
        assertEquals(emptyList<RemoteEvening>(), stranger.api.eveningsSince(round, 0))
        assertEquals(RoundError.NOT_MEMBER, errorOf { stranger.api.createInvite(round) }?.error)
        assertEquals(RoundError.NOT_MEMBER, errorOf { stranger.api.savePlayer(round, Player(PlayerId("x"), "Xaver", 0)) }?.error)
        assertEquals(RoundError.NOT_MEMBER, errorOf { stranger.api.saveEvening(round, evening("abend-x"), 0) }?.error)
        assertEquals(RoundError.NOT_MEMBER, errorOf { stranger.api.saveRules(round, RuleSet.standard()) }?.error)
        assertEquals(RoundError.NOT_MEMBER, errorOf { stranger.api.leaveRound(round) }?.error)

        // Einladen und beitreten.
        val token = anna.api.createInvite(round)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(token), "Einladungscode: $token")
        assertEquals(token, InviteLinks.tokenFrom(InviteLinks.webLink("https://example.org/app/", token)))
        assertEquals(InviteInfo(round, "Stammtisch am Freitag", 1, alreadyMember = false), bert.api.inviteInfo(token))
        assertTrue(anna.api.inviteInfo(token).alreadyMember)
        assertEquals(RoundError.INVALID_INVITE, errorOf { bert.api.inviteInfo("0".repeat(32)) }?.error)
        assertEquals(RoundError.INVALID_INVITE, errorOf { bert.api.joinRound("kein Code") }?.error)

        assertEquals(round, bert.api.joinRound(token))
        assertEquals(round, bert.api.joinRound(token), "Ein zweites Mal beitreten ändert nichts.")

        assertEquals(2L, checkNotNull(bert.api.head(round)).rev)
        assertEquals(listOf(RoundMember(anna.userId, null), RoundMember(bert.userId, null)), anna.api.members(round))
        assertEquals(listOf(RoundSummary(round, "Stammtisch am Freitag", 2, 2, null, isCreator = false)), bert.api.myRounds())

        // Spieler anlegen: Der Name wird bereinigt, die Reihenfolge gezählt.
        assertEquals(3L, anna.api.savePlayer(round, Player(seating[0], "  Anna  ", colorIndex = 0)))
        assertEquals(4L, anna.api.savePlayer(round, Player(seating[1], "Spieler 2", colorIndex = 1)))
        assertEquals(
            listOf(
                RemotePlayer(seating[0], "Anna", 0, archived = false, position = 1, rev = 3),
                RemotePlayer(seating[1], "Spieler 2", 1, archived = false, position = 2, rev = 4),
            ),
            bert.api.playersSince(round, 0),
        )
        assertEquals(listOf(seating[1]), bert.api.playersSince(round, 3).map { it.id })
        assertEquals(RoundError.NAME_TAKEN, errorOf { bert.api.savePlayer(round, Player(seating[2], "anna", 2)) }?.error)
        assertEquals(RoundError.INVALID_NAME, errorOf { bert.api.savePlayer(round, Player(seating[2], "   ", 2)) }?.error)
        assertEquals(RoundError.INVALID_NAME, errorOf { bert.api.savePlayer(round, Player(seating[2], "x".repeat(25), 2)) }?.error)
        assertEquals(4L, checkNotNull(anna.api.head(round)).rev, "Abgelehntes zählt nicht.")

        // „Das bin ich“: Bert übernimmt den zweiten Spieler und legt seinen Namen fest.
        bert.api.claimPlayer(round, seating[1], " Bert ", 5)

        assertEquals(listOf(RoundMember(anna.userId, null), RoundMember(bert.userId, seating[1])), anna.api.members(round))
        assertEquals(
            listOf(RemotePlayer(seating[1], "Bert", 5, archived = false, position = 2, owner = bert.userId, nameLocked = true, rev = 5)),
            anna.api.playersSince(round, 4),
        )
        // member_names (seit dem 21.11.2026): die Namen der übernommenen Spieler, hier nur Berts.
        assertEquals(
            listOf(RoundSummary(round, "Stammtisch am Freitag", 5, 2, seating[1], isCreator = false, memberNames = listOf("Bert"))),
            bert.api.myRounds(),
        )
        // Derselbe Aufruf noch einmal (zum Beispiel nach einer verlorenen Antwort): Der Name bleibt.
        bert.api.claimPlayer(round, seating[1], "Anders", 6)
        assertEquals("Bert", anna.api.playersSince(round, 5).single().name)
        assertEquals(RoundError.ALREADY_CLAIMED, errorOf { bert.api.claimPlayer(round, seating[0], "Bert", 0) }?.error)
        assertEquals(RoundError.PLAYER_TAKEN, errorOf { anna.api.claimPlayer(round, seating[1], "Anna", 0) }?.error)
        assertEquals(RoundError.NAME_TAKEN, errorOf { anna.api.claimPlayer(round, PlayerId("neu"), "BERT", 0) }?.error)

        // Ein fester Name bleibt; nur wer die Runde angelegt hat, darf ihn korrigieren. Gelöscht werden nur Gäste.
        assertEquals(RoundError.NAME_LOCKED, errorOf { bert.api.savePlayer(round, Player(seating[1], "Berti", 6)) }?.error)
        anna.api.savePlayer(round, Player(seating[1], "Berthold", 6, archived = true))
        val corrected = anna.api.playersSince(round, 6).single()
        assertEquals(RemotePlayer(seating[1], "Berthold", 6, archived = true, position = 2, owner = bert.userId, nameLocked = true, rev = 7), corrected)
        assertEquals(RoundError.PLAYER_OWNED, errorOf { anna.api.savePlayer(round, Player(seating[1], "Berthold", 6), deleted = true) }?.error)
        assertEquals(8L, anna.api.savePlayer(round, Player(seating[0], "Anna", 0), deleted = true))
        assertTrue(anna.api.playersSince(round, 7).single().deleted)
        assertEquals(8L, anna.api.savePlayer(round, Player(PlayerId("gibt-es-nicht"), "Niemand", 0), deleted = true), "Nichts zu löschen: rev bleibt.")

        // Verlassen und wiederkommen: Der eigene Spieler ist wieder verknüpft.
        bert.api.leaveRound(round)

        assertNull(bert.api.head(round))
        assertEquals(emptyList<RoundSummary>(), bert.api.myRounds())
        assertEquals(listOf(RoundMember(anna.userId, null)), anna.api.members(round))
        assertEquals(RoundError.NOT_MEMBER, errorOf { bert.api.savePlayer(round, Player(seating[2], "Cäcilie", 2)) }?.error)
        assertEquals(round, bert.api.joinRound(token))
        assertEquals(RoundMember(bert.userId, seating[1]), anna.api.members(round).last())

        // Geht das letzte Mitglied, gibt es die Runde nicht mehr.
        bert.api.leaveRound(round)
        anna.api.leaveRound(round)
        assertNull(anna.api.head(round))
        assertEquals(emptyList<RoundSummary>(), anna.api.myRounds())
        assertEquals(RoundError.INVALID_INVITE, errorOf { stranger.api.joinRound(token) }?.error)
    }

    // --- Abende und Regelwerk ---

    @Test
    fun `Abende werden nur mit der erwarteten Fassung ersetzt`() = runBlocking {
        val anna = newAccount()
        val round = anna.api.createRound("Abende")
        anna.api.savePlaceholders(round)
        val first = evening("abend-1", game("spiel-1", 60, note = "Schneider! „Herz“ sticht – 10 € 🂱"), game("spiel-2", 120))

        assertEquals(SavedEvening(version = 1, rev = 6), anna.api.saveEvening(round, first, expectedVersion = 0))

        // Der Inhalt kommt unverändert zurück, auch Umlaute, Anführungszeichen und Bruchteile von Sekunden.
        assertEquals(RemoteEvening(first.id, 1, deleted = false, rev = 6, evening = first), anna.api.evening(round, first.id))
        assertNull(anna.api.evening(round, EveningId("gibt-es-nicht")))

        // Die erwartete Fassung passt nicht: kein Fehler, sondern nichts, und nichts ändert sich.
        val changed = first.append(game("spiel-3", 180))
        assertNull(anna.api.saveEvening(round, changed, expectedVersion = 0))
        assertNull(anna.api.saveEvening(round, changed, expectedVersion = 2))
        assertEquals(6L, checkNotNull(anna.api.head(round)).rev)
        assertEquals(first, anna.api.evening(round, first.id)?.evening)

        assertEquals(SavedEvening(version = 2, rev = 7), anna.api.saveEvening(round, changed, expectedVersion = 1))
        assertEquals(listOf(RemoteEvening(first.id, 2, deleted = false, rev = 7, evening = changed)), anna.api.eveningsSince(round, 6))
        assertEquals(emptyList<RemoteEvening>(), anna.api.eveningsSince(round, 7))

        // Löschen ist eine Markierung; die Zeile bleibt, damit alle Geräte davon erfahren.
        assertEquals(SavedEvening(version = 3, rev = 8), anna.api.saveEvening(round, changed, expectedVersion = 2, deleted = true))
        val deleted = checkNotNull(anna.api.evening(round, first.id))
        assertTrue(deleted.deleted)
        assertEquals(3, deleted.version)

        // Was der Server nicht annimmt, trifft nur diesen einen Abend.
        val withNul = evening("abend-nul", game("spiel-nul", 60, note = "a\u0000b"))
        assertEquals(RoundError.INVALID_DOC, errorOf { anna.api.saveEvening(round, withNul, 0) }?.error)
        val tooLarge = evening("abend-gross", game("spiel-gross", 60, note = "x".repeat(300_001)))
        assertEquals(RoundError.TOO_LARGE, errorOf { anna.api.saveEvening(round, tooLarge, 0) }?.error)
        assertEquals(8L, checkNotNull(anna.api.head(round)).rev)
        // Knapp darunter geht es.
        val large = evening("abend-fast-zu-gross", game("spiel-fast", 60, note = "x".repeat(290_000)))
        assertEquals(SavedEvening(version = 1, rev = 9), anna.api.saveEvening(round, large, 0))
        assertEquals(large, anna.api.evening(round, large.id)?.evening)
    }

    @Test
    fun `viele Abende werden seitenweise und vollstaendig gelesen`() = runBlocking {
        val anna = newAccount()
        val round = anna.api.createRound("Viele Abende")
        val count = 205
        repeat(count) { index ->
            val id = "abend-%03d".format(index)
            checkNotNull(anna.api.saveEvening(round, evening(id, game("spiel-$id", 60)), expectedVersion = 0))
        }

        val all = anna.api.eveningsSince(round, 0)

        assertEquals(count, all.size)
        assertEquals(count, all.map { it.id }.toSet().size)
        assertEquals((2L..(count + 1L)).toList(), all.map { it.rev })
        assertTrue(all.all { it.evening != null && it.version == 1 && !it.deleted })
        assertEquals(5, anna.api.eveningsSince(round, count - 4L).size)
    }

    @Test
    fun `das Regelwerk der Runde wird gespeichert und gelesen`() = runBlocking {
        val anna = newAccount()
        val round = anna.api.createRound("Regeln")

        assertEquals(2L, anna.api.saveRules(round, RuleSet.sauspiel()))

        val head = checkNotNull(anna.api.head(round))
        assertEquals(RuleSet.sauspiel(), head.rules)
        assertFalse(head.rulesUnreadable)
        assertEquals(2L, head.rev)
        assertEquals(3L, anna.api.saveRules(round, RuleSet.standard()))
        assertEquals(RuleSet.standard(), checkNotNull(anna.api.head(round)).rules)
    }

    @Test
    fun `mehr als fuenf Runden je Konto und doppelte Namen lehnt der Server ab`() = runBlocking {
        val anna = newAccount()
        assertEquals(RoundError.INVALID_NAME, errorOf { anna.api.createRound("   ") }?.error)
        assertEquals(RoundError.INVALID_NAME, errorOf { anna.api.createRound("x".repeat(41)) }?.error)
        repeat(5) { anna.api.createRound("Runde ${it + 1}") }

        // Seit dem 21.11.2026: ein Name je Konto, ohne Groß/Klein, geprüft vor der Obergrenze.
        assertEquals(RoundError.NAME_TAKEN, errorOf { anna.api.createRound("  RUNDE   1 ") }?.error)
        assertEquals(RoundError.LIMIT_REACHED, errorOf { anna.api.createRound("Runde 6") }?.error)
        assertEquals(List(5) { "Runde ${it + 1}" }, anna.api.myRounds().map { it.name })
    }

    // --- Der ganze Abgleich mit zwei Geräten ---

    /** Ein nachgestelltes Handy mit eigenen Daten; der Server ist der echte. */
    private class Device(val account: TestAccount) {
        val players = InMemoryPlayerRepository()
        val evenings = InMemoryEveningRepository()
        val settings = InMemorySettingsRepository()
        val store = InMemoryRoundStore()
        private var counter = 0
        private val ids = IdGenerator { "${account.userId.take(8)}-${++counter}" }
        val editor = EveningEditor(evenings, Clock.systemUTC(), ids)
        val sync = RoundSync(account.api, store, players, evenings, settings, editor)
        val connection = RoundConnection(account.api, store, players, evenings, sync, ids)

        suspend fun synced(): SyncOutcome.Synced {
            val outcome = sync.sync()
            check(outcome is SyncOutcome.Synced) { "Abgleich: $outcome" }
            return outcome
        }

        suspend fun addGame(evening: EveningId): EntryId {
            val id = editor.newEntryId()
            editor.update(evening) {
                it.append(
                    GameEntry(
                        id = id,
                        recordedAt = editor.now(),
                        type = GameType.RUFSPIEL,
                        declarer = it.seating[0],
                        partner = it.seating[1],
                        declarerWon = true,
                    ),
                )
            }
            return id
        }

        suspend fun allPlayers(): List<Player> = players.players.first().sortedBy { it.id.value }

        suspend fun allEvenings(): List<Evening> = evenings.evenings.first().sortedBy { it.id.value }
    }

    private val placeholders = seating.mapIndexed { index, id -> Player(id, "Spieler ${index + 1}", colorIndex = index) }

    @Test
    fun `zwei Geraete gleichen sich ueber den echten Server ab`() = runBlocking {
        val one = Device(newAccount())
        val two = Device(newAccount())

        // Das erste Handy hat schon einen Abend und bringt ihn mit in die neue Runde.
        placeholders.forEach { one.players.save(it) }
        val evening = evening("abend-live", game("spiel-1", 60))
        one.evenings.save(evening)
        check(one.connection.create("Runde am Tisch", one.account.userId, placeholders) is SyncOutcome.Synced)
        val round = checkNotNull(one.store.link.first()).roundId
        assertEquals(0, one.sync.pendingCount())
        assertEquals(evening, one.account.api.evening(round, evening.id)?.evening)
        assertEquals(4, one.account.api.playersSince(round, 0).size)

        // Das zweite Handy hat eigene Daten; sie werden durch die der Runde ersetzt.
        placeholders.forEach { two.players.save(it) }
        two.evenings.save(evening("abend-eigener"))
        val token = one.account.api.createInvite(round)
        check(two.connection.join(token, two.account.userId) is SyncOutcome.Synced)

        assertEquals(listOf(evening), two.allEvenings())
        assertEquals(one.allPlayers(), two.allPlayers())

        // „Das bin ich“ auf beiden Handys: die festen Namen erscheinen überall.
        check(one.connection.claim(seating[0], "Anna") is SyncOutcome.Synced)
        check(two.connection.claim(seating[1], "Bert") is SyncOutcome.Synced)
        one.synced()
        assertEquals(listOf("Anna", "Bert", "Spieler 3", "Spieler 4"), one.allPlayers().map { it.name })
        assertEquals(one.allPlayers(), two.allPlayers())
        assertEquals(seating[1], checkNotNull(two.store.link.first()).playerId)
        assertEquals(
            listOf(RoundMember(one.account.userId, seating[0]), RoundMember(two.account.userId, seating[1])),
            checkNotNull(one.store.link.first()).members,
        )

        // Beide tragen gleichzeitig ein; nach dem Abgleich haben beide dasselbe, in derselben Reihenfolge.
        val fromOne = one.addGame(evening.id)
        val fromTwo = two.addGame(evening.id)
        one.synced()
        two.synced()
        one.synced()
        val merged = checkNotNull(one.evenings.evening(evening.id).first())
        assertEquals(setOf(EntryId("spiel-1"), fromOne, fromTwo), merged.entries.map { it.id }.toSet())
        assertEquals(merged, two.evenings.evening(evening.id).first())

        // Löschen und ein neuer Abend wandern auch hinüber.
        two.editor.update(evening.id) { it.remove(fromOne) }
        two.evenings.save(evening("abend-zwei", game("spiel-zwei", 60)))
        two.synced()
        one.synced()
        assertEquals(two.allEvenings(), one.allEvenings())
        assertFalse(checkNotNull(one.evenings.evening(evening.id).first()).entries.any { it.id == fromOne })
        assertEquals(2, one.allEvenings().size)

        // Ruhe: Ohne Änderung meldet der schnelle Abgleich „nichts Neues“, und nichts wartet.
        assertEquals(SyncOutcome.Unchanged, one.sync.sync(quick = true))
        assertEquals(SyncOutcome.Unchanged, two.sync.sync(quick = true))
        assertEquals(0, one.sync.pendingCount())
        assertEquals(0, two.sync.pendingCount())

        // Wer die Runde verlässt, behält seine Daten als Kopie; der andere erfährt davon.
        two.connection.leave()
        assertNull(two.store.link.first())
        assertEquals(2, two.allEvenings().size)
        one.synced()
        assertEquals(listOf(RoundMember(one.account.userId, seating[0])), checkNotNull(one.store.link.first()).members)
        assertEquals(SyncOutcome.NotLinked, two.sync.sync())
    }
}
