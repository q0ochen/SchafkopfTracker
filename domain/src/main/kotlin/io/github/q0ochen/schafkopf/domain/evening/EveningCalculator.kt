package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameFacts
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.ValueBreakdown

/** Was an einem Eintrag nicht stimmt. Die App zeigt das an, rechnet aber trotzdem weiter. */
enum class EntryIssue {
    RULE_VIOLATION,
    AMOUNTS_NOT_ZERO_SUM,
    UNKNOWN_PLAYER,
}

/**
 * Ergebnis eines Eintrags im Verlauf des Abends.
 *
 * @property gameNumber Laufende Nummer; nur gespielte Spiele werden gezählt.
 * @property dealer Wer bei diesem Eintrag gegeben hat.
 * @property bockExponent 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden.
 * @property amounts Betrag je Spieler aus diesem Eintrag in Cent (Summe 0).
 * @property balances Stand je Spieler nach diesem Eintrag.
 * @property ranks Platz je Spieler nach diesem Eintrag; bei Gleichstand teilen sich Spieler einen Platz.
 */
data class EntryResult(
    val entry: EveningEntry,
    val gameNumber: Int?,
    val dealer: PlayerId,
    val bockExponent: Int,
    val breakdown: ValueBreakdown?,
    val amounts: Map<PlayerId, Int>,
    val balances: Map<PlayerId, Int>,
    val ranks: Map<PlayerId, Int>,
    val issues: List<EntryIssue>,
)

/**
 * Der vollständig durchgerechnete Abend.
 *
 * @property nextDealer Wer das nächste Spiel gibt.
 * @property nextBockExponent Bock-Stufe des nächsten Spiels (0 = kein Bockspiel).
 * @property bockGamesRemaining Wie viele der kommenden Spiele noch Bockspiele sind.
 */
data class EveningState(
    val evening: Evening,
    val results: List<EntryResult>,
    val balances: Map<PlayerId, Int>,
    val ranks: Map<PlayerId, Int>,
    val nextDealer: PlayerId,
    val nextBockExponent: Int,
    val bockGamesRemaining: Int,
    val gamesPlayed: Int,
)

/**
 * Leitet aus der Liste der Einträge alles Übrige ab: Beträge, laufende Salden, Plätze, Geberfolge und Bock-Zähler.
 *
 * Nichts davon wird gespeichert. Wird ein früherer Eintrag geändert oder gelöscht, stimmt danach wieder alles,
 * weil der ganze Abend neu durchgerechnet wird.
 */
object EveningCalculator {

