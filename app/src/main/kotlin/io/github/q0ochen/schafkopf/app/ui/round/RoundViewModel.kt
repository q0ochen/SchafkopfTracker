package io.github.q0ochen.schafkopf.app.ui.round

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Was beim Beitreten oder Verwenden einer Runde noch bestätigt werden muss. */
sealed interface PendingAttach {
    data class Join(val token: String) : PendingAttach
    data class Use(val round: RoundSummary) : PendingAttach
}

/** Eine Rückfrage, bevor das Gerät von der Runde getrennt wird. */
enum class Farewell { LEAVE, SIGN_OUT }

/** Ein Fehler, den der Bildschirm in Worten zeigt. */
sealed interface RoundUiError {
    /** Im eingefügten Text steht kein Einladungscode. */
    data object NoCode : RoundUiError

    /** Auf dem Handy gibt es keinen Browser für die Anmeldung. */
    data object NoBrowser : RoundUiError

    /** @property roundName Es ging um den Namen einer Runde. */
    data class Server(val error: RoundError, val roundName: Boolean = false) : RoundUiError
}

/**
 * Was der Bildschirm „Gemeinsame Runde“ über den Stand der Runde hinaus braucht.
 *
 * @property rounds Die Runden des Kontos; `null`, solange sie nicht gelesen sind.
 * @property invite Was die wartende Einladung verrät; `null`, solange unbekannt.
 * @property replace Wartet auf die Rückfrage „Daten dieses Geräts ersetzen?“.
 * @property eveningCount So viele Abende lägen beim Ersetzen auf dem Gerät.
 * @property farewell Wartet auf die Rückfrage vor dem Verlassen oder Abmelden; [farewellPending] Änderungen warten noch.
 * @property signInUrl Diese Adresse soll der Browser jetzt öffnen.
 * @property shareLink Diesen Einladungslink soll das Teilen-Menü jetzt anbieten.
 */
data class RoundUi(
    val busy: Boolean = false,
    val error: RoundUiError? = null,
    val rounds: List<RoundSummary>? = null,
    val invite: InviteInfo? = null,
    val replace: PendingAttach? = null,
    val eveningCount: Int = 0,
    val farewell: Farewell? = null,
    val farewellPending: Int = 0,
    val signInUrl: String? = null,
    val shareLink: String? = null,
)

