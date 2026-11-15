package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Statistik über mehrere Abende: der Beispielabend (A, B, C, D; beendet) und der zweite, kurze Abend eine Woche
 * später (A, B, C und Emil statt Dora; läuft noch). Beide Tabellen stehen in [SampleEvening].
 *
 * Wer am zweiten Abend bei welchem Spiel gewonnen (G) oder verloren (V) hat:
 *
 * ```
 * Spiel   1  2  3  4   Siege
 * A       G  G  V  G     3
 * B       V  V  V  G     1
 * C       V  V  V  V     0
 * E       G  G  G  V     3
 * ```
 */
class HistoryStatisticsTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d
    private val e = SampleEvening.e
    private val first = SampleEvening.id
    private val second = SampleEvening.secondId
    private val none = RoleRecord(0, 0)

    /** Ein Abend ohne gespieltes Spiel, zeitlich vor den beiden anderen; Franz (F) sitzt nur hier am Tisch. */
    private val withoutGames = Evening(
        id = EveningId("abend-0"),
        startedAt = Instant.parse("2026-11-07T18:30:00Z"),
        seating = listOf(a, b, c, PlayerId("F")),
        firstDealer = a,
        rules = RuleSet.sauspiel(),
        entries = listOf(
            ThrownInEntry(EntryId("x1"), Instant.parse("2026-11-07T18:40:00Z")),
            ManualEntry(EntryId("x2"), Instant.parse("2026-11-07T18:45:00Z"), mapOf(a to 500, b to -500), "Wette"),
        ),
    )

    // Die Abende kommen wie aus der Datenhaltung: der neueste zuerst.
    private val history = HistoryStatistics.compute(listOf(SampleEvening.secondEvening, SampleEvening.evening, withoutGames))

    private fun of(player: PlayerId): PlayerStats = checkNotNull(history.statsFor(player))

    /** Jede Spielart ist enthalten; was hier nicht genannt ist, wurde nie angesagt. */
    private fun byType(rufspiel: RoleRecord, farbsolo: RoleRecord, wenz: RoleRecord, geier: RoleRecord) =
        GameType.entries.associateWith { none } + mapOf(
            GameType.RUFSPIEL to rufspiel,
            GameType.FARBSOLO to farbsolo,
            GameType.WENZ to wenz,
            GameType.GEIER to geier,
        )

    private fun oneGameEvening(id: String, startedAt: String, seating: List<PlayerId>): Evening {
        val start = Instant.parse(startedAt)
        return Evening(
            id = EveningId(id),
            startedAt = start,
            seating = seating,
            firstDealer = seating.first(),
            rules = RuleSet.sauspiel(),
            entries = listOf(
                GameEntry(EntryId("$id-spiel"), start, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true),
            ),
        )
    }

    @Test
    fun `Abende und Spiele werden gezaehlt, Abende ohne Spiel nicht`() {
        assertEquals(2, history.eveningCount)
        assertEquals(16, history.gameCount)
        // Franz saß nur an dem Abend ohne Spiel am Tisch: Er steht in der Tabelle, hat aber keinen Abend gespielt.
        val franz = checkNotNull(history.statsFor(PlayerId("F")))
        assertEquals(0, franz.evenings)
        assertEquals(0, franz.games)
        assertEquals(0, franz.balance)
        assertNull(franz.bestEvening)
        assertNull(history.statsFor(PlayerId("X")))
    }

    @Test
    fun `Spieler stehen nach Saldo absteigend`() {
        // A 920 + 40, B −1340 − 60, C 1140 − 140, D −720 (nur erster Abend), E 160 (nur zweiter Abend).
        // Dazu die freie Buchung vom Abend ohne Spiel: A +500, B −500. Kein gebuchter Betrag geht verloren.
        val franz = PlayerId("F")
        assertEquals(listOf(a, c, e, franz, d, b), history.playerStats.map { it.player })
        assertEquals(listOf(1460, 1000, 160, 0, -720, -1900), history.playerStats.map { it.balance })
        assertEquals(0, history.playerStats.sumOf { it.balance })
    }

    @Test
    fun `Kennzahlen von Anna ueber beide Abende`() {
        val expected = PlayerStats(
            player = a,
            // Der Abend ohne Spiel zählt nicht als Abend, seine freie Buchung (+500) aber zum Saldo.
            evenings = 2,
            games = 16,
            balance = 960 + 500,
            // 7 Siege am ersten Abend, 3 am zweiten.
            gamesWon = 10,
            // Erster Abend 3 von 3; zweiter Abend Spiel 1 und 4, beide gewonnen.
            asDeclarer = RoleRecord(5, 5),
            asPartner = RoleRecord(2, 1),
            // Erster Abend 3 von 7; zweiter Abend Spiel 2 gewonnen, Spiel 3 verloren.
            asOpponent = RoleRecord(9, 4),
            declarerByType = byType(RoleRecord(3, 3), RoleRecord(2, 2), none, none),
            klopferGiven = RoleRecord(1, 0),
            klopferEffect = -80,
            kontraGiven = RoleRecord(1, 0),
            retourGiven = none,
            // Erster Abend Spiel 2, zweiter Abend Spiel 2.
            schneiderWon = 2,
            schneiderLost = 2,
            schwarzWon = 0,
            schwarzLost = 1,
            toutPlayed = RoleRecord(1, 1),
            biggestWin = GameAmount(first, SampleEvening.g7.id, 7, 960),
            biggestLoss = GameAmount(first, SampleEvening.g8.id, 8, -320),
            // Serien gelten je Abend: 4 Siege am ersten Abend, am zweiten höchstens 2.
            longestWinStreak = 4,
            longestLoseStreak = 2,
            nixerTriggers = 0,
            eveningWins = 0,
            eveningLastPlaces = 0,
            bestEvening = EveningBalance(first, 920),
            worstEvening = EveningBalance(second, 40),
        )
        assertEquals(expected, of(a))
    }

    @Test
    fun `Kennzahlen von Bert ueber beide Abende`() {
        val expected = PlayerStats(
            player = b,
            evenings = 2,
            games = 16,
            balance = -1400 - 500,
            gamesWon = 5,
            // Zweiter Abend: Spiel 2 angesagt und verloren, Mitspieler in Spiel 4 (gewonnen).
            asDeclarer = RoleRecord(4, 1),
            asPartner = RoleRecord(2, 2),
            asOpponent = RoleRecord(10, 2),
            declarerByType = byType(RoleRecord(2, 1), none, RoleRecord(1, 0), RoleRecord(1, 0)),
            klopferGiven = RoleRecord(2, 0),
            klopferEffect = -450,
            kontraGiven = RoleRecord(1, 1),
            retourGiven = none,
            schneiderWon = 1,
            // Erster Abend Spiel 5 und 9, zweiter Abend Spiel 2.
            schneiderLost = 3,
            schwarzWon = 1,
            schwarzLost = 0,
            toutPlayed = none,
            biggestWin = GameAmount(first, SampleEvening.g8.id, 8, 320),
            biggestLoss = GameAmount(first, SampleEvening.g11.id, 11, -600),
            longestWinStreak = 2,
            longestLoseStreak = 4,
            nixerTriggers = 0,
            eveningWins = 0,
            // Letzter am ersten Abend; am zweiten ist C Letzter.
            eveningLastPlaces = 1,
            bestEvening = EveningBalance(second, -60),
            worstEvening = EveningBalance(first, -1340),
        )
        assertEquals(expected, of(b))
    }

    @Test
    fun `Kennzahlen von Chris ueber beide Abende`() {
        val expected = PlayerStats(
            player = c,
            evenings = 2,
            games = 16,
            balance = 1000,
            gamesWon = 7,
            asDeclarer = RoleRecord(4, 3),
            // Zweiter Abend: Mitspieler in Spiel 2 (verloren), Gegenspieler in 1, 3 und 4 (alle verloren).
            asPartner = RoleRecord(2, 1),
            asOpponent = RoleRecord(10, 3),
            declarerByType = byType(RoleRecord(2, 1), RoleRecord(1, 1), RoleRecord(1, 1), none),
            klopferGiven = RoleRecord(2, 1),
            klopferEffect = 50,
            kontraGiven = none,
            retourGiven = none,
            schneiderWon = 1,
            schneiderLost = 3,
            schwarzWon = 1,
            schwarzLost = 0,
            toutPlayed = none,
            biggestWin = GameAmount(first, SampleEvening.g12.id, 12, 600),
            biggestLoss = GameAmount(first, SampleEvening.g7.id, 7, -320),
            longestWinStreak = 5,
            // Am zweiten Abend verliert C alle vier Spiele.
            longestLoseStreak = 4,
            nixerTriggers = 1,
            eveningWins = 1,
            eveningLastPlaces = 1,
            bestEvening = EveningBalance(first, 1140),
            worstEvening = EveningBalance(second, -140),
        )
        assertEquals(expected, of(c))
    }

    @Test
    fun `wer nur an einem Abend dabei war, hat genau dessen Kennzahlen`() {
        assertEquals(EveningStatistics.compute(SampleEvening.state).statsFor(d), history.statsFor(d))

        val expected = PlayerStats(
            player = e,
            evenings = 1,
            games = 4,
            balance = 160,
            gamesWon = 3,
            asDeclarer = RoleRecord(1, 1),
            asPartner = RoleRecord(1, 1),
            asOpponent = RoleRecord(2, 1),
            declarerByType = byType(none, none, RoleRecord(1, 1), none),
            // E klopft in Spiel 4 und verliert 40: Wirkung −20.
            klopferGiven = RoleRecord(1, 0),
            klopferEffect = -20,
            kontraGiven = none,
            retourGiven = none,
            schneiderWon = 1,
            schneiderLost = 0,
            schwarzWon = 0,
            schwarzLost = 0,
            toutPlayed = none,
            biggestWin = GameAmount(second, SampleEvening.h3.id, 3, 150),
            biggestLoss = GameAmount(second, SampleEvening.h4.id, 4, -40),
            longestWinStreak = 3,
            longestLoseStreak = 1,
            nixerTriggers = 0,
            eveningWins = 1,
            eveningLastPlaces = 0,
            bestEvening = EveningBalance(second, 160),
            worstEvening = EveningBalance(second, 160),
        )
        assertEquals(expected, of(e))
    }

    @Test
    fun `Kennzahlen des Tisches ueber beide Abende`() {
        // Rufspiele 5 + 3, Farbsolo 3 + 0, Wenz 2 + 1, Geier 2 + 0.
        val played = mapOf(GameType.RUFSPIEL to 8, GameType.FARBSOLO to 3, GameType.WENZ to 3, GameType.GEIER to 2)
        assertEquals(GameType.entries.associateWith { 0 } + played, history.gameTypeCounts)
        assertEquals(7, history.bockGamesCount)
        assertEquals(2, history.bockTriggerCount)
        // Auch das zusammengeworfene Blatt vom Abend ohne Spiel zählt mit.
        assertEquals(3, history.thrownInCount)
        // Am zweiten Abend ist E ab Spiel 3 der erste und einzige alleinige Erste: kein Wechsel.
        assertEquals(3, history.leadChanges)
        assertEquals(ExpensiveGame(first, SampleEvening.g7.id, 7, 320), history.mostExpensiveGame)
        // Erster Abend 4960; zweiter Abend 40 + 60 + 150 + 80 = 330; freie Buchung vom Abend ohne Spiel 500.
        assertEquals(5790, history.turnover)
    }

    @Test
    fun `Paare im Rufspiel - als Spielerpaar und als Gegenspielerpaar`() {
        // Rufspiele des ersten Abends: 1 (A+B gegen C+D, gewonnen, 20), 2 (C+D gegen A+B, verloren, 60),
        // 6 (B+C gegen A+D, gewonnen, 160), 8 (D+A gegen B+C, verloren, 320), 10 (C+A gegen B+D, gewonnen, 160).
        // Rufspiele des zweiten Abends: 1 (A+E gegen B+C, gewonnen, 20), 2 (B+C gegen A+E, verloren, 30),
        // 4 (A+B gegen C+E, gewonnen, 40). Der gemeinsame Saldo ist jeweils zweimal der Spielwert.
        val noGames = PairRecord(0, 0, 0)
        val expected = listOf(
            PartnerRecord(a, b, asDeclarerPair = PairRecord(2, 2, 40 + 80), asOpponentPair = PairRecord(1, 1, 120)),
            PartnerRecord(a, c, asDeclarerPair = PairRecord(1, 1, 320), asOpponentPair = noGames),
            PartnerRecord(a, d, asDeclarerPair = PairRecord(1, 0, -640), asOpponentPair = PairRecord(1, 0, -320)),
            PartnerRecord(a, e, asDeclarerPair = PairRecord(1, 1, 40), asOpponentPair = PairRecord(1, 1, 60)),
            PartnerRecord(b, c, asDeclarerPair = PairRecord(2, 1, 320 - 60), asOpponentPair = PairRecord(2, 1, 640 - 40)),
            PartnerRecord(b, d, asDeclarerPair = noGames, asOpponentPair = PairRecord(1, 0, -320)),
            PartnerRecord(c, d, asDeclarerPair = PairRecord(1, 0, -120), asOpponentPair = PairRecord(1, 0, -40)),
            PartnerRecord(c, e, asDeclarerPair = noGames, asOpponentPair = PairRecord(1, 0, -80)),
        )
        assertEquals(expected, history.partnerRecords)
    }

    @Test
    fun `ein Paar laesst sich in beiden Reihenfolgen nachschlagen`() {
        val record = checkNotNull(history.partnerRecord(c, b))
        assertEquals(b, record.first)
        assertEquals(c, record.second)
        assertEquals(record, history.partnerRecord(b, c))
        assertTrue(record.isPair(b, c))
        assertTrue(record.isPair(c, b))
        assertFalse(record.isPair(a, b))
        // B und E standen in keinem Rufspiel auf derselben Seite, D und E saßen nie zusammen am Tisch.
        assertNull(history.partnerRecord(b, e))
        assertNull(history.partnerRecord(d, e))
    }

    @Test
    fun `die Quote eines Paars nennt Zaehler und Nenner`() {
        assertEquals(0.5, PairRecord(2, 1, 260).rate)
        assertEquals(1, PairRecord(2, 1, 260).lost)
        assertNull(PairRecord(0, 0, 0).rate)
    }

    @Test
    fun `die Reihenfolge der uebergebenen Abende spielt keine Rolle`() {
        val reversed = HistoryStatistics.compute(listOf(withoutGames, SampleEvening.evening, SampleEvening.secondEvening))
        assertEquals(history, reversed)
    }

    @Test
    fun `ein doppelt uebergebener Abend zaehlt nur einmal`() {
        val doubled = HistoryStatistics.compute(
            listOf(SampleEvening.secondEvening, SampleEvening.evening, SampleEvening.evening, withoutGames),
        )
        assertEquals(history, doubled)
    }

    @Test
    fun `ohne Abende ist die Statistik leer`() {
        val empty = HistoryStatistics.compute(emptyList())

        assertEquals(emptyList<PlayerStats>(), empty.playerStats)
        assertEquals(0, empty.eveningCount)
        assertEquals(0, empty.gameCount)
        assertEquals(emptyList<PartnerRecord>(), empty.partnerRecords)
        assertEquals(GameType.entries.associateWith { 0 }, empty.gameTypeCounts)
        assertEquals(0, empty.bockGamesCount)
        assertEquals(0, empty.bockTriggerCount)
        assertEquals(0, empty.thrownInCount)
        assertEquals(0, empty.leadChanges)
        assertNull(empty.mostExpensiveGame)
        assertEquals(0, empty.turnover)
        assertNull(empty.statsFor(a))
    }

    @Test
    fun `ein Abend ohne Spiel zaehlt nicht als Abend, seine Buchungen aber schon`() {
        val stats = HistoryStatistics.compute(listOf(withoutGames))

        assertEquals(0, stats.eveningCount)
        assertEquals(0, stats.gameCount)
        assertEquals(emptyList<PartnerRecord>(), stats.partnerRecords)
        assertEquals(GameType.entries.associateWith { 0 }, stats.gameTypeCounts)
        assertNull(stats.mostExpensiveGame)
        assertEquals(0, stats.leadChanges)
        // Was eingetragen wurde, bleibt sichtbar: das zusammengeworfene Blatt und die freie Buchung.
        assertEquals(1, stats.thrownInCount)
        assertEquals(500, stats.turnover)
        assertEquals(listOf(a, c, PlayerId("F"), b), stats.playerStats.map { it.player })
        assertEquals(listOf(500, 0, 0, -500), stats.playerStats.map { it.balance })
        for (player in stats.playerStats) {
            assertEquals(0, player.evenings)
            assertEquals(0, player.games)
            assertEquals(0, player.eveningWins)
            assertEquals(0, player.eveningLastPlaces)
            assertNull(player.bestEvening)
            assertNull(player.worstEvening)
        }
        // Dasselbe zeigt die Statistik dieses einen Abends.
        val single = EveningStatistics.compute(EveningCalculator.compute(withoutGames))
        assertEquals(single.playerStats.toSet(), stats.playerStats.toSet())
        assertEquals(single.thrownInCount, stats.thrownInCount)
        assertEquals(single.turnover, stats.turnover)
    }

    @Test
    fun `ein Uebertrag als einzige Buchung eines Abends steht in der ewigen Tabelle`() {
        // So übernimmt eine Runde alte Kontostände: ein Abend ohne Spiel mit einer freien Buchung.
        val carryOver = Evening(
            id = EveningId("uebertrag"),
            startedAt = Instant.parse("2026-01-01T12:00:00Z"),
            seating = listOf(a, b, c, d),
            firstDealer = a,
            rules = RuleSet.sauspiel(),
            entries = listOf(
                ManualEntry(EntryId("u1"), Instant.parse("2026-01-01T12:00:00Z"), mapOf(a to -300, d to 300), "Übertrag"),
            ),
        )
        val stats = HistoryStatistics.compute(listOf(SampleEvening.evening, carryOver))

        assertEquals(1, stats.eveningCount)
        // Beispielabend: A 920, B −1340, C 1140, D −720.
        assertEquals(listOf(c, a, d, b), stats.playerStats.map { it.player })
        assertEquals(listOf(1140, 620, -420, -1340), stats.playerStats.map { it.balance })
        assertEquals(4960 + 300, stats.turnover)
        // Als Abend zählt nur der gespielte: Anna hat einen Abend, und der beste ist der Beispielabend.
        assertEquals(1, checkNotNull(stats.statsFor(a)).evenings)
        assertEquals(EveningBalance(first, 920), checkNotNull(stats.statsFor(a)).bestEvening)
    }

    @Test
    fun `bei gleichem Saldo entscheidet die Kennung, nicht die Sitzordnung`() {
        // Ein Rufspiel: A und B je +20, C und D je −20. Am Tisch sitzen sie in umgekehrter Reihenfolge.
        val stats = HistoryStatistics.compute(listOf(oneGameEvening("tisch", "2026-11-14T18:30:00Z", listOf(d, c, b, a))))
        assertEquals(listOf(a, b, c, d), stats.playerStats.map { it.player })
        assertEquals(listOf(20, 20, -20, -20), stats.playerStats.map { it.balance })
        assertEquals(listOf(1, 1, 0, 0), stats.playerStats.map { it.eveningWins })
        assertEquals(listOf(0, 0, 1, 1), stats.playerStats.map { it.eveningLastPlaces })
    }

    @Test
    fun `bei gleich hohen Betraegen gewinnt der fruehere Abend`() {
        val early = oneGameEvening("frueh", "2026-10-01T18:30:00Z", listOf(a, b, c, d))
        val late = oneGameEvening("spaet", "2026-10-08T18:30:00Z", listOf(a, b, c, d))
        val stats = HistoryStatistics.compute(listOf(late, early))
        val anna = checkNotNull(stats.statsFor(a))

        assertEquals(2, anna.evenings)
        assertEquals(2, anna.eveningWins)
        assertEquals(GameAmount(EveningId("frueh"), EntryId("frueh-spiel"), 1, 20), anna.biggestWin)
        assertNull(anna.biggestLoss)
        assertEquals(EveningBalance(EveningId("frueh"), 20), anna.bestEvening)
        assertEquals(EveningBalance(EveningId("frueh"), 20), anna.worstEvening)
        assertEquals(ExpensiveGame(EveningId("frueh"), EntryId("frueh-spiel"), 1, 20), stats.mostExpensiveGame)
        // Serien enden mit dem Abend: zwei Abende mit je einem Sieg ergeben keine Serie von zwei.
        assertEquals(1, anna.longestWinStreak)
        assertEquals(PairRecord(2, 2, 80), checkNotNull(stats.partnerRecord(a, b)).asDeclarerPair)
        assertEquals(PairRecord(2, 0, -80), checkNotNull(stats.partnerRecord(c, d)).asOpponentPair)
    }

    @Test
    fun `die Statistik ueber viele Abende ist die Summe der einzelnen Abende`() {
        val random = Random(4711)
        val pool = listOf(a, b, c, d, e, PlayerId("F"))
        val firstDay = Instant.parse("2026-01-01T18:00:00Z")
        repeat(40) { round ->
            // Ein bis sechs Abende, an jedem sitzen vier der sechs Spieler in zufälliger Ordnung am Tisch.
            val evenings = List(random.nextInt(1, 7)) { index ->
                RandomEvenings.evening(
                    random = random,
                    id = "runde-$round-abend-$index",
                    seating = pool.shuffled(random).take(4),
                    startedAt = firstDay.plusSeconds(86_400L * index),
                )
            }
            // Auch Abende ohne Spiel gehören dazu: Sie zählen nicht als Abend, ihre Buchungen aber schon.
            val single = evenings.map { EveningStatistics.compute(EveningCalculator.compute(it)) }
            val history = HistoryStatistics.compute(evenings.shuffled(random))

            assertEquals(single.count { it.gamesPlayed > 0 }, history.eveningCount)
            assertEquals(single.sumOf { it.gamesPlayed }, history.gameCount)
            assertEquals(single.sumOf { it.turnover }, history.turnover)
            assertEquals(single.sumOf { it.leadChanges }, history.leadChanges)
            assertEquals(single.sumOf { it.bockGamesCount }, history.bockGamesCount)
            assertEquals(single.sumOf { it.bockTriggerCount }, history.bockTriggerCount)
            assertEquals(single.sumOf { it.thrownInCount }, history.thrownInCount)
            assertEquals(single.mapNotNull { it.mostExpensiveGame?.value }.maxOrNull(), history.mostExpensiveGame?.value)
            for (type in GameType.entries) {
                assertEquals(single.sumOf { it.gameTypeCounts.getValue(type) }, history.gameTypeCounts.getValue(type))
            }

            val players = single.flatMap { evening -> evening.playerStats.map { it.player } }.toSet()
            assertEquals(players, history.playerStats.map { it.player }.toSet())
            assertEquals(
                history.playerStats.sortedWith(compareByDescending<PlayerStats> { it.balance }.thenBy { it.player.value }),
                history.playerStats,
            )

            for (player in players) {
                val parts = single.mapNotNull { it.statsFor(player) }
                val total = checkNotNull(history.statsFor(player))

                assertEquals(parts.count { it.evenings > 0 }, total.evenings)
                assertEquals(parts.sumOf { it.games }, total.games)
                assertEquals(parts.sumOf { it.balance }, total.balance)
                assertEquals(parts.sumOf { it.gamesWon }, total.gamesWon)
                assertEquals(parts.sumOf { it.asDeclarer.played }, total.asDeclarer.played)
                assertEquals(parts.sumOf { it.asDeclarer.won }, total.asDeclarer.won)
                assertEquals(parts.sumOf { it.asPartner.played }, total.asPartner.played)
                assertEquals(parts.sumOf { it.asPartner.won }, total.asPartner.won)
                assertEquals(parts.sumOf { it.asOpponent.played }, total.asOpponent.played)
                assertEquals(parts.sumOf { it.asOpponent.won }, total.asOpponent.won)
                for (type in GameType.entries) {
                    assertEquals(
                        parts.sumOf { it.declarerByType.getValue(type).won },
                        total.declarerByType.getValue(type).won,
                    )
                }
                assertEquals(parts.sumOf { it.klopferCount }, total.klopferCount)
                assertEquals(parts.sumOf { it.klopferLost }, total.klopferLost)
                assertEquals(parts.sumOf { it.klopferEffect }, total.klopferEffect)
                assertEquals(parts.sumOf { it.kontraGiven.played }, total.kontraGiven.played)
                assertEquals(parts.sumOf { it.retourGiven.won }, total.retourGiven.won)
                assertEquals(parts.sumOf { it.schneiderWon }, total.schneiderWon)
                assertEquals(parts.sumOf { it.schneiderLost }, total.schneiderLost)
                assertEquals(parts.sumOf { it.schwarzWon }, total.schwarzWon)
                assertEquals(parts.sumOf { it.schwarzLost }, total.schwarzLost)
                assertEquals(parts.sumOf { it.toutPlayed.played }, total.toutPlayed.played)
                assertEquals(parts.sumOf { it.nixerTriggers }, total.nixerTriggers)
                assertEquals(parts.sumOf { it.eveningWins }, total.eveningWins)
                assertEquals(parts.sumOf { it.eveningLastPlaces }, total.eveningLastPlaces)
                // Serien und Spitzenwerte summieren sich nicht: Es zählt der beste bzw. schlechteste Abend.
                assertEquals(parts.maxOf { it.longestWinStreak }, total.longestWinStreak)
                assertEquals(parts.maxOf { it.longestLoseStreak }, total.longestLoseStreak)
                assertEquals(parts.mapNotNull { it.biggestWin?.amount }.maxOrNull(), total.biggestWin?.amount)
                assertEquals(parts.mapNotNull { it.biggestLoss?.amount }.minOrNull(), total.biggestLoss?.amount)
                // Bester und schlechtester Abend gibt es nur unter den Abenden mit mindestens einem Spiel.
                val played = parts.filter { it.evenings > 0 }
                assertEquals(played.maxOfOrNull { it.balance }, total.bestEvening?.balance)
                assertEquals(played.minOfOrNull { it.balance }, total.worstEvening?.balance)
            }

            // Jedes Rufspiel hat genau ein Spielerpaar und ein Gegenspielerpaar, genau eines davon gewinnt,
            // und was das eine Paar gewinnt, verliert das andere.
            val rufspiele = evenings.sumOf { evening -> evening.entries.count { it is GameEntry && !it.type.isSolo } }
            assertEquals(rufspiele, history.partnerRecords.sumOf { it.asDeclarerPair.played })
            assertEquals(rufspiele, history.partnerRecords.sumOf { it.asOpponentPair.played })
            assertEquals(rufspiele, history.partnerRecords.sumOf { it.asDeclarerPair.won + it.asOpponentPair.won })
            assertEquals(0, history.partnerRecords.sumOf { it.asDeclarerPair.balance + it.asOpponentPair.balance })
            for (record in history.partnerRecords) {
                assertTrue(record.first.value < record.second.value)
                assertTrue(record.asDeclarerPair.played + record.asOpponentPair.played > 0)
            }
        }
    }
}
