package io.github.q0ochen.schafkopf.app.ui.newevening

import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** „Neuer Abend“, geprüft ohne Oberfläche: nur das ViewModel mit Ablagen im Arbeitsspeicher. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewEveningViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `doppeltes Antippen von Abend starten legt nur einen Abend an`() = runTest(dispatcher) {
        val players = InMemoryPlayerRepository(List(4) { Player(PlayerId("p$it"), "Spieler ${it + 1}", colorIndex = it) })
        val evenings = InMemoryEveningRepository()
        var next = 0
        val clock = Clock.fixed(Instant.parse("2026-11-14T18:30:00Z"), ZoneOffset.UTC)
        val viewModel = NewEveningViewModel(players, evenings, InMemorySettingsRepository(), clock) { "abend-${++next}" }
        backgroundScope.launch { viewModel.state.collect {} }
        withTimeout(5_000) { viewModel.state.first { it.canStart } }

        val started = mutableListOf<String>()
        viewModel.start { started += it }
        viewModel.start { started += it }
        advanceUntilIdle()

        assertEquals(listOf("abend-1"), started)
        assertEquals(1, evenings.evenings.first().size)
    }
}
