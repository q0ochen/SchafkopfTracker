package io.github.q0ochen.schafkopf.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.TestSchafkopfApp
import io.github.q0ochen.schafkopf.app.ui.closing.ClosingScreen
import io.github.q0ochen.schafkopf.app.ui.common.Sharing
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Teilen aus „Abend teilen“: Das Teilen-Menü von Android bekommt den Text bzw. das Bild. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestSchafkopfApp::class)
class ShareTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: TestSchafkopfApp get() = ApplicationProvider.getApplicationContext()

    @After
    fun stopDependencyInjection() = stopKoin()

    private fun showClosing() {
        runBlocking {
            app
            val koin = GlobalContext.get()
            TestEvenings.players.forEach { koin.get<PlayerRepository>().save(it) }
            koin.get<EveningRepository>().save(TestEvenings.evening(location = "Post"))
        }
        compose.setContent {
            SchafkopfTheme(ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) { ClosingScreen("abend-1", Navigator()) }
            }
        }
        compose.waitForIdle()
        compose.waitForText("Wer zahlt wem")
    }

    /** Die Absicht hinter dem Teilen-Menü, das zuletzt geöffnet wurde. */
    private fun sharedIntent(): Intent {
        val chooser = shadowOf(app).nextStartedActivity
        assertNotNull("Das Teilen-Menü wurde nicht geöffnet", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    @Test
    fun `Als Text teilen gibt den Abschluss als Text weiter`() {
        showClosing()
        compose.onNodeWithText("Als Text teilen").performScrollTo().performClick()
        compose.waitForIdle()
        val send = sharedIntent()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Schafkopf-Abend", send.getStringExtra(Intent.EXTRA_SUBJECT))
        val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text, text.startsWith("Schafkopf-Abend vom "))
        assertTrue(text, text.contains("· Post\n2 Spiele\n"))
        assertTrue(text, text.endsWith("Bert → Done: 0,40 €"))
    }

    @Test
    fun `Als Bild teilen legt ein PNG ab und gibt es mit Leserecht weiter`() {
        showClosing()
        // Robolectric zeichnet nur auf Anfrage; auf dem Gerät ist der Bildschirm längst gezeichnet, bevor man tippt.
        compose.onRoot().captureToImage()
        compose.onNodeWithText("Als Bild teilen").performScrollTo().performClick()
        // Das Bild entsteht im Hintergrund; warten, bis das Teilen-Menü offen ist.
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(app).peekNextStartedActivity() != null ||
                compose.onAllNodesWithText("Das Bild ließ sich nicht erstellen.").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "Fehlermeldung statt Bild",
            0,
            compose.onAllNodesWithText("Das Bild ließ sich nicht erstellen.").fetchSemanticsNodes().size,
        )
        val send = sharedIntent()
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri: Uri = send.getParcelableExtra(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.dateien", uri.authority)
        val file = File(File(app.cacheDir, Sharing.SHARED_FOLDER), "schafkopf-abend.png")
        assertTrue("Bild fehlt", file.isFile)
        // PNG-Signatur und eine sinnvolle Größe (mehr als ein leeres Bild).
        val bytes = file.readBytes()
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertTrue("Bild zu klein: ${bytes.size} Bytes", bytes.size > 5_000)
        // Zum Ansehen neben den Bildschirmfotos ablegen.
        file.copyTo(File("build/outputs/roborazzi/18_geteiltes_bild_abschluss.png"), overwrite = true)
    }
}
