package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.EntryResult
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Rechnet jede Kennzahl der [StatisticEngine] an zufälligen Abenden auf einem zweiten, unabhängigen Weg nach.
 *
 * Der zweite Weg benutzt nur, was der [EveningCalculator] je Eintrag gebucht hat: Wer in einem Spiel Geld bekommt,
 * hat gewonnen; ein Gespann sind die zwei, die im selben Spiel beide bekommen oder beide zahlen; der Verlauf ist die
 * Summe aller Buchungen bis zu einem Spiel; was das Legen gekostet hat, zeigt der Abend, wenn man ihn ohne diesen
 * einen Leger noch einmal durchrechnet.
 */
class StatisticEngineRandomTest {

    private val pool = listOf("A", "B", "C", "D", "E", "F").map(::PlayerId)
    private val firstDay: Instant = Instant.parse("2026-01-01T18:00:00Z")

    /** Regelwerke, zwischen denen die Zufallsabende wechseln; in allen ist jedes Spiel mehr als 0 wert. */
    private val ruleSets = listOf(
        RuleSet.standard(),
        RuleSet.sauspiel(),
        RuleSet.standard().copy(payoutRule = PayoutRule.POT_SPLIT),
        RuleSet.standard().copy(bock = BockRules(stacking = BockStacking.STACK)),
        RuleSet.sauspiel().copy(payoutRule = PayoutRule.POT_SPLIT, bock = BockRules(gamesPerTrigger = 2)),
    )

    /**
     * Ein Zufallsabend mit zufälligem Regelwerk. Etwa jedes zwölfte Spiel lässt sich nicht abrechnen (unbekannter
     * Ansager oder fehlender Mitspieler): Es bekommt eine Nummer, bucht aber nichts.
     */
    private fun randomEvening(random: Random, id: String, day: Int): Evening {
        val evening = RandomEvenings.evening(
            random = random,
            id = id,
            seating = pool.shuffled(random).take(4),
            startedAt = firstDay.plusSeconds(86_400L * day),
        )
        return evening.copy(
            rules = ruleSets.random(random),
            entries = evening.entries.map { entry ->
                when {
                    entry !is GameEntry || random.nextInt(12) != 0 -> entry
                    entry.type.isSolo || random.nextBoolean() -> entry.copy(declarer = PlayerId("X"))
                    else -> entry.copy(partner = null)
                }
            },
        )
    }

    @Test
    fun `zufaellige Abende - jede Kennzahl stimmt mit der Nachrechnung auf dem zweiten Weg ueberein`() {
        val random = Random(20261115)
        var games = 0
        repeat(120) { round ->
            val evenings = List(random.nextInt(1, 6)) { index -> randomEvening(random, "runde-$round-abend-$index", index) }

            // Die ewige Tabelle über alle Abende der Runde, in zufälliger Reihenfolge übergeben.
            val table = StatisticEngine.forEvenings(evenings.shuffled(random))
            val reference = Reference(evenings)
            verify(table, reference, reference.playersByBalance())
            assertEquals(HistoryStatistics.compute(evenings), table.history)
            games += table.gameCount

            // Jeder Abend für sich.
            for (evening in evenings) {
                val single = StatisticEngine.forEvening(evening)
                verify(single, Reference(listOf(evening)), evening.seating)
                assertEquals(HistoryStatistics.compute(listOf(evening)), single.history)
            }
        }
        // Der Test soll nicht unbemerkt leerlaufen.
        assertTrue(games > 2000, "nur $games Spiele in den Zufallsabenden")
    }

