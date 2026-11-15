package io.github.q0ochen.schafkopf.domain.rules

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

/**
 * Die Abrechnung nach dem Regelwerk der Runde ([RuleSet.standard]):
 * T = (Grundwert + Laufende × 10 + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt).
 * Jedes Beispiel ist von Hand vorgerechnet; der Rechenweg steht als Kommentar dabei.
 */
class ScoreCalculatorTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val table = listOf(a, b, c, d)
    private val rules = RuleSet.standard()

    private fun facts(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        announcement: Announcement = Announcement.NONE,
        gelegt: Int = 0,
        kontra: KontraLevel = KontraLevel.NONE,
        bock: Int = 0,
    ) = GameFacts(type, table, declarer, partner, won, level, laufende, announcement, gelegt, kontra, bock)

    private fun amounts(facts: GameFacts, rules: RuleSet = this.rules) = ScoreCalculator.amounts(facts, rules)

    private fun value(facts: GameFacts, rules: RuleSet = this.rules) = ScoreCalculator.breakdown(facts, rules).value

    private fun expect(aCents: Int, bCents: Int, cCents: Int, dCents: Int, facts: GameFacts, rules: RuleSet = this.rules) {
        assertEquals(mapOf(a to aCents, b to bCents, c to cCents, d to dCents), amounts(facts, rules))
    }

    // ---------------------------------------------------------------- Grundwerte je Spielart

    @Test
    fun `Rufspiel ohne Zusaetze - jeder Verlierer zahlt 10 Cent`() =
        expect(10, 10, -10, -10, facts(GameType.RUFSPIEL, a, b))

    @Test
    fun `Rufspiel verloren - die Spielerseite zahlt je 10 Cent`() =
        expect(-10, -10, 10, 10, facts(GameType.RUFSPIEL, a, b, won = false))

    @Test
    fun `Ueber Kreuz ohne Zusaetze - die zwei Gegenuebersitzenden gewinnen je 10 Cent`() =
        expect(10, -10, 10, -10, facts(GameType.UEBER_KREUZ, a, c))

    @Test
    fun `Farbsolo gewonnen - der Solist bekommt dreimal 50 Cent`() =
        expect(-50, -50, 150, -50, facts(GameType.FARBSOLO, c))

    @Test
    fun `Wenz verloren - der Solist zahlt dreimal 50 Cent`() =
        expect(50, -150, 50, 50, facts(GameType.WENZ, b, won = false))

    @Test
    fun `Geier gewonnen - der Solist bekommt dreimal 50 Cent`() =
        expect(-50, -50, -50, 150, facts(GameType.GEIER, d))

    @ParameterizedTest
    @CsvSource("RUFSPIEL,10", "UEBER_KREUZ,10", "FARBSOLO,50", "WENZ,50", "GEIER,50")
    fun `Grundwert jeder Spielart`(type: GameType, expected: Int) {
        assertEquals(expected, rules.price(type))
        val partner = if (type.isSolo) null else b
        assertEquals(expected, value(facts(type, a, partner)))
    }

    // ---------------------------------------------------------------- Laufende, Schneider, Schwarz

    @Test
    fun `Schneider kostet 10 Cent mehr`() {
        // 10 + 10 = 20
        expect(20, 20, -20, -20, facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHNEIDER))
        // 50 + 10 = 60, der Solist dreimal: 180
        expect(-60, -60, -60, 180, facts(GameType.GEIER, d, level = ResultLevel.SCHNEIDER))
    }

    @Test
    fun `Schwarz schliesst Schneider ein und kostet zusammen 20 Cent mehr`() {
        // 10 + 10 (Schneider) + 10 (Schwarz) = 30
        expect(30, 30, -30, -30, facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHWARZ))
        // 50 + 20 = 70, der Solist dreimal: 210
        expect(210, -70, -70, -70, facts(GameType.FARBSOLO, a, level = ResultLevel.SCHWARZ))
    }

    @ParameterizedTest
    @CsvSource("3,40", "4,50", "5,60", "6,70", "7,80", "8,90")
    fun `jeder Laufende kostet beim Rufspiel 10 Cent`(laufende: Int, expected: Int) {
        // 10 + Laufende × 10
        assertEquals(expected, value(facts(GameType.RUFSPIEL, a, b, laufende = laufende)))
        assertEquals(expected, value(facts(GameType.UEBER_KREUZ, a, c, laufende = laufende)))
    }

    @ParameterizedTest
    @CsvSource("3,80", "4,90", "5,100", "6,110", "7,120", "8,130")
    fun `jeder Laufende kostet beim Farbsolo 10 Cent`(laufende: Int, expected: Int) {
        // 50 + Laufende × 10
        assertEquals(expected, value(facts(GameType.FARBSOLO, a, laufende = laufende)))
    }

    @ParameterizedTest
    @CsvSource("WENZ,2,70", "WENZ,3,80", "WENZ,4,90", "GEIER,2,70", "GEIER,3,80", "GEIER,4,90")
    fun `bei Wenz und Geier zaehlen Laufende schon ab zwei`(type: GameType, laufende: Int, expected: Int) {
        // 50 + Laufende × 10
        assertEquals(expected, value(facts(type, a, laufende = laufende)))
    }

    @Test
    fun `Laufende unter der Schwelle zaehlen nicht`() {
        assertEquals(10, value(facts(GameType.RUFSPIEL, a, b, laufende = 2)))
        assertEquals(10, value(facts(GameType.UEBER_KREUZ, a, c, laufende = 1)))
        assertEquals(50, value(facts(GameType.FARBSOLO, a, laufende = 2)))
        assertEquals(50, value(facts(GameType.WENZ, a, laufende = 1)))
        assertEquals(50, value(facts(GameType.GEIER, a, laufende = 1)))
    }

    @Test
    fun `mit Schwelle eins zaehlt jeder Laufende wie im Wortlaut der Vorgabe`() {
        val fromOne = rules.copy(laufendeMin = GameType.entries.associateWith { 1 })
        // 10 + 1 × 10 = 20 und 10 + 2 × 10 = 30
        assertEquals(20, value(facts(GameType.RUFSPIEL, a, b, laufende = 1), fromOne))
        assertEquals(30, value(facts(GameType.RUFSPIEL, a, b, laufende = 2), fromOne))
        // 50 + 1 × 10 = 60
        assertEquals(60, value(facts(GameType.WENZ, a, laufende = 1), fromOne))
    }

    // ---------------------------------------------------------------- Potenzen aus Bock und Gelegt

    @ParameterizedTest
    @CsvSource("0,10", "1,20", "2,40", "3,80", "4,160")
    fun `jeder, der gelegt hat, verdoppelt das Rufspiel`(gelegt: Int, expected: Int) {
        // 10 × 2^gelegt
        assertEquals(expected, value(facts(GameType.RUFSPIEL, a, b, gelegt = gelegt)))
    }

    @ParameterizedTest
    @CsvSource("0,20", "1,40", "2,80", "3,160", "4,320")
    fun `Bock verdoppelt zusaetzlich`(gelegt: Int, expected: Int) {
        // 10 × 2 (Bock) × 2^gelegt
        assertEquals(expected, value(facts(GameType.RUFSPIEL, a, b, gelegt = gelegt, bock = 1)))
    }

    @ParameterizedTest
    @CsvSource(
        "0,0,60", "1,0,120", "2,0,240", "3,0,480", "4,0,960",
        "0,1,120", "1,1,240", "2,1,480", "3,1,960", "4,1,1920",
    )
    fun `Solo mit Schneider in allen Stufen von Gelegt und Bock`(gelegt: Int, bock: Int, expected: Int) {
        // (50 + 10) × 2^bock × 2^gelegt; der Solist bekommt das Dreifache
        val game = facts(GameType.FARBSOLO, b, level = ResultLevel.SCHNEIDER, gelegt = gelegt, bock = bock)
        assertEquals(expected, value(game))
        expect(-expected, 3 * expected, -expected, -expected, game)
    }

    @Test
    fun `Referenzfall - Farbsolo, drei Laufende, schwarz, zweimal gelegt`() {
        // (50 + 3 × 10 + 10 + 10) × 2^2 = 100 × 4 = 400 je Gegner, 1200 für den Solisten
        expect(-400, -400, 1200, -400, facts(GameType.FARBSOLO, c, level = ResultLevel.SCHWARZ, laufende = 3, gelegt = 2))
    }

    @Test
    fun `alles zusammen - Wenz, vier Laufende, schwarz, Bock, einmal gelegt`() {
        // (50 + 4 × 10 + 20) × 2 × 2 = 110 × 4 = 440 je Gegner, 1320 für den Solisten
        expect(-440, -440, -440, 1320, facts(GameType.WENZ, d, level = ResultLevel.SCHWARZ, laufende = 4, gelegt = 1, bock = 1))
    }

    @Test
    fun `groesster Fall der Vorgabe - Farbsolo, acht Laufende, schwarz, Bock, viermal gelegt`() {
        // (50 + 80 + 20) × 2 × 16 = 150 × 32 = 4800 je Gegner, 14400 für den Solisten
        expect(14_400, -4_800, -4_800, -4_800, facts(GameType.FARBSOLO, a, level = ResultLevel.SCHWARZ, laufende = 8, gelegt = 4, bock = 1))
    }

    @Test
    fun `ueberlagerte Bockrunden vervierfachen`() =
        // 10 × 2 × 2 = 40
        expect(40, 40, -40, -40, facts(GameType.RUFSPIEL, a, b, bock = 2))

    @Test
    fun `Aufschluesselung nennt Zuschlaege und Faktoren einzeln`() {
        val breakdown = ScoreCalculator.breakdown(
            facts(GameType.FARBSOLO, c, level = ResultLevel.SCHWARZ, laufende = 3, gelegt = 2, kontra = KontraLevel.KONTRA, bock = 1),
            rules,
        )
        assertEquals(50, breakdown.basePrice)
        assertEquals(3, breakdown.laufendeCounted)
        assertEquals(30, breakdown.laufendeAmount)
        assertEquals(20, breakdown.levelAmount)
        assertEquals(100, breakdown.sum)
        assertEquals(1, breakdown.announcementFactor)
        assertEquals(4, breakdown.klopferFactor)
        assertEquals(2, breakdown.kontraFactor)
        assertEquals(2, breakdown.bockFactor)
        assertEquals(16, breakdown.totalFactor)
        assertEquals(1600, breakdown.value)
    }

    // ---------------------------------------------------------------- Ueber Kreuz wertet wie ein Rufspiel

    @Test
    fun `Ueber Kreuz wird in jedem Fall genau wie ein Rufspiel abgerechnet`() {
        val random = Random(2026)
        repeat(500) {
            val rufspiel = randomFacts(random).copy(
                type = GameType.RUFSPIEL,
                partner = c,
                declarer = a,
                announcement = Announcement.NONE,
            )
            assertEquals(amounts(rufspiel), amounts(rufspiel.copy(type = GameType.UEBER_KREUZ)))
        }
    }

    // ---------------------------------------------------------------- Kontra, Retour, Tout (Zusatz, abschaltbar)

    @Test
    fun `Kontra verdoppelt und Retour vervierfacht`() {
        // 50 × 2 = 100, der Solist dreimal: 300
        expect(300, -100, -100, -100, facts(GameType.FARBSOLO, a, kontra = KontraLevel.KONTRA))
        // 10 × 4 = 40
        expect(-40, -40, 40, 40, facts(GameType.RUFSPIEL, a, b, won = false, kontra = KontraLevel.RETOUR))
    }

    @Test
    fun `Tout verdoppelt, Schneider und Schwarz entfallen dabei`() {
        // (50 + 3 × 10) × 2 = 160, der Solist dreimal: 480
        expect(
            480, -160, -160, -160,
            facts(GameType.FARBSOLO, a, level = ResultLevel.SCHWARZ, laufende = 3, announcement = Announcement.TOUT),
        )
    }

    @Test
    fun `Sie zaehlt wie ein Tout mit acht Laufenden`() {
        // (50 + 8 × 10) × 2 = 260, der Solist dreimal: 780
        expect(780, -260, -260, -260, facts(GameType.FARBSOLO, a, announcement = Announcement.SIE))
    }

    // ---------------------------------------------------------------- Verteilung der Spielschuld

    @Test
    fun `Schafkopf-Regel ist der Standard`() {
        assertEquals(PayoutRule.SCHAFKOPF, rules.payoutRule)
    }

    @Test
    fun `Topf-Teilung - der Solist gewinnt nur einen Spielwert, die Gegner teilen ihn sich`() {
        val potSplit = rules.copy(payoutRule = PayoutRule.POT_SPLIT)
        // T = 50 + 10 = 60: Solist +60, die drei Gegner je 20
        expect(-20, 60, -20, -20, facts(GameType.FARBSOLO, b, level = ResultLevel.SCHNEIDER), potSplit)
        // T = 50: 50 durch drei = 16 Rest 2, die vorderen Gegner tragen je einen Cent mehr: 17, 17, 16
        expect(-17, 50, -17, -16, facts(GameType.WENZ, b), potSplit)
        // Verliert der Solist, bekommen die Gegner entsprechend 17, 17, 16
        expect(17, -50, 17, 16, facts(GameType.WENZ, b, won = false), potSplit)
    }

    @Test
    fun `Topf-Teilung aendert zwei gegen zwei nicht`() {
        val potSplit = rules.copy(payoutRule = PayoutRule.POT_SPLIT)
        expect(10, 10, -10, -10, facts(GameType.RUFSPIEL, a, b), potSplit)
        expect(-10, 10, -10, 10, facts(GameType.UEBER_KREUZ, b, d), potSplit)
    }

    @Test
    fun `Gewinner und Verlierer ergeben sich aus Ansager, Mitspieler und Ausgang`() {
        assertEquals(listOf(a, b), facts(GameType.RUFSPIEL, b, a).winners)
        assertEquals(listOf(c, d), facts(GameType.RUFSPIEL, b, a).losers)
        assertEquals(listOf(c, d), facts(GameType.RUFSPIEL, b, a, won = false).winners)
        assertEquals(listOf(b, d), facts(GameType.UEBER_KREUZ, d, b).winners)
        assertEquals(listOf(c), facts(GameType.WENZ, c).winners)
        assertEquals(listOf(a, b, d), facts(GameType.WENZ, c).losers)
        assertEquals(listOf(a, b, d), facts(GameType.WENZ, c, won = false).winners)
        assertEquals(setOf(a, b), facts(GameType.RUFSPIEL, b, a).declarerSide)
        // Ein beim Einzelspiel versehentlich eingetragener Mitspieler zählt nicht zur Spielerseite.
        assertEquals(setOf(c), facts(GameType.GEIER, c, a).declarerSide)
    }

    @ParameterizedTest
    @EnumSource(GameType::class)
    fun `bei jeder Spielart stimmt die Zahl der Gewinner`(type: GameType) {
        val partner = if (type.isSolo) null else c
        assertEquals(if (type.isSolo) 1 else 2, facts(type, a, partner).winners.size)
        assertEquals(if (type.isSolo) 3 else 2, facts(type, a, partner, won = false).winners.size)
    }

    // ---------------------------------------------------------------- Eigenschaften, die immer gelten

    @Test
    fun `jedes zufaellige Spiel ist ein Nullsummenspiel`() {
        val random = Random(20261114)
        val ruleSets = listOf(
            rules,
            rules.copy(payoutRule = PayoutRule.POT_SPLIT),
            RuleSet.sauspiel(),
            RuleSet.sauspiel().copy(payoutRule = PayoutRule.POT_SPLIT),
        )
        repeat(2_000) {
            val facts = randomFacts(random)
            for (ruleSet in ruleSets) {
                val result = amounts(facts, ruleSet)
                assertEquals(0, result.values.sum(), "Summe ungleich 0 bei $facts")
                assertEquals(table.toSet(), result.keys)
                assertTrue(facts.winners.all { result.getValue(it) > 0 }, "Gewinner ohne Gewinn bei $facts")
                assertTrue(facts.losers.all { result.getValue(it) < 0 }, "Verlierer ohne Verlust bei $facts")
            }
        }
    }

    @Test
    fun `auch ein fehlerhaft eingetragenes Spiel bleibt ein Nullsummenspiel`() {
        // Rufspiel mit sich selbst, ohne Mitspieler oder mit einem Fremden: Die Summe ist trotzdem 0.
        assertEquals(0, amounts(facts(GameType.RUFSPIEL, a, a)).values.sum())
        assertEquals(0, amounts(facts(GameType.RUFSPIEL, a)).values.sum())
        assertEquals(0, amounts(facts(GameType.UEBER_KREUZ, a, PlayerId("X"))).values.sum())
        assertEquals(mapOf(a to 0, b to 0, c to 0, d to 0), amounts(facts(GameType.WENZ, PlayerId("X"), won = false)))
    }

    @Test
    fun `jeder weitere, der legt, verdoppelt genau`() {
        val random = Random(7)
        repeat(500) {
            val facts = randomFacts(random).copy(klopferCount = random.nextInt(0, 4))
            val base = value(facts)
            assertEquals(2 * base, value(facts.copy(klopferCount = facts.klopferCount + 1)))
        }
    }

    @Test
    fun `ein Bockspiel ist genau doppelt so teuer`() {
        val random = Random(8)
        repeat(500) {
            val facts = randomFacts(random).copy(bockExponent = 0)
            assertEquals(amounts(facts).mapValues { it.value * 2 }, amounts(facts.copy(bockExponent = 1)))
        }
    }

    @Test
    fun `Betraege wachsen im gleichen Verhaeltnis wie der Tarif`() {
        val random = Random(13)
        val tripled = rules.copy(unit = rules.unit * 3, prices = GameType.entries.associateWith { rules.price(it) * 3 })
        repeat(500) {
            val facts = randomFacts(random)
            assertEquals(amounts(facts).mapValues { it.value * 3 }, amounts(facts, tripled))
        }
    }

    @Test
    fun `Gewinn und Verlust unterscheiden sich nur im Vorzeichen`() {
        val random = Random(17)
        repeat(500) {
            val facts = randomFacts(random).copy(announcement = Announcement.NONE)
            for (ruleSet in listOf(rules, rules.copy(payoutRule = PayoutRule.POT_SPLIT))) {
                assertEquals(
                    amounts(facts.copy(declarerWon = true), ruleSet).mapValues { -it.value },
                    amounts(facts.copy(declarerWon = false), ruleSet),
                )
            }
        }
    }

    // ---------------------------------------------------------------- Regelwerk

    @Test
    fun `das Regelwerk der Runde entspricht der Vorgabe`() {
        assertEquals(10, rules.unit)
        assertEquals(GameType.entries.toSet(), rules.enabledTypes)
        assertEquals(listOf(8, 8, 8, 4, 4), GameType.entries.map { rules.laufendeMax(it) })
        assertEquals(listOf(3, 3, 3, 2, 2), GameType.entries.map { rules.laufendeMin(it) })
        assertEquals(2, rules.bock.factor)
        assertEquals(4, rules.bock.gamesPerTrigger)
        assertTrue(rules.klopfenEnabled)
        assertTrue(rules.displayAsMoney)
    }

    @Test
    fun `fehlt Ueber Kreuz in einem Regelwerk, gilt der Wert des Rufspiels`() {
        val sauspiel = RuleSet.sauspiel()
        assertEquals(20, sauspiel.price(GameType.UEBER_KREUZ))
        assertEquals(14, sauspiel.laufendeMax(GameType.UEBER_KREUZ))
        assertEquals(3, sauspiel.laufendeMin(GameType.UEBER_KREUZ))
        assertTrue(GameType.UEBER_KREUZ !in sauspiel.enabledTypes)
    }

    @Test
    fun `fehlende Werte in einem Regelwerk werden durch die der Runde ersetzt`() {
        val empty = rules.copy(prices = emptyMap(), laufendeMin = emptyMap(), laufendeMax = emptyMap())
        for (type in GameType.entries) {
            assertEquals(rules.price(type), empty.price(type))
            assertEquals(rules.laufendeMin(type), empty.laufendeMin(type))
            assertEquals(rules.laufendeMax(type), empty.laufendeMax(type))
        }
    }

    @Test
    fun `Tout gibt es nur im Einzelspiel und ein Sie nur im Farbsolo`() {
        assertEquals(listOf(false, false, true, true, true), GameType.entries.map { rules.allowsTout(it) })
        assertEquals(listOf(false, false, true, false, false), GameType.entries.map { rules.allowsSie(it) })
    }

    // ---------------------------------------------------------------- Prüfung der Eingabe

    @Test
    fun `gueltige Spiele haben keine Regelverstoesse`() {
        assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(facts(GameType.RUFSPIEL, a, b), rules))
        assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(facts(GameType.UEBER_KREUZ, b, d, laufende = 8), rules))
        assertEquals(emptyList<RuleViolation>(), ScoreCalculator.validate(facts(GameType.WENZ, a, laufende = 4, gelegt = 4, bock = 1), rules))
    }

    @Test
    fun `Regelverstoesse werden erkannt`() {
        fun violations(facts: GameFacts, rules: RuleSet = this.rules) = ScoreCalculator.validate(facts, rules)

        assertTrue(RuleViolation.PARTNER_MISSING in violations(facts(GameType.RUFSPIEL, a)))
        assertTrue(RuleViolation.PARTNER_MISSING in violations(facts(GameType.UEBER_KREUZ, a)))
        assertTrue(RuleViolation.PARTNER_IS_DECLARER in violations(facts(GameType.RUFSPIEL, a, a)))
        assertTrue(RuleViolation.PARTNER_NOT_AT_TABLE in violations(facts(GameType.RUFSPIEL, a, PlayerId("X"))))
        assertTrue(RuleViolation.PARTNER_NOT_ALLOWED in violations(facts(GameType.WENZ, a, b)))
        assertTrue(RuleViolation.DECLARER_NOT_AT_TABLE in violations(facts(GameType.WENZ, PlayerId("X"))))
        assertTrue(RuleViolation.LAUFENDE_OUT_OF_RANGE in violations(facts(GameType.WENZ, a, laufende = 5)))
        assertTrue(RuleViolation.LAUFENDE_OUT_OF_RANGE in violations(facts(GameType.RUFSPIEL, a, b, laufende = 9)))
        assertTrue(RuleViolation.LAUFENDE_OUT_OF_RANGE in violations(facts(GameType.RUFSPIEL, a, b, laufende = -1)))
        assertTrue(RuleViolation.TOUT_NOT_ALLOWED in violations(facts(GameType.RUFSPIEL, a, b, announcement = Announcement.TOUT)))
        assertTrue(RuleViolation.SIE_NOT_ALLOWED in violations(facts(GameType.WENZ, a, announcement = Announcement.SIE)))
        assertTrue(
            RuleViolation.SIE_CANNOT_BE_LOST in
                violations(facts(GameType.FARBSOLO, a, won = false, announcement = Announcement.SIE)),
        )
        assertTrue(RuleViolation.KLOPFER_OUT_OF_RANGE in violations(facts(GameType.WENZ, a, gelegt = 5)))
        assertTrue(RuleViolation.BOCK_EXPONENT_NEGATIVE in violations(facts(GameType.WENZ, a, bock = -1)))
        assertTrue(
            RuleViolation.GAME_TYPE_DISABLED in
                violations(facts(GameType.GEIER, a), rules.copy(enabledTypes = setOf(GameType.RUFSPIEL))),
        )
        assertTrue(RuleViolation.GAME_TYPE_DISABLED in violations(facts(GameType.UEBER_KREUZ, a, c), RuleSet.sauspiel()))
        assertTrue(RuleViolation.KLOPFEN_DISABLED in violations(facts(GameType.WENZ, a, gelegt = 1), rules.copy(klopfenEnabled = false)))
        assertTrue(
            RuleViolation.KONTRA_DISABLED in
                violations(facts(GameType.WENZ, a, kontra = KontraLevel.KONTRA), rules.copy(kontraEnabled = false)),
        )
        assertTrue(
            RuleViolation.RETOUR_DISABLED in
                violations(facts(GameType.WENZ, a, kontra = KontraLevel.RETOUR), rules.copy(retourEnabled = false)),
        )
        val threePlayers = facts(GameType.WENZ, a).copy(players = listOf(a, b, c))
        assertTrue(RuleViolation.WRONG_PLAYER_COUNT in violations(threePlayers))
        val duplicate = facts(GameType.WENZ, a).copy(players = listOf(a, b, c, c))
        assertTrue(RuleViolation.DUPLICATE_PLAYERS in violations(duplicate))
    }

    @Test
    fun `ein negativer Bock-Wert oder zu viele Leger bringen die Rechnung nicht durcheinander`() {
        // Unsinnige Werte werden auf den erlaubten Bereich begrenzt (und von validate gemeldet).
        assertEquals(10, value(facts(GameType.RUFSPIEL, a, b, bock = -3)))
        assertEquals(160, value(facts(GameType.RUFSPIEL, a, b, gelegt = 9)))
        assertEquals(10, value(facts(GameType.RUFSPIEL, a, b, gelegt = -2)))
    }

    private fun randomFacts(random: Random): GameFacts {
        val type = GameType.entries.random(random)
        val declarer = table.random(random)
        val partner = if (type.isSolo) null else (table - declarer).random(random)
        val announcement = when {
            !type.isSolo -> Announcement.NONE
            random.nextInt(10) == 0 -> Announcement.TOUT
            type == GameType.FARBSOLO && random.nextInt(40) == 0 -> Announcement.SIE
            else -> Announcement.NONE
        }
        return GameFacts(
            type = type,
            players = table,
            declarer = declarer,
            partner = partner,
            declarerWon = announcement == Announcement.SIE || random.nextBoolean(),
            level = ResultLevel.entries.random(random),
            laufende = random.nextInt(0, rules.laufendeMax(type) + 1),
            announcement = announcement,
            klopferCount = random.nextInt(0, 5),
            kontra = KontraLevel.entries.random(random),
            bockExponent = random.nextInt(0, 3),
        )
    }
}
