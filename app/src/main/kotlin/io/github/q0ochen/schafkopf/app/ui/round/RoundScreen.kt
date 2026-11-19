package io.github.q0ochen.schafkopf.app.ui.round

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.round.RoundMessage
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.app.ui.common.ChoiceTile
import io.github.q0ochen.schafkopf.app.ui.common.PlayerName
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.Sharing
import io.github.q0ochen.schafkopf.app.ui.common.SubScreenTopBar
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.navigation.Screen
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import org.koin.androidx.compose.koinViewModel

/** Was der Bildschirm auslösen kann. Als eigene Klasse, damit er sich ohne ViewModel zeichnen und prüfen lässt. */
class RoundActions(
    val signIn: () -> Unit = {},
    val create: (String) -> Unit = {},
    val redeem: (String) -> Unit = {},
    val joinInvited: () -> Unit = {},
    val discardInvite: () -> Unit = {},
    val use: (RoundSummary) -> Unit = {},
    val confirmReplace: () -> Unit = {},
    val cancelReplace: () -> Unit = {},
    val openBackup: () -> Unit = {},
    val claim: (PlayerId?, String) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val invite: () -> Unit = {},
    val askFarewell: (Farewell) -> Unit = {},
    val confirmFarewell: () -> Unit = {},
    val cancelFarewell: () -> Unit = {},
    val clearNotices: () -> Unit = {},
    val clearMessage: () -> Unit = {},
    val clearError: () -> Unit = {},
)

/** Gemeinsame Runde: anmelden, Runde anlegen oder beitreten, „Das bin ich“, Mitglieder, einladen, verlassen. */
@Composable
fun RoundScreen(navigator: Navigator) {
    val viewModel: RoundViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Die Anmeldung läuft im Browser; danach öffnet er die App wieder (siehe MainActivity).
    LaunchedEffect(ui.signInUrl) {
        val url = ui.signInUrl ?: return@LaunchedEffect
        viewModel.signInOpened(openInBrowser(context, url))
    }
    val shareTitle = stringResource(R.string.round_invite_share_title)
    val roundName = state.link?.roundName.orEmpty()
    val shareText = ui.shareLink?.let { stringResource(R.string.round_invite_message, roundName, it) }
    LaunchedEffect(shareText) {
        if (shareText == null) return@LaunchedEffect
        Sharing.shareText(context, shareTitle, subject = shareTitle, text = shareText)
        viewModel.shared()
    }

    Scaffold(
        topBar = { SubScreenTopBar(title = stringResource(R.string.round_title), onBack = { navigator.back() }) },
    ) { padding ->
        RoundContent(
            state = state,
            ui = ui,
            players = players,
            actions = remember(viewModel, navigator) {
                RoundActions(
                    signIn = viewModel::signIn,
                    create = viewModel::create,
                    redeem = viewModel::redeem,
                    joinInvited = viewModel::joinInvited,
                    discardInvite = viewModel::discardInvite,
                    use = viewModel::use,
                    confirmReplace = viewModel::confirmReplace,
                    cancelReplace = viewModel::cancelReplace,
                    openBackup = {
                        viewModel.cancelReplace()
                        navigator.open(Screen.Backup)
                    },
                    claim = viewModel::claim,
                    syncNow = viewModel::syncNow,
                    invite = viewModel::invite,
                    askFarewell = viewModel::askFarewell,
                    confirmFarewell = viewModel::confirmFarewell,
                    cancelFarewell = viewModel::cancelFarewell,
                    clearNotices = viewModel::clearNotices,
                    clearMessage = viewModel::clearMessage,
                    clearError = viewModel::clearError,
                )
            },
            modifier = Modifier.padding(padding),
        )
    }
}