    @Test
    fun `zufaellige Abende - die ewige Tabelle ist die Summe der einzelnen Abende`() {
        val random = Random(4711)
        repeat(60) { round ->
            val evenings = List(random.nextInt(2, 6)) { index -> randomEvening(random, "summe-$round-abend-$index", index) }
            val table = StatisticEngine.forEvenings(evenings)
            val singles = evenings.map { StatisticEngine.forEvening(it) }

            assertEquals(singles.sumOf { it.eveningCount }, table.eveningCount)
            assertEquals(singles.sumOf { it.gameCount }, table.gameCount)
            assertEquals(singles.sumOf { it.finance.turnover }, table.finance.turnover)
            assertEquals(singles.flatMap { it.players }.toSet(), table.players.toSet())

            for (player in table.players) {
                val parts = singles.filter { player in it.players }
                assertEquals(
                    parts.sumOf { checkNotNull(it.finance.standingOf(player)).balance },
                    table.finance.standingOf(player)?.balance,
                )
                for (type in GameType.entries) {
                    assertEquals(
                        parts.map { checkNotNull(it.gameModesOf(player)).atTable.getValue(type) }.sum(),
                        table.gameModesOf(player)?.atTable?.getValue(type),
                    )
                    assertEquals(
                        parts.map { checkNotNull(it.gameModesOf(player)).asPlayer.getValue(type) }.sum(),
                        table.gameModesOf(player)?.asPlayer?.getValue(type),
                    )
                }
                val gelegt = parts.map { checkNotNull(it.gelegtOf(player)) }
                assertEquals(
                    GelegtRecord(
                        player = player,
                        count = gelegt.sumOf { it.count },
                        won = gelegt.sumOf { it.won },
                        netBalance = gelegt.sumOf { it.netBalance },
                        effect = gelegt.sumOf { it.effect },
                    ),
                    table.gelegtOf(player),
                )
                val bock = parts.map { checkNotNull(it.bock.recordOf(player)) }
                assertEquals(
                    BockRecord(player, bock.sumOf { it.games }, bock.sumOf { it.won }, bock.sumOf { it.balance }),
                    table.bock.recordOf(player),
                )
                val solo = parts.map { checkNotNull(it.solo.recordOf(player)) }
                assertEquals(SoloRecord(player, solo.sumOf { it.solos }, solo.sumOf { it.calls }), table.solo.recordOf(player))
            }

            val pairs = singles.flatMap { it.partners.pairs }.groupBy { it.first to it.second }
            assertEquals(pairs.keys, table.partners.pairs.map { it.first to it.second }.toSet())
            for (pair in table.partners.pairs) {
                val parts = pairs.getValue(pair.first to pair.second)
                assertEquals(parts.sumOf { it.games }, pair.games)
                assertEquals(parts.sumOf { it.won }, pair.won)
                assertEquals(parts.sumOf { it.balance }, pair.balance)
            }
        }
    }

    private fun List<ModeRecord>.sum(): ModeRecord = ModeRecord(sumOf { it.games }, sumOf { it.won }, sumOf { it.balance })

    // ---------------------------------------------------------------------------------------------------------------
    // Der Vergleich
    // ---------------------------------------------------------------------------------------------------------------

