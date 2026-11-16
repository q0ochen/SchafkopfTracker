package io.github.q0ochen.schafkopf.app.ui.closing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

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
)

/** Endstand und „Wer zahlt wem“ eines Abends; rechnet bei jeder Änderung des Abends oder der Namen neu. */
class ClosingViewModel(
    eveningId: EveningId,
    evenings: EveningRepository,
    players: PlayerRepository,
) : ViewModel() {

    val state: StateFlow<ClosingUiState> =
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
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClosingUiState())
}
