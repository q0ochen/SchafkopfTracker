package io.github.q0ochen.schafkopf.app.ui.closing

import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.awaitFirst
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Der Abschluss folgt dem gespeicherten Abend und den Namen, ohne Oberfläche geprüft. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClosingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `rechnet den Abend durch und folgt neuen Namen`() = runTest(dispatcher) {
        val players = InMemoryPlayerRepository(TestEvenings.players)
        val viewModel = ClosingViewModel(EveningId("abend-1"), InMemoryEveningRepository(listOf(TestEvenings.evening())), players)
        backgroundScope.launch { viewModel.state.collect {} }

        val loaded = viewModel.state.awaitFirst { !it.loading }
        assertEquals(2, loaded.computed?.gamesPlayed)
        assertEquals(listOf("Anna", "Cilli", "Done", "Bert"), loaded.closing?.standings?.map { it.name })
        assertEquals(3, loaded.closing?.transfers?.size)

        players.save(Player(TestEvenings.bert, "Berti", colorIndex = 1))
        val renamed = viewModel.state.awaitFirst { it.closing?.standings?.last()?.name == "Berti" }
        assertEquals(1, renamed.colorIndex[TestEvenings.bert])
    }

    @Test
    fun `einen geloeschten Abend gibt es nicht mehr`() = runTest(dispatcher) {
        val viewModel = ClosingViewModel(
            EveningId("weg"),
            InMemoryEveningRepository(listOf(TestEvenings.evening())),
            InMemoryPlayerRepository(TestEvenings.players),
        )
        backgroundScope.launch { viewModel.state.collect {} }
        val state = viewModel.state.awaitFirst { !it.loading }
        assertNull(state.computed)
        assertNull(state.closing)
    }
}
