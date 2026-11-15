package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Ergebnisse der unabhängigen Prüfung der [StatisticEngine]. Jeder Test hier hält eine Regel des Vertrags fest, bei
 * der ein probeweise eingebauter Fehler von den übrigen Tests nicht bemerkt wurde oder nur zufällig über die
 * Zufallsabende auffiel, oder einen seltenen Grenzfall, den die Prüfung aufgedeckt hat.
 *
 * Es gilt das Regelwerk der Runde: Ein Rufspiel ist 10 wert (je ±10), ein Farbsolo 50 (Alleinspieler ±150, jeder
 * Gegner ∓50).
 */
class StatisticEngineReviewTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val seating = listOf(a, b, c, d)
    private val firstDay: Instant = Instant.parse("2026-03-01T18:00:00Z")
    private var nextId = 0

    private fun id() = EntryId("p${nextId++}")

    private fun evening(
        name: String,
        vararg entries: EveningEntry,
        seating: List<PlayerId> = this.seating,
        rules: RuleSet = RuleSet.standard(),
    ) = Evening(
        id = EveningId(name),
        startedAt = firstDay,
        seating = seating,
        firstDealer = seating.first(),
        rules = rules,
        entries = entries.toList(),
    )

    private fun rufspiel(declarer: PlayerId, partner: PlayerId) =
        GameEntry(id(), firstDay, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = true)

    private fun solo(declarer: PlayerId) = GameEntry(id(), firstDay, GameType.FARBSOLO, declarer = declarer, declarerWon = true)

    private fun booking(vararg amounts: Pair<PlayerId, Int>) = ManualEntry(id(), firstDay, amounts.toMap(), "Buchung")

    /** [count] gewonnene Rufspiele, die [declarer] ansagt. */
    private fun calls(declarer: PlayerId, partner: PlayerId, count: Int): Array<GameEntry> =
        Array(count) { rufspiel(declarer, partner) }

    @Test
    fun `Mauerbluemchen - hat der Spezialist den kleinsten Solo-Anteil, ist es der Naechste und nicht niemand`() {
        // A und B spielen je ein Farbsolo. A sagt dazu neun Rufspiele an (Solo-Anteil 1 von 10), B fünf (1 von 6).
        // Bei gleich vielen Einzelspielen ist A der Spezialist, weil er weiter vorn sitzt. Das Mauerblümchen wird
        // „ohne den Spezialisten“ gesucht: Es ist B mit 1 von 6, also knapp 17 % und damit höchstens 20 %.
        // Wer stattdessen erst den kleinsten Anteil aller sucht (das ist A) und danach den Spezialisten ausschließt,
        // fände niemanden; in diesem Fall unterscheiden sich die beiden Lesarten.
        val evening = evening("blume", solo(a), *calls(a, c, 9), solo(b), *calls(b, d, 5))

        val single = StatisticEngine.forEvening(evening)
        assertEquals(
            listOf(SoloRecord(a, solos = 1, calls = 9), SoloRecord(b, solos = 1, calls = 5), SoloRecord(c, 0, 0), SoloRecord(d, 0, 0)),
            single.solo.records,
        )
        assertEquals(SoloRecord(a, solos = 1, calls = 9), single.solo.specialist)
        assertEquals(SoloRecord(b, solos = 1, calls = 5), single.solo.wallflower)

        // In der ewigen Tabelle steht A (150 + 90 − 50 − 50 = 140) vor B (−50 − 90 + 150 + 50 = 60): dasselbe Ergebnis.
        val table = StatisticEngine.forEvenings(listOf(evening))
        assertEquals(listOf(a, b, c, d), table.players)
        assertEquals(a, table.solo.specialist?.player)
        assertEquals(b, table.solo.wallflower?.player)
    }

    @Test
    fun `Abende mit gleichem Beginn werden nach ihrer Kennung geordnet, egal in welcher Reihenfolge sie kommen`() {
        // Zwei Abende mit demselben Beginn (zum Beispiel mit demselben Datum nachgetragen): „x1“ mit einem Rufspiel
        // (A und B je +10), „x2“ mit demselben Rufspiel und einem Farbsolo von C (+150, die anderen je −50).
        val first = evening("x1", rufspiel(a, b))
        val second = evening("x2", rufspiel(a, b), solo(c))

        val stats = StatisticEngine.forEvenings(listOf(second, first))

        assertEquals(listOf(EveningId("x1"), EveningId("x2")), stats.finance.eveningIds)
        assertEquals(listOf(0, 1), stats.finance.eveningStarts)
        // C −10 − 10 + 150, A und B 10 + 10 − 50, D −10 − 10 − 50.
        assertEquals(listOf(c, a, b, d), stats.players)
        assertEquals(
            mapOf(
                c to listOf(0, -10, -20, 130),
                a to listOf(0, 10, 20, -30),
                b to listOf(0, 10, 20, -30),
                d to listOf(0, -10, -20, -70),
            ),
            stats.finance.balanceSeries,
        )
        // A gewinnt an beiden Abenden 10: Bei gleich hohen Beträgen wird der frühere Abend genannt, hier also der mit
        // der kleineren Kennung.
        assertEquals(EveningId("x1"), stats.history.statsFor(a)?.biggestWin?.eveningId)
        // Die Reihenfolge der Liste spielt keine Rolle.
        assertEquals(stats, StatisticEngine.forEvenings(listOf(first, second)))
        assertEquals(HistoryStatistics.compute(listOf(second, first)), HistoryStatistics.compute(listOf(first, second)))
    }

    @Test
    fun `Gespanne mit gleichem Saldo, gleicher Quote und gleichem ersten Spieler stehen nach dem zweiten Spieler`() {
        // Erst gewinnen A+D gegen B+C, dann A+B gegen C+D, beide Male ein einfaches Rufspiel: A+D und A+B stehen
        // bei +20 mit 1 von 1. A+B steht vorn, obwohl A+D zuerst gespielt hat.
        val stats = StatisticEngine.forEvening(evening("zweiter", rufspiel(a, d), rufspiel(a, b)))

        assertEquals(
            listOf(
                PartnerPair(a, b, games = 1, won = 1, balance = 20),
                PartnerPair(a, d, games = 1, won = 1, balance = 20),
                PartnerPair(b, c, games = 1, won = 0, balance = -20),
                PartnerPair(c, d, games = 1, won = 0, balance = -20),
            ),
            stats.partners.pairs,
        )
    }

    @Test
    fun `ein Spiel aus zwei ueberlagerten Bockrunden zaehlt als Bockspiel`() {
        // Regel „überlagern“: Zwei ausgelöste Bockrunden vervierfachen das nächste Spiel (10 × 2 × 2 = 40).
        val stacked = RuleSet.standard().copy(bock = BockRules(stacking = BockStacking.STACK))
        val evening = evening(
            "doppelbock",
            BockTriggerEntry(id(), firstDay, BockReason.MANUAL),
            BockTriggerEntry(id(), firstDay, BockReason.MANUAL),
            rufspiel(a, b),
            rules = stacked,
        )

        val stats = StatisticEngine.forEvening(evening)

        assertEquals(
            listOf(Standing(a, 40, 1), Standing(b, 40, 1), Standing(c, -40, 3), Standing(d, -40, 3)),
            stats.finance.standings,
        )
        assertEquals(
            listOf(BockRecord(a, 1, 1, 40), BockRecord(b, 1, 1, 40), BockRecord(c, 1, 0, -40), BockRecord(d, 1, 0, -40)),
            stats.bock.records,
        )
        assertEquals(a, stats.bock.king?.player)
        assertEquals(1, stats.history.bockGamesCount)
    }

    @Test
    fun `ein doppelt eingetragener Sitzplatz ergibt keinen doppelten Spieler`() {
        // So einen Abend lässt die App nicht zu (der Import weist ihn ab). Kommt er trotzdem an, nennt die Auswertung
        // jeden Spieler genau einmal.
        val stats = StatisticEngine.forEvening(evening("doppelt", rufspiel(a, b), seating = listOf(a, a, b, c)))

        assertEquals(listOf(a, b, c), stats.players)
        assertEquals(stats.players, stats.finance.standings.map { it.player })
        assertEquals(stats.players, stats.finance.balanceSeries.keys.toList())
        assertEquals(stats.players, stats.gameModes.map { it.player })
        assertEquals(stats.players, stats.gelegt.map { it.player })
        assertEquals(stats.players, stats.bock.records.map { it.player })
        assertEquals(stats.players, stats.solo.records.map { it.player })
    }

    @Test
    fun `Fuehrungswechsel zaehlen nur an Punkten, die auch der Verlauf zeigt`() {
        // Farbsolo von A gewonnen (A 150), freie Buchung (C +500, A −500), ein Spiel mit unbekanntem Ansager (bucht
        // nichts und zählt in der Statistik nicht), freie Buchung (A +1000, C −1000), Farbsolo von A gewonnen (A 800).
        val unknownDeclarer = GameEntry(id(), firstDay, GameType.WENZ, declarer = PlayerId("X"), declarerWon = true)
        val evening = evening(
            "luecke",
            solo(a),
            booking(c to 500, a to -500),
            unknownDeclarer,
            booking(a to 1000, c to -1000),
            solo(a),
        )

        val stats = StatisticEngine.forEvening(evening)

        // Die Statistik zählt zwei Spiele, und nach beiden führt A allein.
        assertEquals(2, stats.gameCount)
        assertEquals(
            mapOf(a to listOf(0, 150, 800), b to listOf(0, -50, -100), c to listOf(0, -50, -600), d to listOf(0, -50, -100)),
            stats.finance.balanceSeries,
        )
        // Also gibt es keinen Führungswechsel. Das nicht abrechenbare Spiel bekommt in der Statistik keinen eigenen
        // Punkt; an ihm läge C wegen der ersten Buchung kurz vorn (so zeigt es der Verlauf des Abend-Bildschirms,
        // der den Spielnummern folgt und deshalb einen Punkt mehr hat).
        assertEquals(0, stats.history.leadChanges)
        assertEquals(0, EveningStatistics.compute(EveningCalculator.compute(evening)).leadChanges)
        assertEquals(listOf(0, 150, -350, 800), Progression.balanceSeries(EveningCalculator.compute(evening))[a])
    }

    @Test
    fun `stehen unter einer Kennung zwei verschiedene Fassungen in der Liste, zaehlt die zuerst uebergebene`() {
        // Das lässt die Datenhaltung nicht zu (jede Kennung gibt es nur einmal). Kommt es trotzdem vor, ist das
        // Ergebnis wenigstens festgelegt; derselbe Abend doppelt ändert nichts.
        val earlier = evening("gleich", rufspiel(a, b))
        val later = evening("gleich", rufspiel(a, b), rufspiel(c, d))

        assertEquals(1, StatisticEngine.forEvenings(listOf(earlier, later)).gameCount)
        assertEquals(2, StatisticEngine.forEvenings(listOf(later, earlier)).gameCount)
        assertEquals(StatisticEngine.forEvenings(listOf(earlier)), StatisticEngine.forEvenings(listOf(earlier, later, earlier)))
        assertEquals(StatisticEngine.forEvenings(listOf(later)), StatisticEngine.forEvenings(listOf(later, later)))
    }

    @Test
    fun `ein Gespann im Minus ist kein Lieblingspartner, auch wenn es als einziges genug gemeinsame Spiele hat`() {
        // Erster Abend (A, B, C, D): A+B verlieren zwei Rufspiele gegen C+D. Zweiter Abend mit E statt D: A+B
        // verlieren eines gegen C+E. Nur A+B haben drei gemeinsame Spiele, stehen aber bei −60.
        val e = PlayerId("E")
        val first = evening("abend-1", rufspiel(c, d), rufspiel(d, c))
        val second = evening("abend-2", rufspiel(c, e), seating = listOf(a, b, c, e))

        val stats = StatisticEngine.forEvenings(listOf(first, second))

        assertEquals(
            listOf(
                PartnerPair(c, d, games = 2, won = 2, balance = 40),
                PartnerPair(c, e, games = 1, won = 1, balance = 20),
                PartnerPair(a, b, games = 3, won = 0, balance = -60),
            ),
            stats.partners.pairs,
        )
        // „Erfolgreichstes Zweiergespann“: Ein Gespann, das nur verloren hat, ist das nicht.
        assertEquals(null, stats.partners.favorite)
    }
}
