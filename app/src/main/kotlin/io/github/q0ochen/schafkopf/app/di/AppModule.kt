package io.github.q0ochen.schafkopf.app.di

import android.content.Context
import androidx.work.WorkManager
import io.github.q0ochen.schafkopf.app.backup.AutoBackupRunner
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.backup.ContentResolverDocuments
import io.github.q0ochen.schafkopf.app.backup.DocumentAccess
import io.github.q0ochen.schafkopf.app.ui.backup.BackupViewModel
import io.github.q0ochen.schafkopf.app.ui.board.BoardViewModel
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.app.ui.closing.ClosingViewModel
import io.github.q0ochen.schafkopf.app.ui.statistics.StatisticsViewModel
import io.github.q0ochen.schafkopf.data.storage.FileStorage
import io.github.q0ochen.schafkopf.app.ui.gameform.GameFormViewModel
import io.github.q0ochen.schafkopf.app.ui.newevening.NewEveningViewModel
import io.github.q0ochen.schafkopf.app.ui.settings.PlayersViewModel
import io.github.q0ochen.schafkopf.app.ui.settings.RulesViewModel
import io.github.q0ochen.schafkopf.app.ui.settings.SettingsViewModel
import io.github.q0ochen.schafkopf.app.ui.start.StartViewModel
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySecretStore
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.time.Clock
import java.util.UUID

/**
 * Ordner im privaten Speicher der App, in dem alle Daten als JSON-Dateien liegen (siehe [FileStorage]).
 * Nur die App selbst kann ihn lesen; beim Deinstallieren löscht Android ihn mit.
 */
const val STORAGE_FOLDER = "daten"

/** Version der App, wie sie im Kopf jeder Sicherung steht. */
private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

/**
 * Kompositionswurzel der App: Hier und nur hier steht, welche Umsetzung hinter welchem Port steckt.
 * Alle Klassen bekommen ihre Abhängigkeiten über den Konstruktor; Koin setzt sie zusammen.
 *
 * Gespeichert wird dauerhaft in JSON-Dateien ([FileStorage] aus dem Modul `data`): Jede Eingabe steht sofort auf
 * dem Gerät und übersteht, dass Android die App beendet. Die Room-Datenbank (Modul `database`) löst das später ab.
 *
 * ViewModels mit Parametern bekommen Kennungen als Text; ein leerer Text bedeutet „keine“.
 */
val appModule = module {
    single<Clock> { Clock.systemDefaultZone() }
    single<IdGenerator> { IdGenerator { UUID.randomUUID().toString() } }

    // Für einen Ordner darf es nur ein einziges FileStorage geben; alle drei Ablagen kommen aus ihm.
    single { FileStorage(androidContext().filesDir.toPath().resolve(STORAGE_FOLDER), Dispatchers.IO) }
    single<PlayerRepository> { get<FileStorage>().players }
    single<EveningRepository> { get<FileStorage>().evenings }
    single<SettingsRepository> { get<FileStorage>().settings }
    // Der Schlüssel der KI-Zusammenfassung bleibt vorerst nur im Arbeitsspeicher (die Funktion kommt in Schritt 6).
    single<SecretStore> { InMemorySecretStore() }

    single { EveningEditor(get(), get(), get()) }

    // Sicherung: als Datei und automatisch in eine einmal gewählte Datei (zum Beispiel in Google Drive).
    single { BackupService(get<PlayerRepository>(), get<EveningRepository>(), get<SettingsRepository>(), get(), appVersion(androidContext()), Dispatchers.Default) }
    single<DocumentAccess> { ContentResolverDocuments(androidContext()) }
    single { AutoBackupStore(androidContext()) }
    single { AutoBackupRunner(get(), get(), get(), get()) }
    single { AutoBackupScheduler({ WorkManager.getInstance(androidContext()) }, get()) }

    viewModel { StartViewModel(get(), get()) }
    viewModel { NewEveningViewModel(get(), get(), get(), get(), get()) }
    viewModel { params -> BoardViewModel(EveningId(params.get(0)), get(), get(), get(), get()) }
    viewModel { params ->
        GameFormViewModel(
            eveningId = EveningId(params.get(0)),
            entryId = params.get<String>(1).takeIf { it.isNotEmpty() }?.let(::EntryId),
            evenings = get(),
            players = get(),
            editor = get(),
        )
    }
    viewModel { params ->
        RulesViewModel(
            eveningId = params.get<String>(0).takeIf { it.isNotEmpty() }?.let(::EveningId),
            settings = get(),
            evenings = get(),
            editor = get(),
        )
    }
    viewModel { params -> ClosingViewModel(EveningId(params.get(0)), get(), get()) }
    viewModel { params ->
        StatisticsViewModel(
            initial = params.get<String>(0).takeIf { it.isNotEmpty() }?.let(::EveningId),
            evenings = get(),
            players = get(),
        )
    }
    viewModel { SettingsViewModel(get()) }
    viewModel { BackupViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { PlayersViewModel(get(), get(), get()) }
}