/** Öffnet die Adresse als Tab des Browsers über der App. @return `false`, wenn es keinen Browser gibt. */
private fun openInBrowser(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Composable
fun RoundContent(state: RoundState, ui: RoundUi, players: List<Player>, actions: RoundActions, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.message?.let { MessageCard(text = messageText(it), onDismiss = actions.clearMessage) }
        ui.error?.let { MessageCard(text = errorText(it), onDismiss = actions.clearError, isError = true) }

        val link = state.link
        when {
            !state.configured -> {
                Text(stringResource(R.string.round_not_configured), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.round_not_configured_hint), style = MaterialTheme.typography.bodyLarge)
            }
            !state.loaded -> Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyLarge)
            link == null && state.account == null -> SignedOut(invited = state.pendingInvite != null, busy = ui.busy, actions = actions)
            link == null -> SignedIn(state = state, ui = ui, actions = actions)
            else -> Linked(state = state, link = link, ui = ui, players = players, actions = actions)
        }
        if (ui.busy) {
            Text(
                stringResource(R.string.round_working),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (ui.replace != null) {
        AlertDialog(
            onDismissRequest = actions.cancelReplace,
            title = { Text(stringResource(R.string.round_replace_title)) },
            text = {
                Text(
                    if (ui.eveningCount > 0) {
                        pluralStringResource(R.plurals.round_replace_text, ui.eveningCount, ui.eveningCount)
                    } else {
                        stringResource(R.string.round_replace_text_players)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = actions.confirmReplace) {
                    Text(stringResource(R.string.round_replace_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = actions.openBackup) { Text(stringResource(R.string.round_replace_backup)) }
                    TextButton(onClick = actions.cancelReplace) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    ui.farewell?.let { kind ->
        val leaving = kind == Farewell.LEAVE
        AlertDialog(
            onDismissRequest = actions.cancelFarewell,
            title = { Text(stringResource(if (leaving) R.string.round_leave_title else R.string.round_sign_out_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (leaving) R.string.round_leave_text else R.string.round_sign_out_text))
                    if (ui.farewellPending > 0) {
                        Text(
                            pluralStringResource(R.plurals.round_pending_warning, ui.farewellPending, ui.farewellPending),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = actions.confirmFarewell) {
                    Text(
                        stringResource(if (leaving) R.string.round_leave else R.string.round_sign_out),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = actions.cancelFarewell) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun messageText(message: RoundMessage): String = stringResource(
    when (message) {
        RoundMessage.SIGN_IN_FAILED -> R.string.round_sign_in_failed
        RoundMessage.SIGN_IN_CANCELLED -> R.string.round_sign_in_cancelled
        RoundMessage.OTHER_ACCOUNT -> R.string.round_other_account
        RoundMessage.DETACHED -> R.string.round_detached
    },
)

@Composable
private fun errorText(error: RoundUiError): String = when (error) {
    RoundUiError.NoCode -> stringResource(R.string.round_error_no_code)
    RoundUiError.NoBrowser -> stringResource(R.string.round_no_browser)
    is RoundUiError.Server -> roundErrorText(error.error, LocalContext.current.resources, error.roundName)
}

/** Eine Meldung, die stehen bleibt, bis man sie wegtippt; Screenreader lesen sie von selbst vor. */
@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) colors.errorContainer else colors.secondaryContainer,
            contentColor = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) { Text(text) }
}

// --- Nicht angemeldet ---

@Composable
private fun SignedOut(invited: Boolean, busy: Boolean, actions: RoundActions) {
    Text(stringResource(R.string.round_intro_heading), style = MaterialTheme.typography.titleLarge)
    if (invited) Text(stringResource(R.string.round_invited_sign_in), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.round_intro_text), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.round_intro_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BigButton(stringResource(R.string.round_sign_in), onClick = actions.signIn, enabled = !busy)
}

// --- Angemeldet, ohne Runde ---

@Composable
private fun SignedIn(state: RoundState, ui: RoundUi, actions: RoundActions) {
    val email = state.account?.email
    Text(
        if (email == null) stringResource(R.string.round_signed_in) else stringResource(R.string.round_signed_in_as, email),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.pendingInvite != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                val invite = ui.invite
                Text(
                    stringResource(R.string.round_invite_card, invite?.roundName ?: "…"),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (invite != null) {
                    Text(pluralStringResource(R.plurals.round_members_count, invite.memberCount, invite.memberCount))
                }
                BigButton(stringResource(R.string.round_join), onClick = actions.joinInvited, enabled = !ui.busy)
                TextButton(
                    onClick = actions.discardInvite,
                    enabled = !ui.busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.round_invite_discard)) }
            }
        }
    }

    val rounds = ui.rounds.orEmpty()
    if (rounds.isNotEmpty()) {
        SectionHeading(stringResource(R.string.round_my_rounds))
        for (round in rounds) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
                    Text(round.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.round_members_count, round.memberCount, round.memberCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QuietButton(stringResource(R.string.round_use), onClick = { actions.use(round) }, enabled = !ui.busy)
                }
            }
        }
    }

    SectionHeading(stringResource(R.string.round_create_heading))
    val defaultName = stringResource(R.string.round_create_default_name)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.round_create_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.round_create_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BigButton(stringResource(R.string.round_create), onClick = { actions.create(name) }, enabled = !ui.busy && name.isNotBlank())

    SectionHeading(stringResource(R.string.round_redeem_heading))
    var code by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(R.string.round_redeem_field)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    QuietButton(stringResource(R.string.round_join), onClick = { actions.redeem(code) }, enabled = !ui.busy && code.isNotBlank())

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    TextButton(
        onClick = { actions.askFarewell(Farewell.SIGN_OUT) },
        enabled = !ui.busy,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.round_sign_out)) }
}

// --- Verbunden ---

@Composable
private fun Linked(state: RoundState, link: RoundLink, ui: RoundUi, players: List<Player>, actions: RoundActions) {
    Text(link.roundName, style = MaterialTheme.typography.headlineSmall)
    StatusRow(state)

    if (state.needsSignIn) {
        BigButton(stringResource(R.string.round_sign_in), onClick = actions.signIn, enabled = !ui.busy)
    } else {
        QuietButton(stringResource(R.string.round_sync_now), onClick = actions.syncNow, enabled = !state.syncing)
        if (link.playerId == null) {
            ClaimCard(state = state, players = players, busy = ui.busy, onClaim = actions.claim)
        }
    }

    SectionHeading(stringResource(R.string.round_members))
    val names = players.associateBy { it.id }
    for (member in link.members) {
        val player = member.playerId?.let(names::get)
        val isMe = member.userId == link.userId
        when {
            player == null -> Text(
                stringResource(R.string.round_member_unnamed).let { if (isMe) stringResource(R.string.round_member_you, it) else it },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = 32.dp),
            )
            else -> PlayerName(
                name = if (isMe) stringResource(R.string.round_member_you, player.name) else player.name,
                colorIndex = player.colorIndex,
                modifier = Modifier.heightIn(min = 32.dp),
            )
        }
    }

    if (!state.needsSignIn) {
        BigButton(stringResource(R.string.round_invite), onClick = actions.invite, enabled = !ui.busy)
        Text(
            stringResource(R.string.round_invite_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.notices.isNotEmpty()) {
        SectionHeading(stringResource(R.string.round_notices))
        val resources = LocalContext.current.resources
        for (notice in state.notices) Text(noticeText(notice, resources), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = actions.clearNotices, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.round_notices_clear))
        }
    }

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.needsSignIn) {
            TextButton(
                onClick = { actions.askFarewell(Farewell.LEAVE) },
                enabled = !ui.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.round_leave), color = MaterialTheme.colorScheme.error) }
        }
        TextButton(
            onClick = { actions.askFarewell(Farewell.SIGN_OUT) },
            enabled = !ui.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.round_sign_out)) }
    }
}

