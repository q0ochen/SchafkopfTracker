package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.model.AmountFormatter
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.stats.EveningDigest
import io.github.q0ochen.schafkopf.domain.stats.GelegtBadge
import io.github.q0ochen.schafkopf.domain.stats.GelegtRecord
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import java.time.ZoneId

/** Wie oft ein Spieler einen Abend gewonnen hat (auch geteilt). */
data class EveningWins(val player: SummaryPlayer, val count: Int)

/** Der Bock-König: wer in Bockspielen am meisten gewonnen hat. [balance] ist sein Saldo daraus, mit Vorzeichen. */
data class BockKing(val player: SummaryPlayer, val balance: String, val games: Int)

/** Was ein Spieler ansagt: [solos] Einzelspiele unter [announced] angesagten Spielen. */
data class Announcements(val player: SummaryPlayer, val solos: Int, val announced: Int)

/**
 * Die Lieblingspartner: das erfolgreichste Gespann aus Rufspiel und Über Kreuz.
 *
 * @property balance Der gemeinsame Saldo beider Spieler aus diesen Spielen, mit Vorzeichen.
 */
data class FavoritePartners(
    val first: SummaryPlayer,
    val second: SummaryPlayer,
    val won: Int,
    val games: Int,
    val balance: String,
)

/** Was beim Legen herauskam: [count]-mal gelegt, [won] davon gewonnen, unterm Strich [balance] (mit Vorzeichen). */
data class KlopferRecord(val player: SummaryPlayer, val count: Int, val won: Int, val balance: String)

/** Das teuerste Spiel über alle Abende, mit dem Datum seines Abends (TT.MM.JJJJ). */
data class HistoryGame(val date: String, val game: SummaryGame)

/**
 * Alle vorab berechneten Fakten über viele Abende („ewige Tabelle“) für den Kommentator. Wie bei [SummaryFacts]
 * sind Beträge fertige Texte, damit weder die Vorlage noch ein Sprachmodell rechnen muss; wo es nichts zu berichten
 * gibt, ist das Feld `null` oder die Liste leer.
 *
 * @property firstDate Datum des ersten Abends mit einem Spiel (TT.MM.JJJJ); `null` ohne ein einziges Spiel.
 * @property lastDate Datum des letzten Abends mit einem Spiel; `null` ohne ein einziges Spiel.
 * @property evenings Abende mit mindestens einem Spiel.
 * @property asMoney `true`: Beträge in Euro, `false`: in Punkten.
 * @property players Alle Spieler, höchster Saldo zuerst (wie in der Statistik). [SummaryPlayer.seat] ist hier die
 *   Stelle in dieser Liste ab 1; aus ihr entsteht der Platzhalter `[[S1]]`, `[[S2]]` und so weiter.
 * @property standings Die ewige Tabelle, bester Platz zuerst.
 * @property leader Wer die ewige Tabelle anführt; `null`, wenn alle gleich stehen.
 * @property payer Wer bisher am meisten bezahlt hat (letzter Platz); `null`, wenn alle gleich stehen.
 * @property eveningWins Wer wie oft einen Abend gewonnen hat, die meisten zuerst; ohne Abendsieg nicht dabei.
 * @property soloSpecialist Wer die meisten Einzelspiele angesagt hat; `null`, wenn es keinen gibt.
 * @property wallflower Wer fast nur ruft; `null`, wenn es keinen gibt.
 * @property safeBets Wer beim Legen eine sichere Bank ist (siehe [GelegtBadge.SICHERE_BANK]).
 * @property firestarters Wer es beim Legen lieber lassen sollte (siehe [GelegtBadge.BRANDSTIFTER]).
 * @property mostExpensiveGame Das Spiel mit dem höchsten Spielwert; `null` ohne Spiel.
 * @property winStreak Die längste Siegesserie innerhalb eines Abends, ab drei Spielen; sonst `null`.
 * @property bockRounds Wie oft eine Bockrunde ausgelöst wurde.
 */
data class HistoryFacts(
    val firstDate: String?,
    val lastDate: String?,
    val evenings: Int,
    val gamesPlayed: Int,
    val asMoney: Boolean,
    val players: List<SummaryPlayer>,
    val standings: List<SummaryStanding>,
    val leader: SummaryPlace?,
    val payer: SummaryPlace?,
    val eveningWins: List<EveningWins>,
    val bockKing: BockKing?,
    val soloSpecialist: Announcements?,
    val wallflower: Announcements?,
    val favoritePartners: FavoritePartners?,
    val safeBets: List<KlopferRecord>,
    val firestarters: List<KlopferRecord>,
    val mostExpensiveGame: HistoryGame?,
    val winStreak: WinStreak?,
    val bockRounds: Int,
)

/**
 * Stellt aus vielen Abenden die Fakten für den Kommentar über alle Abende zusammen. Gerechnet wird mit der
 * [StatisticEngine]; ihre Auszeichnungen (Bock-König, Solo-Spezialist und so weiter) gelten unverändert.
 *
 * Reine Funktion: Dieselbe Eingabe ergibt immer dieselben Fakten. Bei Gleichstand entscheidet die Reihenfolge der
 * Statistik (höchster Saldo zuerst, dann nach Kennung).
 */
object HistoryFactsBuilder {

