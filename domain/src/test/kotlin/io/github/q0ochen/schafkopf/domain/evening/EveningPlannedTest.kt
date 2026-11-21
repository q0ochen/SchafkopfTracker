package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EveningPlannedTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 11, 21)
    private val seating = listOf(PlayerId("a"), PlayerId("b"), PlayerId("c"), PlayerId("d"))

    private fun evening(startedAt: String, finishedAt: String? = null) = Evening(
        id = EveningId("abend"),
        startedAt = Instant.parse(startedAt),
        finishedAt = finishedAt?.let(Instant::parse),
        seating = seating,
        firstDealer = seating.first(),
        rules = RuleSet.standard(),
    )

    @Test
    fun `ein Abend an einem spaeteren Tag ist geplant`() {
        assertTrue(evening("2026-12-12T18:00:00Z").isPlanned(today, berlin))
        // Morgen um 0:30 Uhr Ortszeit (in UTC noch heute).
        assertTrue(evening("2026-11-21T23:30:00Z").isPlanned(today, berlin))
    }

    @Test
    fun `heute, frueher oder beendet ist nicht geplant`() {
        // Heute um 23:30 Uhr Ortszeit, also auch nach jetzt: Am Tag selbst läuft der Abend.
        assertFalse(evening("2026-11-21T22:30:00Z").isPlanned(today, berlin))
        assertFalse(evening("2026-11-14T18:00:00Z").isPlanned(today, berlin))
        assertFalse(evening("2026-12-12T18:00:00Z", finishedAt = "2026-11-21T10:00:00Z").isPlanned(today, berlin))
    }
}
