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
    }
}
