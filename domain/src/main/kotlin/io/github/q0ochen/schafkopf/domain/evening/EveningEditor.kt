package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * Ändert einen Abend und speichert ihn sofort. Alle Änderungen laufen nacheinander, damit keine Eingabe
 * eine andere überschreibt.
 */
class EveningEditor(
    private val evenings: EveningRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) {
    private val mutex = Mutex()

    /** Neue Kennung für einen Eintrag. */
    fun newEntryId(): EntryId = EntryId(idGenerator.newId())

    /** Die aktuelle Zeit laut injizierter Uhr. */
    fun now(): Instant = clock.instant()

    /**
     * Liest den aktuellen Stand des Abends, wendet [transform] an und speichert das Ergebnis.
     *
     * @return der gespeicherte Abend oder `null`, wenn es ihn nicht (mehr) gibt.
     */
    suspend fun update(id: EveningId, transform: (Evening) -> Evening): Evening? = mutex.withLock {
        val current = evenings.evening(id).first() ?: return@withLock null
        val changed = transform(current)
        if (changed != current) evenings.save(changed)
        changed
    }
}
