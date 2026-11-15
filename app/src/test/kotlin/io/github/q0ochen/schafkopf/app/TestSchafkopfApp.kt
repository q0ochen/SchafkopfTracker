package io.github.q0ochen.schafkopf.app

import android.app.Application
import io.github.q0ochen.schafkopf.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * App-Klasse für Tests: verdrahtet wie die echte App, legt aber die feste Runde nicht an,
 * damit jeder Test genau die Spieler hat, die er selbst anlegt.
 */
class TestSchafkopfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TestSchafkopfApp)
            modules(appModule)
        }
    }
}
