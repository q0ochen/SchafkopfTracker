package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameFacts
import io.github.q0ochen.schafkopf.domain.rules.RuleViolation
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.ValueBreakdown

/** Was an einem Eintrag nicht stimmt. Die App zeigt das an, rechnet aber trotzdem weiter. */
enum class EntryIssue {
    /** Das Spiel passt nicht zum Regelwerk (siehe [ScoreCalculator.validate]). */
    RULE_VIOLATION,

    /** Die Beträge einer freien Buchung ergeben zusammen nicht 0. */
    AMOUNTS_NOT_ZERO_SUM,

    /** In einer freien Buchung oder unter denen, die gelegt haben, steht jemand, der nicht am Tisch sitzt. */
    UNKNOWN_PLAYER,

    /** Mit diesem Eintrag passte ein Saldo nicht mehr in den Zahlenbereich; er wurde deshalb nicht gebucht. */
    BALANCE_OUT_OF_RANGE,
}

/**
 * Ergebnis eines Eintrags im Verlauf des Abends.
 *
 * @property gameNumber Laufende Nummer; nur gespielte Spiele werden gezählt.
 * @property dealer Wer bei diesem Eintrag gegeben hat.
 * @property bockExponent 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden.
 * @property breakdown Aufschlüsselung des Spielwerts; `null` bei allem, was kein Spiel ist, und bei einem Spiel, das
 *   sich nicht abrechnen ließ und deshalb nichts gebucht hat.
 * @property amounts Betrag je Spieler aus diesem Eintrag in Cent. Die Summe ist 0; nur eine freie Buchung, die als
 *   fehlerhaft markiert ist, kann davon abweichen.
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
 * @property nextDealer Wer das nächste Spiel gibt. Ohne Sitzordnung (beschädigte Daten) der eingetragene erste Geber.
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
 *
 * Auch mit beschädigten Daten (leere oder falsche Sitzordnung, unbekannte Spieler, unsinnige Werte im Regelwerk)
 * wirft [compute] nie. Ein Spiel bucht dann entweder ein Nullsummenspiel oder gar nichts; was nichts bucht, ist als
 * fehlerhaft markiert ([EntryResult.issues]).
 */
object EveningCalculator {

    /**
     * Regelverstöße, bei denen sich ein Spiel nicht abrechnen lässt. Alle anderen werden nur angezeigt; das gilt
     * auch für eine falsche Zahl von Spielern am Tisch (bei dreien zahlt oder bekommt der Einzelne beide Anteile).
     */
    private val NOT_COMPUTABLE = setOf(
        RuleViolation.DUPLICATE_PLAYERS,
        RuleViolation.DECLARER_NOT_AT_TABLE,
        RuleViolation.PARTNER_MISSING,
        RuleViolation.PARTNER_NOT_AT_TABLE,
        RuleViolation.PARTNER_IS_DECLARER,
        RuleViolation.VALUE_OUT_OF_RANGE,
    )

    fun compute(evening: Evening): EveningState {
        val seating = evening.seating
        val rules = evening.rules
        val balances = seating.associateWith { 0 }.toMutableMap()
        val bock = BockQueue(rules.bock)
        // Sitzt der eingetragene erste Geber nicht am Tisch, beginnt der erste Platz.
        var dealerIndex = seating.indexOf(evening.firstDealer).coerceAtLeast(0)
        var gamesPlayed = 0
        val results = ArrayList<EntryResult>(evening.entries.size)

        for (entry in evening.entries) {
            val dealer = dealerAt(evening, dealerIndex)
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
                    val violations = ScoreCalculator.validate(facts, rules)
                    if (violations.isNotEmpty()) issues += EntryIssue.RULE_VIOLATION
                    // Wer gelegt hat, verdoppelt den Betrag: Ein Unbekannter darunter wird deshalb gemeldet.
                    if (entry.klopfer.any { it !in seating }) issues += EntryIssue.UNKNOWN_PLAYER
                    // Abgerechnet wird nur, wenn feststeht, wer auf welcher Seite stand, wenn niemand doppelt am Tisch
                    // sitzt und wenn sich mit dem Spielwert rechnen lässt.
                    if (violations.none { it in NOT_COMPUTABLE }) {
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
                    // Als Long summiert: Riesige Beträge dürfen sich nicht durch einen Überlauf scheinbar zu 0 ergänzen.
                    if (entry.amounts.values.sumOf { it.toLong() } != 0L) issues += EntryIssue.AMOUNTS_NOT_ZERO_SUM
                    amounts = seating.associateWith { entry.amounts[it] ?: 0 }
                }
            }

            // Passt ein Saldo danach nicht mehr in einen Int, wird der Eintrag gemeldet und nicht gebucht, statt mit
            // einem übergelaufenen Saldo weiterzurechnen.
            if (amounts.any { (player, amount) -> balances.getValue(player).toLong() + amount !in Int.MIN_VALUE..Int.MAX_VALUE }) {
                issues += EntryIssue.BALANCE_OUT_OF_RANGE
                breakdown = null
                amounts = seating.associateWith { 0 }
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
            if (dealerAdvances && seating.isNotEmpty()) dealerIndex = (dealerIndex + 1) % seating.size
        }

        val finalBalances = balances.toMap()
        return EveningState(
            evening = evening,
            results = results,
            balances = finalBalances,
            ranks = Ranking.ranks(finalBalances),
            nextDealer = dealerAt(evening, dealerIndex),
            nextBockExponent = bock.exponent,
            bockGamesRemaining = bock.remaining,
            gamesPlayed = gamesPlayed,
        )
    }

    /**
     * Wer an dieser Stelle der Sitzordnung gibt. Ohne Sitzordnung (beschädigte Daten) gibt es keine Geberfolge;
     * dann bleibt es beim eingetragenen ersten Geber, damit die Rechnung nicht abstürzt.
     */
    private fun dealerAt(evening: Evening, index: Int): PlayerId = evening.seating.getOrElse(index) { evening.firstDealer }

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
                if (rounds.isEmpty()) {
                    rounds += rules.gamesPerTrigger
                } else {
                    // Als Long addiert und begrenzt: Eine unsinnig lange Bockrunde darf nicht ins Negative überlaufen.
                    rounds[0] = (rounds[0].toLong() + rules.gamesPerTrigger).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }

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
