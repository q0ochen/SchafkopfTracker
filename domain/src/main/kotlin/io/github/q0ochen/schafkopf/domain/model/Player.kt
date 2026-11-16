package io.github.q0ochen.schafkopf.domain.model

/**
 * Ein Spieler der Runde.
 *
 * @property colorIndex Feste Farbe des Spielers in Diagrammen (Index in die Palette der Oberfläche).
 * @property archived Archivierte Spieler erscheinen nicht mehr in der Auswahl, bleiben aber in alten Abenden erhalten.
 */
data class Player(
    val id: PlayerId,
    val name: String,
    val colorIndex: Int,
    val archived: Boolean = false,
) {
    companion object {
        const val MAX_NAME_LENGTH = 24

        /** Bereinigt eine Namenseingabe; `null`, wenn der Name leer oder zu lang ist. */
        fun cleanName(input: String): String? {
            val name = input.trim().replace(Regex("\\s+"), " ")
            return name.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
        }

        /** So viele Spielerfarben hat die Oberfläche; danach wiederholen sie sich. */
        const val COLOR_COUNT = 8

        /**
         * Die Farbe für einen neuen Spieler: die erste, die kein aktiver (nicht archivierter) Spieler trägt. Sind alle
         * vergeben, die nächste Nummer nach der höchsten. So bekommen auch nach dem Löschen eines Spielers zwei aktive
         * Spieler nicht dieselbe Farbe, solange es Farben gibt.
         */
        fun nextColorIndex(existing: List<Player>): Int {
            val taken = existing.filterNot { it.archived }.map { Math.floorMod(it.colorIndex, COLOR_COUNT) }.toSet()
            return (0 until COLOR_COUNT).firstOrNull { it !in taken } ?: ((existing.maxOfOrNull { it.colorIndex } ?: -1) + 1)
        }
    }
}
