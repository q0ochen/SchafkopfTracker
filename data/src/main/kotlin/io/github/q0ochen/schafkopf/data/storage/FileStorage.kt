package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Dauerhafte Ablage aller Daten als JSON-Dateien in einem Ordner: `players.json`, `settings.json` und je Abend
 * eine Datei unter `evenings/`.
 *
 * - Beim ersten Zugriff wird alles einmal gelesen (auf dem IO-Dispatcher); danach kommen alle Antworten aus
 *   dem Speicher. Fehlende Ordner werden angelegt.
 * - Geschrieben wird immer nacheinander. Eine Datei wird nie halb überschrieben: Erst entsteht daneben eine
 *   vollständige neue Datei, dann wird sie in einem Schritt an die Stelle der alten gesetzt. `save`, `delete`
 *   und `update` kehren erst zurück, wenn das geschehen ist, und lassen sich dabei nicht abbrechen.
 * - Eine kaputte Datei bekommt die Endung `.defekt` und wird übersprungen; die App läuft weiter.
 * - Lässt sich etwas nicht schreiben (zum Beispiel weil der Speicher voll ist), werfen `save`, `delete` und
 *   `update` eine [java.io.IOException]. Der Stand im Speicher bleibt dann, wie er war.
 * - Für einen Ordner darf es in der laufenden App nur ein einziges `FileStorage` geben: Jedes hält seinen eigenen
 *   Stand im Speicher, zwei würden die Änderungen des jeweils anderen nicht bemerken.
 *
 * @param directory Ordner für alle Dateien der App.
 * @param ioDispatcher Darauf laufen alle Zugriffe auf den Datenträger (und die Rechenarbeit der Sicherung).
 */
class FileStorage(directory: Path, internal val ioDispatcher: CoroutineDispatcher) {

    private val files = StorageFiles(directory)

    /** Sorgt dafür, dass immer nur einer lädt oder schreibt. */
    private val lock = Mutex()

    /** Der Stand im Speicher; `null`, solange noch nichts geladen wurde. */
    private val state = MutableStateFlow<Snapshot?>(null)

    /** Der geladene Stand und jede spätere Änderung. Wer als Erster zuhört, löst das Laden aus. */
    private val loaded: Flow<Snapshot> = flow {
        current()
        emitAll(state.filterNotNull())
    }

    /** Die Spieler der Runde (`players.json`), in der Reihenfolge, in der sie angelegt wurden. */
    val players: PlayerRepository = FilePlayerRepository()

    /** Die Abende (`evenings/<Kennung>.json`), der neueste zuerst. */
    val evenings: EveningRepository = FileEveningRepository()

    /** Die Einstellungen der App (`settings.json`). Fehlt die Datei, gelten die Standardeinstellungen. */
    val settings: SettingsRepository = FileSettingsRepository()

    private inner class FilePlayerRepository : PlayerRepository {
        override val players: Flow<List<Player>> = loaded.map { it.players }.distinctUntilChanged()

        override suspend fun save(player: Player) = change { before ->
            val updated = if (before.players.any { it.id == player.id }) {
                before.players.map { if (it.id == player.id) player else it }
            } else {
                before.players + player
            }
            onDisk { files.writePlayers(updated) }
            before.copy(players = updated)
        }

        override suspend fun delete(id: PlayerId) = change { before ->
            if (before.players.none { it.id == id }) return@change before
            val updated = before.players.filterNot { it.id == id }
            onDisk { files.writePlayers(updated) }
            before.copy(players = updated)
        }
    }

    private inner class FileEveningRepository : EveningRepository {
        override val evenings: Flow<List<Evening>> = loaded.map { it.evenings }.distinctUntilChanged()

        override fun evening(id: EveningId): Flow<Evening?> =
            loaded.map { snapshot -> snapshot.evenings.firstOrNull { it.id == id } }.distinctUntilChanged()

        override suspend fun save(evening: Evening) = change { before ->
            val file = onDisk { files.writeEvening(evening, before.eveningFiles) }
            before.copy(
                evenings = (before.evenings.filterNot { it.id == evening.id } + evening).sortedWith(NEWEST_FIRST),
                eveningFiles = before.eveningFiles + (evening.id to file),
            )
        }

        override suspend fun delete(id: EveningId) = change { before ->
            val file = before.eveningFiles[id] ?: return@change before
            onDisk { files.delete(file) }
            before.copy(
                evenings = before.evenings.filterNot { it.id == id },
                eveningFiles = before.eveningFiles - id,
            )
        }
    }

    private inner class FileSettingsRepository : SettingsRepository {
        override val settings: Flow<AppSettings> = loaded.map { it.settings }.distinctUntilChanged()

        override suspend fun update(transform: (AppSettings) -> AppSettings) = change { before ->
            val updated = transform(before.settings)
            onDisk { files.writeSettings(updated) }
            before.copy(settings = updated)
        }
    }

    /**
     * Der Stand im Speicher; beim ersten Aufruf wird er von den Dateien geladen. Das Laden läuft auch dann zu
     * Ende, wenn der Aufrufer inzwischen aufgegeben hat, damit nie zweimal geladen werden muss.
     */
    private suspend fun current(): Snapshot =
        state.value ?: withContext(NonCancellable) { lock.withLock { state.value ?: load() } }

    /** Liest alle Dateien. Nur aufrufen, während [lock] gehalten wird und ein Abbruch ausgeschlossen ist. */
    private suspend fun load(): Snapshot = onDisk { files.load() }.also { state.value = it }

    /**
     * Führt eine Änderung aus: erst auf dem Datenträger, dann im Speicher. Scheitert das Schreiben, bleibt der
     * Speicher unverändert. Einmal begonnen, lässt sich die Änderung nicht mehr abbrechen, damit Datei und
     * Speicher nie auseinanderlaufen und keine Eingabe verloren geht, nur weil ein Bildschirm geschlossen wird.
     */
    private suspend fun change(action: suspend (Snapshot) -> Snapshot) {
        withContext(NonCancellable) {
            lock.withLock {
                val before = state.value ?: load()
                state.value = action(before)
            }
        }
    }

    private suspend fun <T> onDisk(block: () -> T): T = withContext(ioDispatcher) { block() }
}
