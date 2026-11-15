package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
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
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

/**
 * Grenzfälle der [StatisticEngine] an kleinen, eigens gebauten Abenden: nichts gespielt, Abende ohne Spiel,
 * Gleichstände, jede Schwelle der Abzeichen und Titel, Topf-Teilung.
 *
 * Wenn nichts anderes dabeisteht, gilt das Regelwerk der Runde: Ein Rufspiel ist 10 wert (je ±10), ein Farbsolo 50
 * (Alleinspieler ±150, jeder Gegner ∓50).
 */
class StatisticEngineEdgeCasesTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val seating = listOf(a, b, c, d)
    private val firstDay: Instant = Instant.parse("2026-03-01T18:00:00Z")
    private var nextId = 0

    private fun id() = EntryId("x${nextId++}")

    private fun evening(
        name: String,
        vararg entries: EveningEntry,
        day: Long = 0,
        seating: List<PlayerId> = this.seating,
        rules: RuleSet = RuleSet.standard(),
    ) = Evening(
        id = EveningId(name),
        startedAt = firstDay.plusSeconds(86_400 * day),
        seating = seating,
        firstDealer = seating.first(),
        rules = rules,
        entries = entries.toList(),
    )

    private fun rufspiel(
        declarer: PlayerId,
        partner: PlayerId,
        won: Boolean = true,
        klopfer: Set<PlayerId> = emptySet(),
        bock: Boolean = false,
    ) = GameEntry(
        id(), firstDay, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = won, klopfer = klopfer,
        bockOverride = if (bock) BockOverride.FORCE_ON else BockOverride.AUTO,
    )

    private fun solo(
        declarer: PlayerId,
        won: Boolean = true,
        type: GameType = GameType.FARBSOLO,
        klopfer: Set<PlayerId> = emptySet(),
    ) = GameEntry(id(), firstDay, type, declarer = declarer, declarerWon = won, klopfer = klopfer)

    private fun booking(vararg amounts: Pair<PlayerId, Int>) = ManualEntry(id(), firstDay, amounts.toMap(), "Buchung")

    /** [count] Rufspiele, die [declarer] ansagt. */
    private fun calls(declarer: PlayerId, partner: PlayerId, count: Int): Array<GameEntry> =
        Array(count) { rufspiel(declarer, partner) }

    /** [count] Farbsoli von [declarer]. */
    private fun solos(declarer: PlayerId, count: Int): Array<GameEntry> = Array(count) { solo(declarer) }

    // ---------------------------------------------------------------------------------------------------------------
    // Nichts gespielt
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `ohne Abende ist alles leer`() {
        val stats = StatisticEngine.forEvenings(emptyList())

        assertEquals(0, stats.eveningCount)
        assertEquals(0, stats.gameCount)
        assertEquals(emptyList<PlayerId>(), stats.players)
        assertEquals(
            FinanceOverview(
                standings = emptyList(),
                balanceSeries = emptyMap(),
                eveningStarts = emptyList(),
                eveningIds = emptyList(),
                turnover = 0,
            ),
            stats.finance,
        )
        assertEquals(emptyList<PlayerGameModes>(), stats.gameModes)
        assertEquals(emptyList<GelegtRecord>(), stats.gelegt)
        assertEquals(BockOverview(emptyList(), king = null), stats.bock)
        assertEquals(SoloOverview(emptyList(), specialist = null, wallflower = null), stats.solo)
        assertEquals(PartnerOverview(emptyList(), favorite = null), stats.partners)
        assertEquals(HistoryStatistics.compute(emptyList()), stats.history)
        assertNull(stats.gameModesOf(a))
        assertNull(stats.gelegtOf(a))
    }

    @Test
    fun `ein Abend ohne einen einzigen Eintrag`() {
        val stats = StatisticEngine.forEvening(evening("leer"))
        val none = ModeRecord(0, 0, 0)

        assertEquals(0, stats.eveningCount)
        assertEquals(0, stats.gameCount)
        assertEquals(seating, stats.players)
        assertEquals(seating.map { Standing(it, balance = 0, rank = 1) }, stats.finance.standings)
        // Ohne Spiel hat jede Reihe nur ihren Startpunkt.
        assertEquals(seating.associateWith { listOf(0) }, stats.finance.balanceSeries)
        assertEquals(emptyList<Int>(), stats.finance.eveningStarts)
        assertEquals(emptyList<EveningId>(), stats.finance.eveningIds)
        assertEquals(0, stats.finance.turnover)
        for (player in seating) {
            assertEquals(GameType.entries.associateWith { none }, stats.gameModesOf(player)?.atTable)
            assertEquals(GameType.entries.associateWith { none }, stats.gameModesOf(player)?.asPlayer)
            assertEquals(GelegtRecord(player, 0, 0, 0, 0), stats.gelegtOf(player))
            assertEquals(GelegtBadge.STREICHHOLZ_SPARER, stats.gelegtOf(player)?.badge)
            assertNull(stats.gelegtOf(player)?.winRate)
            assertEquals(BockRecord(player, 0, 0, 0), stats.bock.recordOf(player))
            assertNull(stats.bock.recordOf(player)?.winRate)
            assertEquals(SoloRecord(player, 0, 0), stats.solo.recordOf(player))
            assertNull(stats.solo.recordOf(player)?.soloShare)
        }
        assertNull(stats.bock.king)
        assertNull(stats.solo.specialist)
        assertNull(stats.solo.wallflower)
        assertEquals(emptyList<PartnerPair>(), stats.partners.pairs)
        assertNull(stats.partners.favorite)
    }

    @Test
    fun `ein Abend ohne Spiel zeigt freie Buchungen im Kontostand, zaehlt aber nicht als Abend`() {
        val onlyBooking = evening("wette", ThrownInEntry(id(), firstDay), booking(a to 100, b to -100))

        val single = StatisticEngine.forEvening(onlyBooking)
        assertEquals(0, single.eveningCount)
        assertEquals(0, single.gameCount)
        assertEquals(
            listOf(Standing(a, 100, 1), Standing(b, -100, 4), Standing(c, 0, 2), Standing(d, 0, 2)),
            single.finance.standings,
        )
        // Ohne ein einziges Spiel steht die Buchung im einzigen Punkt der Reihe: Er ist zugleich der Kontostand.
        assertEquals(mapOf(a to listOf(100), b to listOf(-100), c to listOf(0), d to listOf(0)), single.finance.balanceSeries)
        assertEquals(emptyList<Int>(), single.finance.eveningStarts)
        assertEquals(100, single.finance.turnover)

        val table = StatisticEngine.forEvenings(listOf(onlyBooking))
        assertEquals(0, table.eveningCount)
        assertEquals(listOf(a, c, d, b), table.players)
        assertEquals(
            listOf(Standing(a, 100, 1), Standing(c, 0, 2), Standing(d, 0, 2), Standing(b, -100, 4)),
            table.finance.standings,
        )
        assertEquals(listOf(100, 0, 0, -100), table.history.playerStats.map { it.balance })
    }

    @Test
    fun `freie Buchungen von Abenden ohne Spiel zaehlen zum naechsten Spiel und am Ende zum letzten Punkt`() {
        val carryOver = evening("uebertrag", booking(a to 100, b to -100), day = 1)
        val firstGame = evening("spiel-1", rufspiel(a, b), day = 2)
        val between = evening("dazwischen", booking(c to 40, d to -40), day = 3)
        val secondGame = evening("spiel-2", rufspiel(c, d), day = 4)
        val afterwards = evening("danach", booking(a to -5, d to 5), day = 5)

        val stats = StatisticEngine.forEvenings(listOf(afterwards, secondGame, between, firstGame, carryOver))

        // Als Abend zählen nur die beiden mit einem Spiel.
        assertEquals(2, stats.eveningCount)
        assertEquals(2, stats.gameCount)
        assertEquals(listOf(0, 1), stats.finance.eveningStarts)
        assertEquals(listOf(EveningId("spiel-1"), EveningId("spiel-2")), stats.finance.eveningIds)
        // Kein Betrag geht verloren: A 100 + 10 − 10 − 5, C −10 + 40 + 10, D −10 − 40 + 10 + 5, B −100 + 10 − 10.
        assertEquals(listOf(a, c, d, b), stats.players)
        assertEquals(
            listOf(Standing(a, 95, 1), Standing(c, 40, 2), Standing(d, -35, 3), Standing(b, -100, 4)),
            stats.finance.standings,
        )
        // Punkt 1: der Übertrag und das erste Spiel. Punkt 2: die Buchung dazwischen, das zweite Spiel und,
        // weil kein Spiel mehr folgt, auch die Buchung danach.
        assertEquals(
            mapOf(
                a to listOf(0, 110, 95),
                c to listOf(0, -10, 40),
                d to listOf(0, -10, -35),
                b to listOf(0, -90, -100),
            ),
            stats.finance.balanceSeries,
        )
        assertEquals(100 + 20 + 40 + 20 + 5, stats.finance.turnover)
        assertEquals(stats.finance.turnover, stats.history.turnover)
        assertEquals(listOf(95, 40, -35, -100), stats.history.playerStats.map { it.balance })
        assertEquals(listOf(2, 2, 2, 2), stats.history.playerStats.map { it.evenings })
    }

    @Test
    fun `eine freie Buchung hinter dem letzten Spiel bleibt im Verlauf bei ihrem Abend`() {
        // Früher Abend: A und B gewinnen ein Rufspiel, danach bekommt C von D 30. Später Abend: C und D gewinnen.
        val early = evening("frueh", rufspiel(a, b), booking(c to 30, d to -30), day = 1)
        val late = evening("spaet", rufspiel(c, d), day = 2)

        val stats = StatisticEngine.forEvenings(listOf(late, early))

        // Punkt 1 ist der Stand nach dem ganzen ersten Abend; die Buchung rutscht nicht in den zweiten Abend.
        assertEquals(listOf(c, a, b, d), stats.players)
        assertEquals(
            mapOf(c to listOf(0, 20, 30), a to listOf(0, 10, 0), b to listOf(0, 10, 0), d to listOf(0, -40, -30)),
            stats.finance.balanceSeries,
        )
        assertEquals(listOf(0, 1), stats.finance.eveningStarts)
        assertEquals(
            listOf(Standing(c, 30, 1), Standing(a, 0, 2), Standing(b, 0, 2), Standing(d, -30, 4)),
            stats.finance.standings,
        )
    }

    @Test
    fun `ein Spiel, das sich nicht abrechnen laesst, zaehlt nicht und bekommt keinen Punkt im Verlauf`() {
        val unknownDeclarer = GameEntry(id(), firstDay, GameType.WENZ, declarer = PlayerId("X"), declarerWon = true)
        val missingPartner = GameEntry(id(), firstDay, GameType.RUFSPIEL, declarer = a, declarerWon = true)
        val evening = evening(
            "luecken",
            booking(a to 50, b to -50),
            unknownDeclarer,
            rufspiel(a, b),
            missingPartner,
            booking(c to 5, d to -5),
        )
        val state = EveningCalculator.compute(evening)
        val stats = StatisticEngine.forEvening(evening)

        // Der Abend nummeriert alle drei Spiele, die Statistik zählt nur das abgerechnete.
        assertEquals(3, state.gamesPlayed)
        assertEquals(1, stats.gameCount)
        assertEquals(1, stats.eveningCount)
        // Die Buchung vor dem ersten abgerechneten Spiel steckt in dessen Punkt, die danach ebenfalls.
        assertEquals(
            mapOf(a to listOf(0, 60), b to listOf(0, -40), c to listOf(0, -5), d to listOf(0, -15)),
            stats.finance.balanceSeries,
        )
        assertEquals(1, stats.gameModesOf(a)?.atTable?.getValue(GameType.RUFSPIEL)?.games)
        assertEquals(0, stats.gameModesOf(a)?.atTable?.getValue(GameType.WENZ)?.games)
        assertEquals(SoloRecord(a, solos = 0, calls = 1), stats.solo.recordOf(a))
        assertEquals(listOf(PartnerPair(a, b, 1, 1, 20), PartnerPair(c, d, 1, 0, -20)), stats.partners.pairs)
    }

    @Test
    fun `beschaedigte Abende bringen die Auswertung nicht zum Absturz`() {
        // Ohne Sitzordnung gibt es keine Spieler; die Einträge lassen sich niemandem zuordnen und buchen nichts.
        val noSeating = Evening(
            id = EveningId("ohne-tisch"),
            startedAt = firstDay,
            seating = emptyList(),
            firstDealer = a,
            rules = RuleSet.standard(),
            entries = listOf(rufspiel(a, b), booking(a to 10, b to -10)),
        )
        val empty = StatisticEngine.forEvening(noSeating)
        assertEquals(emptyList<PlayerId>(), empty.players)
        assertEquals(0, empty.eveningCount)
        assertEquals(0, empty.gameCount)
        assertEquals(emptyList<Standing>(), empty.finance.standings)
        assertEquals(emptyMap<PlayerId, List<Int>>(), empty.finance.balanceSeries)
        assertEquals(0, empty.finance.turnover)
        assertEquals(emptyList<PartnerPair>(), empty.partners.pairs)

        // Sitzt jemand doppelt am Tisch, wird kein Spiel abgerechnet; jeder Spieler steht nur einmal in der Liste.
        val doubleSeat = evening("doppelt", rufspiel(a, b), solo(c), seating = listOf(a, a, b, c))
        val doubled = StatisticEngine.forEvening(doubleSeat)
        assertEquals(listOf(a, b, c), doubled.players)
        assertEquals(0, doubled.gameCount)
        assertEquals(listOf(a, b, c).map { Standing(it, balance = 0, rank = 1) }, doubled.finance.standings)

        // Zusammen mit einem gesunden Abend zählt nur dieser.
        val healthy = evening("gesund", rufspiel(a, b), day = 1)
        val table = StatisticEngine.forEvenings(listOf(noSeating, doubleSeat, healthy))
        assertEquals(1, table.eveningCount)
        assertEquals(1, table.gameCount)
        assertEquals(listOf(a, b, c, d), table.players)
        assertEquals(listOf(10, 10, -10, -10), table.finance.standings.map { it.balance })
        assertEquals(listOf(EveningId("gesund")), table.finance.eveningIds)
    }

    @Test
    fun `ein Eintrag, der einen Saldo sprengen wuerde, bucht nichts und zaehlt nicht mit`() {
        // Die zweite Buchung über zwei Milliarden passte nicht mehr in den Zahlenbereich; ebenso das Farbsolo danach,
        // dessen Grundwert das Regelwerk dieses Abends auf 700 Millionen setzt (A bekäme das Dreifache).
        val absurd = RuleSet.standard().let { it.copy(prices = it.prices + (GameType.FARBSOLO to 700_000_000)) }
        val stats = StatisticEngine.forEvening(
            evening(
                "riesig",
                rufspiel(a, b),
                booking(a to 2_000_000_000, b to -2_000_000_000),
                booking(a to 2_000_000_000, b to -2_000_000_000),
                solo(a),
                rules = absurd,
            ),
        )

        assertEquals(1, stats.gameCount)
        assertEquals(
            listOf(Standing(a, 2_000_000_010, 1), Standing(b, -1_999_999_990, 4), Standing(c, -10, 2), Standing(d, -10, 2)),
            stats.finance.standings,
        )
        assertEquals(listOf(0, 2_000_000_010), stats.finance.balanceSeries[a])
        assertEquals(SoloRecord(a, solos = 0, calls = 1), stats.solo.recordOf(a))
        assertEquals(0, stats.gameModesOf(a)?.atTable?.getValue(GameType.FARBSOLO)?.games)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gleichstände
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `bei gleichem Saldo teilen sich Spieler den Platz`() {
        // Ein Rufspiel: A und B je +10, C und D je −10. Am Tisch sitzen sie in umgekehrter Reihenfolge.
        val evening = evening("gleich", rufspiel(a, b), seating = listOf(d, c, b, a))

        // Ein einzelner Abend: Sitzreihenfolge.
        val single = StatisticEngine.forEvening(evening)
        assertEquals(listOf(d, c, b, a), single.players)
        assertEquals(
            listOf(Standing(d, -10, 3), Standing(c, -10, 3), Standing(b, 10, 1), Standing(a, 10, 1)),
            single.finance.standings,
        )

        // Ewige Tabelle: nach Saldo, bei Gleichstand nach Kennung. Das gilt auch für nur einen Abend.
        val table = StatisticEngine.forEvenings(listOf(evening))
        assertEquals(listOf(a, b, c, d), table.players)
        assertEquals(
            listOf(Standing(a, 10, 1), Standing(b, 10, 1), Standing(c, -10, 3), Standing(d, -10, 3)),
            table.finance.standings,
        )
        // Bis auf die Reihenfolge sind beide Auswertungen dieselben.
        for (player in seating) {
            assertEquals(single.finance.standingOf(player), table.finance.standingOf(player))
            assertEquals(single.finance.balanceSeries[player], table.finance.balanceSeries[player])
            assertEquals(single.gameModesOf(player), table.gameModesOf(player))
            assertEquals(single.gelegtOf(player), table.gelegtOf(player))
            assertEquals(single.bock.recordOf(player), table.bock.recordOf(player))
            assertEquals(single.solo.recordOf(player), table.solo.recordOf(player))
        }
        assertEquals(single.partners, table.partners)
        assertEquals(single.history, table.history)
    }

    @Test
    fun `Bock-Koenig bei Gleichstand ist, wer in der Spielerliste weiter vorn steht`() {
        // Ein Rufspiel als Bockspiel: A und B je +20.
        val evening = evening("bock", rufspiel(a, b, bock = true), seating = listOf(d, c, b, a))

        val single = StatisticEngine.forEvening(evening)
        assertEquals(
            listOf(BockRecord(d, 1, 0, -20), BockRecord(c, 1, 0, -20), BockRecord(b, 1, 1, 20), BockRecord(a, 1, 1, 20)),
            single.bock.records,
        )
        // In Sitzreihenfolge steht B vor A, in der ewigen Tabelle (nach Kennung) A vor B.
        assertEquals(b, single.bock.king?.player)
        assertEquals(a, StatisticEngine.forEvenings(listOf(evening)).bock.king?.player)
    }

    @Test
    fun `ohne Gewinn aus Bockspielen gibt es keinen Bock-Koenig`() {
        // Zwei Bockspiele, die sich aufheben: Alle stehen aus Bockspielen bei 0.
        val even = StatisticEngine.forEvening(evening("null", rufspiel(a, b, bock = true), rufspiel(c, d, bock = true)))
        assertEquals(seating.map { BockRecord(it, games = 2, won = 1, balance = 0) }, even.bock.records)
        assertNull(even.bock.king)

        // Ohne Bockspiel erst recht nicht, auch wenn sonst gewonnen wurde.
        val noBock = StatisticEngine.forEvening(evening("ohne", rufspiel(a, b), solo(a)))
        assertEquals(seating.map { BockRecord(it, 0, 0, 0) }, noBock.bock.records)
        assertNull(noBock.bock.king)
    }

    @Test
    fun `ohne Einzelspiel gibt es keinen Solo-Spezialisten`() {
        val stats = StatisticEngine.forEvening(evening("rufer", rufspiel(a, b), rufspiel(b, c), rufspiel(c, d)))
        assertNull(stats.solo.specialist)
        // Drei verschiedene Ansager mit je einem Rufspiel: Niemand hat drei Spiele angesagt.
        assertNull(stats.solo.wallflower)
    }

    @Test
    fun `Ueber Kreuz zaehlt bei den Gespannen und Spielarten, aber nicht als angesagtes Spiel`() {
        fun kreuz(first: PlayerId, second: PlayerId) =
            GameEntry(id(), firstDay, GameType.UEBER_KREUZ, declarer = first, partner = second, declarerWon = true)

        val stats = StatisticEngine.forEvening(evening("kreuz", kreuz(a, c), kreuz(a, c), kreuz(c, a), kreuz(b, d)))

        // Niemand sagt an: keine Rufspiele, keine Einzelspiele, weder Spezialist noch Mauerblümchen.
        assertEquals(seating.map { SoloRecord(it, solos = 0, calls = 0) }, stats.solo.records)
        assertNull(stats.solo.specialist)
        assertNull(stats.solo.wallflower)
        // A und C gewinnen dreimal je 10, B und D einmal.
        assertEquals(
            listOf(PartnerPair(a, c, games = 4, won = 3, balance = 40), PartnerPair(b, d, games = 4, won = 1, balance = -40)),
            stats.partners.pairs,
        )
        assertEquals(PartnerPair(a, c, 4, 3, 40), stats.partners.favorite)
        for (player in seating) {
            val modes = checkNotNull(stats.gameModesOf(player))
            assertEquals(4, modes.atTable.getValue(GameType.UEBER_KREUZ).games)
            assertEquals(modes.atTable, modes.asPlayer)
            // In der bisherigen Statistik zählt Über Kreuz in keiner Ansager-Rolle.
            val history = checkNotNull(stats.history.statsFor(player))
            assertEquals(4, history.games)
            assertEquals(RoleRecord(0, 0), history.asDeclarer)
            assertEquals(RoleRecord(0, 0), history.asPartner)
            assertEquals(RoleRecord(0, 0), history.asOpponent)
            assertEquals(GameType.entries.associateWith { RoleRecord(0, 0) }, history.declarerByType)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mauerblümchen
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `Mauerbluemchen ist, wer hoechstens jedes fuenfte angesagte Spiel allein spielt`() {
        // A: ein Einzelspiel und vier Rufspiele, genau 20 %. B ist mit zwei Einzelspielen der Spezialist.
        val exactly = StatisticEngine.forEvening(evening("zwanzig", *solos(a, 1), *calls(a, c, 4), *solos(b, 2)))
        assertEquals(SoloRecord(b, solos = 2, calls = 0), exactly.solo.specialist)
        assertEquals(SoloRecord(a, solos = 1, calls = 4), exactly.solo.wallflower)
        assertEquals(0.2, exactly.solo.wallflower?.soloShare)

        // A: ein Einzelspiel und drei Rufspiele sind 25 %, also zu viel.
        val above = StatisticEngine.forEvening(evening("mehr", *solos(a, 1), *calls(a, c, 3), *solos(b, 2)))
        assertEquals(b, above.solo.specialist?.player)
        assertNull(above.solo.wallflower)
    }

    @Test
    fun `Mauerbluemchen braucht mindestens drei angesagte Spiele`() {
        val two = StatisticEngine.forEvening(evening("zwei", *calls(a, c, 2), *solos(b, 1)))
        assertNull(two.solo.wallflower)

        val three = StatisticEngine.forEvening(evening("drei", *calls(a, c, 3), *solos(b, 1)))
        assertEquals(SoloRecord(a, solos = 0, calls = 3), three.solo.wallflower)
    }

    @Test
    fun `der Solo-Spezialist ist nie zugleich das Mauerbluemchen`() {
        // A spielt als Einziger allein (einmal) und ruft neunmal: 10 %, aber er ist der Spezialist.
        val alone = StatisticEngine.forEvening(evening("allein", *solos(a, 1), *calls(a, c, 9), *calls(b, d, 2)))
        assertEquals(a, alone.solo.specialist?.player)
        assertNull(alone.solo.wallflower)

        // Ruft B dreimal, ist er das Mauerblümchen (er hat nie allein gespielt). Den Fall, dass der Spezialist den
        // kleinsten Solo-Anteil von allen hat, prüft `StatisticEngineReviewTest`.
        val other = StatisticEngine.forEvening(evening("anderer", *solos(a, 1), *calls(a, c, 9), *calls(b, d, 3)))
        assertEquals(a, other.solo.specialist?.player)
        assertEquals(SoloRecord(b, solos = 0, calls = 3), other.solo.wallflower)
    }

    @Test
    fun `bei gleichem Solo-Anteil ist das Mauerbluemchen, wer mehr angesagt hat, dann wer weiter vorn steht`() {
        // A und B haben beide nie allein gespielt; B hat fünf Spiele angesagt, A drei.
        val more = StatisticEngine.forEvening(evening("mehr", *calls(a, c, 3), *calls(b, d, 5), *solos(c, 1)))
        assertEquals(SoloRecord(b, solos = 0, calls = 5), more.solo.wallflower)

        // Beide drei Rufspiele: Es entscheidet die Reihenfolge der Spielerliste.
        val aFirst = evening("gleich", *calls(a, c, 3), *calls(b, d, 3), *solos(c, 1))
        assertEquals(a, StatisticEngine.forEvening(aFirst).solo.wallflower?.player)
        val bFirst = evening("gleich", *calls(a, c, 3), *calls(b, d, 3), *solos(c, 1), seating = listOf(b, a, c, d))
        assertEquals(b, StatisticEngine.forEvening(bFirst).solo.wallflower?.player)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lieblingspartner
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `Lieblingspartner brauchen mindestens drei gemeinsame Spiele`() {
        val two = StatisticEngine.forEvening(evening("zwei", rufspiel(a, b), rufspiel(a, b)))
        assertEquals(listOf(PartnerPair(a, b, 2, 2, 40), PartnerPair(c, d, 2, 0, -40)), two.partners.pairs)
        assertNull(two.partners.favorite)

        val three = StatisticEngine.forEvening(evening("drei", rufspiel(a, b), rufspiel(a, b), rufspiel(b, a)))
        assertEquals(PartnerPair(a, b, 3, 3, 60), three.partners.favorite)
    }

    @Test
    fun `das Gespann mit dem hoechsten Saldo ist nicht Lieblingspartner, wenn es zu selten zusammen gespielt hat`() {
        // A+D gewinnen ein einziges Rufspiel, bei dem alle vier gelegt haben: 10 × 16 = 160, zusammen +320.
        // A+B gewinnen drei einfache Rufspiele: zusammen +60. Einzelspiele zählen bei den Gespannen nicht.
        val stats = StatisticEngine.forEvening(
            evening(
                "einmal",
                rufspiel(a, d, klopfer = setOf(a, b, c, d)),
                rufspiel(a, b),
                rufspiel(b, a),
                rufspiel(a, b),
                solo(c),
            ),
        )
        assertEquals(
            listOf(
                PartnerPair(a, d, games = 1, won = 1, balance = 320),
                PartnerPair(a, b, games = 3, won = 3, balance = 60),
                PartnerPair(c, d, games = 3, won = 0, balance = -60),
                PartnerPair(b, c, games = 1, won = 0, balance = -320),
            ),
            stats.partners.pairs,
        )
        assertEquals(PartnerPair(a, b, 3, 3, 60), stats.partners.favorite)
    }

    @Test
    fun `bei gleichem Saldo und gleicher Quote stehen die Gespanne nach ihren Kennungen`() {
        // Erst gewinnen A+B gegen C+D, dann C+D gegen A+B, dann B+C gegen A+D und A+D gegen B+C: alle bei 0.
        val stats = StatisticEngine.forEvening(evening("patt", rufspiel(c, d), rufspiel(a, b), rufspiel(b, c), rufspiel(d, a)))
        assertEquals(
            listOf(
                PartnerPair(a, b, games = 2, won = 1, balance = 0),
                PartnerPair(a, d, games = 2, won = 1, balance = 0),
                PartnerPair(b, c, games = 2, won = 1, balance = 0),
                PartnerPair(c, d, games = 2, won = 1, balance = 0),
            ),
            stats.partners.pairs,
        )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Abzeichen
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} gelegt, {1} gewonnen, Bilanz {2}: {3}")
    @CsvSource(
        // Nie gelegt.
        "0, 0, 0, STREICHHOLZ_SPARER",
        // Ein- oder zweimal gelegt: noch kein Urteil, egal wie es ausging.
        "1, 1, 500, NOCH_OFFEN",
        "1, 0, -500, NOCH_OFFEN",
        "2, 2, 500, NOCH_OFFEN",
        "2, 0, -500, NOCH_OFFEN",
        // Ab drei gelegten Spielen. Sichere Bank: mindestens 60 % gewonnen und nicht im Minus.
        "3, 3, 0, SICHERE_BANK",
        "3, 2, 0, SICHERE_BANK",
        "3, 2, 1, SICHERE_BANK",
        "5, 3, 0, SICHERE_BANK",
        "10, 6, 1000, SICHERE_BANK",
        // Genug gewonnen, aber im Minus: keine sichere Bank.
        "3, 2, -1, WACKELKANDIDAT",
        "5, 3, -1, WACKELKANDIDAT",
        "3, 3, -10, WACKELKANDIDAT",
        // Knapp unter 60 % (4 von 7 sind 57 %): im Plus Glücksritter, sonst Wackelkandidat.
        "7, 4, 100, GLUECKSRITTER",
        "7, 4, 0, WACKELKANDIDAT",
        "7, 4, -100, WACKELKANDIDAT",
        "2000, 1199, 1, GLUECKSRITTER",
        "2000, 1200, 1, SICHERE_BANK",
        // Brandstifter: höchstens 40 % gewonnen und im Minus.
        "3, 0, -1, BRANDSTIFTER",
        "3, 1, -1, BRANDSTIFTER",
        "5, 2, -1, BRANDSTIFTER",
        "10, 4, -1000, BRANDSTIFTER",
        "2000, 800, -1, BRANDSTIFTER",
        // Wenig gewonnen, aber nicht im Minus: kein Brandstifter.
        "3, 1, 0, WACKELKANDIDAT",
        "3, 1, 1, GLUECKSRITTER",
        "5, 2, 0, WACKELKANDIDAT",
        "3, 0, 0, WACKELKANDIDAT",
        // Knapp über 40 % (3 von 7 sind 43 %) und im Minus: Wackelkandidat.
        "7, 3, -100, WACKELKANDIDAT",
        "2000, 801, -1, WACKELKANDIDAT",
        // Genau die Hälfte.
        "10, 5, 1, GLUECKSRITTER",
        "10, 5, 0, WACKELKANDIDAT",
        "10, 5, -1, WACKELKANDIDAT",
    )
    fun `jede Schwelle der Abzeichen`(count: Int, won: Int, netBalance: Int, expected: GelegtBadge) {
        assertEquals(expected, GelegtBadge.of(count, won, netBalance))

        val record = GelegtRecord(a, count, won, netBalance, effect = 0)
        assertEquals(expected, record.badge)
        assertEquals(expected == GelegtBadge.BRANDSTIFTER, record.shouldLeaveIt)
    }

    @Test
    fun `das Abzeichen haengt am Ausgang der gelegten Spiele, nicht am Rest des Abends`() {
        // A legt dreimal und verliert dreimal je 20; die beiden gewonnenen Farbsoli ohne Legen zählen hier nicht.
        val stats = StatisticEngine.forEvening(
            evening(
                "zuendler",
                rufspiel(c, d, klopfer = setOf(a)),
                rufspiel(c, d, klopfer = setOf(a)),
                rufspiel(c, d, klopfer = setOf(a)),
                solo(a),
                solo(a),
            ),
        )
        assertEquals(GelegtRecord(a, count = 3, won = 0, netBalance = -60, effect = -30), stats.gelegtOf(a))
        assertEquals(GelegtBadge.BRANDSTIFTER, stats.gelegtOf(a)?.badge)
        assertTrue(checkNotNull(stats.gelegtOf(a)).shouldLeaveIt)
        // Unterm Strich steht A trotzdem im Plus: −60 + 150 + 150.
        assertEquals(240, stats.finance.standingOf(a)?.balance)
        assertFalse(checkNotNull(stats.gelegtOf(c)).shouldLeaveIt)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Durchschnitt
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{1} Cent aus {0} Spielen: im Schnitt {2}")
    @CsvSource(
        "1, 7, 7",
        "1, -7, -7",
        "2, 5, 3",
        "2, -5, -3",
        "2, 1, 1",
        "2, -1, -1",
        "3, 1, 0",
        "3, -1, 0",
        "3, 7, 2",
        "3, 8, 3",
        "3, -7, -2",
        "3, -8, -3",
        "4, 10, 3",
        "4, -10, -3",
        "4, 9, 2",
        "7, 0, 0",
        "1, 2147483647, 2147483647",
        "1, -2147483648, -2147483648",
        "2, 2147483647, 1073741824",
    )
    fun `der Durchschnitt wird kaufmaennisch gerundet`(games: Int, balance: Int, expected: Int) {
        assertEquals(expected, ModeRecord(games, won = 0, balance = balance).averageAmount)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Andere Regelwerke
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `Topf-Teilung - die Wirkung des Legens wird auf den Cent nachgerechnet`() {
        val potSplit = RuleSet.standard().copy(payoutRule = PayoutRule.POT_SPLIT)
        val stats = StatisticEngine.forEvening(
            evening(
                "topf",
                // Farbsolo A gewonnen, gelegt hat D: T = 100. A +100; B, C, D teilen sich 100: −34, −33, −33.
                // Ohne Ds Leger wäre T = 50 mit −17, −17, −16: Das Legen hat D 33 − 16 = 17 gekostet (nicht 16).
                solo(a, klopfer = setOf(d)),
                // Wenz B verloren, gelegt haben B und C: T = 200. B −200; A, C, D teilen sich 200: +67, +67, +66.
                // Ohne Bs Leger T = 100: B −100. Ohne Cs Leger T = 100 mit +34, +33, +33: C hat 67 − 33 = 34 gewonnen.
                solo(b, won = false, type = GameType.WENZ, klopfer = setOf(b, c)),
                // Rufspiel C mit D gewonnen, gelegt hat D: T = 20, ohne den Leger 10.
                rufspiel(c, d, klopfer = setOf(d)),
                rules = potSplit,
            ),
        )

        assertEquals(
            listOf(Standing(a, 147, 1), Standing(b, -254, 4), Standing(c, 54, 2), Standing(d, 53, 3)),
            stats.finance.standings,
        )
        assertEquals(
            listOf(
                GelegtRecord(a, count = 0, won = 0, netBalance = 0, effect = 0),
                GelegtRecord(b, count = 1, won = 0, netBalance = -200, effect = -100),
                GelegtRecord(c, count = 1, won = 1, netBalance = 67, effect = 34),
                // −33 + 20 und −17 + 10
                GelegtRecord(d, count = 2, won = 1, netBalance = -13, effect = -7),
            ),
            stats.gelegt,
        )
        // Die bisherige Statistik rechnet genauso (früher: die Hälfte des Betrags, also −6 statt −7 bei D).
        assertEquals(listOf(0, -100, 34, -7), seating.map { stats.history.statsFor(it)?.klopferEffect })
        assertEquals(ModeRecord(1, 0, -34), stats.gameModesOf(b)?.atTable?.getValue(GameType.FARBSOLO))
        assertEquals(ModeRecord(1, 1, 66), stats.gameModesOf(d)?.atTable?.getValue(GameType.WENZ))
        assertEquals(100 + 200 + 40, stats.finance.turnover)
        assertEquals(0, stats.finance.standings.sumOf { it.balance })
    }

    @Test
    fun `jeder Abend wird mit seinem eigenen Regelwerk gerechnet`() {
        // Dasselbe Rufspiel ist nach dem Tarif der Runde 10 wert, nach dem Sauspiel-Tarif 20.
        val round = evening("runde", rufspiel(a, b), day = 1)
        val sauspiel = evening("sauspiel", rufspiel(a, b), day = 2, rules = RuleSet.sauspiel())

        val stats = StatisticEngine.forEvenings(listOf(sauspiel, round))
        assertEquals(mapOf(a to listOf(0, 10, 30), b to listOf(0, 10, 30), c to listOf(0, -10, -30), d to listOf(0, -10, -30)), stats.finance.balanceSeries)
        assertEquals(ModeRecord(2, 2, 30), stats.gameModesOf(a)?.atTable?.getValue(GameType.RUFSPIEL))
        assertEquals(15, stats.gameModesOf(a)?.atTable?.getValue(GameType.RUFSPIEL)?.averageAmount)
    }

    @Test
    fun `Tout und Kontra gehen mit ihren gebuchten Betraegen in die Auswertung ein`() {
        // Wenz-Tout von A, gewonnen: 50 × 2 = 100, A bekommt 300.
        val tout = GameEntry(id(), firstDay, GameType.WENZ, declarer = a, declarerWon = true, announcement = Announcement.TOUT)
        // Rufspiel C mit D, gewonnen, mit Kontra von A: 10 × 2 = 20.
        val kontra = GameEntry(
            id(), firstDay, GameType.RUFSPIEL, declarer = c, partner = d, declarerWon = true,
            kontra = KontraLevel.KONTRA, kontraBy = a,
        )
        val stats = StatisticEngine.forEvening(evening("tout", tout, kontra))

        assertEquals(ModeRecord(1, 1, 300), stats.gameModesOf(a)?.asPlayer?.getValue(GameType.WENZ))
        assertEquals(ModeRecord(1, 0, -100), stats.gameModesOf(b)?.atTable?.getValue(GameType.WENZ))
        assertEquals(SoloRecord(a, solos = 1, calls = 0), stats.solo.specialist)
        assertEquals(RoleRecord(1, 1), stats.history.statsFor(a)?.toutPlayed)
        assertEquals(ModeRecord(1, 1, 20), stats.gameModesOf(c)?.asPlayer?.getValue(GameType.RUFSPIEL))
        assertEquals(ModeRecord(1, 0, -20), stats.gameModesOf(a)?.atTable?.getValue(GameType.RUFSPIEL))
        assertEquals(RoleRecord(1, 0), stats.history.statsFor(a)?.kontraGiven)
        // A 300 − 20, B −100 − 20, C −100 + 20, D −100 + 20.
        assertEquals(listOf(280, -120, -80, -80), stats.finance.standings.map { it.balance })
    }

    @Test
    fun `wer an einem Abend nicht am Tisch sass, bleibt im Verlauf waagerecht und spielt dort nicht mit`() {
        val e = PlayerId("E")
        val withD = evening("mit-d", rufspiel(a, b), solo(d), day = 1)
        val withE = evening("mit-e", rufspiel(e, a), solo(e, won = false), day = 2, seating = listOf(a, b, c, e))

        val stats = StatisticEngine.forEvenings(listOf(withD, withE))
        // Abend 1: A +10 −50, B +10 −50, C −10 −50, D −10 +150. Abend 2: A +10 +50, B −10 +50, C −10 +50, E +10 −150.
        assertEquals(listOf(d, a, b, c, e), stats.players)
        assertEquals(
            mapOf(
                d to listOf(0, -10, 140, 140, 140),
                a to listOf(0, 10, -40, -30, 20),
                b to listOf(0, 10, -40, -50, 0),
                c to listOf(0, -10, -60, -70, -20),
                e to listOf(0, 0, 0, 10, -140),
            ),
            stats.finance.balanceSeries,
        )
        assertEquals(listOf(0, 2), stats.finance.eveningStarts)
        // D hat nur die zwei Spiele des ersten Abends am Tisch erlebt, E nur die des zweiten.
        assertEquals(1, stats.gameModesOf(d)?.atTable?.getValue(GameType.RUFSPIEL)?.games)
        assertEquals(1, stats.gameModesOf(d)?.atTable?.getValue(GameType.FARBSOLO)?.games)
        assertEquals(ModeRecord(1, 0, -150), stats.gameModesOf(e)?.atTable?.getValue(GameType.FARBSOLO))
        assertEquals(2, stats.gameModesOf(a)?.atTable?.getValue(GameType.FARBSOLO)?.games)
        assertNull(stats.partners.pairOf(d, e))
    }
}
