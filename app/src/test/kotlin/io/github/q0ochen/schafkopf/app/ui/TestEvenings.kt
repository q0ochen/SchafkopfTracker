package io.github.q0ochen.schafkopf.app.ui

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import java.time.Instant

/** Gemeinsame Beispieldaten für die Tests der Oberfläche: die feste Runde und einfache Abende. */
object TestEvenings {
    val anna = PlayerId("spieler-1")
    val bert = PlayerId("spieler-2")
    val cilli = PlayerId("spieler-3")
    val done = PlayerId("spieler-4")
    val seating = listOf(anna, bert, cilli, done)
    val players = listOf(
        Player(anna, "Anna", colorIndex = 0),
        Player(bert, "Bert", colorIndex = 1),
        Player(cilli, "Cilli", colorIndex = 2),
        Player(done, "Done", colorIndex = 3),
    )
    val names: Map<PlayerId, String> = players.associate { it.id to it.name }

    /** Rufspiel Anna mit Cilli gewonnen (je 10), dann Wenz von Bert verloren ((50 + 0) × 3 = 150 für Bert). */
    fun evening(
        id: String = "abend-1",
        startedAt: Instant = Instant.parse("2026-11-14T18:30:00Z"),
        location: String = "",
        finished: Boolean = false,
        rules: RuleSet = RuleSet.standard(),
        extra: List<ManualEntry> = emptyList(),
    ): Evening = Evening(
        id = EveningId(id),
        startedAt = startedAt,
        finishedAt = startedAt.plusSeconds(4 * 3600).takeIf { finished },
        location = location,
        seating = seating,
        firstDealer = anna,
        rules = rules,
        entries = listOf(
            GameEntry(EntryId("$id-g1"), startedAt, GameType.RUFSPIEL, declarer = anna, partner = cilli, declarerWon = true),
            GameEntry(EntryId("$id-g2"), startedAt, GameType.WENZ, declarer = bert, declarerWon = false),
        ) + extra,
    )
}