    private fun verify(stats: Statistics, reference: Reference, players: List<PlayerId>) {
        assertEquals(players, stats.players)
        assertEquals(reference.eveningCount, stats.eveningCount)
        assertEquals(reference.games.size, stats.gameCount)

        // Finanz-Übersicht
        val balances = players.map { reference.balance(it) }
        assertEquals(
            players.map { player ->
                val balance = reference.balance(player)
                Standing(player, balance, rank = 1 + balances.count { it > balance })
            },
            stats.finance.standings,
        )
        assertEquals(players.associateWith { reference.series(it) }, stats.finance.balanceSeries)
        assertEquals(players, stats.finance.balanceSeries.keys.toList())
        assertEquals(reference.eveningStarts, stats.finance.eveningStarts)
        assertEquals(reference.eveningIds, stats.finance.eveningIds)
        assertEquals(reference.turnover, stats.finance.turnover)
        for (player in players) {
            val series = stats.finance.balanceSeries.getValue(player)
            assertEquals(stats.gameCount + 1, series.size)
            assertEquals(reference.balance(player), series.last())
            if (stats.gameCount > 0) assertEquals(0, series.first())
        }
        // Die freien Buchungen der Zufallsabende gleichen sich aus, also auch die Tabelle.
        assertEquals(0, stats.finance.standings.sumOf { it.balance })

        // Spielart-Analyse
        assertEquals(players, stats.gameModes.map { it.player })
        for (modes in stats.gameModes) {
            assertEquals(GameType.entries, modes.atTable.keys.toList())
            assertEquals(GameType.entries, modes.asPlayer.keys.toList())
            for (type in GameType.entries) {
                val atTable = reference.gamesOf(modes.player).filter { it.entry.type == type }
                assertEquals(reference.modeRecord(atTable, modes.player), modes.atTable.getValue(type))
                val asPlayer = atTable.filter { it.playedBy(modes.player) }
                assertEquals(reference.modeRecord(asPlayer, modes.player), modes.asPlayer.getValue(type))
            }
        }

        // Sollte-er-es-lassen-Index
        assertEquals(players, stats.gelegt.map { it.player })
        for (record in stats.gelegt) {
            val own = reference.gamesOf(record.player).filter { record.player in it.entry.klopfer }
            assertEquals(own.size, record.count)
            assertEquals(own.count { it.won(record.player) }, record.won)
            assertEquals(own.sumOf { it.amount(record.player) }, record.netBalance)
            assertEquals(own.sumOf { reference.costOfKlopfer(it, record.player) }, record.effect)
            assertEquals(referenceBadge(record), record.badge)
            assertEquals(record.badge == GelegtBadge.BRANDSTIFTER, record.shouldLeaveIt)
        }

        // Bock-König
        assertEquals(
            players.map { player ->
                val bockGames = reference.gamesOf(player).filter { it.result.bockExponent > 0 }
                BockRecord(player, bockGames.size, bockGames.count { it.won(player) }, bockGames.sumOf { it.amount(player) })
            },
            stats.bock.records,
        )
        var king: BockRecord? = null
        for (record in stats.bock.records) {
            if (record.balance > 0 && record.balance > (king?.balance ?: 0)) king = record
        }
        assertEquals(king, stats.bock.king)

        // Solo-Spezialist und Mauerblümchen
        assertEquals(
            players.map { player ->
                val announced = reference.gamesOf(player).filter { it.entry.type != GameType.UEBER_KREUZ && it.entry.declarer == player }
                SoloRecord(
                    player = player,
                    solos = announced.count { it.entry.type != GameType.RUFSPIEL },
                    calls = announced.count { it.entry.type == GameType.RUFSPIEL },
                )
            },
            stats.solo.records,
        )
        var specialist: SoloRecord? = null
        for (record in stats.solo.records) {
            if (record.solos > (specialist?.solos ?: 0)) specialist = record
        }
        assertEquals(specialist, stats.solo.specialist)
        var wallflower: SoloRecord? = null
        for (record in stats.solo.records) {
            if (record.announced < 3 || record == specialist) continue
            val share = checkNotNull(record.soloShare)
            val best = wallflower
            val bestShare = best?.soloShare
            if (best == null || bestShare == null || share < bestShare || (share == bestShare && record.announced > best.announced)) {
                wallflower = record
            }
        }
        assertEquals(wallflower?.takeIf { checkNotNull(it.soloShare) <= 0.2 }, stats.solo.wallflower)

        // Zweiergespanne
        val expectedPairs = reference.pairs()
        assertEquals(expectedPairs, stats.partners.pairs)
        assertEquals(expectedPairs.firstOrNull { it.games >= 3 }, stats.partners.favorite)
        assertEquals(0, stats.partners.pairs.sumOf { it.balance })
    }