    fun compute(evening: Evening): EveningState {
        val seating = evening.seating
        val rules = evening.rules
        val balances = seating.associateWith { 0 }.toMutableMap()
        val bock = BockQueue(rules.bock)
        var dealerIndex = seating.indexOf(evening.firstDealer).coerceAtLeast(0)
        var gamesPlayed = 0
        val results = ArrayList<EntryResult>(evening.entries.size)

        for (entry in evening.entries) {
            val dealer = seating[dealerIndex]
            var gameNumber: Int? = null
            var bockExponent = 0
            var breakdown: ValueBreakdown? = null
            var amounts: Map<PlayerId, Int> = seating.associateWith { 0 }
            val issues = ArrayList<EntryIssue>()
            var dealerAdvances = false

            when (entry) {
                is GameEntry -> {
                    bockExponent = when (entry.bockOverride) {
                        BockOverride.AUTO -> bock.exponent.also { bock.consume() }
                        BockOverride.FORCE_ON -> maxOf(1, bock.exponent).also { bock.consume() }
                        BockOverride.FORCE_OFF -> 0
                    }
                    val facts = entry.toFacts(seating, bockExponent)
                    if (ScoreCalculator.validate(facts, rules).isNotEmpty()) issues += EntryIssue.RULE_VIOLATION
                    // Abgerechnet wird nur, wenn feststeht, wer auf welcher Seite stand.
                    val computable = facts.declarer in seating &&
                        (facts.type.isSolo || (facts.partner != null && facts.partner in seating && facts.partner != facts.declarer))
                    if (computable) {
                        breakdown = ScoreCalculator.breakdown(facts, rules)
                        amounts = ScoreCalculator.amounts(facts, rules)
                    }
                    gamesPlayed += 1
                    gameNumber = gamesPlayed
                    dealerAdvances = true
                }

                is ThrownInEntry -> {
                    if (rules.bock.thrownInConsumesBockGame) bock.consume()
                    dealerAdvances = rules.dealerAdvancesAfterThrowIn
                }

                is BockTriggerEntry -> {
                    bock.trigger()
                    dealerAdvances = entry.handThrownIn && rules.dealerAdvancesAfterThrowIn
                }

                is ManualEntry -> {
                    if (entry.amounts.keys.any { it !in seating }) issues += EntryIssue.UNKNOWN_PLAYER
                    if (entry.amounts.values.sum() != 0) issues += EntryIssue.AMOUNTS_NOT_ZERO_SUM
                    amounts = seating.associateWith { entry.amounts[it] ?: 0 }
                }
            }

            for ((player, amount) in amounts) balances[player] = balances.getValue(player) + amount
            val balancesAfter = balances.toMap()
            results += EntryResult(
                entry = entry,
                gameNumber = gameNumber,
                dealer = dealer,
                bockExponent = bockExponent,
                breakdown = breakdown,
                amounts = amounts,
                balances = balancesAfter,
                ranks = Ranking.ranks(balancesAfter),
                issues = issues,
            )
            if (dealerAdvances) dealerIndex = (dealerIndex + 1) % seating.size
        }

        val finalBalances = balances.toMap()
        return EveningState(
            evening = evening,
            results = results,
            balances = finalBalances,
            ranks = Ranking.ranks(finalBalances),
            nextDealer = seating[dealerIndex],
            nextBockExponent = bock.exponent,
            bockGamesRemaining = bock.remaining,
            gamesPlayed = gamesPlayed,
        )
    }

    /** Die abrechnungsrelevanten Tatsachen eines Spiels. */
    fun GameEntry.toFacts(seating: List<PlayerId>, bockExponent: Int): GameFacts = GameFacts(
        type = type,
        players = seating,
        declarer = declarer,
        partner = partner,
        declarerWon = declarerWon,
        level = level,
        laufende = laufende,
        announcement = announcement,
        klopferCount = klopfer.size,
        kontra = kontra,
        bockExponent = bockExponent,
    )
}

/** Plätze aus Salden: Platz = 1 + Anzahl der Spieler mit höherem Saldo (Gleichstand ergibt denselben Platz). */
object Ranking {
    fun ranks(balances: Map<PlayerId, Int>): Map<PlayerId, Int> =
        balances.mapValues { (_, balance) -> 1 + balances.values.count { it > balance } }
}

/**
 * Zähler der offenen Bockspiele. Je nach Regel werden neue Bockrunden angehängt, überlagert oder ignoriert.
 */
internal class BockQueue(private val rules: BockRules) {
    /** Verbleibende Spiele je aktiver Bockrunde. Bei „anhängen“ und „ignorieren“ gibt es höchstens eine. */
    private val rounds = ArrayList<Int>()

    /** Bock-Stufe des nächsten Spiels. */
    val exponent: Int get() = rounds.size

    /** Anzahl der kommenden Spiele, die noch Bockspiele sind. */
    val remaining: Int get() = rounds.maxOrNull() ?: 0

    fun trigger() {
        if (!rules.enabled || rules.gamesPerTrigger <= 0) return
        when (rules.stacking) {
            BockStacking.APPEND ->
                if (rounds.isEmpty()) rounds += rules.gamesPerTrigger else rounds[0] = rounds[0] + rules.gamesPerTrigger

            BockStacking.STACK -> rounds += rules.gamesPerTrigger
            BockStacking.IGNORE -> if (rounds.isEmpty()) rounds += rules.gamesPerTrigger
        }
    }

    /** Ein Bockspiel ist gespielt: Jede aktive Bockrunde wird um ein Spiel kürzer. */
    fun consume() {
        for (i in rounds.indices) rounds[i] = rounds[i] - 1
        rounds.removeAll { it <= 0 }
    }
}
