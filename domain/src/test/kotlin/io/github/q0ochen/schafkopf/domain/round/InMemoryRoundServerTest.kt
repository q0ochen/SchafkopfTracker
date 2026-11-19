package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Der nachgestellte Server hält sich an den Vertrag aus docs/ARCHITEKTUR.md, Abschnitt 8.2 und 8.3. Gegen ihn laufen
 * die Tests des Abgleichs; der echte Server hat seine eigenen Tests in `supabase/tests`.
 */
class InMemoryRoundServerTest {

    private val clock = TestClock()
    private val server = InMemoryRoundServer(clock)
    private val one = server.sessionFor("konto-1")
    private val two = server.sessionFor("konto-2")
    private val guest = Player(PlayerId("gast"), "Wirt", colorIndex = 3)

    private suspend fun errorOf(block: suspend () -> Unit): RoundError? = try {
        block()
        null
    } catch (e: RoundException) {
        e.error
    }

    private suspend fun roundOfTwo(): RoundId {
        val round = one.createRound("Stammtisch")
        two.joinRound(one.createInvite(round))
        return round
    }

    // --- Runden und Mitglieder ---

    @Test
    fun `eine neue Runde hat ihren Ersteller als einziges Mitglied`() = runTest {
        val round = one.createRound("  Stammtisch  ")

        assertEquals(RoundHead(round, "Stammtisch", rev = 0, rules = null, createdBy = "konto-1"), one.head(round))
        assertEquals(listOf(RoundMember("konto-1", null)), one.members(round))
        assertEquals(listOf(RoundSummary(round, "Stammtisch", 0, memberCount = 1, myPlayerId = null, isCreator = true)), one.myRounds())
    }

    @Test
    fun `der Name einer Runde hat 1 bis 40 Zeichen`() = runTest {
        assertEquals(RoundError.INVALID_NAME, errorOf { one.createRound("   ") })
        assertEquals(RoundError.INVALID_NAME, errorOf { one.createRound("x".repeat(41)) })
        assertNull(errorOf { one.createRound("x".repeat(40)) })
    }

    @Test
    fun `ein Konto kann hoechstens fuenf Runden angehoeren`() = runTest {
        repeat(InMemoryRoundServer.MAX_ROUNDS) { one.createRound("Runde $it") }
        val other = two.createRound("Noch eine")

        assertEquals(RoundError.LIMIT_REACHED, errorOf { one.createRound("Zu viel") })
        assertEquals(RoundError.LIMIT_REACHED, errorOf { one.joinRound(two.createInvite(other)) })
    }

