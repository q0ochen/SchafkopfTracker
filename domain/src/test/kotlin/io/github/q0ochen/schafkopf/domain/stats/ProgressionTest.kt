package io.github.q0ochen.schafkopf.domain.stats

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
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class ProgressionTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d
    private val seating = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private var nextId = 0

    private fun id() = EntryId("p${nextId++}")

    private fun compute(vararg entries: EveningEntry): EveningState = EveningCalculator.compute(
        Evening(
            id = EveningId("verlauf"),
            startedAt = now,
            seating = seating,
            firstDealer = a,
            rules = RuleSet.sauspiel(),
            entries = entries.toList(),
        ),
    )

    /** Rufspiel A mit B gewonnen, ohne Zusätze: A +20, B +20, C −20, D −20. */
    private fun rufspiel() = GameEntry(id(), now, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true)

    /** Freie Buchung: C zahlt 50 an A. */
    private fun bet() = ManualEntry(id(), now, mapOf(a to 50, c to -50), "Wette")

    @Test
    fun `Saldenverlauf des Beispielabends`() {
        // Die Tabelle „Stand nach jedem Spiel“ aus SampleEvening, davor der Start bei 0.
        val expected = mapOf(
            a to listOf(0, 20, 80, 320, 420, 260, 100, 1160, 840, 760, 920, 1120, 920),
            b to listOf(0, 20, 80, 0, -300, -460, -300, -620, -300, -380, -540, -1140, -1340),
            c to listOf(0, -20, -80, -160, -60, -220, -60, -380, -60, 180, 340, 540, 1140),
            d to listOf(0, -20, -80, -160, -60, 420, 260, -160, -480, -560, -720, -520, -720),
        )
        val series = Progression.balanceSeries(SampleEvening.state)
        assertEquals(expected, series)
        assertEquals(seating, series.keys.toList())
    }

    @Test
    fun `Platzverlauf des Beispielabends`() {
        // Platz = 1 + Zahl der Spieler mit höherem Saldo, abgelesen aus der Tabelle „Stand nach jedem Spiel“.
        // Nach Spiel 4 zum Beispiel: A 420, C −60, D −60, B −300 ergibt die Plätze 1, 2, 2, 4.
        val expected = mapOf(
            a to listOf(1, 1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 1, 2),
            b to listOf(1, 1, 1, 2, 4, 4, 4, 4, 3, 3, 3, 4, 4),
            c to listOf(1, 3, 3, 3, 2, 3, 3, 3, 2, 2, 2, 2, 1),
            d to listOf(1, 3, 3, 3, 2, 1, 1, 2, 4, 4, 4, 3, 3),
        )
        val series = Progression.rankSeries(SampleEvening.state)
        assertEquals(expected, series)
        assertEquals(seating, series.keys.toList())
    }

    @Test
    fun `jede Reihe hat einen Punkt mehr als gespielte Spiele und endet beim aktuellen Stand`() {
        val state = SampleEvening.state
        val balances = Progression.balanceSeries(state)
        val ranks = Progression.rankSeries(state)
        for (player in seating) {
            assertEquals(state.gamesPlayed + 1, balances.getValue(player).size)
            assertEquals(state.gamesPlayed + 1, ranks.getValue(player).size)
            assertEquals(state.balances.getValue(player), balances.getValue(player).last())
            assertEquals(state.ranks.getValue(player), ranks.getValue(player).last())
        }
    }

    @Test
    fun `leerer Abend hat nur den Startpunkt`() {
        val state = compute()
        assertEquals(seating.associateWith { listOf(0) }, Progression.balanceSeries(state))
        assertEquals(seating.associateWith { listOf(1) }, Progression.rankSeries(state))
    }

    @Test
    fun `zusammengeworfene Blaetter und Bock-Ausloeser ergeben keinen eigenen Punkt`() {
        val state = compute(
            ThrownInEntry(id(), now),
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = c),
            rufspiel(),
            ThrownInEntry(id(), now),
        )
        // Das Rufspiel ist ein Bockspiel und zählt doppelt: 40 statt 20.
        assertEquals(
            mapOf(a to listOf(0, 40), b to listOf(0, 40), c to listOf(0, -40), d to listOf(0, -40)),
            Progression.balanceSeries(state),
        )
        assertEquals(
            mapOf(a to listOf(1, 1), b to listOf(1, 1), c to listOf(1, 3), d to listOf(1, 3)),
            Progression.rankSeries(state),
        )
    }

    @Test
    fun `freie Buchung vor einem Spiel zaehlt zum Punkt dieses Spiels`() {
        val state = compute(bet(), rufspiel(), rufspiel())
        // Nach Spiel 1: A 50 + 20 = 70, C −50 − 20 = −70. Nach Spiel 2: A 90, C −90.
        assertEquals(
            mapOf(a to listOf(0, 70, 90), b to listOf(0, 20, 40), c to listOf(0, -70, -90), d to listOf(0, -20, -40)),
            Progression.balanceSeries(state),
        )
        assertEquals(
            mapOf(a to listOf(1, 1, 1), b to listOf(1, 2, 2), c to listOf(1, 4, 4), d to listOf(1, 3, 3)),
            Progression.rankSeries(state),
        )
    }

    @Test
    fun `freie Buchung nach dem letzten Spiel zaehlt zum letzten Punkt`() {
        val state = compute(rufspiel(), bet())
        // Spiel 1 bringt A 20 und kostet C 20, die Wette danach bringt A weitere 50 und kostet C weitere 50.
        assertEquals(
            mapOf(a to listOf(0, 70), b to listOf(0, 20), c to listOf(0, -70), d to listOf(0, -20)),
            Progression.balanceSeries(state),
        )
        assertEquals(
            mapOf(a to listOf(1, 1), b to listOf(1, 2), c to listOf(1, 4), d to listOf(1, 3)),
            Progression.rankSeries(state),
        )
    }

    @Test
    fun `ohne ein einziges Spiel steht eine freie Buchung im einzigen Punkt`() {
        val state = compute(bet())
        assertEquals(
            mapOf(a to listOf(50), b to listOf(0), c to listOf(-50), d to listOf(0)),
            Progression.balanceSeries(state),
        )
        assertEquals(
            mapOf(a to listOf(1), b to listOf(2), c to listOf(4), d to listOf(2)),
            Progression.rankSeries(state),
        )
    }
}
