package io.github.q0ochen.schafkopf.app

import android.app.Application
import io.github.q0ochen.schafkopf.app.di.appModule
import io.github.q0ochen.schafkopf.database.SchafkopfDatabase
import io.github.q0ochen.schafkopf.database.openSchafkopfDatabase
import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * App-Klasse für Tests: verdrahtet wie die echte App, legt aber die feste Runde nicht an,
 * damit jeder Test genau die Spieler hat, die er selbst anlegt. Die Ortsvorschläge kommen aus [TestPlaceSearch]:
 * Kein Test fragt den echten Dienst im Internet.
 *
 * Zwei Vorkehrungen gegen Fehler, die von einem Test in den nächsten wandern:
 * - Die Datenbank eines Tests wird erst [CLOSE_DELAY_SECONDS] Sekunden nach dem Ende von Koin geschlossen. Sonst
 *   fragte ein ViewModel, das noch kurz weiterläuft (`WhileSubscribed`), eine schon geschlossene Datenbank ab; die
 *   Ausnahme landete im nächsten Test („uncaught exceptions before the test started“). Jeder Test hat ohnehin seine
 *   eigene Datenbankdatei.
 * - Läuft noch ein Koin aus einem gescheiterten Test, wird es zuerst beendet, statt dass alle weiteren Tests mit
 *   „A Koin Application has already been started“ scheitern.
 */
class TestSchafkopfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        stopKoin()
        startKoin {
            allowOverride(true)
            androidContext(this@TestSchafkopfApp)
            modules(
                appModule,
                module {
                    single<PlaceSearch> { TestPlaceSearch }
                    single { openSchafkopfDatabase(androidContext()) } onClose { database -> database?.let(::closeLater) }
                },
            )
        }
    }

    private companion object {
        const val CLOSE_DELAY_SECONDS = 15L

        val closer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Datenbank schliessen").apply { isDaemon = true }
        }

        fun closeLater(database: SchafkopfDatabase) {
            closer.schedule({ runCatching { database.close() } }, CLOSE_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }
}
