package io.github.q0ochen.schafkopf.domain.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Der eigene kleine JSON-Schreiber, gegengeprüft mit dem JSON-Leser von kotlinx.serialization. */
class JsonWriterTest {

    @Test
    fun `Anfuehrungszeichen, Backslash und Steuerzeichen werden maskiert`() {
        assertEquals("\"einfach\"", jsonString("einfach"))
        assertEquals("\"\"", jsonString(""))
        assertEquals("\"sagt \\\"Servus\\\"\"", jsonString("sagt \"Servus\""))
        assertEquals("\"C:\\\\Karten\"", jsonString("C:\\Karten"))
        assertEquals("\"Zeile 1\\nZeile 2\\r\\n\"", jsonString("Zeile 1\nZeile 2\r\n"))
        assertEquals("\"\\t\\b\\f\"", jsonString("\t\b\u000C"))
        assertEquals("\"\\u0000\\u0001\\u001f\"", jsonString("\u0000\u0001\u001F"))
    }

    @Test
    fun `alle uebrigen Zeichen bleiben, wie sie sind`() {
        val text = "Umlaute äöüß, 4,20\u00A0€, \u22127,20, Emoji \uD83C\uDCCF, [[S1]], {\"x\": 1}/"
        assertEquals("\"Umlaute äöüß, 4,20\u00A0€, \u22127,20, Emoji \uD83C\uDCCF, [[S1]], {\\\"x\\\": 1}/\"", jsonString(text))
    }

    @Test
    fun `jeder Text kommt nach dem Einlesen unveraendert zurueck`() {
        val texts = listOf(
            "einfach",
            "",
            "Hans \"Bomber\" O'Neil",
            "Hansi \\ \$1",
            "\\\"",
            "Zeile 1\nZeile 2\r\nZeile 3\tmit Tab",
            "Steuerzeichen \u0000 \u0001 \u0007 \u001B \u001F \b \u000C und \u007F",
            "Umlaute äöüß, 4,20\u00A0€, \u22127,20, Emoji \uD83C\uDCCF",
            "[[S1]] {\"json\": [1, 2]} </script>",
        )
        for (text in texts) {
            val parsed = Json.parseToJsonElement(jsonString(text)) as JsonPrimitive
            assertTrue(parsed.isString)
            assertEquals(text, parsed.content)
        }
    }

    @Test
    fun `ein Objekt wird kompakt und in der Reihenfolge der Eintraege geschrieben`() {
        val json = jsonObject {
            number("schema", 1)
            string("name", "Anna")
            boolean("ja", true)
            boolean("nein", false)
            number("null_bleibt_stehen", 0)
            number("minus", -5)
            strings("liste", listOf("a", "b"))
            obj("kind") {
                string("name", "x")
                number("zahl", 2)
            }
            objects("kinder", listOf(1, 2)) { n -> number("n", n) }
        }
        assertEquals(
            """{"schema":1,"name":"Anna","ja":true,"nein":false,"null_bleibt_stehen":0,"minus":-5,"liste":""" +
                """["a","b"],"kind":{"name":"x","zahl":2},"kinder":[{"n":1},{"n":2}]}""",
            json,
        )
    }

    @Test
    fun `leere Angaben werden weggelassen`() {
        val json = jsonObject {
            number("schema", 1)
            string("leerer_text", "")
            string("kein_text", null)
            number("keine_zahl", null)
            boolean("kein_wahrheitswert", null)
            strings("leere_liste", emptyList())
            strings("liste_mit_leerem_text", listOf("", "a", ""))
            strings("liste_nur_mit_leeren_texten", listOf("", ""))
            obj("leeres_objekt") { string("name", null) }
            objects("keine_objekte", emptyList<Int>()) { n -> number("n", n) }
            objects("nur_leere_objekte", listOf(1, 2)) { _ -> string("name", "") }
            objects("teils_leere_objekte", listOf(0, 7)) { n -> number("n", n.takeIf { it > 0 }) }
        }
        assertEquals("""{"schema":1,"liste_mit_leerem_text":["a"],"teils_leere_objekte":[{"n":7}]}""", json)
        assertEquals("{}", jsonObject { string("nichts", null) })
    }

    @Test
    fun `das Ergebnis ist gueltiges JSON, auch mit schwierigen Namen und Werten`() {
        val nasty = "Hans \"Bomber\" \\ \n O'Neil"
        val json = jsonObject {
            string(nasty, nasty)
            strings("liste", listOf(nasty, "zwei"))
            obj("kind") { string("name", nasty) }
        }
        val parsed = Json.parseToJsonElement(json) as JsonObject

        assertEquals(setOf(nasty, "liste", "kind"), parsed.keys)
        assertEquals(nasty, (parsed.getValue(nasty) as JsonPrimitive).content)
        assertEquals(listOf(nasty, "zwei"), (parsed.getValue("liste") as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(nasty, ((parsed.getValue("kind") as JsonObject).getValue("name") as JsonPrimitive).content)
    }
}
