package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.BYTE_ORDER_MARK
import io.github.q0ochen.schafkopf.domain.evening.EntryResult
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Export aller gespielten Spiele als Tabelle (CSV), wie sie ein deutsches Excel oder LibreOffice direkt öffnet:
 * Semikolon als Trenner, Dezimalkomma, UTF-8 mit Markierung am Dateianfang (BOM), Zeilenende CRLF.
 */
object CsvExport {

    private const val SEPARATOR = ";"
    private const val LINE_END = "\r\n"
    private const val SEATS = 4
    private const val UNKNOWN_PLAYER = "Unbekannt"

    /** Zeichen, mit denen eine Tabellenkalkulation eine Formel beginnen lässt. */
    private const val FORMULA_STARTS = "=+-@"

    /** Steuerzeichen, die eine Tabellenkalkulation am Anfang eines Feldes überliest, bevor sie nach einer Formel sucht. */
    private const val SKIPPED_STARTS = "\t\r\n"

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)

    private val OLDEST_FIRST: Comparator<Evening> = compareBy<Evening> { it.startedAt }.thenBy { it.id.value }

    private val HEADER = listOf(
        "Datum", "Ort", "Spiel-Nr.", "Spielart", "Farbe", "Ansager", "Mitspieler", "Ausgang", "Stufe", "Laufende",
        "Ansage", "Klopfer", "Kontra", "Bock-Stufe", "Spielwert",
        "Sitz 1", "Betrag 1", "Sitz 2", "Betrag 2", "Sitz 3", "Betrag 3", "Sitz 4", "Betrag 4",
    )

    /**
     * Kopfzeile plus eine Zeile je gespieltem Spiel, die Abende vom ältesten zum neuesten.
     *
     * Die Beträge rechnet [EveningCalculator] aus; sie stehen in Euro mit Dezimalkomma und ohne Währungszeichen
     * da (zum Beispiel `-1,20`). Texte, die eine Tabellenkalkulation als Formel lesen würde (Beginn mit `=`, `+`,
     * `-` oder `@`, auch hinter Leerzeichen, sowie Beginn mit Tabulator oder Zeilenumbruch), bekommen ein Hochkomma
     * vorangestellt; Zahlen nicht.
     *
     * @param players Liefert die Namen zu den Kennungen; ein unbekannter Spieler heißt „Unbekannt“.
     * @param zone Zeitzone, in der das Datum angegeben wird. Als Datum gilt der Tag, an dem der Abend begonnen hat,
     *   auch für Spiele nach Mitternacht.
     * @return der Text der Datei; das erste Zeichen ist die Markierung für UTF-8 (BOM).
     */
    fun games(evenings: List<Evening>, players: List<Player>, zone: ZoneId): String {
        val names = players.associate { it.id to it.name }
        val csv = StringBuilder(BYTE_ORDER_MARK)
        csv.appendRow(HEADER)
        for (evening in evenings.sortedWith(OLDEST_FIRST)) {
            // Ohne Sitzordnung lässt sich kein Abend durchrechnen.
            if (evening.seating.isEmpty()) continue
            val date = DATE.format(evening.startedAt.atZone(zone))
            for (result in EveningCalculator.compute(evening).results) {
                val game = result.entry as? GameEntry ?: continue
                csv.appendRow(row(date, evening, game, result, names))
            }
        }
        return csv.toString()
    }

    private fun row(
        date: String,
        evening: Evening,
        game: GameEntry,
        result: EntryResult,
        names: Map<PlayerId, String>,
    ): List<String> = buildList {
        val seating = evening.seating
        val klopfer = seating.filter { it in game.klopfer } + game.klopfer.filterNot { it in seating }

        add(date)
        add(text(evening.location))
        add(result.gameNumber?.toString().orEmpty())
        add(gameTypeName(game.type))
        add(game.suit?.let { suitName(it) }.orEmpty())
        add(text(names.nameOf(game.declarer)))
        add(game.partner?.let { text(names.nameOf(it)) }.orEmpty())
        add(if (game.declarerWon) "gewonnen" else "verloren")
        add(levelName(game.level))
        add(game.laufende.toString())
        add(announcementName(game.announcement))
        add(text(klopfer.joinToString(", ") { names.nameOf(it) }))
        add(kontraName(game.kontra))
        add(result.bockExponent.toString())
        add(result.breakdown?.let { euros(it.value) }.orEmpty())
        for (index in 0 until SEATS) {
            val seat = seating.getOrNull(index)
            add(seat?.let { text(names.nameOf(it)) }.orEmpty())
            add(seat?.let { euros(result.amounts[it] ?: 0) }.orEmpty())
        }
    }

    private fun Map<PlayerId, String>.nameOf(id: PlayerId): String = this[id] ?: UNKNOWN_PLAYER

    private fun gameTypeName(type: GameType): String = when (type) {
        GameType.RUFSPIEL -> "Rufspiel"
        GameType.UEBER_KREUZ -> "Über Kreuz"
        GameType.FARBSOLO -> "Farbsolo"
        GameType.WENZ -> "Wenz"
        GameType.GEIER -> "Geier"
    }

    private fun suitName(suit: Suit): String = when (suit) {
        Suit.EICHEL -> "Eichel"
        Suit.GRAS -> "Gras"
        Suit.HERZ -> "Herz"
        Suit.SCHELLEN -> "Schellen"
    }

    private fun levelName(level: ResultLevel): String = when (level) {
        ResultLevel.NORMAL -> "normal"
        ResultLevel.SCHNEIDER -> "Schneider"
        ResultLevel.SCHWARZ -> "Schwarz"
    }

    private fun announcementName(announcement: Announcement): String = when (announcement) {
        Announcement.NONE -> ""
        Announcement.TOUT -> "Tout"
        Announcement.SIE -> "Sie"
    }

    private fun kontraName(kontra: KontraLevel): String = when (kontra) {
        KontraLevel.NONE -> ""
        KontraLevel.KONTRA -> "Kontra"
        KontraLevel.RETOUR -> "Retour"
    }

    /** Cent als Euro mit Dezimalkomma, ohne Währungszeichen und mit einfachem Minuszeichen: -120 wird zu `-1,20`. */
    private fun euros(cents: Int): String {
        val absolute = abs(cents.toLong())
        val sign = if (cents < 0) "-" else ""
        return "$sign${absolute / 100},${(absolute % 100).toString().padStart(2, '0')}"
    }

    /** Ein Textfeld: erst gegen Formeln entschärft, dann bei Bedarf in Anführungszeichen gesetzt. */
    private fun text(value: String): String = quoted(defused(value))

    /**
     * Stellt ein Hochkomma voran, wenn der Text als Formel gelesen werden könnte. Manche Tabellenkalkulationen
     * schneiden Leerraum am Anfang ab; deshalb zählt das erste Zeichen, das kein Leerraum ist.
     */
    private fun defused(value: String): String {
        val first = value.firstOrNull() ?: return value
        val firstVisible = value.trimStart().firstOrNull()
        val looksLikeFormula = first in SKIPPED_STARTS || (firstVisible != null && firstVisible in FORMULA_STARTS)
        return if (looksLikeFormula) "'$value" else value
    }

    /** Nach RFC 4180: Felder mit Trenner, Anführungszeichen oder Zeilenumbruch stehen in Anführungszeichen. */
    private fun quoted(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.joinTo(this, SEPARATOR)
        append(LINE_END)
    }
}
