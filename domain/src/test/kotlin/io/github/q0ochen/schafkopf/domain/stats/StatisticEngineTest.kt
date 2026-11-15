package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prüft jede Kennzahl der [StatisticEngine] an den beiden von Hand durchgerechneten Abenden nach dem Regelwerk der
 * Runde (Tabellen in [RoundEvenings]): erst jeden Abend für sich, dann beide zusammen als „ewige Tabelle“.
 *
 * Wer am ersten Abend bei welchem Spiel auf der Gewinnerseite stand (G) oder verloren hat (V); R = Rufspiel,
 * K = Über Kreuz, F = Farbsolo, W = Wenz, G = Geier, * = Bockspiel:
 *
 * ```
 * Spiel   1  2  3  4  5  6* 7* 8* 9* 10 11 12 13 14   Siege
 * Art     R  K  F  R  W  R  G  K  R  F  R  W  R  R
 * A       G  G  V  G  G  G  V  G  V  G  G  V  V  G      9
 * B       G  V  G  G  G  V  V  V  G  V  V  G  V  V      6
 * C       V  G  V  V  G  V  G  G  G  G  G  V  G  G      9
 * D       V  V  V  V  V  G  V  V  V  G  V  V  G  V      3
 * ```
 *
 * Zweiter Abend:
 *
 * ```
 * Spiel   1  2  3  4  5* 6*   Siege
 * Art     R  R  K  G  R  F
 * E       G  G  V  G  G  G      5
 * A       G  G  G  V  V  G      4
 * C       V  V  V  V  V  V      0
 * B       V  V  G  V  G  G      3
 * ```
 */
class StatisticEngineTest {

    private val a = RoundEvenings.a
    private val b = RoundEvenings.b
    private val c = RoundEvenings.c
    private val d = RoundEvenings.d
    private val e = RoundEvenings.e

    private val firstState: EveningState = EveningCalculator.compute(RoundEvenings.evening)
    private val secondState: EveningState = EveningCalculator.compute(RoundEvenings.secondEvening)

    private val first = StatisticEngine.forEvening(RoundEvenings.evening)
    private val second = StatisticEngine.forEvening(RoundEvenings.secondEvening)

    // Die Abende kommen wie aus der Datenhaltung: der neueste zuerst.
    private val all = StatisticEngine.forEvenings(listOf(RoundEvenings.secondEvening, RoundEvenings.evening))

    private val none = ModeRecord(0, 0, 0)

    /** Die fünf Spielarten in der Reihenfolge der Aufzählung; was nicht genannt ist, wurde nie gespielt. */
    private fun modes(
        rufspiel: ModeRecord = none,
        ueberKreuz: ModeRecord = none,
        farbsolo: ModeRecord = none,
        wenz: ModeRecord = none,
        geier: ModeRecord = none,
    ): Map<GameType, ModeRecord> = mapOf(
        GameType.RUFSPIEL to rufspiel,
        GameType.UEBER_KREUZ to ueberKreuz,
        GameType.FARBSOLO to farbsolo,
        GameType.WENZ to wenz,
        GameType.GEIER to geier,
    )

    private fun Statistics.atTable(player: PlayerId) = checkNotNull(gameModesOf(player)).atTable

    private fun Statistics.asPlayer(player: PlayerId) = checkNotNull(gameModesOf(player)).asPlayer

