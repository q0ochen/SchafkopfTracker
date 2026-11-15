package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unabhängige Gegenrechnung des Rechenkerns. Jeder Fall ist vollständig von Hand gerechnet; der Rechenweg steht als
 * Kommentar dabei. Die erwarteten Zahlen stammen aus der Vorgabe, nicht aus dem Code.
 *
 * Regelwerk der Runde ([RuleSet.standard]):
 *
 *     T = (Grundwert + Laufende × 10 + Schneider 10 + Schwarz weitere 10) × 2 bei Bock × 2^(Anzahl gelegt)
 *
 * Grundwert: Rufspiel und Über Kreuz 10 Cent, Farbsolo, Wenz und Geier 50 Cent. Laufende zählen ab 3, bei Wenz und
 * Geier ab 2. Zwei gegen zwei: jeder Gewinner +T, jeder Verlierer −T. Einzelspiel: Solist ±3 T, jeder Gegner ∓T.
 * Mit Topf-Teilung: Solist ±T, die drei Gegner teilen sich T (Rest-Cent an die in Sitzreihenfolge vorderen).
 *
 * Am Tisch sitzen A, B, C und D in dieser Reihenfolge.
 */
class HandCalculatedCasesTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val table = listOf(a, b, c, d)
    private val standard = RuleSet.standard()
    private val potSplit = RuleSet.standard().copy(payoutRule = PayoutRule.POT_SPLIT)

    private fun game(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        gelegt: Int = 0,
        bock: Boolean = false,
        announcement: Announcement = Announcement.NONE,
        kontra: KontraLevel = KontraLevel.NONE,
    ) = GameFacts(type, table, declarer, partner, won, level, laufende, announcement, gelegt, kontra, if (bock) 1 else 0)

    /** Prüft den Spielwert T und den Betrag jedes Spielers in der Reihenfolge A, B, C, D. */
    private fun check(value: Int, aCents: Int, bCents: Int, cCents: Int, dCents: Int, facts: GameFacts, rules: RuleSet = standard) {
        assertEquals(0, aCents + bCents + cCents + dCents, "Die Handrechnung selbst muss aufgehen")
        assertEquals(value, ScoreCalculator.breakdown(facts, rules).value, "Spielwert T")
        assertEquals(mapOf(a to aCents, b to bCents, c to cCents, d to dCents), ScoreCalculator.amounts(facts, rules))
        assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(facts, rules))
    }

    // ---------------------------------------------------------------- Rufspiel

    @Test
    fun `01 Rufspiel A mit C gewonnen, ohne Zusaetze`() =
        // T = 10. A und C je +10, B und D je −10.
        check(10, 10, -10, 10, -10, game(GameType.RUFSPIEL, a, c))

    @Test
    fun `02 Rufspiel B mit D verloren, Schneider`() =
        // T = 10 + 10 = 20. Die Spielerseite B und D zahlt je 20, A und C bekommen je 20.
        check(20, 20, -20, 20, -20, game(GameType.RUFSPIEL, b, d, won = false, level = ResultLevel.SCHNEIDER))

    @Test
    fun `03 Rufspiel A mit B gewonnen, Schwarz, drei Laufende - genau an der Schwelle`() =
        // T = 10 + 3 × 10 + 10 (Schneider) + 10 (Schwarz) = 60.
        check(60, 60, 60, -60, -60, game(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHWARZ, laufende = 3))

    @Test
    fun `04 Rufspiel C mit D gewonnen, zwei Laufende unter der Schwelle, einmal gelegt`() =
        // Zwei Laufende zählen beim Rufspiel nicht: T = 10 × 2 = 20.
        check(20, -20, -20, 20, 20, game(GameType.RUFSPIEL, c, d, laufende = 2, gelegt = 1))

    @Test
    fun `05 Rufspiel A mit D verloren, fuenf Laufende, Schneider, zweimal gelegt`() =
        // T = (10 + 5 × 10 + 10) × 2² = 70 × 4 = 280. A und D zahlen je 280.
        check(
            280, -280, 280, 280, -280,
            game(GameType.RUFSPIEL, a, d, won = false, level = ResultLevel.SCHNEIDER, laufende = 5, gelegt = 2),
        )

    @Test
    fun `06 Rufspiel B mit C gewonnen, Bock`() =
        // T = 10 × 2 = 20.
        check(20, -20, 20, 20, -20, game(GameType.RUFSPIEL, b, c, bock = true))

    @Test
    fun `07 Rufspiel A mit B gewonnen, Bock und dreimal gelegt`() =
        // T = 10 × 2 × 2³ = 10 × 16 = 160.
        check(160, 160, 160, -160, -160, game(GameType.RUFSPIEL, a, b, gelegt = 3, bock = true))

    @Test
    fun `08 groesstes Rufspiel der Vorgabe - acht Laufende, Schwarz, Bock, viermal gelegt, verloren`() =
        // T = (10 + 8 × 10 + 20) × 2 × 2⁴ = 110 × 32 = 3520. Die Spielerseite C und A zahlt je 3520.
        check(
            3520, -3520, 3520, -3520, 3520,
            game(GameType.RUFSPIEL, c, a, won = false, level = ResultLevel.SCHWARZ, laufende = 8, gelegt = 4, bock = true),
        )

    // ---------------------------------------------------------------- Über Kreuz (wertet wie ein Rufspiel)

    @Test
    fun `09 Ueber Kreuz B mit D gewonnen, ohne Zusaetze`() =
        // T = 10.
        check(10, -10, 10, -10, 10, game(GameType.UEBER_KREUZ, b, d))

    @Test
    fun `10 Ueber Kreuz A mit C verloren, Schneider, vier Laufende, einmal gelegt`() =
        // T = (10 + 4 × 10 + 10) × 2 = 60 × 2 = 120.
        check(
            120, -120, 120, -120, 120,
            game(GameType.UEBER_KREUZ, a, c, won = false, level = ResultLevel.SCHNEIDER, laufende = 4, gelegt = 1),
        )

    @Test
    fun `11 Ueber Kreuz A mit C gewonnen, Schwarz, Bock, zweimal gelegt`() =
        // T = (10 + 20) × 2 × 2² = 30 × 8 = 240.
        check(240, 240, -240, 240, -240, game(GameType.UEBER_KREUZ, a, c, level = ResultLevel.SCHWARZ, gelegt = 2, bock = true))

    // ---------------------------------------------------------------- Farbsolo

    @Test
    fun `12 Farbsolo A gewonnen, ohne Zusaetze`() =
        // T = 50. A bekommt von jedem der drei 50, zusammen 150.
        check(50, 150, -50, -50, -50, game(GameType.FARBSOLO, a))

    @Test
    fun `13 Farbsolo C verloren, Schneider`() =
        // T = 50 + 10 = 60. C zahlt jedem 60, zusammen 180.
        check(60, 60, 60, -180, 60, game(GameType.FARBSOLO, c, won = false, level = ResultLevel.SCHNEIDER))

    @Test
    fun `14 Referenzfall - Farbsolo D gewonnen, Schwarz, drei Laufende, zweimal gelegt`() =
        // T = (50 + 3 × 10 + 20) × 2² = 100 × 4 = 400. D bekommt 3 × 400 = 1200.
        check(400, -400, -400, -400, 1200, game(GameType.FARBSOLO, d, level = ResultLevel.SCHWARZ, laufende = 3, gelegt = 2))

    @Test
    fun `15 Farbsolo B gewonnen, zwei Laufende unter der Schwelle, Bock`() =
        // Zwei Laufende zählen beim Farbsolo nicht: T = 50 × 2 = 100. B bekommt 300.
        check(100, -100, 300, -100, -100, game(GameType.FARBSOLO, b, laufende = 2, bock = true))

    @Test
    fun `16 groesster Fall der Vorgabe - Farbsolo B verloren, acht Laufende, Schwarz, Bock, viermal gelegt`() =
        // T = (50 + 8 × 10 + 20) × 2 × 2⁴ = 150 × 32 = 4800. B zahlt 3 × 4800 = 14400.
        check(
            4800, 4800, -14_400, 4800, 4800,
            game(GameType.FARBSOLO, b, won = false, level = ResultLevel.SCHWARZ, laufende = 8, gelegt = 4, bock = true),
        )

    // ---------------------------------------------------------------- Wenz

    @Test
    fun `17 Wenz A gewonnen, zwei Laufende - beim Wenz genau an der Schwelle`() =
        // T = 50 + 2 × 10 = 70. A bekommt 210.
        check(70, 210, -70, -70, -70, game(GameType.WENZ, a, laufende = 2))

    @Test
    fun `18 Wenz C verloren, ein Laufender unter der Schwelle, Schneider, einmal gelegt`() =
        // Ein Laufender zählt nicht: T = (50 + 10) × 2 = 120. C zahlt 360.
        check(
            120, 120, 120, -360, 120,
            game(GameType.WENZ, c, won = false, level = ResultLevel.SCHNEIDER, laufende = 1, gelegt = 1),
        )

    @Test
    fun `19 Wenz D gewonnen, vier Laufende, Schwarz, Bock, einmal gelegt`() =
        // T = (50 + 4 × 10 + 20) × 2 × 2 = 110 × 4 = 440. D bekommt 1320.
        check(
            440, -440, -440, -440, 1320,
            game(GameType.WENZ, d, level = ResultLevel.SCHWARZ, laufende = 4, gelegt = 1, bock = true),
        )

    // ---------------------------------------------------------------- Geier

    @Test
    fun `20 Geier B gewonnen, viermal gelegt`() =
        // T = 50 × 2⁴ = 800. B bekommt 2400.
        check(800, -800, 2400, -800, -800, game(GameType.GEIER, b, gelegt = 4))

    @Test
    fun `21 Geier A verloren, drei Laufende, Schneider, Bock`() =
        // T = (50 + 3 × 10 + 10) × 2 = 90 × 2 = 180. A zahlt 540.
        check(
            180, -540, 180, 180, 180,
            game(GameType.GEIER, a, won = false, level = ResultLevel.SCHNEIDER, laufende = 3, bock = true),
        )

    @Test
    fun `22 Geier C gewonnen, zwei Laufende, Schwarz, dreimal gelegt`() =
        // T = (50 + 2 × 10 + 20) × 2³ = 90 × 8 = 720. C bekommt 2160.
        check(720, -720, -720, 2160, -720, game(GameType.GEIER, c, level = ResultLevel.SCHWARZ, laufende = 2, gelegt = 3))

    // ---------------------------------------------------------------- Topf-Teilung

    @Test
    fun `23 Topf-Teilung ohne Rest - Farbsolo B gewonnen, Schneider`() =
        // T = 60. B bekommt 60; 60 : 3 = 20 je Gegner.
        check(60, -20, 60, -20, -20, game(GameType.FARBSOLO, b, level = ResultLevel.SCHNEIDER), potSplit)

    @Test
    fun `24 Topf-Teilung mit Rest zwei - Wenz C gewonnen`() =
        // T = 50. 50 : 3 = 16 Rest 2. Die Gegner in Sitzreihenfolge sind A, B, D: 17, 17, 16.
        check(50, -17, -17, 50, -16, game(GameType.WENZ, c), potSplit)

    @Test
    fun `25 Topf-Teilung mit Rest eins - Geier A verloren, einmal gelegt`() =
        // T = 50 × 2 = 100. A zahlt 100. 100 : 3 = 33 Rest 1. Die Gegner B, C, D bekommen 34, 33, 33.
        check(100, -100, 34, 33, 33, game(GameType.GEIER, a, won = false, gelegt = 1), potSplit)

    @Test
    fun `26 Topf-Teilung mit Rest zwei - Farbsolo D verloren, Schwarz, drei Laufende, Bock`() =
        // T = (50 + 30 + 20) × 2 = 200. D zahlt 200. 200 : 3 = 66 Rest 2. A, B, C bekommen 67, 67, 66.
        check(
            200, 67, 67, 66, -200,
            game(GameType.FARBSOLO, d, won = false, level = ResultLevel.SCHWARZ, laufende = 3, bock = true),
            potSplit,
        )

    @Test
    fun `27 Topf-Teilung aendert das Rufspiel nicht - A mit B gewonnen, Schneider, einmal gelegt`() =
        // T = (10 + 10) × 2 = 40, je ±40 wie bei der Schafkopf-Regel.
        check(40, 40, 40, -40, -40, game(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER, gelegt = 1), potSplit)

    @Test
    fun `28 Topf-Teilung mit Rest zwei - Wenz B gewonnen, drei Laufende`() =
        // T = 50 + 30 = 80. 80 : 3 = 26 Rest 2. Die Gegner A, C, D zahlen 27, 27, 26.
        check(80, -27, 80, -27, -26, game(GameType.WENZ, b, laufende = 3), potSplit)

    @Test
    fun `29 Topf-Teilung ohne Rest - Geier D gewonnen, Schneider, Bock, zweimal gelegt`() =
        // T = (50 + 10) × 2 × 2² = 60 × 8 = 480. 480 : 3 = 160 je Gegner.
        check(
            480, -160, -160, -160, 480,
            game(GameType.GEIER, d, level = ResultLevel.SCHNEIDER, gelegt = 2, bock = true),
            potSplit,
        )

    @Test
    fun `30 Topf-Teilung aendert Ueber Kreuz nicht - B mit D verloren, drei Laufende`() =
        // T = 10 + 30 = 40. B und D zahlen je 40.
        check(40, 40, -40, 40, -40, game(GameType.UEBER_KREUZ, b, d, won = false, laufende = 3), potSplit)

    // ---------------------------------------------------------------- Zusätze: Tout, Sie, Kontra, Retour

    @Test
    fun `31 Farbsolo Tout A gewonnen, vier Laufende, Bock, einmal gelegt - Schwarz entfaellt`() =
        // Beim Tout entfallen Schneider und Schwarz: T = (50 + 4 × 10) × 2 (Tout) × 2 (Bock) × 2 (gelegt) = 90 × 8 = 720.
        check(
            720, 2160, -720, -720, -720,
            game(
                GameType.FARBSOLO, a, level = ResultLevel.SCHWARZ, laufende = 4, gelegt = 1, bock = true,
                announcement = Announcement.TOUT,
            ),
        )

    @Test
    fun `32 Wenz Tout C verloren, zwei Laufende`() =
        // T = (50 + 2 × 10) × 2 = 140. C zahlt 420.
        check(140, 140, 140, -420, 140, game(GameType.WENZ, c, won = false, laufende = 2, announcement = Announcement.TOUT))

    @Test
    fun `33 Sie von D - wie ein Tout mit acht Laufenden`() =
        // T = (50 + 8 × 10) × 2 = 260. D bekommt 780.
        check(260, -260, -260, -260, 780, game(GameType.FARBSOLO, d, announcement = Announcement.SIE))

    @Test
    fun `34 Rufspiel A mit B gewonnen, Schneider, mit Kontra und mit Retour`() {
        // Kontra: T = (10 + 10) × 2 = 40. Retour: T = 20 × 4 = 80.
        check(40, 40, 40, -40, -40, game(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER, kontra = KontraLevel.KONTRA))
        check(80, 80, 80, -80, -80, game(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER, kontra = KontraLevel.RETOUR))
    }

    @Test
    fun `35 Geier B verloren, Retour, Bock, einmal gelegt`() =
        // T = 50 × 4 (Retour) × 2 (Bock) × 2 (gelegt) = 800. B zahlt 2400.
        check(800, 800, -2400, 800, 800, game(GameType.GEIER, b, won = false, gelegt = 1, bock = true, kontra = KontraLevel.RETOUR))

    // ---------------------------------------------------------------- Reihen: jede Stufe einzeln von Hand

    @Test
    fun `36 Laufende beim Rufspiel von null bis acht`() {
        // 0, 1, 2 zählen nicht (10); ab 3 zählt jeder: 10 + n × 10.
        val expected = listOf(10, 10, 10, 40, 50, 60, 70, 80, 90)
        assertEquals(expected, (0..8).map { ScoreCalculator.breakdown(game(GameType.RUFSPIEL, a, b, laufende = it), standard).value })
        assertEquals(expected, (0..8).map { ScoreCalculator.breakdown(game(GameType.UEBER_KREUZ, a, c, laufende = it), standard).value })
    }

    @Test
    fun `37 Laufende beim Farbsolo von null bis acht und bei Wenz und Geier von null bis vier`() {
        // Farbsolo: 0, 1, 2 zählen nicht (50); ab 3: 50 + n × 10.
        assertEquals(
            listOf(50, 50, 50, 80, 90, 100, 110, 120, 130),
            (0..8).map { ScoreCalculator.breakdown(game(GameType.FARBSOLO, a, laufende = it), standard).value },
        )
        // Wenz und Geier: 0 und 1 zählen nicht (50); ab 2: 50 + n × 10.
        for (type in listOf(GameType.WENZ, GameType.GEIER)) {
            assertEquals(
                listOf(50, 50, 70, 80, 90),
                (0..4).map { ScoreCalculator.breakdown(game(type, a, laufende = it), standard).value },
            )
        }
    }

    @Test
    fun `38 null bis vier Leger ohne und mit Bock beim Farbsolo mit Schneider`() {
        // (50 + 10) × 2^n: 60, 120, 240, 480, 960; mit Bock das Doppelte.
        fun values(bock: Boolean) = (0..4).map {
            ScoreCalculator.breakdown(game(GameType.FARBSOLO, a, level = ResultLevel.SCHNEIDER, gelegt = it, bock = bock), standard).value
        }
        assertEquals(listOf(60, 120, 240, 480, 960), values(bock = false))
        assertEquals(listOf(120, 240, 480, 960, 1920), values(bock = true))
    }
}
