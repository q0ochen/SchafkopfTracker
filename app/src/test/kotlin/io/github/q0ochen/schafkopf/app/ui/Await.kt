package io.github.q0ochen.schafkopf.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Wartet in echter Zeit (höchstens 5 Sekunden) auf den ersten passenden Wert. Gewartet wird auf `Dispatchers.Default`,
 * weil die virtuelle Zeit von `runTest` über Arbeit auf echten Threads hinwegspränge (die Sicherung spricht Google
 * Drive über `Dispatchers.IO` an).
 *
 * Danach geht es auf dem Thread des Tests weiter, über den Planer von `runTest`. Mit `UnconfinedTestDispatcher` lief der
 * Test sonst auf dem Thread weiter, der gewartet hatte, und das manchmal noch nach `Dispatchers.resetMain()`: Am
 * 21.11.2026 scheiterte so ein späterer Test mit „Dispatchers.Main was accessed when the platform dispatcher was
 * absent and the test dispatcher was unset“.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T {
    val scheduler = checkNotNull(currentCoroutineContext()[TestCoroutineScheduler]) { "awaitFirst gehört in runTest" }
    return withContext(StandardTestDispatcher(scheduler)) {
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { first(predicate) } }
    }
}
