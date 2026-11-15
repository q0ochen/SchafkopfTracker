package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SettleUpTest {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")

    @Test
    fun `alle bei null - niemand zahlt`() {
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(mapOf(a to 0, b to 0, c to 0, d to 0)))
    }

    @Test
    fun `ein Verlierer zahlt an drei Gewinner`() {
        val transfers = SettleUp.transfers(mapOf(a to 50, b to 30, c to 20, d to -100))
        assertEquals(listOf(Transfer(d, a, 50), Transfer(d, b, 30), Transfer(d, c, 20)), transfers)
    }

    @Test
    fun `zwei passende Paare brauchen nur zwei Zahlungen`() {
        val transfers = SettleUp.transfers(mapOf(a to 450, b to -200, c to 200, d to -450))
        assertEquals(listOf(Transfer(d, a, 450), Transfer(b, c, 200)), transfers)
    }

    @Test
    fun `gemischter Fall mit drei Zahlungen`() {
        val transfers = SettleUp.transfers(mapOf(a to 300, b to 300, c to -200, d to -400))
        assertEquals(listOf(Transfer(d, a, 300), Transfer(c, b, 200), Transfer(d, b, 100)), transfers)
    }

    @Test
    fun `gehen die Salden nicht auf, wird so weit wie moeglich ausgeglichen`() {
        // Niemand schuldet etwas: Es gibt nichts zu zahlen, auch wenn A im Plus steht.
        assertEquals(emptyList<Transfer>(), SettleUp.transfers(mapOf(a to 10, b to 0)))
        // B kann nur 60 zahlen; die übrigen 40 von A bleiben offen.
        assertEquals(listOf(Transfer(b, a, 60)), SettleUp.transfers(mapOf(a to 100, b to -60, c to 0)))
    }

    @Test
    fun `ob die Salden aufgehen, laesst sich pruefen`() {
        assertTrue(SettleUp.isBalanced(mapOf(a to 50, b to 30, c to 20, d to -100)))
        assertTrue(SettleUp.isBalanced(emptyMap()))
        assertFalse(SettleUp.isBalanced(mapOf(a to 10, b to 0)))
    }

    @Test
    fun `nach den Zahlungen sind alle ausgeglichen und es sind hoechstens n minus 1`() {
        val random = Random(99)
        repeat(500) {
            val players = List(random.nextInt(2, 8)) { PlayerId("P$it") }
            val amounts = players.dropLast(1).associateWith { random.nextInt(-5_000, 5_000) }.toMutableMap()
            amounts[players.last()] = -amounts.values.sum()

            val transfers = SettleUp.transfers(amounts)
            val after = amounts.toMutableMap()
            for (t in transfers) {
                assertTrue(t.amount > 0)
                after[t.from] = after.getValue(t.from) + t.amount
                after[t.to] = after.getValue(t.to) - t.amount
            }
            assertTrue(after.values.all { it == 0 })
            assertTrue(transfers.size <= players.size - 1)
        }
    }
}
