package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId

/**
 * Alles, was für die Abrechnung eines gespielten Spiels zählt.
 *
 * @property players Die vier Spieler am Tisch in Sitzreihenfolge.
 * @property declarer Wer das Spiel angesagt hat (der „Spieler“). Bei Über Kreuz einer aus dem einen Paar.
 * @property partner Der Mitspieler beim Rufspiel und bei Über Kreuz; bei Einzelspielen `null`.
 * @property declarerWon Ob die Spielerseite gewonnen hat (61 Augen oder mehr; bei 60:60 hat sie verloren).
 * @property laufende Eingetragene Zahl der Laufenden; unterhalb der Schwelle des Regelwerks zählen sie nicht.
 * @property klopferCount Wie viele Spieler gelegt (geklopft) haben; jeder verdoppelt.
 * @property bockExponent 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden usw.
 */
data class GameFacts(
    val type: GameType,
    val players: List<PlayerId>,
    val declarer: PlayerId,
    val partner: PlayerId?,
    val declarerWon: Boolean,
    val level: ResultLevel,
    val laufende: Int,
    val announcement: Announcement,
    val klopferCount: Int,
    val kontra: KontraLevel,
    val bockExponent: Int,
) {
    /** Die Spielerseite: der Ansager und – außer beim Einzelspiel – sein Mitspieler. */
    val declarerSide: Set<PlayerId> get() = setOfNotNull(declarer, partner.takeIf { !type.isSolo })

    /** Die Gewinner in Sitzreihenfolge: zwei beim Rufspiel und bei Über Kreuz, einer oder drei beim Einzelspiel. */
    val winners: List<PlayerId> get() = players.filter { (it in declarerSide) == declarerWon }

    /** Die Verlierer in Sitzreihenfolge. */
    val losers: List<PlayerId> get() = players.filter { (it in declarerSide) != declarerWon }
}

/**
 * Aufschlüsselung des Spielwerts, so wie man sie am Tisch vorrechnet:
 * erst alle Zuschläge addieren, dann multipliziert jeder Verdoppler die ganze Summe.
 *
 * @property value Der Spielwert T: so viel zahlt ein Verlierer an einen Gewinner.
 */
data class ValueBreakdown(
    val basePrice: Int,
    val laufendeCounted: Int,
    val laufendeAmount: Int,
    val levelAmount: Int,
    val sum: Int,
    val announcementFactor: Int,
    val klopferFactor: Int,
    val kontraFactor: Int,
    val bockFactor: Int,
    val value: Int,
) {
    val totalFactor: Int get() = announcementFactor * klopferFactor * kontraFactor * bockFactor
}

/** Warum ein Spiel so nicht eingetragen werden kann. */
enum class RuleViolation {
    WRONG_PLAYER_COUNT,
    DUPLICATE_PLAYERS,
    DECLARER_NOT_AT_TABLE,
    PARTNER_MISSING,
    PARTNER_NOT_AT_TABLE,
    PARTNER_IS_DECLARER,
    PARTNER_NOT_ALLOWED,
    GAME_TYPE_DISABLED,
    LAUFENDE_OUT_OF_RANGE,
    TOUT_NOT_ALLOWED,
    SIE_NOT_ALLOWED,
    SIE_CANNOT_BE_LOST,
    KLOPFER_OUT_OF_RANGE,
    KLOPFEN_DISABLED,
    KONTRA_DISABLED,
    RETOUR_DISABLED,
    BOCK_EXPONENT_NEGATIVE,

    /**
     * Der Spielwert ist negativ oder größer als [ScoreCalculator.MAX_VALUE]. Das kann nur aus einem beschädigten
     * Regelwerk oder Eintrag stammen; so ein Spiel wird nicht abgerechnet.
     */
    VALUE_OUT_OF_RANGE,
}

