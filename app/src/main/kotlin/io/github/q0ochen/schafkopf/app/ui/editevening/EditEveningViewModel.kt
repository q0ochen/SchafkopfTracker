package io.github.q0ochen.schafkopf.app.ui.editevening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.EveningDetails
import io.github.q0ochen.schafkopf.domain.evening.EveningEdit
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Zustand von „Abend bearbeiten“ (docs/ARCHITEKTUR.md, Abschnitt 4.1).
 *
 * @property date Das gewählte Datum (Ortszeit des Geräts).
 * @property today Spätestes wählbares Datum.
 * @property linked Ob das Gerät mit einer gemeinsamen Runde verbunden ist.
 * @property changed Ob sich gegenüber dem Stand beim Öffnen etwas geändert hat.
 */
data class EditEveningUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val startedAt: Instant? = null,
    val date: LocalDate? = null,
    val today: LocalDate? = null,
    val location: String = "",
    val seating: List<PlayerId> = emptyList(),
    val firstDealer: PlayerId? = null,
    val names: Map<PlayerId, String> = emptyMap(),
    val linked: Boolean = false,
    val changed: Boolean = false,
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = changed && !saving

    fun name(player: PlayerId): String = names[player] ?: "?"
}

/** Was der Nutzer gerade bearbeitet, neben dem Stand beim Öffnen ([original]). */
private data class Form(
    val loading: Boolean = true,
    val original: EveningDetails? = null,
    val date: LocalDate? = null,
    val location: String = "",
    val seating: List<PlayerId> = emptyList(),
    val firstDealer: PlayerId? = null,
    val saving: Boolean = false,
)

/**
 * Bearbeitet Datum, Ort, Sitzordnung und ersten Geber eines Abends. Gespeichert wird erst mit „Speichern“, und dann
 * nur, was sich geändert hat ([EveningEdit.apply]); was ein anderes Handy der gemeinsamen Runde inzwischen geändert
 * hat, bleibt.
 *
 * @param clock Uhr mit der Zeitzone des Geräts: Datum und Uhrzeit des Beginns gelten in Ortszeit.
 * @param linked Ob das Gerät mit einer gemeinsamen Runde verbunden ist (für den Hinweis).
 */
class EditEveningViewModel(
    private val eveningId: EveningId,
    evenings: EveningRepository,
    players: PlayerRepository,
    private val editor: EveningEditor,
    private val clock: Clock,
    linked: Flow<Boolean>,
) : ViewModel() {

    private val form = MutableStateFlow(Form())

    init {
        viewModelScope.launch {
            // Einmal lesen: Das ist der Stand, gegen den beim Speichern verglichen wird.
            val evening = evenings.evening(eveningId).first()
            form.value = if (evening == null) {
                Form(loading = false)
            } else {
                Form(
                    loading = false,
                    original = EveningDetails.of(evening),
                    date = localDate(evening.startedAt),
                    location = evening.location,
                    seating = evening.seating,
                    firstDealer = evening.firstDealer,
                )
            }
        }
    }

    val state: StateFlow<EditEveningUiState> =
        combine(form, players.players, linked) { current, allPlayers, isLinked ->
            val original = current.original
            EditEveningUiState(
                loading = current.loading,
                missing = !current.loading && original == null,
                startedAt = original?.let { edited(current, it).startedAt },
                date = current.date,
                today = LocalDate.now(clock),
                location = current.location,
                seating = current.seating,
                firstDealer = current.firstDealer,
                names = allPlayers.associate { it.id to it.name },
                linked = isLinked,
                changed = original != null && edited(current, original) != original,
                saving = current.saving,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditEveningUiState())

    /** Wählt ein anderes Datum; Tage nach heute gibt es nicht. */
    fun setDate(date: LocalDate) {
        if (date.isAfter(LocalDate.now(clock))) return
        form.update { it.copy(date = date) }
    }

    fun setLocation(location: String) = form.update { it.copy(location = location.take(EveningDetails.MAX_LOCATION_LENGTH)) }

    /** Verschiebt einen Spieler in der Sitzordnung um einen Platz nach vorn (−1) oder hinten (+1). */
    fun move(player: PlayerId, delta: Int) {
        form.update { current ->
            val from = current.seating.indexOf(player)
            val to = from + delta
            if (from < 0 || to !in current.seating.indices) return@update current
            val seating = current.seating.toMutableList()
            seating[from] = seating[to]
            seating[to] = player
            current.copy(seating = seating)
        }
    }

    fun setFirstDealer(player: PlayerId) = form.update { if (player in it.seating) it.copy(firstDealer = player) else it }

    /** Speichert die Änderungen und meldet sich danach; doppeltes Antippen speichert nur einmal. */
    fun save(onSaved: () -> Unit) {
        val current = form.value
        val original = current.original ?: return
        val edited = edited(current, original)
        if (current.saving || edited == original) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Gibt es den Abend inzwischen nicht mehr, ändert das nichts; der Abend zeigt das dann selbst.
            editor.update(eveningId) { EveningEdit.apply(it, original, edited) }
            onSaved()
        }
    }

    /**
     * Die bearbeiteten Angaben für [EveningEdit.apply]. Derselbe Tag behält den Beginn genau; ein anderer Tag bekommt
     * dieselbe Ortszeit. Ein Ort, der sich nur im Leerraum unterscheidet, gilt als unverändert.
     */
    private fun edited(current: Form, original: EveningDetails): EveningDetails {
        val date = current.date
        val startedAt = if (date == null || date == localDate(original.startedAt)) {
            original.startedAt
        } else {
            ZonedDateTime.of(date, original.startedAt.atZone(clock.zone).toLocalTime(), clock.zone).toInstant()
        }
        val location = if (EveningEdit.cleanLocation(current.location) == EveningEdit.cleanLocation(original.location)) {
            original.location
        } else {
            current.location
        }
        return EveningDetails(
            startedAt = startedAt,
            location = location,
            seating = current.seating,
            firstDealer = current.firstDealer ?: original.firstDealer,
        )
    }

    private fun localDate(instant: Instant): LocalDate = instant.atZone(clock.zone).toLocalDate()
}