class RoundViewModel(
    private val coordinator: RoundCoordinator,
    players: PlayerRepository,
    private val evenings: EveningRepository,
) : ViewModel() {

    val state: StateFlow<RoundState> = coordinator.state

    val players: StateFlow<List<Player>> = players.players.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutable = MutableStateFlow(RoundUi())
    val ui: StateFlow<RoundUi> = mutable.asStateFlow()

    init {
        // Sobald jemand angemeldet ist und noch keine Runde verbunden hat: seine Runden und die Einladung nachsehen.
        viewModelScope.launch {
            coordinator.state
                .map { Triple(it.account?.userId, it.link == null, it.pendingInvite) }
                .distinctUntilChanged()
                .collect { (userId, unlinked, _) ->
                    if (userId != null && unlinked) refresh() else mutable.update { it.copy(rounds = null, invite = null) }
                }
        }
    }

    /** Liest die Runden des Kontos und die wartende Einladung. Scheitert das, bleibt der Bildschirm benutzbar. */
    fun refresh() {
        viewModelScope.launch {
            val rounds = attempt { coordinator.myRounds() }
            mutable.update { it.copy(rounds = rounds ?: it.rounds) }
            val token = coordinator.state.value.pendingInvite
            if (token == null) {
                mutable.update { it.copy(invite = null) }
                return@launch
            }
            try {
                val invite = coordinator.inviteInfo(token)
                mutable.update { it.copy(invite = invite) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoundException) {
                if (e.error == RoundError.INVALID_INVITE) {
                    // Abgelaufen oder ungültig: sagen und vergessen, sonst käme die Meldung bei jedem Öffnen wieder.
                    coordinator.rememberInvite(null)
                    mutable.update { it.copy(invite = null, error = RoundUiError.Server(e.error)) }
                }
            } catch (_: Exception) {
                // Ohne Netz bleibt die Einladung einfach stehen; „Beitreten“ versucht es dann noch einmal.
            }
        }
    }

    // --- Anmelden und Abmelden ---

    fun signIn() = busy { mutable.update { it.copy(signInUrl = coordinator.beginSignIn()) } }

    /** Der Browser ist geöffnet (oder es gibt keinen). */
    fun signInOpened(found: Boolean) =
        mutable.update { it.copy(signInUrl = null, error = if (found) it.error else RoundUiError.NoBrowser) }

    fun askFarewell(kind: Farewell) = busy {
        val pending = coordinator.pendingCount()
        mutable.update { it.copy(farewell = kind, farewellPending = pending) }
    }

    fun cancelFarewell() = mutable.update { it.copy(farewell = null) }

    fun confirmFarewell() {
        val kind = mutable.value.farewell ?: return
        mutable.update { it.copy(farewell = null) }
        busy {
            when (kind) {
                Farewell.LEAVE -> coordinator.leave()
                Farewell.SIGN_OUT -> coordinator.signOut()
            }
        }
    }

    // --- Runde anlegen, beitreten, verwenden ---

    fun create(name: String) = busy(roundName = true) { requireSynced(coordinator.create(name)) }

    /** Löst einen eingefügten Link oder Code ein. */
    fun redeem(text: String) {
        val token = InviteLinks.tokenFrom(text)
        if (token == null) mutable.update { it.copy(error = RoundUiError.NoCode) } else requestAttach(PendingAttach.Join(token))
    }

    /** Tritt der Runde der wartenden Einladung bei. */
    fun joinInvited() {
        coordinator.state.value.pendingInvite?.let { requestAttach(PendingAttach.Join(it)) }
    }

    fun use(round: RoundSummary) = requestAttach(PendingAttach.Use(round))

    fun discardInvite() {
        coordinator.rememberInvite(null)
    }

    /** Liegt auf dem Gerät etwas, das ersetzt würde, kommt erst die Rückfrage. */
    private fun requestAttach(target: PendingAttach) = busy {
        if (coordinator.hasOwnData()) {
            val count = evenings.evenings.first().size
            mutable.update { it.copy(replace = target, eveningCount = count) }
        } else {
            attach(target)
        }
    }

    fun cancelReplace() = mutable.update { it.copy(replace = null) }

    fun confirmReplace() {
        val target = mutable.value.replace ?: return
        mutable.update { it.copy(replace = null) }
        busy { attach(target) }
    }

    private suspend fun attach(target: PendingAttach) {
        requireSynced(
            when (target) {
                is PendingAttach.Join -> coordinator.join(target.token)
                is PendingAttach.Use -> coordinator.use(target.round)
            },
        )
    }

    // --- Verbunden ---

    /** „Das bin ich“; `player == null` heißt „Ich bin neu“. */
    fun claim(player: PlayerId?, name: String) = busy { requireSynced(coordinator.claim(player, name)) }

    fun invite() = busy { mutable.update { it.copy(shareLink = coordinator.inviteLink()) } }

    fun shared() = mutable.update { it.copy(shareLink = null) }

    fun syncNow() = coordinator.syncNow()

    fun clearNotices() = coordinator.clearNotices()

    fun clearMessage() = coordinator.clearMessage()

    fun clearError() = mutable.update { it.copy(error = null) }

    // --- Gemeinsames ---

    /** Die Verbindung steht schon; scheitert nur der erste Abgleich, holt die App ihn von selbst nach. */
    private fun requireSynced(outcome: SyncOutcome) {
        when (outcome) {
            SyncOutcome.Offline -> throw RoundException(RoundError.OFFLINE)
            SyncOutcome.SignedOut -> throw RoundException(RoundError.SIGNED_OUT)
            is SyncOutcome.Failed -> throw RoundException(outcome.error)
            else -> Unit
        }
    }

    private fun busy(roundName: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoundException) {
                mutable.update { it.copy(error = RoundUiError.Server(e.error, roundName)) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = RoundUiError.Server(RoundError.SERVER)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
