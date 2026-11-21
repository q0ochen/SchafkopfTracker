package io.github.q0ochen.schafkopf.domain.round

/**
 * Namen von Runden (docs/ARCHITEKTUR.md, 8.7, „Eindeutige Namen“): bereinigen und vergleichen wie der Server, einen
 * freien Namen vorschlagen und gleichnamige Runden für Listen erkennen. Ein Konto hat keine zwei Runden desselben
 * Namens; durch Beitreten kann es trotzdem welche haben (angelegt haben sie dann verschiedene Leute).
 */
object RoundNames {
    /** Die Vorgabe im Feld „Name der Runde“. */
    const val DEFAULT = "Schafkopfrunde"

    /** Höchstens so viele Zeichen hat der Name einer Runde (nach dem Bereinigen). */
    const val MAX_LENGTH = 40

    /** Leer- und Steuerzeichen (ASCII), wie `[[:space:][:cntrl:]]` auf dem Server. */
    private val SPACE_OR_CONTROL = Regex("[\\x00-\\x20\\x7F]+")

    /**
     * Wie `private.clean_name` auf dem Server: Folgen von Leer- und Steuerzeichen (auch Tabulator und Zeilenumbruch)
     * werden zu einem Leerzeichen, Leerzeichen am Anfang und Ende entfallen.
     */
    fun clean(name: String): String = name.replace(SPACE_OR_CONTROL, " ").trim(' ')

    /** Ob der Name nach dem Bereinigen 1 bis [MAX_LENGTH] Zeichen hat (gezählt werden Zeichen wie auf dem Server). */
    fun valid(name: String): Boolean = clean(name).let { it.codePointCount(0, it.length) } in 1..MAX_LENGTH

    /** Ob zwei Namen für den Server derselbe sind: bereinigt und ohne Groß/Klein. */
    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /** Ob unter [rounds] schon eine Runde [name] heißt. */
    fun taken(name: String, rounds: List<RoundSummary>): Boolean = rounds.any { same(it.name, name) }

    /** [base] oder, wenn das Konto so eine Runde schon hat, die erste freie Fassung „‹base› 2“, „‹base› 3“ … */
    fun suggest(rounds: List<RoundSummary>, base: String = DEFAULT): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { !taken(it, rounds) }

    /** Die Runden, deren Name unter [rounds] mehr als einmal vorkommt; Listen nennen bei ihnen die Mitglieder. */
    fun duplicates(rounds: List<RoundSummary>): Set<RoundId> =
        rounds.groupBy { key(it.name) }.values.filter { it.size > 1 }.flatten().mapTo(LinkedHashSet()) { it.id }

    private fun key(name: String): String = clean(name).lowercase()
}
