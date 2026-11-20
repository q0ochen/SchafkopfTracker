package io.github.q0ochen.schafkopf.app

import android.app.Application
import android.util.Log
import io.github.q0ochen.schafkopf.app.storage.LegacyFileImport
import io.github.q0ochen.schafkopf.app.backup.AutoBackupError
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.di.appModule
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.round.RoundStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Startet die App, verdrahtet sie mit Koin (siehe [appModule]) und legt beim ersten Start die feste Runde an. */
class SchafkopfApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SchafkopfApp)
            modules(appModule)
        }
        // Die Platzhalter der festen Runde nur, wenn die alten Daten drin sind: Sonst belegten sie die Kennungen
        // spieler-1 bis spieler-4, und die echten Namen kämen beim nächsten Versuch nicht mehr an.
        if (importLegacyFiles()) applicationScope.launch { ensureDefaultPlayers(get(), get()) }
        startAutoBackup()
        get<RoundCoordinator>().start()
    }

    /**
     * Einmalig nach dem Update: die Daten der ersten Testversionen (JSON-Dateien) in die Datenbank übernehmen, bevor
     * irgendein Bildschirm sie liest. Das dauert nur einen Augenblick und geschieht nur, solange der alte Ordner
     * noch da ist. Geht es schief, bleibt der Ordner liegen und der nächste Start versucht es wieder.
     */
    private fun importLegacyFiles(): Boolean = try {
        runBlocking(Dispatchers.IO) {
            LegacyFileImport.runIfNeeded(filesDir.toPath().resolve(LegacyFileImport.FOLDER), get())
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Übernahme der alten Daten fehlgeschlagen; nächster Versuch beim nächsten Start", e)
        false
    }

    /**
     * Automatische Sicherung: Jede Änderung stößt eine Sicherung an. Ging der letzte Versuch schief (zum Beispiel
     * weil die Versuche im Hintergrund ausgeschöpft waren), gibt es beim Start der App einen neuen. Wartet noch ein
     * Wiederholungsversuch, bleibt er stehen: Android startet die App auch für ihn, und ein Ersetzen finge die
     * Zählung der Versuche immer wieder von vorn an.
     */
    private fun startAutoBackup() {
        val scheduler: AutoBackupScheduler = get()
        scheduler.watch(applicationScope, AutoBackupScheduler.changesOf(get(), get(), get()))
        if (get<AutoBackupStore>().status.value.lastError == AutoBackupError.WRITE_FAILED) scheduler.request(replace = false)
    }

    companion object {
        private const val TAG = "SchafkopfApp"

        /**
         * Die feste Vierer-Runde. Die Kennungen sind fest vergeben, damit Sicherungen von verschiedenen Geräten
         * dieselben Spieler meinen. Die echten Namen trägt die Runde in der App ein (Bereich „Spieler“);
         * sie stehen bewusst nicht im Quelltext, weil das Repository öffentlich ist.
         */
        val DEFAULT_PLAYERS: List<Player> = List(4) { index ->
            Player(PlayerId("spieler-${index + 1}"), "Spieler ${index + 1}", colorIndex = index)
        }

        /**
         * Legt die feste Runde an, solange es noch keinen einzigen Spieler gibt. Nicht, wenn das Gerät mit einer
         * gemeinsamen Runde verbunden ist: Deren Spieler kommen vom Server, und Platzhalter gingen sonst als neue
         * Spieler in die Runde hoch.
         */
        suspend fun ensureDefaultPlayers(players: PlayerRepository, round: RoundStore) {
            if (round.link.first() != null) return
            if (players.players.first().isEmpty()) DEFAULT_PLAYERS.forEach { players.save(it) }
        }
    }
}
