package io.github.q0ochen.schafkopf.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.app.ai.Commentary
import io.github.q0ochen.schafkopf.app.ai.CommentaryOutcome
import io.github.q0ochen.schafkopf.app.ai.CommentarySource
import io.github.q0ochen.schafkopf.app.ai.Commentator
import io.github.q0ochen.schafkopf.app.ai.messageRes
import io.github.q0ochen.schafkopf.app.ui.common.SectionHeading
import io.github.q0ochen.schafkopf.app.ui.common.Sharing
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Wie weit der Abend-Kommentator ist. */
sealed interface CommentaryUi {
    data object Idle : CommentaryUi
    data object Writing : CommentaryUi

    /** Vor dem ersten Senden an diesen Dienst: Zustimmung erfragen. */
    data class NeedsConsent(val host: String, val realNames: Boolean) : CommentaryUi
    data class Ready(val commentary: Commentary, val error: LlmError? = null) : CommentaryUi
}

/**
 * Der Ablauf des Kommentators auf einem Bildschirm (Abschluss oder Statistik): schreiben lassen, vor dem ersten
 * Senden nach Zustimmung fragen, das Ergebnis zeigen. Ein zweites Tippen, solange geschrieben wird, zählt nicht.
 *
 * @param scope Darin wird geschrieben, üblicherweise der `viewModelScope`.
 */
class CommentaryFlow(private val scope: CoroutineScope, private val commentator: Commentator) {

    private val mutable = MutableStateFlow<CommentaryUi>(CommentaryUi.Idle)
    val state: StateFlow<CommentaryUi> = mutable.asStateFlow()

    /** Was zuletzt geschrieben werden sollte; nach der Zustimmung geht es damit weiter. */
    private var pending: (suspend () -> CommentaryOutcome?)? = null
    private var job: Job? = null

    /**
     * Lässt [work] den Kommentar schreiben. Liefert [work] `null` (es gibt nichts zu kommentieren), bleibt es beim
     * Ausgangszustand.
     */
    fun write(work: suspend () -> CommentaryOutcome?) {
        if (mutable.value == CommentaryUi.Writing) return
        pending = work
        mutable.value = CommentaryUi.Writing
        job = scope.launch { mutable.value = work().toUi() }
    }

    /** Der Nutzer stimmt dem Senden an diesen Dienst zu; dann geht es gleich los. Ein zweites Tippen zählt nicht. */
    fun consent(host: String) {
        val work = pending
        if (mutable.value !is CommentaryUi.NeedsConsent || work == null) return
        mutable.value = CommentaryUi.Writing
        job = scope.launch {
            commentator.consent(host)
            mutable.value = work().toUi()
        }
    }

    fun decline() {
        mutable.value = CommentaryUi.Idle
    }

    /** Verwirft den Kommentar, etwa wenn ein anderer Abend gewählt wird; ein laufendes Schreiben wird abgebrochen. */
    fun reset() {
        job?.cancel()
        job = null
        pending = null
        mutable.value = CommentaryUi.Idle
    }

    private fun CommentaryOutcome?.toUi(): CommentaryUi = when (this) {
        null -> CommentaryUi.Idle
        is CommentaryOutcome.Done -> CommentaryUi.Ready(commentary)
        is CommentaryOutcome.NeedsConsent -> CommentaryUi.NeedsConsent(host, realNames)
        is CommentaryOutcome.Failed -> CommentaryUi.Ready(fallback, error)
    }
}

/**
 * Der Kommentator: Kommentar schreiben lassen, lesen und teilen.
 *
 * @param shareSubject Betreff beim Teilen, zum Beispiel „Schafkopf-Abend: der Kommentar“.
 */
@Composable
fun CommentarySection(
    commentary: CommentaryUi,
    onWrite: () -> Unit,
    onMessage: (String) -> Unit,
    shareSubject: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chooser = stringResource(R.string.share_chooser)
    val noApp = stringResource(R.string.share_no_app)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.padding(top = 16.dp)) {
        SectionHeading(stringResource(R.string.commentary_heading))
        val ready = commentary as? CommentaryUi.Ready
        if (ready != null) {
            ready.error?.let { error ->
                Text(
                    stringResource(R.string.commentary_failed, stringResource(error.messageRes())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = ready.commentary.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Text(
                text = if (ready.commentary.source == CommentarySource.AI) {
                    stringResource(R.string.commentary_ai_note, ready.commentary.model.ifBlank { "?" })
                } else {
                    stringResource(R.string.commentary_template_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { if (!Sharing.shareText(context, chooser, shareSubject, ready.commentary.text)) onMessage(noApp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.commentary_share), style = MaterialTheme.typography.titleMedium) }
        }
        val writing = commentary == CommentaryUi.Writing
        OutlinedButton(
            enabled = !writing,
            onClick = onWrite,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(
                    when {
                        writing -> R.string.commentary_writing
                        ready != null -> R.string.commentary_again
                        else -> R.string.commentary_write
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Fragt vor dem ersten Senden an einen Dienst, ob gesendet werden darf.
 *
 * @param allEvenings `true`: Gesendet werden die Fakten aller Abende (die Bilanz), sonst die eines Abends.
 */
@Composable
fun CommentaryConsentDialog(
    consent: CommentaryUi.NeedsConsent,
    allEvenings: Boolean,
    onConsent: (String) -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.ai_consent_title, consent.host)) },
        text = {
            Text(
                stringResource(
                    if (allEvenings) R.string.ai_consent_text_all else R.string.ai_consent_text,
                    consent.host,
                    stringResource(if (consent.realNames) R.string.ai_consent_names_on else R.string.ai_consent_names_off),
                ),
            )
        },
        confirmButton = { Button(onClick = { onConsent(consent.host) }) { Text(stringResource(R.string.ai_consent_yes)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.ai_consent_no)) } },
    )
}
