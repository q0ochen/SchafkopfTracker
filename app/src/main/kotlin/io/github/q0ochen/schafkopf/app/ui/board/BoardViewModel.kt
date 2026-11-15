package io.github.q0ochen.schafkopf.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Zustand der Abend-Liste.
 *
 * @property computed Der durchgerechnete Abend; `null`, solange geladen wird oder wenn es den Abend nicht mehr gibt.
 */
data class BoardUiState(
    val loading: Boolean = true,
    val computed: EveningState? = null,
    val names: Map<PlayerId, String> = emptyMap(),
    val colorIndex: Map<PlayerId, Int> = emptyMap(),
    val keepScreenOn: Boolean = true,
) {
    fun name(player: PlayerId?): String = names[player] ?: "?"
}

/** Ein entfernter Eintrag samt früherer Position, damit er sich wiederherstellen lässt. */
data class RemovedEntry(val entry: EveningEntry, val index: Int)

class BoardViewModel(
    private val eveningId: EveningId,
    private val evenings: EveningRepository,
    players: PlayerRepository,
    settings: SettingsRepository,
    private val editor: EveningEditor,
) : ViewModel() {

    val state: StateFlow<BoardUiState> =
        combine(evenings.evening(eveningId), players.players, settings.settings) { evening, allPlayers, appSettings ->
            BoardUiState(
                loading = false,
                computed = evening?.let(EveningCalculator::compute),
                names = allPlayers.associate { it.id to it.name },
                colorIndex = allPlayers.associate { it.id to it.colorIndex },
                keepScreenOn = appSettings.keepScreenOn,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardUiState())

    /** Alle haben „weiter“ gesagt. */
    fun addThrownIn() {
        viewModelScope.launch {
            editor.update(eveningId) { it.append(ThrownInEntry(editor.newEntryId(), editor.now())) }
        }
    }

    /** Jemand hat 6 Nixer: Bockrunde auslösen. */
    fun addBockTrigger(player: PlayerId?, handThrownIn: Boolean) {
        viewModelScope.launch {
            editor.update(eveningId) {
                it.append(
                    BockTriggerEntry(
                        id = editor.newEntryId(),
                        recordedAt = editor.now(),
                        reason = BockReason.SECHS_NIXER,
                        player = player,
                        handThrownIn = handThrownIn,
                    ),
                )
            }
        }
    }

    /** Nimmt den letzten Eintrag zurück und liefert ihn, damit die Oberfläche „Wiederherstellen“ anbieten kann. */
    suspend fun removeLast(): RemovedEntry? {
        var removed: RemovedEntry? = null
        editor.update(eveningId) { evening ->
            val last = evening.entries.lastOrNull() ?: return@update evening
            removed = RemovedEntry(last, evening.entries.lastIndex)
            evening.remove(last.id)
        }
        return removed
    }

    fun restore(removed: RemovedEntry) {
        viewModelScope.launch {
            editor.update(eveningId) { evening ->
                if (evening.entries.any { it.id == removed.entry.id }) evening else evening.insertAt(removed.index, removed.entry)
            }
        }
    }

    fun finish() {
        viewModelScope.launch { editor.update(eveningId) { it.copy(finishedAt = editor.now()) } }
    }

    fun reopen() {
        viewModelScope.launch { editor.update(eveningId) { it.copy(finishedAt = null) } }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            evenings.delete(eveningId)
            onDeleted()
        }
    }
}