    @Test
    fun `wer nicht dazugehoert, sieht nichts und darf nichts`() = runTest {
        val round = one.createRound("Stammtisch")
        one.savePlayer(round, guest)
        one.saveEvening(round, evening(), expectedVersion = 0)

        assertNull(two.head(round))
        assertEquals(emptyList<RoundMember>(), two.members(round))
        assertEquals(emptyList<RemotePlayer>(), two.playersSince(round, 0))
        assertEquals(emptyList<RemoteEvening>(), two.eveningsSince(round, 0))
        assertNull(two.evening(round, EveningId("abend")))
        assertEquals(emptyList<RoundSummary>(), two.myRounds())
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.saveEvening(round, evening(), 1) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.savePlayer(round, guest) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.saveRules(round, RuleSet.standard()) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.createInvite(round) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.claimPlayer(round, guest.id, "Zwei", 0) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.leaveRound(round) })
        assertEquals(RoundError.NOT_MEMBER, errorOf { two.leaveRound(RoundId("gibt-es-nicht")) })
    }

    // --- Einladungen ---

    @Test
    fun `eine Einladung ist ein Code aus 32 Zeichen und verraet vor dem Beitritt nur Name und Groesse`() = runTest {
        val round = one.createRound("Stammtisch")
        val token = one.createInvite(round)

        assertEquals(token, InviteLinks.tokenFrom(InviteLinks.webLink("https://example.org/app/", token)))
        assertEquals(InviteInfo(round, "Stammtisch", memberCount = 1, alreadyMember = false), two.inviteInfo(token))
        assertEquals(round, two.joinRound(token))
        assertEquals(InviteInfo(round, "Stammtisch", memberCount = 2, alreadyMember = true), two.inviteInfo(token))
        // Noch einmal beitreten ändert nichts.
        val rev = server.rev(round)
        assertEquals(round, two.joinRound(token))
        assertEquals(rev, server.rev(round))
        assertEquals(2, one.members(round).size)
    }

    @Test
    fun `eine Einladung gilt 14 Tage, ein falscher Code nie`() = runTest {
        val round = one.createRound("Stammtisch")
        val token = one.createInvite(round)

        assertEquals(RoundError.INVALID_INVITE, errorOf { two.inviteInfo("0".repeat(32)) })
        assertEquals(RoundError.INVALID_INVITE, errorOf { two.joinRound("kein-code") })
        clock.now = clock.now.plus(InMemoryRoundServer.INVITE_LIFETIME).minusSeconds(1)
        assertNull(errorOf { two.inviteInfo(token) })
        clock.advance(1)
        assertEquals(RoundError.INVALID_INVITE, errorOf { two.inviteInfo(token) })
        assertEquals(RoundError.INVALID_INVITE, errorOf { two.joinRound(token) })
    }

    @Test
    fun `je Runde gibt es hoechstens 20 gueltige Einladungen - abgelaufene zaehlen nicht`() = runTest {
        val round = one.createRound("Stammtisch")
        val tokens = List(InMemoryRoundServer.MAX_INVITES) { one.createInvite(round) }

        assertEquals(InMemoryRoundServer.MAX_INVITES, tokens.toSet().size)
        assertEquals(RoundError.LIMIT_REACHED, errorOf { one.createInvite(round) })
        clock.now = clock.now.plus(Duration.ofDays(15))
        assertNull(errorOf { one.createInvite(round) })
    }

    @Test
    fun `eine Runde hat hoechstens zwoelf Mitglieder`() = runTest {
        val round = one.createRound("Stammtisch")
        val token = one.createInvite(round)
        repeat(InMemoryRoundServer.MAX_MEMBERS - 1) { server.sessionFor("mitglied-$it").joinRound(token) }

        assertEquals(RoundError.LIMIT_REACHED, errorOf { two.joinRound(token) })
    }

    // --- Abende ---

    @Test
    fun `ein Abend wird nur mit der erwarteten Fassung gespeichert`() = runTest {
        val round = roundOfTwo()
        val first = evening(game("g1", 60))
        val second = evening(game("g1", 60), game("g2", 120))
        val before = server.rev(round)

        // Neu nur mit der Fassung 0.
        assertNull(one.saveEvening(round, first, expectedVersion = 1))
        assertEquals(SavedEvening(version = 1, rev = before + 1), one.saveEvening(round, first, expectedVersion = 0))
        assertNull(two.saveEvening(round, second, expectedVersion = 0))
        assertNull(two.saveEvening(round, second, expectedVersion = 2))
        // Eine abgelehnte Fassung zählt nicht als Änderung der Runde.
        assertEquals(before + 1, server.rev(round))
        assertEquals(SavedEvening(version = 2, rev = before + 2), two.saveEvening(round, second, expectedVersion = 1))

        assertEquals(RemoteEvening(first.id, version = 2, deleted = false, rev = before + 2, evening = second), one.evening(round, first.id))
        assertEquals(listOf(second), server.evenings(round))
    }

    @Test
    fun `ein geloeschter Abend bleibt als Zeile und laesst sich wieder anlegen`() = runTest {
        val round = roundOfTwo()
        val doc = evening(game("g1", 60))
        one.saveEvening(round, doc, expectedVersion = 0)

        assertEquals(2, one.saveEvening(round, doc, expectedVersion = 1, deleted = true)?.version)
        assertTrue(two.eveningsSince(round, 0).single().deleted)
        assertEquals(emptyList<Any>(), server.evenings(round))
        assertEquals(3, two.saveEvening(round, doc, expectedVersion = 2)?.version)
        assertFalse(one.evening(round, doc.id)!!.deleted)
    }

    @Test
    fun `gelesen wird alles nach einer Aenderungsnummer, in der Reihenfolge der Aenderungen`() = runTest {
        val round = roundOfTwo()
        val start = server.rev(round)
        one.saveEvening(round, evening(id = "a"), 0)
        one.saveEvening(round, evening(id = "b"), 0)
        one.saveEvening(round, evening(game("g1", 60), id = "a"), 1)

        assertEquals(listOf("b", "a"), two.eveningsSince(round, start).map { it.id.value })
        assertEquals(listOf("a"), two.eveningsSince(round, start + 2).map { it.id.value })
        assertEquals(emptyList<RemoteEvening>(), two.eveningsSince(round, start + 3))
        assertEquals(start + 3, two.head(round)?.rev)
    }

    // --- Spieler ---

    @Test
    fun `Gaeste bekommen eine Position, Namen werden bereinigt und sind eindeutig`() = runTest {
        val round = roundOfTwo()

        one.savePlayer(round, guest.copy(name = "  Wirt  "))
        two.savePlayer(round, Player(PlayerId("gast-2"), "Nachbar", colorIndex = 40))

        assertEquals(listOf("Wirt", "Nachbar"), server.players(round).map { it.name })
        assertEquals(listOf(0, 1), server.players(round).map { it.position })
        assertEquals(8, server.players(round)[1].colorIndex)
        assertEquals(RoundError.NAME_TAKEN, errorOf { two.savePlayer(round, Player(PlayerId("gast-3"), "WIRT", 0)) })
        assertEquals(RoundError.NAME_TAKEN, errorOf { two.savePlayer(round, Player(PlayerId("gast-2"), "wirt", 0)) })
        assertEquals(RoundError.INVALID_NAME, errorOf { two.savePlayer(round, Player(PlayerId("gast-3"), "  ", 0)) })
        assertEquals(RoundError.INVALID_NAME, errorOf { two.savePlayer(round, Player(PlayerId("gast-3"), "x".repeat(25), 0)) })
        // Der eigene Name darf bleiben, auch in anderer Schreibweise.
        assertNull(errorOf { two.savePlayer(round, guest.copy(name = "WIRT", archived = true)) })
        assertEquals(RemotePlayer(guest.id, "WIRT", 3, archived = true, position = 0, rev = server.rev(round)), one.playersSince(round, 0).last())
    }

    @Test
    fun `ein geloeschter Gast gibt seinen Namen frei und kann wiederkommen`() = runTest {
        val round = roundOfTwo()
        one.savePlayer(round, guest)

        val rev = one.savePlayer(round, guest, deleted = true)

        assertEquals(server.rev(round), rev)
        assertTrue(two.playersSince(round, 0).single().deleted)
        assertEquals(emptyList<RemotePlayer>(), server.players(round))
        // Noch einmal löschen oder einen unbekannten Spieler löschen ändert nichts.
        assertEquals(rev, two.savePlayer(round, guest, deleted = true))
        assertEquals(rev, two.savePlayer(round, Player(PlayerId("unbekannt"), "Niemand", 0), deleted = true))
        assertNull(errorOf { two.savePlayer(round, Player(PlayerId("gast-2"), "Wirt", 0)) })
        assertEquals(RoundError.NAME_TAKEN, errorOf { one.savePlayer(round, guest) })
        assertNull(errorOf { one.savePlayer(round, guest.copy(name = "Wirt von nebenan")) })
        assertEquals(listOf("Wirt von nebenan", "Wirt"), server.players(round).map { it.name })
    }

    @Test
    fun `eine Runde hat hoechstens 40 Spieler - geloeschte zaehlen nicht`() = runTest {
        val round = roundOfTwo()
        repeat(InMemoryRoundServer.MAX_PLAYERS) { one.savePlayer(round, Player(PlayerId("p$it"), "Spieler $it", it)) }

        assertEquals(RoundError.LIMIT_REACHED, errorOf { one.savePlayer(round, guest) })
        assertEquals(RoundError.LIMIT_REACHED, errorOf { two.claimPlayer(round, PlayerId("ich"), "Zwei", 0) })
        one.savePlayer(round, Player(PlayerId("p0"), "Spieler 0", 0), deleted = true)
        assertNull(errorOf { one.savePlayer(round, guest) })
    }

    // --- Das bin ich ---

    @Test
    fun `Das bin ich macht den Spieler zum eigenen und den Namen fest`() = runTest {
        val round = roundOfTwo()
        one.savePlayer(round, guest)

        two.claimPlayer(round, guest.id, " Zwei ", colorIndex = 9)

        val row = server.players(round).single()
        assertEquals(RemotePlayer(guest.id, "Zwei", colorIndex = 3, archived = false, position = 0, owner = "konto-2", nameLocked = true, rev = row.rev), row)
        assertEquals(RoundMember("konto-2", guest.id), one.members(round).last())
        assertEquals(guest.id, two.myRounds().single().myPlayerId)
        assertEquals(RoundError.ALREADY_CLAIMED, errorOf { two.claimPlayer(round, PlayerId("noch-einer"), "Drei", 0) })
        assertEquals(RoundError.PLAYER_TAKEN, errorOf { one.claimPlayer(round, guest.id, "Eins", 0) })
        assertEquals(RoundError.PLAYER_OWNED, errorOf { one.savePlayer(round, guest, deleted = true) })
    }

    @Test
    fun `den festen Namen aendert nur der Ersteller der Runde`() = runTest {
        val round = roundOfTwo()
        val third = server.sessionFor("konto-3")
        third.joinRound(one.createInvite(round))
        two.claimPlayer(round, guest.id, "Zwei", colorIndex = 3)
        val mine = Player(guest.id, "Zwei", colorIndex = 3)

        assertEquals(RoundError.NAME_LOCKED, errorOf { third.savePlayer(round, mine.copy(name = "Witzbold")) })
        // Auch der Besitzer selbst nicht: Der Name ist nach „Das bin ich“ fest.
        assertEquals(RoundError.NAME_LOCKED, errorOf { two.savePlayer(round, mine.copy(name = "Doch anders")) })
        assertNull(errorOf { third.savePlayer(round, mine.copy(colorIndex = 5, archived = true)) })
        assertNull(errorOf { one.savePlayer(round, mine.copy(name = "Zwo")) })
        assertEquals(Player(guest.id, "Zwo", colorIndex = 3), server.players(round).single().toPlayer())
        assertTrue(server.players(round).single().nameLocked)
    }

    @Test
    fun `Das bin ich mit neuem Spieler, vergebenem Namen und nach dem Wiederkommen`() = runTest {
        val round = roundOfTwo()
        one.savePlayer(round, guest)
        assertEquals(RoundError.NAME_TAKEN, errorOf { two.claimPlayer(round, PlayerId("ich"), "wirt", 0) })
        assertEquals(RoundError.INVALID_NAME, errorOf { two.claimPlayer(round, PlayerId("ich"), "", 0) })

        two.claimPlayer(round, PlayerId("ich"), "Zwei", colorIndex = 33)
        assertEquals(RemotePlayer(PlayerId("ich"), "Zwei", colorIndex = 1, archived = false, position = 1, owner = "konto-2", nameLocked = true, rev = server.rev(round)), server.players(round).last())

        // Wer geht und wiederkommt, ist wieder derselbe Spieler.
        two.leaveRound(round)
        assertEquals(listOf(RoundMember("konto-1", null)), one.members(round))
        assertEquals("konto-2", server.players(round).last().owner)
        two.joinRound(one.createInvite(round))
        assertEquals(PlayerId("ich"), one.members(round).last().playerId)
    }

    @Test
    fun `ein geloeschter Gast kann von jemandem als eigener Spieler neu angelegt werden`() = runTest {
        val round = roundOfTwo()
        one.savePlayer(round, guest)
        one.savePlayer(round, guest, deleted = true)

        two.claimPlayer(round, guest.id, "Zwei", colorIndex = 6)

        val row = server.players(round).single()
        assertEquals("konto-2", row.owner)
        assertEquals(0, row.position)
        assertEquals(6, row.colorIndex)
    }

    // --- Regelwerk, Störungen, Hilfen für Tests ---

    @Test
    fun `das Regelwerk fuer neue Abende steht am Kopf der Runde`() = runTest {
        val round = roundOfTwo()

        val rev = two.saveRules(round, RuleSet.sauspiel())

        assertEquals(RuleSet.sauspiel(), one.head(round)?.rules)
        assertEquals(rev, one.head(round)?.rev)
        server.makeRulesUnreadable(round)
        assertEquals(true, one.head(round)?.rulesUnreadable)
        assertNull(one.head(round)?.rules)
        one.saveRules(round, RuleSet.standard())
        assertEquals(false, one.head(round)?.rulesUnreadable)
    }

    @Test
    fun `ohne Netz und ohne Anmeldung scheitert jeder Aufruf, eine verlorene Antwort nur das eine Mal`() = runTest {
        val round = roundOfTwo()
        one.offline = true
        assertEquals(RoundError.OFFLINE, errorOf { one.head(round) })
        assertEquals(RoundError.OFFLINE, errorOf { one.savePlayer(round, guest) })
        one.offline = false
        one.signedOut = true
        assertEquals(RoundError.SIGNED_OUT, errorOf { one.myRounds() })
        one.signedOut = false

        one.loseNextAnswer = true
        // Lesen ist nicht betroffen; die nächste Änderung kommt an, nur ihre Antwort nicht.
        assertNull(errorOf { one.head(round) })
        assertEquals(RoundError.OFFLINE, errorOf { one.savePlayer(round, guest) })
        assertEquals(listOf("Wirt"), server.players(round).map { it.name })
        assertNull(errorOf { one.savePlayer(round, guest.copy(colorIndex = 1)) })
        assertEquals(listOf("head", "savePlayer", "myRounds", "head", "savePlayer", "savePlayer"), one.calls.drop(2))
    }

    @Test
    fun `der Server laesst sich fuer Tests befragen und stoeren`() = runTest {
        val round = roundOfTwo()
        val doc = evening(game("g1", 60))
        one.saveEvening(round, doc, 0)
        val rev = server.rev(round)

        server.makeUnreadable(round, doc.id)
        assertEquals(RemoteEvening(doc.id, version = 2, deleted = false, rev = rev + 1, evening = null), server.eveningRow(round, doc.id))
        assertEquals(emptyList<Any>(), server.evenings(round))

        server.rollBack(round, doc.id, version = 1, evening = doc, rev = 1)
        assertEquals(1, server.rev(round))
        assertEquals(listOf(doc), server.evenings(round))

        server.removeMember(round, "konto-2")
        assertNull(two.head(round))
        server.removeMember(round, "konto-1")
        assertEquals(0, server.rev(round))
        assertNull(server.eveningRow(round, doc.id))
        assertEquals(emptyList<Any>(), server.players(round))
        server.removeMember(round, "konto-1")
        assertNotEquals(round, one.createRound("Neu"))
    }

    @Test
    fun `der Speicher eines Geraets merkt sich Runde und Basen`() = runTest {
        val store = InMemoryRoundStore()
        val link = RoundLink(RoundId("runde"), "Stammtisch", "konto-1")
        val base = EveningBase(3, evening(game("g1", 60)))
        val player = RemotePlayer(guest.id, guest.name, guest.colorIndex, guest.archived, owner = "konto-2", nameLocked = true)

        assertNull(store.link.first())
        store.setLink(link)
        store.setEveningBase(base.evening.id, base)
        store.setPlayerBase(player.id, player)

        assertEquals(link, store.link.first())
        assertEquals(mapOf(base.evening.id to base), store.eveningBases())
        assertEquals(mapOf(player.id to player), store.playerBases())
        assertTrue(player.sameContent(guest))
        assertFalse(player.sameContent(guest.copy(archived = true)))
        assertFalse(player.sameContent(guest.copy(id = PlayerId("anders"))))

        store.setEveningBase(base.evening.id, null)
        store.setPlayerBase(player.id, null)
        assertTrue(store.eveningBases().isEmpty() && store.playerBases().isEmpty())
        store.setEveningBase(base.evening.id, base)
        store.setPlayerBase(player.id, player)
        store.clearBases()
        assertTrue(store.eveningBases().isEmpty() && store.playerBases().isEmpty())
        assertEquals(InMemoryRoundStore(link).link.first(), store.link.first())
    }

    @Test
    fun `Fehlercodes des Servers werden erkannt, alles andere nicht`() {
        assertEquals(RoundError.NAME_TAKEN, RoundError.fromCode("name_taken"))
        assertEquals(RoundError.NOT_MEMBER, RoundError.fromCode("not_member"))
        assertEquals(RoundError.INVALID_DOC, RoundError.fromCode("invalid_doc"))
        assertEquals(
            listOf(
                "not_member", "invalid_name", "name_taken", "name_locked", "already_claimed", "player_taken",
                "player_owned", "invalid_invite", "limit_reached", "too_large", "invalid_doc",
            ),
            RoundError.entries.mapNotNull { error -> error.name.lowercase().takeIf { RoundError.fromCode(it) == error } },
        )
        // Die Fehler der App selbst sind keine Codes des Servers.
        assertNull(RoundError.fromCode("offline"))
        assertNull(RoundError.fromCode("signed_out"))
        assertNull(RoundError.fromCode("server"))
        assertNull(RoundError.fromCode("not_configured"))
        assertNull(RoundError.fromCode("NAME_TAKEN"))
        assertNull(RoundError.fromCode(""))
        assertEquals("NAME_TAKEN", RoundException(RoundError.NAME_TAKEN).message)
        assertEquals("SERVER: 503", RoundException(RoundError.SERVER, "503").message)
    }
}
