package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuleSetJsonTest {

    @Test
    fun `ein Regelwerk kommt als Text unveraendert zurueck`() {
        assertEquals(TestData.fullRules, RuleSetJson.decode(RuleSetJson.encode(TestData.fullRules)))
        assertEquals(RuleSet.sauspiel(), RuleSetJson.decode(RuleSetJson.encode(RuleSet.sauspiel())))
    }

    @Test
    fun `der Text ist derselbe wie im Abend und damit wie in der Sicherung`() {
        val evening = TestData.fullEvening()
        val inEvening = Json.parseToJsonElement(StorageCodec.encodeEvening(evening)).jsonObject.getValue("rules")

        assertEquals(inEvening, Json.parseToJsonElement(RuleSetJson.encode(evening.rules)))
    }

    @Test
    fun `unbekannte Felder werden ueberlesen und fehlende Werte kommen aus der Vorlage Sauspiel`() {
        assertEquals(RuleSet.sauspiel(), RuleSetJson.decode("{}"))
        assertEquals(
            RuleSet.sauspiel().copy(unit = 5, klopfenEnabled = false),
            RuleSetJson.decode("""{"unit":5,"klopfenEnabled":false,"zusatzregel":[1,2,{"x":null}]}"""),
        )
        val sauspiel = RuleSet.sauspiel()
        assertEquals(
            sauspiel.copy(bock = sauspiel.bock.copy(stacking = BockStacking.STACK)),
            RuleSetJson.decode("""{"bock":{"stacking":"stack"}}"""),
        )
    }

    @Test
    fun `ein unlesbarer Text ergibt null statt einer Ausnahme`() {
        val unreadable = listOf(
            "",
            "kein JSON",
            "[]",
            "{\"unit\":10",
            "{\"unit\":\"zehn\"}",
            "{\"prices\":{\"ramsch\":10}}",
            "{\"enabledTypes\":[\"ramsch\"]}",
            "{\"bock\":{\"stacking\":\"turm\"}}",
        )
        for (text in unreadable) assertNull(RuleSetJson.decode(text), text)
    }
}
