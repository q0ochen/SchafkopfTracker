package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Lässt mehrere nachgestellte Handys zufällig, aber bei jedem Lauf gleich, eintragen, ändern, löschen und abgleichen –
 * mit Funklöchern und verlorenen Antworten. Am Ende müssen alle Handys und der Server dasselbe haben, und nichts, was
 * niemand gelöscht hat, darf fehlen.
 */
class RoundSimulationTest {

    @Test
    fun `vier Handys am Tisch tragen durcheinander in denselben Abend ein`() = runTest {
        val seen = Seen()
        repeat(60) { seed -> Simulation(seed, devices = 4, atTheTable = true, seen).run() }

        // Der Test taugt nur, wenn die schwierigen Fälle auch wirklich vorkommen.
        assertTrue(seen.merges > 300, "zusammengeführt: ${seen.merges}")
        assertTrue(seen.rereads > 30, "jemand war schneller: ${seen.rereads}")
        assertTrue(seen.offline > 100, "ohne Netz: ${seen.offline}")
        assertTrue(seen.lostAnswers > 20, "verlorene Antworten: ${seen.lostAnswers}")
    }

    @Test
    fun `was am Tisch geloescht und von niemandem geaendert wurde, bleibt geloescht`() = runTest {
        // Ohne verlorene Antworten: Nur dann weiß jedes Handy sicher, was schon auf dem Server war.
        val seen = Seen()
        repeat(60) { seed -> Simulation(500 + seed, devices = 4, atTheTable = true, seen, lossy = false).run() }

        assertTrue(seen.rereads > 30, "jemand war schneller: ${seen.rereads}")
        assertTrue(seen.deletedForGood > 300, "gelöscht geblieben: ${seen.deletedForGood}")
    }

    @Test
    fun `drei Handys aendern ueber Wochen Abende, Spieler und Regeln`() = runTest {
        val seen = Seen()
        repeat(60) { seed -> Simulation(1000 + seed, devices = 3, atTheTable = false, seen).run() }

        assertTrue(seen.merges > 100, "zusammengeführt: ${seen.merges}")
        assertTrue(seen.notices > 30, "abgelehnte Spieler: ${seen.notices}")
        assertTrue(seen.rereads > 10, "jemand war schneller: ${seen.rereads}")
        assertTrue(seen.offline > 100, "ohne Netz: ${seen.offline}")
    }
}

/** Was in allen Läufen zusammen vorgekommen ist. */
private class Seen {
    /** Abgleiche, die zugleich etwas geholt und etwas hochgeladen haben. */
    var merges = 0

    /** Abende, die neu gelesen werden mussten, weil jemand anders schneller war. */
    var rereads = 0
    var offline = 0
    var lostAnswers = 0
    var notices = 0

    /** Gelöschte Einträge, die niemand geändert hatte und die am Ende wirklich fehlen. */
    var deletedForGood = 0
}

