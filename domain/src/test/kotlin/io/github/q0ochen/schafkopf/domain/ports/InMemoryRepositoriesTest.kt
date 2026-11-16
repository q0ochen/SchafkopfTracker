package io.github.q0ochen.schafkopf.domain.ports

import app.cash.turbine.test
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Die Ablagen im Speicher verhalten sich wie die echten: Jede Änderung meldet sich von selbst bei allen,
 * die zuschauen (geprüft mit Turbine).
 */
class InMemoryRepositoriesTest {

    private val seating = listOf(PlayerId("A"), PlayerId("B"), PlayerId("C"), PlayerId("D"))

    private fun evening(id: String, startedAt: String) = Evening(
        id = EveningId(id),
        startedAt = Instant.parse(startedAt),
        seating = seating,
        firstDealer = seating.first(),
        rules = RuleSet.standard(),
    )

    @Test
    fun `Spieler - anlegen und aendern meldet sich bei den Beobachtern`() = runTest {
        val repository = InMemoryPlayerRepository()
        repository.players.test {
            assertEquals(emptyList<Player>(), awaitItem())

            repository.save(Player(PlayerId("A"), "Spieler A", colorIndex = 0))
            assertEquals(listOf("Spieler A"), awaitItem().map { it.name })

            repository.save(Player(PlayerId("B"), "Spieler B", colorIndex = 1))
            assertEquals(listOf("Spieler A", "Spieler B"), awaitItem().map { it.name })

            // Dieselbe Kennung ersetzt den Spieler an seiner Stelle.
            repository.save(Player(PlayerId("A"), "Neuer Name", colorIndex = 0, archived = true))
            val changed = awaitItem()
            assertEquals(listOf("Neuer Name", "Spieler B"), changed.map { it.name })
            assertTrue(changed.first().archived)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Spieler - loeschen entfernt nur diesen Spieler, ein unbekannter aendert nichts`() = runTest {
        val repository = InMemoryPlayerRepository(
            listOf(Player(PlayerId("A"), "Spieler A", colorIndex = 0), Player(PlayerId("B"), "Spieler B", colorIndex = 1)),
        )
        repository.delete(PlayerId("A"))
        assertEquals(listOf("Spieler B"), repository.players.first().map { it.name })
        repository.delete(PlayerId("gibt-es-nicht"))
        assertEquals(listOf("Spieler B"), repository.players.first().map { it.name })
    }

    @Test
    fun `Abende - der neueste steht vorn und geloeschte verschwinden`() = runTest {
        val older = evening("alt", "2026-11-07T19:00:00Z")
        val newer = evening("neu", "2026-11-14T19:00:00Z")
        val repository = InMemoryEveningRepository(listOf(older))

        repository.evenings.test {
            assertEquals(listOf(older.id), awaitItem().map { it.id })

            repository.save(newer)
            assertEquals(listOf(newer.id, older.id), awaitItem().map { it.id })

            repository.delete(newer.id)
            assertEquals(listOf(older.id), awaitItem().map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ein einzelner Abend meldet jede Aenderung und sein Verschwinden`() = runTest {
        val start = evening("abend", "2026-11-14T19:00:00Z")
        val repository = InMemoryEveningRepository(listOf(start))

        repository.evening(start.id).test {
            assertEquals(start, awaitItem())

            val changed = start.append(ThrownInEntry(EntryId("e1"), start.startedAt))
            repository.save(changed)
            assertEquals(changed, awaitItem())

            repository.delete(start.id)
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ein unbekannter Abend ist null`() = runTest {
        InMemoryEveningRepository().evening(EveningId("gibt es nicht")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Einstellungen - Standardwerte der Vorgabe und Aenderungen`() = runTest {
        val repository = InMemorySettingsRepository()
        repository.settings.test {
            val defaults = awaitItem()
            // Dunkles Design ist der Standard, neue Abende beginnen mit dem Regelwerk der Runde.
            assertEquals(ThemeMode.DARK, defaults.themeMode)
            assertEquals(RuleSet.standard(), defaults.defaultRules)
            assertTrue(defaults.keepScreenOn)
            assertFalse(defaults.ai.enabled)
            assertFalse(defaults.ai.sendRealNames)
            assertEquals(AppSettings(), defaults)

            repository.update { it.copy(themeMode = ThemeMode.LIGHT, keepScreenOn = false) }
            val changed = awaitItem()
            assertEquals(ThemeMode.LIGHT, changed.themeMode)
            assertFalse(changed.keepScreenOn)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Schluessel - speichern, lesen und mit leerem Text loeschen`() = runTest {
        val store = InMemorySecretStore()
        assertNull(store.apiKey())

        store.setApiKey("geheim")
        assertEquals("geheim", store.apiKey())

        store.setApiKey("   ")
        assertNull(store.apiKey())

        store.setApiKey("neu")
        store.setApiKey(null)
        assertNull(store.apiKey())
    }
}
