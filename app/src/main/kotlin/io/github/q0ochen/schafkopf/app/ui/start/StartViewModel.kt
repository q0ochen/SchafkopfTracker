package io.github.q0ochen.schafkopf.app.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Ein Spieler mit Endstand und Platz in der Übersicht eines Abends. */
data class Standing(val name: String, val balance: Int, val rank: Int)

/**
 * Kurzfassung eines Abends für die Startseite.
 *
 * @property planned Geplant (nicht beendet, Tag nach heute, docs/ARCHITEKTUR.md 4.2): Marke „geplant“ statt „läuft“.
 */
data class EveningCard(
    val id: String,
    val startedAt: Instant,
    val location: String,
    val running: Boolean,
    val planned: Boolean,
    val games: Int,
    val asMoney: Boolean,
    val standings: List<Standing>,
)

data class StartUiState(
    val loading: Boolean = true,
    val running: EveningCard? = null,
    val evenings: List<EveningCard> = emptyList(),
)

/** Startseite. [clock] (mit der Zeitzone des Geräts) entscheidet, welche Abende geplant sind. */
class StartViewModel(
    evenings: EveningRepository,
    players: PlayerRepository,
    coordinator: RoundCoordinator,
    private val clock: Clock,
) : ViewModel() {

    /** Der Stand der gemeinsamen Runde für die Zeile auf der Startseite. */
    val round: StateFlow<RoundState> = coordinator.state

    val state: StateFlow<StartUiState> = combine(evenings.evenings, players.players) { list, allPlayers ->
        val names = allPlayers.associate { it.id to it.name }
        val today = LocalDate.now(clock)
        val cards = list.map { evening ->
            val computed = EveningCalculator.compute(evening)
            EveningCard(
                id = evening.id.value,
                startedAt = evening.startedAt,
                location = evening.location,
                running = evening.isRunning,
                planned = evening.isPlanned(today, clock.zone),
                games = computed.gamesPlayed,
                asMoney = evening.rules.displayAsMoney,
                standings = evening.seating
                    .map { Standing(names[it] ?: "?", computed.balances.getValue(it), computed.ranks.getValue(it)) }
                    .sortedBy { it.rank },
            )
        }
        // „Abend läuft“ zeigt nie einen geplanten Abend (Abschnitt 4.2), auch wenn er in der Liste ganz oben steht.
        StartUiState(loading = false, running = cards.firstOrNull { it.running && !it.planned }, evenings = cards)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())
}
