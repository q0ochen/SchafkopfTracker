package io.github.q0ochen.schafkopf.domain.rules

/** Spielarten, die die App abrechnen kann. */
enum class GameType(
    /** `true`: einer spielt gegen drei. `false`: zwei gegen zwei (Rufspiel, Über Kreuz). */
    val isSolo: Boolean,
) {
    RUFSPIEL(isSolo = false),

    /** Über Kreuz: Zwei spielen zusammen gegen die beiden anderen. Wird wie ein Rufspiel abgerechnet. */
    UEBER_KREUZ(isSolo = false),
    FARBSOLO(isSolo = true),
    WENZ(isSolo = true),
    GEIER(isSolo = true),
    ;

    /**
     * Ob jemand das Spiel angesagt hat. Bei Über Kreuz sagt niemand an: Dort stehen sich einfach zwei Paare
     * gegenüber, und „Ansager“ und „Mitspieler“ bezeichnen nur das eine der beiden Paare.
     */
    val hasDeclarer: Boolean get() = this != UEBER_KREUZ
}

/** Die vier Farben des bayerischen Blatts (Rufsau beim Rufspiel, Trumpffarbe beim Farbsolo). */
enum class Suit { EICHEL, GRAS, HERZ, SCHELLEN }

/** Ausgang eines Spiels über den einfachen Sieg hinaus. Schwarz schließt Schneider ein. */
enum class ResultLevel(
    /** Anzahl der Grundeinheiten, um die der Spielwert steigt. */
    val steps: Int,
) {
    NORMAL(0),
    SCHNEIDER(1),
    SCHWARZ(2),
}

/** Besondere Ansage eines Alleinspielers. */
enum class Announcement { NONE, TOUT, SIE }

/** Kontra (Stoß) der Gegenspieler und Retour (Re) der Spielerseite. */
enum class KontraLevel(val factor: Int) {
    NONE(1),
    KONTRA(2),
    RETOUR(4),
}

/** Was passiert, wenn während einer laufenden Bockrunde erneut eine ausgelöst wird. */
enum class BockStacking {
    /** Die neue Bockrunde wird hinten angehängt; der Faktor bleibt gleich. */
    APPEND,

    /** Die Bockrunden überlagern sich; in der Überschneidung wird mehrfach verdoppelt. */
    STACK,

    /** Solange eine Bockrunde läuft, wird keine weitere ausgelöst. */
    IGNORE,
}

/**
 * Hausregel Bockrunde: Sie wird ausgelöst, wenn jemand 6 Nixer hat. In einem Bockspiel zählt alles doppelt.
 *
 * @property gamesPerTrigger Länge einer Bockrunde in gewerteten Spielen (4 = jeder gibt einmal).
 * @property factor Faktor, mit dem der gesamte Spielwert eines Bockspiels malgenommen wird.
 * @property thrownInConsumesBockGame Ob ein zusammengeworfenes Blatt ein Bockspiel verbraucht.
 */
data class BockRules(
    val enabled: Boolean = true,
    val gamesPerTrigger: Int = 4,
    val factor: Int = 2,
    val stacking: BockStacking = BockStacking.APPEND,
    val thrownInConsumesBockGame: Boolean = false,
)

/**
 * Das Regelwerk eines Abends. Alle Beträge sind ganze Cent (oder Punkte, 1 Cent = 1 Punkt).
 *
 * Jeder Abend speichert seine eigene Kopie. Spätere Änderungen an der Vorlage verändern alte Abende nicht.
 *
 * @property unit Grundeinheit: Zuschlag für Schneider, für Schwarz und für jeden Laufenden.
 * @property prices Grundwert je Spielart.
 * @property laufendeMin Ab wie vielen Laufenden gezählt wird; darunter zählen sie nicht.
 * @property laufendeMax Höchste eintragbare Zahl von Laufenden.
 * @property toutFactor Faktor für ein Tout (Schneider und Schwarz entfallen dabei).
 * @property sieFactor Faktor für ein Sie. Es wird wie ein Tout mit [sieLaufende] Laufenden gerechnet (Faktor 2);
 *   manche Runden rechnen stattdessen vierfach.
 * @property dealerAdvancesAfterThrowIn Ob nach einem zusammengeworfenen Blatt der nächste Spieler gibt.
 * @property displayAsMoney `true`: Anzeige in Euro. `false`: Anzeige in Punkten.
 * @property payoutRule Wie der Spielwert auf die Spieler verteilt wird (siehe [PayoutCalculator]).
 */
