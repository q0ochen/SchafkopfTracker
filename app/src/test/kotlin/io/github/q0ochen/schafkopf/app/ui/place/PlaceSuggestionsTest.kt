package io.github.q0ochen.schafkopf.app.ui.place

import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Die Steuerung der Ortsvorschläge (docs/ARCHITEKTUR.md, Abschnitt 4.3), ohne Oberfläche und ohne Netz. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSuggestionsTest {

    private fun suggestion(name: String) = PlaceSuggestion(name, "Bamberg", "$name, Bamberg")

    /** Eine Suche, die jede Anfrage mitschreibt und mit [answer] beantwortet. */
    private class RecordingSearch(val answer: suspend (String) -> List<PlaceSuggestion>) : PlaceSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): List<PlaceSuggestion> {
            queries += query
            return answer(query)
        }
    }

    private fun TestScope.controller(search: PlaceSearch) = PlaceSuggestions(backgroundScope, search)

    @Test
    fun `sucht erst 400 ms nach der letzten Eingabe und nach dem getrimmten Text`() = runTest {
        val search = RecordingSearch { listOf(suggestion(it)) }
        val places = controller(search)

        places.typed("  Kli")
        advanceTimeBy(399)
        runCurrent()
        assertEquals(emptyList<String>(), search.queries)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Kli"), search.queries)
        assertEquals(listOf(suggestion("Kli")), places.state.value.suggestions)
    }

    @Test
    fun `jede neue Eingabe verwirft die geplante Suche`() = runTest {
        val search = RecordingSearch { listOf(suggestion(it)) }
        val places = controller(search)

        places.typed("Kli")
        advanceTimeBy(300)
        places.typed("Klini")
        advanceTimeBy(300)
        places.typed("Klinikum")
        advanceTimeBy(401)
        runCurrent()

        assertEquals(listOf("Klinikum"), search.queries)
        assertEquals(listOf(suggestion("Klinikum")), places.state.value.suggestions)
    }

    @Test
    fun `es zaehlt nur die Antwort auf die letzte Eingabe`() = runTest {
        val slow = CompletableDeferred<List<PlaceSuggestion>>()
        val search = RecordingSearch { query -> if (query == "Post") slow.await() else listOf(suggestion(query)) }
        val places = controller(search)

        places.typed("Post")
        advanceTimeBy(401)
        runCurrent()
        places.typed("Posthorn")
        // Die alte Antwort kommt zu spät: Ihre Suche ist schon verworfen.
        slow.complete(listOf(suggestion("Post")))
        advanceTimeBy(401)
        runCurrent()

        assertEquals(listOf("Post", "Posthorn"), search.queries)
        assertEquals(listOf(suggestion("Posthorn")), places.state.value.suggestions)
    }

    @Test
    fun `unter drei Zeichen wird nicht gesucht und die Liste verschwindet`() = runTest {
        val search = RecordingSearch { listOf(suggestion(it)) }
        val places = controller(search)

        places.typed("Kel")
        advanceTimeBy(401)
        runCurrent()
        assertEquals(1, places.state.value.suggestions.size)

        places.typed(" Ke ")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("Kel"), search.queries)
        assertEquals(PlaceSuggestionsState(), places.state.value)
    }

    @Test
    fun `ohne Netz kommt der Hinweis, bis wieder getippt wird`() = runTest {
        var online = false
        val search = RecordingSearch { if (online) listOf(suggestion(it)) else throw IOException("kein Netz") }
        val places = controller(search)

        places.typed("Keller")
        advanceTimeBy(401)
        runCurrent()
        assertTrue(places.state.value.offline)
        assertTrue(places.state.value.suggestions.isEmpty())

        online = true
        places.typed("Kellerei")
        assertFalse(places.state.value.offline)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf(suggestion("Kellerei")), places.state.value.suggestions)
    }

    @Test
    fun `bis zur neuen Antwort bleiben die bisherigen Vorschlaege stehen`() = runTest {
        val search = RecordingSearch { listOf(suggestion(it)) }
        val places = controller(search)

        places.typed("Keller")
        advanceTimeBy(401)
        runCurrent()
        places.typed("Kellerei")
        assertEquals(listOf(suggestion("Keller")), places.state.value.suggestions)
    }

    @Test
    fun `eine Abbruch-Ausnahme ohne Abbruch zaehlt als Fehler`() = runTest {
        val places = controller(RecordingSearch { throw CancellationException("vom HTTP-Client") })

        places.typed("Keller")
        advanceTimeBy(401)
        runCurrent()
        assertTrue(places.state.value.offline)
    }

    @Test
    fun `nach dem Antippen ist die Liste zu und eine geplante Suche verworfen`() = runTest {
        val search = RecordingSearch { listOf(suggestion(it)) }
        val places = controller(search)

        places.typed("Keller")
        advanceTimeBy(401)
        runCurrent()
        places.typed("Kellerei")
        places.close()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("Keller"), search.queries)
        assertEquals(PlaceSuggestionsState(), places.state.value)
    }
}
