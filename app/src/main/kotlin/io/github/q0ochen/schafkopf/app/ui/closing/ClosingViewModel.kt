package io.github.q0ochen.schafkopf.app.ui.closing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.ai.Commentary
import io.github.q0ochen.schafkopf.app.ai.CommentaryOutcome
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Wie weit der Abend-Kommentator ist. */
sealed interface CommentaryUi {
    data object Idle : CommentaryUi
    data object Writing : CommentaryUi

    /** Vor dem ersten Senden an diesen Dienst: Zustimmung erfragen. */
    data class NeedsConsent(val host: String, val realNames: Boolean) : CommentaryUi
    data class Ready(val commentary: Commentary, val error: LlmError? = null) : CommentaryUi
}

/**
 * Zustand des Abschlusses.
 *
 * @property computed Der durchgerechnete Abend; `null`, solange geladen wird oder wenn es den Abend nicht mehr gibt.
 */
data class ClosingUiState(
    val loading: Boolean = true,
    val computed: EveningState? = null,
    val closing: Closing? = null,
    val names: Map<PlayerId, String> = emptyMap(),
    val colorIndex: Map<PlayerId, Int> = emptyMap(),
    val commentary: CommentaryUi = CommentaryUi.Idle,
)

/**
 * Endstand und „Wer zahlt wem“ eines Abends; rechnet bei jeder Änderung des Abends oder der Namen neu.
 * Dazu der Abend-Kommentator ([Commentator]).
 *
 * @param computeDispatcher Darauf wird gerechnet; Tests geben ihren eigenen, damit nach dem Test nichts nachläuft.
 */
class ClosingViewModel(
    eveningId: EveningId,
    evenings: EveningRepository,
    players: PlayerRepository,
    private val commentator: Commentator,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val commentary = MutableStateFlow<CommentaryUi>(CommentaryUi.Idle)

    private val computedState: Flow<ClosingUiState> =
        combine(evenings.evening(eveningId), players.players) { evening, allPlayers ->
            val names = allPlayers.associate { it.id to it.name }
            val computed = evening?.let(EveningCalculator::compute)
            ClosingUiState(
                loading = false,
                computed = computed,
                closing = computed?.let { Closing.of(it, names) },
                names = names,
                colorIndex = allPlayers.associate { it.id to it.colorIndex },
            )
        }.flowOn(computeDispatcher)

    val state: StateFlow<ClosingUiState> =
        combine(computedState, commentary) { closing, comment -> closing.copy(commentary = comment) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClosingUiState())

    /** Lässt den Kommentar schreiben; fehlt die Zustimmung zum Senden, wird erst danach gefragt. */
    fun writeCommentary() {
        if (commentary.value == CommentaryUi.Writing) return
        val computed = state.value.computed ?: return
        val names = state.value.names
        commentary.value = CommentaryUi.Writing
        viewModelScope.launch {
            commentary.value = when (val outcome = commentator.write(computed, names)) {
                is CommentaryOutcome.Done -> CommentaryUi.Ready(outcome.commentary)
                is CommentaryOutcome.NeedsConsent -> CommentaryUi.NeedsConsent(outcome.host, outcome.realNames)
                is CommentaryOutcome.Failed -> CommentaryUi.Ready(outcome.fallback, outcome.error)
            }
        }
    }

    /** Der Nutzer stimmt dem Senden an diesen Dienst zu; dann geht es gleich los. */
    fun consent(host: String) {
        viewModelScope.launch {
            commentator.consent(host)
            commentary.value = CommentaryUi.Idle
            writeCommentary()
        }
    }

    fun declineConsent() {
        commentary.value = CommentaryUi.Idle
    }
}
