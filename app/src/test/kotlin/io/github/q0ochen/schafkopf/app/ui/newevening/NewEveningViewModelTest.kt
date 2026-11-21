package io.github.q0ochen.schafkopf.app.ui.newevening

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** „Neuer Abend“, geprüft ohne Oberfläche: nur das ViewModel mit Ablagen im Arbeitsspeicher. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewEveningViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val players = InMemoryPlayerRepository(List(4) { Player(PlayerId("p$it"), "Spieler ${it + 1}", colorIndex = it) })
    private val evenings = InMemoryEveningRepository()
    private val searched = mutableListOf<String>()
    private val post = PlaceSuggestion("Gasthof Post", "Hauptstraße 1, 96049 Bamberg", "Gasthof Post, Hauptstraße 1, 96049 Bamberg")
    private val placeSearch = PlaceSearch { query ->
        searched += query
        listOf(post)
    }

    /** „Jetzt“: Samstag, 21.11.2026, 10:00 Uhr in Berlin. */
    private val berlinClock: Clock = Clock.fixed(Instant.parse("2026-11-21T09:00:00Z"), ZoneId.of("Europe/Berlin"))

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private suspend fun TestScope.ready(clock: Clock = berlinClock): NewEveningViewModel {
        var next = 0
        val viewModel = NewEveningViewModel(players, evenings, InMemorySettingsRepository(), clock, placeSearch) { "abend-${++next}" }
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { it.canStart } }
        return viewModel
    }

    private suspend fun TestScope.started(viewModel: NewEveningViewModel): Evening {
        viewModel.start {}
        advanceUntilIdle()
        return evenings.evenings.first().single()
    }

    @Test
    fun `doppeltes Antippen von Abend starten legt nur einen Abend an`() = runTest(dispatcher) {
        val viewModel = ready(Clock.fixed(Instant.parse("2026-11-14T18:30:00Z"), ZoneOffset.UTC))

        val started = mutableListOf<String>()
        viewModel.start { started += it }
        viewModel.start { started += it }
        advanceUntilIdle()

        assertEquals(listOf("abend-1"), started)
        assertEquals(1, evenings.evenings.first().size)
    }

    @Test
    fun `heute ist vorbelegt, und der Abend beginnt beim Antippen`() = runTest(dispatcher) {
        val viewModel = ready()
        assertEquals(LocalDate.of(2026, 11, 21), viewModel.state.value.date)
        assertFalse(viewModel.state.value.planned)

        assertEquals(Instant.parse("2026-11-21T09:00:00Z"), started(viewModel).startedAt)
    }

    @Test
    fun `ein Tag nach heute ist geplant und beginnt dort zur selben Uhrzeit`() = runTest(dispatcher) {
        val viewModel = ready()
        viewModel.setDate(LocalDate.of(2026, 12, 12))
        assertEquals(LocalDate.of(2026, 12, 12), viewModel.state.value.date)
        assertTrue(viewModel.state.value.planned)

        val evening = started(viewModel)
        // 10:00 Uhr Ortszeit wie beim Antippen, am 12.12.2026.
        assertEquals(Instant.parse("2026-12-12T09:00:00Z"), evening.startedAt)
        assertTrue(evening.isRunning)
    }

    @Test
    fun `ein Tag vor heute geht auch und ist nicht geplant`() = runTest(dispatcher) {
        val viewModel = ready()
        viewModel.setDate(LocalDate.of(2026, 7, 10))
        assertFalse(viewModel.state.value.planned)
        // Im Sommer gilt in Berlin die Sommerzeit: 10:00 Uhr Ortszeit sind 8:00 Uhr UTC.
        assertEquals(Instant.parse("2026-07-10T08:00:00Z"), started(viewModel).startedAt)
    }

    @Test
    fun `wieder heute gewaehlt beginnt der Abend beim Antippen`() = runTest(dispatcher) {
        val viewModel = ready()
        viewModel.setDate(LocalDate.of(2026, 12, 12))
        viewModel.setDate(LocalDate.of(2026, 11, 21))
        assertFalse(viewModel.state.value.planned)
        assertEquals(Instant.parse("2026-11-21T09:00:00Z"), started(viewModel).startedAt)
    }

    @Test
    fun `Ortsvorschlag antippen uebernimmt ihn als Ort des neuen Abends`() = runTest(dispatcher) {
        val viewModel = ready()
        viewModel.setLocation("Ga")
        advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), searched)

        viewModel.setLocation("Gasthof P")
        advanceTimeBy(401)
        assertEquals(listOf("Gasthof P"), searched)
        assertEquals(listOf(post), viewModel.state.value.places.suggestions)

        viewModel.pickPlace(post)
        assertEquals(post.text, viewModel.state.value.location)
        assertTrue(viewModel.state.value.places.suggestions.isEmpty())
        assertEquals(post.text, started(viewModel).location)
    }

    @Test
    fun `der Ort wird auf 100 Zeichen begrenzt`() = runTest(dispatcher) {
        val viewModel = ready()
        viewModel.setLocation("x".repeat(160))
        assertEquals(100, viewModel.state.value.location.length)
    }
}
