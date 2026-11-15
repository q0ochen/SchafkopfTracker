package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import io.github.q0ochen.schafkopf.domain.stats.typo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Die Zusammenfassung ohne KI. Erwartete Texte stehen hier mit einfachem Leerzeichen und Bindestrich und werden
 * mit [typo] in die Schreibweise von `Money.format` gebracht.
 *
 * Welche Formulierung eine Rubrik nimmt, hängt von der Zahl der Spiele ab (Rest beim Teilen durch die Zahl der
 * Formulierungen, je Rubrik um 0, 1 oder 2 versetzt). Die erwarteten Texte unten sind danach von Hand ausgewählt.
 */
class TemplateSummaryTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d
    private val seating = listOf(a, b, c, d)
    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private var nextId = 0

    private val anna = SummaryPlayer(a, 1, "Anna")
    private val bert = SummaryPlayer(b, 2, "Bert")
    private val chris = SummaryPlayer(c, 3, "Chris")
    private val dora = SummaryPlayer(d, 4, "Dora")

    private val facts = SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, SampleEvening.zone)

    private fun id() = EntryId("t${nextId++}")

    /** Die Fakten eines kleinen Abends mit genau diesen Einträgen. */
    private fun build(vararg entries: EveningEntry, rules: RuleSet = RuleSet.sauspiel()): SummaryFacts =
        SummaryFactsBuilder.build(
            EveningCalculator.compute(
                Evening(
                    id = EveningId("klein"),
                    startedAt = now,
                    seating = seating,
                    firstDealer = a,
                    rules = rules,
                    entries = entries.toList(),
                ),
            ),
            SampleEvening.names,
            SampleEvening.zone,
        )

    /** Rufspiel, gewonnen, ohne Zusätze: Ansager und Mitspieler je +20, die beiden anderen je −20. */
    private fun rufspiel(declarer: PlayerId, partner: PlayerId) =
        GameEntry(id(), now, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = true)

    /** Farbsolo ohne Zusätze: der Ansager ±150, jeder Gegenspieler ∓50. */
    private fun solo(declarer: PlayerId, won: Boolean = true) =
        GameEntry(id(), now, GameType.FARBSOLO, declarer = declarer, declarerWon = won)

    private fun lines(text: String): List<String> = text.split("\n")

    @Test
    fun `Zusammenfassung des Beispielabends`() {
        val expected = typo(
            """
            Schafkopf am 14.11.2026, Beim Wirt
            Gspuit: 12 Spiele um Geld. Die Führung hat 3-mal gwechselt.
            Gwonna hat heid Chris mit +11,40 €.
            Bert hat heid die Runde gsponsert: -13,40 €.
            Beim Legen verzockt: Bert, 2-mal glegt und verlorn, macht 9,00 €.
            Im Schneider gsessn: Anna 3-mal (1-mal schwarz), Dora 3-mal (1-mal schwarz), Bert 2-mal, Chris 2-mal.
            Spiel des Abends: Nr. 7, Gras-Solo von Anna (Tout, 3 Laufende, Bockspiel), gwonna: 3,20 € je Zahlung, für Anna +9,60 €.
            2-mal Bockrunde (6 Nixer: Chris, Dora).
            Solo-König: Anna mit 2 gwonnenen Einzelspielen.
            Chris war ned zum Bremsen: 5 Siege hintereinander.
            Endstand: 1. Chris +11,40 €, 2. Anna +9,20 €, 3. Dora -7,20 €, 4. Bert -13,40 €.
            Wer zahlt wem: Bert 11,40 € an Chris, Dora 7,20 € an Anna, Bert 2,00 € an Anna.
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(facts))
    }

    @Test
    fun `die Formulierungen wechseln mit der Zahl der Spiele`() {
        // Dieselben Fakten, nur mit 13 statt 12 Spielen: Jede Rubrik nimmt ihre nächste Formulierung.
        val thirteen = facts.copy(gamesPlayed = 13)
        val expected = typo(
            """
            Schafkopf am 14.11.2026, Beim Wirt
            Auf'm Zettel: 13 Spiele, ganga is um Geld. Die Führung hat 3-mal gwechselt.
            Chris hat ois abgramt: +11,40 €.
            Zahlmeister: Bert mit -13,40 €. Vergelt's Gott fürs Finanzieren!
            Legen will glernt sei: Bert hat 2-mal glegt und verlorn, des hat 9,00 € kost.
            Geschneidert: Anna 3-mal (1-mal schwarz), Dora 3-mal (1-mal schwarz), Bert 2-mal, Chris 2-mal.
            Der Hammer war Spiel Nr. 7: Gras-Solo von Anna (Tout, 3 Laufende, Bockspiel), gwonna – 3,20 € je Zahlung, +9,60 € für Anna.
            Bockrunden: 2 (6 Nixer: Chris, Dora).
            Alloa am stärksten: Anna, 2 Einzelspiele gwonna.
            Längste Siegesserie: Chris mit 5 Spielen am Stück.
            Endstand: 1. Chris +11,40 €, 2. Anna +9,20 €, 3. Dora -7,20 €, 4. Bert -13,40 €.
            Wer zahlt wem: Bert 11,40 € an Chris, Dora 7,20 € an Anna, Bert 2,00 € an Anna.
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(thirteen))

        // Mit 14 Spielen sind die Rubriken mit drei Formulierungen bei ihrer dritten angekommen.
        val fourteen = lines(TemplateSummary.render(facts.copy(gamesPlayed = 14)))
        assertEquals("Bilanz vom Abend: 14 Spiele um Geld. Die Führung hat 3-mal gwechselt.", fourteen[1])
        assertEquals(typo("Abendsieger: Chris mit +11,40 €. Respekt!"), fourteen[2])
        assertEquals(typo("Den Abend finanziert hat Bert: -13,40 €. Dankschön!"), fourteen[3])
    }

    @Test
    fun `dieselben Fakten ergeben immer denselben Text`() {
        val again = SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, SampleEvening.zone)
        assertEquals(TemplateSummary.render(facts), TemplateSummary.render(again))
    }

    @Test
    fun `jede Zusammenfassung hat sechs bis zwoelf Zeilen`() {
        val full = lines(TemplateSummary.render(facts))
        assertEquals(12, full.size)

        // Ein einziges Rufspiel: Kopf, Spiele, Sieger, Zahlmeister, Spiel des Abends, Endstand, Zahlungen.
        val oneGame = lines(TemplateSummary.render(build(rufspiel(a, b))))
        assertEquals(7, oneGame.size)

        // Zwei Rufspiele, die sich aufheben: Kopf, Spiele, Gleichstand, Spiel des Abends, Endstand, quitt.
        val even = lines(TemplateSummary.render(build(rufspiel(a, b), rufspiel(c, d))))
        assertEquals(6, even.size)

        // Ohne Spiel: Kopf, kein Spiel, wer am Tisch saß, Stand, Zahlungen, Schlusszeile.
        val empty = lines(TemplateSummary.render(build()))
        assertEquals(6, empty.size)
        val onlyBooking = lines(TemplateSummary.render(build(ManualEntry(id(), now, mapOf(b to 300, c to -300), "Wette"))))
        assertEquals(6, onlyBooking.size)

        for (text in listOf(full, oneGame, even, empty, onlyBooking)) {
            assertTrue(text.all { it.isNotBlank() })
        }
    }

    @Test
    fun `Abend ohne Spiel bekommt eine kurze Fassung`() {
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Heid is koa oanzigs Spiel zammganga.
            Am Tisch: Anna, Bert, Chris und Dora.
            Stand: alle bei 0,00 €.
            Zahln muaß koana, alle san quitt.
            Nächstes Moi werd wieder gscheid kartelt!
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(build()))
    }

    @Test
    fun `Abend ohne Spiel nennt Stand und Zahlungen aus freien Buchungen`() {
        val onlyBooking = build(ManualEntry(id(), now, mapOf(b to 300, c to -300), "Wette"))
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Heid is koa oanzigs Spiel zammganga.
            Am Tisch: Anna, Bert, Chris und Dora.
            Stand: 1. Bert +3,00 €, 2. Anna 0,00 €, 2. Dora 0,00 €, 4. Chris -3,00 €.
            Wer zahlt wem: Chris 3,00 € an Bert.
            Nächstes Moi werd wieder gscheid kartelt!
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(onlyBooking))
    }

    @Test
    fun `stehen alle gleich, gibt es keinen Sieger und niemand muss zahlen`() {
        // A und B gewinnen ein Rufspiel, danach C und D: alle wieder bei 0.
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Bilanz vom Abend: 2 Spiele um Geld.
            Koa Sieger, koa Zahlmeister: alle stehn gleich.
            Spiel des Abends: Nr. 1, Rufspiel von Anna mit Bert, gwonna: 0,20 € je Zahlung, für Anna +0,20 €.
            Endstand: alle bei 0,00 €.
            Zahln muaß koana, alle san quitt.
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(build(rufspiel(a, b), rufspiel(c, d))))
    }

    @Test
    fun `geteilte Plaetze werden gemeinsam genannt`() {
        // Ein Rufspiel: A und B je +20, C und D je −20.
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Auf'm Zettel: 1 Spiel, ganga is um Geld.
            Den Sieg teilen sich Anna und Bert mit je +0,20 €.
            Finanziert ham den Abend Chris und Dora mit je -0,20 €. Vergelt's Gott!
            Das oanzige Spiel: Rufspiel von Anna mit Bert, gwonna – 0,20 € je Zahlung, +0,20 € für Anna.
            Endstand: 1. Anna +0,20 €, 1. Bert +0,20 €, 3. Chris -0,20 €, 3. Dora -0,20 €.
            Wer zahlt wem: Chris 0,20 € an Anna, Dora 0,20 € an Bert.
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(build(rufspiel(a, b))))

        // Ein verlorenes Solo von A: die drei anderen stehen mit je +50 gemeinsam vorn, A zahlt 150.
        val lostSolo = lines(TemplateSummary.render(build(solo(a, won = false))))
        assertEquals(typo("Den Sieg teilen sich Bert, Chris und Dora mit je +0,50 €."), lostSolo[2])
        assertEquals(typo("Zahlmeister: Anna mit -1,50 €. Vergelt's Gott fürs Finanzieren!"), lostSolo[3])
    }

    @Test
    fun `wird um Punkte gespielt, steht die Einheit hinter jeder Zahl`() {
        // Ein gewonnenes Solo von A: A +150, die anderen je −50.
        val points = build(solo(a), rules = RuleSet.sauspiel().copy(displayAsMoney = false))
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Auf'm Zettel: 1 Spiel, ganga is um Punkte.
            Anna hat ois abgramt: +150 Punkte.
            Finanziert ham den Abend Bert, Chris und Dora mit je -50 Punkten. Vergelt's Gott!
            Das oanzige Spiel: Farbsolo von Anna, gwonna – 50 Punkte je Zahlung, +150 Punkte für Anna.
            Endstand: 1. Anna +150 Punkte, 2. Bert -50 Punkte, 2. Chris -50 Punkte, 2. Dora -50 Punkte.
            Wer zahlt wem: Bert 50 Punkte an Anna, Chris 50 Punkte an Anna, Dora 50 Punkte an Anna.
            """.trimIndent(),
        )
        assertEquals(expected, TemplateSummary.render(points))

        // Ein einzelner Punkt steht in der Einzahl.
        val onePoint = points.copy(transfers = listOf(SummaryTransfer(bert, anna, "1")))
        assertEquals("Wer zahlt wem: Bert 1 Punkt an Anna.", lines(TemplateSummary.render(onePoint)).last())
    }

    @Test
    fun `hinter mit und bei stehen Punkte im Wemfall`() {
        val pointRules = RuleSet.sauspiel().copy(displayAsMoney = false)

        // Ein gewonnenes Solo von A (A +150, die anderen je −50); die Zahl der Spiele wählt die Formulierung.
        val won = build(solo(a), rules = pointRules)
        assertEquals("Abendsieger: Anna mit +150 Punkten. Respekt!", lines(TemplateSummary.render(won.copy(gamesPlayed = 2)))[2])
        assertEquals("Gwonna hat heid Anna mit +150 Punkten.", lines(TemplateSummary.render(won.copy(gamesPlayed = 3)))[2])

        // Ein verlorenes Solo von A: die drei anderen je +50, A −150.
        val lost = lines(TemplateSummary.render(build(solo(a, won = false), rules = pointRules)))
        assertEquals("Den Sieg teilen sich Bert, Chris und Dora mit je +50 Punkten.", lost[2])
        assertEquals(typo("Zahlmeister: Anna mit -150 Punkten. Vergelt's Gott fürs Finanzieren!"), lost[3])

        // Zwei Rufspiele, die sich aufheben: alle stehen bei 0.
        val even = lines(TemplateSummary.render(build(rufspiel(a, b), rufspiel(c, d), rules = pointRules)))
        assertEquals("Endstand: alle bei 0 Punkten.", even[4])

        // Ein einzelner Punkt heißt auch im Wemfall „Punkt“.
        val onePoint = won.copy(winner = SummaryPlace(listOf(anna, bert), "+1"))
        assertEquals("Den Sieg teilen sich Anna und Bert mit je +1 Punkt.", lines(TemplateSummary.render(onePoint))[2])
    }

    @Test
    fun `ein Abend mit nur einem Spiel kuert kein Spiel des Abends`() {
        // Ein gewonnenes Solo von A: A +150, die anderen je −50.
        val expected = typo(
            """
            Schafkopf am 14.11.2026
            Auf'm Zettel: 1 Spiel, ganga is um Geld.
            Anna hat ois abgramt: +1,50 €.
            Finanziert ham den Abend Bert, Chris und Dora mit je -0,50 €. Vergelt's Gott!
            Das oanzige Spiel: Farbsolo von Anna, gwonna – 0,50 € je Zahlung, +1,50 € für Anna.
            Endstand: 1. Anna +1,50 €, 2. Bert -0,50 €, 2. Chris -0,50 €, 2. Dora -0,50 €.
            Wer zahlt wem: Bert 0,50 € an Anna, Chris 0,50 € an Anna, Dora 0,50 € an Anna.
            """.trimIndent(),
        )
        val oneGame = TemplateSummary.render(build(solo(a)))
        assertEquals(expected, oneGame)
        assertFalse(oneGame.contains("Hammer") || oneGame.contains("Spiel des Abends"))

        // Ab zwei Spielen gibt es wieder etwas zu küren: Das Solo (50) ist mehr wert als das Rufspiel (20).
        val twoGames = lines(TemplateSummary.render(build(solo(a), rufspiel(a, b))))
        assertEquals(typo("Spiel des Abends: Nr. 1, Farbsolo von Anna, gwonna: 0,50 € je Zahlung, für Anna +1,50 €."), twoGames[4])
    }

    @Test
    fun `beim Legen werden nur die verlorenen Spiele genannt`() {
        // C legt dreimal: Zwei Rufspiele von A mit B verliert C als Gegenspieler (je 20 × 2 = 40),
        // das dritte gewinnt C selbst. Verzockt hat C also 2-mal, zusammen 80; gelegt hat C aber 3-mal.
        fun withKlopfer(declarer: PlayerId, partner: PlayerId) = GameEntry(
            id(), now, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = true, klopfer = setOf(c),
        )
        val threeGames = build(withKlopfer(a, b), withKlopfer(a, b), withKlopfer(c, d))
        assertEquals(KlopferLoser(chris, 2, typo("0,80 €")), threeGames.klopferLoser)
        assertEquals(
            typo("Legen will glernt sei: Chris hat 2-mal glegt und verlorn, des hat 0,80 € kost."),
            lines(TemplateSummary.render(threeGames))[4],
        )
        // Mit vier Spielen kommt die andere Formulierung dran.
        val fourGames = build(withKlopfer(a, b), withKlopfer(a, b), withKlopfer(c, d), rufspiel(a, b))
        assertEquals(
            typo("Beim Legen verzockt: Chris, 2-mal glegt und verlorn, macht 0,80 €."),
            lines(TemplateSummary.render(fourGames))[4],
        )
    }

    @Test
    fun `nur Namen und Betraege aus den Fakten kommen im Text vor`() {
        val text = TemplateSummary.render(facts)
        val known = buildSet {
            addAll(facts.standings.map { it.balance })
            facts.winner?.let { add(it.balance) }
            facts.payer?.let { add(it.balance) }
            facts.klopferLoser?.let { add(it.lostAmount) }
            facts.gameOfTheEvening?.let {
                add(it.value)
                add(it.declarerAmount)
            }
            addAll(facts.transfers.map { it.amount })
        }
        val amounts = Regex("""[+\x{2212}]?\d+,\d{2}\x{A0}€""").findAll(text).map { it.value }.toList()

        // Sieger 1, Zahlmeister 1, Legen 1, Spiel des Abends 2, Endstand 4, Zahlungen 3.
        assertEquals(12, amounts.size)
        for (amount in amounts) assertTrue(amount in known, "Unbekannter Betrag im Text: $amount")
        for (player in facts.players) assertTrue(player.name in text)
    }

    @Test
    fun `der Text enthaelt kein Markdown`() {
        val texts = listOf(
            TemplateSummary.render(facts),
            TemplateSummary.render(facts.copy(gamesPlayed = 13)),
            TemplateSummary.render(build()),
            TemplateSummary.render(build(rufspiel(a, b), rufspiel(c, d))),
        )
        for (text in texts) {
            assertFalse(text.contains('*'))
            assertFalse(text.contains('#'))
            assertFalse(text.contains('`'))
            assertFalse(text.contains('_'))
            assertTrue(lines(text).none { it.startsWith("- ") || it.startsWith(" ") })
            assertTrue(text.startsWith("Schafkopf am 14.11.2026"))
        }
    }

    @Test
    fun `leere Rubriken entfallen`() {
        val text = TemplateSummary.render(build(rufspiel(a, b)))
        for (rubric in listOf("Legen", "Schneider", "Geschneidert", "Bockrunde", "Solo", "Siegesserie", "Bremsen", "Führung")) {
            assertFalse(text.contains(rubric), "Rubrik ohne Fakten im Text: $rubric")
        }
    }

    @Test
    fun `Einzelheiten der Rubriken`() {
        val custom = facts.copy(
            schneiderLosers = listOf(
                SchneiderLoser(anna, schneider = 0, schwarz = 1),
                SchneiderLoser(bert, schneider = 1, schwarz = 0),
            ),
            gameOfTheEvening = SummaryGame(
                number = 3,
                type = "Rufspiel",
                declarer = dora,
                partner = anna,
                won = false,
                value = typo("25,60 €"),
                declarerAmount = typo("-25,60 €"),
                specials = emptyList(),
            ),
            bockRounds = BockRounds(1, listOf(NixerCount(chris, 2))),
        )
        val text = lines(TemplateSummary.render(custom))

        assertEquals("Im Schneider gsessn: Anna 1-mal schwarz, Bert 1-mal.", text[5])
        assertEquals(
            typo("Spiel des Abends: Nr. 3, Rufspiel von Dora mit Anna, verlorn: 25,60 € je Zahlung, für Dora -25,60 €."),
            text[6],
        )
        assertEquals("1-mal Bockrunde (6 Nixer: Chris 2-mal).", text[7])

        val withoutNixer = lines(TemplateSummary.render(custom.copy(bockRounds = BockRounds(3, emptyList()))))
        assertEquals("3-mal Bockrunde.", withoutNixer[7])
    }

    @Test
    fun `fehlt ein Sieger trotz ungleicher Staende, wird nichts ueber Zahlungen behauptet`() {
        // So sehen die Fakten aus, wenn die Salden wegen einer fehlerhaften freien Buchung nicht aufgehen:
        // Es gibt einen Sieger, aber keine Zahlungen. „Alle san quitt“ wäre dann falsch.
        val broken = build(rufspiel(a, b), ManualEntry(id(), now, mapOf(a to 100), "falsch gebucht"))
        val text = lines(TemplateSummary.render(broken))

        assertEquals(6, text.size)
        assertTrue(text.last().startsWith("Endstand: "))
        assertTrue(text.none { it.contains("quitt") || it.startsWith("Wer zahlt wem") })
    }
}
