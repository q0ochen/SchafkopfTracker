package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.SettleUp
import io.github.q0ochen.schafkopf.domain.model.AmountFormatter
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.Suit
import io.github.q0ochen.schafkopf.domain.stats.EveningDigest
import io.github.q0ochen.schafkopf.domain.stats.EveningStatistics
import io.github.q0ochen.schafkopf.domain.stats.EveningStats
import io.github.q0ochen.schafkopf.domain.stats.PlayedGame
import io.github.q0ochen.schafkopf.domain.stats.PlayerStats
import java.time.Instant
import java.time.ZoneId

/**
 * Stellt aus einem durchgerechneten Abend die Fakten für die Zusammenfassung zusammen.
 *
 * Reine Funktion: Dieselbe Eingabe ergibt immer dieselben Fakten. Bei Gleichstand entscheidet die Sitzreihenfolge.
 */
object SummaryFactsBuilder {

    /** Ab so vielen gewonnenen Einzelspielen gibt es einen Solo-König. */
    const val MIN_SOLO_WINS = 2

    /** Ab so vielen gewonnenen Spielen am Stück wird eine Siegesserie erwähnt. */
    const val MIN_WIN_STREAK = 3

    /**
     * Wie [build] mit Zeitzone; das Datum des Abends steht in der Zeitzone des Geräts.
     *
     * @param names Die Namen der Spieler. Fehlt ein Name, heißt der Spieler „Spieler 1“ bis „Spieler 4“.
     * @param formatter Macht aus Cent-Beträgen Texte; kommt aus der Oberfläche (in Euro formatiert wird nur dort).
     */
    fun build(state: EveningState, names: Map<PlayerId, String>, formatter: AmountFormatter): SummaryFacts =
        build(state, names, formatter, ZoneId.systemDefault())

    /**
     * @param names Die Namen der Spieler. Fehlt ein Name, heißt der Spieler „Spieler 1“ bis „Spieler 4“.
     * @param formatter Macht aus Cent-Beträgen Texte; kommt aus der Oberfläche (in Euro formatiert wird nur dort).
     * @param zone Zeitzone, in der das Datum des Abends angegeben wird.
     */
    fun build(state: EveningState, names: Map<PlayerId, String>, formatter: AmountFormatter, zone: ZoneId): SummaryFacts {
        val evening = state.evening
        val asMoney = evening.rules.displayAsMoney
        val amounts = Amounts(formatter, asMoney)
        val digest = EveningDigest(state)
        val stats = EveningStatistics.compute(digest)

        val players = digest.seating.mapIndexed { index, id ->
            val seat = index + 1
            SummaryPlayer(id, seat, oneLine(names[id].orEmpty()).ifEmpty { "Spieler $seat" })
        }
        val playerOf = players.associateBy { it.id }
        val statsOf = stats.playerStats.associateBy { it.player }

        return SummaryFacts(
            date = formatDate(evening.startedAt, zone),
            location = oneLine(evening.location).ifEmpty { null },
            gamesPlayed = stats.gamesPlayed,
            asMoney = asMoney,
            players = players,
            standings = players
                .map { player ->
                    SummaryStanding(
                        player = player,
                        rank = state.ranks[player.id] ?: 1,
                        balance = amounts.signed(digest.balance(player.id)),
                    )
                }
                .sortedBy { it.rank },
            winner = if (digest.allEqual) null else place(players, digest, digest.highestBalance, amounts),
            payer = if (digest.allEqual) null else place(players, digest, digest.lowestBalance, amounts),
            klopferLoser = klopferLoser(players, digest, amounts),
            schneiderLosers = schneiderLosers(players, statsOf),
            gameOfTheEvening = gameOfTheEvening(stats, digest, playerOf, amounts),
            leadChanges = stats.leadChanges,
            bockRounds = bockRounds(players, stats, statsOf),
            soloKing = players
                .map { player -> SoloKing(player, soloWins(statsOf[player.id])) }
                .filter { it.soloWins >= MIN_SOLO_WINS }
                .maxByOrNull { it.soloWins },
            winStreak = players
                .map { player -> WinStreak(player, statsOf[player.id]?.longestWinStreak ?: 0) }
                .filter { it.length >= MIN_WIN_STREAK }
                .maxByOrNull { it.length },
            transfers = transfers(state, playerOf, amounts),
        )
    }

