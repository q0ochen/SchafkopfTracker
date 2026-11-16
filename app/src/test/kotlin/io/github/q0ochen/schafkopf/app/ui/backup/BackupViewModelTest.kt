package io.github.q0ochen.schafkopf.app.ui.backup

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.backup.AutoBackupError
import io.github.q0ochen.schafkopf.app.backup.AutoBackupKind
import io.github.q0ochen.schafkopf.app.drive.FakeDriveAccount
import io.github.q0ochen.schafkopf.app.drive.FakeDriveServer
import io.github.q0ochen.schafkopf.app.backup.AutoBackupRunner
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.backup.DocumentAccess
import io.github.q0ochen.schafkopf.app.backup.FakeDocuments
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.app.ui.awaitFirst
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Sicherung speichern, einlesen und automatisch sichern, ohne Oberfläche geprüft (Dateien im Arbeitsspeicher). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.parse("2026-11-16T20:00:00Z"), ZoneOffset.UTC)
    private val placeholders = (1..4).map { Player(TestEvenings.seating[it - 1], "Spieler $it", colorIndex = it - 1) }

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    /** Wie die Oberfläche: die gezeigte Meldung als gezeigt melden. */
    private fun BackupViewModel.clearMessage() {
        state.value.message?.let(::messageShown)
    }

    /** Ein Gerät: Ablagen, Sicherungsdienst und ViewModel. */
    private inner class Device(
        players: List<Player>,
        evenings: List<Evening>,
        val documents: FakeDocuments = FakeDocuments(),
        val driveServer: FakeDriveServer = FakeDriveServer(),
        val account: FakeDriveAccount = FakeDriveAccount(),
    ) {
        val players = InMemoryPlayerRepository(players)
        val evenings = InMemoryEveningRepository(evenings)
        val settings = InMemorySettingsRepository(AppSettings())
        val backup = BackupService(this.players, this.evenings, settings, clock, "0.1.0", Dispatchers.Unconfined)
        val store = AutoBackupStore(context)
        val workManager: WorkManager = run {
            val config = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).setExecutor(SynchronousExecutor()).build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
            WorkManager.getInstance(context)
        }
        val driveBackup = driveServer.backup { kotlinx.coroutines.runBlocking { account.accessToken() } }
        val viewModel = BackupViewModel(
            backup, this.players, settings, documents, store,
            AutoBackupRunner(backup, store, documents, driveBackup, clock),
            AutoBackupScheduler({ workManager }, store),
            account,
            driveBackup,
        )
    }

    @Test
    fun `speichern, auf einem neuen Geraet einlesen und die Namen uebernehmen`() = runTest(dispatcher) {
        val shared = FakeDocuments()
        val old = Device(TestEvenings.players, listOf(TestEvenings.evening()), shared)
        backgroundScope.launch { old.viewModel.state.collect {} }
        old.viewModel.exportTo("mem://sicherung.json")
        assertEquals(R.string.backup_saved, old.viewModel.state.awaitFirst { it.message?.res == R.string.backup_saved }.message?.res)
        old.viewModel.clearMessage()

        // Neues Handy: nur die Platzhalter der festen Runde, noch kein Abend.
        val fresh = Device(placeholders, emptyList(), shared)
        backgroundScope.launch { fresh.viewModel.state.collect {} }
        fresh.viewModel.readFrom("mem://sicherung.json")
        val pending = fresh.viewModel.state.awaitFirst { it.pending != null }.pending!!
        assertEquals(1, pending.preview.newEveningCount)
        assertEquals(listOf("Anna", "Bert", "Cilli", "Done"), pending.preview.differingPlayers.map { it.name })

        fresh.viewModel.confirmImport(takeNames = true, takeRules = false)
        val done = fresh.viewModel.state.awaitFirst { it.message != null && !it.busy }
        assertEquals(R.string.backup_import_done, done.message?.res)
        assertNull(done.pending)
        assertEquals(listOf(TestEvenings.evening()), fresh.evenings.evenings.first())
        assertEquals(TestEvenings.players, fresh.players.players.first())
    }

    @Test
    fun `Namen behalten und Regeln uebernehmen`() = runTest(dispatcher) {
        val shared = FakeDocuments()
        val old = Device(TestEvenings.players, listOf(TestEvenings.evening()), shared)
        old.settings.update { it.copy(defaultRules = RuleSet.sauspiel()) }
        old.viewModel.exportTo("mem://s.json")
        val fresh = Device(placeholders, emptyList(), shared)
        backgroundScope.launch { fresh.viewModel.state.collect {} }
        fresh.viewModel.readFrom("mem://s.json")
        assertTrue(fresh.viewModel.state.awaitFirst { it.pending != null }.rulesDiffer)
        fresh.viewModel.confirmImport(takeNames = false, takeRules = true)
        fresh.viewModel.state.awaitFirst { it.message != null && !it.busy }
        assertEquals(placeholders, fresh.players.players.first())
        assertEquals(RuleSet.sauspiel(), fresh.settings.settings.first().defaultRules)
    }

    @Test
    fun `unlesbare, zu grosse und kaputte Dateien`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, emptyList())
        backgroundScope.launch { device.viewModel.state.collect {} }
        device.documents.files["mem://kaputt.json"] = "{kaputt"
        device.viewModel.readFrom("mem://kaputt.json")
        val broken = device.viewModel.state.awaitFirst { it.pending != null }.pending!!
        assertFalse(broken.preview.isReadable)
        device.viewModel.dismissImport()
        assertNull(device.viewModel.state.awaitFirst { it.pending == null }.pending)

        device.documents.failReads = DocumentAccess.TooLargeException()
        device.viewModel.readFrom("mem://riesig.json")
        assertEquals(R.string.backup_too_big, device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_too_big }.message?.res)
        device.viewModel.clearMessage()

        device.documents.failReads = IOException("weg")
        device.viewModel.readFrom("mem://weg.json")
        assertEquals(R.string.backup_read_failed, device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_read_failed }.message?.res)
        device.viewModel.clearMessage()

        device.documents.failWrites = IOException("voll")
        device.viewModel.exportTo("mem://neu.json")
        assertEquals(R.string.backup_save_failed, device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_save_failed }.message?.res)
    }

    @Test
    fun `automatische Sicherung einschalten, sofort sichern, umziehen und ausschalten`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, listOf(TestEvenings.evening()))
        backgroundScope.launch { device.viewModel.state.collect {} }

        device.viewModel.chooseAutoTarget("mem://drive/schafkopf-sicherung.json")
        val on = device.viewModel.state.awaitFirst { it.message != null && !it.busy }
        assertEquals(R.string.backup_auto_enabled, on.message?.res)
        assertEquals("schafkopf-sicherung.json", on.auto.targetName)
        assertTrue(device.documents.files.getValue("mem://drive/schafkopf-sicherung.json").contains("schafkopf-tracker-backup"))
        assertEquals(setOf("mem://drive/schafkopf-sicherung.json"), device.documents.kept)
        device.viewModel.clearMessage()

        // Umziehen gibt das Recht an der alten Datei ab.
        device.viewModel.chooseAutoTarget("mem://karte/neu.json")
        device.viewModel.state.awaitFirst { it.auto.target == "mem://karte/neu.json" && !it.busy }
        assertEquals(setOf("mem://karte/neu.json"), device.documents.kept)

        // Schreiben scheitert: steht im Status, der Hintergrund versucht es weiter.
        device.documents.failWrites = IOException("kein Netz")
        device.viewModel.backupNow()
        val failed = device.viewModel.state.awaitFirst { it.auto.lastError != null && !it.busy }
        assertEquals(AutoBackupError.WRITE_FAILED, failed.auto.lastError)
        assertEquals(1, device.workManager.getWorkInfosForUniqueWork(AutoBackupScheduler.WORK_NAME).get().size)

        // Einlesen aus der Datei der automatischen Sicherung: Der Abend ist schon da.
        device.viewModel.restoreFromAuto()
        val restored = device.viewModel.state.awaitFirst { it.pending != null }.pending!!
        assertEquals(1, restored.preview.existingEveningCount)
        assertFalse(restored.preview.hasNewData)
        device.viewModel.dismissImport()

        device.viewModel.disableAuto()
        val off = device.viewModel.state.awaitFirst { it.auto.target == null }
        assertEquals(R.string.backup_auto_disabled, off.message?.res)
        assertTrue(device.documents.kept.isEmpty())
    }

    @Test
    fun `zwei gleiche Meldungen nacheinander erscheinen beide, und eine neuere bleibt stehen`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, emptyList())
        backgroundScope.launch { device.viewModel.state.collect {} }
        device.viewModel.exportTo("mem://a.json")
        val first = device.viewModel.state.awaitFirst { it.message != null && !it.busy }.message!!
        device.viewModel.exportTo("mem://b.json")
        val second = device.viewModel.state.awaitFirst { it.message != first && !it.busy }.message!!
        assertEquals(first.res, second.res)
        assertTrue(first.id != second.id)
        // Die erste Meldung als gezeigt zu melden, löscht nicht die zweite.
        device.viewModel.messageShown(first)
        assertEquals(second, device.viewModel.state.value.message)
        device.viewModel.messageShown(second)
        assertNull(device.viewModel.state.awaitFirst { it.message == null }.message)
    }

    @Test
    fun `Google Drive - anmelden, automatisch sichern, auf neuem Geraet einlesen, ausschalten`() = runTest(dispatcher) {
        val drive = FakeDriveServer()
        val old = Device(TestEvenings.players, listOf(TestEvenings.evening()), driveServer = drive)
        backgroundScope.launch { old.viewModel.state.collect {} }
        assertTrue(old.viewModel.state.awaitFirst { it.driveConfigured }.driveConfigured)

        // Erst anmelden, dann einschalten: Die erste Sicherung landet sofort in Drive.
        old.viewModel.signInIntent(AfterSignIn.ENABLE_AUTO)
        old.viewModel.completeSignIn(null)
        val on = old.viewModel.state.awaitFirst { it.message?.res == R.string.backup_auto_enabled && !it.busy }
        assertEquals(AutoBackupKind.GOOGLE_DRIVE, on.auto.kind)
        assertEquals("runde@example.com", on.auto.targetName)
        assertTrue(drive.files.values.single().contains("schafkopf-tracker-backup"))

        // Neues Handy, dasselbe Google-Konto: aus Drive einlesen.
        val fresh = Device(placeholders, emptyList(), driveServer = drive, account = FakeDriveAccount(signedIn = true))
        backgroundScope.launch { fresh.viewModel.state.collect {} }
        fresh.viewModel.restoreFromDrive()
        val pending = fresh.viewModel.state.awaitFirst { it.pending != null }.pending!!
        assertEquals(1, pending.preview.newEveningCount)
        fresh.viewModel.dismissImport()

        old.viewModel.clearMessage()
        old.viewModel.disableAuto()
        old.viewModel.state.awaitFirst { !it.auto.enabled }
        assertTrue(old.account.signedOut)
    }

    @Test
    fun `Google Drive - gescheiterte Anmeldung, noch keine Sicherung, Fehler beim Lesen`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, emptyList())
        backgroundScope.launch { device.viewModel.state.collect {} }
        device.account.signInSucceeds = false
        device.viewModel.signInIntent(AfterSignIn.RESTORE)
        device.viewModel.completeSignIn(null)
        assertEquals(
            R.string.backup_drive_sign_in_failed,
            device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_drive_sign_in_failed }.message?.res,
        )
        device.viewModel.clearMessage()

        device.account.signInSucceeds = true
        device.viewModel.signInIntent(AfterSignIn.RESTORE)
        device.viewModel.completeSignIn(null)
        assertEquals(R.string.backup_drive_none, device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_drive_none }.message?.res)
        device.viewModel.clearMessage()

        device.driveServer.failWith = 503
        device.viewModel.restoreFromDrive()
        assertEquals(R.string.drive_error_server, device.viewModel.state.awaitFirst { it.message?.res == R.string.drive_error_server }.message?.res)
    }

    @Test
    fun `ohne Client-ID bietet die App Google Drive nicht an`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, emptyList(), account = FakeDriveAccount(configured = false))
        backgroundScope.launch { device.viewModel.state.collect {} }
        assertFalse(device.viewModel.state.awaitFirst { true }.driveConfigured)
    }

    @Test
    fun `eine Datei ohne dauerhaftes Schreibrecht wird nicht zum Ziel`() = runTest(dispatcher) {
        val device = Device(TestEvenings.players, emptyList())
        backgroundScope.launch { device.viewModel.state.collect {} }
        device.documents.refuseAccess = true
        device.viewModel.chooseAutoTarget("mem://einmalig.json")
        assertEquals(R.string.backup_auto_access_refused, device.viewModel.state.awaitFirst { it.message?.res == R.string.backup_auto_access_refused }.message?.res)
        assertNull(device.store.status.value.target)
    }
}
