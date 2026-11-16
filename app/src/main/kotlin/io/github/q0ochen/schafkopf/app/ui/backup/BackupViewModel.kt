package io.github.q0ochen.schafkopf.app.ui.backup

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.R
import android.content.ActivityNotFoundException
import android.content.Intent
import io.github.q0ochen.schafkopf.app.backup.AutoBackupKind
import io.github.q0ochen.schafkopf.app.backup.AutoBackupOutcome
import io.github.q0ochen.schafkopf.app.backup.AutoBackupRunner
import io.github.q0ochen.schafkopf.app.backup.AutoBackupScheduler
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStatus
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStore
import io.github.q0ochen.schafkopf.app.backup.DocumentAccess
import io.github.q0ochen.schafkopf.app.drive.DriveAccount
import io.github.q0ochen.schafkopf.app.drive.DriveSignIn
import io.github.q0ochen.schafkopf.app.drive.SignInResult
import io.github.q0ochen.schafkopf.app.drive.messageRes
import io.github.q0ochen.schafkopf.data.drive.DriveBackup
import io.github.q0ochen.schafkopf.data.drive.DriveError
import io.github.q0ochen.schafkopf.data.drive.DriveResult
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Eine Meldung für die Leiste unten: ein Text aus den Ressourcen oder ein fertiger Satz.
 *
 * @property id Unterscheidet zwei gleichlautende Meldungen nacheinander, damit auch die zweite erscheint.
 */
data class BackupMessage(@StringRes val res: Int = 0, val text: String? = null, val id: Long = 0)

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
    val driveConfigured: Boolean = false,
    val drive: DriveSignIn = DriveSignIn(),
) {
    /** Enthält die wartende Sicherung eine andere Vorlage des Regelwerks? */
    val rulesDiffer: Boolean
        get() = pending?.preview?.defaultRules?.let { it != defaultRules } ?: false
}

private data class LocalState(val pending: PendingImport? = null, val busy: Boolean = false, val message: BackupMessage? = null)

/** Was nach der Anmeldung bei Google geschehen soll. */
enum class AfterSignIn { ENABLE_AUTO, RESTORE }

/**
 * Sicherung als Datei speichern und einlesen, dazu die automatische Sicherung: direkt in Google Drive (mit Anmeldung,
 * siehe [DriveAccount]) oder in eine einmal gewählte Datei (siehe [DocumentAccess]).
 */
