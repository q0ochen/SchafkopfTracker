package io.github.q0ochen.schafkopf.app.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.q0ochen.schafkopf.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die beiden Arten zu teilen, fertig verdrahtet: als Text und als Bild.
 *
 * @property busy `true`, solange das Bild entsteht; ein zweites Tippen wartet dann nicht noch ein Bild ab.
 */
class ShareActions(val shareText: () -> Unit, val shareImage: () -> Unit, val busy: Boolean)

/**
 * Teilen über das Teilen-Menü von Android.
 *
 * @param text Liefert den Text erst beim Tippen, damit er immer zum aktuellen Stand passt.
 * @param capture Was als Bild geteilt wird (siehe [captureInto]).
 * @param imageName Dateiname des Bildes, zum Beispiel „schafkopf-abend.png“.
 * @param onMessage Zeigt eine Meldung, wenn das Teilen nicht klappt.
 */
@Composable
fun rememberShareActions(
    subject: String,
    text: () -> String,
    capture: GraphicsLayer,
    imageName: String,
    onMessage: (String) -> Unit,
): ShareActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooser = stringResource(R.string.share_chooser)
    val noApp = stringResource(R.string.share_no_app)
    val imageFailed = stringResource(R.string.share_image_failed)
    var busy by remember { mutableStateOf(false) }
    return ShareActions(
        shareText = { if (!Sharing.shareText(context, chooser, subject, text())) onMessage(noApp) },
        shareImage = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val picture = capture.toImageBitmap().asAndroidBitmap()
                        // Ein Bild im Grafikspeicher lässt sich nicht direkt als PNG schreiben; erst umkopieren.
                        val bitmap = if (picture.config == Bitmap.Config.HARDWARE) {
                            picture.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            picture
                        }
                        val file = withContext(Dispatchers.IO) { Sharing.writePng(context, bitmap, imageName) }
                        if (!Sharing.shareImage(context, chooser, file)) onMessage(noApp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        onMessage(imageFailed)
                    } finally {
                        busy = false
                    }
                }
            }
        },
        busy = busy,
    )
}

/** „Als Bild teilen“ (Hauptaktion, zuerst) und „Als Text teilen“ untereinander (docs/ARCHITEKTUR.md, Abschnitt 4.4). */
@Composable
fun ShareButtons(actions: ShareActions, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        Button(
            enabled = !actions.busy,
            onClick = actions.shareImage,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_as_image), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = actions.shareText,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.share_as_text), style = MaterialTheme.typography.titleMedium)
        }
    }
}
