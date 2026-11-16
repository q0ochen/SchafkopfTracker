package io.github.q0ochen.schafkopf.app.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.data.drive.DriveBackup
import io.github.q0ochen.schafkopf.data.drive.DriveError
import io.github.q0ochen.schafkopf.data.drive.DriveResult
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Wohin automatisch gesichert wird. */
enum class AutoBackupKind {
    /** In eine Datei aus der Dateiauswahl von Android (auf dem Gerät oder bei einem Cloud-Dienst mit eigener App). */
    DOCUMENT,

    /** Direkt in Google Drive (Ordner `SchafkopfTracker_Backups`), mit Anmeldung bei Google. */
    GOOGLE_DRIVE,
}

/** Warum die letzte automatische Sicherung nicht geklappt hat. Die Texte dazu stehen in der Oberfläche. */
enum class AutoBackupError {
    /** Die App darf die Datei nicht mehr schreiben (Recht entzogen, Datei gelöscht oder verschoben). */
    ACCESS_LOST,

    /** Die Anmeldung bei Google gilt nicht mehr: neu verbinden. */
    SIGN_IN_REQUIRED,

    /** Schreiben ging gerade nicht, zum Beispiel ohne Netz beim Cloud-Dienst; die App versucht es weiter. */
    WRITE_FAILED,
}

/**
 * Stand der automatischen Sicherung.
 *
 * @property target Die Datei, in die gesichert wird (Adresse aus der Dateiauswahl), oder [DRIVE_TARGET];
 *   `null`: ausgeschaltet.
 * @property targetName Ihr Name, wie der Anbieter ihn zeigt, bei Google Drive das Konto.
 * @property kind Datei oder Google Drive.
 * @property lastSuccess Wann zuletzt erfolgreich gesichert wurde.
 * @property lastError Was beim letzten Versuch schiefging; `null`, wenn er geklappt hat.
 * @property lastErrorAt Wann das war.
 */
data class AutoBackupStatus(
    val target: String? = null,
    val targetName: String? = null,
    val kind: AutoBackupKind = AutoBackupKind.DOCUMENT,
    val lastSuccess: Instant? = null,
    val lastError: AutoBackupError? = null,
    val lastErrorAt: Instant? = null,
) {
    val enabled: Boolean get() = target != null

    companion object {
        /** Ziel „Google Drive“; es gibt dort keine Datei-Adresse wie bei der Dateiauswahl. */
        const val DRIVE_TARGET = "google-drive"
    }
}

/**
 * Merkt sich Ziel und Verlauf der automatischen Sicherung, nur auf diesem Gerät. Das gehört bewusst nicht in die
 * Sicherung selbst: Die Adresse der Datei gilt nur auf diesem einen Gerät.
 */
class AutoBackupStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("automatische_sicherung", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val status: StateFlow<AutoBackupStatus> = state.asStateFlow()

    /** Neues Ziel (oder `null` zum Ausschalten); der bisherige Verlauf gilt dann nicht mehr. */
    fun setTarget(uri: String?, name: String?, kind: AutoBackupKind = AutoBackupKind.DOCUMENT) {
        prefs.edit {
            clear()
            if (uri != null) {
                putString(KEY_TARGET, uri)
                putString(KEY_KIND, kind.name)
                if (name != null) putString(KEY_NAME, name)
            }
        }
        state.value = read()
    }

    /** Der angezeigte Name des Ziels hat sich geändert (zum Beispiel das Google-Konto). */
    fun setTargetName(name: String?) {
        prefs.edit { if (name == null) remove(KEY_NAME) else putString(KEY_NAME, name) }
        state.value = read()
    }

    fun recordSuccess(at: Instant) {
        prefs.edit {
            putLong(KEY_SUCCESS, at.toEpochMilli())
            remove(KEY_ERROR)
            remove(KEY_ERROR_AT)
        }
        state.value = read()
    }

    fun recordFailure(error: AutoBackupError, at: Instant) {
        prefs.edit {
            putString(KEY_ERROR, error.name)
            putLong(KEY_ERROR_AT, at.toEpochMilli())
        }
        state.value = read()
    }

    private fun read() = AutoBackupStatus(
        target = prefs.getString(KEY_TARGET, null),
        targetName = prefs.getString(KEY_NAME, null),
        kind = prefs.getString(KEY_KIND, null)?.let { name -> AutoBackupKind.entries.firstOrNull { it.name == name } }
            ?: AutoBackupKind.DOCUMENT,
        lastSuccess = prefs.instant(KEY_SUCCESS),
        lastError = prefs.getString(KEY_ERROR, null)?.let { name -> AutoBackupError.entries.firstOrNull { it.name == name } },
        lastErrorAt = prefs.instant(KEY_ERROR_AT),
    )

    private fun SharedPreferences.instant(key: String): Instant? =
        if (contains(key)) Instant.ofEpochMilli(getLong(key, 0L)) else null

    private companion object {
        const val KEY_TARGET = "ziel"
        const val KEY_NAME = "name"
        const val KEY_KIND = "art"
        const val KEY_SUCCESS = "zuletzt"
        const val KEY_ERROR = "fehler"
        const val KEY_ERROR_AT = "fehler_am"
    }
}

/** Ergebnis eines Sicherungsversuchs. */
enum class AutoBackupOutcome { SAVED, NO_TARGET, ACCESS_LOST, SIGN_IN_REQUIRED, FAILED }

