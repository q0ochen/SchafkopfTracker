package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.Instant

/**
 * Zwei von Hand durchgerechnete Abende nach dem Regelwerk der Runde ([RuleSet.standard]) für die Tests der
 * [StatisticEngine]: Rufspiel und Über Kreuz 10 Cent, Farbsolo, Wenz und Geier 50 Cent, je Laufendem 10 Cent
 * (gezählt ab 3, bei Wenz und Geier ab 2), Schneider 10 Cent, Schwarz weitere 10 Cent; ein Bockspiel und jeder, der
 * gelegt hat, verdoppeln. Zwei gegen zwei zahlt jeder Verlierer T an einen Gewinner; im Einzelspiel bekommt oder
 * zahlt der Alleinspieler 3 T.
 *
 * ## Erster Abend
 *
 * Am Tisch sitzen im Uhrzeigersinn A, B, C und D; A und C sowie B und D sitzen sich gegenüber. Beträge in Cent.
 *
 * ```
 * Nr.  Eintrag                                                              Wert T      A     B     C     D
 *  1   Rufspiel A mit B, gewonnen                                               10     +10   +10   -10   -10
 *  2   Über Kreuz A und C gegen B und D, A und C gewinnen, Schneider     10+10 = 20    +20   -20   +20   -20
 *  3   Herz-Solo B, gewonnen, 3 Laufende                                 50+30 = 80    -80  +240   -80   -80
 *  4   Rufspiel C mit D, verloren, gelegt hat C                           10×2 = 20    +20   +20   -20   -20
 *      zusammengeworfen
 *  5   Wenz D, verloren, 2 Laufende, gelegt haben A und D           (50+20)×4 = 280   +280  +280  +280  -840
 *      Bockrunde (6 Nixer bei B): die Spiele 6 bis 9 zählen doppelt
 *  6   Rufspiel A mit D, gewonnen, schwarz                           (10+20)×2 = 60    +60   -60   -60   +60
 *  7   Geier C, gewonnen, Schneider, gelegt hat C                 (50+10)×2×2 = 240   -240  -240  +720  -240
 *      freie Buchung „Wette“                                                           +50   -50     0     0
 *  8   Über Kreuz B und D gegen A und C, A und C gewinnen,
 *      4 Laufende, gelegt hat D                                   (10+40)×2×2 = 200   +200  -200  +200  -200
 *  9   Rufspiel B mit C, gewonnen, gelegt hat C                         10×2×2 = 40    -40   +40   +40   -40
 * 10   Eichel-Solo B, verloren, Schneider                                50+10 = 60    +60  -180   +60   +60
 * 11   Rufspiel D mit B, verloren, gelegt hat D                           10×2 = 20    +20   -20   +20   -20
 * 12   Wenz B, gewonnen, schwarz, 3 Laufende                         50+30+20 = 100   -100  +300  -100  -100
 * 13   Rufspiel A mit B, verloren, gelegt hat D; 2 Laufende zählen
 *      beim Rufspiel nicht                                                10×2 = 20    -20   -20   +20   +20
 * 14   Rufspiel C mit A, gewonnen, Schneider, 5 Laufende              10+50+10 = 70    +70   -70   +70   -70
 *                                                                           Endstand  +310   +30 +1160 -1500
 * ```
 *
 * Stand nach jedem Spiel (die freie Buchung steckt im Stand nach Spiel 8):
 *
 * ```
 * Spiel    1    2     3     4     5     6      7      8      9     10     11     12     13     14
 * A       10   30   -50   -30   250   310     70    320    280    340    360    260    240    310
 * B       10  -10   230   250   530   470    230    -20     20   -160   -180    120    100     30
 * C      -10   10   -70   -90   190   130    850   1050   1090   1150   1170   1070   1090   1160
 * D      -10  -30  -110  -130  -970  -910  -1150  -1350  -1390  -1330  -1350  -1450  -1430  -1500
 * ```
 *
 * ## Zweiter Abend
 *
 * Eine Woche später sitzt E statt D am Tisch, im Uhrzeigersinn E, A, C und B; E und C sowie A und B sitzen sich
 * gegenüber.
 *
 * ```
 * Nr.  Eintrag                                                              Wert T      E     A     C     B
 *  1   Rufspiel A mit E, gewonnen, gelegt haben A und B                   10×4 = 40    +40   +40   -40   -40
 *  2   Rufspiel C mit B, verloren, schwarz, 3 Laufende,
 *      gelegt hat B                                              (10+30+20)×2 = 120   +120  +120  -120  -120
 *  3   Über Kreuz A und B gegen E und C, A und B gewinnen                       10     -10   +10   -10   +10
 *  4   Geier E, gewonnen, 2 Laufende, gelegt hat A                  (50+20)×2 = 140   +420  -140  -140  -140
 *      Bockrunde (von Hand ausgelöst): die Spiele 5 und 6 zählen doppelt
 *  5   Rufspiel B mit E, gewonnen, schwarz, gelegt haben A und B  (10+20)×2×4 = 240   +240  -240  -240  +240
 *  6   Gras-Solo C, verloren                                             50×2 = 100   +100  +100  -300  +100
 *      freie Buchung „Getränke“                                                        -30     0     0   +30
 *                                                                           Endstand  +880  -110  -850   +80
 * ```
 *
 * Stand nach jedem Spiel (die freie Buchung hinter dem letzten Spiel steckt im Stand nach Spiel 6):
 *
 * ```
 * Spiel    1     2     3     4     5     6
 * E       40   160   150   570   810   880
 * A       40   160   170    30  -210  -110
 * C      -40  -160  -170  -310  -550  -850
 * B      -40  -160  -150  -290   -50    80
 * ```
 *
 * Über beide Abende: E +880, C +310, A +200, B +110, D -1500.
 */
