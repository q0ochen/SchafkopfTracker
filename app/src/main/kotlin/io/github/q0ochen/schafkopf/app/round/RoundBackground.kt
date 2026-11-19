package io.github.q0ochen.schafkopf.app.round

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Lädt hoch, was beim Schließen der App noch nicht auf dem Server der gemeinsamen Runde war: Der Auftrag wartet auf
 * Netz, übersteht, dass Android die App beendet, und wird bei einem Fehler mit wachsendem Abstand wiederholt.
 *
 * @param workManager Erst beim ersten Auftrag geholt, damit Tests ohne WorkManager auskommen.
 */
class RoundBackgroundSync(private val workManager: () -> WorkManager) {

    /** Stößt den Abgleich im Hintergrund an. Wartet schon einer, bleibt er, wie er ist. */
    fun request() {
        val request = OneTimeWorkRequestBuilder<RoundSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager().enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "runde-abgleich"
        const val RETRY_SECONDS = 30L
    }
}

/** Die Arbeit im Hintergrund: ein vollständiger Abgleich; ohne Verbindung später noch einmal. */
class RoundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val coordinator: RoundCoordinator by inject()

    override suspend fun doWork(): Result = result(coordinator.syncInBackground(), runAttemptCount)

    companion object {
        /** Mit wachsendem Abstand ab 30 Sekunden sind das zusammen gut vier Stunden. */
        const val MAX_ATTEMPTS = 9

        /** Was der Auftrag nach einem Abgleich tut: fertig, oder später noch einmal. */
        fun result(outcome: SyncOutcome, attempt: Int): Result = when (outcome) {
            // Wartet nach einem Abgleich noch etwas, obwohl sich etwas bewegt hat, war gerade jemand anders schneller.
            is SyncOutcome.Synced ->
                if (outcome.pending > 0 && outcome.pulled + outcome.pushed > 0 && attempt < MAX_ATTEMPTS) Result.retry() else Result.success()
            SyncOutcome.Offline, is SyncOutcome.Failed -> if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
            // Ohne Runde oder ohne Anmeldung hilft Warten nicht; die Oberfläche bittet beim nächsten Öffnen darum.
            SyncOutcome.SignedOut, SyncOutcome.NotLinked, SyncOutcome.Detached, SyncOutcome.Unchanged -> Result.success()
        }
    }
}