/** Schreibt die Sicherung in die gewählte Datei oder nach Google Drive und hält fest, wie es lief. */
class AutoBackupRunner(
    private val backup: BackupService,
    private val store: AutoBackupStore,
    private val documents: DocumentAccess,
    private val drive: DriveBackup,
    private val clock: Clock,
) {
    /** Zwei Versuche zugleich (Hintergrund und „Jetzt sichern“) schreiben nacheinander, nie durcheinander. */
    private val lock = Mutex()

    suspend fun run(): AutoBackupOutcome = lock.withLock {
        val status = store.status.value
        val target = status.target ?: return@withLock AutoBackupOutcome.NO_TARGET
        try {
            val json = backup.exportJson()
            if (status.kind == AutoBackupKind.GOOGLE_DRIVE) return@withLock toDrive(json)
            // Einmal begonnen, wird die Datei fertig geschrieben, auch wenn inzwischen ein neuerer Auftrag ansteht.
            withContext(NonCancellable) { documents.write(target, json) }
            store.recordSuccess(clock.instant())
            AutoBackupOutcome.SAVED
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            store.recordFailure(AutoBackupError.ACCESS_LOST, clock.instant())
            AutoBackupOutcome.ACCESS_LOST
        } catch (_: FileNotFoundException) {
            // Die Datei gibt es nicht mehr (gelöscht oder verschoben): Warten hilft nicht, sie muss neu gewählt werden.
            store.recordFailure(AutoBackupError.ACCESS_LOST, clock.instant())
            AutoBackupOutcome.ACCESS_LOST
        } catch (_: Exception) {
            store.recordFailure(AutoBackupError.WRITE_FAILED, clock.instant())
            AutoBackupOutcome.FAILED
        }
    }

    /** Hochladen nach Drive: Der neue Inhalt gilt erst, wenn er vollständig angekommen ist. */
    private suspend fun toDrive(json: String): AutoBackupOutcome =
        when (val result = withContext(NonCancellable) { drive.save(json) }) {
            is DriveResult.Success -> {
                store.recordSuccess(clock.instant())
                AutoBackupOutcome.SAVED
            }
            is DriveResult.Failure -> when (result.error) {
                DriveError.SIGN_IN_REQUIRED, DriveError.ACCESS_DENIED -> {
                    store.recordFailure(AutoBackupError.SIGN_IN_REQUIRED, clock.instant())
                    AutoBackupOutcome.SIGN_IN_REQUIRED
                }
                else -> {
                    store.recordFailure(AutoBackupError.WRITE_FAILED, clock.instant())
                    AutoBackupOutcome.FAILED
                }
            }
        }
}

/**
 * Stößt die automatische Sicherung an: nach jeder Änderung an Spielern, Abenden oder der Vorlage des Regelwerks,
 * gebündelt (erst wenn ein paar Sekunden Ruhe ist). Die Arbeit übernimmt WorkManager; der Auftrag übersteht, dass
 * Android die App beendet, und wird bei einem Fehler mit wachsendem Abstand wiederholt.
 *
 * @param workManager Erst beim ersten Auftrag geholt, damit Tests ohne WorkManager auskommen.
 */
class AutoBackupScheduler(private val workManager: () -> WorkManager, private val store: AutoBackupStore) {

    /**
     * Stößt eine Sicherung an.
     *
     * @param replace `true`: ersetzt einen noch wartenden Auftrag, denn gesichert wird ohnehin immer der neueste
     *   Stand. `false`: Ein wartender Auftrag (etwa ein Wiederholungsversuch) bleibt, wie er ist, samt seiner Zahl
     *   an Versuchen; nur wenn keiner wartet, kommt ein neuer dazu.
     */
    fun request(delay: Duration = DEBOUNCE, replace: Boolean = true) {
        val status = store.status.value
        if (!status.enabled) return
        // Nach Drive erst, wenn Netz da ist; WorkManager holt das von selbst nach.
        val network = if (status.kind == AutoBackupKind.GOOGLE_DRIVE) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_SECONDS, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager().enqueueUniqueWork(WORK_NAME, policy, request)
    }

    fun cancel() {
        workManager().cancelUniqueWork(WORK_NAME)
    }

    /** Beobachtet die Daten; jede Änderung (nicht schon der Stand beim Start) stößt eine Sicherung an. */
    fun watch(scope: CoroutineScope, changes: Flow<Any>): Job = scope.launch {
        changes.drop(1).collect { request() }
    }

    companion object {
        const val WORK_NAME = "automatische-sicherung"
        val DEBOUNCE: Duration = Duration.ofSeconds(3)
        const val RETRY_SECONDS = 30L

        /** Alles, was in eine Sicherung gehört: Spieler, Abende und die Vorlage des Regelwerks. */
        fun changesOf(players: PlayerRepository, evenings: EveningRepository, settings: SettingsRepository): Flow<Any> =
            combine(players.players, evenings.evenings, settings.settings.map { it.defaultRules }) { p, e, r -> Triple(p, e, r) }
                .distinctUntilChanged()
    }
}

/** Die Arbeit im Hintergrund: eine Sicherung schreiben; bei einem vorübergehenden Fehler später noch einmal. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val runner: AutoBackupRunner by inject()

    override suspend fun doWork(): Result = when (runner.run()) {
        AutoBackupOutcome.SAVED, AutoBackupOutcome.NO_TARGET -> Result.success()
        // Ohne Recht auf die Datei oder ohne Anmeldung hilft Warten nicht; die Oberfläche bittet darum, neu zu wählen.
        AutoBackupOutcome.ACCESS_LOST, AutoBackupOutcome.SIGN_IN_REQUIRED -> Result.failure()
        AutoBackupOutcome.FAILED -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        /** Mit wachsendem Abstand ab 30 Sekunden sind das zusammen gut acht Stunden. */
        const val MAX_ATTEMPTS = 10
    }
}
