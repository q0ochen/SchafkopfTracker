package io.github.q0ochen.schafkopf.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryFlow
import io.github.q0ochen.schafkopf.app.ui.ai.CommentaryUi
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import io.github.q0ochen.schafkopf.domain.stats.Statistics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
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
 * @property commentary Der Kommentator: Kommentar zum gewählten Abend oder Bilanz über alle Abende.
 */
data class StatisticsUiState(
    val loading: Boolean = true,
    val selected: EveningId? = null,
    val choices: List<EveningChoice> = emptyList(),
    val stats: Statistics? = null,
    val asMoney: Boolean = true,
    val names: Map<PlayerId, String> = emptyMap(),
    val colorIndex: Map<PlayerId, Int> = emptyMap(),
    val commentary: CommentaryUi = CommentaryUi.Idle,
) {
    val selectedChoice: EveningChoice? get() = choices.firstOrNull { it.id == selected }

    /** Beginn eines Abends, zum Beispiel für das teuerste Spiel über alle Abende. */
    fun startOf(id: EveningId): Instant? = choices.firstOrNull { it.id == id }?.startedAt

    fun name(player: PlayerId): String = names[player] ?: "?"
}

/**
 * Statistik mit der [StatisticEngine]: ein Abend oder alle Abende. Rechnet bei jeder Änderung neu, nicht auf dem
 * Hauptthread. Dazu der Kommentator ([Commentator]): für einen Abend wie im Abschluss, für alle Abende als Bilanz.
 *
 * @param initial Der Abend, mit dem die Statistik öffnet; `null` für alle Abende.
 * @param computeDispatcher Darauf wird gerechnet; Tests geben ihren eigenen, damit nach dem Test nichts nachläuft.
 */
class StatisticsViewModel(
    initial: EveningId?,
    private val evenings: EveningRepository,
    players: PlayerRepository,
    private val commentator: Commentator,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val selection = MutableStateFlow(initial)
    private val commentary = CommentaryFlow(viewModelScope, commentator)

    private val computedState: Flow<StatisticsUiState> =
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

    val state: StateFlow<StatisticsUiState> =
        combine(computedState, commentary.state) { stats, comment -> stats.copy(commentary = comment) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState(selected = initial))

    /** Wählt einen Abend aus; `null` für alle Abende. Ein Kommentar zur bisherigen Auswahl verschwindet. */
    fun select(eveningId: EveningId?) {
        if (selection.value != eveningId) commentary.reset()
        selection.value = eveningId
    }

    /**
     * Lässt den Kommentar zur aktuellen Auswahl schreiben: zum gewählten Abend oder die Bilanz über alle Abende.
     * Fehlt die Zustimmung zum Senden, wird erst danach gefragt.
     */
    fun writeCommentary() {
        if (state.value.stats == null) return
        // Die Auswahl selbst, nicht der angezeigte Stand: Der rechnet nach einem Wechsel womöglich noch.
        val selected = selection.value
        val names = state.value.names
        commentary.write {
            withContext(computeDispatcher) {
                val all = evenings.evenings.first().sortedWith(NEWEST_FIRST)
                if (selected == null) {
                    // Euro oder Punkte wie in der Statistik über alle Abende: wie beim neuesten Abend.
                    commentator.writeHistory(all, names, asMoney = all.firstOrNull()?.rules?.displayAsMoney ?: true)
                } else {
                    all.firstOrNull { it.id == selected }?.let { commentator.write(EveningCalculator.compute(it), names) }
                }
            }
        }
    }

    /** Der Nutzer stimmt dem Senden an diesen Dienst zu; dann geht es gleich los. */
    fun consent(host: String) = commentary.consent(host)

    fun declineConsent() = commentary.decline()

    private companion object {
        val NEWEST_FIRST: Comparator<Evening> =
            compareByDescending<Evening> { it.startedAt }.thenBy { it.id.value }
    }
}
