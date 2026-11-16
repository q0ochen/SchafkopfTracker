package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.stats.RoundEvenings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Der Kommentar über alle Abende („ewige Tabelle“): Fakten, Anfrage an das Sprachmodell und Textbausteine.
 * Grundlage sind die beiden von Hand durchgerechneten Abende aus [RoundEvenings]; über beide Abende stehen
 * E +880, C +310, A +200, B +110 und D −1500 Cent.
 */
class HistorySummaryTest {

    private val a = RoundEvenings.a
    private val b = RoundEvenings.b
    private val c = RoundEvenings.c
    private val d = RoundEvenings.d
    private val e = RoundEvenings.e
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val names = mapOf(a to "Anna", b to "Bert", c to "Chris", d to "Dora", e to "Emil")
    private val evenings = listOf(RoundEvenings.secondEvening, RoundEvenings.evening)

    private val facts = HistoryFactsBuilder.build(evenings, names, TestAmounts, asMoney = true, zone = zone)

    // In der ewigen Tabelle nach Saldo: Emil, Chris, Anna, Bert, Dora.
    private val emil = SummaryPlayer(e, 1, "Emil")
    private val chris = SummaryPlayer(c, 2, "Chris")
    private val anna = SummaryPlayer(a, 3, "Anna")
    private val bert = SummaryPlayer(b, 4, "Bert")
    private val dora = SummaryPlayer(d, 5, "Dora")

    /** Euro-Beträge schreibt die Oberfläche mit geschütztem Leerzeichen vor dem Euro-Zeichen. */
    private fun eur(text: String): String = text.replace(" €", " €")

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun JsonObject.obj(name: String): JsonObject = getValue(name) as JsonObject