/**
 * Rechnet ein einzelnes Spiel ab. Reine Funktionen ohne Zustand.
 *
 * Formel: Spielwert T = (Grundwert + Laufende × Einheit + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt)
 * × Kontra × Tout. Schneider zählt eine Einheit, Schwarz zwei (Schwarz schließt Schneider ein);
 * bei Tout und Sie entfallen beide.
 *
 * Wie T auf die Spieler verteilt wird, bestimmt der [PayoutCalculator] des Regelwerks.
 */
object ScoreCalculator {

    const val PLAYERS_AT_TABLE = 4

    /**
     * Größter Spielwert T, den die App abrechnet: Auch der dreifache Betrag des Alleinspielers passt dann noch in
     * einen `Int`. Ein größerer oder ein negativer Wert kann nur aus beschädigten Daten stammen; [validate] meldet
     * ihn als [RuleViolation.VALUE_OUT_OF_RANGE], und [amounts] verteilt dann nichts.
     */
    const val MAX_VALUE = Int.MAX_VALUE / 3

    /**
     * Die Aufschlüsselung des Spielwerts. Gerechnet wird ohne Überlauf: Ergeben unsinnige Werte (beschädigte Daten)
     * mehr, als in einen `Int` passt, bleibt das Ergebnis am Rand des Zahlenbereichs stehen. Ein solcher Wert ist
     * dann erkennbar zu groß oder negativ (siehe [MAX_VALUE]) und sieht nie zufällig wieder harmlos aus.
     */
    fun breakdown(facts: GameFacts, rules: RuleSet): ValueBreakdown {
        val hasAnnouncement = facts.announcement != Announcement.NONE
        val basePrice = rules.price(facts.type)
        val laufende = if (facts.announcement == Announcement.SIE) rules.sieLaufende else facts.laufende
        val laufendeCounted = if (laufende >= rules.laufendeMin(facts.type)) laufende else 0
        // Die Summe wird als Long genau gerechnet und erst dann begrenzt: So können sich ein riesiger Zuschlag und
        // ein riesiger negativer Grundwert nicht scheinbar zu einem gültigen Wert aufheben.
        val laufendeAmount = laufendeCounted.toLong() * rules.unit
        val levelAmount = if (hasAnnouncement) 0L else facts.level.steps.toLong() * rules.unit
        val sum = limited(basePrice + laufendeAmount + levelAmount)

        val announcementFactor = when (facts.announcement) {
            Announcement.NONE -> 1
            Announcement.TOUT -> rules.toutFactor
            Announcement.SIE -> rules.sieFactor
        }
        val klopferFactor = 1 shl facts.klopferCount.coerceIn(0, PLAYERS_AT_TABLE)
        val kontraFactor = facts.kontra.factor
        val bockFactor = power(rules.bock.factor, facts.bockExponent.coerceAtLeast(0))

        return ValueBreakdown(
            basePrice = basePrice,
            laufendeCounted = laufendeCounted,
            laufendeAmount = limited(laufendeAmount),
            levelAmount = limited(levelAmount),
            sum = sum,
            announcementFactor = announcementFactor,
            klopferFactor = klopferFactor,
            kontraFactor = kontraFactor,
            bockFactor = bockFactor,
            value = listOf(announcementFactor, klopferFactor, kontraFactor, bockFactor).fold(sum, ::product),
        )
    }

    /**
     * Betrag je Spieler in Cent (positiv = bekommt Geld). Die Summe ist immer 0.
     *
     * Mit der Schafkopf-Regel: Rufspiel und Über Kreuz je ±T; beim Einzelspiel bekommt oder zahlt der
     * Alleinspieler 3 T, jeder Gegner T. Mit der Topf-Teilung teilen sich die drei Gegner einen Spielwert.
     *
     * Lässt sich mit dem Spielwert nicht rechnen (negativ oder größer als [MAX_VALUE]) oder lässt er sich nicht
     * sinnvoll verteilen (siehe [PayoutCalculator]), bekommen alle 0.
     */
    fun amounts(facts: GameFacts, rules: RuleSet): Map<PlayerId, Int> {
        val value = breakdown(facts, rules).value
        if (value !in 0..MAX_VALUE) return facts.players.associateWith { 0 }
        val payout = PayoutCalculator.of(rules.payoutRule).payout(value, facts.winners, facts.losers)
        return facts.players.associateWith { payout[it] ?: 0 }
    }

