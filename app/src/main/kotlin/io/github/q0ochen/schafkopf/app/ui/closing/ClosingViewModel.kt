package io.github.q0ochen.schafkopf.app.ui.closing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryFlow
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryUi
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Zustand von „Abend teilen“ (bis 21.11.2026 „Abschluss“, docs/ARCHITEKTUR.md, Abschnitt 4.4).
 *
 * @property computed Der durchgerechnete Abend; `null`, solange geladen wird oder wenn es den Abend nicht mehr gibt.
 */
data class ClosingUiState(
    val loading: Boolean = true,
    val computed: EveningState? = null,
    val closing: Closing? = null,
    val names: Map<PlayerId, String> = emptyMap(),
    val colorIndex: Map<PlayerId, Int> = emptyMap(),
    val balanceSeries: List<Pair<PlayerId, List<Int>>> = emptyList(),
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

    private val commentary = CommentaryFlow(viewModelScope, commentator)

    private val computedState: Flow<ClosingUiState> =
        combine(evenings.evening(eveningId), players.players) { evening, allPlayers ->
            val names = allPlayers.associate { it.id to it.name }
            val computed = evening?.let(EveningCalculator::compute)
            // Der Vermögensverlauf wie in der Statistik dieses Abends: Spieler in Sitzreihenfolge, Start und nach jedem Spiel.
            val finance = evening?.let { StatisticEngine.forEvening(it) }
            ClosingUiState(
                loading = false,
                computed = computed,
                closing = computed?.let { Closing.of(it, names) },
                names = names,
                colorIndex = allPlayers.associate { it.id to it.colorIndex },
                balanceSeries = finance?.players.orEmpty().map { player ->
                    player to (finance?.finance?.balanceSeries?.get(player) ?: listOf(0))
                },
            )
        }.flowOn(computeDispatcher)

    val state: StateFlow<ClosingUiState> =
        combine(computedState, commentary.state) { closing, comment -> closing.copy(commentary = comment) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClosingUiState())

    /** Lässt den Kommentar schreiben; fehlt die Zustimmung zum Senden, wird erst danach gefragt. */
    fun writeCommentary() {
        val computed = state.value.computed ?: return
        val names = state.value.names
        commentary.write { commentator.write(computed, names) }
    }

    /** Der Nutzer stimmt dem Senden an diesen Dienst zu; dann geht es gleich los. Ein zweites Tippen zählt nicht. */
    fun consent(host: String) = commentary.consent(host)

    fun declineConsent() = commentary.decline()
}
