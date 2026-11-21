package io.github.q0ochen.schafkopf.app.di

import android.content.Context
import androidx.work.WorkManager
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.app.ai.TextGeneratorFactory
import io.github.q0ochen.schafkopf.app.backup.AutoBackupRunner
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.backup.ContentResolverDocuments
import io.github.q0ochen.schafkopf.app.backup.DocumentAccess
import io.github.q0ochen.schafkopf.app.drive.AppAuthDriveAccount
import io.github.q0ochen.schafkopf.app.drive.DriveAccount
import io.github.q0ochen.schafkopf.app.ui.backup.BackupViewModel
import io.github.q0ochen.schafkopf.app.ui.board.BoardViewModel
import io.github.q0ochen.schafkopf.app.ui.editevening.EditEveningViewModel
import io.github.q0ochen.schafkopf.app.security.AndroidKeystoreCipher
import io.github.q0ochen.schafkopf.app.security.EncryptedSecretStore
import io.github.q0ochen.schafkopf.app.security.EncryptedValues
import io.github.q0ochen.schafkopf.app.storage.JsonRulesCodec
import io.github.q0ochen.schafkopf.app.ui.ai.AiSettingsViewModel
import io.github.q0ochen.schafkopf.app.storage.JsonSettingsCodec
import io.github.q0ochen.schafkopf.app.storage.LegacyFileImport
import io.github.q0ochen.schafkopf.BuildConfig
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.SchafkopfApp
import io.github.q0ochen.schafkopf.app.round.EncryptedSessionStorage
import io.github.q0ochen.schafkopf.app.round.InviteMemory
import io.github.q0ochen.schafkopf.app.round.JsonRoundCodec
import io.github.q0ochen.schafkopf.app.round.PreferencesInviteMemory
import io.github.q0ochen.schafkopf.app.round.RoundBackgroundSync
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.app.round.RoundServer
import io.github.q0ochen.schafkopf.app.ui.round.RoundViewModel
import io.github.q0ochen.schafkopf.data.round.RoundAccount
import io.github.q0ochen.schafkopf.data.round.SupabaseConfig
import io.github.q0ochen.schafkopf.database.RoomRoundStore
import io.github.q0ochen.schafkopf.domain.round.RoundApi
import io.github.q0ochen.schafkopf.domain.round.RoundConnection
import io.github.q0ochen.schafkopf.domain.round.RoundStore
import io.github.q0ochen.schafkopf.domain.round.RoundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.data.drive.DriveBackup
import io.github.q0ochen.schafkopf.data.drive.GoogleDriveClient
import io.github.q0ochen.schafkopf.data.llm.defaultHttpClient
import io.github.q0ochen.schafkopf.database.RoomEveningRepository
import io.github.q0ochen.schafkopf.database.RoomPlayerRepository
import io.github.q0ochen.schafkopf.database.RoomSettingsRepository
import io.github.q0ochen.schafkopf.database.openSchafkopfDatabase
import io.github.q0ochen.schafkopf.app.ui.closing.ClosingViewModel
import io.github.q0ochen.schafkopf.app.ui.statistics.StatisticsViewModel
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
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.time.Clock
import java.util.UUID


/** Die Adresse der Web-App; Einladungslinks führen dorthin (docs/ARCHITEKTUR.md, Abschnitt 8.1). */
private const val WEB_APP_URL = "https://q0ochen.github.io/SchafkopfTracker/"

/** Version der App, wie sie im Kopf jeder Sicherung steht. */
private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

/**
 * Kompositionswurzel der App: Hier und nur hier steht, welche Umsetzung hinter welchem Port steckt.
 * Alle Klassen bekommen ihre Abhängigkeiten über den Konstruktor; Koin setzt sie zusammen.
 *
 * Gespeichert wird dauerhaft in der Room-Datenbank (Modul `database`, eine Datei im privaten Speicher der App): Jede
 * Eingabe steht sofort auf dem Gerät und übersteht, dass Android die App beendet; Änderungen melden sich über Flows
 * von selbst bei allen Bildschirmen. Die JSON-Dateien der ersten Testversionen übernimmt [LegacyFileImport] einmalig.
 *
 * ViewModels mit Parametern bekommen Kennungen als Text; ein leerer Text bedeutet „keine“.
 */
