package io.github.q0ochen.schafkopf.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Wartet in echter Zeit (höchstens 5 Sekunden) auf den ersten passenden Wert. ViewModels rechnen auf
 * `Dispatchers.Default`; die virtuelle Zeit von `runTest` würde darüber hinwegspringen.
 */
suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T =
    withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { first(predicate) } }