data class RuleSet(
    val name: String,
    val unit: Int,
    val prices: Map<GameType, Int>,
    val enabledTypes: Set<GameType>,
    val laufendeMin: Map<GameType, Int>,
    val laufendeMax: Map<GameType, Int>,
    val klopfenEnabled: Boolean,
    val kontraEnabled: Boolean,
    val retourEnabled: Boolean,
    val toutFactor: Int,
    val sieFactor: Int,
    val sieLaufende: Int,
    val bock: BockRules,
    val dealerAdvancesAfterThrowIn: Boolean,
    val displayAsMoney: Boolean,
    val payoutRule: PayoutRule = PayoutRule.SCHAFKOPF,
) {
    fun price(type: GameType): Int = lookup(prices, type, STANDARD_PRICES)

    fun laufendeMin(type: GameType): Int = lookup(laufendeMin, type, STANDARD_LAUFENDE_MIN)

    fun laufendeMax(type: GameType): Int = lookup(laufendeMax, type, STANDARD_LAUFENDE_MAX)

    /** Tout gibt es nur bei Einzelspielen. */
    fun allowsTout(type: GameType): Boolean = type.isSolo

    /** Ein Sie (alle vier Ober und alle vier Unter) gibt es nur beim Farbsolo. */
    fun allowsSie(type: GameType): Boolean = type == GameType.FARBSOLO

    companion object {
        private val STANDARD_PRICES = mapOf(
            GameType.RUFSPIEL to 10,
            GameType.UEBER_KREUZ to 10,
            GameType.FARBSOLO to 50,
            GameType.WENZ to 50,
            GameType.GEIER to 50,
        )
        private val STANDARD_LAUFENDE_MIN = mapOf(
            GameType.RUFSPIEL to 3,
            GameType.UEBER_KREUZ to 3,
            GameType.FARBSOLO to 3,
            GameType.WENZ to 2,
            GameType.GEIER to 2,
        )
        private val STANDARD_LAUFENDE_MAX = mapOf(
            GameType.RUFSPIEL to 8,
            GameType.UEBER_KREUZ to 8,
            GameType.FARBSOLO to 8,
            GameType.WENZ to 4,
            GameType.GEIER to 4,
        )

        /**
         * Liest einen Wert je Spielart. Fehlt „Über Kreuz“ in einem Regelwerk (zum Beispiel in einem älteren
         * gespeicherten Abend), gilt dafür der Wert des Rufspiels, weil es genauso abgerechnet wird.
         */
        private fun lookup(values: Map<GameType, Int>, type: GameType, standard: Map<GameType, Int>): Int =
            values[type]
                ?: values[GameType.RUFSPIEL].takeIf { type == GameType.UEBER_KREUZ }
                ?: standard.getValue(type)

        /**
         * Das Regelwerk der Runde, so wie es der Hauptentwickler vorgegeben hat: Rufspiel und Über Kreuz 10 Cent,
         * Farbsolo, Wenz und Geier 50 Cent, je Laufendem 10 Cent (bis 8, bei Wenz und Geier bis 4), Schneider 10,
         * Schwarz weitere 10 Cent. Jeder, der gelegt hat, und ein Bockspiel verdoppeln. Dazu Kontra, Retour und
         * Tout wie auf sauspiel.de (abschaltbar) und die Bockrunde über vier Spiele.
         */
        fun standard(): RuleSet = RuleSet(
            name = "Runde 10/50 mit Bockrunde",
            unit = 10,
            prices = STANDARD_PRICES,
            enabledTypes = GameType.entries.toSet(),
            laufendeMin = STANDARD_LAUFENDE_MIN,
            laufendeMax = STANDARD_LAUFENDE_MAX,
            klopfenEnabled = true,
            kontraEnabled = true,
            retourEnabled = true,
            toutFactor = 2,
            sieFactor = 2,
            sieLaufende = 8,
            bock = BockRules(),
            dealerAdvancesAfterThrowIn = true,
            displayAsMoney = true,
            payoutRule = PayoutRule.SCHAFKOPF,
        )

        /**
         * Regeln von sauspiel.de als wählbare Vorlage: Tarif 10-20-50 (Rufspiel 20 Cent), bis zu 14 Laufende,
         * Klopfen, Kontra und Retour, Tout, Sie als Tout mit 8 Laufenden; ohne „Über Kreuz“.
         * Dazu die Bockrunde der Runde über vier Spiele.
         */
        fun sauspiel(): RuleSet = RuleSet(
            name = "Sauspiel 10-20-50 mit Bockrunde",
            unit = 10,
            prices = mapOf(
                GameType.RUFSPIEL to 20,
                GameType.FARBSOLO to 50,
                GameType.WENZ to 50,
                GameType.GEIER to 50,
            ),
            enabledTypes = setOf(GameType.RUFSPIEL, GameType.FARBSOLO, GameType.WENZ, GameType.GEIER),
            laufendeMin = mapOf(
                GameType.RUFSPIEL to 3,
                GameType.FARBSOLO to 3,
                GameType.WENZ to 2,
                GameType.GEIER to 2,
            ),
            laufendeMax = mapOf(
                GameType.RUFSPIEL to 14,
                GameType.FARBSOLO to 14,
                GameType.WENZ to 4,
                GameType.GEIER to 4,
            ),
            klopfenEnabled = true,
            kontraEnabled = true,
            retourEnabled = true,
            toutFactor = 2,
            sieFactor = 2,
            sieLaufende = 8,
            bock = BockRules(),
            dealerAdvancesAfterThrowIn = true,
            displayAsMoney = true,
            payoutRule = PayoutRule.SCHAFKOPF,
        )
    }
}
