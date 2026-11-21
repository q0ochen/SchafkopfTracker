package io.github.q0ochen.schafkopf.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** [awaitFirst] wartet in echter Zeit und lässt den Test danach auf seinem eigenen Thread weiterlaufen. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitTest {

    @Test
    fun `nach dem Warten geht es auf dem Thread des Tests weiter`() = runTest(UnconfinedTestDispatcher()) {
        val testThread = Thread.currentThread()
        val value = MutableStateFlow(0)
        // Der passende Wert kommt von einem echten Thread, wie bei Arbeit auf Dispatchers.IO.
        launch(Dispatchers.Default) {
            delay(50)
            value.value = 1
        }

        assertEquals(1, value.awaitFirst { it == 1 })
        assertSame(testThread, Thread.currentThread())
    }
}