    /** Bündelt die Formatierung der Oberfläche mit der Einheit (Euro oder Punkte). */
    internal class Amounts(private val formatter: AmountFormatter, private val asMoney: Boolean) {
        /** Betrag ohne Plus, zum Beispiel ein Spielwert. */
        fun plain(cents: Int): String = formatter.format(cents, asMoney, false)

        /** Betrag mit Vorzeichen, zum Beispiel ein Saldo. */
        fun signed(cents: Int): String = formatter.format(cents, asMoney, true)
    }

    /** Namen und Ort stehen in der Zusammenfassung in einer Zeile: ohne Rand und ohne Zeilenumbrüche. */
    internal fun oneLine(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /** Datum im Format TT.MM.JJJJ, unabhängig von der Sprache des Geräts. */
    internal fun formatDate(instant: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        return padded(date.dayOfMonth, 2) + "." + padded(date.monthValue, 2) + "." + padded(date.year, 4)
    }

    private fun padded(number: Int, length: Int): String = number.toString().padStart(length, '0')

    /** Alle Spieler, die mit genau diesem Saldo auf dem ersten bzw. letzten Platz stehen. */
    private fun place(players: List<SummaryPlayer>, digest: EveningDigest, balance: Int, amounts: Amounts): SummaryPlace =
        SummaryPlace(
            players = players.filter { digest.balance(it.id) == balance },
            balance = amounts.signed(balance),
        )

    /**
     * Wer in selbst geklopften Spielen zusammen am meisten verloren hat. Gewonnene Spiele mit eigenem Klopfer
     * werden nicht gegengerechnet.
     */
    private fun klopferLoser(players: List<SummaryPlayer>, digest: EveningDigest, amounts: Amounts): KlopferLoser? =
        players
            .map { player ->
                val lostGames = digest.games.filter { player.id in it.entry.klopfer && !it.won(player.id) }
                KlopferLoss(player, lostGames.size, lostGames.sumOf { it.amount(player.id) })
            }
            .filter { it.amount < 0 }
            .minByOrNull { it.amount }
            ?.let { KlopferLoser(it.player, it.games, amounts.plain(-it.amount)) }

    private class KlopferLoss(val player: SummaryPlayer, val games: Int, val amount: Int)

    private fun schneiderLosers(players: List<SummaryPlayer>, statsOf: Map<PlayerId, PlayerStats>): List<SchneiderLoser> =
        players
            .mapNotNull { player ->
                val schneider = statsOf[player.id]?.schneiderLost ?: 0
                val schwarz = statsOf[player.id]?.schwarzLost ?: 0
                if (schneider + schwarz > 0) SchneiderLoser(player, schneider, schwarz) else null
            }
            .sortedByDescending { it.schneider + it.schwarz }

    private fun gameOfTheEvening(
        stats: EveningStats,
        digest: EveningDigest,
        playerOf: Map<PlayerId, SummaryPlayer>,
        amounts: Amounts,
    ): SummaryGame? {
        val top = stats.mostExpensiveGame ?: return null
        val game = digest.games.firstOrNull { it.entry.id == top.entryId } ?: return null
        return summaryGame(game, playerOf, amounts)
    }

    /** Ein Spiel für die Zusammenfassung; `null`, wenn der Ansager nicht unter den Spielern ist. */
    internal fun summaryGame(game: PlayedGame, playerOf: Map<PlayerId, SummaryPlayer>, amounts: Amounts): SummaryGame? {
        val entry = game.entry
        val declarer = playerOf[entry.declarer] ?: return null
        return SummaryGame(
            number = game.gameNumber,
            type = typeLabel(entry),
            declarer = declarer,
            partner = entry.partner?.takeIf { !entry.type.isSolo }?.let { playerOf[it] },
            won = entry.declarerWon,
            value = amounts.plain(game.value),
            declarerAmount = amounts.signed(game.amount(declarer.id)),
            specials = specials(game),
        )
    }

    /** Die Spielart, wie man sie am Tisch nennt; ein Farbsolo mit bekannter Trumpffarbe heißt zum Beispiel „Herz-Solo“. */
    private fun typeLabel(entry: GameEntry): String {
        val suit = entry.suit
        if (entry.type == GameType.FARBSOLO && suit != null) return suitLabel(suit) + "-Solo"
        return TYPE_LABELS[entry.type] ?: entry.type.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Kommt später eine Spielart dazu, die hier noch fehlt, erscheint sie unter ihrem eigenen Namen. */
    private val TYPE_LABELS = mapOf(
        GameType.RUFSPIEL to "Rufspiel",
        GameType.UEBER_KREUZ to "Über Kreuz",
        GameType.FARBSOLO to "Farbsolo",
        GameType.WENZ to "Wenz",
        GameType.GEIER to "Geier",
    )

    private fun suitLabel(suit: Suit): String = when (suit) {
        Suit.EICHEL -> "Eichel"
        Suit.GRAS -> "Gras"
        Suit.HERZ -> "Herz"
        Suit.SCHELLEN -> "Schellen"
    }

    /** Was das Spiel teuer gemacht hat, in der Reihenfolge der Abrechnung. */
    private fun specials(game: PlayedGame): List<String> {
        val entry = game.entry
        val specials = ArrayList<String>()
        when (entry.announcement) {
            Announcement.TOUT -> specials += "Tout"
            Announcement.SIE -> specials += "Sie"
            Announcement.NONE -> when (entry.level) {
                ResultLevel.NORMAL -> Unit
                ResultLevel.SCHNEIDER -> specials += "Schneider"
                ResultLevel.SCHWARZ -> specials += "Schwarz"
            }
        }
        // Beim Sie gehören die Laufenden zur Ansage und werden nicht eigens genannt.
        val laufende = game.breakdown.laufendeCounted
        if (laufende > 0 && entry.announcement != Announcement.SIE) specials += "$laufende Laufende"
        val klopfer = entry.klopfer.size
        if (klopfer > 0) specials += "$klopfer Klopfer"
        when (entry.kontra) {
            KontraLevel.NONE -> Unit
            KontraLevel.KONTRA -> specials += "Kontra"
            KontraLevel.RETOUR -> specials += "Kontra und Retour"
        }
        // Bei überlagerten Bockrunden steht der Faktor dabei, mit dem das Spiel zählte.
        when {
            game.bockExponent == 1 -> specials += "Bockspiel"
            game.bockExponent > 1 -> specials += "Bockspiel ×${game.breakdown.bockFactor}"
        }
        return specials
    }

    private fun bockRounds(
        players: List<SummaryPlayer>,
        stats: EveningStats,
        statsOf: Map<PlayerId, PlayerStats>,
    ): BockRounds? {
        if (stats.bockTriggerCount == 0) return null
        val nixer = players.mapNotNull { player ->
            val count = statsOf[player.id]?.nixerTriggers ?: 0
            if (count > 0) NixerCount(player, count) else null
        }
        return BockRounds(stats.bockTriggerCount, nixer)
    }

    /** Gewonnene Einzelspiele (Farbsolo, Wenz, Geier) als Ansager. */
    private fun soloWins(stats: PlayerStats?): Int =
        stats?.declarerByType?.filterKeys { it.isSolo }?.values?.sumOf { it.won } ?: 0

    /** „Wer zahlt wem“. Gehen die Salden wegen einer fehlerhaften freien Buchung nicht auf, bleibt die Liste leer. */
    private fun transfers(
        state: EveningState,
        playerOf: Map<PlayerId, SummaryPlayer>,
        amounts: Amounts,
    ): List<SummaryTransfer> {
        if (!SettleUp.isBalanced(state.balances)) return emptyList()
        return SettleUp.transfers(state.balances).mapNotNull { transfer ->
            val from = playerOf[transfer.from] ?: return@mapNotNull null
            val to = playerOf[transfer.to] ?: return@mapNotNull null
            SummaryTransfer(from, to, amounts.plain(transfer.amount))
        }
    }
}
