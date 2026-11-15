package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import io.github.q0ochen.schafkopf.domain.stats.typo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Prüft jede Angabe der Zusammenfassung am von Hand durchgerechneten Beispielabend (Tabelle in [SampleEvening])
 * und danach die Grenzfälle an kleinen Abenden.
 */
class SummaryFactsBuilderTest {

    private val a = SampleEvening.a
    private val b = SampleEvening.b
    private val c = SampleEvening.c
    private val d = SampleEvening.d
    private val seating = listOf(a, b, c, d)
    private val zone = SampleEvening.zone
    private val now: Instant = Instant.parse("2026-11-14T19:00:00Z")
    private var nextId = 0

    private val anna = SummaryPlayer(a, 1, "Anna")
    private val bert = SummaryPlayer(b, 2, "Bert")
    private val chris = SummaryPlayer(c, 3, "Chris")
    private val dora = SummaryPlayer(d, 4, "Dora")

    private val facts = SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, zone)

    private fun id() = EntryId("f${nextId++}")

    /** Die Fakten eines kleinen Abends mit genau diesen Einträgen. */
    private fun build(vararg entries: EveningEntry, rules: RuleSet = RuleSet.sauspiel(), location: String = ""): SummaryFacts =
        SummaryFactsBuilder.build(
            EveningCalculator.compute(
                Evening(
                    id = EveningId("klein"),
                    startedAt = now,
                    location = location,
                    seating = seating,
                    firstDealer = a,
                    rules = rules,
                    entries = entries.toList(),
                ),
            ),
            SampleEvening.names,
            zone,
        )

    private fun rufspiel(declarer: PlayerId, partner: PlayerId, won: Boolean = true) =
        GameEntry(id(), now, GameType.RUFSPIEL, declarer = declarer, partner = partner, declarerWon = won)

    private fun solo(declarer: PlayerId, won: Boolean = true) =
        GameEntry(id(), now, GameType.FARBSOLO, declarer = declarer, declarerWon = won)

    @Test
    fun `Datum, Ort, Zahl der Spiele und Spieler`() {
        // Der Abend beginnt am 14.11.2026 um 18.30 Uhr Weltzeit, in Deutschland ist es dann 19.30 Uhr.
        assertEquals("14.11.2026", facts.date)
        assertEquals("Beim Wirt", facts.location)
        assertEquals(12, facts.gamesPlayed)
        assertEquals(true, facts.asMoney)
        assertEquals(listOf(anna, bert, chris, dora), facts.players)
    }

    @Test
    fun `Rangliste mit Platz und Endstand`() {
        val expected = listOf(
            SummaryStanding(chris, 1, typo("+11,40 €")),
            SummaryStanding(anna, 2, typo("+9,20 €")),
            SummaryStanding(dora, 3, typo("-7,20 €")),
            SummaryStanding(bert, 4, typo("-13,40 €")),
        )
        assertEquals(expected, facts.standings)
    }

    @Test
    fun `Abendsieger und Zahlmeister`() {
        assertEquals(SummaryPlace(listOf(chris), typo("+11,40 €")), facts.winner)
        assertEquals(SummaryPlace(listOf(bert), typo("-13,40 €")), facts.payer)
    }

    @Test
    fun `Lege-Pechvogel ist, wer nach eigenem Klopfen am meisten verloren hat`() {
        // Verlorene Spiele mit eigenem Klopfer: A Spiel 6 (−160), B Spiel 4 und 11 (−300 − 600 = −900),
        // C Spiel 2 (−60), D Spiel 10 (−160). Das gewonnene Spiel 10 von C wird nicht gegengerechnet.
        assertEquals(KlopferLoser(bert, 2, typo("9,00 €")), facts.klopferLoser)
    }

    @Test
    fun `Geschneiderte mit den meisten Faellen zuerst`() {
        // Schneider verloren: A in 5 und 9, B in 5 und 9, C in 2 und 5, D in 2 und 9. Schwarz verloren: A und D in 8.
        // A und D haben je drei Fälle, B und C je zwei; bei Gleichstand gilt die Sitzreihenfolge.
        val expected = listOf(
            SchneiderLoser(anna, schneider = 2, schwarz = 1),
            SchneiderLoser(dora, schneider = 2, schwarz = 1),
            SchneiderLoser(bert, schneider = 2, schwarz = 0),
            SchneiderLoser(chris, schneider = 2, schwarz = 0),
        )
        assertEquals(expected, facts.schneiderLosers)
    }

    @Test
    fun `Spiel des Abends ist das Spiel mit dem hoechsten Wert`() {
        // Spiel 7 und Spiel 8 sind beide 320 wert, das frühere gewinnt. Annas Tout bringt ihr 3 × 320 = 960.
        val expected = SummaryGame(
            number = 7,
            type = "Gras-Solo",
            declarer = anna,
            partner = null,
            won = true,
            value = typo("3,20 €"),
            declarerAmount = typo("+9,60 €"),
            specials = listOf("Tout", "3 Laufende", "Bockspiel"),
        )
        assertEquals(expected, facts.gameOfTheEvening)
    }

    @Test
    fun `Fuehrungswechsel und Bockrunden`() {
        assertEquals(3, facts.leadChanges)
        assertEquals(BockRounds(2, listOf(NixerCount(chris, 1), NixerCount(dora, 1))), facts.bockRounds)
    }

    @Test
    fun `Solo-Koenig - bei Gleichstand entscheidet die Sitzreihenfolge`() {
        // Gewonnene Einzelspiele als Ansager: A 3 und 7, C 9 und 12, D 5, B keines.
        assertEquals(SoloKing(anna, 2), facts.soloKing)
    }

    @Test
    fun `laengste Siegesserie`() {
        // C gewinnt die Spiele 8 bis 12, A die Spiele 1 bis 4.
        assertEquals(WinStreak(chris, 5), facts.winStreak)
    }

    @Test
    fun `wer zahlt wem`() {
        // Größter Schuldner an größten Gläubiger: B (−1340) zahlt 1140 an C, dann D (−720) 720 an A (920),
        // dann B die restlichen 200 an A.
        val expected = listOf(
            SummaryTransfer(bert, chris, typo("11,40 €")),
            SummaryTransfer(dora, anna, typo("7,20 €")),
            SummaryTransfer(bert, anna, typo("2,00 €")),
        )
        assertEquals(expected, facts.transfers)
    }

    @Test
    fun `leerer Abend hat nichts zu berichten`() {
        val empty = build()
        val expected = SummaryFacts(
            date = "14.11.2026",
            location = null,
            gamesPlayed = 0,
            asMoney = true,
            players = listOf(anna, bert, chris, dora),
            standings = listOf(anna, bert, chris, dora).map { SummaryStanding(it, 1, typo("0,00 €")) },
            winner = null,
            payer = null,
            klopferLoser = null,
            schneiderLosers = emptyList(),
            gameOfTheEvening = null,
            leadChanges = 0,
            bockRounds = null,
            soloKing = null,
            winStreak = null,
            transfers = emptyList(),
        )
        assertEquals(expected, empty)
    }

    @Test
    fun `stehen alle bei null, gibt es weder Sieger noch Zahlmeister noch Zahlungen`() {
        // A und B gewinnen ein Rufspiel, danach C und D: alle wieder bei 0.
        val even = build(rufspiel(a, b), rufspiel(c, d))

        assertEquals(2, even.gamesPlayed)
        assertNull(even.winner)
        assertNull(even.payer)
        assertEquals(emptyList<SummaryTransfer>(), even.transfers)
        assertEquals(listOf(1, 1, 1, 1), even.standings.map { it.rank })
        assertEquals(listOf(anna, bert, chris, dora), even.standings.map { it.player })
        assertEquals(0, even.leadChanges)
        // Beide Spiele sind 20 wert, das frühere ist das Spiel des Abends.
        assertEquals(1, even.gameOfTheEvening?.number)
        assertEquals(typo("0,20 €"), even.gameOfTheEvening?.value)
    }

    @Test
    fun `bei Gleichstand teilen sich mehrere den ersten oder den letzten Platz`() {
        // Ein Rufspiel: A und B je +20, C und D je −20.
        val tied = build(rufspiel(a, b))

        assertEquals(SummaryPlace(listOf(anna, bert), typo("+0,20 €")), tied.winner)
        assertEquals(SummaryPlace(listOf(chris, dora), typo("-0,20 €")), tied.payer)
        assertEquals(listOf(1, 1, 3, 3), tied.standings.map { it.rank })
        assertEquals(listOf(anna, bert, chris, dora), tied.standings.map { it.player })
        // Größter Schuldner an größten Gläubiger, bei Gleichstand in Sitzreihenfolge: C an A, D an B.
        assertEquals(
            listOf(SummaryTransfer(chris, anna, typo("0,20 €")), SummaryTransfer(dora, bert, typo("0,20 €"))),
            tied.transfers,
        )
    }

    @Test
    fun `Abend nur mit freier Buchung hat einen Stand, aber kein Spiel`() {
        val onlyBooking = build(ManualEntry(id(), now, mapOf(b to 300, c to -300), "Wette"))

        assertEquals(0, onlyBooking.gamesPlayed)
        assertNull(onlyBooking.gameOfTheEvening)
        assertEquals(SummaryPlace(listOf(bert), typo("+3,00 €")), onlyBooking.winner)
        assertEquals(SummaryPlace(listOf(chris), typo("-3,00 €")), onlyBooking.payer)
        assertEquals(listOf(SummaryTransfer(chris, bert, typo("3,00 €"))), onlyBooking.transfers)
    }

    @Test
    fun `das Datum richtet sich nach der Zeitzone`() {
        val lateEvening = EveningCalculator.compute(
            Evening(
                id = EveningId("spaet"),
                startedAt = Instant.parse("2026-01-05T23:30:00Z"),
                seating = seating,
                firstDealer = a,
                rules = RuleSet.sauspiel(),
            ),
        )
        // 23.30 Uhr Weltzeit ist in Deutschland schon 0.30 Uhr am nächsten Tag.
        assertEquals("06.01.2026", SummaryFactsBuilder.build(lateEvening, SampleEvening.names, ZoneId.of("Europe/Berlin")).date)
        assertEquals("05.01.2026", SummaryFactsBuilder.build(lateEvening, SampleEvening.names, ZoneOffset.UTC).date)
    }

    @Test
    fun `ohne Angabe einer Zeitzone gilt die des Geraets`() {
        assertEquals(
            SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names, ZoneId.systemDefault()),
            SummaryFactsBuilder.build(SampleEvening.state, SampleEvening.names),
        )
    }

    @Test
    fun `fehlende und leere Namen werden durch den Sitzplatz ersetzt`() {
        val names = mapOf(a to "  Anna  ", c to "   ", d to "Dora\nvom  Berg")
        val players = SummaryFactsBuilder.build(SampleEvening.state, names, zone).players
        assertEquals(
            listOf(
                SummaryPlayer(a, 1, "Anna"),
                SummaryPlayer(b, 2, "Spieler 2"),
                SummaryPlayer(c, 3, "Spieler 3"),
                // Zeilenumbrüche und doppelte Leerzeichen würden die Zeilen der Zusammenfassung zerreißen.
                SummaryPlayer(d, 4, "Dora vom Berg"),
            ),
            players,
        )
    }

    @Test
    fun `ein Ort nur aus Leerzeichen gilt als nicht eingetragen`() {
        assertNull(build(location = "   ").location)
        assertNull(build(location = " \n ").location)
        assertEquals("Stüberl", build(location = " Stüberl ").location)
        assertEquals("Beim Wirt in Haar", build(location = "Beim Wirt\nin  Haar").location)
    }

    @Test
    fun `wird um Punkte gespielt, stehen die Betraege ohne Euro da`() {
        val points = SummaryFactsBuilder.build(
            EveningCalculator.compute(SampleEvening.evening.copy(rules = RuleSet.sauspiel().copy(displayAsMoney = false))),
            SampleEvening.names,
            zone,
        )

        assertEquals(false, points.asMoney)
        assertEquals(listOf("+1140", "+920", typo("-720"), typo("-1340")), points.standings.map { it.balance })
        assertEquals("900", points.klopferLoser?.lostAmount)
        assertEquals("320", points.gameOfTheEvening?.value)
        assertEquals("+960", points.gameOfTheEvening?.declarerAmount)
        assertEquals(listOf("1140", "720", "200"), points.transfers.map { it.amount })
    }

    @Test
    fun `Rufspiel als Spiel des Abends nennt den Mitspieler und alle Besonderheiten`() {
        // (20 + 4 Laufende × 10 + Schwarz 20) × 4 (zwei Klopfer) × 4 (Retour) × 2 (Bock) = 80 × 32 = 2560.
        val game = GameEntry(
            id(), now, GameType.RUFSPIEL, suit = Suit.SCHELLEN, declarer = d, partner = a, declarerWon = false,
            level = ResultLevel.SCHWARZ, laufende = 4, klopfer = setOf(b, c),
            kontra = KontraLevel.RETOUR, kontraBy = b, retourBy = d,
        )
        val built = build(BockTriggerEntry(id(), now, BockReason.MANUAL), game)
        val expected = SummaryGame(
            number = 1,
            type = "Rufspiel",
            declarer = dora,
            partner = anna,
            won = false,
            value = typo("25,60 €"),
            declarerAmount = typo("-25,60 €"),
            specials = listOf("Schwarz", "4 Laufende", "2 Klopfer", "Kontra und Retour", "Bockspiel"),
        )
        assertEquals(expected, built.gameOfTheEvening)
        // Die Bockrunde wurde ohne Angabe ausgelöst, wer die 6 Nixer hatte.
        assertEquals(BockRounds(1, emptyList()), built.bockRounds)
    }

    @Test
    fun `Spielart und Besonderheiten der Einzelspiele`() {
        // Sie: (50 + 8 Laufende × 10) × 2 = 260; die Laufenden gehören zur Ansage und werden nicht eigens genannt.
        val sie = GameEntry(id(), now, GameType.FARBSOLO, declarer = a, declarerWon = true, announcement = Announcement.SIE)
        val sieGame = checkNotNull(build(sie).gameOfTheEvening)
        assertEquals("Farbsolo", sieGame.type)
        assertEquals(listOf("Sie"), sieGame.specials)
        assertEquals(typo("2,60 €"), sieGame.value)
        assertEquals(typo("+7,80 €"), sieGame.declarerAmount)

        // Wenz mit Schneider, einem Klopfer und Kontra: (50 + 10) × 2 × 2 = 240; ein Laufender zählt nicht.
        val wenz = GameEntry(
            id(), now, GameType.WENZ, declarer = b, declarerWon = false, level = ResultLevel.SCHNEIDER,
            laufende = 1, klopfer = setOf(c), kontra = KontraLevel.KONTRA,
        )
        val wenzGame = checkNotNull(build(wenz).gameOfTheEvening)
        assertEquals("Wenz", wenzGame.type)
        assertEquals(listOf("Schneider", "1 Klopfer", "Kontra"), wenzGame.specials)
        assertEquals(typo("2,40 €"), wenzGame.value)
        assertEquals(typo("-7,20 €"), wenzGame.declarerAmount)

        val geier = GameEntry(id(), now, GameType.GEIER, declarer = c, declarerWon = true)
        val geierGame = checkNotNull(build(geier).gameOfTheEvening)
        assertEquals("Geier", geierGame.type)
        assertEquals(emptyList<String>(), geierGame.specials)

        val named = listOf(Suit.EICHEL to "Eichel-Solo", Suit.GRAS to "Gras-Solo", Suit.HERZ to "Herz-Solo", Suit.SCHELLEN to "Schellen-Solo")
        for ((suit, label) in named) {
            val farbsolo = GameEntry(id(), now, GameType.FARBSOLO, suit = suit, declarer = a, declarerWon = true)
            assertEquals(label, build(farbsolo).gameOfTheEvening?.type)
        }
    }

    @Test
    fun `bei ueberlagerten Bockrunden steht der Faktor beim Bockspiel`() {
        val stacking = RuleSet.sauspiel().let { it.copy(bock = it.bock.copy(stacking = BockStacking.STACK)) }
        val built = build(
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = c),
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = c),
            rufspiel(a, b),
            rules = stacking,
        )
        // Zwei überlagerte Bockrunden vervierfachen: 20 × 4 = 80.
        assertEquals(listOf("Bockspiel ×4"), built.gameOfTheEvening?.specials)
        assertEquals(typo("0,80 €"), built.gameOfTheEvening?.value)
        assertEquals(BockRounds(2, listOf(NixerCount(chris, 2))), built.bockRounds)
    }

    @Test
    fun `sechs Nixer werden nur bei Bockrunden genannt, die deshalb ausgeloest wurden`() {
        // Die erste Bockrunde wurde von Hand ausgelöst; der Spieler, der dabeisteht, hatte keine 6 Nixer.
        val built = build(
            BockTriggerEntry(id(), now, BockReason.MANUAL, player = c),
            BockTriggerEntry(id(), now, BockReason.SECHS_NIXER, player = d),
            rufspiel(a, b),
        )
        assertEquals(BockRounds(2, listOf(NixerCount(dora, 1))), built.bockRounds)
    }

    @Test
    fun `einen Solo-Koenig gibt es erst ab zwei gewonnenen Einzelspielen`() {
        // Ein gewonnenes und ein verlorenes Solo reichen nicht, auch nicht zusammen mit gewonnenen Rufspielen.
        assertNull(build(solo(a), solo(a, won = false), rufspiel(a, b), rufspiel(a, b)).soloKing)
        // B gewinnt zwei Einzelspiele, D drei: D ist Solo-König, obwohl B weiter vorn sitzt.
        val wenz = GameEntry(id(), now, GameType.WENZ, declarer = d, declarerWon = true)
        val geier = GameEntry(id(), now, GameType.GEIER, declarer = d, declarerWon = true)
        assertEquals(SoloKing(dora, 3), build(solo(b), solo(d), solo(b), wenz, geier).soloKing)
    }

    @Test
    fun `eine Siegesserie wird erst ab drei Spielen erwaehnt`() {
        assertNull(build(rufspiel(a, b), rufspiel(a, b)).winStreak)
        // Drei gewonnene Rufspiele von A mit B: Beide haben eine Serie von drei, A sitzt weiter vorn.
        assertEquals(WinStreak(anna, 3), build(rufspiel(a, b), rufspiel(a, b), rufspiel(a, b)).winStreak)
    }

    @Test
    fun `einen Lege-Pechvogel gibt es nur nach einem verlorenen Spiel mit eigenem Klopfer`() {
        val wonWithKlopfer = GameEntry(
            id(), now, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true, klopfer = setOf(a),
        )
        assertNull(build(wonWithKlopfer).klopferLoser)
        assertNull(build(rufspiel(a, b)).klopferLoser)

        // C klopft und verliert 40, D klopft zweimal und verliert 2 × 40 = 80: D ist der Pechvogel.
        fun lost(klopfer: PlayerId) = GameEntry(
            id(), now, GameType.RUFSPIEL, declarer = a, partner = b, declarerWon = true, klopfer = setOf(klopfer),
        )
        assertEquals(KlopferLoser(dora, 2, typo("0,80 €")), build(lost(c), lost(d), lost(d)).klopferLoser)
        // Bei gleichem Verlust entscheidet die Sitzreihenfolge.
        assertEquals(KlopferLoser(chris, 1, typo("0,40 €")), build(lost(d), lost(c)).klopferLoser)
    }

    @Test
    fun `gehen die Salden nicht auf, gibt es keine Zahlungen und keinen Absturz`() {
        val broken = build(rufspiel(a, b), ManualEntry(id(), now, mapOf(a to 100), "falsch gebucht"))

        assertEquals(emptyList<SummaryTransfer>(), broken.transfers)
        assertEquals(SummaryPlace(listOf(anna), typo("+1,20 €")), broken.winner)
        assertEquals(1, broken.gamesPlayed)
    }
}
