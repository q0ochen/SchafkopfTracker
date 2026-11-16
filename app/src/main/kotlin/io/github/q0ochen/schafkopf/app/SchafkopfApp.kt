package io.github.q0ochen.schafkopf.app

import android.app.Application
import io.github.q0ochen.schafkopf.app.backup.AutoBackupError
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.di.appModule
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        applicationScope.launch { ensureDefaultPlayers(get()) }
        startAutoBackup()
    }

    /**
     * Automatische Sicherung: Jede Änderung stößt eine Sicherung an. Ging der letzte Versuch schief (zum Beispiel
     * weil die Versuche im Hintergrund ausgeschöpft waren), gibt es beim Start der App einen neuen.
     */
    private fun startAutoBackup() {
        val scheduler: AutoBackupScheduler = get()
        scheduler.watch(applicationScope, AutoBackupScheduler.changesOf(get(), get(), get()))
        if (get<AutoBackupStore>().status.value.lastError == AutoBackupError.WRITE_FAILED) scheduler.request()
    }

    companion object {
        /**
         * Die feste Vierer-Runde. Die Kennungen sind fest vergeben, damit Sicherungen von verschiedenen Geräten
         * dieselben Spieler meinen. Die echten Namen trägt die Runde in der App ein (Bereich „Spieler“);
         * sie stehen bewusst nicht im Quelltext, weil das Repository öffentlich ist.
         */
        val DEFAULT_PLAYERS: List<Player> = List(4) { index ->
            Player(PlayerId("spieler-${index + 1}"), "Spieler ${index + 1}", colorIndex = index)
        }

        /** Legt die feste Runde an, solange es noch keinen einzigen Spieler gibt. */
        suspend fun ensureDefaultPlayers(players: PlayerRepository) {
            if (players.players.first().isEmpty()) DEFAULT_PLAYERS.forEach { players.save(it) }
        }
    }
}
