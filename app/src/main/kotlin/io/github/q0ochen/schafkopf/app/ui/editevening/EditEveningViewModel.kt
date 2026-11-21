package io.github.q0ochen.schafkopf.app.ui.editevening

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.ui.place.PlaceSuggestions
import io.github.q0ochen.schafkopf.app.ui.place.PlaceSuggestionsState
import io.github.q0ochen.schafkopf.domain.evening.EveningDetails
import io.github.q0ochen.schafkopf.domain.evening.EveningEdit
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
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
 * @property date Das gewählte Datum (Ortszeit des Geräts); jeder Tag lässt sich wählen, auch einer nach heute.
 * @property places Ortsvorschläge unter dem Feld „Ort“ (Abschnitt 4.3).
 * @property linked Ob das Gerät mit einer gemeinsamen Runde verbunden ist.
 * @property changed Ob sich gegenüber dem Stand beim Öffnen etwas geändert hat.
 */
data class EditEveningUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val startedAt: Instant? = null,
    val date: LocalDate? = null,
    val location: String = "",
    val places: PlaceSuggestionsState = PlaceSuggestionsState(),
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
 * @param placeSearch Ortsvorschläge aus OpenStreetMap (Abschnitt 4.3).
 */
class EditEveningViewModel(
    private val eveningId: EveningId,
    evenings: EveningRepository,
    players: PlayerRepository,
    private val editor: EveningEditor,
    private val clock: Clock,
    linked: Flow<Boolean>,
    placeSearch: PlaceSearch,
) : ViewModel() {

    private val form = MutableStateFlow(Form())
    private val places = PlaceSuggestions(viewModelScope, placeSearch)

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
        combine(form, players.players, linked, places.state) { current, allPlayers, isLinked, suggestions ->
            val original = current.original
            EditEveningUiState(
                loading = current.loading,
                missing = !current.loading && original == null,
                startedAt = original?.let { edited(current, it).startedAt },
                date = current.date,
                location = current.location,
                places = suggestions,
                seating = current.seating,
                firstDealer = current.firstDealer,
                names = allPlayers.associate { it.id to it.name },
                linked = isLinked,
                changed = original != null && edited(current, original) != original,
                saving = current.saving,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditEveningUiState())

    /** Wählt ein anderes Datum, auch einen Tag nach heute (dann ist der Abend geplant, Abschnitt 4.2). */
    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }

    /** Der Nutzer hat den Ort geändert; ab drei Zeichen gibt es Vorschläge. */
    fun setLocation(location: String) {
        form.update { it.copy(location = location.take(EveningDetails.MAX_LOCATION_LENGTH)) }
        places.typed(location)
    }

    /** Übernimmt einen Ortsvorschlag als Ort und schließt die Liste. */
    fun pickPlace(suggestion: PlaceSuggestion) {
        places.close()
        form.update { it.copy(location = suggestion.text.take(EveningDetails.MAX_LOCATION_LENGTH)) }
    }

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