class BackupViewModel(
    private val backup: BackupService,
    private val players: PlayerRepository,
    private val settings: SettingsRepository,
    private val documents: DocumentAccess,
    private val store: AutoBackupStore,
    private val runner: AutoBackupRunner,
    private val scheduler: AutoBackupScheduler,
    private val driveAccount: DriveAccount,
    private val driveBackup: DriveBackup,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<BackupUiState> =
        combine(local, store.status, players.players, settings.settings, driveAccount.status) { mine, auto, allPlayers, appSettings, drive ->
            BackupUiState(
                auto = auto,
                pending = mine.pending,
                players = allPlayers,
                defaultRules = appSettings.defaultRules,
                busy = mine.busy,
                message = mine.message,
                driveConfigured = driveAccount.configured,
                drive = drive,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState(auto = store.status.value))

    init {
        viewModelScope.launch { driveAccount.load() }
    }

    /** Die Anmeldung bei Google im Browser; `null` (mit Meldung), wenn es auf dem Gerät keinen Browser gibt. */
    fun signInIntent(): Intent? = try {
        driveAccount.signInIntent()
    } catch (_: ActivityNotFoundException) {
        say(R.string.backup_drive_no_browser)
        null
    }

    /**
     * Ergebnis der Anmeldung; bei Erfolg geht es mit [action] weiter. Die Oberfläche merkt sich den Schritt selbst,
     * damit er auch dann nicht verloren geht, wenn Android die App während der Anmeldung im Browser beendet.
     */
    fun completeSignIn(result: Intent?, action: AfterSignIn?) {
        viewModelScope.launch {
            when (driveAccount.completeSignIn(result)) {
                SignInResult.FAILED -> say(R.string.backup_drive_sign_in_failed)
                SignInResult.DRIVE_NOT_ALLOWED -> say(R.string.backup_drive_not_allowed)
                SignInResult.OK -> when (action) {
                    AfterSignIn.ENABLE_AUTO -> enableDrive()
                    AfterSignIn.RESTORE -> restoreFromDrive()
                    null -> say(R.string.backup_drive_connected)
                }
            }
        }
    }

    /** Trennt die Verbindung zu Google (auch bei Google widerrufen); eine automatische Sicherung nach Drive endet. */
    fun disconnectDrive() {
        val status = store.status.value
        if (status.enabled && status.kind == AutoBackupKind.GOOGLE_DRIVE) {
            store.setTarget(null, null)
            scheduler.cancel()
        }
        viewModelScope.launch { driveAccount.signOut() }
        say(R.string.backup_drive_disconnected)
    }

    /** Automatisch nach Google Drive sichern (Anmeldung vorausgesetzt); die erste Sicherung folgt sofort. */
    fun enableDrive() {
        val previous = store.status.value
        if (previous.kind == AutoBackupKind.DOCUMENT) previous.target?.let(documents::releaseAccess)
        store.setTarget(AutoBackupStatus.DRIVE_TARGET, driveAccount.status.value.email, AutoBackupKind.GOOGLE_DRIVE)
        backupNow(enabled = true)
    }

    /** Liest die neueste Sicherung aus Google Drive (Anmeldung vorausgesetzt) und zeigt, was das Einlesen bewirkt. */
    fun restoreFromDrive() = work {
        val file = when (val latest = driveBackup.latest()) {
            is DriveResult.Failure -> {
                forgetIfUnusable(latest.error)
                say(latest.error.messageRes())
                return@work
            }
            is DriveResult.Success -> latest.value ?: run {
                say(R.string.backup_drive_none)
                return@work
            }
        }
        when (val text = driveBackup.read(file)) {
            is DriveResult.Failure -> say(text.error.messageRes())
            is DriveResult.Success -> {
                val preview = backup.preview(text.value)
                local.update { it.copy(pending = PendingImport(text.value, preview)) }
            }
        }
    }

    /**
     * Ohne gültige Anmeldung oder ohne erlaubten Zugriff hilft nur eine neue Anmeldung: die alte verwerfen, damit der
     * nächste Versuch gleich den Browser öffnet.
     */
    private suspend fun forgetIfUnusable(error: DriveError) {
        if (error == DriveError.SIGN_IN_REQUIRED || error == DriveError.ACCESS_DENIED) driveAccount.signOut()
    }

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
                post(BackupMessage(text = error.message))
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
        val previous = store.status.value
        if (previous.kind == AutoBackupKind.GOOGLE_DRIVE && previous.enabled) {
            // Vorher ging die Sicherung nach Drive: Die Anmeldung dort wird nicht mehr gebraucht.
            viewModelScope.launch { driveAccount.signOut() }
        } else if (previous.target != null && previous.target != uri) {
            documents.releaseAccess(previous.target)
        }
        store.setTarget(uri, documents.displayName(uri))
        backupNow(enabled = true)
    }

    /** Sichert sofort in die gewählte Datei oder nach Drive und meldet, wie es lief. */
    fun backupNow(enabled: Boolean = false) = work {
        when (runner.run()) {
            AutoBackupOutcome.SAVED -> say(if (enabled) R.string.backup_auto_enabled else R.string.backup_saved)
            // Steht im Status, mit der Bitte, neu zu wählen oder neu zu verbinden.
            AutoBackupOutcome.ACCESS_LOST, AutoBackupOutcome.SIGN_IN_REQUIRED -> Unit
            AutoBackupOutcome.FAILED -> scheduler.request() // steht im Status; der Hintergrund versucht es weiter
            AutoBackupOutcome.NO_TARGET -> Unit
        }
    }

    /** Schaltet die automatische Sicherung aus; bei Google Drive meldet die App sich dabei auch ab. */
    fun disableAuto() {
        val status = store.status.value
        if (status.kind == AutoBackupKind.GOOGLE_DRIVE) {
            viewModelScope.launch { driveAccount.signOut() }
        } else {
            status.target?.let(documents::releaseAccess)
        }
        store.setTarget(null, null)
        scheduler.cancel()
        say(R.string.backup_auto_disabled)
    }

    /** Liest die Sicherung vom Ziel der automatischen Sicherung, zum Beispiel auf einem neuen Handy. */
    fun restoreFromAuto() {
        val status = store.status.value
        if (status.kind == AutoBackupKind.GOOGLE_DRIVE) restoreFromDrive() else status.target?.let(::readFrom)
    }

    /** Die Meldung ist gezeigt worden. Eine inzwischen neuere Meldung bleibt stehen. */
    fun messageShown(shown: BackupMessage) = local.update { if (it.message == shown) it.copy(message = null) else it }

    private val messageIds = AtomicLong()

    private fun post(message: BackupMessage) =
        local.update { it.copy(message = message.copy(id = messageIds.incrementAndGet())) }

    private fun say(@StringRes res: Int) = post(BackupMessage(res = res))

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
