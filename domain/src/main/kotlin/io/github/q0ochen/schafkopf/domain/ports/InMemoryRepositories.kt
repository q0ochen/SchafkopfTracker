package io.github.q0ochen.schafkopf.domain.ports

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * Umsetzungen der Ports, die alles nur im Speicher halten. Sie dienen Tests und Vorschauen der Oberfläche
 * und sind überall austauschbar, wo die Datei-Umsetzungen aus dem Modul `data` erwartet werden.
 */

class InMemoryPlayerRepository(initial: List<Player> = emptyList()) : PlayerRepository {
    private val state = MutableStateFlow(initial)
    override val players: Flow<List<Player>> = state

    override suspend fun save(player: Player) {
        state.update { current ->
            if (current.any { it.id == player.id }) current.map { if (it.id == player.id) player else it } else current + player
        }
    }
}

class InMemoryEveningRepository(initial: List<Evening> = emptyList()) : EveningRepository {
    private val state = MutableStateFlow(initial)
    override val evenings: Flow<List<Evening>> = state.map { list -> list.sortedByDescending { it.startedAt } }

    override fun evening(id: EveningId): Flow<Evening?> = state.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun save(evening: Evening) {
        state.update { current ->
            if (current.any { it.id == evening.id }) current.map { if (it.id == evening.id) evening else it } else current + evening
        }
    }

    override suspend fun delete(id: EveningId) {
        state.update { current -> current.filterNot { it.id == id } }
    }
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.update(transform)
    }
}

class InMemorySecretStore(private var key: String? = null) : SecretStore {
    override suspend fun apiKey(): String? = key

    override suspend fun setApiKey(key: String?) {
        this.key = key?.takeIf { it.isNotBlank() }
    }
}
