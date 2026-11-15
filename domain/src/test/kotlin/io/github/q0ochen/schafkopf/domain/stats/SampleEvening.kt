package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Ein von Hand durchgerechneter Beispielabend für die Tests von Statistik und Zusammenfassung.
 *
 * Am Tisch sitzen Anna (A), Bert (B), Chris (C) und Dora (D); Regeln von sauspiel.de (Tarif 10-20-50) mit
 * Bockrunde über vier Spiele. Beträge in Cent, Reihenfolge A, B, C, D.
 *
 * ```
 * Nr.  Eintrag                                                        Wert      A     B     C     D
 *  1   Rufspiel A mit B, gewonnen                                       20     +20   +20   -20   -20
 *  2   Rufspiel C mit D, verloren, Schneider, Klopfer C       (20+10)×2 = 60   +60   +60   -60   -60
 *  3   Herz-Solo A, gewonnen, 3 Laufende                        50+30 = 80    +240   -80   -80   -80
 *      zusammengeworfen
 *  4   Wenz B, verloren, Klopfer B                              50×2 = 100    +100  -300  +100  +100
 *      Bockrunde (6 Nixer bei C): die Spiele 5 bis 8 zählen doppelt
 *  5   Geier D, gewonnen, Schneider, 2 Laufende           (50+20+10)×2 = 160  -160  -160  -160  +480
 *  6   Rufspiel B mit C, gewonnen, Klopfer A, Kontra von D    20×2×2×2 = 160  -160  +160  +160  -160
 *      freie Buchung „Wette um die Brotzeit“                                  +100     0     0  -100
 *  7   Gras-Solo Tout A, gewonnen, 3 Laufende (Schwarz
 *      entfällt beim Tout)                                  (50+30)×2×2 = 320 +960  -320  -320  -320
 *  8   Rufspiel D mit A, verloren, schwarz, Kontra von B,
 *      Retour von D                                         (20+20)×4×2 = 320 -320  +320  +320  -320
 *  9   Wenz C, gewonnen, Schneider, 2 Laufende                50+20+10 = 80    -80   -80  +240   -80
 *      Bockrunde (6 Nixer bei D): die Spiele 10 bis 13 zählen doppelt
 * 10   Rufspiel C mit A, gewonnen, Klopfer C und D             20×4×2 = 160   +160  -160  +160  -160
 * 11   Geier B, verloren, Klopfer B                            50×2×2 = 200   +200  -600  +200  +200
 *      zusammengeworfen
 * 12   Eichel-Solo C, gewonnen, Kontra von A                   50×2×2 = 200   -200  -200  +600  -200
 *                                                                     Endstand +920 -1340 +1140  -720
 * ```
 *
 * Stand nach jedem Spiel (die freie Buchung steckt im Stand nach Spiel 7):
 *
 * ```
 * Spiel    1    2    3     4     5     6     7     8     9    10     11     12
 * A       20   80  320   420   260   100  1160   840   760   920   1120    920
 * B       20   80    0  -300  -460  -300  -620  -300  -380  -540  -1140  -1340
 * C      -20  -80 -160   -60  -220   -60  -380   -60   180   340    540   1140
 * D      -20  -80 -160   -60   420   260  -160  -480  -560  -720   -520   -720
 * ```
 */
internal object SampleEvening {
    val a = PlayerId("A")
    val b = PlayerId("B")
    val c = PlayerId("C")
    val d = PlayerId("D")

    /** Emil sitzt nur am zweiten Abend am Tisch. */
    val e = PlayerId("E")

    val names: Map<PlayerId, String> = mapOf(a to "Anna", b to "Bert", c to "Chris", d to "Dora", e to "Emil")
    val zone: ZoneId = ZoneId.of("Europe/Berlin")

