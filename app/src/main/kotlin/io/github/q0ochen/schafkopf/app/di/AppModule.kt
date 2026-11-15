package io.github.q0ochen.schafkopf.app.di

import io.github.q0ochen.schafkopf.app.ui.board.BoardViewModel
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
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryPlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemorySecretStore
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.time.Clock
import java.util.UUID

/**
 * Kompositionswurzel der App: Hier und nur hier steht, welche Umsetzung hinter welchem Port steckt.
 * Alle Klassen bekommen ihre Abhängigkeiten über den Konstruktor; Koin setzt sie zusammen.
 *
 * ViewModels mit Parametern bekommen Kennungen als Text; ein leerer Text bedeutet „keine“.
 */
val appModule = module {
    single<Clock> { Clock.systemDefaultZone() }
    single<IdGenerator> { IdGenerator { UUID.randomUUID().toString() } }

    single<PlayerRepository> { InMemoryPlayerRepository() }
    single<EveningRepository> { InMemoryEveningRepository() }
    single<SettingsRepository> { InMemorySettingsRepository() }
    single<SecretStore> { InMemorySecretStore() }

    single { EveningEditor(get(), get(), get()) }

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
    viewModel { SettingsViewModel(get()) }
    viewModel { PlayersViewModel(get(), get()) }
}