val appModule = module {
    single<Clock> { Clock.systemDefaultZone() }
    single<IdGenerator> { IdGenerator { UUID.randomUUID().toString() } }

    // Eine einzige Datenbank für die ganze App; beim Beenden von Koin (in Tests) wird sie geschlossen.
    single { openSchafkopfDatabase(androidContext()) } onClose { it?.close() }
    single<PlayerRepository> { RoomPlayerRepository(get()) }
    single<EveningRepository> { RoomEveningRepository(get(), JsonRulesCodec) }
    single<SettingsRepository> { RoomSettingsRepository(get(), JsonSettingsCodec) }
    // Geheimnisse (Schlüssel des Abend-Kommentators) verschlüsselt mit einem Schlüssel aus dem Android Keystore.
    single { EncryptedValues(androidContext(), AndroidKeystoreCipher()) }
    single<SecretStore> { EncryptedSecretStore(get()) }

    // Abend-Kommentator: ein HTTP-Client für die ganze App, die Anbindung je nach gewähltem Dienst.
    single { defaultHttpClient() }
    single { TextGeneratorFactory(get()) }
    single { Commentator(get(), get(), get()) }

    single { EveningEditor(get(), get(), get()) }

    // Sicherung: als Datei und automatisch in eine einmal gewählte Datei (zum Beispiel in Google Drive).
    single { BackupService(get<PlayerRepository>(), get<EveningRepository>(), get<SettingsRepository>(), get(), appVersion(androidContext()), Dispatchers.Default) }
    single<DocumentAccess> { ContentResolverDocuments(androidContext()) }
    single { AutoBackupStore(androidContext()) }
    // Google Drive direkt: Anmeldung im Browser (AppAuth), Aufrufe der Drive-Schnittstelle über OkHttp.
    single<DriveAccount> {
        AppAuthDriveAccount(
            context = androidContext(),
            values = get(),
            clientId = androidContext().getString(R.string.google_client_id).trim(),
            http = get(),
        )
    }
    single { DriveBackup(GoogleDriveClient(get(), { get<DriveAccount>().accessToken() })) }
    single { AutoBackupRunner(get(), get(), get(), get(), get()) }
    single { AutoBackupScheduler({ WorkManager.getInstance(androidContext()) }, get()) }

    // Gemeinsame Runde: Server (Supabase) über OkHttp, Gerätezustand in der Datenbank, Abgleich im Vordergrund und
    // über WorkManager. Ohne Adresse des Servers (BuildConfig leer) ist die Funktion abgeschaltet.
    single {
        RoundServer(
            config = SupabaseConfig.of(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
            client = get(),
            storage = EncryptedSessionStorage(get()),
        )
    }
    single<RoundApi> { get<RoundServer>().api }
    single<RoundAccount> { get<RoundServer>().session }
    single<RoundStore> { RoomRoundStore(get(), JsonRoundCodec) }
    single { RoundSync(get(), get(), get(), get(), get(), get()) }
    single { RoundConnection(get(), get(), get(), get(), get(), get()) }
    single<InviteMemory> { PreferencesInviteMemory(androidContext()) }
    single { RoundBackgroundSync { WorkManager.getInstance(androidContext()) } }
    single {
        val background: RoundBackgroundSync = get()
        RoundCoordinator(
            configured = get<RoundServer>().configured,
            account = get(),
            api = get(),
            store = get(),
            sync = get(),
            connection = get(),
            changes = AutoBackupScheduler.changesOf(get(), get(), get()),
            invites = get(),
            placeholders = SchafkopfApp.DEFAULT_PLAYERS,
            webAppUrl = WEB_APP_URL,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onBackground = background::request,
        )
    }

    viewModel { StartViewModel(get(), get(), get()) }
    viewModel { RoundViewModel(get(), get(), get(), autoBackupEnabled = { get<AutoBackupStore>().status.value.enabled }) }
    viewModel { NewEveningViewModel(get(), get(), get(), get(), get()) }
    viewModel { params -> BoardViewModel(EveningId(params.get(0)), get(), get(), get(), get(), get()) }
    viewModel { params ->
        EditEveningViewModel(
            eveningId = EveningId(params.get(0)),
            evenings = get(),
            players = get(),
            editor = get(),
            clock = get(),
            linked = get<RoundCoordinator>().state.map { it.link != null },
        )
    }
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
    viewModel { params -> ClosingViewModel(EveningId(params.get(0)), get(), get(), get()) }
    viewModel { AiSettingsViewModel(get(), get(), get()) }
    viewModel { params ->
        StatisticsViewModel(
            initial = params.get<String>(0).takeIf { it.isNotEmpty() }?.let(::EveningId),
            evenings = get(),
            players = get(),
            commentator = get(),
        )
    }
    viewModel { SettingsViewModel(get()) }
    viewModel { BackupViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { PlayersViewModel(get(), get(), get(), get()) }
}
