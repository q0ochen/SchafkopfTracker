package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType

/**
 * Bilanz eines Spielerpaars aus den Spielen zu zweit gegen zwei (Rufspiel und Über Kreuz), in denen beide auf
 * derselben Seite standen.
 *
 * @property balance Gemeinsamer Saldo dieser Spiele in Cent: die Summe der Beträge beider Spieler.
 */
data class PairRecord(val played: Int, val won: Int, val balance: Int) {
    /** Wie viele dieser Spiele das Paar verloren hat. */
    val lost: Int get() = played - won

    /** Anteil der Siege zwischen 0 und 1; `null`, wenn das Paar so nie zusammen gespielt hat. */
    val rate: Double? get() = if (played == 0) null else won.toDouble() / played
}

/**
 * Wie gut zwei Spieler zusammenpassen. Das Paar ist ungeordnet; [first] ist der Spieler mit der kleineren Kennung.
 *
 * Bei Über Kreuz sagt niemand an: Dort ist das „Spielerpaar“ einfach das Paar, das im Eintrag als Ansager und
 * Mitspieler steht, das „Gegenspielerpaar“ das andere. Wer nur wissen will, wie zwei zusammen abschneiden, nimmt
 * die Paare der [StatisticEngine] (`Statistics.partners`).
 *
 * @property asDeclarerPair Spiele als Spielerpaar: einer hat angesagt, der andere war sein Mitspieler.
 * @property asOpponentPair Spiele, in denen die beiden gemeinsam die Gegenspieler waren.
 */
data class PartnerRecord(
    val first: PlayerId,
    val second: PlayerId,
    val asDeclarerPair: PairRecord,
    val asOpponentPair: PairRecord,
) {
    /** Ob dieser Eintrag zu den beiden Spielern gehört, egal in welcher Reihenfolge. */
    fun isPair(a: PlayerId, b: PlayerId): Boolean = (first == a && second == b) || (first == b && second == a)
}

/**
 * Die Statistik über viele Abende.
 *
 * Ein Abend ohne abgerechnetes Spiel gilt nicht als gespielter Abend: Er zählt weder in [eveningCount] noch bei den
 * Abenden, Abendsiegen und besten oder schlechtesten Abenden der Spieler. Was dort trotzdem eingetragen wurde, zählt
 * aber mit, damit kein gebuchter Betrag verloren geht: freie Buchungen (Saldo und Umsatz), zusammengeworfene Blätter
 * und ausgelöste Bockrunden. Laufende und beendete Abende zählen gleich.
 *
 * @property playerStats Kennzahlen je Spieler über alle Abende, an denen er am Tisch saß. Reihenfolge: höchster
 *   Saldo zuerst, bei Gleichstand nach Kennung.
 * @property eveningCount Abende mit mindestens einem abgerechneten Spiel.
 * @property gameCount Gespielte Spiele über alle Abende.
 * @property partnerRecords Je Spielerpaar die gemeinsamen Spiele zu zweit gegen zwei (Rufspiel und Über Kreuz),
 *   sortiert nach den Kennungen. Paare ohne gemeinsames Spiel fehlen.
 * @property gameTypeCounts Wie oft jede Spielart gespielt wurde. Jede Spielart ist enthalten, notfalls mit 0.
 * @property bockGamesCount Gespielte Spiele, die als Bockspiel doppelt (oder mehrfach) zählten.
 * @property bockTriggerCount Wie oft eine Bockrunde ausgelöst wurde. Gezählt wird jeder solche Eintrag, auch wenn er
 *   nach dem Regelwerk des Abends ohne Wirkung blieb (Bockrunde abgeschaltet oder Regel „ignorieren“).
 * @property thrownInCount Wie oft das Blatt zusammengeworfen wurde.
 * @property leadChanges Führungswechsel, über alle Abende zusammengezählt.
 * @property mostExpensiveGame Das Spiel mit dem höchsten Spielwert, bei Gleichstand das frühere; `null` ohne Spiel.
 * @property turnover Umsatz: die Summe aller positiven Beträge aller Einträge, auch der freien Buchungen.
 */
data class HistoryStats(
    val playerStats: List<PlayerStats>,
    val eveningCount: Int,
    val gameCount: Int,
    val partnerRecords: List<PartnerRecord>,
    val gameTypeCounts: Map<GameType, Int>,
    val bockGamesCount: Int,
    val bockTriggerCount: Int,
    val thrownInCount: Int,
    val leadChanges: Int,
    val mostExpensiveGame: ExpensiveGame?,
    val turnover: Int,
) {
    /** Die Kennzahlen eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun statsFor(player: PlayerId): PlayerStats? = playerStats.firstOrNull { it.player == player }

    /** Die gemeinsamen Spiele zweier Spieler zu zweit gegen zwei; `null`, wenn es keine gibt. */
    fun partnerRecord(a: PlayerId, b: PlayerId): PartnerRecord? = partnerRecords.firstOrNull { it.isPair(a, b) }
}

