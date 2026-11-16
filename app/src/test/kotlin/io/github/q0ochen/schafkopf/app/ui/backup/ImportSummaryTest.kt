package io.github.q0ochen.schafkopf.app.ui.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.ui.TestEvenings
import io.github.q0ochen.schafkopf.data.backup.BackupError
import io.github.q0ochen.schafkopf.data.backup.BackupErrorKind
import io.github.q0ochen.schafkopf.data.backup.EveningProblem
import io.github.q0ochen.schafkopf.data.backup.ImportPreview
import io.github.q0ochen.schafkopf.data.backup.InvalidEvening
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die Vorschau vor dem Einlesen, in ganzen Sätzen und mit richtiger Einzahl und Mehrzahl. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ImportSummaryTest {

    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `alles in einem`() {
        val gast = Player(PlayerId("gast"), "Gast", colorIndex = 4)
        val anna2 = Player(PlayerId("anna-2"), "Anna", colorIndex = 5)
        val preview = ImportPreview(
            newPlayers = listOf(gast, anna2),
            sameNamePlayers = listOf(anna2),
            newEvenings = listOf(TestEvenings.evening("a"), TestEvenings.evening("b")),
            existingEvenings = listOf(TestEvenings.evening("c")),
            invalidEvenings = listOf(InvalidEvening("x", null, EveningProblem.WRONG_SEATING, "Am Tisch sitzen nicht vier Spieler.")),
        )
        assertEquals(
            listOf(
                "2 neue Abende kommen dazu.",
                "1 Abend ist schon da und bleibt, wie er ist.",
                "1 Abend wird ausgelassen:",
                "– Am Tisch sitzen nicht vier Spieler.",
                "2 neue Spieler kommen dazu: Gast, Anna.",
                "Achtung: Anna heißt wie ein vorhandener Spieler, ist aber ein anderer und wird zusätzlich angelegt.",
            ),
            importSummary(resources, preview),
        )
    }

    @Test
    fun `nichts Neues und eine unlesbare Datei`() {
        assertEquals(listOf("Keine neuen Abende."), importSummary(resources, ImportPreview()))
        val error = BackupError(BackupErrorKind.INVALID_JSON, "Die Datei ist keine Sicherung.")
        assertEquals(listOf("Die Datei ist keine Sicherung."), importSummary(resources, ImportPreview(error = error)))
    }
}
