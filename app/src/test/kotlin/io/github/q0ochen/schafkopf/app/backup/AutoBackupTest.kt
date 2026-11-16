package io.github.q0ochen.schafkopf.app.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.q0ochen.schafkopf.app.TestSchafkopfApp
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Automatische Sicherung: Ziel und Verlauf, ein Sicherungsversuch, der Auftrag an WorkManager und die Arbeit dahinter. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestSchafkopfApp::class)
class AutoBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-11-16T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @After
    fun stopDependencyInjection() = stopKoin()

    private fun backupService() = BackupService(
        InMemoryPlayerRepository(TestEvenings.players),
        InMemoryEveningRepository(listOf(TestEvenings.evening())),
        InMemorySettingsRepository(AppSettings()),
        clock,
        "0.1.0",
        Dispatchers.Unconfined,
    )

    private fun testWorkManager(): WorkManager {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        return WorkManager.getInstance(context)
    }

    @Test
    fun `Ziel und Verlauf bleiben gespeichert, ein neues Ziel beginnt von vorn`() {
        val store = AutoBackupStore(context)
        assertEquals(AutoBackupStatus(), store.status.value)
        store.setTarget("content://drive/1", "schafkopf-sicherung.json")
        store.recordFailure(AutoBackupError.WRITE_FAILED, now)
        assertEquals(AutoBackupError.WRITE_FAILED, AutoBackupStore(context).status.value.lastError)
        store.recordSuccess(now.plusSeconds(60))
        val reloaded = AutoBackupStore(context).status.value
        assertEquals("content://drive/1", reloaded.target)
        assertEquals("schafkopf-sicherung.json", reloaded.targetName)
        assertEquals(now.plusSeconds(60), reloaded.lastSuccess)
        assertNull(reloaded.lastError)
        store.setTarget("content://drive/2", null)
        assertEquals(AutoBackupStatus(target = "content://drive/2"), store.status.value)
        store.setTarget(null, "egal")
        assertEquals(AutoBackupStatus(), AutoBackupStore(context).status.value)
    }

    @Test
    fun `ein Versuch schreibt die Sicherung oder haelt fest, warum es nicht ging`() = runBlocking {
        val store = AutoBackupStore(context)
        val documents = FakeDocuments()
        val runner = AutoBackupRunner(backupService(), store, documents, clock)
        assertEquals(AutoBackupOutcome.NO_TARGET, runner.run())

        store.setTarget("mem://sicherung.json", "sicherung.json")
        assertEquals(AutoBackupOutcome.SAVED, runner.run())
        assertTrue(backupService().preview(documents.files.getValue("mem://sicherung.json")).isReadable)
        assertEquals(now, store.status.value.lastSuccess)

        documents.failWrites = IOException("kein Netz")
        assertEquals(AutoBackupOutcome.FAILED, runner.run())
        assertEquals(AutoBackupError.WRITE_FAILED, store.status.value.lastError)
        assertEquals(now, store.status.value.lastSuccess)

        documents.failWrites = SecurityException("Recht entzogen")
        assertEquals(AutoBackupOutcome.ACCESS_LOST, runner.run())
        assertEquals(AutoBackupError.ACCESS_LOST, store.status.value.lastError)

        // Gelöscht oder verschoben: Warten hilft nicht, die Datei muss neu gewählt werden.
        documents.failWrites = FileNotFoundException("gelöscht")
        assertEquals(AutoBackupOutcome.ACCESS_LOST, runner.run())

        documents.failWrites = null
        assertEquals(AutoBackupOutcome.SAVED, runner.run())
        assertNull(store.status.value.lastError)
    }

    @Test
    fun `Auftraege nur mit Ziel, und erst nach einer Aenderung`() = runBlocking {
        val workManager = testWorkManager()
        val store = AutoBackupStore(context)
        val scheduler = AutoBackupScheduler({ workManager }, store)
        fun jobs() = workManager.getWorkInfosForUniqueWork(AutoBackupScheduler.WORK_NAME).get()

        scheduler.request()
        assertTrue(jobs().isEmpty())

        store.setTarget("mem://sicherung.json", null)
        val changes = MutableStateFlow(1)
        val scope = TestScope(UnconfinedTestDispatcher())
        scheduler.watch(scope, changes)
        assertTrue("Der Stand beim Start ist keine Änderung", jobs().isEmpty())
        changes.value = 2
        assertEquals(1, jobs().size)
        assertEquals(WorkInfo.State.ENQUEUED, jobs().single().state)
        // Eine weitere Änderung ersetzt den wartenden Auftrag, statt einen zweiten anzuhängen.
        changes.value = 3
        assertEquals(1, jobs().count { it.state == WorkInfo.State.ENQUEUED })

        // Beim Start der App bleibt ein wartender Auftrag (zum Beispiel ein Wiederholungsversuch) stehen.
        val waiting = jobs().single { it.state == WorkInfo.State.ENQUEUED }.id
        scheduler.request(replace = false)
        assertEquals(listOf(waiting), jobs().filter { it.state == WorkInfo.State.ENQUEUED }.map { it.id })

        scheduler.cancel()
        assertTrue(jobs().all { it.state == WorkInfo.State.CANCELLED })
        // Ohne wartenden Auftrag kommt auch beim Start einer dazu.
        scheduler.request(replace = false)
        assertEquals(1, jobs().count { it.state == WorkInfo.State.ENQUEUED })
        scope.cancel()
    }

    @Test
    fun `die Arbeit im Hintergrund schreibt in die gewaehlte Datei und versucht es bei Fehlern wieder`() = runBlocking {
        context // startet die App und damit Koin
        val koin = GlobalContext.get()
        TestEvenings.players.forEach { koin.get<PlayerRepository>().save(it) }
        koin.get<EveningRepository>().save(TestEvenings.evening())
        val target = File(folder.root, "sicherung.json")
        koin.get<AutoBackupStore>().setTarget(Uri.fromFile(target).toString(), null)

        val worker = TestListenableWorkerBuilder<AutoBackupWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(target.readText().contains("schafkopf-tracker-backup"))

        // Die Datei ist weg (hier: an ihrer Stelle liegt ein Ordner): Warten hilft nicht, also kein neuer Versuch.
        koin.get<AutoBackupStore>().setTarget(Uri.fromFile(folder.newFolder("ordner")).toString(), null)
        assertEquals(ListenableWorker.Result.failure(), TestListenableWorkerBuilder<AutoBackupWorker>(context).build().doWork())
        assertEquals(AutoBackupError.ACCESS_LOST, koin.get<AutoBackupStore>().status.value.lastError)
    }

    @Test
    fun `ein voruebergehender Fehler fuehrt zu einem neuen Versuch`() = runBlocking {
        context // startet die App und damit Koin
        val failing = FakeDocuments().apply { failWrites = IOException("kein Netz") }
        GlobalContext.get().loadModules(listOf(module { single<DocumentAccess> { failing } }), allowOverride = true)
        GlobalContext.get().get<AutoBackupStore>().setTarget("mem://sicherung.json", null)
        assertEquals(ListenableWorker.Result.retry(), TestListenableWorkerBuilder<AutoBackupWorker>(context).build().doWork())
        assertEquals(AutoBackupError.WRITE_FAILED, GlobalContext.get().get<AutoBackupStore>().status.value.lastError)
    }
}