private class Simulation(
    private val seed: Int,
    devices: Int,
    private val atTheTable: Boolean,
    private val seen: Seen,
    private val lossy: Boolean = true,
) {
    private val random = Random(seed)
    private val clock = TestClock()
    private val server = InMemoryRoundServer(clock)
    private val devices = List(devices) { Device(server, "konto-${it + 1}", clock) }

    /** Alle je angelegten Einträge mit ihrem Abend. */
    private val created = HashMap<EntryId, EveningId>()

    /** Einträge, die irgendwer gelöscht hat: Sie dürfen am Ende fehlen (oder nach einem Konflikt noch da sein). */
    private val deletedEntries = HashSet<EntryId>()

    /** Einträge, die irgendwer geändert hat: Eine Änderung gewinnt gegen das Löschen auf einem anderen Handy. */
    private val editedEntries = HashSet<EntryId>()
    private val deletedEvenings = HashSet<EveningId>()
    private var counter = 0

    suspend fun run() {
        setup()
        repeat(STEPS) {
            clock.advance(random.nextLong(1, 30))
            step(devices.random(random))
        }
        settle()
        check()
        seen.rereads += devices.sumOf { device -> device.session.calls.count { it == "evening" } }
    }

    private suspend fun sync(device: Device, quick: Boolean = false) {
        val lost = device.session.loseNextAnswer
        when (val outcome = device.sync.sync(quick)) {
            is SyncOutcome.Synced -> {
                if (outcome.pulled > 0 && outcome.pushed > 0) seen.merges++
                seen.notices += outcome.notices.size
            }
            SyncOutcome.Offline -> if (lost && !device.session.loseNextAnswer) seen.lostAnswers++ else seen.offline++
            SyncOutcome.Unchanged -> Unit
            else -> throw AssertionError("Lauf $seed, ${device.userId}: $outcome")
        }
    }

    /** Genau bevor [device] das nächste Mal einen Abend hochlädt, trägt ein anderes Handy etwas ein und gleicht ab. */
    private fun interleave(device: Device) {
        val other = devices.filter { it !== device }.random(random)
        device.api.beforeSave = {
            clock.advance(1)
            when (random.nextInt(4)) {
                0 -> editEntry(other)
                1 -> deleteEntry(other)
                else -> addGame(other)
            }
            // Das andere Handy gleicht hier ohne eigene Unterbrechung ab: Sonst riefe es am Ende das wartende Handy.
            other.api.beforeSave = null
            sync(other)
        }
    }

    private suspend fun setup() {
        val first = devices.first()
        first.firstStart()
        first.startEvening("abend-0")
        first.connection.create("Stammtisch", first.userId, PLACEHOLDERS)
        val round = first.roundId()
        for (device in devices.drop(1)) device.connection.join(first.api.createInvite(round), device.userId)
        // Jedes Konto sagt „Das bin ich“ und legt damit seinen Namen fest.
        devices.forEachIndexed { index, device ->
            device.synced()
            device.connection.claim(PLACEHOLDERS[index].id, "Konto ${index + 1}")
        }
        devices.forEach { it.synced() }
    }

    private suspend fun step(device: Device) {
        val dice = random.nextInt(100)
        if (atTheTable) {
            when {
                dice < 45 -> addGame(device)
                dice < 55 -> editEntry(device)
                dice < 62 -> deleteEntry(device)
                dice < 64 -> finishEvening(device)
                dice < 68 -> interleave(device)
                dice < 90 -> sync(device, quick = random.nextInt(4) == 0)
                dice < 96 -> device.session.offline = !device.session.offline
                else -> device.session.loseNextAnswer = lossy
            }
        } else {
            when {
                dice < 22 -> addGame(device)
                dice < 28 -> startEvening(device)
                dice < 34 -> editEntry(device)
                dice < 39 -> deleteEntry(device)
                dice < 42 -> finishEvening(device)
                dice < 45 -> deleteEvening(device)
                dice < 51 -> addGuest(device)
                dice < 56 -> changePlayer(device)
                dice < 60 -> deletePlayer(device)
                dice < 63 -> changeRules(device)
                dice < 66 -> interleave(device)
                dice < 89 -> sync(device, quick = random.nextInt(4) == 0)
                dice < 95 -> device.session.offline = !device.session.offline
                else -> device.session.loseNextAnswer = true
            }
        }
    }

    private suspend fun addGame(device: Device) {
        val evening = device.allEvenings().randomOrNull(random) ?: return
        created[device.addGame(evening.id, won = random.nextBoolean(), laufende = random.nextInt(0, 4))] = evening.id
    }

    private suspend fun startEvening(device: Device) {
        val seats = device.allPlayers().filterNot { it.archived }.shuffled(random).take(4).map { it.id }
        if (seats.size == 4) device.startEvening("abend-${++counter}", seats)
    }

    private suspend fun editEntry(device: Device) {
        val evening = device.allEvenings().randomOrNull(random) ?: return
        val entry = evening.entries.filterIsInstance<GameEntry>().randomOrNull(random) ?: return
        device.editor.update(evening.id) { it.replace(entry.copy(laufende = random.nextInt(4, 9), declarerWon = random.nextBoolean())) }
        editedEntries += entry.id
    }

    private suspend fun deleteEntry(device: Device) {
        val evening = device.allEvenings().randomOrNull(random) ?: return
        val entry = evening.entries.randomOrNull(random) ?: return
        device.editor.update(evening.id) { it.remove(entry.id) }
        deletedEntries += entry.id
    }

    private suspend fun finishEvening(device: Device) {
        val evening = device.allEvenings().randomOrNull(random) ?: return
        device.editor.update(evening.id) {
            if (random.nextBoolean()) it.copy(finishedAt = if (it.finishedAt == null) clock.instant() else null) else it.copy(location = "Wirtshaus ${random.nextInt(3)}")
        }
    }

    private suspend fun deleteEvening(device: Device) {
        val evening = device.allEvenings().randomOrNull(random) ?: return
        device.evenings.delete(evening.id)
        deletedEvenings += evening.id
    }

    private suspend fun addGuest(device: Device) {
        val players = device.allPlayers()
        // Wenige Namen, damit sich zwei Handys auch einmal denselben aussuchen.
        val name = "Gast ${random.nextInt(6)}"
        if (players.any { it.name.equals(name, ignoreCase = true) }) return
        device.players.save(Player(PlayerId("gast-${++counter}"), name, Player.nextColorIndex(players)))
    }

    private suspend fun changePlayer(device: Device) {
        val players = device.allPlayers()
        val player = players.randomOrNull(random) ?: return
        val changed = when (random.nextInt(3)) {
            // Auch Namen, die fest oder schon vergeben sind: Der Server lehnt sie ab.
            0 -> player.copy(name = "Name ${random.nextInt(8)}")
            1 -> player.copy(colorIndex = random.nextInt(0, 40))
            else -> player.copy(archived = !player.archived)
        }
        if (players.none { it.id != player.id && it.name.equals(changed.name, ignoreCase = true) }) device.players.save(changed)
    }

    private suspend fun deletePlayer(device: Device) {
        val evenings = device.allEvenings()
        val unused = device.allPlayers().filter { player -> evenings.none { it.involves(player.id) } }
        device.players.delete((unused.randomOrNull(random) ?: return).id)
    }

    private suspend fun changeRules(device: Device) {
        val rules = if (random.nextBoolean()) RuleSet.sauspiel() else RuleSet.standard().copy(name = "Runde ${random.nextInt(3)}")
        device.settings.update { it.copy(defaultRules = rules) }
    }

    /** Alle wieder am Netz: abgleichen, bis sich nichts mehr bewegt. */
    private suspend fun settle() {
        devices.forEach {
            it.session.offline = false
            it.session.loseNextAnswer = false
            it.api.beforeSave = null
        }
        repeat(MAX_ROUNDS) {
            var moving = false
            for (device in devices) {
                val outcome = device.synced()
                if (outcome.pulled > 0 || outcome.pushed > 0 || outcome.pending > 0) moving = true
            }
            if (!moving) return
        }
        throw AssertionError("Lauf $seed: Der Abgleich kommt nicht zur Ruhe.")
    }

    private suspend fun check() {
        val round = devices.first().roundId()
        val onServer = server.evenings(round).sortedBy { it.id.value }
        val playersOnServer = server.players(round).map { it.toPlayer() }.toSet()
        val rules = devices.first().api.head(round)?.rules
        for (device in devices) {
            val where = "Lauf $seed, ${device.userId}"
            assertEquals(onServer, device.allEvenings(), "$where: Abende")
            assertEquals(playersOnServer, device.allPlayers().toSet(), "$where: Spieler")
            assertEquals(rules, device.settings.settings.first().defaultRules, "$where: Regelwerk")
            assertEquals(0, device.sync.pendingCount(), "$where: Wartendes")
            assertEquals(server.rev(round), device.link()?.cursor, "$where: Stand")
        }
        val present = HashSet<EntryId>()
        for (evening in onServer) {
            val ids = evening.entries.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "Lauf $seed: doppelte Einträge in ${evening.id.value}")
            present += ids
            // Kein Abend nennt jemanden, den es in der Runde nicht gibt.
            val missing = (evening.seating + evening.firstDealer).filter { id -> playersOnServer.none { it.id == id } }
            assertTrue(missing.isEmpty(), "Lauf $seed: ${evening.id.value} nennt unbekannte Spieler $missing")
        }
        // Nichts geht verloren, was niemand gelöscht hat.
        val lost = created.filter { (entry, evening) -> entry !in deletedEntries && evening !in deletedEvenings && entry !in present }.keys
        assertTrue(lost.isEmpty(), "Lauf $seed: verlorene Einträge $lost")
        if (!lossy) {
            // Ohne Änderung von anderer Seite bleibt Gelöschtes gelöscht.
            val gone = deletedEntries - editedEntries
            val back = gone.filter { it in present }
            assertTrue(back.isEmpty(), "Lauf $seed: wieder aufgetauchte Einträge $back")
            seen.deletedForGood += gone.size
        }
        // Namen sind in der Runde eindeutig.
        val names = playersOnServer.map { it.name.lowercase() }
        assertEquals(names.size, names.toSet().size, "Lauf $seed: doppelte Namen")
    }

    private companion object {
        const val STEPS = 160
        const val MAX_ROUNDS = 12
    }
}
