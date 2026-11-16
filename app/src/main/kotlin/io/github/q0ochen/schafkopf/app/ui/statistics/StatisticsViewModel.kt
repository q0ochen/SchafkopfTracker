package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import io.github.q0ochen.schafkopf.domain.stats.Statistics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** Ein Abend in der Auswahl „Auswertung für“. */
data class EveningChoice(val id: EveningId, val startedAt: Instant, val location: String)

/**
 * Zustand der Statistik.
 *
 * @property selected Der ausgewertete Abend; `null` heißt: alle Abende zusammen („ewige Tabelle“).
 * @property choices Alle Abende für die Auswahl, der neueste zuerst.
 * @property stats Die Auswertung; `null`, solange geladen wird oder wenn es den gewählten Abend nicht mehr gibt.
 * @property asMoney Euro oder Punkte: wie beim gewählten Abend, über alle Abende wie beim neuesten.
 */
data class StatisticsUiState(
    val loading: Boolean = true,
    val selected: EveningId? = null,
    val choices: List<EveningChoice> = emptyList(),
    val stats: Statistics? = null,
    val asMoney: Boolean = true,
    val names: Map<PlayerId, String> = emptyMap(),
    val colorIndex: Map<PlayerId, Int> = emptyMap(),
) {
    val selectedChoice: EveningChoice? get() = choices.firstOrNull { it.id == selected }

    /** Beginn eines Abends, zum Beispiel für das teuerste Spiel über alle Abende. */
    fun startOf(id: EveningId): Instant? = choices.firstOrNull { it.id == id }?.startedAt

    fun name(player: PlayerId): String = names[player] ?: "?"
}

/**
 * Statistik mit der [StatisticEngine]: ein Abend oder alle Abende. Rechnet bei jeder Änderung neu, nicht auf dem
 * Hauptthread.
 *
 * @param initial Der Abend, mit dem die Statistik öffnet; `null` für alle Abende.
 * @param computeDispatcher Darauf wird gerechnet; Tests geben ihren eigenen, damit nach dem Test nichts nachläuft.
 */
class StatisticsViewModel(
    initial: EveningId?,
    evenings: EveningRepository,
    players: PlayerRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val selection = MutableStateFlow(initial)

    val state: StateFlow<StatisticsUiState> =
        combine(evenings.evenings, players.players, selection) { allEvenings, allPlayers, selected ->
            val sorted = allEvenings.sortedWith(NEWEST_FIRST)
            val evening = selected?.let { id -> sorted.firstOrNull { it.id == id } }
            StatisticsUiState(
                loading = false,
                selected = selected,
                choices = sorted.map { EveningChoice(it.id, it.startedAt, it.location.trim()) },
                stats = when {
                    selected == null -> StatisticEngine.forEvenings(sorted)
                    evening != null -> StatisticEngine.forEvening(evening)
                    else -> null
                },
                asMoney = (evening ?: sorted.firstOrNull())?.rules?.displayAsMoney ?: true,
                names = allPlayers.associate { it.id to it.name },
                colorIndex = allPlayers.associate { it.id to it.colorIndex },
            )
        }
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState(selected = initial))

    /** Wählt einen Abend aus; `null` für alle Abende. */
    fun select(eveningId: EveningId?) {
        selection.value = eveningId
    }

    private companion object {
        val NEWEST_FIRST: Comparator<Evening> =
            compareByDescending<Evening> { it.startedAt }.thenBy { it.id.value }
    }
}
