package io.github.q0ochen.schafkopf.app.round

import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.data.round.AuthCallback
import io.github.q0ochen.schafkopf.data.round.RoundAccount
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundApi
import io.github.q0ochen.schafkopf.domain.round.RoundConnection
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundStore
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.RoundSync
import io.github.q0ochen.schafkopf.domain.round.SyncNotice
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** Warum der letzte Abgleich nicht geklappt hat. */
enum class RoundProblem {
    /** Keine Verbindung zum Server. */
    OFFLINE,

    /** Der Server antwortet mit einem Fehler (zum Beispiel, weil das Projekt pausiert ist). */
    SERVER,

    /** Die Anmeldung ist abgelaufen. */
    SIGNED_OUT,
}

/** Eine einmalige Meldung für den Bildschirm „Gemeinsame Runde“. */
enum class RoundMessage {
    /** Die Anmeldung im Browser ist gescheitert. */
    SIGN_IN_FAILED,

    /** Die Anmeldung im Browser wurde abgebrochen. */
    SIGN_IN_CANCELLED,

    /**
     * Angemeldet wurde ein anderes Konto als das der verbundenen Runde. Es wurde gleich wieder abgemeldet; die Runde
     * bleibt verbunden.
     */
    OTHER_ACCOUNT,

    /** Die Runde gibt es nicht mehr, oder das Konto gehört nicht mehr dazu. */
    DETACHED,

    /** Die App wurde mit einem Einladungslink geöffnet, in dem kein gültiger Code steht. */
    INVALID_LINK,
}

/**
 * Der Stand der gemeinsamen Runde für die Oberfläche.
 *
 * @property configured `false`: Diese App wurde ohne Adresse des Servers gebaut; die Funktion ist abgeschaltet.
 * @property loaded Anmeldung und Verbindung sind gelesen (vorher weiß die Oberfläche noch nicht, was sie zeigen soll).
 * @property pending So viele Änderungen dieses Geräts sind noch nicht auf dem Server.
 * @property players Der Stand der Spieler auf dem Server (Besitzer, fester Name), solange eine Runde verbunden ist.
 * @property signingIn Die Anmeldung wird nach der Rückkehr aus dem Browser gerade eingelöst.
 * @property openRound Die App wurde von außen gebeten, den Bildschirm zu zeigen (Einladungslink, Rückkehr aus der
 *   Anmeldung). Die Oberfläche öffnet ihn und meldet das mit [RoundCoordinator.roundOpened] zurück.
 */
data class RoundState(
    val configured: Boolean,
    val loaded: Boolean = false,
    val account: Account? = null,
    val link: RoundLink? = null,
    val syncing: Boolean = false,
    val problem: RoundProblem? = null,
    val pending: Int = 0,
    val unreadable: Int = 0,
    val refused: Int = 0,
    val notices: List<SyncNotice> = emptyList(),
    val players: Map<PlayerId, RemotePlayer> = emptyMap(),
    val pendingInvite: String? = null,
    val message: RoundMessage? = null,
    val signingIn: Boolean = false,
    val openRound: Boolean = false,
) {
    /** Verbunden, aber die Anmeldung fehlt oder ist abgelaufen: Bis zur neuen Anmeldung wird nichts abgeglichen. */
    val needsSignIn: Boolean get() = link != null && (account == null || problem == RoundProblem.SIGNED_OUT)
}

/**
 * Hält die gemeinsame Runde am Laufen (docs/ARCHITEKTUR.md, Abschnitt 8.6): gleicht beim Öffnen der App ab, eine
 * halbe Sekunde nach jeder Änderung auf dem Gerät und alle vier Sekunden, solange die App im Vordergrund ist; bei
 * Fehlern mit wachsendem Abstand. Im Hintergrund ruht der Abgleich; was dann noch wartet, übernimmt [onBackground]
 * (WorkManager).
 *
 * @param changes Meldet jede Änderung an Spielern, Abenden und dem Regelwerk für neue Abende.
 * @param placeholders Die vorbelegten Spieler der App (siehe [RoundConnection.create]).
 * @param onBackground Wird gerufen, wenn die App in den Hintergrund geht und noch etwas hochzuladen ist.
 */