    // ---------------------------------------------------------------------------------------------------------------
    // Grundlage: Die Abrechnung der beiden Abende stimmt mit der Handrechnung überein.
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `erster Abend - Spielwerte, Betraege und Bockspiele stimmen mit der Handrechnung ueberein`() {
        val games = firstState.results.filter { it.gameNumber != null }
        val seating = listOf(a, b, c, d)

        assertEquals((1..14).toList(), games.map { it.gameNumber })
        assertEquals(RoundEvenings.games.map { it.id }, games.map { it.entry.id })
        assertEquals(
            listOf(10, 20, 80, 20, 280, 60, 240, 200, 40, 60, 20, 100, 20, 70),
            games.map { it.breakdown?.value },
        )
        assertEquals(
            listOf(
                listOf(10, 10, -10, -10),
                listOf(20, -20, 20, -20),
                listOf(-80, 240, -80, -80),
                listOf(20, 20, -20, -20),
                listOf(280, 280, 280, -840),
                listOf(60, -60, -60, 60),
                listOf(-240, -240, 720, -240),
                listOf(200, -200, 200, -200),
                listOf(-40, 40, 40, -40),
                listOf(60, -180, 60, 60),
                listOf(20, -20, 20, -20),
                listOf(-100, 300, -100, -100),
                listOf(-20, -20, 20, 20),
                listOf(70, -70, 70, -70),
            ),
            games.map { game -> seating.map { game.amounts.getValue(it) } },
        )
        // Bockspiele sind die Spiele 6 bis 9.
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0), games.map { it.bockExponent })
        assertEquals(mapOf(a to 310, b to 30, c to 1160, d to -1500), firstState.balances)
        assertTrue(firstState.results.all { it.issues.isEmpty() })
    }

    @Test
    fun `zweiter Abend - Spielwerte, Betraege und Bockspiele stimmen mit der Handrechnung ueberein`() {
        val games = secondState.results.filter { it.gameNumber != null }
        val seating = listOf(e, a, c, b)

        assertEquals(RoundEvenings.secondGames.map { it.id }, games.map { it.entry.id })
        assertEquals(listOf(40, 120, 10, 140, 240, 100), games.map { it.breakdown?.value })
        assertEquals(
            listOf(
                listOf(40, 40, -40, -40),
                listOf(120, 120, -120, -120),
                listOf(-10, 10, -10, 10),
                listOf(420, -140, -140, -140),
                listOf(240, -240, -240, 240),
                listOf(100, 100, -300, 100),
            ),
            games.map { game -> seating.map { game.amounts.getValue(it) } },
        )
        assertEquals(listOf(0, 0, 0, 0, 1, 1), games.map { it.bockExponent })
        assertEquals(mapOf(e to 880, a to -110, c to -850, b to 80), secondState.balances)
        // Von der Bockrunde über vier Spiele sind erst zwei gespielt.
        assertEquals(2, secondState.bockGamesRemaining)
        assertTrue(secondState.results.all { it.issues.isEmpty() })
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Erster Abend
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `erster Abend - Spieler stehen in Sitzreihenfolge`() {
        assertEquals(1, first.eveningCount)
        assertEquals(14, first.gameCount)
        assertEquals(listOf(a, b, c, d), first.players)
        assertEquals(first.players, first.finance.standings.map { it.player })
        assertEquals(first.players, first.finance.balanceSeries.keys.toList())
        assertEquals(first.players, first.gameModes.map { it.player })
        assertEquals(first.players, first.gelegt.map { it.player })
        assertEquals(first.players, first.bock.records.map { it.player })
        assertEquals(first.players, first.solo.records.map { it.player })
    }

    @Test
    fun `erster Abend - Kontostand und Platz`() {
        assertEquals(
            listOf(Standing(a, 310, 2), Standing(b, 30, 3), Standing(c, 1160, 1), Standing(d, -1500, 4)),
            first.finance.standings,
        )
        assertEquals(Standing(c, 1160, 1), first.finance.standingOf(c))
        assertNull(first.finance.standingOf(e))
        // Spiele 1 bis 7: 20 + 40 + 240 + 40 + 840 + 120 + 720 = 2020. Freie Buchung: 50.
        // Spiele 8 bis 14: 400 + 80 + 180 + 40 + 300 + 40 + 140 = 1180. Zusammen 3250.
        assertEquals(3250, first.finance.turnover)
    }

    @Test
    fun `erster Abend - Vermoegensverlauf mit einem Punkt je Spiel`() {
        // Die freie Buchung zwischen Spiel 7 und Spiel 8 steckt im Punkt von Spiel 8.
        val expected = mapOf(
            a to listOf(0, 10, 30, -50, -30, 250, 310, 70, 320, 280, 340, 360, 260, 240, 310),
            b to listOf(0, 10, -10, 230, 250, 530, 470, 230, -20, 20, -160, -180, 120, 100, 30),
            c to listOf(0, -10, 10, -70, -90, 190, 130, 850, 1050, 1090, 1150, 1170, 1070, 1090, 1160),
            d to listOf(0, -10, -30, -110, -130, -970, -910, -1150, -1350, -1390, -1330, -1350, -1450, -1430, -1500),
        )
        assertEquals(expected, first.finance.balanceSeries)
        assertEquals(listOf(0), first.finance.eveningStarts)
        assertEquals(listOf(RoundEvenings.id), first.finance.eveningIds)
        // Für einen einzelnen Abend ist das derselbe Verlauf, den der Abend-Bildschirm zeichnet.
        assertEquals(Progression.balanceSeries(firstState), first.finance.balanceSeries)
    }

    @Test
    fun `erster Abend - Spielart-Analyse am Tisch`() {
        // Rufspiele 1, 4, 6, 9, 11, 13, 14; Über Kreuz 2, 8; Farbsolo 3, 10; Wenz 5, 12; Geier 7.
        assertEquals(
            modes(
                // +10 +20 +60 −40 +20 −20 +70
                rufspiel = ModeRecord(7, 5, 120),
                ueberKreuz = ModeRecord(2, 2, 220),
                farbsolo = ModeRecord(2, 1, -20),
                wenz = ModeRecord(2, 1, 180),
                geier = ModeRecord(1, 0, -240),
            ),
            first.atTable(a),
        )
        assertEquals(
            modes(
                // +10 +20 −60 +40 −20 −20 −70
                rufspiel = ModeRecord(7, 3, -100),
                ueberKreuz = ModeRecord(2, 0, -220),
                farbsolo = ModeRecord(2, 1, 60),
                wenz = ModeRecord(2, 2, 580),
                geier = ModeRecord(1, 0, -240),
            ),
            first.atTable(b),
        )
        assertEquals(
            modes(
                // −10 −20 −60 +40 +20 +20 +70
                rufspiel = ModeRecord(7, 4, 60),
                ueberKreuz = ModeRecord(2, 2, 220),
                farbsolo = ModeRecord(2, 1, -20),
                wenz = ModeRecord(2, 1, 180),
                geier = ModeRecord(1, 1, 720),
            ),
            first.atTable(c),
        )
        assertEquals(
            modes(
                // −10 −20 +60 −40 −20 +20 −70
                rufspiel = ModeRecord(7, 2, -80),
                ueberKreuz = ModeRecord(2, 0, -220),
                farbsolo = ModeRecord(2, 1, -20),
                wenz = ModeRecord(2, 0, -940),
                geier = ModeRecord(1, 0, -240),
            ),
            first.atTable(d),
        )
        // Jede Spielart ist enthalten, in der Reihenfolge der Aufzählung.
        assertEquals(GameType.entries, first.atTable(a).keys.toList())
        assertEquals(GameType.entries, first.asPlayer(a).keys.toList())
        assertNull(first.gameModesOf(e))
    }

    @Test
    fun `erster Abend - Gewinnrate und Durchschnitt je Spielart`() {
        val rufspiel = GameType.RUFSPIEL
        assertEquals(5.0 / 7, first.atTable(a).getValue(rufspiel).winRate)
        assertEquals(2, first.atTable(a).getValue(rufspiel).lost)
        // 120 / 7 = 17,14; −100 / 7 = −14,29; 60 / 7 = 8,57; −80 / 7 = −11,43.
        assertEquals(17, first.atTable(a).getValue(rufspiel).averageAmount)
        assertEquals(-14, first.atTable(b).getValue(rufspiel).averageAmount)
        assertEquals(9, first.atTable(c).getValue(rufspiel).averageAmount)
        assertEquals(-11, first.atTable(d).getValue(rufspiel).averageAmount)
        assertEquals(110, first.atTable(a).getValue(GameType.UEBER_KREUZ).averageAmount)
        assertEquals(-470, first.atTable(d).getValue(GameType.WENZ).averageAmount)
        assertEquals(1.0, first.atTable(b).getValue(GameType.WENZ).winRate)
        assertEquals(0.0, first.atTable(d).getValue(GameType.WENZ).winRate)
        // B hat seine vier selbst gespielten Rufspiele mit zusammen +10 beendet: 2,5 Cent je Spiel, gerundet 3.
        assertEquals(3, first.asPlayer(b).getValue(rufspiel).averageAmount)
        // Ohne Spiel gibt es weder Quote noch Durchschnitt.
        assertNull(first.asPlayer(a).getValue(GameType.GEIER).winRate)
        assertNull(first.asPlayer(a).getValue(GameType.GEIER).averageAmount)
    }

    @Test
    fun `erster Abend - Spielart-Analyse der selbst gespielten Spiele`() {
        // A: Ansager in 1, 6, 13, Mitspieler in 14; kein Einzelspiel.
        assertEquals(
            modes(rufspiel = ModeRecord(4, 3, 120), ueberKreuz = ModeRecord(2, 2, 220)),
            first.asPlayer(a),
        )
        // B: Mitspieler in 1, 11, 13, Ansager in 9; Farbsolo 3 und 10, Wenz 12.
        assertEquals(
            modes(
                rufspiel = ModeRecord(4, 2, 10),
                ueberKreuz = ModeRecord(2, 0, -220),
                farbsolo = ModeRecord(2, 1, 60),
                wenz = ModeRecord(1, 1, 300),
            ),
            first.asPlayer(b),
        )
        // C: Ansager in 4 und 14, Mitspieler in 9; Geier 7.
        assertEquals(
            modes(rufspiel = ModeRecord(3, 2, 90), ueberKreuz = ModeRecord(2, 2, 220), geier = ModeRecord(1, 1, 720)),
            first.asPlayer(c),
        )
        // D: Mitspieler in 4 und 6, Ansager in 11; Wenz 5.
        assertEquals(
            modes(rufspiel = ModeRecord(3, 1, 20), ueberKreuz = ModeRecord(2, 0, -220), wenz = ModeRecord(1, 0, -840)),
            first.asPlayer(d),
        )
        // Bei Über Kreuz sagt niemand an: Dort spielt jeder am Tisch selbst.
        for (player in first.players) {
            assertEquals(first.atTable(player).getValue(GameType.UEBER_KREUZ), first.asPlayer(player).getValue(GameType.UEBER_KREUZ))
        }
    }

    @Test
    fun `erster Abend - Sollte-er-es-lassen-Index`() {
        // A legt in Spiel 5 (+280). Ohne den eigenen Leger wäre das Spiel 140 wert gewesen: Wirkung +140.
        assertEquals(GelegtRecord(a, count = 1, won = 1, netBalance = 280, effect = 140), first.gelegtOf(a))
        // B legt nie.
        assertEquals(GelegtRecord(b, count = 0, won = 0, netBalance = 0, effect = 0), first.gelegtOf(b))
        // C legt in Spiel 4 (−20), 7 (+720) und 9 (+40); ohne den eigenen Leger −10, +360 und +20.
        assertEquals(GelegtRecord(c, count = 3, won = 2, netBalance = 740, effect = 370), first.gelegtOf(c))
        // D legt in Spiel 5 (−840), 8 (−200), 11 (−20) und 13 (+20); ohne den eigenen Leger −420, −100, −10, +10.
        assertEquals(GelegtRecord(d, count = 4, won = 1, netBalance = -1040, effect = -520), first.gelegtOf(d))
        assertNull(first.gelegtOf(e))

        assertEquals(
            listOf(GelegtBadge.NOCH_OFFEN, GelegtBadge.STREICHHOLZ_SPARER, GelegtBadge.SICHERE_BANK, GelegtBadge.BRANDSTIFTER),
            first.gelegt.map { it.badge },
        )
        assertEquals(listOf(false, false, false, true), first.gelegt.map { it.shouldLeaveIt })
        assertEquals(listOf(1.0, null, 2.0 / 3, 0.25), first.gelegt.map { it.winRate })
        assertEquals(listOf(0, 0, 1, 3), first.gelegt.map { it.lost })
    }

    @Test
    fun `erster Abend - Bock-Koenig`() {
        // Bockspiele sind 6 bis 9; die freie Buchung dazwischen ist kein Spiel und zählt hier nicht.
        assertEquals(
            listOf(
                // +60 −240 +200 −40
                BockRecord(a, games = 4, won = 2, balance = -20),
                // −60 −240 −200 +40
                BockRecord(b, games = 4, won = 1, balance = -460),
                // −60 +720 +200 +40
                BockRecord(c, games = 4, won = 3, balance = 900),
                // +60 −240 −200 −40
                BockRecord(d, games = 4, won = 1, balance = -420),
            ),
            first.bock.records,
        )
        assertEquals(BockRecord(c, 4, 3, 900), first.bock.king)
        assertEquals(0.75, first.bock.king?.winRate)
        assertEquals(BockRecord(a, 4, 2, -20), first.bock.recordOf(a))
        assertNull(first.bock.recordOf(e))
    }

    @Test
    fun `erster Abend - Solo-Spezialist und Mauerbluemchen`() {
        assertEquals(
            listOf(
                // A sagt die Rufspiele 1, 6 und 13 an und spielt nie allein.
                SoloRecord(a, solos = 0, calls = 3),
                // B spielt Farbsolo 3 und 10 und Wenz 12 und sagt das Rufspiel 9 an.
                SoloRecord(b, solos = 3, calls = 1),
                // C spielt den Geier 7 und sagt die Rufspiele 4 und 14 an.
                SoloRecord(c, solos = 1, calls = 2),
                // D spielt den Wenz 5 und sagt das Rufspiel 11 an.
                SoloRecord(d, solos = 1, calls = 1),
            ),
            first.solo.records,
        )
        assertEquals(listOf(0.0, 0.75, 1.0 / 3, 0.5), first.solo.records.map { it.soloShare })
        assertEquals(listOf(3, 4, 3, 2), first.solo.records.map { it.announced })
        assertEquals(SoloRecord(b, 3, 1), first.solo.specialist)
        // A hat drei Spiele angesagt und keines davon allein gespielt.
        assertEquals(SoloRecord(a, 0, 3), first.solo.wallflower)
        assertEquals(SoloRecord(c, 1, 2), first.solo.recordOf(c))
        assertNull(first.solo.recordOf(e))
    }

    @Test
    fun `erster Abend - Zweiergespanne und Lieblingspartner`() {
        // Zwei gegen zwei: 1 (A+B schlagen C+D, 10), 2 (A+C schlagen B+D, 20), 4 (A+B schlagen C+D, 20),
        // 6 (A+D schlagen B+C, 60), 8 (A+C schlagen B+D, 200), 9 (B+C schlagen A+D, 40), 11 (A+C schlagen B+D, 20),
        // 13 (C+D schlagen A+B, 20), 14 (A+C schlagen B+D, 70). Der gemeinsame Saldo ist je Spiel zweimal der Wert.
        assertEquals(
            listOf(
                // 40 + 400 + 40 + 140
                PartnerPair(a, c, games = 4, won = 4, balance = 620),
                // 120 − 80
                PartnerPair(a, d, games = 2, won = 1, balance = 40),
                // 20 + 40 − 40
                PartnerPair(a, b, games = 3, won = 2, balance = 20),
                PartnerPair(c, d, games = 3, won = 1, balance = -20),
                PartnerPair(b, c, games = 2, won = 1, balance = -40),
                PartnerPair(b, d, games = 4, won = 0, balance = -620),
            ),
            first.partners.pairs,
        )
        assertEquals(PartnerPair(a, c, 4, 4, 620), first.partners.favorite)
        assertEquals(1.0, first.partners.favorite?.winRate)
        assertEquals(PartnerPair(b, d, 4, 0, -620), first.partners.pairOf(d, b))
        assertNull(first.partners.pairOf(a, e))
        // Was das eine Gespann gewinnt, verliert das andere.
        assertEquals(0, first.partners.pairs.sumOf { it.balance })
    }

    @Test
    fun `erster Abend - die uebrigen Kennzahlen kommen aus der bisherigen Statistik`() {
        assertEquals(HistoryStatistics.compute(listOf(RoundEvenings.evening)), first.history)
        // Siege laut der Tabelle im Klassenkommentar.
        assertEquals(mapOf(a to 9, b to 6, c to 9, d to 3), first.history.playerStats.associate { it.player to it.gamesWon })
        // Die Kennzahlen zum Legen stimmen mit dem Index überein.
        for (record in first.gelegt) {
            val stats = checkNotNull(first.history.statsFor(record.player))
            assertEquals(RoleRecord(record.count, record.won), stats.klopferGiven)
            assertEquals(record.effect, stats.klopferEffect)
        }
        assertEquals(4, first.history.bockGamesCount)
        assertEquals(first.finance.turnover, first.history.turnover)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Zweiter Abend
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `zweiter Abend - Kontostand, Platz und Verlauf`() {
        assertEquals(1, second.eveningCount)
        assertEquals(6, second.gameCount)
        assertEquals(listOf(e, a, c, b), second.players)
        assertEquals(
            listOf(Standing(e, 880, 1), Standing(a, -110, 3), Standing(c, -850, 4), Standing(b, 80, 2)),
            second.finance.standings,
        )
        // Die freie Buchung hinter dem letzten Spiel steckt im letzten Punkt: Der Verlauf endet beim Kontostand.
        val expected = mapOf(
            e to listOf(0, 40, 160, 150, 570, 810, 880),
            a to listOf(0, 40, 160, 170, 30, -210, -110),
            c to listOf(0, -40, -160, -170, -310, -550, -850),
            b to listOf(0, -40, -160, -150, -290, -50, 80),
        )
        assertEquals(expected, second.finance.balanceSeries)
        assertEquals(listOf(0), second.finance.eveningStarts)
        assertEquals(listOf(RoundEvenings.secondId), second.finance.eveningIds)
        // 80 + 240 + 20 + 420 + 480 + 300 aus den Spielen und 30 aus der freien Buchung.
        assertEquals(1570, second.finance.turnover)
    }

    @Test
    fun `zweiter Abend - Spielart-Analyse`() {
        assertEquals(
            modes(
                rufspiel = ModeRecord(3, 3, 400),
                ueberKreuz = ModeRecord(1, 0, -10),
                farbsolo = ModeRecord(1, 1, 100),
                geier = ModeRecord(1, 1, 420),
            ),
            second.atTable(e),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(3, 2, -80),
                ueberKreuz = ModeRecord(1, 1, 10),
                farbsolo = ModeRecord(1, 1, 100),
                geier = ModeRecord(1, 0, -140),
            ),
            second.atTable(a),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(3, 0, -400),
                ueberKreuz = ModeRecord(1, 0, -10),
                farbsolo = ModeRecord(1, 0, -300),
                geier = ModeRecord(1, 0, -140),
            ),
            second.atTable(c),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(3, 1, 80),
                ueberKreuz = ModeRecord(1, 1, 10),
                farbsolo = ModeRecord(1, 1, 100),
                geier = ModeRecord(1, 0, -140),
            ),
            second.atTable(b),
        )
        // 400 / 3 = 133,33; −80 / 3 = −26,67; −400 / 3 = −133,33; 80 / 3 = 26,67.
        assertEquals(
            listOf(133, -27, -133, 27),
            second.players.map { second.atTable(it).getValue(GameType.RUFSPIEL).averageAmount },
        )

        // Selbst gespielt: E ist Mitspieler in 1 und 5 und spielt den Geier 4; A sagt 1 an; C sagt 2 an und spielt
        // das Farbsolo 6; B ist Mitspieler in 2 und sagt 5 an.
        assertEquals(
            modes(rufspiel = ModeRecord(2, 2, 280), ueberKreuz = ModeRecord(1, 0, -10), geier = ModeRecord(1, 1, 420)),
            second.asPlayer(e),
        )
        assertEquals(modes(rufspiel = ModeRecord(1, 1, 40), ueberKreuz = ModeRecord(1, 1, 10)), second.asPlayer(a))
        assertEquals(
            modes(rufspiel = ModeRecord(1, 0, -120), ueberKreuz = ModeRecord(1, 0, -10), farbsolo = ModeRecord(1, 0, -300)),
            second.asPlayer(c),
        )
        assertEquals(modes(rufspiel = ModeRecord(2, 1, 120), ueberKreuz = ModeRecord(1, 1, 10)), second.asPlayer(b))
    }

    @Test
    fun `zweiter Abend - Sollte-er-es-lassen-Index`() {
        assertEquals(
            listOf(
                GelegtRecord(e, count = 0, won = 0, netBalance = 0, effect = 0),
                // A legt in 1 (+40), 4 (−140) und 5 (−240); ohne den eigenen Leger +20, −70 und −120.
                GelegtRecord(a, count = 3, won = 1, netBalance = -340, effect = -170),
                GelegtRecord(c, count = 0, won = 0, netBalance = 0, effect = 0),
                // B legt in 1 (−40), 2 (−120) und 5 (+240); ohne den eigenen Leger −20, −60 und +120.
                GelegtRecord(b, count = 3, won = 1, netBalance = 80, effect = 40),
            ),
            second.gelegt,
        )
        assertEquals(
            listOf(
                GelegtBadge.STREICHHOLZ_SPARER,
                GelegtBadge.BRANDSTIFTER,
                GelegtBadge.STREICHHOLZ_SPARER,
                // Nur eines von drei gewonnen, aber das eine hat sich gelohnt.
                GelegtBadge.GLUECKSRITTER,
            ),
            second.gelegt.map { it.badge },
        )
        assertEquals(listOf(false, true, false, false), second.gelegt.map { it.shouldLeaveIt })
    }

    @Test
    fun `zweiter Abend - bei Gleichstand entscheidet die Sitzreihenfolge`() {
        // Bockspiele sind 5 und 6. E und B stehen beide bei +340: Bock-König ist E, der weiter vorn sitzt.
        assertEquals(
            listOf(
                BockRecord(e, games = 2, won = 2, balance = 340),
                BockRecord(a, games = 2, won = 1, balance = -140),
                BockRecord(c, games = 2, won = 0, balance = -540),
                BockRecord(b, games = 2, won = 2, balance = 340),
            ),
            second.bock.records,
        )
        assertEquals(e, second.bock.king?.player)

        // E und C haben je ein Einzelspiel: Solo-Spezialist ist E. Niemand hat drei Spiele angesagt.
        assertEquals(
            listOf(SoloRecord(e, 1, 0), SoloRecord(a, 0, 1), SoloRecord(c, 1, 1), SoloRecord(b, 0, 1)),
            second.solo.records,
        )
        assertEquals(e, second.solo.specialist?.player)
        assertNull(second.solo.wallflower)
    }

    @Test
    fun `zweiter Abend - Zweiergespanne ohne Lieblingspartner`() {
        // Zwei gegen zwei: 1 (A+E schlagen B+C, 40), 2 (A+E schlagen B+C, 120), 3 (A+B schlagen C+E, 10),
        // 5 (B+E schlagen A+C, 240).
        assertEquals(
            listOf(
                PartnerPair(b, e, games = 1, won = 1, balance = 480),
                PartnerPair(a, e, games = 2, won = 2, balance = 320),
                PartnerPair(a, b, games = 1, won = 1, balance = 20),
                PartnerPair(c, e, games = 1, won = 0, balance = -20),
                PartnerPair(b, c, games = 2, won = 0, balance = -320),
                PartnerPair(a, c, games = 1, won = 0, balance = -480),
            ),
            second.partners.pairs,
        )
        // Kein Gespann hat drei gemeinsame Spiele.
        assertNull(second.partners.favorite)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Beide Abende zusammen: die ewige Tabelle
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `ewige Tabelle - Spieler stehen nach Saldo absteigend`() {
        assertEquals(2, all.eveningCount)
        assertEquals(20, all.gameCount)
        // E 880 (nur zweiter Abend), C 1160 − 850, A 310 − 110, B 30 + 80, D −1500 (nur erster Abend).
        assertEquals(listOf(e, c, a, b, d), all.players)
        assertEquals(
            listOf(Standing(e, 880, 1), Standing(c, 310, 2), Standing(a, 200, 3), Standing(b, 110, 4), Standing(d, -1500, 5)),
            all.finance.standings,
        )
        assertEquals(0, all.finance.standings.sumOf { it.balance })
        assertEquals(3250 + 1570, all.finance.turnover)
        assertEquals(all.players, all.finance.balanceSeries.keys.toList())
        assertEquals(all.players, all.gameModes.map { it.player })
        assertEquals(all.players, all.gelegt.map { it.player })
        assertEquals(all.players, all.bock.records.map { it.player })
        assertEquals(all.players, all.solo.records.map { it.player })
    }

    @Test
    fun `ewige Tabelle - Vermoegensverlauf ueber beide Abende`() {
        val expected = mapOf(
            // E sitzt am ersten Abend nicht am Tisch und bleibt dort waagerecht bei 0.
            e to List(15) { 0 } + listOf(40, 160, 150, 570, 810, 880),
            c to listOf(0, -10, 10, -70, -90, 190, 130, 850, 1050, 1090, 1150, 1170, 1070, 1090, 1160) +
                listOf(1120, 1000, 990, 850, 610, 310),
            a to listOf(0, 10, 30, -50, -30, 250, 310, 70, 320, 280, 340, 360, 260, 240, 310) +
                listOf(350, 470, 480, 340, 100, 200),
            b to listOf(0, 10, -10, 230, 250, 530, 470, 230, -20, 20, -160, -180, 120, 100, 30) +
                listOf(-10, -130, -120, -260, -20, 110),
            // D sitzt am zweiten Abend nicht am Tisch und bleibt dort waagerecht.
            d to listOf(0, -10, -30, -110, -130, -970, -910, -1150, -1350, -1390, -1330, -1350, -1450, -1430, -1500) +
                List(6) { -1500 },
        )
        assertEquals(expected, all.finance.balanceSeries)
        // Der zweite Abend beginnt beim Punkt 14, dem Stand nach dem letzten Spiel des ersten Abends.
        assertEquals(listOf(0, 14), all.finance.eveningStarts)
        assertEquals(listOf(RoundEvenings.id, RoundEvenings.secondId), all.finance.eveningIds)
        for ((player, series) in all.finance.balanceSeries) {
            assertEquals(all.gameCount + 1, series.size)
            assertEquals(all.finance.standingOf(player)?.balance, series.last())
        }
    }

    @Test
    fun `ewige Tabelle - Spielart-Analyse ist die Summe beider Abende`() {
        assertEquals(
            modes(
                rufspiel = ModeRecord(10, 7, 40),
                ueberKreuz = ModeRecord(3, 3, 230),
                farbsolo = ModeRecord(3, 2, 80),
                wenz = ModeRecord(2, 1, 180),
                geier = ModeRecord(2, 0, -380),
            ),
            all.atTable(a),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(10, 4, -20),
                ueberKreuz = ModeRecord(3, 1, -210),
                farbsolo = ModeRecord(3, 2, 160),
                wenz = ModeRecord(2, 2, 580),
                geier = ModeRecord(2, 0, -380),
            ),
            all.atTable(b),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(10, 4, -340),
                ueberKreuz = ModeRecord(3, 2, 210),
                farbsolo = ModeRecord(3, 1, -320),
                wenz = ModeRecord(2, 1, 180),
                geier = ModeRecord(2, 1, 580),
            ),
            all.atTable(c),
        )
        // D und E waren nur an einem Abend dabei.
        assertEquals(first.atTable(d), all.atTable(d))
        assertEquals(second.atTable(e), all.atTable(e))

        assertEquals(modes(rufspiel = ModeRecord(5, 4, 160), ueberKreuz = ModeRecord(3, 3, 230)), all.asPlayer(a))
        assertEquals(
            modes(
                rufspiel = ModeRecord(6, 3, 130),
                ueberKreuz = ModeRecord(3, 1, -210),
                farbsolo = ModeRecord(2, 1, 60),
                wenz = ModeRecord(1, 1, 300),
            ),
            all.asPlayer(b),
        )
        assertEquals(
            modes(
                rufspiel = ModeRecord(4, 2, -30),
                ueberKreuz = ModeRecord(3, 2, 210),
                farbsolo = ModeRecord(1, 0, -300),
                geier = ModeRecord(1, 1, 720),
            ),
            all.asPlayer(c),
        )
        assertEquals(first.asPlayer(d), all.asPlayer(d))
        assertEquals(second.asPlayer(e), all.asPlayer(e))

        // Kaufmännisch gerundet: 40 / 10 = 4; 230 / 3 = 76,67; −320 / 3 = −106,67; −30 / 4 = −7,5 wird −8.
        assertEquals(4, all.atTable(a).getValue(GameType.RUFSPIEL).averageAmount)
        assertEquals(77, all.atTable(a).getValue(GameType.UEBER_KREUZ).averageAmount)
        assertEquals(-107, all.atTable(c).getValue(GameType.FARBSOLO).averageAmount)
        assertEquals(-8, all.asPlayer(c).getValue(GameType.RUFSPIEL).averageAmount)
        assertEquals(0.7, all.atTable(a).getValue(GameType.RUFSPIEL).winRate)
    }

    @Test
    fun `ewige Tabelle - Sollte-er-es-lassen-Index mit allen Abzeichen`() {
        assertEquals(
            listOf(
                GelegtRecord(e, count = 0, won = 0, netBalance = 0, effect = 0),
                GelegtRecord(c, count = 3, won = 2, netBalance = 740, effect = 370),
                // Erster Abend 1 von 1 (+280, Wirkung +140), zweiter Abend 1 von 3 (−340, Wirkung −170).
                GelegtRecord(a, count = 4, won = 2, netBalance = -60, effect = -30),
                GelegtRecord(b, count = 3, won = 1, netBalance = 80, effect = 40),
                GelegtRecord(d, count = 4, won = 1, netBalance = -1040, effect = -520),
            ),
            all.gelegt,
        )
        assertEquals(
            listOf(
                GelegtBadge.STREICHHOLZ_SPARER,
                GelegtBadge.SICHERE_BANK,
                // Die Hälfte gewonnen und trotzdem im Minus.
                GelegtBadge.WACKELKANDIDAT,
                GelegtBadge.GLUECKSRITTER,
                GelegtBadge.BRANDSTIFTER,
            ),
            all.gelegt.map { it.badge },
        )
        assertEquals(listOf(false, false, false, false, true), all.gelegt.map { it.shouldLeaveIt })
        // Zusammen mit dem ersten Abend (A dort: „noch offen“) kommt jedes der sechs Abzeichen vor.
        assertEquals(GelegtBadge.entries.toSet(), (first.gelegt + all.gelegt).map { it.badge }.toSet())
    }

    @Test
    fun `ewige Tabelle - Bock-Koenig, Solo-Spezialist und Mauerbluemchen`() {
        assertEquals(
            listOf(
                BockRecord(e, games = 2, won = 2, balance = 340),
                // 900 − 540
                BockRecord(c, games = 6, won = 3, balance = 360),
                // −20 − 140
                BockRecord(a, games = 6, won = 3, balance = -160),
                // −460 + 340
                BockRecord(b, games = 6, won = 3, balance = -120),
                BockRecord(d, games = 4, won = 1, balance = -420),
            ),
            all.bock.records,
        )
        assertEquals(c, all.bock.king?.player)

        assertEquals(
            listOf(SoloRecord(e, 1, 0), SoloRecord(c, 2, 3), SoloRecord(a, 0, 4), SoloRecord(b, 3, 2), SoloRecord(d, 1, 1)),
            all.solo.records,
        )
        assertEquals(listOf(1.0, 0.4, 0.0, 0.6, 0.5), all.solo.records.map { it.soloShare })
        assertEquals(SoloRecord(b, 3, 2), all.solo.specialist)
        assertEquals(SoloRecord(a, 0, 4), all.solo.wallflower)
    }

    @Test
    fun `ewige Tabelle - Lieblingspartner ist das beste Gespann mit mindestens drei gemeinsamen Spielen`() {
        assertEquals(
            listOf(
                PartnerPair(b, e, games = 1, won = 1, balance = 480),
                PartnerPair(a, e, games = 2, won = 2, balance = 320),
                // 620 − 480
                PartnerPair(a, c, games = 5, won = 4, balance = 140),
                // Gleicher Saldo: Die höhere Gewinnquote steht vorn (3 von 4 vor 1 von 2).
                PartnerPair(a, b, games = 4, won = 3, balance = 40),
                PartnerPair(a, d, games = 2, won = 1, balance = 40),
                // Gleicher Saldo: 1 von 3 vor 0 von 1.
                PartnerPair(c, d, games = 3, won = 1, balance = -20),
                PartnerPair(c, e, games = 1, won = 0, balance = -20),
                // −40 − 320
                PartnerPair(b, c, games = 4, won = 1, balance = -360),
                PartnerPair(b, d, games = 4, won = 0, balance = -620),
            ),
            all.partners.pairs,
        )
        // B+E und A+E stehen weiter vorn, haben aber zu wenige gemeinsame Spiele.
        assertEquals(PartnerPair(a, c, 5, 4, 140), all.partners.favorite)
        assertEquals(0.8, all.partners.favorite?.winRate)
        assertTrue(checkNotNull(all.partners.pairOf(e, b)).isPair(b, e))
        assertFalse(checkNotNull(all.partners.pairOf(e, b)).isPair(a, e))
        // D und E saßen nie zusammen am Tisch.
        assertNull(all.partners.pairOf(d, e))
    }

    @Test
    fun `ewige Tabelle - die uebrigen Kennzahlen kommen aus der bisherigen Statistik`() {
        val history = HistoryStatistics.compute(listOf(RoundEvenings.evening, RoundEvenings.secondEvening))
        assertEquals(history, all.history)
        assertEquals(all.players, all.history.playerStats.map { it.player })
        assertEquals(all.finance.standings.map { it.balance }, all.history.playerStats.map { it.balance })
        assertEquals(all.eveningCount, all.history.eveningCount)
        assertEquals(all.gameCount, all.history.gameCount)
        assertEquals(all.finance.turnover, all.history.turnover)
        for (record in all.gelegt) {
            val stats = checkNotNull(all.history.statsFor(record.player))
            assertEquals(RoleRecord(record.count, record.won), stats.klopferGiven)
            assertEquals(record.effect, stats.klopferEffect)
        }
    }

    @Test
    fun `ewige Tabelle - Reihenfolge und Dubletten der uebergebenen Abende spielen keine Rolle`() {
        val evenings = listOf(RoundEvenings.evening, RoundEvenings.secondEvening)
        assertEquals(all, StatisticEngine.forEvenings(evenings))
        assertEquals(all, StatisticEngine.forEvenings(evenings + evenings.reversed()))
    }
}
