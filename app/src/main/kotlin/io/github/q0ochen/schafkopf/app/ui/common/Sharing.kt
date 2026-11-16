package io.github.q0ochen.schafkopf.app.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.core.content.FileProvider
import java.io.File

/**
 * Teilen über das Teilen-Menü von Android, zum Beispiel an Signal oder Telegram: als Text oder als Bild.
 *
 * Bilder entstehen aus dem, was eine Ansicht zeichnet ([captureInto]), und liegen kurz im Cache der App. Nur dieser
 * eine Ordner ist über den [FileProvider] für die Empfänger-App lesbar (siehe `res/xml/file_paths.xml`), und nur mit
 * der Erlaubnis, die das Teilen mitgibt.
 */
object Sharing {
    /** Ordner im Cache für geteilte Bilder; muss zu `res/xml/file_paths.xml` passen. */
    const val SHARED_FOLDER = "geteilt"

    /** Kennung des [FileProvider] aus dem Manifest. */
    fun authority(context: Context): String = "${context.packageName}.dateien"

    /** Das Teilen-Menü für einen Text. @return `false`, wenn es auf dem Gerät keine App zum Teilen gibt. */
    fun shareText(context: Context, chooserTitle: String, subject: String, text: String): Boolean =
        start(context, Intent.createChooser(textIntent(subject, text), chooserTitle))

    /** Die Absicht „Text senden“, ohne Auswahl der App (für Tests einzeln abrufbar). */
    fun textIntent(subject: String, text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }

    /**
     * Legt das Bild als PNG im Cache ab. Ältere geteilte Bilder werden dabei gelöscht, damit der Cache nicht wächst
     * (eine Empfänger-App hat das Bild bis dahin längst übernommen).
     */
    fun writePng(context: Context, bitmap: Bitmap, fileName: String): File {
        val folder = File(context.cacheDir, SHARED_FOLDER)
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Das Teilen-Menü für ein Bild aus [writePng]. @return `false`, wenn es keine App zum Teilen gibt. */
    fun shareImage(context: Context, chooserTitle: String, file: File): Boolean =
        start(context, Intent.createChooser(imageIntent(context, file), chooserTitle))

    /** Die Absicht „Bild senden“ mit Leserecht für die Empfänger-App, ohne Auswahl der App. */
    fun imageIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Über ClipData bekommt auch die Vorschau im Teilen-Menü das Leserecht.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Ein Zwischenspeicher für das, was eine Ansicht zeichnet; daraus entsteht das Bild zum Teilen. */
@Composable
fun rememberCapture(): GraphicsLayer = rememberGraphicsLayer()

/**
 * Zeichnet die Ansicht wie gewohnt und hält dabei fest, was sie zeichnet, mit [background] darunter (sonst wäre das
 * Bild durchsichtig). Das gilt auch für Teile, die gerade nicht im Bild sind, weil die Seite gescrollt ist.
 */
fun Modifier.captureInto(layer: GraphicsLayer, background: Color): Modifier = drawWithContent {
    layer.record {
        drawRect(background)
        this@drawWithContent.drawContent()
    }
    drawLayer(layer)
}
