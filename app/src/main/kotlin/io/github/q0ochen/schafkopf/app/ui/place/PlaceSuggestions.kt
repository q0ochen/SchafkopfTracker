package io.github.q0ochen.schafkopf.app.ui.place

import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Was unter dem Feld „Ort“ steht.
 *
 * @property suggestions Die Vorschläge zur letzten Eingabe; leer heißt: keine Liste.
 * @property offline Die letzte Suche ist gescheitert: statt der Liste der Hinweis „Ortsvorschläge gibt es nur mit
 *   Internet.“.
 */
data class PlaceSuggestionsState(
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val offline: Boolean = false,
)

/**
 * Steuert die Ortsvorschläge eines Felds „Ort“ (docs/ARCHITEKTUR.md, Abschnitt 4.3); „Neuer Abend“ und „Abend
 * bearbeiten“ teilen sich das. Gesucht wird nur nach einer Eingabe des Nutzers ([typed]): ab drei Zeichen (getrimmt),
 * [delayMillis] nach der letzten Eingabe. Jede neue Eingabe verwirft die geplante oder laufende Suche, es zählt nur die
 * Antwort auf die letzte. Bis die neue Antwort da ist, bleiben die bisherigen Vorschläge stehen (die Liste flackert
 * nicht bei jedem Buchstaben); der Hinweis auf fehlendes Internet verschwindet sofort.
 */
class PlaceSuggestions(
    private val scope: CoroutineScope,
    private val search: PlaceSearch,
    private val delayMillis: Long = DELAY_MILLIS,
) {
    private val mutableState = MutableStateFlow(PlaceSuggestionsState())
    val state: StateFlow<PlaceSuggestionsState> = mutableState.asStateFlow()

    private var pending: Job? = null

    /** Der Nutzer hat den Ort zu [text] geändert. */
    fun typed(text: String) {
        pending?.cancel()
        val query = text.trim()
        if (query.length < MIN_LENGTH) {
            pending = null
            mutableState.value = PlaceSuggestionsState()
            return
        }
        mutableState.update { it.copy(offline = false) }
        pending = scope.launch {
            delay(delayMillis)
            val next = try {
                PlaceSuggestionsState(suggestions = search.search(query))
            } catch (e: CancellationException) {
                // Nur ein echter Abbruch (neue Eingabe, Bildschirm zu) wird durchgereicht; dieselbe Ausnahme aus dem
                // HTTP-Client, ohne dass jemand abgebrochen hat, ist ein Fehler wie jeder andere.
                if (!isActive) throw e
                PlaceSuggestionsState(offline = true)
            } catch (_: Exception) {
                PlaceSuggestionsState(offline = true)
            }
            mutableState.value = next
        }
    }

    /** Schließt die Liste (nach dem Antippen eines Vorschlags); gesucht wird erst wieder nach der nächsten Eingabe. */
    fun close() {
        pending?.cancel()
        pending = null
        mutableState.value = PlaceSuggestionsState()
    }

    companion object {
        /** So viele Zeichen (getrimmt) braucht eine Suche mindestens. */
        const val MIN_LENGTH = 3

        /** So lange nach der letzten Eingabe wird gesucht. */
        const val DELAY_MILLIS = 400L
    }
}