internal object RoundEvenings {
    val a = PlayerId("A")
    val b = PlayerId("B")
    val c = PlayerId("C")
    val d = PlayerId("D")

    /** E sitzt nur am zweiten Abend am Tisch. */
    val e = PlayerId("E")

    val id = EveningId("runde-1")
    private val start: Instant = Instant.parse("2026-11-07T18:30:00Z")

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    val g1 = GameEntry(EntryId("r01"), at(5), GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true)
    val g2 = GameEntry(
        EntryId("r02"), at(12), GameType.UEBER_KREUZ, declarer = a, partner = c, declarerWon = true,
        level = ResultLevel.SCHNEIDER,
    )
    val g3 = GameEntry(
        EntryId("r03"), at(20), GameType.FARBSOLO, suit = Suit.HERZ, declarer = b, declarerWon = true, laufende = 3,
    )
    val g4 = GameEntry(
        EntryId("r04"), at(27), GameType.RUFSPIEL, declarer = c, partner = d, declarerWon = false, klopfer = setOf(c),
    )
    val thrownIn = ThrownInEntry(EntryId("r05"), at(30))
    val g5 = GameEntry(
        EntryId("r06"), at(38), GameType.WENZ, declarer = d, declarerWon = false, laufende = 2, klopfer = setOf(a, d),
    )
    val bock = BockTriggerEntry(EntryId("r07"), at(41), BockReason.SECHS_NIXER, player = b)
    val g6 = GameEntry(
        EntryId("r08"), at(48), GameType.RUFSPIEL, declarer = a, partner = d, declarerWon = true,
        level = ResultLevel.SCHWARZ,
    )
    val g7 = GameEntry(
        EntryId("r09"), at(56), GameType.GEIER, declarer = c, declarerWon = true, level = ResultLevel.SCHNEIDER,
        klopfer = setOf(c),
    )
    val bet = ManualEntry(EntryId("r10"), at(58), mapOf(a to 50, b to -50), "Wette")
    val g8 = GameEntry(
        EntryId("r11"), at(65), GameType.UEBER_KREUZ, declarer = b, partner = d, declarerWon = false, laufende = 4,
        klopfer = setOf(d),
    )
    val g9 = GameEntry(
        EntryId("r12"), at(72), GameType.RUFSPIEL, declarer = b, partner = c, declarerWon = true, klopfer = setOf(c),
    )
    val g10 = GameEntry(
        EntryId("r13"), at(80), GameType.FARBSOLO, suit = Suit.EICHEL, declarer = b, declarerWon = false,
        level = ResultLevel.SCHNEIDER,
    )
    val g11 = GameEntry(
        EntryId("r14"), at(87), GameType.RUFSPIEL, declarer = d, partner = b, declarerWon = false, klopfer = setOf(d),
    )
    val g12 = GameEntry(
        EntryId("r15"), at(95), GameType.WENZ, declarer = b, declarerWon = true, level = ResultLevel.SCHWARZ,
        laufende = 3,
    )
    val g13 = GameEntry(
        EntryId("r16"), at(102), GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = false, laufende = 2,
        klopfer = setOf(d),
    )
    val g14 = GameEntry(
        EntryId("r17"), at(110), GameType.RUFSPIEL, declarer = c, partner = a, declarerWon = true,
        level = ResultLevel.SCHNEIDER, laufende = 5,
    )

