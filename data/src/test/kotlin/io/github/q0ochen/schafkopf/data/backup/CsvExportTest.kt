package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.TestData
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CsvExportTest {

    private val a = TestData.a
    private val b = TestData.b
    private val c = TestData.c
    private val d = TestData.d
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Instant = TestData.start

    /** Die unsichtbare Markierung für UTF-8 (BOM, Zeichen U+FEFF), hier über ihre Zeichennummer gebildet. */
    private val bom = Char(0xFEFF).toString()

    private val header = "Datum;Ort;Spiel-Nr.;Spielart;Farbe;Ansager;Mitspieler;Ausgang;Stufe;Laufende;Ansage;Klopfer;" +
        "Kontra;Bock-Stufe;Spielwert;Sitz 1;Betrag 1;Sitz 2;Betrag 2;Sitz 3;Betrag 3;Sitz 4;Betrag 4"

    private fun evening(
        vararg entries: EveningEntry,
        id: String = "abend",
        startedAt: Instant = now,
        location: String = "",
    ) = Evening(
        id = EveningId(id),
        startedAt = startedAt,
        location = location,
        seating = TestData.seating,
        firstDealer = a,
        rules = RuleSet.sauspiel(),
        entries = entries.toList(),
    )

    /** Rufspiel Anna mit Bertl, gewonnen, ohne Zusätze: Spielwert 20 Cent. */
    private fun rufspiel(id: String = "rufspiel") = GameEntry(
        id = EntryId(id),
        recordedAt = now,
        type = GameType.RUFSPIEL,
        declarer = a,
        partner = b,
        declarerWon = true,
    )

    /** Die Zeilen der Datei ohne die Markierung am Anfang und ohne die leere Zeile nach dem letzten Zeilenende. */
    private fun lines(csv: String): List<String> = csv.removePrefix(bom).split("\r\n").dropLast(1)

    @Test
    fun `ohne Abende besteht die Datei nur aus der Kopfzeile`() {
        val csv = CsvExport.games(emptyList(), TestData.players, berlin)

        assertEquals(bom + header + "\r\n", csv)
    }

    @Test
    fun `die Datei beginnt mit der Markierung fuer UTF-8 und jede Zeile endet mit CRLF`() {
        val csv = CsvExport.games(listOf(evening(rufspiel("e1"), rufspiel("e2"))), TestData.players, berlin)

        // Als UTF-8 geschrieben sind die ersten drei Bytes EF BB BF.
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            csv.toByteArray(Charsets.UTF_8).copyOfRange(0, 3),
        )
        assertEquals(1, csv.count { it == bom.single() })
        assertTrue(csv.endsWith("\r\n"))
        // Kopfzeile und zwei Spiele: drei Zeilenenden, und kein einzelnes \n oder \r ohne das andere.
        assertEquals(3, csv.split("\r\n").size - 1)
        assertEquals(3, csv.count { it == '\n' })
        assertEquals(3, csv.count { it == '\r' })
        assertEquals(header, lines(csv).first())
        assertTrue(lines(csv).all { line -> line.count { it == ';' } == 22 }, "jede Zeile hat 23 Spalten")
    }

    @Test
    fun `eine Zeile je gespieltem Spiel mit Betraegen in Euro, die Abende vom aeltesten zum neuesten`() {
        val november = evening(
            // Spiel 1: Rufspiel ohne Zusätze, Spielwert 20. Anna und Bertl je +0,20, Centa und Done je −0,20.
            rufspiel("e1").copy(suit = Suit.EICHEL),
            // Zusammengeworfen und Bockrunde ergeben keine Zeile; die nächsten vier Spiele sind Bockspiele.
            ThrownInEntry(EntryId("e2"), now),
            BockTriggerEntry(EntryId("e3"), now, BockReason.SECHS_NIXER, player = c),
            // Spiel 2: Herz-Solo von Centa verloren, Schneider, 3 Laufende, 2 Klopfer, Kontra, Bockspiel.
            // (50 + 3 × 10 + 10) = 90; × 4 (zwei Klopfer) × 2 (Kontra) × 2 (Bock) = 1440 Cent = 14,40.
            // Centa zahlt an alle drei: −3 × 14,40 = −43,20; die anderen bekommen je 14,40.
            GameEntry(
                id = EntryId("e4"),
                recordedAt = now,
                type = GameType.FARBSOLO,
                suit = Suit.HERZ,
                declarer = c,
                declarerWon = false,
                level = ResultLevel.SCHNEIDER,
                laufende = 3,
                klopfer = setOf(d, a),
                kontra = KontraLevel.KONTRA,
                kontraBy = a,
            ),
            // Spiel 3: Wenz Tout von Bertl gewonnen, 2 Laufende, Bockspiel. Schneider und Schwarz entfallen.
            // (50 + 2 × 10) = 70; × 2 (Tout) × 2 (Bock) = 280 Cent = 2,80. Bertl bekommt 3 × 2,80 = 8,40.
            GameEntry(
                id = EntryId("e5"),
                recordedAt = now,
                type = GameType.WENZ,
                declarer = b,
                declarerWon = true,
                laufende = 2,
                announcement = Announcement.TOUT,
            ),
            // Eine freie Buchung ergibt keine Zeile.
            ManualEntry(EntryId("e6"), now, mapOf(a to 100, d to -100), "Wette"),
            // Spiel 4: Geier von Done gewonnen, schwarz, mit Retour, von Hand aus der Bockrunde genommen.
            // (50 + 2 × 10) = 70; × 4 (Retour) = 280 Cent = 2,80. Done bekommt 3 × 2,80 = 8,40.
            GameEntry(
                id = EntryId("e7"),
                recordedAt = now,
                type = GameType.GEIER,
                declarer = d,
                declarerWon = true,
                level = ResultLevel.SCHWARZ,
                kontra = KontraLevel.RETOUR,
                bockOverride = BockOverride.FORCE_OFF,
            ),
            id = "november",
            startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            location = "Wirtshaus zur Post",
        )
        // Ein älterer Abend im Oktober (Sommerzeit: 18 Uhr UTC ist 20 Uhr in Berlin), ohne Ort.
        // Rufspiel Centa mit Done verloren, Spielwert 20: Anna und Bertl je +0,20, Centa und Done je −0,20.
        val october = evening(
            GameEntry(
                id = EntryId("o1"),
                recordedAt = now,
                type = GameType.RUFSPIEL,
                declarer = c,
                partner = d,
                declarerWon = false,
            ),
            id = "oktober",
            startedAt = Instant.parse("2026-10-03T18:00:00Z"),
        )

        val csv = CsvExport.games(listOf(november, october), TestData.players, berlin)

        val expected = listOf(
            header,
            "03.10.2026;;1;Rufspiel;;Centa;Done;verloren;normal;0;;;;0;0,20;" +
                "Anna;0,20;Bertl;0,20;Centa;-0,20;Done;-0,20",
            "14.11.2026;Wirtshaus zur Post;1;Rufspiel;Eichel;Anna;Bertl;gewonnen;normal;0;;;;0;0,20;" +
                "Anna;0,20;Bertl;0,20;Centa;-0,20;Done;-0,20",
            "14.11.2026;Wirtshaus zur Post;2;Farbsolo;Herz;Centa;;verloren;Schneider;3;;Anna, Done;Kontra;1;14,40;" +
                "Anna;14,40;Bertl;14,40;Centa;-43,20;Done;14,40",
            "14.11.2026;Wirtshaus zur Post;3;Wenz;;Bertl;;gewonnen;normal;2;Tout;;;1;2,80;" +
                "Anna;-2,80;Bertl;8,40;Centa;-2,80;Done;-2,80",
            "14.11.2026;Wirtshaus zur Post;4;Geier;;Done;;gewonnen;Schwarz;0;;;Retour;0;2,80;" +
                "Anna;-2,80;Bertl;-2,80;Centa;-2,80;Done;8,40",
        )
        assertEquals(expected, lines(csv))
        assertEquals(bom + expected.joinToString("") { it + "\r\n" }, csv)
    }

    @Test
    fun `Betraege stehen mit Dezimalkomma, zwei Nachkommastellen und ohne Waehrungszeichen da`() {
        // Farbsolo Sie von Anna (wie ein Tout mit 8 Laufenden): (50 + 8 × 10) × 2 = 260 Cent = 2,60 je Gegner,
        // Anna bekommt 3 × 2,60 = 7,80. Mit vier Klopfern (× 16): 4160 Cent = 41,60 und 124,80.
        val sie = GameEntry(
            id = EntryId("sie"),
            recordedAt = now,
            type = GameType.FARBSOLO,
            declarer = a,
            declarerWon = true,
            announcement = Announcement.SIE,
        )
        // Tarif 1-2-5 statt 10-20-50: Ein Rufspiel kostet dann 2 Cent = 0,02.
        val tiny = RuleSet.sauspiel().copy(unit = 1, prices = GameType.entries.associateWith { if (it.isSolo) 5 else 2 })
        val cheap = evening(rufspiel(), id = "billig", startedAt = now.plusSeconds(60)).copy(rules = tiny)

        val rows = lines(
            CsvExport.games(
                listOf(evening(sie, sie.copy(id = EntryId("sie-2"), klopfer = setOf(a, b, c, d))), cheap),
                TestData.players,
                berlin,
            ),
        )

        assertEquals(4, rows.size)
        assertEquals(
            "14.11.2026;;1;Farbsolo;;Anna;;gewonnen;normal;0;Sie;;;0;2,60;" +
                "Anna;7,80;Bertl;-2,60;Centa;-2,60;Done;-2,60",
            rows[1],
        )
        assertEquals(
            "14.11.2026;;2;Farbsolo;;Anna;;gewonnen;normal;0;Sie;Anna, Bertl, Centa, Done;;0;41,60;" +
                "Anna;124,80;Bertl;-41,60;Centa;-41,60;Done;-41,60",
            rows[2],
        )
        assertEquals(
            "14.11.2026;;1;Rufspiel;;Anna;Bertl;gewonnen;normal;0;;;;0;0,02;" +
                "Anna;0,02;Bertl;0,02;Centa;-0,02;Done;-0,02",
            rows[3],
        )
        // Hinter dem Datum steht in keiner Zeile mehr ein Punkt und nirgends ein Währungszeichen.
        for (row in rows.drop(1)) {
            val afterDate = row.substringAfter(';')
            assertFalse(afterDate.contains('.') || afterDate.contains('€'), row)
        }
    }

    @Test
    fun `Namen mit Semikolon, Anfuehrungszeichen oder Zeilenumbruch stehen nach RFC 4180 in Anfuehrungszeichen`() {
        val players = listOf(
            Player(a, "Huber; Sepp", 0),
            Player(b, "Hans \"Hansi\" Maier", 1),
            Player(c, "Centa", 2),
            Player(d, "Done", 3),
        )
        val game = rufspiel().copy(klopfer = setOf(a, c))

        val csv = CsvExport.games(listOf(evening(game, location = "Wirtshaus\nNebenzimmer; hinten")), players, berlin)

        // Rufspiel mit zwei Klopfern: 20 × 4 = 80 Cent = 0,80.
        assertEquals(
            bom + header + "\r\n" +
                "14.11.2026;\"Wirtshaus\nNebenzimmer; hinten\";1;Rufspiel;;\"Huber; Sepp\";\"Hans \"\"Hansi\"\" Maier\";" +
                "gewonnen;normal;0;;\"Huber; Sepp, Centa\";;0;0,80;" +
                "\"Huber; Sepp\";0,80;\"Hans \"\"Hansi\"\" Maier\";0,80;Centa;-0,80;Done;-0,80\r\n",
            csv,
        )
    }

    @Test
    fun `Texte, die wie eine Formel beginnen, werden mit einem Hochkomma entschaerft, Zahlen nicht`() {
        val players = listOf(
            Player(a, "=SUMME(A1:A9)", 0),
            Player(b, "+49 170 1234567", 1),
            Player(c, "-Centa", 2),
            Player(d, "@done", 3),
        )
        // Rufspiel von „-Centa“ mit „@done“ verloren: Die Verlierer zahlen je 0,20, die Zahl −0,20 bleibt eine Zahl.
        val game = GameEntry(
            id = EntryId("e1"),
            recordedAt = now,
            type = GameType.RUFSPIEL,
            declarer = c,
            partner = d,
            declarerWon = false,
            klopfer = emptySet(),
        )

        val csv = CsvExport.games(listOf(evening(game, location = "=HYPERLINK(\"http://example.invalid\";\"Klick\")")), players, berlin)

        assertEquals(
            bom + header + "\r\n" +
                "14.11.2026;\"'=HYPERLINK(\"\"http://example.invalid\"\";\"\"Klick\"\")\";1;Rufspiel;;'-Centa;'@done;" +
                "verloren;normal;0;;;;0;0,20;" +
                "'=SUMME(A1:A9);0,20;'+49 170 1234567;0,20;'-Centa;-0,20;'@done;-0,20\r\n",
            csv,
        )
    }

    @Test
    fun `auch Tabulator und Zeilenumbruch am Anfang werden entschaerft, harmlose Texte bleiben unveraendert`() {
        fun locationCell(location: String): String {
            val row = lines(CsvExport.games(listOf(evening(rufspiel(), location = location)), TestData.players, berlin))[1]
            return row.removePrefix("14.11.2026;").substringBefore(";1;Rufspiel;")
        }

        assertEquals("'\t=1+1", locationCell("\t=1+1"))
        assertEquals("\"'\r=1+1\"", locationCell("\r=1+1"))
        assertEquals("\"'\n=1+1\"", locationCell("\n=1+1"))
        assertEquals("Beim Wirt = gut + günstig - laut @ Dorf", locationCell("Beim Wirt = gut + günstig - laut @ Dorf"))
        assertEquals("1. Stock", locationCell("1. Stock"))
        assertEquals("", locationCell(""))

        // Klopfer stehen in einem gemeinsamen Feld; entschärft wird dessen Anfang.
        val players = listOf(Player(a, "=A", 0), Player(b, "Bertl", 1), Player(c, "=C", 2), Player(d, "Done", 3))
        val row = lines(CsvExport.games(listOf(evening(rufspiel().copy(klopfer = setOf(a, c)))), players, berlin))[1]
        assertTrue(row.contains(";'=A, =C;"), row)
    }

    @Test
    fun `eine Formel hinter fuehrenden Leerzeichen wird ebenfalls entschaerft, Betraege und harmlose Texte nicht`() {
        // Manche Tabellenkalkulationen schneiden Leerraum am Anfang eines Feldes ab und lesen den Rest als Formel.
        val players = listOf(
            Player(a, " =SUMME(A1:A9)", 0),
            Player(b, " Bertl", 1),
            Player(c, "  -Centa", 2),
            Player(d, "Done", 3),
        )
        // Rufspiel von Centa mit Done verloren, Spielwert 20 Cent: Anna und Bertl bekommen je 0,20, Centa und Done
        // zahlen je 0,20. Die Beträge −0,20 beginnen mit einem Minus und bleiben trotzdem Zahlen.
        val game = GameEntry(
            id = EntryId("e1"),
            recordedAt = now,
            type = GameType.RUFSPIEL,
            declarer = c,
            partner = d,
            declarerWon = false,
        )

        val csv = CsvExport.games(listOf(evening(game, location = "  @Wirt")), players, berlin)

        assertEquals(
            bom + header + "\r\n" +
                "14.11.2026;'  @Wirt;1;Rufspiel;;'  -Centa;Done;verloren;normal;0;;;;0;0,20;" +
                "' =SUMME(A1:A9);0,20; Bertl;0,20;'  -Centa;-0,20;Done;-0,20\r\n",
            csv,
        )

        fun locationCell(location: String): String {
            val row = lines(CsvExport.games(listOf(evening(rufspiel(), location = location)), TestData.players, berlin))[1]
            return row.removePrefix("14.11.2026;").substringBefore(";1;Rufspiel;")
        }

        // Auch das geschützte Leerzeichen (U+00A0) und eine Mischung aus Leerzeichen und Tabulator zählen als Leerraum.
        val noBreakSpace = Char(0x00A0)
        assertEquals("'$noBreakSpace=1+1", locationCell("$noBreakSpace=1+1"))
        assertEquals("' \t+1", locationCell(" \t+1"))
        // Harmlose Texte mit Leerzeichen am Anfang bleiben, wie sie sind.
        assertEquals(" Beim Wirt", locationCell(" Beim Wirt"))
        assertEquals(" 1. Stock", locationCell(" 1. Stock"))
        assertEquals("   ", locationCell("   "))
    }

    @Test
    fun `das Datum richtet sich nach der uebergebenen Zeitzone`() {
        // 23 Uhr 30 UTC ist in Berlin (Winterzeit, eine Stunde voraus) schon der nächste Tag.
        val late = evening(rufspiel(), startedAt = Instant.parse("2026-11-14T23:30:00Z"))

        fun date(zone: ZoneId): String = lines(CsvExport.games(listOf(late), TestData.players, zone))[1].substringBefore(';')

        assertEquals("15.11.2026", date(berlin))
        assertEquals("14.11.2026", date(ZoneOffset.UTC))
        assertEquals("14.11.2026", date(ZoneId.of("America/New_York")))
        // Einstellige Tage und Monate bekommen eine führende Null.
        val january = evening(rufspiel(), startedAt = Instant.parse("2027-01-05T12:00:00Z"))
        assertEquals("05.01.2027", lines(CsvExport.games(listOf(january), TestData.players, berlin))[1].substringBefore(';'))
    }

    @Test
    fun `unbekannte Spieler, nicht abrechenbare Spiele und Abende ohne Sitzordnung bringen den Export nicht zum Absturz`() {
        val stranger = PlayerId("fremd")
        // Der Ansager sitzt nicht am Tisch: Das Spiel lässt sich nicht abrechnen, der Spielwert bleibt leer.
        val broken = rufspiel("kaputt").copy(declarer = stranger)
        val withoutSeats = evening(rufspiel(), id = "leer", startedAt = now.plusSeconds(60)).copy(seating = emptyList())
        val threeSeats = evening(rufspiel(), id = "drei", startedAt = now.plusSeconds(120)).copy(seating = listOf(a, b, c))

        // Nur Anna ist als Spielerin bekannt.
        val rows = lines(CsvExport.games(listOf(evening(broken), withoutSeats, threeSeats), listOf(TestData.anna), berlin))

        assertEquals(
            listOf(
                header,
                "14.11.2026;;1;Rufspiel;;Unbekannt;Unbekannt;gewonnen;normal;0;;;;0;;" +
                    "Anna;0,00;Unbekannt;0,00;Unbekannt;0,00;Unbekannt;0,00",
                // Drei am Tisch (Anna und Bertl gewinnen je 0,20, Centa zahlt beides allein): Der vierte Sitz bleibt leer.
                "14.11.2026;;1;Rufspiel;;Anna;Unbekannt;gewonnen;normal;0;;;;0;0,20;" +
                    "Anna;0,20;Unbekannt;0,20;Unbekannt;-0,40;;",
            ),
            rows,
        )
    }
}