class RoundCoordinator(
    configured: Boolean,
    private val account: RoundAccount,
    private val api: RoundApi,
    private val store: RoundStore,
    private val sync: RoundSync,
    private val connection: RoundConnection,
    private val changes: Flow<Any>,
    private val invites: InviteMemory,
    private val placeholders: List<Player>,
    private val webAppUrl: String,
    private val scope: CoroutineScope,
    private val onBackground: () -> Unit = {},
    private val timing: Timing = Timing(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Die Abstände des Abgleichs in Millisekunden. */
    data class Timing(
        val poll: Long = 4_000,
        val debounce: Long = 500,
        val backoff: List<Long> = listOf(4_000, 8_000, 16_000, 32_000, 60_000),
    )

    private val mutable = MutableStateFlow(RoundState(configured = configured, loaded = !configured))
    val state: StateFlow<RoundState> = mutable.asStateFlow()

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val foreground = MutableStateFlow(false)

    /** Zählt die Änderungen auf dem Gerät. Steht [cleanAt] auf demselben Wert, wartet nichts Ungeprüftes. */
    private val changeCount = AtomicLong(0)

    @Volatile
    private var cleanAt = -1L

    @Volatile
    private var started = false

    /** Beginnt zu arbeiten; einmal beim Start der App. Ohne eingerichteten Server passiert nichts. */
    fun start() {
        if (!state.value.configured || started) return
        started = true
        mutable.update { it.copy(pendingInvite = invites.get()) }
        scope.launch {
            account.load()
            // Wer angemeldet ist und welche Runde verbunden ist: Ändert sich das, wird sofort abgeglichen. Bewegt sich
            // nur das Lesezeichen der Runde (nach jedem Abgleich), ist dafür kein weiterer Abgleich nötig.
            var known: Pair<String?, RoundId?>? = null
            combine(account.account, store.link) { signedIn, link -> signedIn to link }.collect { (signedIn, link) ->
                mutable.update { current ->
                    if (link == null) {
                        // Ohne Runde gibt es nichts abzugleichen: alle Zähler zurück.
                        RoundState(
                            configured = true,
                            loaded = true,
                            account = signedIn,
                            pendingInvite = current.pendingInvite,
                            message = current.message,
                            signingIn = current.signingIn,
                            openRound = current.openRound,
                        )
                    } else {
                        current.copy(
                            loaded = true,
                            account = signedIn,
                            link = link,
                            unreadable = link.unreadable.size,
                            // Nach einer neuen Anmeldung gilt die alte Meldung nicht mehr.
                            problem = current.problem.takeUnless { it == RoundProblem.SIGNED_OUT && signedIn != current.account },
                        )
                    }
                }
                refreshPlayers()
                val now = signedIn?.userId to link?.roundId
                if (now != known) {
                    known = now
                    // Was schon vor dem ersten Abgleich wartet, soll die Statuszeile gleich zeigen.
                    if (link != null) refreshPending()
                    wake()
                }
            }
        }
        scope.launch {
            // Erst der Stand beim Start, dann jede Änderung; mehrere kurz hintereinander zählen als eine.
            changes.drop(1).collectLatest {
                changeCount.incrementAndGet()
                delay(timing.debounce)
                wake()
            }
        }
        scope.launch { loop() }
    }

    /** Die App ist sichtbar bzw. nicht mehr sichtbar. */
    fun setForeground(visible: Boolean) {
        foreground.value = visible
        if (visible) {
            wake()
        } else if (canSync() && (isDirty() || state.value.pending > 0)) {
            onBackground()
        }
    }

    /** „Jetzt abgleichen“: sofort und vollständig. */
    fun syncNow() {
        cleanAt = -1
        wake()
    }

    /** Ein vollständiger Abgleich für die Arbeit im Hintergrund (WorkManager), ohne die Schleife zu wecken. */
    suspend fun syncInBackground(): SyncOutcome {
        val signedIn = account.load()
        val link = store.link.first()
        // Nie unter fremdem Namen: Mit einem anderen Konto sähe die Runde aus, als gäbe es sie nicht mehr.
        if (link != null && signedIn != null && signedIn.userId != link.userId) return SyncOutcome.SignedOut
        return run(full = true)
    }

    // --- Anmelden ---

    /** Beginnt die Anmeldung und liefert die Adresse für den Browser. */
    suspend fun beginSignIn(): String = account.beginSignIn()

    /** Meldet ab und löst die Verbindung auf diesem Gerät. Die Daten bleiben als Kopie hier. */
    suspend fun signOut() {
        connection.disconnect()
        invites.setIssued(null)
        account.signOut()
    }

    /**
     * Ein Link, mit dem die App geöffnet wurde: die Rückkehr aus der Anmeldung oder eine Einladung.
     *
     * @return `false`, wenn der Link nichts mit der gemeinsamen Runde zu tun hat.
     */
    fun onLink(uri: String): Boolean {
        when (val callback = AuthCallback.parse(uri)) {
            is AuthCallback.Code -> scope.launch { completeSignIn(callback.code) }
            is AuthCallback.Failure -> mutable.update {
                it.copy(message = if (callback.error == "access_denied") RoundMessage.SIGN_IN_CANCELLED else RoundMessage.SIGN_IN_FAILED)
            }
            null -> {
                if (!uri.startsWith(InviteLinks.appLink(""))) return false
                val token = InviteLinks.tokenFrom(uri)
                if (token == null) mutable.update { it.copy(message = RoundMessage.INVALID_LINK) } else rememberInvite(token)
            }
        }
        mutable.update { it.copy(openRound = true) }
        return true
    }

    /** Die Oberfläche hat den Bildschirm „Gemeinsame Runde“ auf einen Wunsch von außen hin geöffnet. */
    fun roundOpened() = mutable.update { it.copy(openRound = false) }

    private suspend fun completeSignIn(code: String) {
        mutable.update { it.copy(signingIn = true) }
        try {
            val signedIn = account.completeSignIn(code)
            val link = store.link.first()
            if (link != null && link.userId != signedIn.userId) {
                // Die Runde hängt an einem anderen Konto. Der Abgleich darf nie unter fremdem Namen laufen; trennen
                // wäre aber zu viel (was noch wartet, käme nie mehr an). Also das falsche Konto gleich wieder abmelden.
                account.signOut()
                mutable.update { it.copy(message = RoundMessage.OTHER_ACCOUNT) }
            } else {
                // Geklappt: Ältere Meldungen zur Anmeldung gelten nicht mehr.
                mutable.update { it.copy(message = it.message.takeUnless { message -> message in SIGN_IN_MESSAGES }) }
                syncNow()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Eine Rückkehr, zu der keine Anmeldung (mehr) offen ist, während längst jemand angemeldet ist (zum Beispiel
            // derselbe Link ein zweites Mal): kein Grund für eine Fehlermeldung.
            val stale = e is RoundException && e.detail == RoundAccount.NO_SIGN_IN_PENDING
            val signedIn = state.value.let { it.account != null && it.problem != RoundProblem.SIGNED_OUT }
            if (!(stale && signedIn)) mutable.update { it.copy(message = RoundMessage.SIGN_IN_FAILED) }
        } finally {
            mutable.update { it.copy(signingIn = false) }
        }
    }

    // --- Runden ---

    /** Die Runden des angemeldeten Kontos. */
    suspend fun myRounds(): List<RoundSummary> = api.myRounds()

    suspend fun inviteInfo(token: String): InviteInfo = api.inviteInfo(token)

    /** Ob auf dem Gerät etwas liegt, das beim Beitreten ersetzt würde. */
    suspend fun hasOwnData(): Boolean = connection.hasOwnData(placeholders)

    suspend fun create(name: String): SyncOutcome = applied(connection.create(name, userId(), placeholders))

    /** Tritt bei und übernimmt die Daten der Runde; die Daten dieses Geräts werden dabei ersetzt. */
    suspend fun join(token: String): SyncOutcome {
        val outcome = connection.join(token, userId())
        rememberInvite(null)
        return applied(outcome)
    }

    suspend fun use(round: RoundSummary): SyncOutcome = applied(connection.use(round, userId()))

    /** „Das bin ich“; `player == null` heißt „Ich bin neu“. */
    suspend fun claim(player: PlayerId?, name: String): SyncOutcome = applied(connection.claim(player, name))

    suspend fun leave() {
        connection.leave()
        invites.setIssued(null)
    }

    /**
     * Verlässt eine Runde des Kontos, die nicht mit diesem Gerät verbunden ist. Auf dem Gerät ändert sich nichts; war
     * das Konto das letzte Mitglied, löscht der Server die Runde.
     */
    suspend fun leaveRound(round: RoundSummary) {
        api.leaveRound(round.id)
        if (invites.issued()?.roundId == round.id.value) invites.setIssued(null)
    }

    /**
     * Der Link zum Weitergeben. Eine Einladung, die dieses Gerät vor weniger als 13 Tagen für diese Runde erzeugt hat,
     * wird wiederverwendet (gültig sind 14): Sonst stieße wiederholtes „Einladen“ an die Grenze von 20 gültigen
     * Einladungen je Runde.
     */
    suspend fun inviteLink(): String {
        val link = state.value.link ?: throw RoundException(RoundError.NOT_MEMBER)
        val now = clock.instant()
        val reusable = invites.issued()?.takeIf {
            it.roundId == link.roundId.value && !it.createdAt.isAfter(now) && it.createdAt.isAfter(now.minus(INVITE_REUSE))
        }
        val token = reusable?.token ?: api.createInvite(link.roundId).also {
            invites.setIssued(IssuedInvite(link.roundId.value, it, now))
        }
        return InviteLinks.webLink(webAppUrl, token)
    }

    /** Wie viele Änderungen gerade noch warten (für die Rückfrage vor dem Verlassen). */
    suspend fun pendingCount(): Int = sync.pendingCount()

    fun rememberInvite(token: String?) {
        invites.set(token)
        mutable.update { it.copy(pendingInvite = token) }
    }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun clearNotices() = mutable.update { it.copy(notices = emptyList()) }

    // --- Die Schleife ---

    private fun wake() {
        wakeups.trySend(Unit)
    }

    private fun isDirty(): Boolean = changeCount.get() != cleanAt

    /**
     * Abgeglichen wird nur mit dem Konto, mit dem das Gerät der Runde beigetreten ist. Mit einem anderen Konto sähe der
     * Server die Runde nicht und der Abgleich hielte sie für aufgelöst (siehe [completeSignIn]).
     */
    private fun canSync(): Boolean = state.value.let { it.link != null && it.account != null && it.account.userId == it.link.userId }

    private fun userId(): String = state.value.account?.userId ?: throw RoundException(RoundError.SIGNED_OUT)

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val wait = if (failures == 0) timing.poll else timing.backoff[(failures - 1).coerceAtMost(timing.backoff.lastIndex)]
            // Im Hintergrund nur auf Anstoß; im Vordergrund spätestens nach der Wartezeit.
            val nudged = if (foreground.value) withTimeoutOrNull(wait) { wakeups.receive() } != null else wakeups.receive().let { true }
            // Die Wartezeit ist abgelaufen, aber die App ist inzwischen nicht mehr zu sehen: weiter ruhen.
            if (!nudged && !foreground.value) continue
            if (!canSync()) {
                failures = 0
                continue
            }
            failures = when (run(full = isDirty())) {
                is SyncOutcome.Synced, SyncOutcome.Unchanged, SyncOutcome.NotLinked, SyncOutcome.Detached -> 0
                SyncOutcome.Offline, SyncOutcome.SignedOut, is SyncOutcome.Failed -> failures + 1
            }
        }
    }

    private suspend fun run(full: Boolean): SyncOutcome {
        val seen = changeCount.get()
        mutable.update { it.copy(syncing = true) }
        val outcome = try {
            sync.sync(quick = !full)
        } catch (e: CancellationException) {
            // Zum Beispiel bricht WorkManager den Auftrag ab, wenn das Netz weg ist: nicht „gleicht ab“ stehen lassen.
            mutable.update { it.copy(syncing = false) }
            throw e
        } catch (_: Exception) {
            // Zum Beispiel ein Fehler der Datenbank: Der nächste Versuch kommt von selbst.
            SyncOutcome.Failed(RoundError.SERVER)
        }
        apply(outcome, seen)
        return outcome
    }

    private suspend fun applied(outcome: SyncOutcome): SyncOutcome {
        apply(outcome, seen = changeCount.get())
        return outcome
    }

    private suspend fun apply(outcome: SyncOutcome, seen: Long) {
        when (outcome) {
            is SyncOutcome.Synced -> {
                // Sauber, wenn nichts mehr wartet – oder wenn sich nichts bewegt hat: Dann hilft erst eine Änderung.
                if (outcome.pending == 0 || (outcome.pulled == 0 && outcome.pushed == 0)) cleanAt = seen
                mutable.update {
                    it.copy(
                        syncing = false,
                        problem = null,
                        pending = outcome.pending,
                        unreadable = outcome.unreadable,
                        refused = outcome.refused,
                        notices = (it.notices + outcome.notices).takeLast(MAX_NOTICES),
                    )
                }
                refreshPlayers()
            }
            SyncOutcome.Unchanged -> mutable.update { it.copy(syncing = false, problem = null) }
            SyncOutcome.Offline -> failed(RoundProblem.OFFLINE)
            is SyncOutcome.Failed -> failed(RoundProblem.SERVER)
            SyncOutcome.SignedOut -> failed(RoundProblem.SIGNED_OUT)
            SyncOutcome.Detached -> mutable.update { it.copy(syncing = false, message = RoundMessage.DETACHED) }
            SyncOutcome.NotLinked -> mutable.update { it.copy(syncing = false) }
        }
    }

    private suspend fun failed(problem: RoundProblem) {
        val pending = try {
            sync.pendingCount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value.pending
        }
        mutable.update { it.copy(syncing = false, problem = problem, pending = pending) }
    }

    private suspend fun refreshPending() {
        val pending = try {
            sync.pendingCount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        mutable.update { it.copy(pending = pending) }
    }

    private suspend fun refreshPlayers() {
        val players = if (state.value.link == null) emptyMap() else store.playerBases()
        mutable.update { it.copy(players = players) }
    }

    private companion object {
        /** So viele Hinweise aus dem Abgleich bleiben höchstens stehen. */
        const val MAX_NOTICES = 20

        /** So lange wird eine selbst erzeugte Einladung wiederverwendet (sie gilt 14 Tage). */
        val INVITE_REUSE: Duration = Duration.ofDays(13)

        /** Meldungen, die eine geglückte Anmeldung erledigt. */
        val SIGN_IN_MESSAGES = setOf(RoundMessage.SIGN_IN_FAILED, RoundMessage.SIGN_IN_CANCELLED, RoundMessage.OTHER_ACCOUNT)
    }
}