    /** Die Abzeichen-Regel noch einmal, hier mit Quoten als Kommazahlen. */
    private fun referenceBadge(record: GelegtRecord): GelegtBadge {
        val rate = record.winRate
        return when {
            rate == null -> GelegtBadge.STREICHHOLZ_SPARER
            record.count < 3 -> GelegtBadge.NOCH_OFFEN
            rate >= 0.6 && record.netBalance >= 0 -> GelegtBadge.SICHERE_BANK
            rate <= 0.4 && record.netBalance < 0 -> GelegtBadge.BRANDSTIFTER
            record.netBalance > 0 -> GelegtBadge.GLUECKSRITTER
            else -> GelegtBadge.WACKELKANDIDAT
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Der zweite Weg
    // ---------------------------------------------------------------------------------------------------------------

    /** Ein abgerechnetes Spiel mit dem, was der Abend dafür gebucht hat. */
    private class BookedGame(val evening: Evening, val entry: GameEntry, val result: EntryResult) {
        fun amount(player: PlayerId): Int = result.amounts.getValue(player)

        /** In allen Regelwerken dieses Tests ist jedes Spiel mehr als 0 wert: Wer gewinnt, bekommt Geld. */
        fun won(player: PlayerId): Boolean = amount(player) > 0

        fun playedBy(player: PlayerId): Boolean = when (entry.type) {
            GameType.UEBER_KREUZ -> true
            GameType.RUFSPIEL -> entry.declarer == player || entry.partner == player
            else -> entry.declarer == player
        }
    }

    private class Reference(evenings: List<Evening>) {
        private val states: List<EveningState> = evenings
            .sortedWith(compareBy<Evening> { it.startedAt }.thenBy { it.id.value })
            .map { EveningCalculator.compute(it) }

        private fun gamesOf(state: EveningState): List<BookedGame> = state.results.mapNotNull { result ->
            val entry = result.entry as? GameEntry ?: return@mapNotNull null
            if (result.breakdown == null) null else BookedGame(state.evening, entry, result)
        }

        val games: List<BookedGame> = states.flatMap { gamesOf(it) }

        val eveningCount: Int = states.count { gamesOf(it).isNotEmpty() }

        val turnover: Int = states.sumOf { state -> state.results.sumOf { result -> result.amounts.values.filter { it > 0 }.sum() } }

        private val players: Set<PlayerId> = states.flatMap { it.evening.seating }.toSet()

        /** Alles, was für den Spieler je gebucht wurde. */
        fun balance(player: PlayerId): Int = states.sumOf { state -> state.results.sumOf { it.amounts[player] ?: 0 } }

        fun playersByBalance(): List<PlayerId> =
            players.sortedWith(compareByDescending<PlayerId> { balance(it) }.thenBy { it.value })

        /** Die Spiele der Abende, an denen der Spieler am Tisch saß. */
        fun gamesOf(player: PlayerId): List<BookedGame> = games.filter { player in it.evening.seating }

        fun modeRecord(games: List<BookedGame>, player: PlayerId): ModeRecord =
            ModeRecord(games.size, games.count { it.won(player) }, games.sumOf { it.amount(player) })

        /**
         * Der Verlauf als Summen: Der Punkt eines Spiels ist alles, was bis einschließlich dieses Spiels gebucht
         * wurde; der Punkt des letzten Spiels eines Abends enthält den ganzen Abend. Was vor dem Abend gebucht
         * wurde, kommt dazu, und der letzte Punkt ist der Saldo.
         */
        fun series(player: PlayerId): List<Int> {
            val points = ArrayList<Int>()
            points += 0
            var before = 0
            for (state in states) {
                val amounts = state.results.map { it.amounts[player] ?: 0 }
                val gameIndices = state.results.indices.filter { state.results[it].entry is GameEntry && state.results[it].breakdown != null }
                for ((position, index) in gameIndices.withIndex()) {
                    val upTo = if (position == gameIndices.lastIndex) amounts.size else index + 1
                    points += before + amounts.take(upTo).sum()
                }
                before += amounts.sum()
            }
            points[points.lastIndex] = before
            return points
        }

        val eveningStarts: List<Int>
        val eveningIds: List<EveningId>

        init {
            val starts = ArrayList<Int>()
            val ids = ArrayList<EveningId>()
            var gamesBefore = 0
            for (state in states) {
                val count = gamesOf(state).size
                if (count > 0) {
                    starts += gamesBefore
                    ids += state.evening.id
                }
                gamesBefore += count
            }
            eveningStarts = starts
            eveningIds = ids
        }

        /**
         * Was das eigene Legen gekostet (negativ) oder gebracht hat: der gebuchte Betrag minus der Betrag, den
         * derselbe Abend für dieses Spiel bucht, wenn der Spieler dort nicht gelegt hat.
         */
        fun costOfKlopfer(game: BookedGame, player: PlayerId): Int {
            val without = game.evening.replace(game.entry.copy(klopfer = game.entry.klopfer - player))
            val recalculated = EveningCalculator.compute(without).results.first { it.entry.id == game.entry.id }
            return game.amount(player) - recalculated.amounts.getValue(player)
        }

        /** Gespanne: die zwei, die in einem Spiel zu zweit gegen zwei beide bekommen, und die zwei, die beide zahlen. */
        fun pairs(): List<PartnerPair> {
            class Tally(var games: Int = 0, var won: Int = 0, var balance: Int = 0)

            val tallies = HashMap<List<PlayerId>, Tally>()
            for (game in games) {
                if (game.entry.type != GameType.RUFSPIEL && game.entry.type != GameType.UEBER_KREUZ) continue
                val (winners, losers) = game.evening.seating.partition { game.won(it) }
                check(winners.size == 2 && losers.size == 2)
                for (side in listOf(winners, losers)) {
                    val tally = tallies.getOrPut(side.sortedBy { it.value }) { Tally() }
                    tally.games += 1
                    if (side === winners) tally.won += 1
                    tally.balance += side.sumOf { game.amount(it) }
                }
            }
            return tallies
                .map { (pair, tally) -> PartnerPair(pair[0], pair[1], tally.games, tally.won, tally.balance) }
                .sortedWith(
                    compareByDescending<PartnerPair> { it.balance }
                        .thenByDescending { checkNotNull(it.winRate) }
                        .thenBy { it.first.value }
                        .thenBy { it.second.value },
                )
        }
    }
}