/**
 * Berechnet die Statistik über viele Abende. Reine Funktion; nichts wird gespeichert.
 *
 * Jeder Abend wird mit [EveningCalculator] neu durchgerechnet. Die Reihenfolge der übergebenen Abende spielt keine
 * Rolle: Ausgewertet wird in zeitlicher Reihenfolge, damit bei Gleichstand immer das frühere Ereignis gewinnt.
 * Ein doppelt übergebener Abend (gleiche Kennung) zählt nur einmal.
 */
object HistoryStatistics {

    fun compute(evenings: List<Evening>): HistoryStats = fromDigests(digests(evenings))

    /** Rechnet jeden Abend genau einmal durch: ohne Dubletten und in zeitlicher Reihenfolge. */
    internal fun digests(evenings: List<Evening>): List<EveningDigest> =
        evenings
            .distinctBy { it.id }
            .sortedWith(compareBy<Evening> { it.startedAt }.thenBy { it.id.value })
            .map { EveningDigest(EveningCalculator.compute(it)) }

    /** @param digests Die durchgerechneten Abende in zeitlicher Reihenfolge, jeder genau einmal. */
    internal fun fromDigests(digests: List<EveningDigest>): HistoryStats {
        val table = TableAccumulator()
        for (digest in digests) table.add(digest)

        val playerStats = digests.flatMap { it.seating }.distinct()
            .map { player ->
                val accumulator = PlayerAccumulator(player)
                for (digest in digests) {
                    if (player in digest.seating) accumulator.add(digest)
                }
                accumulator.build()
            }
            .sortedWith(compareByDescending<PlayerStats> { it.balance }.thenBy { it.player.value })

        return HistoryStats(
            playerStats = playerStats,
            eveningCount = digests.count { it.games.isNotEmpty() },
            gameCount = table.gameCount,
            partnerRecords = partnerRecords(digests),
            gameTypeCounts = table.gameTypeCounts,
            bockGamesCount = table.bockGamesCount,
            bockTriggerCount = table.bockTriggerCount,
            thrownInCount = table.thrownInCount,
            leadChanges = table.leadChanges,
            mostExpensiveGame = table.mostExpensiveGame,
            turnover = table.turnover,
        )
    }

    private fun partnerRecords(digests: List<EveningDigest>): List<PartnerRecord> {
        val declarerPairs = LinkedHashMap<PairKey, PairTally>()
        val opponentPairs = LinkedHashMap<PairKey, PairTally>()
        for (digest in digests) {
            for (game in digest.games) {
                if (game.entry.type.isSolo) continue
                val (declarers, opponents) = digest.seating.partition { it in game.declarerSide }
                if (declarers.size != 2 || opponents.size != 2) continue
                declarerPairs.getOrPut(PairKey.of(declarers)) { PairTally() }.add(game, declarers)
                opponentPairs.getOrPut(PairKey.of(opponents)) { PairTally() }.add(game, opponents)
            }
        }
        return (declarerPairs.keys + opponentPairs.keys)
            .sortedWith(compareBy<PairKey> { it.first.value }.thenBy { it.second.value })
            .map { key ->
                PartnerRecord(
                    first = key.first,
                    second = key.second,
                    asDeclarerPair = declarerPairs[key]?.toRecord() ?: NO_GAMES,
                    asOpponentPair = opponentPairs[key]?.toRecord() ?: NO_GAMES,
                )
            }
    }

    private val NO_GAMES = PairRecord(played = 0, won = 0, balance = 0)

    /** Ein ungeordnetes Spielerpaar: Die kleinere Kennung steht immer vorn. */
    private data class PairKey(val first: PlayerId, val second: PlayerId) {
        companion object {
            fun of(pair: List<PlayerId>): PairKey {
                val (first, second) = pair.sortedBy { it.value }
                return PairKey(first, second)
            }
        }
    }

    private class PairTally {
        private var played = 0
        private var won = 0
        private var balance = 0

        fun add(game: PlayedGame, pair: List<PlayerId>) {
            played += 1
            if (game.won(pair.first())) won += 1
            balance += pair.sumOf { game.amount(it) }
        }

        fun toRecord(): PairRecord = PairRecord(played, won, balance)
    }
}
