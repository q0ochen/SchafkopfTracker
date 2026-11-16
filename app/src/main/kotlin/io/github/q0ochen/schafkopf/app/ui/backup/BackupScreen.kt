package io.github.q0ochen.schafkopf.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.backup.AutoBackupError
import io.github.q0ochen.schafkopf.app.backup.AutoBackupKind
import io.github.q0ochen.schafkopf.app.backup.AutoBackupStatus
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.common.formatDateTime
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

/** Dateitypen, unter denen Anbieter eine Sicherung (JSON) führen; andere Dateien gar nicht erst anbieten. */
private val BACKUP_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

/** Sicherung als Datei speichern und einlesen, dazu die automatische Sicherung (zum Beispiel in Google Drive). */
@Composable
fun BackupScreen(navigator: Navigator) {
    val viewModel: BackupViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportTo(it.toString()) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.readFrom(it.toString()) }
    }
    val chooseAuto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.chooseAutoTarget(it.toString()) }
    }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.completeSignIn(result.data)
    }
    // Mit Google verbinden, falls nötig, und dann [action] ausführen.
    fun withDrive(action: AfterSignIn) {
        if (state.drive.signedIn) {
            when (action) {
                AfterSignIn.ENABLE_AUTO -> viewModel.enableDrive()
                AfterSignIn.RESTORE -> viewModel.restoreFromDrive()
            }
        } else {
            signIn.launch(viewModel.signInIntent(action))
        }
    }

    // Erst zeigen, dann als gezeigt melden: Würde die Meldung vorher gelöscht, bräche das ihre Anzeige sofort ab.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(message.text ?: resources.getString(message.res))
        } finally {
            viewModel.messageShown(message)
        }
    }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.settings_backup), onBack = { navigator.back() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeading(stringResource(R.string.backup_file_heading))
            Hint(stringResource(R.string.backup_file_hint))
            Button(
                enabled = !state.busy,
                onClick = { save.launch("schafkopf-sicherung-${LocalDate.now()}.json") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.backup_save), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                enabled = !state.busy,
                onClick = { open.launch(BACKUP_TYPES) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.backup_load), style = MaterialTheme.typography.titleMedium) }
            val driveIsAuto = state.auto.enabled && state.auto.kind == AutoBackupKind.GOOGLE_DRIVE
            if (state.driveConfigured && !driveIsAuto) {
                OutlinedButton(
                    enabled = !state.busy,
                    onClick = { withDrive(AfterSignIn.RESTORE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.backup_drive_restore), style = MaterialTheme.typography.titleMedium) }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            SectionHeading(stringResource(R.string.backup_auto_heading))
            Hint(stringResource(R.string.backup_auto_hint))
            AutoStatus(state.auto)
            val fileName = stringResource(R.string.backup_auto_file_name)
            if (!state.auto.enabled) {
                if (state.driveConfigured) {
                    Button(
                        enabled = !state.busy,
                        onClick = { withDrive(AfterSignIn.ENABLE_AUTO) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.backup_auto_drive), style = MaterialTheme.typography.titleMedium) }
                } else {
                    Hint(stringResource(R.string.backup_auto_drive_missing))
                }
                OutlinedButton(
                    enabled = !state.busy,
                    onClick = { chooseAuto.launch(fileName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.backup_auto_file), style = MaterialTheme.typography.titleMedium) }
            } else {
                if (state.auto.lastError == AutoBackupError.SIGN_IN_REQUIRED) {
                    Button(
                        enabled = !state.busy,
                        onClick = { signIn.launch(viewModel.signInIntent(AfterSignIn.ENABLE_AUTO)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.backup_drive_reconnect), style = MaterialTheme.typography.titleMedium) }
                }
                Button(
                    enabled = !state.busy,
                    onClick = { viewModel.backupNow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.backup_auto_now), style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(
                    enabled = !state.busy,
                    onClick = { viewModel.restoreFromAuto() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.auto.kind == AutoBackupKind.GOOGLE_DRIVE) R.string.backup_drive_restore else R.string.backup_auto_restore,
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = !state.busy,
                        onClick = { chooseAuto.launch(fileName) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.backup_auto_change)) }
                    TextButton(
                        onClick = { viewModel.disableAuto() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.backup_auto_disable)) }
                }
            }
        }
    }

    state.pending?.let { pending ->
        ImportDialog(
            state = state,
            pending = pending,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
        )
    }
}