    /** Wie [build] mit Zeitzone; die Daten der Abende stehen in der Zeitzone des Geräts. */
    fun build(
        evenings: List<Evening>,
        names: Map<PlayerId, String>,
        formatter: AmountFormatter,
        asMoney: Boolean,
    ): HistoryFacts = build(evenings, names, formatter, asMoney, ZoneId.systemDefault())

    /**
     * @param names Die Namen der Spieler. Fehlt ein Name, heißt der Spieler „Spieler 1“, „Spieler 2“ und so weiter.
     * @param formatter Macht aus Cent-Beträgen Texte; kommt aus der Oberfläche.
     * @param asMoney Euro oder Punkte, wie in der Statistik über alle Abende.
     * @param zone Zeitzone, in der die Daten der Abende angegeben werden.
     */
    fun build(
        evenings: List<Evening>,
        names: Map<PlayerId, String>,
        formatter: AmountFormatter,
        asMoney: Boolean,
        zone: ZoneId,
    ): HistoryFacts {
        val amounts = SummaryFactsBuilder.Amounts(formatter, asMoney)
        val stats = StatisticEngine.forEvenings(evenings)
        val players = stats.players.mapIndexed { index, id ->
            val seat = index + 1
            SummaryPlayer(id, seat, SummaryFactsBuilder.oneLine(names[id].orEmpty()).ifEmpty { "Spieler $seat" })
        }
        val playerOf = players.associateBy { it.id }
        // Dieselbe Kennung doppelt: Wie in der Statistik zählt die zuerst übergebene Fassung.
        val eveningOf = evenings.distinctBy { it.id }.associateBy { it.id }
        val played = stats.finance.eveningIds.mapNotNull { eveningOf[it] }
        val balances = stats.finance.standings.associate { it.player to it.balance }
        val allEqual = balances.values.distinct().size <= 1

        fun place(balance: Int?): SummaryPlace? = balance?.takeIf { !allEqual }?.let { value ->
            SummaryPlace(players.filter { balances[it.id] == value }, amounts.signed(value))
        }

        fun klopfer(badge: GelegtBadge): List<KlopferRecord> = stats.gelegt
            .filter { it.badge == badge }
            .mapNotNull { record -> klopferRecord(record, playerOf, amounts) }

        return HistoryFacts(
            firstDate = played.firstOrNull()?.let { SummaryFactsBuilder.formatDate(it.startedAt, zone) },
            lastDate = played.lastOrNull()?.let { SummaryFactsBuilder.formatDate(it.startedAt, zone) },
            evenings = stats.eveningCount,
            gamesPlayed = stats.gameCount,
            asMoney = asMoney,
            players = players,
            standings = stats.finance.standings
                .mapNotNull { standing ->
                    playerOf[standing.player]?.let { SummaryStanding(it, standing.rank, amounts.signed(standing.balance)) }
                }
                .sortedBy { it.rank },
            leader = place(balances.values.maxOrNull()),
            payer = place(balances.values.minOrNull()),
            eveningWins = players
                .map { EveningWins(it, stats.history.statsFor(it.id)?.eveningWins ?: 0) }
                .filter { it.count > 0 }
                .sortedByDescending { it.count },
            bockKing = stats.bock.king?.let { king ->
                playerOf[king.player]?.let { BockKing(it, amounts.signed(king.balance), king.games) }
            },
            soloSpecialist = stats.solo.specialist?.let { record ->
                playerOf[record.player]?.let { Announcements(it, record.solos, record.announced) }
            },
            wallflower = stats.solo.wallflower?.let { record ->
                playerOf[record.player]?.let { Announcements(it, record.solos, record.announced) }
            },
            favoritePartners = stats.partners.favorite?.let { pair ->
                val first = playerOf[pair.first]
                val second = playerOf[pair.second]
                if (first == null || second == null) null else FavoritePartners(first, second, pair.won, pair.games, amounts.signed(pair.balance))
            },
            safeBets = klopfer(GelegtBadge.SICHERE_BANK),
            firestarters = klopfer(GelegtBadge.BRANDSTIFTER),
            mostExpensiveGame = stats.history.mostExpensiveGame?.let { top ->
                val evening = eveningOf[top.eveningId] ?: return@let null
                val game = EveningDigest(EveningCalculator.compute(evening)).games.firstOrNull { it.entry.id == top.entryId }
                    ?: return@let null
                SummaryFactsBuilder.summaryGame(game, playerOf, amounts)?.let {
                    HistoryGame(SummaryFactsBuilder.formatDate(evening.startedAt, zone), it)
                }
            },
            winStreak = players
                .map { WinStreak(it, stats.history.statsFor(it.id)?.longestWinStreak ?: 0) }
                .filter { it.length >= SummaryFactsBuilder.MIN_WIN_STREAK }
                .maxByOrNull { it.length },
            bockRounds = stats.history.bockTriggerCount,
        )
    }

    private fun klopferRecord(
        record: GelegtRecord,
        playerOf: Map<PlayerId, SummaryPlayer>,
        amounts: SummaryFactsBuilder.Amounts,
    ): KlopferRecord? = playerOf[record.player]?.let { KlopferRecord(it, record.count, record.won, amounts.signed(record.netBalance)) }
}
