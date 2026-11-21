package io.github.q0ochen.schafkopf.app.ui.editevening

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/** „Abend bearbeiten“, geprüft ohne Oberfläche: nur das ViewModel mit Ablagen im Arbeitsspeicher. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditEveningViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    /** „Jetzt“: Samstag, 21.11.2026, 10:00 Uhr in Berlin. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-21T09:00:00Z"), berlin)

    private val a = PlayerId("a")
    private val b = PlayerId("b")
    private val c = PlayerId("c")
    private val d = PlayerId("d")
    private val id = EveningId("abend")

    /** Freitag, 20.11.2026, 19:30 Uhr in Berlin, beendet um 23:45 Uhr. */
    private val evening = Evening(
        id = id,
        startedAt = Instant.parse("2026-11-20T18:30:00Z"),
        finishedAt = Instant.parse("2026-11-20T22:45:00Z"),
        location = "Wirtshaus",
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
    )

    private val players = InMemoryPlayerRepository(listOf(a, b, c, d).mapIndexed { index, player -> Player(player, "Spieler ${index + 1}", colorIndex = index) })
    private val evenings = InMemoryEveningRepository(listOf(evening))
    private val linked = MutableStateFlow(false)
    private val searched = mutableListOf<String>()
    private val post = PlaceSuggestion("Gasthof Post", "Hauptstraße 1, 96049 Bamberg", "Gasthof Post, Hauptstraße 1, 96049 Bamberg")
    private val placeSearch = PlaceSearch { query ->
        searched += query
        listOf(post)
    }

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private suspend fun TestScope.loaded(eveningId: EveningId = id): EditEveningViewModel {
        val viewModel = EditEveningViewModel(eveningId, evenings, players, EveningEditor(evenings, clock) { "neu" }, clock, linked, placeSearch)
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { !it.loading } }
        return viewModel
    }

    private suspend fun stored(): Evening = evenings.evening(id).first()!!

    @Test
    fun `zeigt den Abend und laesst ohne Aenderung nicht speichern`() = runTest(dispatcher) {
        val state = loaded().state.value
        assertEquals(LocalDate.of(2026, 11, 20), state.date)
        assertEquals(evening.startedAt, state.startedAt)
        assertEquals("Wirtshaus", state.location)
        assertEquals(listOf(a, b, c, d), state.seating)
        assertEquals(a, state.firstDealer)
        assertEquals("Spieler 3", state.name(c))
        assertFalse(state.changed)
        assertFalse(state.canSave)
        assertFalse(state.missing)
    }

    @Test
    fun `ein anderes Datum behaelt die Uhrzeit, auch ueber die Sommerzeit hinweg`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setDate(LocalDate.of(2026, 7, 10))
        assertEquals(Instant.parse("2026-07-10T17:30:00Z"), viewModel.state.value.startedAt)
        assertTrue(viewModel.state.value.canSave)

        var saved = false
        viewModel.save { saved = true }
        advanceUntilIdle()

        assertTrue(saved)
        val result = stored()
        assertEquals(Instant.parse("2026-07-10T17:30:00Z"), result.startedAt)
        // Ein beendeter Abend behält seine Dauer.
        assertEquals(Instant.parse("2026-07-10T21:45:00Z"), result.finishedAt)
        assertEquals("Wirtshaus", result.location)
    }

    @Test
    fun `auch Tage nach heute lassen sich waehlen`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setDate(LocalDate.of(2026, 12, 12))
        assertEquals(LocalDate.of(2026, 12, 12), viewModel.state.value.date)
        assertEquals(Instant.parse("2026-12-12T18:30:00Z"), viewModel.state.value.startedAt)
        viewModel.save {}
        advanceUntilIdle()
        assertEquals(Instant.parse("2026-12-12T18:30:00Z"), stored().startedAt)
    }

    @Test
    fun `derselbe Tag aendert nichts`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setDate(LocalDate.of(2026, 11, 21))
        assertEquals(LocalDate.of(2026, 11, 21), viewModel.state.value.date)
        viewModel.setDate(LocalDate.of(2026, 11, 20))
        assertEquals(evening.startedAt, viewModel.state.value.startedAt)
        assertFalse(viewModel.state.value.changed)
    }

    @Test
    fun `nur Leerraum am Ort zaehlt nicht als Aenderung`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setLocation("  Wirtshaus ")
        assertFalse(viewModel.state.value.changed)

        viewModel.setLocation(" Beim Huber ")
        assertTrue(viewModel.state.value.changed)
        viewModel.save {}
        advanceUntilIdle()
        assertEquals("Beim Huber", stored().location)
    }

    @Test
    fun `der Ort wird auf 100 Zeichen begrenzt`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setLocation("x".repeat(160))
        assertEquals(100, viewModel.state.value.location.length)
    }

    @Test
    fun `nach dem Tippen kommen Ortsvorschlaege, antippen uebernimmt den ganzen Vorschlag`() = runTest(dispatcher) {
        val viewModel = loaded()
        // Beim Öffnen wird für den vorhandenen Ort nicht gesucht.
        advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), searched)

        viewModel.setLocation("Gasthof P")
        advanceTimeBy(401)
        assertEquals(listOf("Gasthof P"), searched)
        assertEquals(listOf(post), viewModel.state.value.places.suggestions)

        viewModel.pickPlace(post)
        assertEquals(post.text, viewModel.state.value.location)
        assertTrue(viewModel.state.value.places.suggestions.isEmpty())
        viewModel.save {}
        advanceUntilIdle()
        assertEquals(post.text, stored().location)
        assertEquals(listOf("Gasthof P"), searched)
    }

    @Test
    fun `Sitzordnung und erster Geber werden gespeichert`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.move(c, -1)
        viewModel.move(a, -1)
        viewModel.move(d, 1)
        viewModel.setFirstDealer(c)
        viewModel.setFirstDealer(PlayerId("fremd"))
        assertEquals(listOf(a, c, b, d), viewModel.state.value.seating)
        assertEquals(c, viewModel.state.value.firstDealer)

        viewModel.save {}
        advanceUntilIdle()
        val result = stored()
        assertEquals(listOf(a, c, b, d), result.seating)
        assertEquals(c, result.firstDealer)
        assertEquals(evening.startedAt, result.startedAt)
    }

    @Test
    fun `was ein anderes Handy inzwischen geaendert hat, bleibt`() = runTest(dispatcher) {
        val viewModel = loaded()
        // Während der Bildschirm offen ist, ändert die gemeinsame Runde den Ort.
        evenings.save(stored().copy(location = "Daheim"))
        viewModel.setFirstDealer(b)
        viewModel.save {}
        advanceUntilIdle()

        val result = stored()
        assertEquals("Daheim", result.location)
        assertEquals(b, result.firstDealer)
    }

    @Test
    fun `doppeltes Antippen speichert nur einmal`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.setFirstDealer(d)
        var saved = 0
        viewModel.save { saved++ }
        viewModel.save { saved++ }
        advanceUntilIdle()
        assertEquals(1, saved)
    }

    @Test
    fun `ohne den Abend gibt es nichts zu bearbeiten`() = runTest(dispatcher) {
        val viewModel = loaded(EveningId("weg"))
        assertTrue(viewModel.state.value.missing)
        var saved = false
        viewModel.save { saved = true }
        advanceUntilIdle()
        assertFalse(saved)
    }

    @Test
    fun `der Hinweis zur gemeinsamen Runde folgt der Verbindung`() = runTest(dispatcher) {
        val viewModel = loaded()
        assertFalse(viewModel.state.value.linked)
        linked.value = true
        assertTrue(viewModel.state.value.linked)
    }
}