    val id = EveningId("abend-1")
    private val start: Instant = Instant.parse("2026-11-14T18:30:00Z")

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    val g1 = GameEntry(
        EntryId("e01"), at(5), GameType.RUFSPIEL, suit = Suit.EICHEL, declarer = a, partner = b, declarerWon = true,
    )
    val g2 = GameEntry(
        EntryId("e02"), at(12), GameType.RUFSPIEL, declarer = c, partner = d, declarerWon = false,
        level = ResultLevel.SCHNEIDER, klopfer = setOf(c),
    )
    val g3 = GameEntry(
        EntryId("e03"), at(20), GameType.FARBSOLO, suit = Suit.HERZ, declarer = a, declarerWon = true, laufende = 3,
    )
    val thrownIn1 = ThrownInEntry(EntryId("e04"), at(24))
    val g4 = GameEntry(
        EntryId("e05"), at(30), GameType.WENZ, declarer = b, declarerWon = false, klopfer = setOf(b),
    )
    val bock1 = BockTriggerEntry(EntryId("e06"), at(33), BockReason.SECHS_NIXER, player = c)
    val g5 = GameEntry(
        EntryId("e07"), at(40), GameType.GEIER, declarer = d, declarerWon = true,
        level = ResultLevel.SCHNEIDER, laufende = 2,
    )
    val g6 = GameEntry(
        EntryId("e08"), at(47), GameType.RUFSPIEL, declarer = b, partner = c, declarerWon = true,
        klopfer = setOf(a), kontra = KontraLevel.KONTRA, kontraBy = d,
    )
    val bet = ManualEntry(EntryId("e09"), at(50), mapOf(a to 100, d to -100), "Wette um die Brotzeit")
    val g7 = GameEntry(
        EntryId("e10"), at(58), GameType.FARBSOLO, suit = Suit.GRAS, declarer = a, declarerWon = true,
        level = ResultLevel.SCHWARZ, laufende = 3, announcement = Announcement.TOUT,
    )
    val g8 = GameEntry(
        EntryId("e11"), at(66), GameType.RUFSPIEL, declarer = d, partner = a, declarerWon = false,
        level = ResultLevel.SCHWARZ, kontra = KontraLevel.RETOUR, kontraBy = b, retourBy = d,
    )
    val g9 = GameEntry(
        EntryId("e12"), at(73), GameType.WENZ, declarer = c, declarerWon = true,
        level = ResultLevel.SCHNEIDER, laufende = 2,
    )
    val bock2 = BockTriggerEntry(EntryId("e13"), at(76), BockReason.SECHS_NIXER, player = d)
    val g10 = GameEntry(
        EntryId("e14"), at(83), GameType.RUFSPIEL, declarer = c, partner = a, declarerWon = true,
        klopfer = setOf(c, d),
    )
    val g11 = GameEntry(
        EntryId("e15"), at(90), GameType.GEIER, declarer = b, declarerWon = false, klopfer = setOf(b),
    )
    val thrownIn2 = ThrownInEntry(EntryId("e16"), at(93))
    val g12 = GameEntry(
        EntryId("e17"), at(100), GameType.FARBSOLO, suit = Suit.EICHEL, declarer = c, declarerWon = true,
        kontra = KontraLevel.KONTRA, kontraBy = a,
    )

    /** Die zwölf Spiele in der Reihenfolge ihrer Spielnummern. */
    val games: List<GameEntry> = listOf(g1, g2, g3, g4, g5, g6, g7, g8, g9, g10, g11, g12)

    val evening = Evening(
        id = id,
        startedAt = start,
        finishedAt = at(110),
        location = "Beim Wirt",
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.sauspiel(),
        entries = listOf(
            g1, g2, g3, thrownIn1, g4, bock1, g5, g6, bet, g7, g8, g9, bock2, g10, g11, thrownIn2, g12,
        ),
    )

    val state: EveningState = EveningCalculator.compute(evening)

    val secondId = EveningId("abend-2")
    private val secondStart: Instant = Instant.parse("2026-11-21T18:30:00Z")

    val h1 = GameEntry(
        EntryId("h01"), secondStart.plusSeconds(300), GameType.RUFSPIEL, declarer = a, partner = e, declarerWon = true,
    )
    val h2 = GameEntry(
        EntryId("h02"), secondStart.plusSeconds(600), GameType.RUFSPIEL, declarer = b, partner = c, declarerWon = false,
        level = ResultLevel.SCHNEIDER,
    )
    val h3 = GameEntry(
        EntryId("h03"), secondStart.plusSeconds(900), GameType.WENZ, declarer = e, declarerWon = true,
    )
    val h4 = GameEntry(
        EntryId("h04"), secondStart.plusSeconds(1200), GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true,
        klopfer = setOf(e),
    )

    /**
     * Ein zweiter, kurzer Abend eine Woche später: Emil sitzt statt Dora am Tisch, in anderer Sitzordnung.
     *
     * ```
     * Nr.  Eintrag                                       Wert     A     B     C     E
     *  1   Rufspiel A mit E, gewonnen                      20    +20   -20   -20   +20
     *  2   Rufspiel B mit C, verloren, Schneider        20+10    +30   -30   -30   +30
     *  3   Wenz E, gewonnen                                50    -50   -50   -50  +150
     *  4   Rufspiel A mit B, gewonnen, Klopfer E         20×2    +40   +40   -40   -40
     *                                                Endstand    +40   -60  -140  +160
     * ```
     */
    val secondEvening = Evening(
        id = secondId,
        startedAt = secondStart,
        seating = listOf(e, a, c, b),
        firstDealer = e,
        rules = RuleSet.sauspiel(),
        entries = listOf(h1, h2, h3, h4),
    )
}