    /** Die vierzehn Spiele des ersten Abends in der Reihenfolge ihrer Spielnummern. */
    val games: List<GameEntry> = listOf(g1, g2, g3, g4, g5, g6, g7, g8, g9, g10, g11, g12, g13, g14)

    val evening = Evening(
        id = id,
        startedAt = start,
        finishedAt = at(120),
        location = "Beim Wirt",
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
        entries = listOf(g1, g2, g3, g4, thrownIn, g5, bock, g6, g7, bet, g8, g9, g10, g11, g12, g13, g14),
    )

    val secondId = EveningId("runde-2")
    private val secondStart: Instant = Instant.parse("2026-11-14T18:30:00Z")

    private fun secondAt(minutes: Long): Instant = secondStart.plusSeconds(minutes * 60)

    val h1 = GameEntry(
        EntryId("s01"), secondAt(5), GameType.RUFSPIEL, declarer = a, partner = e, declarerWon = true,
        klopfer = setOf(a, b),
    )
    val h2 = GameEntry(
        EntryId("s02"), secondAt(12), GameType.RUFSPIEL, declarer = c, partner = b, declarerWon = false,
        level = ResultLevel.SCHWARZ, laufende = 3, klopfer = setOf(b),
    )
    val h3 = GameEntry(EntryId("s03"), secondAt(20), GameType.UEBER_KREUZ, declarer = a, partner = b, declarerWon = true)
    val h4 = GameEntry(
        EntryId("s04"), secondAt(28), GameType.GEIER, declarer = e, declarerWon = true, laufende = 2,
        klopfer = setOf(a),
    )
    val secondBock = BockTriggerEntry(EntryId("s05"), secondAt(30), BockReason.MANUAL)
    val h5 = GameEntry(
        EntryId("s06"), secondAt(37), GameType.RUFSPIEL, declarer = b, partner = e, declarerWon = true,
        level = ResultLevel.SCHWARZ, klopfer = setOf(a, b),
    )
    val h6 = GameEntry(
        EntryId("s07"), secondAt(45), GameType.FARBSOLO, suit = Suit.GRAS, declarer = c, declarerWon = false,
    )
    val drinks = ManualEntry(EntryId("s08"), secondAt(50), mapOf(b to 30, e to -30), "Getränke")

    /** Die sechs Spiele des zweiten Abends in der Reihenfolge ihrer Spielnummern. */
    val secondGames: List<GameEntry> = listOf(h1, h2, h3, h4, h5, h6)

    /** Der zweite Abend läuft noch (kein Ende eingetragen); zwei Bockspiele sind noch offen. */
    val secondEvening = Evening(
        id = secondId,
        startedAt = secondStart,
        seating = listOf(e, a, c, b),
        firstDealer = e,
        rules = RuleSet.standard(),
        entries = listOf(h1, h2, h3, h4, secondBock, h5, h6, drinks),
    )
}