    private fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).content

    private fun JsonObject.texts(name: String): List<String> =
        (getValue(name) as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `Zeitraum, Abende, Spiele und die ewige Tabelle`() {
        // Beide Abende beginnen um 18.30 Uhr Weltzeit, in Deutschland 19.30 Uhr am selben Tag.
        assertEquals("07.11.2026", facts.firstDate)
        assertEquals("14.11.2026", facts.lastDate)
        assertEquals(2, facts.evenings)
        assertEquals(20, facts.gamesPlayed)
        assertTrue(facts.asMoney)
        assertEquals(listOf(emil, chris, anna, bert, dora), facts.players)
        assertEquals(
            listOf(
                SummaryStanding(emil, 1, eur("+8,80 €")),
                SummaryStanding(chris, 2, eur("+3,10 €")),
                SummaryStanding(anna, 3, eur("+2,00 €")),
                SummaryStanding(bert, 4, eur("+1,10 €")),
                SummaryStanding(dora, 5, eur("−15,00 €")),
            ),
            facts.standings,
        )
        assertEquals(SummaryPlace(listOf(emil), eur("+8,80 €")), facts.leader)
        assertEquals(SummaryPlace(listOf(dora), eur("−15,00 €")), facts.payer)
    }

    @Test
    fun `Abendsiege und die Auszeichnungen der Statistik`() {
        // Den ersten Abend gewinnt Chris (+1160), den zweiten Emil (+880); bei Gleichstand zählt die Tabelle.
        assertEquals(listOf(EveningWins(emil, 1), EveningWins(chris, 1)), facts.eveningWins)
        assertEquals(BockKing(chris, eur("+3,60 €"), 6), facts.bockKing)
        assertEquals(Announcements(bert, solos = 3, announced = 5), facts.soloSpecialist)
        assertEquals(Announcements(anna, solos = 0, announced = 4), facts.wallflower)
        assertEquals(FavoritePartners(anna, chris, won = 4, games = 5, balance = eur("+1,40 €")), facts.favoritePartners)
        assertEquals(listOf(KlopferRecord(chris, count = 3, won = 2, balance = eur("+7,40 €"))), facts.safeBets)
        assertEquals(listOf(KlopferRecord(dora, count = 4, won = 1, balance = eur("−10,40 €"))), facts.firestarters)
        // Chris gewinnt am ersten Abend die Spiele 7 bis 11 am Stück.
        assertEquals(WinStreak(chris, 5), facts.winStreak)
        assertEquals(2, facts.bockRounds)
    }

    @Test
    fun `das teuerste Spiel ist der Wenz von Dora am ersten Abend`() {
        // Spiel 5: Wenz, 2 Laufende, gelegt haben Anna und Dora: (50 + 20) × 4 = 280 je Zahlung, Dora zahlt 3 × 280.
        assertEquals(
            HistoryGame(
                date = "07.11.2026",
                game = SummaryGame(
                    number = 5,
                    type = "Wenz",
                    declarer = dora,
                    partner = null,
                    won = false,
                    value = eur("2,80 €"),
                    declarerAmount = eur("−8,40 €"),
                    specials = listOf("2 Laufende", "2 Klopfer"),
                ),
            ),
            facts.mostExpensiveGame,
        )
    }

    @Test
    fun `Reihenfolge und Dubletten der Abende spielen keine Rolle`() {
        val again = HistoryFactsBuilder.build(evenings.reversed() + evenings, names, TestAmounts, asMoney = true, zone = zone)
        assertEquals(facts, again)
    }

    @Test
    fun `um Punkte gespielt und ohne Namen`() {
        val points = HistoryFactsBuilder.build(evenings, emptyMap(), TestAmounts, asMoney = false, zone = zone)
        assertFalse(points.asMoney)
        assertEquals(listOf("Spieler 1", "Spieler 2", "Spieler 3", "Spieler 4", "Spieler 5"), points.players.map { it.name })
        assertEquals("+880", points.leader?.balance)
        assertEquals("−1500", points.payer?.balance)
        assertEquals("280", points.mostExpensiveGame?.game?.value)
    }

    @Test
    fun `ohne ein einziges Spiel gibt es nichts zu berichten`() {
        val empty = HistoryFactsBuilder.build(emptyList(), names, TestAmounts, asMoney = true, zone = zone)
        assertEquals(
            HistoryFacts(
                firstDate = null,
                lastDate = null,
                evenings = 0,
                gamesPlayed = 0,
                asMoney = true,
                players = emptyList(),
                standings = emptyList(),
                leader = null,
                payer = null,
                eveningWins = emptyList(),
                bockKing = null,
                soloSpecialist = null,
                wallflower = null,
                favoritePartners = null,
                safeBets = emptyList(),
                firestarters = emptyList(),
                mostExpensiveGame = null,
                winStreak = null,
                bockRounds = 0,
            ),
            empty,
        )
        assertEquals("Schafkopf-Bilanz\nNo koa oanzigs Spiel auf'm Zettel – die ewige Tabelle is no leer.", TemplateSummary.renderHistory(empty))
        assertEquals(
            "{\"schema\":1,\"abende\":0,\"spiele\":0,\"einheit\":\"Euro\"}",
            SummaryPrompt.historyPayload(empty, useRealNames = false).userText,
        )
    }

    /** Zwei Rufspiele mit vertauschten Rollen heben sich auf: alle stehen bei 0. */
    private fun evenEvening(): Evening {
        val start = Instant.parse("2026-11-21T18:30:00Z")
        return Evening(
            id = EveningId("gleich"),
            startedAt = start,
            seating = listOf(a, b, c, d),
            firstDealer = a,
            rules = RuleSet.standard(),
            entries = listOf(
                GameEntry(EntryId("x1"), start, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true),
                GameEntry(EntryId("x2"), start, GameType.RUFSPIEL, declarer = c, partner = d, declarerWon = true),
            ),
        )
    }

    @Test
    fun `stehen alle gleich, gibt es weder Spitzenreiter noch Zahlmeister`() {
        val even = HistoryFactsBuilder.build(listOf(evenEvening()), names, TestAmounts, asMoney = true, zone = zone)
        assertNull(even.leader)
        assertNull(even.payer)
        assertEquals("21.11.2026", even.firstDate)
        assertEquals("21.11.2026", even.lastDate)
        assertEquals(
            listOf(
                "Schafkopf-Bilanz vom 21.11.2026",
                "1 Abend, 2 Spiele um Geld.",
                "Über olle Abende gsehn stehn alle gleich.",
                "Teuerstes Spiel: am 21.11.2026 Nr. 1, Rufspiel von Anna mit Bert, gwonna – ${eur("0,10 €")} je Zahlung, " +
                    "${eur("+0,10 €")} für Anna.",
            ),
            TemplateSummary.renderHistory(even).lines(),
        )
    }

    @Test
    fun `teilen sich zwei die Spitze, stehen beide da`() {
        val tied = facts.copy(leader = SummaryPlace(listOf(emil, chris), eur("+8,80 €")))
        assertTrue(TemplateSummary.renderHistory(tied).lines().contains("An der Spitze stehn Emil und Chris mit je ${eur("+8,80 €")}."))
        val payers = facts.copy(payer = SummaryPlace(listOf(bert, dora), eur("−1,00 €")))
        assertTrue(
            TemplateSummary.renderHistory(payers).lines()
                .contains("Am meisten zahlt ham bisher Bert und Dora mit je ${eur("−1,00 €")}. Vergelt's Gott!"),
        )
    }

    @Test
    fun `die Bilanz aus Textbausteinen nennt jede Rubrik in einer Zeile`() {
        assertEquals(
            listOf(
                "Schafkopf-Bilanz vom 07.11.2026 bis 14.11.2026",
                "2 Abende, 20 Spiele um Geld.",
                "Spitzenreiter der ewigen Tabelle: Emil mit ${eur("+8,80 €")}.",
                "Die Runde finanziert hat bisher vor allem Dora: ${eur("−15,00 €")}. Dankschön!",
                "Abendsiege: Emil 1-mal, Chris 1-mal.",
                "Bock-König: Chris mit ${eur("+3,60 €")} aus 6 Bockspielen.",
                "Solo-Spezialist: Bert, 3 von 5 Ansagen warn Einzelspiele.",
                "Mauerblümchen: Anna ruft liaber, nur 0 von 4 Ansagen warn Einzelspiele.",
                "Lieblingspartner: Anna und Chris, 4 von 5 gemeinsamen Spielen gwonna, zamm ${eur("+1,40 €")}.",
                "Sichere Bank beim Legen: Chris, 3-mal glegt, 2-mal gwonna, unterm Strich ${eur("+7,40 €")}.",
                "Brandstifter beim Legen: Dora, 4-mal glegt, 1-mal gwonna, unterm Strich ${eur("−10,40 €")}. Liaber lassn!",
                "Teuerstes Spiel: am 07.11.2026 Nr. 5, Wenz von Dora (2 Laufende, 2 Klopfer), verlorn – " +
                    "${eur("2,80 €")} je Zahlung, ${eur("−8,40 €")} für Dora.",
                "Längste Siegesserie: Chris mit 5 Spielen am Stück.",
                "Bockrunden insgesamt: 2.",
                "Ewige Tabelle: 1. Emil ${eur("+8,80 €")}, 2. Chris ${eur("+3,10 €")}, 3. Anna ${eur("+2,00 €")}, " +
                    "4. Bert ${eur("+1,10 €")}, 5. Dora ${eur("−15,00 €")}.",
            ),
            TemplateSummary.renderHistory(facts).lines(),
        )
    }

    @Test
    fun `in Punkten bekommen die Betraege ihre Einheit`() {
        val points = HistoryFactsBuilder.build(evenings, names, TestAmounts, asMoney = false, zone = zone)
        val lines = TemplateSummary.renderHistory(points).lines()
        assertEquals("2 Abende, 20 Spiele um Punkte.", lines[1])
        assertEquals("Spitzenreiter der ewigen Tabelle: Emil mit +880 Punkten.", lines[2])
        assertTrue(lines.contains("Bock-König: Chris mit +360 Punkten aus 6 Bockspielen."))
        assertEquals("Die Runde finanziert hat bisher vor allem Dora: −1500 Punkte. Dankschön!", lines[3])
        // Ein einziges Bockspiel steht in der Einzahl.
        val one = points.copy(bockKing = BockKing(chris, "+1", 1))
        assertTrue(TemplateSummary.renderHistory(one).lines().contains("Bock-König: Chris mit +1 Punkt aus 1 Bockspiel."))
    }

    @Test
    fun `die Fakten als kompaktes JSON mit Platzhaltern in der Reihenfolge der Tabelle`() {
        val payload = SummaryPrompt.historyPayload(facts, useRealNames = false)
        assertEquals(
            mapOf("[[S1]]" to "Emil", "[[S2]]" to "Chris", "[[S3]]" to "Anna", "[[S4]]" to "Bert", "[[S5]]" to "Dora"),
            payload.placeholders,
        )
        for (name in names.values) assertFalse(name in payload.userText, "Der Name $name steht im JSON")
        assertFalse(payload.userText.contains('\n'))

        val json = parse(payload.userText)
        assertEquals("1", json.text("schema"))
        assertEquals("07.11.2026", json.obj("zeitraum").text("von"))
        assertEquals("14.11.2026", json.obj("zeitraum").text("bis"))
        assertEquals("2", json.text("abende"))
        assertEquals("20", json.text("spiele"))
        assertEquals("Euro", json.text("einheit"))
        assertEquals(listOf("[[S1]]", "[[S2]]", "[[S3]]", "[[S4]]", "[[S5]]"), json.texts("spieler"))
        assertEquals(eur("−15,00 €"), ((json.getValue("ewige_tabelle") as JsonArray).last() as JsonObject).text("stand"))
        assertEquals(listOf("[[S1]]"), json.obj("spitzenreiter").texts("spieler"))
        assertEquals(listOf("[[S5]]"), json.obj("zahlmeister").texts("spieler"))
        assertEquals("[[S2]]", json.obj("bock_koenig").text("spieler"))
        assertEquals("6", json.obj("bock_koenig").text("bockspiele"))
        assertEquals("[[S4]]", json.obj("solo_spezialist").text("spieler"))
        assertEquals("0", json.obj("mauerbluemchen").text("einzelspiele"))
        assertEquals(listOf("[[S3]]", "[[S2]]"), json.obj("lieblingspartner").texts("spieler"))
        assertEquals(
            eur("−10,40 €"),
            ((json.getValue("brandstifter_beim_legen") as JsonArray).single() as JsonObject).text("unterm_strich"),
        )
        val top = json.obj("teuerstes_spiel")
        assertEquals("07.11.2026", top.text("datum"))
        assertEquals("[[S5]]", top.text("ansager"))
        assertFalse("mitspieler" in top)
        assertEquals("false", top.text("gewonnen"))
        assertEquals(listOf("2 Laufende", "2 Klopfer"), top.texts("besonderheiten"))
        assertEquals("5", json.obj("laengste_siegesserie").text("spiele_am_stueck"))
        assertEquals("2", json.text("bockrunden"))
    }

    @Test
    fun `auf Wunsch stehen die echten Namen im JSON`() {
        val payload = SummaryPrompt.historyPayload(facts, useRealNames = true)
        assertEquals(emptyMap<String, String>(), payload.placeholders)
        assertEquals(listOf("Emil", "Chris", "Anna", "Bert", "Dora"), parse(payload.userText).texts("spieler"))
        assertFalse("[[S" in payload.userText)
    }

    @Test
    fun `die Anweisung nennt jeden Abschnitt des JSON`() {
        val prompt = SummaryPrompt.HISTORY_SYSTEM_PROMPT
        val sections = parse(SummaryPrompt.historyPayload(facts, useRealNames = false).userText).keys -
            setOf("schema", "abende", "spiele", "spieler")
        val mentioned = mapOf(
            "zeitraum" to "(zeitraum)",
            "einheit" to "einheit",
            "ewige_tabelle" to "ewige Tabelle",
            "spitzenreiter" to "(spitzenreiter)",
            "zahlmeister" to "(zahlmeister)",
            "abendsiege" to "Abendsiege",
            "bock_koenig" to "(bock_koenig)",
            "solo_spezialist" to "solo_spezialist",
            "mauerbluemchen" to "mauerbluemchen",
            "lieblingspartner" to "(lieblingspartner)",
            "sichere_bank_beim_legen" to "sichere_bank_beim_legen",
            "brandstifter_beim_legen" to "brandstifter_beim_legen",
            "teuerstes_spiel" to "(teuerstes_spiel)",
            "laengste_siegesserie" to "längste Siegesserie",
            "bockrunden" to "Bockrunden",
        )
        assertEquals(mentioned.keys, sections)
        for ((section, word) in mentioned) assertTrue(word in prompt, "Die Anweisung erwähnt nicht: $section")
        // Dieselben Grenzen wie beim Abend: nichts erfinden, nichts rechnen, Platzhalter übernehmen, kein Markdown.
        for (rule in listOf("Erfinde nichts", "rechne nicht", "[[S1]]", "ohne Markdown", "900 Zeichen", "Schafkopf-Bilanz")) {
            assertTrue(rule in prompt, "Die Anweisung nennt nicht: $rule")
        }
    }

    /** Ein Sprachmodell zum Testen: antwortet immer dasselbe und merkt sich, was es gefragt wurde. */
    private class FakeTextGenerator(private val answer: String) : TextGenerator {
        val systemPrompts = ArrayList<String>()
        val userTexts = ArrayList<String>()

        override suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText> {
            systemPrompts += systemPrompt
            userTexts += userText
            return LlmResult.Success(GeneratedText(answer, "test-modell"))
        }

        override suspend fun listModels(): LlmResult<List<String>> = LlmResult.Success(emptyList())
    }

    @Test
    fun `das Sprachmodell bekommt die Bilanz-Anweisung, die Antwort bekommt die Namen zurueck`() = runTest {
        val generator = FakeTextGenerator("**Schafkopf-Bilanz**\n[[S1]] führt, [[S5]] zahlt ${eur("15,00 €")}.")
        val result = SummaryService(generator).generateHistory(facts, useRealNames = false)

        assertEquals(listOf(SummaryPrompt.HISTORY_SYSTEM_PROMPT), generator.systemPrompts)
        assertEquals(listOf(SummaryPrompt.historyPayload(facts, useRealNames = false).userText), generator.userTexts)
        assertEquals(
            LlmResult.Success(FinishedSummary("Schafkopf-Bilanz\nEmil führt, Dora zahlt ${eur("15,00 €")}.", emptySet(), "test-modell")),
            result,
        )
    }
}
