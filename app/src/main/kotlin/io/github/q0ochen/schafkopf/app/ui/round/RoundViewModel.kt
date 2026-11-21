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
import io.github.q0ochen.schafkopf.domain.round.RoundNames
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
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

/** Was beim Beitreten oder Verwenden einer Runde ohne verbundene Runde noch bestätigt werden muss. */
sealed interface PendingAttach {
    data class Join(val token: String) : PendingAttach
    data class Use(val round: RoundSummary) : PendingAttach
}

/**
 * Ein Wechsel in eine andere Runde, während das Gerät verbunden ist (docs/ARCHITEKTUR.md 8.9, „Rückfrage vor dem
 * Wechseln“). [target] ist der Name der Zielrunde für die Rückfrage.
 */
sealed interface SwitchRequest {
    val target: String

    /** Eine andere Runde des Kontos wählen. */
    data class Use(val round: RoundSummary) : SwitchRequest {
        override val target: String get() = round.name
    }

    /** Einer weiteren Runde beitreten. */
    data class Join(val token: String, override val target: String) : SwitchRequest

    /** Eine neue, leere Runde anlegen. */
    data class Create(val name: String) : SwitchRequest {
        override val target: String get() = RoundNames.clean(name)
    }
}

/** Eine Rückfrage, bevor das Gerät von der Runde getrennt wird. */
enum class Farewell { LEAVE, SIGN_OUT }

/** Ein Fehler oder Hinweis, den der Bildschirm in Worten zeigt. */
sealed interface RoundUiError {
    /** Im eingefügten Text steht kein Einladungscode. */
    data object NoCode : RoundUiError

    /** Auf dem Handy gibt es keinen Browser für die Anmeldung. */
    data object NoBrowser : RoundUiError

    /** Die Einladung gehört zur Runde, mit der das Gerät schon verbunden ist (ein Hinweis, kein Fehler). */
    data object AlreadyInRound : RoundUiError

    /** @property roundName Es ging um den Namen einer Runde. */
    data class Server(val error: RoundError, val roundName: Boolean = false) : RoundUiError
}

/**
 * Was der Bildschirm „Gemeinsame Runden“ über den Stand der Runde hinaus braucht. Die Runden des Kontos stehen im
 * Stand der Runde ([RoundState.rounds]), weil auch der Start-Bildschirm sie zeigt.
 *
 * @property autoBackup Die automatische Sicherung ist eingeschaltet (für den Hinweis in der Rückfrage).
 * @property invite Was die wartende Einladung verrät; `null`, solange unbekannt.
 * @property replace Wartet auf die Rückfrage „Daten dieses Geräts ersetzen?“ (ohne verbundene Runde).
 * @property eveningCount So viele Abende lägen beim Ersetzen auf dem Gerät.
 * @property switching Wartet auf die Rückfrage vor dem Wechseln (mit verbundener Runde); [switchPending] Änderungen
 *   warten nach dem Abgleich davor noch.
 * @property farewell Wartet auf die Rückfrage vor dem Verlassen oder Abmelden; [farewellPending] Änderungen warten noch.
 * @property leaving Wartet auf die Rückfrage, ob das Konto diese (nicht verbundene) Runde verlassen will.
 * @property signInUrl Diese Adresse soll der Browser jetzt öffnen.
 * @property shareLink Diesen Einladungslink soll das Teilen-Menü jetzt anbieten.
 */
data class RoundUi(
    val busy: Boolean = false,
    val error: RoundUiError? = null,
    val autoBackup: Boolean = false,
    val invite: InviteInfo? = null,
    val replace: PendingAttach? = null,
    val eveningCount: Int = 0,
    val switching: SwitchRequest? = null,
    val switchPending: Int = 0,
    val farewell: Farewell? = null,
    val farewellPending: Int = 0,
    val leaving: RoundSummary? = null,
    val signInUrl: String? = null,
    val shareLink: String? = null,
)

