package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import java.time.Duration

/**
 * Findet Spiele, die vermutlich zweimal eingetragen wurden, weil zwei am Tisch gleichzeitig getippt haben. Die App
 * weist nur darauf hin; gelöscht wird nichts von selbst.
 */
object DuplicateCheck {
    /** So weit dürfen die beiden Einträge höchstens auseinanderliegen. */
    val WINDOW: Duration = Duration.ofSeconds(120)

    /**
     * Die Kennungen der Spiele, die genauso direkt davor schon einmal stehen: gleich bis auf Kennung, Zeitpunkt und
     * Notiz und höchstens [WINDOW] nach oder vor dem anderen aufgeschrieben. Gemeldet wird jeweils das hintere.
     */
    fun suspects(evening: Evening): Set<EntryId> {
        val result = LinkedHashSet<EntryId>()
        evening.entries.zipWithNext { first, second ->
            if (first is GameEntry && second is GameEntry && sameGame(first, second)) result += second.id
        }
        return result
    }

    private fun sameGame(first: GameEntry, second: GameEntry): Boolean =
        first.copy(id = second.id, recordedAt = second.recordedAt, note = second.note) == second &&
            Duration.between(first.recordedAt, second.recordedAt).abs() <= WINDOW
}
