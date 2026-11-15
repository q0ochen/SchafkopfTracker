package io.github.q0ochen.schafkopf.app.ui.newevening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

data class NewEveningUiState(
    val players: List<Player> = emptyList(),
    val seating: List<PlayerId> = emptyList(),
    val firstDealer: PlayerId? = null,
    val location: String = "",
    val rulesName: String = "",
    val lastSeating: List<PlayerId> = emptyList(),
    val nameError: Boolean = false,
) {
    val missing: Int get() = ScoreCalculator.PLAYERS_AT_TABLE - seating.size
    val canStart: Boolean get() = missing == 0 && firstDealer in seating
    val canUseLast: Boolean get() = lastSeating.size == ScoreCalculator.PLAYERS_AT_TABLE
}

/**
 * Was der Nutzer auf dem Bildschirm „Neuer Abend“ bisher gewählt hat.
 *
 * @property customized `false`, solange der Nutzer an der Sitzordnung nichts geändert hat; dann gilt der Vorschlag
 *   (die Sitzordnung des letzten Abends oder die feste Vierer-Runde).
 */
private data class Selection(
    val customized: Boolean = false,
    val seating: List<PlayerId> = emptyList(),
    val firstDealer: PlayerId? = null,
    val location: String = "",
    val nameError: Boolean = false,
)

class NewEveningViewModel(
    private val players: PlayerRepository,
    private val evenings: EveningRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())

    val state: StateFlow<NewEveningUiState> =
        combine(players.players, evenings.evenings, settings.settings, selection) { allPlayers, allEvenings, appSettings, chosen ->
            val active = allPlayers.filterNot { it.archived }
            val activeIds = active.map { it.id }.toSet()
            val lastSeating = allEvenings.firstOrNull()?.seating.orEmpty().filter { it in activeIds }
            // Vorschlag für die feste Runde: wie beim letzten Mal, sonst die vier Spieler in Anlagereihenfolge.
            val suggestion = when {
                lastSeating.size == ScoreCalculator.PLAYERS_AT_TABLE -> lastSeating
                active.size == ScoreCalculator.PLAYERS_AT_TABLE -> active.map { it.id }
                else -> emptyList()
            }
            val seating = if (chosen.customized) chosen.seating.filter { it in activeIds } else suggestion
            NewEveningUiState(
                players = active,
                seating = seating,
                firstDealer = chosen.firstDealer?.takeIf { it in seating } ?: seating.firstOrNull(),
                location = chosen.location,
                rulesName = appSettings.defaultRules.name,
                lastSeating = lastSeating,
                nameError = chosen.nameError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewEveningUiState())

    /** Übernimmt beim ersten Eingriff des Nutzers die gerade angezeigte Sitzordnung als Ausgangspunkt. */
    private fun Selection.startingFromShown(): Selection =
        if (customized) this else copy(customized = true, seating = state.value.seating)

    /** Wählt einen Spieler aus oder ab. Mehr als vier lassen sich nicht auswählen. */
    fun toggle(player: PlayerId) {
        selection.update { before ->
            val current = before.startingFromShown()
            when {
                player in current.seating -> current.copy(seating = current.seating - player)
                current.seating.size < ScoreCalculator.PLAYERS_AT_TABLE -> current.copy(seating = current.seating + player)
                else -> current
            }
        }
    }

    /** Legt einen neuen Spieler an und setzt ihn gleich an den Tisch, wenn noch Platz ist. */
    fun addPlayer(nameInput: String) {
        val name = Player.cleanName(nameInput)
        if (name == null) {
            selection.update { it.copy(nameError = true) }
            return
        }
        viewModelScope.launch {
            val existing = players.players.first()
            val player = Player(id = PlayerId(idGenerator.newId()), name = name, colorIndex = existing.size)
            players.save(player)
            selection.update { before ->
                val current = before.startingFromShown()
                val seating = if (current.seating.size < ScoreCalculator.PLAYERS_AT_TABLE) current.seating + player.id else current.seating
                current.copy(seating = seating, nameError = false)
            }
        }
    }

    fun clearNameError() = selection.update { it.copy(nameError = false) }

    /** Verschiebt einen Spieler in der Sitzordnung um einen Platz nach vorn (−1) oder hinten (+1). */
    fun move(player: PlayerId, delta: Int) {
        selection.update { before ->
            val current = before.startingFromShown()
            val from = current.seating.indexOf(player)
            val to = from + delta
            if (from < 0 || to !in current.seating.indices) return@update current
            val seating = current.seating.toMutableList()
            seating[from] = seating[to]
            seating[to] = player
            current.copy(seating = seating)
        }
    }

    fun setFirstDealer(player: PlayerId) = selection.update { it.copy(firstDealer = player) }

    fun setLocation(location: String) = selection.update { it.copy(location = location.take(MAX_LOCATION_LENGTH)) }

    /** Übernimmt Spieler und Sitzordnung des letzten Abends. */
    fun useLastSeating() {
        val last = state.value.lastSeating
        selection.update { it.copy(customized = true, seating = last, firstDealer = last.firstOrNull()) }
    }

    /** Legt den Abend an und meldet seine Kennung, sobald er gespeichert ist. */
    fun start(onStarted: (String) -> Unit) {
        val current = state.value
        val dealer = current.firstDealer
        if (!current.canStart || dealer == null) return
        viewModelScope.launch {
            val evening = Evening(
                id = EveningId(idGenerator.newId()),
                startedAt = clock.instant(),
                location = current.location.trim(),
                seating = current.seating,
                firstDealer = dealer,
                rules = settings.settings.first().defaultRules,
            )
            evenings.save(evening)
            onStarted(evening.id.value)
        }
    }

    private companion object {
        const val MAX_LOCATION_LENGTH = 40
    }
}
