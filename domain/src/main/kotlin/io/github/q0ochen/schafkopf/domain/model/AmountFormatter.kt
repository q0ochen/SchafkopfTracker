package io.github.q0ochen.schafkopf.domain.model

/**
 * Macht aus einem Betrag in ganzen Cent einen lesbaren Text.
 *
 * Die Fachlogik rechnet ausschließlich in Cent (1 Cent = 1 Punkt). Wie ein Betrag aussieht, entscheidet allein
 * die Oberfläche: Sie gibt diese Funktion überall dort mit, wo fertige Texte entstehen (Zusammenfassung des Abends).
 */
fun interface AmountFormatter {
    /**
     * @param cents Der Betrag in Cent bzw. Punkten.
     * @param asMoney `true`: als Euro-Betrag samt Währungszeichen. `false`: als Punktzahl ohne Einheit.
     * @param signed `true`: Positive Beträge bekommen ein Plus, damit Gewinn und Verlust nie nur an der Farbe hängen.
     */
    fun format(cents: Int, asMoney: Boolean, signed: Boolean): String
}