@Composable
private fun AutoStatus(status: AutoBackupStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (!status.enabled) {
            Text(stringResource(R.string.backup_auto_off), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(
            text = when {
                status.kind == AutoBackupKind.GOOGLE_DRIVE && status.targetName != null ->
                    stringResource(R.string.backup_auto_drive_target, status.targetName)
                status.kind == AutoBackupKind.GOOGLE_DRIVE -> stringResource(R.string.backup_auto_drive_target_unknown)
                else -> stringResource(R.string.backup_auto_target, status.targetName ?: stringResource(R.string.backup_auto_unnamed))
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = status.lastSuccess?.let { stringResource(R.string.backup_auto_last, formatDateTime(it)) }
                ?: stringResource(R.string.backup_auto_never),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val error = status.lastError
        val errorAt = status.lastErrorAt
        if (error != null && errorAt != null) {
            Text(
                text = stringResource(
                    when (error) {
                        AutoBackupError.ACCESS_LOST -> R.string.backup_auto_error_access
                        AutoBackupError.SIGN_IN_REQUIRED -> R.string.backup_auto_error_sign_in
                        AutoBackupError.WRITE_FAILED -> R.string.backup_auto_error_write
                    },
                    formatDateTime(errorAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** „Sicherung einlesen?“: was dazukommt, was bleibt, was ausgelassen wird; erst „Einlesen“ speichert etwas. */
@Composable
private fun ImportDialog(
    state: BackupUiState,
    pending: PendingImport,
    onConfirm: (takeNames: Boolean, takeRules: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = pending.preview
    val resources = LocalContext.current.resources
    val readable = preview.isReadable
    val canImport = readable && (preview.hasNewData || preview.differingPlayerCount > 0 || state.rulesDiffer)
    var takeNames by rememberSaveable { mutableStateOf(true) }
    var takeRules by rememberSaveable { mutableStateOf(false) }
    val currentNames = state.players.associate { it.id to it.name }
    val renames = preview.differingPlayers.map { player ->
        val before = currentNames[player.id] ?: "?"
        if (before == player.name) player.name else stringResource(R.string.backup_import_rename, before, player.name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (readable) R.string.backup_import_title else R.string.backup_import_unreadable_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                val exportedAt = preview.exportedAt
                if (readable && exportedAt != null) {
                    Text(
                        text = if (preview.appVersion.isNotEmpty()) {
                            stringResource(R.string.backup_import_created_version, formatDateTime(exportedAt), preview.appVersion)
                        } else {
                            stringResource(R.string.backup_import_created, formatDateTime(exportedAt))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                importSummary(resources, preview).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (readable && renames.isNotEmpty()) {
                    CheckRow(
                        text = stringResource(R.string.backup_import_take_names, renames.joinToString(", ")),
                        checked = takeNames,
                        onChange = { takeNames = it },
                    )
                }
                if (readable && state.rulesDiffer) {
                    CheckRow(
                        text = stringResource(R.string.backup_import_take_rules, preview.defaultRules?.name.orEmpty()),
                        checked = takeRules,
                        onChange = { takeRules = it },
                    )
                }
                if (readable && !canImport) {
                    Text(stringResource(R.string.backup_import_nothing_new), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (canImport) {
                Button(onClick = { onConfirm(takeNames, takeRules && state.rulesDiffer) }) {
                    Text(stringResource(R.string.backup_import_confirm))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        },
        dismissButton = {
            if (canImport) TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