    /** Prüft, ob das Spiel zum Regelwerk passt. Eine leere Liste heißt: alles in Ordnung. */
    fun validate(facts: GameFacts, rules: RuleSet): List<RuleViolation> = buildList {
        if (facts.players.size != PLAYERS_AT_TABLE) add(RuleViolation.WRONG_PLAYER_COUNT)
        if (facts.players.toSet().size != facts.players.size) add(RuleViolation.DUPLICATE_PLAYERS)
        if (facts.declarer !in facts.players) add(RuleViolation.DECLARER_NOT_AT_TABLE)

        if (facts.type.isSolo) {
            if (facts.partner != null) add(RuleViolation.PARTNER_NOT_ALLOWED)
        } else {
            when (facts.partner) {
                null -> add(RuleViolation.PARTNER_MISSING)
                facts.declarer -> add(RuleViolation.PARTNER_IS_DECLARER)
                !in facts.players -> add(RuleViolation.PARTNER_NOT_AT_TABLE)
                else -> Unit
            }
        }

        if (facts.type !in rules.enabledTypes) add(RuleViolation.GAME_TYPE_DISABLED)
        if (facts.laufende !in 0..rules.laufendeMax(facts.type)) add(RuleViolation.LAUFENDE_OUT_OF_RANGE)

        when (facts.announcement) {
            Announcement.NONE -> Unit
            Announcement.TOUT -> if (!rules.allowsTout(facts.type)) add(RuleViolation.TOUT_NOT_ALLOWED)
            Announcement.SIE -> {
                if (!rules.allowsSie(facts.type)) add(RuleViolation.SIE_NOT_ALLOWED)
                if (!facts.declarerWon) add(RuleViolation.SIE_CANNOT_BE_LOST)
            }
        }

        if (facts.klopferCount !in 0..PLAYERS_AT_TABLE) add(RuleViolation.KLOPFER_OUT_OF_RANGE)
        if (facts.klopferCount > 0 && !rules.klopfenEnabled) add(RuleViolation.KLOPFEN_DISABLED)
        if (facts.kontra != KontraLevel.NONE && !rules.kontraEnabled) add(RuleViolation.KONTRA_DISABLED)
        if (facts.kontra == KontraLevel.RETOUR && !rules.retourEnabled) add(RuleViolation.RETOUR_DISABLED)
        if (facts.bockExponent < 0) add(RuleViolation.BOCK_EXPONENT_NEGATIVE)
        if (breakdown(facts, rules).value !in 0..MAX_VALUE) add(RuleViolation.VALUE_OUT_OF_RANGE)
    }

    /** Begrenzt auf den Zahlenbereich eines `Int`, statt überzulaufen. */
    private fun limited(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** Produkt zweier Zahlen; passt es nicht in einen `Int`, bleibt es am Rand des Zahlenbereichs stehen. */
    private fun product(a: Int, b: Int): Int = limited(a.toLong() * b)

    /** Potenz; passt sie nicht in einen `Int`, bleibt sie am Rand des Zahlenbereichs stehen. */
    private fun power(base: Int, exponent: Int): Int {
        // Nach 32 Schritten ist jede Basis mit Betrag ab 2 am Rand angekommen. Weitere Schritte könnten nur noch das
        // Vorzeichen wechseln (negative Basis); deshalb genügt es, gerade oder ungerade beizubehalten.
        val steps = if (exponent > POWER_STEPS) POWER_STEPS + exponent % 2 else exponent
        var result = 1
        repeat(steps) { result = product(result, base) }
        return result
    }

    /** Eine gerade Zahl von Schritten, nach der jede Potenz den Zahlenbereich eines `Int` verlassen hat (Basis mit Betrag ab 2). */
    private const val POWER_STEPS = 32
}
