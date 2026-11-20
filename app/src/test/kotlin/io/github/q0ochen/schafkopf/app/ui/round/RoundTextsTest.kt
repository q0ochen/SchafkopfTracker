package io.github.q0ochen.schafkopf.app.ui.round

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.github.q0ochen.schafkopf.app.round.RoundProblem
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.SyncNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die Texte der gemeinsamen Runde, Wort für Wort wie in docs/ARCHITEKTUR.md, Abschnitt 8.9. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoundTextsTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Application>().resources
    private val linked = RoundState(
        configured = true,
        loaded = true,
        account = Account("konto-1", null),
        link = RoundLink(RoundId("runde"), "Stammtisch", "konto-1"),
    )

    private fun status(state: RoundState) = roundStatus(state, resources)

    @Test
    fun `die Statuszeile sagt, ob abgeglichen ist oder etwas wartet`() {
        assertEquals(RoundStatus(RoundStatusKind.SYNCED, "Abgeglichen"), status(linked))
        assertEquals(RoundStatus(RoundStatusKind.PENDING, "1 Änderung wartet"), status(linked.copy(pending = 1)))
        assertEquals(RoundStatus(RoundStatusKind.PENDING, "3 Änderungen warten"), status(linked.copy(pending = 3)))
    }

    @Test
    fun `ohne Netz und bei einem Fehler des Servers steht dabei, wie viel wartet`() {
        val offline = linked.copy(problem = RoundProblem.OFFLINE)
        assertEquals(RoundStatus(RoundStatusKind.PROBLEM, "Kein Netz"), status(offline))
        assertEquals("Kein Netz – 1 Änderung wartet", status(offline.copy(pending = 1)).text)
        assertEquals("Kein Netz – 4 Änderungen warten", status(offline.copy(pending = 4)).text)

        val server = linked.copy(problem = RoundProblem.SERVER)
        assertEquals(RoundStatus(RoundStatusKind.PROBLEM, "Server nicht erreichbar"), status(server))
        assertEquals("Server nicht erreichbar – 1 Änderung wartet", status(server.copy(pending = 1)).text)
        assertEquals("Server nicht erreichbar – 2 Änderungen warten", status(server.copy(pending = 2)).text)
    }

    @Test
    fun `es gilt die erste zutreffende Zeile - Anmeldung vor Unlesbarem vor Abgelehntem vor dem Netz`() {
        val everything = linked.copy(problem = RoundProblem.OFFLINE, pending = 2, unreadable = 1, refused = 3)

        assertEquals("Anmeldung abgelaufen – bitte neu anmelden", status(everything.copy(problem = RoundProblem.SIGNED_OUT)).text)
        assertEquals("Anmeldung abgelaufen – bitte neu anmelden", status(everything.copy(account = null)).text)
        assertEquals("1 Abend kann diese Version nicht lesen – bitte die App aktualisieren", status(everything).text)
        assertEquals("2 Abende kann diese Version nicht lesen – bitte die App aktualisieren", status(everything.copy(unreadable = 2)).text)
        assertEquals("3 Abende nimmt der Server nicht an", status(everything.copy(unreadable = 0)).text)
        assertEquals("1 Abend nimmt der Server nicht an", status(everything.copy(unreadable = 0, refused = 1)).text)
        assertEquals("Kein Netz – 2 Änderungen warten", status(everything.copy(unreadable = 0, refused = 0)).text)
        assertEquals(RoundStatusKind.PROBLEM, status(everything).kind)
    }

    @Test
    fun `zu jedem Fehler gibt es einen Satz, den man versteht`() {
        fun text(error: RoundError, roundName: Boolean = false) = roundErrorText(error, resources, roundName)

        assertEquals("Die Einladung ist abgelaufen oder ungültig.", text(RoundError.INVALID_INVITE))
        assertEquals("Den Namen gibt es in der Runde schon.", text(RoundError.NAME_TAKEN))
        assertEquals("Bitte einen Namen mit 1 bis 24 Zeichen eingeben.", text(RoundError.INVALID_NAME))
        assertEquals("Bitte einen Namen mit 1 bis 40 Zeichen eingeben.", text(RoundError.INVALID_NAME, roundName = true))
        assertEquals("Diesen Spieler hat gerade jemand anders gewählt.", text(RoundError.PLAYER_TAKEN))
        assertEquals("Du hast schon einen Namen in dieser Runde.", text(RoundError.ALREADY_CLAIMED))
        assertEquals("Mehr geht nicht: Die Grenze für Runden, Mitglieder oder Einladungen ist erreicht.", text(RoundError.LIMIT_REACHED))
        assertEquals("Keine Verbindung. Bitte später noch einmal versuchen.", text(RoundError.OFFLINE))
        assertEquals("Anmeldung abgelaufen – bitte neu anmelden", text(RoundError.SIGNED_OUT))
        for (other in listOf(RoundError.SERVER, RoundError.NOT_MEMBER, RoundError.NOT_CONFIGURED, RoundError.TOO_LARGE, RoundError.INVALID_DOC, RoundError.NAME_LOCKED, RoundError.PLAYER_OWNED)) {
            assertEquals("Das hat nicht geklappt. Bitte später noch einmal versuchen.", text(other))
        }
    }

    @Test
    fun `die Hinweise aus dem Abgleich nennen den Spieler und was geschehen ist`() {
        val id = PlayerId("gast")
        fun text(reason: RoundError, name: String = "Wirt", now: String?) = noticeText(SyncNotice(id, name, reason, now), resources)

        assertEquals("Nord hat den Namen selbst festgelegt. Er bleibt.", text(RoundError.NAME_LOCKED, name = "Witzbold", now = "Nord"))
        assertEquals("Den Namen Wirt gibt es in der Runde schon. Der neue Spieler wurde nicht angelegt.", text(RoundError.NAME_TAKEN, now = null))
        assertEquals("Den Namen Wirt gibt es in der Runde schon. Der Spieler heißt jetzt Wirt 2.", text(RoundError.NAME_TAKEN, now = "Wirt 2"))
        assertEquals("Wirt gehört zu einem Mitglied und lässt sich nicht löschen.", text(RoundError.PLAYER_OWNED, now = "Wirt"))
        assertEquals("Die Runde hat schon 40 Spieler. Wirt wurde nicht hochgeladen.", text(RoundError.LIMIT_REACHED, now = "Wirt"))
        assertEquals("Der Name Wirt ist nicht erlaubt.", text(RoundError.INVALID_NAME, now = null))
        assertEquals("Der Name Wirt ist nicht erlaubt.", text(RoundError.INVALID_DOC, now = "Wirt"))
    }
}