/** Die Statuszeile mit Symbol; der Text sagt alles, das Symbol ist nur Beiwerk. */
@Composable
fun StatusRow(state: RoundState, modifier: Modifier = Modifier) {
    val status = roundStatus(state, LocalContext.current.resources)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            painter = painterResource(status.kind.icon),
            contentDescription = null,
            tint = if (status.kind == RoundStatusKind.PROBLEM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(status.text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** „Wer bist du?“: einen Spieler ohne Besitzer wählen oder neu dazukommen, dann den eigenen Namen festlegen. */
@Composable
private fun ClaimCard(state: RoundState, players: List<Player>, busy: Boolean, onClaim: (PlayerId?, String) -> Unit) {
    // Wählbar ist, wer noch niemandem gehört. Solange der Stand des Servers fehlt, gilt das für alle.
    val candidates = players.filter { !it.archived && state.players[it.id]?.owner == null }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var isNew by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val placeholder = Regex("Spieler \\d+")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.round_claim_heading), style = MaterialTheme.typography.titleLarge)
            for (pair in candidates.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (player in pair) {
                        ChoiceTile(
                            text = player.name,
                            selected = !isNew && chosen == player.id.value,
                            onClick = {
                                chosen = player.id.value
                                isNew = false
                                // Ein vorbelegter Name ist noch keiner: Dann bleibt das Feld leer.
                                name = if (placeholder.matches(player.name)) "" else player.name
                            },
                            minHeight = 48.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            ChoiceTile(
                text = stringResource(R.string.round_claim_new),
                selected = isNew,
                onClick = {
                    isNew = true
                    chosen = null
                },
                minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.round_claim_name)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.round_claim_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            BigButton(
                stringResource(R.string.round_claim),
                onClick = { confirm = true },
                enabled = !busy && name.isNotBlank() && (isNew || chosen != null),
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.round_claim_confirm_title, name.trim())) },
            text = { Text(stringResource(R.string.round_claim_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onClaim(if (isNew) null else chosen?.let(::PlayerId), name)
                    },
                ) { Text(stringResource(R.string.round_claim_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
