package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import io.github.q0ochen.schafkopf.domain.stats.typo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Die Anfrage an ein Sprachmodell (Anweisung und Fakten als JSON) und die Aufbereitung der Antwort.
 * Grundlage ist der von Hand durchgerechnete Beispielabend aus [SampleEvening].
 */
class SummaryPromptTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d

    private val facts = SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, SampleEvening.zone)
    private val withPlaceholders = SummaryPrompt.payload(facts, useRealNames = false)
    private val withRealNames = SummaryPrompt.payload(facts, useRealNames = true)

    private val noWarnings = emptySet<SummaryWarning>()

    /** Macht aus einem zur besseren Lesbarkeit umbrochenen JSON-Text wieder eine einzige Zeile. */
    private fun compact(json: String): String = json.lines().joinToString("") { it.trim() }

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun JsonObject.texts(name: String): List<String> =
        (getValue(name) as JsonArray).map { (it as JsonPrimitive).content }

    private val expectedWithPlaceholders = typo(
        compact(
            """
            {"schema":1,"datum":"14.11.2026","spiele":12,"einheit":"Euro",
            "spieler":["[[S1]]","[[S2]]","[[S3]]","[[S4]]"],
            "rangliste":[
            {"platz":1,"spieler":"[[S3]]","stand":"+11,40 €"},
            {"platz":2,"spieler":"[[S1]]","stand":"+9,20 €"},
            {"platz":3,"spieler":"[[S4]]","stand":"-7,20 €"},
            {"platz":4,"spieler":"[[S2]]","stand":"-13,40 €"}],
            "abendsieger":{"spieler":["[[S3]]"],"stand":"+11,40 €"},
            "zahlmeister":{"spieler":["[[S2]]"],"stand":"-13,40 €"},
            "beim_legen_verzockt":{"spieler":"[[S2]]","verlorene_spiele":2,"verloren":"9,00 €"},
            "geschneidert":[
            {"spieler":"[[S1]]","schneider":2,"schwarz":1},
            {"spieler":"[[S4]]","schneider":2,"schwarz":1},
            {"spieler":"[[S2]]","schneider":2},
            {"spieler":"[[S3]]","schneider":2}],
            "spiel_des_abends":{"nummer":7,"spielart":"Gras-Solo","ansager":"[[S1]]","gewonnen":true,
            "wert_je_zahlung":"3,20 €","betrag_ansager":"+9,60 €","besonderheiten":["Tout","3 Laufende","Bockspiel"]},
            "fuehrungswechsel":3,
            "bockrunden":{"anzahl":2,"sechs_nixer":[{"spieler":"[[S3]]","anzahl":1},{"spieler":"[[S4]]","anzahl":1}]},
            "solo_koenig":{"spieler":"[[S1]]","gewonnene_einzelspiele":2},
            "laengste_siegesserie":{"spieler":"[[S3]]","spiele_am_stueck":5},
            "wer_zahlt_wem":[
            {"von":"[[S2]]","an":"[[S3]]","betrag":"11,40 €"},
            {"von":"[[S4]]","an":"[[S1]]","betrag":"7,20 €"},
            {"von":"[[S2]]","an":"[[S1]]","betrag":"2,00 €"}]}
            """,
        ),
    )

    @Test
    fun `die Anweisung nennt Rolle, Ton, Grenzen und Form`() {
        val prompt = SummaryPrompt.SYSTEM_PROMPT
        val required = listOf(
            "Chronist eines bayerischen Schafkopf-Stammtischs",
            "leichte bairische Färbung",
            "liebevoll frotzelnd",
            "nie beleidigend, abwertend oder diskriminierend",
            "ausschließlich Fakten, Zahlen und Namen aus diesem JSON",
            "Erfinde nichts dazu und rechne nicht",
            "das Feld einheit sagt, ob es Euro oder Punkte sind",
            "Platzhalter der Form [[S1]]",
            "unverändert",
            "samt den doppelten eckigen Klammern",
            "auch kein Genitiv-s",
            "Ob jemand ein Mann oder eine Frau ist, weißt du nicht",
            "reiner Text ohne Markdown",
            "höchstens 900 Zeichen",
            "Emojis sparsam",
            "Abendsieger",
            "wer den Abend finanziert hat",
            "wer sich beim Legen verzockt hat",
            "wer geschneidert wurde",
            "das Spiel des Abends",
            "Die übrigen Angaben im JSON",
        )
        for (phrase in required) assertTrue(phrase in prompt, "In der Anweisung fehlt: $phrase")
    }

    @Test
    fun `die Anweisung nennt jeden Abschnitt des JSON beim Namen`() {
        // Die fünf Rubriken stehen mit ihrem Schlüssel in der Anweisung, die übrigen Abschnitte als Stichwort.
        val prompt = SummaryPrompt.SYSTEM_PROMPT
        val sections = parse(withPlaceholders.userText).keys - setOf("schema", "datum", "spiele", "spieler")
        val mentioned = mapOf(
            "einheit" to "einheit",
            "rangliste" to "Rangliste",
            "abendsieger" to "(abendsieger)",
            "zahlmeister" to "(zahlmeister)",
            "beim_legen_verzockt" to "(beim_legen_verzockt)",
            "geschneidert" to "(geschneidert)",
            "spiel_des_abends" to "(spiel_des_abends)",
            "fuehrungswechsel" to "Führungswechsel",
            "bockrunden" to "Bockrunden",
            "solo_koenig" to "Solo-König",
            "laengste_siegesserie" to "längste Siegesserie",
            "wer_zahlt_wem" to "wer zahlt wem",
        )
        assertEquals(mentioned.keys, sections)
        for ((section, word) in mentioned) assertTrue(word in prompt, "Die Anweisung erwähnt nicht: $section")
    }

    @Test
    fun `die Fakten des Beispielabends als kompaktes JSON mit Platzhaltern`() {
        assertEquals(expectedWithPlaceholders, withPlaceholders.userText)
        // Gültiges JSON, und zwar dasselbe, das auch der JSON-Leser aus dem erwarteten Text macht.
        assertEquals(parse(expectedWithPlaceholders), parse(withPlaceholders.userText))
        assertEquals("1", (parse(withPlaceholders.userText).getValue("schema") as JsonPrimitive).content)
        assertFalse(withPlaceholders.userText.contains('\n'))
    }

    @Test
    fun `die Platzhalter stehen in Sitzreihenfolge fuer die Namen`() {
        assertEquals(
            mapOf("[[S1]]" to "Anna", "[[S2]]" to "Bert", "[[S3]]" to "Chris", "[[S4]]" to "Dora"),
            withPlaceholders.placeholders,
        )
        assertEquals(listOf("[[S1]]", "[[S2]]", "[[S3]]", "[[S4]]"), withPlaceholders.placeholders.keys.toList())
    }

    @Test
    fun `mit Platzhaltern verlassen weder Namen noch Ort das Geraet`() {
        for (secret in listOf("Anna", "Bert", "Chris", "Dora", "Beim Wirt", "Wirt")) {
            assertFalse(withPlaceholders.userText.contains(secret), "Im JSON steht: $secret")
        }
    }

    @Test
    fun `auf Wunsch stehen die echten Namen im JSON, der Ort trotzdem nie`() {
        val expected = expectedWithPlaceholders
            .replace("[[S1]]", "Anna")
            .replace("[[S2]]", "Bert")
            .replace("[[S3]]", "Chris")
            .replace("[[S4]]", "Dora")

        assertEquals(expected, withRealNames.userText)
        assertEquals(emptyMap<String, String>(), withRealNames.placeholders)
        assertFalse(withRealNames.userText.contains("[["))
        assertFalse(withRealNames.userText.contains("Wirt"))
        assertEquals(listOf("Anna", "Bert", "Chris", "Dora"), parse(withRealNames.userText).texts("spieler"))
    }

    @Test
    fun `leerer Abend - Null- und Leerwerte fehlen im JSON`() {
        val emptyEvening = EveningCalculator.compute(SampleEvening.evening.copy(entries = emptyList()))
        val payload = SummaryPrompt.payload(
            SummaryFactsBuilder.build(emptyEvening, SampleEvening.names, SampleEvening.zone),
            useRealNames = false,
        )
        val expected = typo(
            compact(
                """
                {"schema":1,"datum":"14.11.2026","spiele":0,"einheit":"Euro",
                "spieler":["[[S1]]","[[S2]]","[[S3]]","[[S4]]"],
                "rangliste":[
                {"platz":1,"spieler":"[[S1]]","stand":"0,00 €"},
                {"platz":1,"spieler":"[[S2]]","stand":"0,00 €"},
                {"platz":1,"spieler":"[[S3]]","stand":"0,00 €"},
                {"platz":1,"spieler":"[[S4]]","stand":"0,00 €"}]}
                """,
            ),
        )
        assertEquals(expected, payload.userText)
        assertEquals(setOf("schema", "datum", "spiele", "einheit", "spieler", "rangliste"), parse(payload.userText).keys)
    }

    @Test
    fun `wird um Punkte gespielt, steht das als Einheit im JSON`() {
        val points = EveningCalculator.compute(
            SampleEvening.evening.copy(rules = RuleSet.sauspiel().copy(displayAsMoney = false)),
        )
        val payload = SummaryPrompt.payload(
            SummaryFactsBuilder.build(points, SampleEvening.names, SampleEvening.zone),
            useRealNames = false,
        )
        val json = parse(payload.userText)

        assertEquals("Punkte", (json.getValue("einheit") as JsonPrimitive).content)
        assertEquals("+1140", ((json.getValue("abendsieger") as JsonObject).getValue("stand") as JsonPrimitive).content)
        assertFalse(payload.userText.contains("€"))
    }

    @Test
    fun `Namen mit Sonderzeichen ergeben gueltiges JSON und kommen unveraendert zurueck`() {
        // Anführungszeichen, Backslash und Dollarzeichen; zwei Namen fangen gleich an; einer sieht aus wie ein Platzhalter.
        val first = "Hans \"Bomber\" O'Neil"
        val second = "Hans"
        val third = "Hansi \\ \$1"
        val fourth = "[[S2]]"
        val special = SummaryFactsBuilder.build(
            SampleEvening.state,
            mapOf(a to first, b to second, c to third, d to fourth),
            SampleEvening.zone,
        )

        val real = SummaryPrompt.payload(special, useRealNames = true)
        assertEquals(listOf(first, second, third, fourth), parse(real.userText).texts("spieler"))
        // Chris alias „Hansi“ ist Abendsieger: Sein Name steht als Wert mitten im JSON.
        assertEquals(listOf(third), (parse(real.userText).getValue("abendsieger") as JsonObject).texts("spieler"))

        val hidden = SummaryPrompt.payload(special, useRealNames = false)
        assertEquals(mapOf("[[S1]]" to first, "[[S2]]" to second, "[[S3]]" to third, "[[S4]]" to fourth), hidden.placeholders)
        assertEquals(expectedWithPlaceholders, hidden.userText)
        for (secret in listOf("Hans", "Bomber", "O'Neil")) assertFalse(hidden.userText.contains(secret))

        val answer = "[[S1]] gratuliert [[S2]]. [[S3]] zahlt, [[S4]] schaut zu, und [[S2]]s Solo war stark."
        val finished = SummaryPrompt.finish(answer, hidden)
        assertEquals("$first gratuliert $second. $third zahlt, $fourth schaut zu, und ${second}s Solo war stark.", finished.text)
        // Der Name „[[S2]]“ ist kein übrig gebliebener Platzhalter und wird auch nicht noch einmal ersetzt.
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `Namen mit Zeilenumbruch, Steuerzeichen und Emoji ergeben gueltiges JSON`() {
        // Der Faktenbauer macht aus Zeilenumbrüchen Leerzeichen. Damit hier trotzdem welche ankommen, stehen die
        // Namen direkt in den Fakten: Zeilenumbruch, Tabulator, Steuerzeichen, Spielkarten-Emoji, Anführungszeichen.
        val joker = String(Character.toChars(0x1F0CF))
        val nasty = listOf("Zeile 1\nZeile 2", "Tab\tund " + Char(1), "Joker $joker", "\"\\\"")
        val players = facts.players.mapIndexed { index, player -> player.copy(name = nasty[index]) }
        val special = facts.copy(players = players)

        val real = SummaryPrompt.payload(special, useRealNames = true)
        assertEquals(nasty, parse(real.userText).texts("spieler"))
        assertTrue(real.userText.none { it < ' ' }, "Im JSON steht ein Steuerzeichen")
        // Das Emoji steht unverändert im JSON, als die zwei Hälften, aus denen es in Kotlin besteht.
        assertEquals(2, joker.length)
        assertTrue(real.userText.contains(joker))

        val hidden = SummaryPrompt.payload(special, useRealNames = false)
        assertEquals(expectedWithPlaceholders, hidden.userText)
        assertEquals(nasty, hidden.placeholders.values.toList())
        assertEquals(
            "${nasty[0]} gegen ${nasty[2]} und ${nasty[3]}",
            SummaryPrompt.finish("[[S1]] gegen [[S3]] und [[S4]]", hidden).text,
        )
    }

    @Test
    fun `Namen mit Markdown-Zeichen bleiben erhalten`() {
        val payload = PromptPayload(userText = "{}", placeholders = mapOf("[[S1]]" to "*Stern* #1 `x`"))
        assertEquals("*Stern* #1 `x` gewinnt", SummaryPrompt.finish("**[[S1]]** gewinnt", payload).text)
    }

    @Test
    fun `Markdown wird entfernt und die Namen werden eingesetzt`() {
        val answer = "# Schafkopf am 14.11.2026\n\n**Abendsieger:** [[S3]] mit `+11,40 €`\n- [[S2]] zahlt\n" +
            "* Punkt zwei\n## Unterzeile ##\n"
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals(
            "Schafkopf am 14.11.2026\n\nAbendsieger: Chris mit +11,40 €\nBert zahlt\nPunkt zwei\nUnterzeile ##",
            finished.text,
        )
        assertEquals(noWarnings, finished.warnings)
        assertEquals("", finished.model)
    }

    @Test
    fun `Sternchen und Backticks verschwinden ueberall, Rauten nur am Zeilenanfang`() {
        val finished = SummaryPrompt.finish("Das war *richtig* **stark** und `sauber`, Spiel #7 bleibt.", withPlaceholders)
        assertEquals("Das war richtig stark und sauber, Spiel #7 bleibt.", finished.text)
    }

    @Test
    fun `ein Hashtag am Zeilenanfang ist keine Ueberschrift`() {
        // Hinter den Rauten einer Überschrift steht Leerraum (oder gar nichts mehr), hinter denen eines Hashtags nicht.
        val answer = "# Schafkopf am 14.11.2026\nServus beinand!\n###\n#Schafkopf #Stammtisch"
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals("Schafkopf am 14.11.2026\nServus beinand!\n\n#Schafkopf #Stammtisch", finished.text)
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `ein Minus vor einem Betrag ist kein Listenstrich`() {
        // Einmal mit Bindestrich, einmal mit dem echten Minuszeichen (U+2212); danach zwei wirkliche Listenpunkte.
        val minus = Char(0x2212)
        val answer = "-13,40 € sind es für [[S2]]\n${minus}13,40 € stehen bei [[S2]]\n- und das ist ein Listenpunkt\n– der auch"
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals(
            "-13,40 € sind es für Bert\n${minus}13,40 € stehen bei Bert\nund das ist ein Listenpunkt\nder auch",
            finished.text,
        )
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `Code-Zaeune und Trennlinien verschwinden`() {
        val finished = SummaryPrompt.finish("```text\nServus\n```\n---\n***\nPfiat di", withPlaceholders)
        assertEquals("Servus\n\nPfiat di", finished.text)
    }

    @Test
    fun `mehrere Leerzeilen werden zu einer`() {
        val finished = SummaryPrompt.finish("\n\nA\n\n\n\nB\r\n\r\n\r\nC   \n   \n\t\nD\n\nE\n\n\n", withPlaceholders)
        assertEquals("A\n\nB\n\nC\n\nD\n\nE", finished.text)
    }

    @Test
    fun `Platzhalter werden auch in leicht abweichender Schreibweise erkannt`() {
        val finished = SummaryPrompt.finish("[[s1]] und [[ S2 ]] gegen [S3] und [[S4]]s Solo", withPlaceholders)
        assertEquals("Anna und Bert gegen Chris und Doras Solo", finished.text)
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `unbekannte Platzhalter bleiben stehen und werden gemeldet`() {
        val finished = SummaryPrompt.finish("[[S5]] war auch da, [[Wirt]] nicht, aber [[S1]].", withPlaceholders)
        assertEquals("[[S5]] war auch da, [[Wirt]] nicht, aber Anna.", finished.text)
        assertEquals(setOf(SummaryWarning.UNRESOLVED_PLACEHOLDER), finished.warnings)

        // Auch in einfachen Klammern: [S1] wird eingesetzt, also ist [S5] ein Platzhalter ohne Spieler.
        val single = SummaryPrompt.finish("[S5] war auch da, aber [S1].", withPlaceholders)
        assertEquals("[S5] war auch da, aber Anna.", single.text)
        assertEquals(setOf(SummaryWarning.UNRESOLVED_PLACEHOLDER), single.warnings)

        // Wurden die echten Namen gesendet, gibt es keine Platzhalter: Jeder im Text ist dann einer zu viel.
        val real = SummaryPrompt.finish("[[S1]] gewinnt.", withRealNames)
        assertEquals("[[S1]] gewinnt.", real.text)
        assertEquals(setOf(SummaryWarning.UNRESOLVED_PLACEHOLDER), real.warnings)
    }

    @Test
    fun `Betraege aus den Fakten sind in jeder Schreibweise bekannt`() {
        // In den Fakten stehen unter anderem +11,40 €, −13,40 €, 7,20 €, 2,00 € und 3,20 €.
        // Hier stehen sie ohne Leerzeichen, als „Euro“ und „EUR“, gekürzt und mit schmalem Leerzeichen (U+202F).
        val narrowSpace = Char(0x202F)
        val answer = "[[S3]]: 11,40€, [[S2]]: -13,40 Euro, [[S4]] zahlt 7,2 € und [[S2]] 2 EUR. Wert: 3,20${narrowSpace}€."
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals("Chris: 11,40€, Bert: -13,40 Euro, Dora zahlt 7,2 € und Bert 2 EUR. Wert: 3,20${narrowSpace}€.", finished.text)
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `ein Betrag, der nicht in den Fakten steht, wird gemeldet`() {
        val unknown = setOf(SummaryWarning.UNKNOWN_AMOUNT)

        assertEquals(unknown, SummaryPrompt.finish("[[S3]] gewinnt 12,00 €.", withPlaceholders).warnings)
        // Selbst gerechnet: 11,40 + 9,20 steht nirgends in den Fakten.
        assertEquals(unknown, SummaryPrompt.finish("Zusammen haben die zwei 20,60 € geholt.", withPlaceholders).warnings)
        assertEquals(unknown, SummaryPrompt.finish("Das waren 1.140,00 € für [[S3]].", withPlaceholders).warnings)
        assertEquals(unknown, SummaryPrompt.finish("Es ging um 5 Euro.", withPlaceholders).warnings)
        // Zahlen ohne Euro sind keine Beträge: Spielnummern, Anzahlen und das Datum stören nicht.
        assertEquals(noWarnings, SummaryPrompt.finish("Am 14.11.2026 gab es 12 Spiele, Nr. 7 war der Hammer.", withPlaceholders).warnings)
    }

    @Test
    fun `beide Warnungen koennen zugleich auftreten`() {
        val finished = SummaryPrompt.finish("[[S9]] gewinnt 99,00 €.", withPlaceholders)
        assertEquals(setOf(SummaryWarning.UNRESOLVED_PLACEHOLDER, SummaryWarning.UNKNOWN_AMOUNT), finished.warnings)
    }

    @Test
    fun `ein Text bis 1500 Zeichen bleibt ungekuerzt`() {
        val text = "x".repeat(1499) + "."
        assertEquals(1500, SummaryPrompt.MAX_TEXT_LENGTH)
        assertEquals(text, SummaryPrompt.finish(text, withPlaceholders).text)
    }

    @Test
    fun `ein zu langer Text wird an der letzten Satzgrenze gekuerzt`() {
        // Jeder Satz hat 30 Zeichen, mit Leerzeichen 31. 48 Sätze brauchen 48 × 31 − 1 = 1487 Zeichen,
        // 49 Sätze wären 1518 Zeichen und damit zu lang.
        val sentence = "Der Abend war lang und lustig."
        val answer = List(60) { sentence }.joinToString(" ")
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals(30, sentence.length)
        assertEquals(List(48) { sentence }.joinToString(" "), finished.text)
        assertEquals(1487, finished.text.length)
    }

    @Test
    fun `ein Satz darf mit einem schliessenden Anfuehrungszeichen enden`() {
        // 1480 Zeichen, dann ein Ausruf in Anführungszeichen (bis Zeichen 1490), dann ein langes Wort ohne Ende.
        val answer = "A".repeat(1480) + " „Gwonna!“ " + "B".repeat(100)
        assertEquals("A".repeat(1480) + " „Gwonna!“", SummaryPrompt.finish(answer, withPlaceholders).text)
    }

    @Test
    fun `ein Punkt hinter einer Zahl oder hinter Nr ist keine Satzgrenze`() {
        // 47 Sätze zu je 30 Zeichen brauchen mit Leerzeichen 47 × 31 − 1 = 1456 Zeichen. Der 48. Satz endet erst
        // hinter der Grenze von 1500 Zeichen; davor steht in ihm nur ein Punkt, der keinen Satz beendet.
        val sentence = "Der Abend war lang und lustig."
        val start = List(47) { sentence }.joinToString(" ")
        val number = "$start Der Hammer war ganz klar Spiel Nr. 7 mit dem Solo, bei dem alle am Tisch bis zum " +
            "letzten Stich gezittert haben."
        val ordinal = "$start Am Ende lag Anna auf dem 2. Platz und war damit an diesem langen Abend mehr als nur " +
            "zufrieden gewesen, wie man hört."

        assertEquals(1456, start.length)
        for (answer in listOf(number, ordinal)) {
            assertTrue(answer.length > SummaryPrompt.MAX_TEXT_LENGTH)
            // Der letzte Punkt vor der Grenze gehört zu „Nr.“ bzw. „2.“ und steht noch im 48. Satz.
            assertTrue(answer.lastIndexOf(". ", SummaryPrompt.MAX_TEXT_LENGTH) > start.length)
            assertEquals(start, SummaryPrompt.finish(answer, withPlaceholders).text)
        }
    }

    @Test
    fun `ohne Satzgrenze wird am letzten Wortende gekuerzt`() {
        // 400 Wörter zu je 4 Zeichen. In 1499 Zeichen passen 299 ganze Wörter (299 × 5 − 1 = 1494 Zeichen).
        val answer = List(400) { "wort" }.joinToString(" ")
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals(List(299) { "wort" }.joinToString(" ") + "…", finished.text)
        assertEquals(1495, finished.text.length)

        // Ein einziges Riesenwort wird hart gekürzt; mit den Auslassungspunkten sind es genau 1500 Zeichen.
        val giant = SummaryPrompt.finish("x".repeat(2000), withPlaceholders).text
        assertEquals("x".repeat(1499) + "…", giant)
    }

    @Test
    fun `Warnungen gelten nur fuer den Text, der uebrig bleibt`() {
        val sentence = "Der Abend war lang und lustig."
        val answer = List(60) { sentence }.joinToString(" ") + " [[S9]] zahlt 99,00 €."
        val finished = SummaryPrompt.finish(answer, withPlaceholders)

        assertEquals(List(48) { sentence }.joinToString(" "), finished.text)
        assertEquals(noWarnings, finished.warnings)
    }

    @Test
    fun `eine leere Antwort bleibt leer`() {
        for (answer in listOf("", "   ", "\n\n", "```\n```", "** **")) {
            val finished = SummaryPrompt.finish(answer, withPlaceholders)
            assertEquals("", finished.text)
            assertEquals(noWarnings, finished.warnings)
        }
    }
}
