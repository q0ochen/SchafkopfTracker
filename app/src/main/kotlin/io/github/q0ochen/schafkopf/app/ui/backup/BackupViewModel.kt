package io.github.q0ochen.schafkopf.app.ui.backup

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.backup.AutoBackupOutcome
import io.github.q0ochen.schafkopf.app.backup.AutoBackupRunner
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStatus
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.backup.DocumentAccess
import io.github.q0ochen.schafkopf.data.backup.BackupService
import io.github.q0ochen.schafkopf.data.backup.ImportPreview
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Eine Meldung für die Leiste unten: ein Text aus den Ressourcen oder ein fertiger Satz. */
data class BackupMessage(@StringRes val res: Int = 0, val text: String? = null)

/** Eine gelesene Sicherung, die auf „Einlesen“ wartet. */
data class PendingImport(val json: String, val preview: ImportPreview)

/**
 * Zustand des Bildschirms „Sicherung und Export“.
 *
 * @property players Die vorhandenen Spieler, um Umbenennungen aus der Sicherung zu zeigen.
 * @property defaultRules Die aktuelle Vorlage des Regelwerks, um zu sehen, ob die Sicherung eine andere enthält.
 */
data class BackupUiState(
    val auto: AutoBackupStatus = AutoBackupStatus(),
    val pending: PendingImport? = null,
    val players: List<Player> = emptyList(),
    val defaultRules: RuleSet? = null,
    val busy: Boolean = false,
    val message: BackupMessage? = null,
) {
    /** Enthält die wartende Sicherung eine andere Vorlage des Regelwerks? */
    val rulesDiffer: Boolean
        get() = pending?.preview?.defaultRules?.let { it != defaultRules } ?: false
}

private data class LocalState(val pending: PendingImport? = null, val busy: Boolean = false, val message: BackupMessage? = null)

/**
 * Sicherung als Datei speichern und einlesen, dazu die automatische Sicherung in eine einmal gewählte Datei
 * (zum Beispiel in Google Drive, siehe [DocumentAccess]).
 */
