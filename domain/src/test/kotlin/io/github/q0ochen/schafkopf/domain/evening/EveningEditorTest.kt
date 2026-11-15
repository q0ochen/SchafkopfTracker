package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.InMemoryEveningRepository
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Ändern und sofortiges Speichern eines Abends; die Datenablage ist hier nachgestellt (MockK). */
class EveningEditorTest {

    private val now: Instant = Instant.parse("2026-11-14T19:30:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val evening = Evening(
        id = EveningId("abend"),
        startedAt = now,
        seating = listOf(a, b, c, d),
        firstDealer = a,
        rules = RuleSet.standard(),
    )

    private val repository = mockk<EveningRepository>()
    private val ids = mockk<IdGenerator>()
    private val editor = EveningEditor(repository, clock, ids)

    @Test
    fun `eine Aenderung wird sofort gespeichert und zurueckgegeben`() = runTest {
        every { repository.evening(evening.id) } returns flowOf(evening)
        coEvery { repository.save(any()) } just Runs
        val game = GameEntry(EntryId("e1"), now, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true)

        val result = editor.update(evening.id) { it.append(game) }

        assertEquals(evening.append(game), result)
        coVerify(exactly = 1) { repository.save(evening.append(game)) }
    }

    @Test
    fun `ohne Aenderung wird nichts gespeichert`() = runTest {
        every { repository.evening(evening.id) } returns flowOf(evening)

        val result = editor.update(evening.id) { it }

        assertEquals(evening, result)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `gibt es den Abend nicht mehr, wird nichts geaendert und nichts gespeichert`() = runTest {
        every { repository.evening(any()) } returns flowOf(null)
        var asked = false

        val result = editor.update(EveningId("weg")) {
            asked = true
            it
        }

        assertNull(result)
        assertFalse(asked)
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `Kennungen kommen vom Generator und die Zeit von der Uhr`() {
        every { ids.newId() } returnsMany listOf("k1", "k2")

        assertEquals(EntryId("k1"), editor.newEntryId())
        assertEquals(EntryId("k2"), editor.newEntryId())
        assertEquals(now, editor.now())
        verify(exactly = 2) { ids.newId() }
    }

    @Test
    fun `gleichzeitige Aenderungen ueberschreiben einander nicht`() = runTest {
        val storage = InMemoryEveningRepository(listOf(evening))
        val realEditor = EveningEditor(storage, clock, IdGenerator { "x" })

        List(50) { index ->
            launch { realEditor.update(evening.id) { it.append(ThrownInEntry(EntryId("e$index"), now)) } }
        }.joinAll()

        val stored = storage.evening(evening.id).first()
        assertEquals(50, stored?.entries?.size)
        assertEquals(50, stored?.entries?.map { it.id }?.toSet()?.size)
    }
}