/**
 * Zufällige, aber gültige Abende für Tests, die Zusammenhänge zwischen Kennzahlen prüfen: Jede Zahl wird dort
 * auf einem zweiten, unabhängigen Weg nachgerechnet. Derselbe Startwert ergibt immer dieselben Abende.
 */
internal object RandomEvenings {

    /** Ein Abend mit 0 bis 39 Einträgen: Spiele aller Art, zusammengeworfene Blätter, Bockrunden, freie Buchungen. */
    fun evening(
        random: Random,
        id: String,
        seating: List<PlayerId>,
        startedAt: Instant = Instant.parse("2026-01-01T18:00:00Z"),
    ): Evening {
        var entryNumber = 0
        fun entryId() = EntryId("$id-${entryNumber++}")

        val entries: List<EveningEntry> = List(random.nextInt(0, 40)) {
            when (random.nextInt(12)) {
                0 -> ThrownInEntry(entryId(), startedAt)
                1 -> BockTriggerEntry(
                    entryId(), startedAt,
                    // Jede dritte Bockrunde ist von Hand ausgelöst (nach der Nummer des Eintrags, nicht nach Zufall).
                    reason = if (entryNumber % 3 == 0) BockReason.MANUAL else BockReason.SECHS_NIXER,
                    player = seating.random(random).takeIf { random.nextBoolean() },
                    handThrownIn = random.nextBoolean(),
                )

                2 -> {
                    val (winner, loser) = seating.shuffled(random)
                    val amount = random.nextInt(1, 50) * 10
                    ManualEntry(entryId(), startedAt, mapOf(winner to amount, loser to -amount), "Wette")
                }

                else -> game(random, entryId(), startedAt, seating)
            }
        }
        return Evening(
            id = EveningId(id),
            startedAt = startedAt,
            seating = seating,
            firstDealer = seating.first(),
            rules = RuleSet.sauspiel(),
            entries = entries,
        )
    }

    private fun game(random: Random, id: EntryId, recordedAt: Instant, seating: List<PlayerId>): GameEntry {
        val type = GameType.entries.random(random)
        val declarer = seating.random(random)
        val partner = if (type.isSolo) null else (seating - declarer).random(random)
        val announcement = when {
            !type.isSolo -> Announcement.NONE
            random.nextInt(10) == 0 -> Announcement.TOUT
            type == GameType.FARBSOLO && random.nextInt(20) == 0 -> Announcement.SIE
            else -> Announcement.NONE
        }
        val declarerSide = setOfNotNull(declarer, partner)
        val opponents = seating - declarerSide
        val kontra = KontraLevel.entries.random(random)
        return GameEntry(
            id = id,
            recordedAt = recordedAt,
            type = type,
            declarer = declarer,
            partner = partner,
            // Ein Sie kann man nicht verlieren.
            declarerWon = announcement == Announcement.SIE || random.nextBoolean(),
            level = ResultLevel.entries.random(random),
            laufende = random.nextInt(0, 5),
            announcement = announcement,
            klopfer = seating.filter { random.nextInt(4) == 0 }.toSet(),
            kontra = kontra,
            // Kontra kommt von den Gegenspielern, Retour von der Spielerseite; manchmal fehlt die Angabe.
            kontraBy = opponents.random(random).takeIf { kontra != KontraLevel.NONE && random.nextBoolean() },
            retourBy = declarerSide.random(random).takeIf { kontra == KontraLevel.RETOUR && random.nextBoolean() },
            bockOverride = BockOverride.entries.random(random),
        )
    }
}

/** Das geschützte Leerzeichen (U+00A0), das `Money.format` vor das Euro-Zeichen setzt. */
private val NO_BREAK_SPACE = Char(0x00A0).toString()

/** Das echte Minuszeichen (U+2212), das `Money.format` statt des Bindestrichs schreibt. */
private val MINUS_SIGN = Char(0x2212).toString()

/**
 * Erwartete Texte lassen sich mit einfachem Leerzeichen und Bindestrich leichter lesen. `Money.format` setzt vor
 * das Euro-Zeichen aber ein geschütztes Leerzeichen und schreibt ein echtes Minuszeichen. Diese Funktion bringt
 * eine Erwartung in dieselbe Schreibweise: „-7,20 €“ wird zu „−7,20 €“ mit geschütztem Leerzeichen.
 * Bindestriche in Wörtern wie „2-mal“ oder „Gras-Solo“ bleiben, wie sie sind.
 */
internal fun typo(text: String): String =
    text.replace(" €", "${NO_BREAK_SPACE}€").replace(Regex("""(?<![\w.])-(?=\d)"""), MINUS_SIGN)
