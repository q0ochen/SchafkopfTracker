package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Prüft jede Kennzahl am von Hand durchgerechneten Beispielabend (Tabelle in [SampleEvening]) und danach
 * die Grenzfälle an kleinen Abenden.
 *
 * Wer bei welchem Spiel des Beispielabends auf der Gewinnerseite stand (G) oder verloren hat (V):
 *
 * ```
 * Spiel   1  2  3  4  5  6  7  8  9 10 11 12   Siege
 * A       G  G  G  G  V  V  G  V  V  G  G  V     7
 * B       G  G  V  V  V  G  V  G  V  V  V  V     4
 * C       V  V  V  G  V  G  V  G  G  G  G  G     7
 * D       V  V  V  G  G  V  V  V  V  V  G  V     3
 * ```
 */
class EveningStatisticsTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d
    private val seating = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private var nextId = 0

    private val sample = EveningStatistics.compute(SampleEvening.state)

    private fun id() = EntryId("s${nextId++}")

    /** Die Statistik eines kleinen Abends mit genau diesen Einträgen. */
    private fun compute(vararg entries: EveningEntry): EveningStats =
        EveningStatistics.compute(
            EveningCalculator.compute(
                Evening(
                    id = EveningId("klein"),
                    startedAt = now,
                    seating = seating,
                    firstDealer = a,
                    rules = RuleSet.sauspiel(),
                    entries = entries.toList(),
                ),
            ),
        )

    private fun PlayerId.inSample(): PlayerStats = checkNotNull(sample.statsFor(this))

    private fun EveningStats.of(player: PlayerId): PlayerStats = checkNotNull(statsFor(player))

    private fun rufspiel(declarer: PlayerId, partner: PlayerId, won: Boolean = true) =
        GameEntry(id(), now, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = won)

    private fun solo(declarer: PlayerId, won: Boolean = true) =
        GameEntry(id(), now, GameType.FARBSOLO, declarer = declarer, declarerWon = won)

    /** Je Spieler in Sitzreihenfolge A, B, C, D. */
    private fun <T> perPlayer(stats: EveningStats, value: (PlayerStats) -> T): List<T> = seating.map { value(stats.of(it)) }

    @Test
    fun `die Betraege des Beispielabends stimmen mit der Handrechnung ueberein`() {
        val games = SampleEvening.state.results.filter { it.gameNumber != null }

        assertEquals((1..12).toList(), games.map { it.gameNumber })
        assertEquals(SampleEvening.games.map { it.id }, games.map { it.entry.id })
        assertEquals(
            listOf(20, 60, 80, 100, 160, 160, 320, 320, 80, 160, 200, 200),
            games.map { it.breakdown?.value },
        )
        assertEquals(
            listOf(
                listOf(20, 20, -20, -20),
                listOf(60, 60, -60, -60),
                listOf(240, -80, -80, -80),
                listOf(100, -300, 100, 100),
                listOf(-160, -160, -160, 480),
                listOf(-160, 160, 160, -160),
                listOf(960, -320, -320, -320),
                listOf(-320, 320, 320, -320),
                listOf(-80, -80, 240, -80),
                listOf(160, -160, 160, -160),
                listOf(200, -600, 200, 200),
                listOf(-200, -200, 600, -200),
            ),
            games.map { game -> seating.map { game.amounts.getValue(it) } },
        )
        assertEquals(mapOf(a to 920, b to -1340, c to 1140, d to -720), SampleEvening.state.balances)
    }

    @Test
    fun `die Kennzahlen stehen in Sitzreihenfolge`() {
        assertEquals(SampleEvening.id, sample.eveningId)
        assertEquals(seating, sample.playerStats.map { it.player })
        assertNull(sample.statsFor(SampleEvening.e))
    }

    @Test
    fun `Abende, Spiele, Siege und Saldo`() {
        assertEquals(12, sample.gamesPlayed)
        assertEquals(listOf(1, 1, 1, 1), perPlayer(sample) { it.evenings })
        assertEquals(listOf(12, 12, 12, 12), perPlayer(sample) { it.games })
        // Siege laut der Tabelle im Klassenkommentar.
        assertEquals(listOf(7, 4, 7, 3), perPlayer(sample) { it.gamesWon })
        assertEquals(listOf(5, 8, 5, 9), perPlayer(sample) { it.gamesLost })
        assertEquals(RoleRecord(12, 7), a.inSample().overall)
        // Der Saldo enthält die freie Buchung: A 820 aus Spielen + 100, D −620 aus Spielen − 100.
        assertEquals(listOf(920, -1340, 1140, -720), perPlayer(sample) { it.balance })
    }

    @Test
    fun `Rollen als Ansager, Mitspieler und Gegenspieler`() {
        // A sagt die Spiele 1, 3, 7 an (alle gewonnen), ist Mitspieler in 8 (verloren) und 10 (gewonnen)
        // und Gegenspieler in 2, 4, 11 (gewonnen) sowie 5, 6, 9, 12 (verloren).
        assertEquals(RoleRecord(3, 3), a.inSample().asDeclarer)
        assertEquals(RoleRecord(2, 1), a.inSample().asPartner)
        assertEquals(RoleRecord(7, 3), a.inSample().asOpponent)
        // B sagt 4 (verloren), 6 (gewonnen), 11 (verloren) an, ist Mitspieler in 1 (gewonnen)
        // und Gegenspieler in 2, 8 (gewonnen) sowie 3, 5, 7, 9, 10, 12 (verloren).
        assertEquals(RoleRecord(3, 1), b.inSample().asDeclarer)
        assertEquals(RoleRecord(1, 1), b.inSample().asPartner)
        assertEquals(RoleRecord(8, 2), b.inSample().asOpponent)
        // C sagt 2 (verloren), 9, 10, 12 (gewonnen) an, ist Mitspieler in 6 (gewonnen)
        // und Gegenspieler in 4, 8, 11 (gewonnen) sowie 1, 3, 5, 7 (verloren).
        assertEquals(RoleRecord(4, 3), c.inSample().asDeclarer)
        assertEquals(RoleRecord(1, 1), c.inSample().asPartner)
        assertEquals(RoleRecord(7, 3), c.inSample().asOpponent)
        // D sagt 5 (gewonnen) und 8 (verloren) an, ist Mitspieler in 2 (verloren)
        // und Gegenspieler in 4, 11 (gewonnen) sowie 1, 3, 6, 7, 9, 10, 12 (verloren).
        assertEquals(RoleRecord(2, 1), d.inSample().asDeclarer)
        assertEquals(RoleRecord(1, 0), d.inSample().asPartner)
        assertEquals(RoleRecord(9, 2), d.inSample().asOpponent)

        for (player in seating) {
            val stats = player.inSample()
            assertEquals(stats.games, stats.asDeclarer.played + stats.asPartner.played + stats.asOpponent.played)
            assertEquals(stats.gamesWon, stats.asDeclarer.won + stats.asPartner.won + stats.asOpponent.won)
        }
    }

    @Test
    fun `Ueber Kreuz zaehlt in keiner Ansager-Rolle`() {
        // Der Abend nach dem Regelwerk der Runde (Tabelle in RoundEvenings): Spiel 2 und Spiel 8 sind Über Kreuz.
        val stats = EveningStatistics.compute(EveningCalculator.compute(RoundEvenings.evening))
        val players = listOf(RoundEvenings.a, RoundEvenings.b, RoundEvenings.c, RoundEvenings.d)
        fun <T> each(value: (PlayerStats) -> T): List<T> = players.map { value(checkNotNull(stats.statsFor(it))) }

        assertEquals(listOf(14, 14, 14, 14), each { it.games })
        assertEquals(listOf(9, 6, 9, 3), each { it.gamesWon })
        // A sagt 1, 6 (gewonnen) und 13 (verloren) an, ist Mitspieler in 14 und Gegenspieler in 3, 7, 9, 12
        // (verloren) sowie 4, 5, 10, 11 (gewonnen).
        // B sagt 3, 9, 12 (gewonnen) und 10 (verloren) an, ist Mitspieler in 1 (gewonnen), 11 und 13 (verloren)
        // und Gegenspieler in 4, 5 (gewonnen) sowie 6, 7, 14 (verloren).
        // C sagt 4 (verloren), 7 und 14 (gewonnen) an, ist Mitspieler in 9 und Gegenspieler in 5, 10, 11, 13
        // (gewonnen) sowie 1, 3, 6, 12 (verloren).
        // D sagt 5 und 11 an (beide verloren), ist Mitspieler in 4 (verloren) und 6 (gewonnen) und Gegenspieler in
        // 10 und 13 (gewonnen) sowie 1, 3, 7, 9, 12, 14 (verloren).
        assertEquals(listOf(RoleRecord(3, 2), RoleRecord(4, 3), RoleRecord(3, 2), RoleRecord(2, 0)), each { it.asDeclarer })
        assertEquals(listOf(RoleRecord(1, 1), RoleRecord(3, 1), RoleRecord(1, 1), RoleRecord(2, 1)), each { it.asPartner })
        assertEquals(listOf(RoleRecord(8, 4), RoleRecord(5, 2), RoleRecord(8, 4), RoleRecord(8, 2)), each { it.asOpponent })
        // Die beiden Über-Kreuz-Spiele fehlen in den Rollen: je Spieler 12 statt 14 Spiele.
        assertEquals(listOf(12, 12, 12, 12), each { it.asDeclarer.played + it.asPartner.played + it.asOpponent.played })
        // A und C gewinnen beide Über-Kreuz-Spiele; diese Siege stehen nur in der Gesamtzahl.
        assertEquals(listOf(7, 6, 7, 3), each { it.asDeclarer.won + it.asPartner.won + it.asOpponent.won })
        assertEquals(List(4) { RoleRecord(0, 0) }, each { it.declarerByType.getValue(GameType.UEBER_KREUZ) })
        assertEquals(
            listOf(RoleRecord(3, 2), RoleRecord(1, 1), RoleRecord(2, 1), RoleRecord(1, 0)),
            each { it.declarerByType.getValue(GameType.RUFSPIEL) },
        )
    }

    @Test
    fun `ein regelwidrig beim Rufspiel eingetragenes Tout zaehlt wie gebucht beim Ansager`() {
        // Tout gibt es nur im Einzelspiel. Steht es trotzdem bei einem Rufspiel, wird es angezeigt und mit dem
        // Tout-Faktor gebucht (20 × 2 = 40); die Statistik zählt dann, was gebucht wurde.
        val rufspielTout = GameEntry(
            id(), now, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true,
            announcement = Announcement.TOUT,
        )
        val state = EveningCalculator.compute(
            Evening(EveningId("klein"), now, seating = seating, firstDealer = a, rules = RuleSet.sauspiel(), entries = listOf(rufspielTout)),
        )
        val stats = EveningStatistics.compute(state)

        assertEquals(listOf(40, 40, -40, -40), seating.map { state.balances.getValue(it) })
        assertEquals(RoleRecord(1, 1), stats.of(a).toutPlayed)
        assertEquals(RoleRecord(0, 0), stats.of(b).toutPlayed)
    }

    @Test
    fun `ein bei Ueber Kreuz eingetragenes Tout zaehlt nicht als Tout`() {
        // So etwas lässt die Eingabe nicht zu; aus einer eingelesenen Sicherung kann es trotzdem kommen.
        val kreuz = GameEntry(
            id(), now, GameType.UEBER_KREUZ, declarer = a, partner = c, declarerWon = true,
            announcement = Announcement.TOUT,
        )
        val stats = compute(kreuz, solo(a))

        assertEquals(List(4) { RoleRecord(0, 0) }, perPlayer(stats) { it.toutPlayed })
        assertEquals(RoleRecord(1, 1), stats.of(a).asDeclarer)
        assertEquals(listOf(2, 2, 2, 2), perPlayer(stats) { it.games })
    }

    @Test
    fun `Ansager je Spielart`() {
        val none = RoleRecord(0, 0)

        // Jede Spielart ist enthalten; was hier nicht genannt ist, wurde nie angesagt.
        fun byType(rufspiel: RoleRecord, farbsolo: RoleRecord, wenz: RoleRecord, geier: RoleRecord) =
            GameType.entries.associateWith { none } + mapOf(
                GameType.RUFSPIEL to rufspiel,
                GameType.FARBSOLO to farbsolo,
                GameType.WENZ to wenz,
                GameType.GEIER to geier,
            )

        // A: Rufspiel 1, Farbsolo 3 und 7 (das Tout zählt als Farbsolo).
        assertEquals(byType(RoleRecord(1, 1), RoleRecord(2, 2), none, none), a.inSample().declarerByType)
        // B: Rufspiel 6 gewonnen, Wenz 4 und Geier 11 verloren.
        assertEquals(byType(RoleRecord(1, 1), none, RoleRecord(1, 0), RoleRecord(1, 0)), b.inSample().declarerByType)
        // C: Rufspiel 2 verloren und 10 gewonnen, Farbsolo 12 und Wenz 9 gewonnen.
        assertEquals(byType(RoleRecord(2, 1), RoleRecord(1, 1), RoleRecord(1, 1), none), c.inSample().declarerByType)
        // D: Rufspiel 8 verloren, Geier 5 gewonnen.
        assertEquals(byType(RoleRecord(1, 0), none, none, RoleRecord(1, 1)), d.inSample().declarerByType)
        assertEquals(GameType.entries, a.inSample().declarerByType.keys.toList())
    }

    @Test
    fun `Klopfen - wie oft, wie oft danach verloren und was es gekostet hat`() {
        // A klopft in Spiel 6 und verliert 160: Wirkung −160 / 2 = −80.
        assertEquals(RoleRecord(1, 0), a.inSample().klopferGiven)
        assertEquals(-80, a.inSample().klopferEffect)
        // B klopft in Spiel 4 (−300) und Spiel 11 (−600): Wirkung (−300 − 600) / 2 = −450.
        assertEquals(RoleRecord(2, 0), b.inSample().klopferGiven)
        assertEquals(-450, b.inSample().klopferEffect)
        // C klopft in Spiel 2 (−60) und Spiel 10 (+160): Wirkung (−60 + 160) / 2 = +50.
        assertEquals(RoleRecord(2, 1), c.inSample().klopferGiven)
        assertEquals(50, c.inSample().klopferEffect)
        // D klopft in Spiel 10 und verliert 160: Wirkung −80.
        assertEquals(RoleRecord(1, 0), d.inSample().klopferGiven)
        assertEquals(-80, d.inSample().klopferEffect)

        assertEquals(listOf(1, 2, 2, 1), perPlayer(sample) { it.klopferCount })
        assertEquals(listOf(1, 2, 1, 1), perPlayer(sample) { it.klopferLost })
    }

    @Test
    fun `die Geldwirkung des Klopfens entspricht der Nachrechnung ohne den eigenen Klopfer`() {
        val rules = SampleEvening.evening.rules
        for (player in seating) {
            var expected = 0
            for (result in SampleEvening.state.results) {
                val entry = result.entry as? GameEntry ?: continue
                if (player !in entry.klopfer) continue
                val facts = with(EveningCalculator) { entry.toFacts(seating, result.bockExponent) }
                val withoutOwnKlopfer = ScoreCalculator.amounts(facts.copy(klopferCount = facts.klopferCount - 1), rules)
                expected += result.amounts.getValue(player) - withoutOwnKlopfer.getValue(player)
            }
            assertEquals(expected, player.inSample().klopferEffect)
        }
    }

    @Test
    fun `Kontra und Retour zaehlen fuer den, der sie gegeben hat`() {
        // Spiel 6: Kontra von D, D verliert. Spiel 8: Kontra von B (gewinnt), Retour von D (verliert).
        // Spiel 12: Kontra von A, A verliert.
        assertEquals(
            listOf(RoleRecord(1, 0), RoleRecord(1, 1), RoleRecord(0, 0), RoleRecord(1, 0)),
            perPlayer(sample) { it.kontraGiven },
        )
        assertEquals(
            listOf(RoleRecord(0, 0), RoleRecord(0, 0), RoleRecord(0, 0), RoleRecord(1, 0)),
            perPlayer(sample) { it.retourGiven },
        )
    }

    @Test
    fun `Schneider und Schwarz auf Gewinner- und Verliererseite`() {
        // Schneider: Spiel 2 (A, B gewinnen), Spiel 5 (D gewinnt), Spiel 9 (C gewinnt).
        // Schwarz: Spiel 8 (B, C gewinnen; A, D verlieren). Spiel 7 ist ein Tout, dort zählt die Stufe nicht.
        assertEquals(listOf(1, 1, 1, 1), perPlayer(sample) { it.schneiderWon })
        assertEquals(listOf(2, 2, 2, 2), perPlayer(sample) { it.schneiderLost })
        assertEquals(listOf(0, 1, 1, 0), perPlayer(sample) { it.schwarzWon })
        assertEquals(listOf(1, 0, 0, 1), perPlayer(sample) { it.schwarzLost })
    }

    @Test
    fun `Tout zaehlt beim Ansager`() {
        assertEquals(
            listOf(RoleRecord(1, 1), RoleRecord(0, 0), RoleRecord(0, 0), RoleRecord(0, 0)),
            perPlayer(sample) { it.toutPlayed },
        )
    }

    @Test
    fun `groesster Gewinn und groesster Verlust mit Abend und Spielnummer`() {
        fun amount(game: GameEntry, number: Int, cents: Int) = GameAmount(SampleEvening.id, game.id, number, cents)

        assertEquals(amount(SampleEvening.g7, 7, 960), a.inSample().biggestWin)
        assertEquals(amount(SampleEvening.g8, 8, -320), a.inSample().biggestLoss)
        assertEquals(amount(SampleEvening.g8, 8, 320), b.inSample().biggestWin)
        assertEquals(amount(SampleEvening.g11, 11, -600), b.inSample().biggestLoss)
        assertEquals(amount(SampleEvening.g12, 12, 600), c.inSample().biggestWin)
        assertEquals(amount(SampleEvening.g7, 7, -320), c.inSample().biggestLoss)
        assertEquals(amount(SampleEvening.g5, 5, 480), d.inSample().biggestWin)
        // D verliert in Spiel 7 und in Spiel 8 je 320: Das frühere Spiel wird genannt.
        assertEquals(amount(SampleEvening.g7, 7, -320), d.inSample().biggestLoss)
    }

    @Test
    fun `laengste Serien - andere Eintraege unterbrechen nicht`() {
        // A gewinnt 1 bis 4 (dazwischen wird einmal zusammengeworfen), C gewinnt 8 bis 12 (dazwischen Bockrunde
        // und zusammengeworfen), D verliert 6 bis 10 (dazwischen freie Buchung und Bockrunde).
        assertEquals(listOf(4, 2, 5, 2), perPlayer(sample) { it.longestWinStreak })
        assertEquals(listOf(2, 4, 3, 5), perPlayer(sample) { it.longestLoseStreak })
    }

    @Test
    fun `sechs Nixer zaehlen bei dem, der sie hatte`() {
        assertEquals(listOf(0, 0, 1, 1), perPlayer(sample) { it.nixerTriggers })
        assertEquals(2, sample.bockTriggerCount)
    }

    @Test
    fun `eine von Hand ausgeloeste Bockrunde zaehlt als Bockrunde, aber nicht als sechs Nixer`() {
        // Bei der ersten Bockrunde steht zwar ein Spieler dabei, sie wurde aber nicht wegen 6 Nixern ausgelöst.
        val stats = compute(
            BockTriggerEntry(id(), now, BockReason.MANUAL, player = c),
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = d),
            rufspiel(a, b),
        )

        assertEquals(2, stats.bockTriggerCount)
        assertEquals(listOf(0, 0, 0, 1), perPlayer(stats) { it.nixerTriggers })
        // Angehängte Bockrunden: Das eine Spiel ist ein Bockspiel.
        assertEquals(1, stats.bockGamesCount)
    }

    @Test
    fun `Abendsieg, letzter Platz und Abendsaldo`() {
        // Endstand: C +1140, A +920, D −720, B −1340.
        assertEquals(listOf(0, 0, 1, 0), perPlayer(sample) { it.eveningWins })
        assertEquals(listOf(0, 1, 0, 0), perPlayer(sample) { it.eveningLastPlaces })
        for (player in seating) {
            val expected = EveningBalance(SampleEvening.id, SampleEvening.state.balances.getValue(player))
            assertEquals(expected, player.inSample().bestEvening)
            assertEquals(expected, player.inSample().worstEvening)
        }
    }

    @Test
    fun `Spielarten-Verteilung`() {
        // Rufspiel: 1, 2, 6, 8, 10. Farbsolo: 3, 7, 12. Wenz: 4, 9. Geier: 5, 11.
        val played = mapOf(GameType.RUFSPIEL to 5, GameType.FARBSOLO to 3, GameType.WENZ to 2, GameType.GEIER to 2)
        assertEquals(GameType.entries.associateWith { 0 } + played, sample.gameTypeCounts)
        assertEquals(GameType.entries, sample.gameTypeCounts.keys.toList())
    }

    @Test
    fun `Bockspiele und zusammengeworfene Blaetter`() {
        // Bockspiele: 5 bis 8 aus der ersten Bockrunde, 10 bis 12 aus der zweiten.
        assertEquals(7, sample.bockGamesCount)
        assertEquals(2, sample.thrownInCount)
    }

    @Test
    fun `Fuehrungswechsel`() {
        // Alleiniger Erster nach jedem Spiel: 1 und 2 keiner (A und B gleichauf), 3 und 4 A, 5 und 6 D,
        // 7 bis 11 A, 12 C. Wechsel: A nach D, D nach A, A nach C.
        assertEquals(3, sample.leadChanges)
    }

    @Test
    fun `teuerstes Spiel - bei Gleichstand das fruehere`() {
        // Spiel 7 und Spiel 8 haben beide den Wert 320.
        assertEquals(ExpensiveGame(SampleEvening.id, SampleEvening.g7.id, 7, 320), sample.mostExpensiveGame)
    }

    @Test
    fun `Umsatz ist die Summe aller positiven Betraege`() {
        // Spiele 1 bis 6: 40 + 120 + 240 + 300 + 480 + 320 = 1500. Freie Buchung: 100.
        // Spiele 7 bis 12: 960 + 640 + 240 + 320 + 600 + 600 = 3360. Zusammen 4960.
        assertEquals(4960, sample.turnover)
    }

    @Test
    fun `leerer Abend`() {
        val stats = compute()

        assertEquals(0, stats.gamesPlayed)
        assertEquals(GameType.entries.associateWith { 0 }, stats.gameTypeCounts)
        assertEquals(0, stats.bockGamesCount)
        assertEquals(0, stats.bockTriggerCount)
        assertEquals(0, stats.thrownInCount)
        assertEquals(0, stats.leadChanges)
        assertNull(stats.mostExpensiveGame)
        assertEquals(0, stats.turnover)
        assertEquals(seating, stats.playerStats.map { it.player })

        val none = RoleRecord(0, 0)
        for (player in seating) {
            val expected = PlayerStats(
                player = player,
                evenings = 0,
                games = 0,
                balance = 0,
                gamesWon = 0,
                asDeclarer = none,
                asPartner = none,
                asOpponent = none,
                declarerByType = GameType.entries.associateWith { none },
                klopferGiven = none,
                klopferEffect = 0,
                kontraGiven = none,
                retourGiven = none,
                schneiderWon = 0,
                schneiderLost = 0,
                schwarzWon = 0,
                schwarzLost = 0,
                toutPlayed = none,
                biggestWin = null,
                biggestLoss = null,
                longestWinStreak = 0,
                longestLoseStreak = 0,
                nixerTriggers = 0,
                eveningWins = 0,
                eveningLastPlaces = 0,
                bestEvening = null,
                worstEvening = null,
            )
            assertEquals(expected, stats.of(player))
        }
    }

    @Test
    fun `Abend ohne Spiel zeigt freie Buchungen, zaehlt aber nicht als Abend`() {
        val stats = compute(
            ThrownInEntry(id(), now),
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = c),
            ManualEntry(id(), now, mapOf(a to 100, b to -100), "Wette"),
        )

        assertEquals(0, stats.gamesPlayed)
        assertEquals(1, stats.thrownInCount)
        assertEquals(1, stats.bockTriggerCount)
        assertEquals(listOf(0, 0, 1, 0), perPlayer(stats) { it.nixerTriggers })
        assertEquals(100, stats.turnover)
        assertEquals(listOf(100, -100, 0, 0), perPlayer(stats) { it.balance })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.evenings })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.eveningWins })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.eveningLastPlaces })
        assertNull(stats.of(a).bestEvening)
        assertNull(stats.of(b).worstEvening)
    }

    @Test
    fun `stehen alle bei null, gibt es weder Sieger noch Letzten`() {
        // A und B gewinnen ein Rufspiel (je +20), danach gewinnen C und D eines (je +20): alle wieder bei 0.
        val first = rufspiel(a, b)
        val stats = compute(first, rufspiel(c, d))

        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.balance })
        assertEquals(listOf(1, 1, 1, 1), perPlayer(stats) { it.evenings })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.eveningWins })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.eveningLastPlaces })
        assertEquals(listOf(1, 1, 1, 1), perPlayer(stats) { it.gamesWon })
        assertEquals(EveningBalance(EveningId("klein"), 0), stats.of(a).bestEvening)
        assertEquals(EveningBalance(EveningId("klein"), 0), stats.of(a).worstEvening)
        assertEquals(0, stats.leadChanges)
        // Beide Spiele sind 20 wert: Das frühere ist das teuerste.
        assertEquals(ExpensiveGame(EveningId("klein"), first.id, 1, 20), stats.mostExpensiveGame)
        assertEquals(80, stats.turnover)
    }

    @Test
    fun `geteilter erster und geteilter letzter Platz zaehlen fuer alle Beteiligten`() {
        // Ein Rufspiel: A und B stehen bei +20, C und D bei −20.
        val pairs = compute(rufspiel(a, b))
        assertEquals(listOf(1, 1, 0, 0), perPlayer(pairs) { it.eveningWins })
        assertEquals(listOf(0, 0, 1, 1), perPlayer(pairs) { it.eveningLastPlaces })
        assertEquals(0, pairs.leadChanges)

        // Ein verlorenes Solo: A steht bei −150, die drei anderen bei +50.
        val lostSolo = compute(solo(a, won = false))
        assertEquals(listOf(0, 1, 1, 1), perPlayer(lostSolo) { it.eveningWins })
        assertEquals(listOf(1, 0, 0, 0), perPlayer(lostSolo) { it.eveningLastPlaces })
    }

    @Test
    fun `Fuehrungswechsel zaehlen nur zwischen alleinigen Ersten`() {
        // Jedes gewonnene Solo bringt dem Ansager 150 und kostet die anderen je 50.
        // Stand A/B nach jedem Spiel: 150/−50 (A führt), 100/100 (keiner), 50/250 (B führt: 1. Wechsel),
        // 200/200 (keiner), 150/350 (wieder B: kein Wechsel), 300/300 (keiner), 450/250 (A führt: 2. Wechsel).
        val stats = compute(solo(a), solo(b), solo(b), solo(a), solo(b), solo(a), solo(a))
        assertEquals(2, stats.leadChanges)
    }

    @Test
    fun `eine freie Buchung nach dem letzten Spiel kann die Fuehrung noch wechseln`() {
        // Nach dem ersten Solo führt A mit 150. Nach dem zweiten stünde A bei 300, die Buchung danach zählt aber
        // wie im Verlauf zum Punkt des letzten Spiels: A −200, C +400. C ist jetzt alleiniger Erster: ein Wechsel.
        val stats = compute(solo(a), solo(a), ManualEntry(id(), now, mapOf(a to -500, c to 500), "Wette"))
        assertEquals(listOf(-200, -100, 400, -100), perPlayer(stats) { it.balance })
        assertEquals(1, stats.leadChanges)
        assertEquals(listOf(0, 0, 1, 0), perPlayer(stats) { it.eveningWins })
        assertEquals(listOf(1, 0, 0, 0), perPlayer(stats) { it.eveningLastPlaces })
    }

    @Test
    fun `eine freie Buchung vor dem ersten Spiel ist kein eigener Punkt im Verlauf`() {
        // Die Buchung bringt C nach vorn, gezählt wird aber erst nach einem gespielten Spiel: Dann steht A mit 150
        // vor C mit 100 − 50 = 50. A ist der erste alleinige Erste des Abends, also gibt es keinen Wechsel.
        val stats = compute(ManualEntry(id(), now, mapOf(c to 100, d to -100), "Wette"), solo(a))
        assertEquals(listOf(150, -50, 50, -150), perPlayer(stats) { it.balance })
        assertEquals(0, stats.leadChanges)
    }

    @Test
    fun `Sie und verlorenes Tout zaehlen als Tout, aber nie als Schwarz`() {
        val sie = GameEntry(
            id(), now, GameType.FARBSOLO, declarer = a, declarerWon = true,
            level = ResultLevel.SCHWARZ, announcement = Announcement.SIE,
        )
        val lostTout = GameEntry(
            id(), now, GameType.WENZ, declarer = a, declarerWon = false,
            level = ResultLevel.SCHNEIDER, announcement = Announcement.TOUT,
        )
        val stats = compute(sie, lostTout)

        assertEquals(RoleRecord(2, 1), stats.of(a).toutPlayed)
        assertEquals(RoleRecord(0, 0), stats.of(b).toutPlayed)
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.schwarzWon + it.schwarzLost })
        assertEquals(listOf(0, 0, 0, 0), perPlayer(stats) { it.schneiderWon + it.schneiderLost })
        assertEquals(RoleRecord(1, 1), stats.of(a).declarerByType.getValue(GameType.FARBSOLO))
        assertEquals(RoleRecord(1, 0), stats.of(a).declarerByType.getValue(GameType.WENZ))
    }

    @Test
    fun `Kontra ohne Angabe, wer es gegeben hat, zaehlt bei niemandem`() {
        val anonymous = GameEntry(
            id(), now, GameType.FARBSOLO, declarer = a, declarerWon = true, kontra = KontraLevel.RETOUR,
        )
        // Hier ist noch ein Name eingetragen, obwohl gar kein Kontra gegeben wurde.
        val stale = GameEntry(
            id(), now, GameType.FARBSOLO, declarer = a, declarerWon = true, kontraBy = b, retourBy = a,
        )
        val stats = compute(anonymous, stale)

        assertEquals(List(4) { RoleRecord(0, 0) }, perPlayer(stats) { it.kontraGiven })
        assertEquals(List(4) { RoleRecord(0, 0) }, perPlayer(stats) { it.retourGiven })
    }

    @Test
    fun `ein Spiel, das sich nicht abrechnen laesst, zaehlt in der Statistik nicht mit`() {
        val unknownDeclarer = GameEntry(id(), now, GameType.WENZ, declarer = PlayerId("X"), declarerWon = true)
        val missingPartner = GameEntry(id(), now, GameType.RUFSPIEL, declarer = a, declarerWon = true)
        val valid = rufspiel(a, b)
        val state = EveningCalculator.compute(
            Evening(
                id = EveningId("klein"),
                startedAt = now,
                seating = seating,
                firstDealer = a,
                rules = RuleSet.sauspiel(),
                entries = listOf(unknownDeclarer, missingPartner, valid),
            ),
        )
        val stats = EveningStatistics.compute(state)

        // Der Abend selbst nummeriert alle drei Einträge; die Statistik zählt nur das abgerechnete Spiel.
        assertEquals(3, state.gamesPlayed)
        assertEquals(1, stats.gamesPlayed)
        assertEquals(listOf(1, 1, 1, 1), perPlayer(stats) { it.games })
        assertEquals(listOf(1, 1, 0, 0), perPlayer(stats) { it.gamesWon })
        assertEquals(GameType.entries.associateWith { 0 } + (GameType.RUFSPIEL to 1), stats.gameTypeCounts)
        assertEquals(ExpensiveGame(EveningId("klein"), valid.id, 3, 20), stats.mostExpensiveGame)
        assertEquals(GameAmount(EveningId("klein"), valid.id, 3, 20), stats.of(a).biggestWin)
    }

    @Test
    fun `eine Quote nennt Zaehler und Nenner und ist ohne Faelle leer`() {
        assertNull(RoleRecord(0, 0).rate)
        assertEquals(0.75, RoleRecord(4, 3).rate)
        assertEquals(0.0, RoleRecord(2, 0).rate)
        assertEquals(1.0, RoleRecord(2, 2).rate)
        assertEquals(1, RoleRecord(4, 3).lost)
    }

    @Test
    fun `die Kennzahlen zufaelliger Abende passen zusammen`() {
        // Jede Kennzahl wird hier auf einem zweiten Weg nachgerechnet, meist direkt aus den gebuchten Beträgen.
        val random = Random(20261115)
        repeat(300) { round ->
            val state = EveningCalculator.compute(RandomEvenings.evening(random, "zufall-$round", seating))
            val stats = EveningStatistics.compute(state)
            val games = state.results.filter { it.entry is GameEntry }
            val entries = games.map { it.entry as GameEntry }

            assertEquals(games.size, stats.gamesPlayed)
            assertEquals(GameType.entries.associateWith { type -> entries.count { it.type == type } }, stats.gameTypeCounts)
            assertEquals(games.count { it.bockExponent > 0 }, stats.bockGamesCount)
            assertEquals(games.maxOfOrNull { it.breakdown?.value ?: 0 }, stats.mostExpensiveGame?.value)
            assertEquals(state.results.sumOf { result -> result.amounts.values.filter { it > 0 }.sum() }, stats.turnover)
            assertEquals(leadChangesInRankSeries(Progression.rankSeries(state)), stats.leadChanges)

            // Jedes Spiel außer Über Kreuz hat genau einen Ansager, jedes Rufspiel genau einen Mitspieler.
            // Bei Über Kreuz sagt niemand an: Diese Spiele zählen in keiner der drei Rollen.
            val ueberKreuz = games.filter { (it.entry as GameEntry).type == GameType.UEBER_KREUZ }
            assertEquals(games.size - ueberKreuz.size, seating.sumOf { stats.of(it).asDeclarer.played })
            assertEquals(entries.count { it.type == GameType.RUFSPIEL }, seating.sumOf { stats.of(it).asPartner.played })

            val best = state.balances.values.max()
            val worst = state.balances.values.min()
            for (player in seating) {
                val own = stats.of(player)
                val amounts = games.map { it.amounts.getValue(player) }
                // Im Tarif 10-20-50 ist kein Spiel 0 wert: Wer gewinnt, bekommt Geld, wer verliert, zahlt.
                val outcomes = amounts.map { it > 0 }
                val balance = state.balances.getValue(player)

                assertEquals(balance, own.balance)
                assertEquals(games.size, own.games)
                assertEquals(if (games.isEmpty()) 0 else 1, own.evenings)
                assertEquals(outcomes.count { it }, own.gamesWon)
                assertEquals(
                    own.games - ueberKreuz.size,
                    own.asDeclarer.played + own.asPartner.played + own.asOpponent.played,
                )
                assertEquals(
                    own.gamesWon - ueberKreuz.count { it.amounts.getValue(player) > 0 },
                    own.asDeclarer.won + own.asPartner.won + own.asOpponent.won,
                )
                val declared = entries.filter { it.declarer == player && it.type != GameType.UEBER_KREUZ }
                assertEquals(RoleRecord(declared.size, declared.count { it.declarerWon }), own.asDeclarer)
                assertEquals(RoleRecord(0, 0), own.declarerByType.getValue(GameType.UEBER_KREUZ))
                assertEquals(
                    own.asDeclarer,
                    RoleRecord(own.declarerByType.values.sumOf { it.played }, own.declarerByType.values.sumOf { it.won }),
                )
                assertEquals(entries.count { player in it.klopfer }, own.klopferCount)
                assertEquals(
                    games.filter { player in (it.entry as GameEntry).klopfer }.sumOf { it.amounts.getValue(player) },
                    2 * own.klopferEffect,
                )
                assertEquals(entries.count { it.kontra != KontraLevel.NONE && it.kontraBy == player }, own.kontraGiven.played)
                assertEquals(entries.count { it.kontra == KontraLevel.RETOUR && it.retourBy == player }, own.retourGiven.played)
                // Bei jedem Spiel mit gewerteter Stufe steht jeder Spieler auf einer der beiden Seiten.
                assertEquals(
                    entries.count { it.level == ResultLevel.SCHNEIDER && it.announcement == Announcement.NONE },
                    own.schneiderWon + own.schneiderLost,
                )
                assertEquals(
                    entries.count { it.level == ResultLevel.SCHWARZ && it.announcement == Announcement.NONE },
                    own.schwarzWon + own.schwarzLost,
                )
                assertEquals(
                    entries.count { it.declarer == player && it.announcement != Announcement.NONE },
                    own.toutPlayed.played,
                )
                assertEquals(amounts.filter { it > 0 }.maxOrNull(), own.biggestWin?.amount)
                assertEquals(amounts.filter { it < 0 }.minOrNull(), own.biggestLoss?.amount)
                assertEquals(longestRun(outcomes, wanted = true), own.longestWinStreak)
                assertEquals(longestRun(outcomes, wanted = false), own.longestLoseStreak)
                assertEquals(
                    state.evening.entries.count {
                        it is BockTriggerEntry && it.reason == BockReason.SECHS_NIXER && it.player == player
                    },
                    own.nixerTriggers,
                )
                val decided = games.isNotEmpty() && best != worst
                assertEquals(if (decided && balance == best) 1 else 0, own.eveningWins)
                assertEquals(if (decided && balance == worst) 1 else 0, own.eveningLastPlaces)
                assertEquals(if (games.isEmpty()) null else balance, own.bestEvening?.balance)
                assertEquals(if (games.isEmpty()) null else balance, own.worstEvening?.balance)
            }
        }
    }

    /** Längste Folge gleicher Ausgänge hintereinander. */
    private fun longestRun(outcomes: List<Boolean>, wanted: Boolean): Int {
        var longest = 0
        var current = 0
        for (outcome in outcomes) {
            current = if (outcome == wanted) current + 1 else 0
            longest = maxOf(longest, current)
        }
        return longest
    }

    /** Führungswechsel, abgelesen am Platzverlauf: Wie oft ist ein anderer Spieler allein auf Platz 1 als zuvor? */
    private fun leadChangesInRankSeries(ranks: Map<PlayerId, List<Int>>): Int {
        var changes = 0
        var lastLeader: PlayerId? = null
        for (index in ranks.values.first().indices) {
            val leader = ranks.filterValues { it[index] == 1 }.keys.singleOrNull() ?: continue
            if (lastLeader != null && lastLeader != leader) changes += 1
            lastLeader = leader
        }
        return changes
    }
}