class RoundViewModel(
    private val coordinator: RoundCoordinator,
    players: PlayerRepository,
    private val evenings: EveningRepository,
    private val autoBackupEnabled: () -> Boolean = { false },
) : ViewModel() {

    val state: StateFlow<RoundState> = coordinator.state

    val players: StateFlow<List<Player>> = players.players.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutable = MutableStateFlow(RoundUi())
    val ui: StateFlow<RoundUi> = mutable.asStateFlow()

    init {
        // Sobald jemand angemeldet ist: seine Runden und die wartende Einladung nachsehen (auch mit verbundener Runde,
        // für „Deine anderen Runden“ und die Einladungskarte).
        viewModelScope.launch {
            coordinator.state
                .map { it.account?.userId to it.pendingInvite }
                .distinctUntilChanged()
                .collect { (userId, _) ->
                    if (userId != null) refresh() else mutable.update { it.copy(invite = null) }
                }
        }
    }

    /** Liest die Runden des Kontos und die wartende Einladung. Scheitert das, bleibt der Bildschirm benutzbar. */
    fun refresh() {
        viewModelScope.launch {
            // Scheitert das Lesen, sagt der Bildschirm es: Sonst sähe es auf einem neuen Handy aus, als gäbe es keine Runde.
            coordinator.refreshRounds()
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

    // --- Runde anlegen, beitreten, verwenden oder wechseln ---

    /**
     * „Runde anlegen“. Der Name wird vorher geprüft (Länge, schon vergeben). Ohne verbundene Runde wird gleich angelegt;
     * mit verbundener Runde kommt erst die Rückfrage, denn die neue Runde beginnt leer.
     */
    fun create(name: String) = busy(roundName = true) {
        if (!RoundNames.valid(name)) throw RoundException(RoundError.INVALID_NAME)
        if (RoundNames.taken(name, knownRounds(coordinator.state.value))) throw RoundException(RoundError.NAME_TAKEN)
        if (coordinator.state.value.link == null) coordinator.create(name) else askSwitch(SwitchRequest.Create(name))
    }

    /** Löst einen eingefügten Link oder Code ein. */
    fun redeem(text: String) {
        val token = InviteLinks.tokenFrom(text)
        if (token == null) mutable.update { it.copy(error = RoundUiError.NoCode) } else requestJoin(token)
    }

    /** Tritt der Runde der wartenden Einladung bei. */
    fun joinInvited() {
        coordinator.state.value.pendingInvite?.let(::requestJoin)
    }

    /** „Auf diesem Gerät verwenden“ (ohne verbundene Runde) bzw. „Wechseln“ (mit verbundener Runde). */
    fun use(round: RoundSummary) {
        if (coordinator.state.value.link == null) requestAttach(PendingAttach.Use(round)) else busy { askSwitch(SwitchRequest.Use(round)) }
    }

    private fun requestJoin(token: String) {
        val link = coordinator.state.value.link ?: return requestAttach(PendingAttach.Join(token))
        busy {
            val info = coordinator.inviteInfo(token)
            if (info.roundId == link.roundId) {
                // Die Einladung führt in die Runde, in der das Gerät schon ist: sagen und die Einladung vergessen.
                coordinator.rememberInvite(null)
                mutable.update { it.copy(error = RoundUiError.AlreadyInRound) }
            } else {
                askSwitch(SwitchRequest.Join(token, info.roundName))
            }
        }
    }

    /**
     * Gleicht vor der Rückfrage vollständig ab und merkt sich, was danach noch wartet. Gibt es die verbundene Runde nicht
     * mehr, kommt keine Rückfrage (die Meldung dazu zeigt der Stand der Runde).
     */
    private suspend fun askSwitch(request: SwitchRequest) {
        val pending = coordinator.prepareSwitch() ?: return
        mutable.update { it.copy(switching = request, switchPending = pending) }
    }

    fun cancelSwitch() = mutable.update { it.copy(switching = null) }

    fun confirmSwitch() {
        val request = mutable.value.switching ?: return
        mutable.update { it.copy(switching = null) }
        busy(roundName = request is SwitchRequest.Create) {
            when (request) {
                is SwitchRequest.Use -> coordinator.use(request.round)
                is SwitchRequest.Join -> coordinator.join(request.token)
                is SwitchRequest.Create -> coordinator.create(request.name)
            }
        }
    }

    /** „Verlassen“ bei einer Runde des Kontos, die nicht mit diesem Gerät verbunden ist: erst die Rückfrage. */
    fun askLeaveRound(round: RoundSummary) = mutable.update { it.copy(leaving = round) }

    fun cancelLeaveRound() = mutable.update { it.copy(leaving = null) }

    fun confirmLeaveRound() {
        val round = mutable.value.leaving ?: return
        mutable.update { it.copy(leaving = null) }
        busy { coordinator.leaveRound(round) }
    }

    fun discardInvite() {
        coordinator.rememberInvite(null)
    }

    /** Ohne verbundene Runde: Liegt auf dem Gerät etwas, das ersetzt würde, kommt erst die Rückfrage. */
    private fun requestAttach(target: PendingAttach) = busy {
        if (coordinator.hasOwnData()) {
            val count = evenings.evenings.first().size
            mutable.update { it.copy(replace = target, eveningCount = count, autoBackup = autoBackupEnabled()) }
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
        when (target) {
            is PendingAttach.Join -> coordinator.join(target.token)
            is PendingAttach.Use -> coordinator.use(target.round)
        }
    }

    // --- Verbunden ---

    /** „Das bin ich“; `player == null` heißt „Ich bin neu“. */
    fun claim(player: PlayerId?, name: String) = busy { coordinator.claim(player, name) }

    fun invite() = busy { mutable.update { it.copy(shareLink = coordinator.inviteLink()) } }

    fun shared() = mutable.update { it.copy(shareLink = null) }

    fun syncNow() = coordinator.syncNow()

    fun clearNotices() = coordinator.clearNotices()

    fun clearMessage() = coordinator.clearMessage()

    fun clearError() = mutable.update { it.copy(error = null) }

    // --- Gemeinsames ---

    /*
     * Anlegen, Beitreten und „Das bin ich“ sind geschafft, sobald der Server sie angenommen hat. Scheitert nur der
     * Abgleich danach, ist das kein Fehler dieser Aktion: Die Statuszeile sagt es („Kein Netz“), und die App holt ihn
     * von selbst nach.
     */

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
}