class BackupViewModel(
    private val backup: BackupService,
    private val players: PlayerRepository,
    private val settings: SettingsRepository,
    private val documents: DocumentAccess,
    private val store: AutoBackupStore,
    private val runner: AutoBackupRunner,
    private val scheduler: AutoBackupScheduler,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<BackupUiState> =
        combine(local, store.status, players.players, settings.settings) { mine, auto, allPlayers, appSettings ->
            BackupUiState(
                auto = auto,
                pending = mine.pending,
                players = allPlayers,
                defaultRules = appSettings.defaultRules,
                busy = mine.busy,
                message = mine.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState(auto = store.status.value))

    /** Schreibt eine Sicherung in die eben gewählte Datei. */
    fun exportTo(uri: String) = work {
        try {
            documents.write(uri, backup.exportJson())
            say(R.string.backup_saved)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            say(R.string.backup_save_failed)
        }
    }

    /** Liest eine Sicherung und zeigt, was das Einlesen bewirken würde; gespeichert wird noch nichts. */
    fun readFrom(uri: String) = work {
        val text = try {
            documents.read(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DocumentAccess.TooLargeException) {
            say(R.string.backup_too_big)
            return@work
        } catch (_: Exception) {
            say(R.string.backup_read_failed)
            return@work
        }
        val preview = backup.preview(text)
        local.update { it.copy(pending = PendingImport(text, preview)) }
    }

    /**
     * Spielt die wartende Sicherung ein.
     *
     * @param takeNames Vorhandene Spieler so benennen wie in der Sicherung (zum Beispiel die Platzhalter auf einem
     *   neuen Handy).
     * @param takeRules Die Vorlage des Regelwerks aus der Sicherung übernehmen.
     */
    fun confirmImport(takeNames: Boolean, takeRules: Boolean) {
        val pending = local.value.pending ?: return
        local.update { it.copy(pending = null) }
        work {
            val result = backup.import(pending.json)
            val error = result.error
            if (error != null) {
                local.update { it.copy(message = BackupMessage(text = error.message)) }
                return@work
            }
            if (takeNames) pending.preview.differingPlayers.forEach { players.save(it) }
            val rules = pending.preview.defaultRules
            if (takeRules && rules != null) settings.update { it.copy(defaultRules = rules) }
            say(R.string.backup_import_done)
        }
    }

    fun dismissImport() = local.update { it.copy(pending = null) }

    /** Die eben gewählte Datei wird das Ziel der automatischen Sicherung; die erste Sicherung folgt sofort. */
    fun chooseAutoTarget(uri: String) {
        try {
            documents.keepAccess(uri)
        } catch (_: SecurityException) {
            say(R.string.backup_auto_access_refused)
            return
        }
        val previous = store.status.value.target
        if (previous != null && previous != uri) documents.releaseAccess(previous)
        store.setTarget(uri, documents.displayName(uri))
        backupNow(enabled = true)
    }

    /** Sichert sofort in die gewählte Datei und meldet, wie es lief. */
    fun backupNow(enabled: Boolean = false) = work {
        when (runner.run()) {
            AutoBackupOutcome.SAVED -> say(if (enabled) R.string.backup_auto_enabled else R.string.backup_saved)
            AutoBackupOutcome.ACCESS_LOST -> Unit // steht im Status, mit der Bitte, neu zu wählen
            AutoBackupOutcome.FAILED -> scheduler.request() // steht im Status; der Hintergrund versucht es weiter
            AutoBackupOutcome.NO_TARGET -> Unit
        }
    }

    fun disableAuto() {
        store.status.value.target?.let(documents::releaseAccess)
        store.setTarget(null, null)
        scheduler.cancel()
        say(R.string.backup_auto_disabled)
    }

    /** Liest die Sicherung aus der Datei der automatischen Sicherung, zum Beispiel auf einem neuen Handy. */
    fun restoreFromAuto() {
        store.status.value.target?.let(::readFrom)
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun say(@StringRes res: Int) = local.update { it.copy(message = BackupMessage(res = res)) }

    /** Eine Aufgabe nach der anderen; solange sie läuft, sind die Knöpfe gesperrt. */
    private fun work(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }
}

/** Was das Einlesen bewirken würde, in ganzen Sätzen (wie in der Web-App). */
fun importSummary(resources: Resources, preview: ImportPreview): List<String> {
    preview.error?.let { return listOf(it.message) }
    val lines = ArrayList<String>()
    val newEvenings = preview.newEveningCount
    lines += if (newEvenings > 0) {
        resources.getQuantityString(R.plurals.backup_import_new_evenings, newEvenings, newEvenings)
    } else {
        resources.getString(R.string.backup_import_no_new_evenings)
    }
    if (preview.existingEveningCount > 0) {
        lines += resources.getQuantityString(
            R.plurals.backup_import_existing_evenings,
            preview.existingEveningCount,
            preview.existingEveningCount,
        )
    }
    if (preview.invalidEveningCount > 0) {
        lines += resources.getQuantityString(
            R.plurals.backup_import_invalid_evenings,
            preview.invalidEveningCount,
            preview.invalidEveningCount,
        )
        preview.invalidEvenings.forEach { lines += resources.getString(R.string.backup_import_invalid_line, it.reason) }
    }
    if (preview.newPlayerCount > 0) {
        lines += resources.getQuantityString(
            R.plurals.backup_import_new_players,
            preview.newPlayerCount,
            preview.newPlayerCount,
            preview.newPlayers.joinToString(", ") { it.name },
        )
    }
    if (preview.sameNamePlayerCount > 0) {
        lines += resources.getQuantityString(
            R.plurals.backup_import_same_name,
            preview.sameNamePlayerCount,
            preview.sameNamePlayers.joinToString(", ") { it.name },
        )
    }
    return lines
}
