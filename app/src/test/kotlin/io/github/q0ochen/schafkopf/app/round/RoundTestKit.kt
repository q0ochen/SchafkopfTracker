package io.github.q0ochen.schafkopf.app.round

import io.github.q0ochen.schafkopf.app.SchafkopfApp
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.data.round.RoundAccount
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundServer
import io.github.q0ochen.schafkopf.domain.round.InMemoryRoundStore
import io.github.q0ochen.schafkopf.domain.round.RoundConnection
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundStore
import io.github.q0ochen.schafkopf.domain.round.RoundSync
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.round.RoundApi
import io.github.q0ochen.schafkopf.domain.round.RoundHead
import io.github.q0ochen.schafkopf.domain.round.RoundId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant

/** Eine Anmeldung ohne Server: „angemeldet“ ist, wen der Test anmeldet. */
internal class FakeAccount(private val stored: Account? = null) : RoundAccount {
    override val account = MutableStateFlow<Account?>(null)

    /** Das Konto, das die nächste Anmeldung im Browser ergibt. */
    var next = Account("konto-1", "konto-1@example.org")

    /** Die nächste Anmeldung scheitert. */
    var fail = false

    /** Zur nächsten Rückkehr gibt es keine begonnene Anmeldung (zum Beispiel derselbe Link ein zweites Mal). */
    var noPendingSignIn = false

    /** Solange gesetzt, hängt das Einlösen der Anmeldung, bis der Test es freigibt. */
    var exchange: CompletableDeferred<Unit>? = null
    var signOuts = 0

    override suspend fun load(): Account? {
        if (account.value == null) account.value = stored
        return account.value
    }

    override suspend fun beginSignIn(): String = "https://anmeldung.example/authorize"

    override suspend fun completeSignIn(code: String): Account {
        exchange?.await()
        if (noPendingSignIn) throw RoundException(RoundError.SIGNED_OUT, RoundAccount.NO_SIGN_IN_PENDING)
        if (fail) throw RoundException(RoundError.SERVER, "Anmeldung")
        account.value = next
        return next
    }

    override suspend fun signOut() {
        signOuts++
        account.value = null
    }
}

/** Ein Zugang zum Server, dessen Kopf-Anfrage ein Test anhalten kann (zum Beispiel: langsames Netz). */
internal class GatedApi(private val inner: RoundApi) : RoundApi by inner {
    /** Solange gesetzt, wartet jede Anfrage nach dem Kopf der Runde, bis der Test sie freigibt. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun head(round: RoundId): RoundHead? {
        gate?.await()
        return inner.head(round)
    }
}

internal class MemoryInvites(private var token: String? = null) : InviteMemory {
    private var issued: IssuedInvite? = null

    override fun get(): String? = token

    override fun set(token: String?) {
        this.token = token
    }

    override fun issued(): IssuedInvite? = issued

    override fun setIssued(invite: IssuedInvite?) {
        issued = invite
    }
}

/**
 * Ein nachgestelltes Handy für die Tests der App: Daten und Gerätezustand im Speicher (oder die übergebenen), ein
 * Zugang zum nachgestellten Server und der Koordinator darüber.
 */
internal class RoundTestKit(
    scope: CoroutineScope,
    val server: InMemoryRoundServer = InMemoryRoundServer(),
    val userId: String = "konto-1",
    signedIn: Boolean = true,
    configured: Boolean = true,
    val players: PlayerRepository = InMemoryPlayerRepository(),
    val evenings: EveningRepository = InMemoryEveningRepository(),
    val settings: SettingsRepository = InMemorySettingsRepository(),
    val store: RoundStore = InMemoryRoundStore(),
    timing: RoundCoordinator.Timing = RoundCoordinator.Timing(),
    private val clock: Clock = Clock.systemUTC(),
) {
    val session = server.sessionFor(userId)
    val api = GatedApi(session)
    val account = FakeAccount(if (signedIn) Account(userId, "$userId@example.org") else null).apply {
        next = Account(userId, "$userId@example.org")
    }
    private var counter = 0
    private val ids = IdGenerator { "$userId-${++counter}" }
    val editor = EveningEditor(evenings, clock, ids)
    val sync = RoundSync(api, store, players, evenings, settings, editor)
    val connection = RoundConnection(api, store, players, evenings, sync, ids)
    val invites = MemoryInvites()
    var backgroundRequests = 0
    val coordinator = RoundCoordinator(
        configured = configured,
        account = account,
        api = api,
        store = store,
        sync = sync,
        connection = connection,
        changes = AutoBackupScheduler.changesOf(players, evenings, settings),
        invites = invites,
        placeholders = SchafkopfApp.DEFAULT_PLAYERS,
        webAppUrl = "https://example.org/app/",
        scope = scope,
        onBackground = { backgroundRequests++ },
        timing = timing,
        clock = clock,
    )

    val state: RoundState get() = coordinator.state.value

    /** Legt die vorbelegten Spieler an und einen Abend mit ihnen. */
    suspend fun seed(evening: String = "abend-1"): EveningId {
        SchafkopfApp.DEFAULT_PLAYERS.forEach { players.save(it) }
        val seating = SchafkopfApp.DEFAULT_PLAYERS.map { it.id }
        val id = EveningId(evening)
        evenings.save(
            Evening(
                id = id,
                startedAt = Instant.parse("2026-12-12T18:00:00Z"),
                seating = seating,
                firstDealer = seating.first(),
                rules = settings.settings.first().defaultRules,
            ),
        )
        return id
    }

    suspend fun addGame(evening: EveningId, won: Boolean = true) {
        editor.update(evening) {
            it.append(
                GameEntry(
                    id = editor.newEntryId(),
                    recordedAt = editor.now(),
                    type = GameType.RUFSPIEL,
                    declarer = it.seating[0],
                    partner = it.seating[1],
                    declarerWon = won,
                ),
            )
        }
    }
}
