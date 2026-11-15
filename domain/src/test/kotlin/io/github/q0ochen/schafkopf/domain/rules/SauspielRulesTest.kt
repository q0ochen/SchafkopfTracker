package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rechenbeispiele für die wählbare Vorlage [RuleSet.sauspiel] (Tarif 10-20-50 von sauspiel.de).
 * Die Beispiele 11 und 12 stehen so auf der Lernseite „Spielabrechnung“ von Sauspiel.
 */
class SauspielRulesTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val table = listOf(a, b, c, d)
    private val rules = RuleSet.sauspiel()

    private fun facts(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        announcement: Announcement = Announcement.NONE,
        klopfer: Int = 0,
        kontra: KontraLevel = KontraLevel.NONE,
        bock: Int = 0,
    ) = GameFacts(type, table, declarer, partner, won, level, laufende, announcement, klopfer, kontra, bock)

    private fun expect(aCents: Int, bCents: Int, cCents: Int, dCents: Int, facts: GameFacts, rules: RuleSet = this.rules) {
        assertEquals(mapOf(a to aCents, b to bCents, c to cCents, d to dCents), ScoreCalculator.amounts(facts, rules))
    }

    @Test
    fun `01 Rufspiel einfach gewonnen`() =
        expect(20, 20, -20, -20, facts(GameType.RUFSPIEL, a, b))

    @Test
    fun `02 Rufspiel mit Schneider und drei Laufenden`() =
        expect(60, 60, -60, -60, facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER, laufende = 3))

    @Test
    fun `03 verlorenes Farbsolo`() =
        expect(50, 50, -150, 50, facts(GameType.FARBSOLO, c, won = false))

    @Test
    fun `04 Wenz mit zwei Laufenden`() =
        expect(-70, -70, -70, 210, facts(GameType.WENZ, d, laufende = 2))

    @Test
    fun `05 Geier mit drei Laufenden und Schneider`() =
        expect(-90, 270, -90, -90, facts(GameType.GEIER, b, level = ResultLevel.SCHNEIDER, laufende = 3))

    @Test
    fun `06 Rufspiel mit zwei Klopfern verloren`() =
        expect(80, -80, 80, -80, facts(GameType.RUFSPIEL, b, d, won = false, klopfer = 2))

    @Test
    fun `07 Farbsolo mit Kontra und Retour`() =
        expect(-320, -320, -320, 960, facts(GameType.FARBSOLO, d, laufende = 3, kontra = KontraLevel.RETOUR))

    @Test
    fun `08 Rufspiel in der Bockrunde mit einem Klopfer`() =
        expect(80, -80, 80, -80, facts(GameType.RUFSPIEL, a, c, klopfer = 1, bock = 1))

    @Test
    fun `09 Farbsolo Tout gewonnen - Schneider und Schwarz entfallen`() =
        expect(
            540, -180, -180, -180,
            facts(GameType.FARBSOLO, a, level = ResultLevel.SCHWARZ, laufende = 4, announcement = Announcement.TOUT),
        )

    @Test
    fun `10 Wenz Tout verloren mit einem Klopfer`() =
        expect(
            280, -840, 280, 280,
            facts(GameType.WENZ, b, won = false, laufende = 2, announcement = Announcement.TOUT, klopfer = 1),
        )

    @Test
    fun `11 Referenzfall Sauspiel - Solo, drei Laufende, schwarz, zwei Klopfer`() =
        expect(-400, -400, 1200, -400, facts(GameType.FARBSOLO, c, level = ResultLevel.SCHWARZ, laufende = 3, klopfer = 2))

    @Test
    fun `12 Sie zaehlt bei Sauspiel wie ein Tout mit acht Laufenden`() {
        expect(780, -260, -260, -260, facts(GameType.FARBSOLO, a, announcement = Announcement.SIE))
        expect(1560, -520, -520, -520, facts(GameType.FARBSOLO, a, announcement = Announcement.SIE, klopfer = 1))
    }

    @Test
    fun `12b Sie mit vierfachem Faktor nach Schafkopfschule`() =
        expect(1560, -520, -520, -520, facts(GameType.FARBSOLO, a, announcement = Announcement.SIE), rules.copy(sieFactor = 4))

    @Test
    fun `13 Rufspiel mit Schneider verloren und Kontra`() =
        expect(
            -60, -60, 60, 60,
            facts(GameType.RUFSPIEL, a, b, won = false, level = ResultLevel.SCHNEIDER, kontra = KontraLevel.KONTRA),
        )

    @Test
    fun `14 Wenz schwarz mit Klopfer, Kontra und Bock`() =
        expect(
            -720, -720, -720, 2160,
            facts(
                GameType.WENZ, d, level = ResultLevel.SCHWARZ, laufende = 2, klopfer = 1,
                kontra = KontraLevel.KONTRA, bock = 1,
            ),
        )

    @Test
    fun `15 zwei Laufende zaehlen beim Farbsolo nicht`() =
        expect(150, -50, -50, -50, facts(GameType.FARBSOLO, a, laufende = 2))

    @Test
    fun `zwei Laufende zaehlen beim Rufspiel nicht, einer beim Wenz auch nicht`() {
        expect(20, 20, -20, -20, facts(GameType.RUFSPIEL, a, b, laufende = 2))
        expect(150, -50, -50, -50, facts(GameType.WENZ, a, laufende = 1))
    }

    @Test
    fun `bis zu vierzehn Laufende sind bei Sauspiel moeglich`() {
        // 20 + 14 × 10 = 160
        expect(160, 160, -160, -160, facts(GameType.RUFSPIEL, a, b, laufende = 14))
        assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(facts(GameType.RUFSPIEL, a, b, laufende = 14), rules))
    }

    @Test
    fun `vier Klopfer versechzehnfachen`() =
        expect(320, 320, -320, -320, facts(GameType.RUFSPIEL, a, b, klopfer = 4))

    @Test
    fun `anderer Tarif 50-100-200`() {
        val high = rules.copy(
            unit = 50,
            prices = mapOf(GameType.RUFSPIEL to 100, GameType.FARBSOLO to 200, GameType.WENZ to 200, GameType.GEIER to 200),
        )
        expect(150, 150, -150, -150, facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER), high)
        expect(-350, -350, -350, 1050, facts(GameType.FARBSOLO, d, laufende = 3), high)
    }
}
