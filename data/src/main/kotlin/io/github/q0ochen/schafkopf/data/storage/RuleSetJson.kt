package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.rules.RuleSet

/**
 * Ein einzelnes Regelwerk als JSON-Text, in genau der Schreibweise, in der es auch in den Dateien der Abende und
 * in der Sicherung steht. Gedacht für Stellen, die ein Regelwerk als Text ablegen, zum Beispiel in einer Spalte
 * einer Datenbank.
 */
object RuleSetJson {

    fun encode(rules: RuleSet): String = StorageCodec.encodeRules(rules)

    /**
     * Liest ein Regelwerk. Unbekannte Felder werden überlesen; fehlt ein Wert, gilt der Wert der Vorlage
     * „Sauspiel 10-20-50 mit Bockrunde“.
     *
     * @return das Regelwerk oder `null`, wenn sich der Text nicht lesen lässt (kaputtes JSON oder ein
     *   unbekannter Wert, etwa eine Spielart aus einer neueren App).
     */
    fun decode(text: String): RuleSet? = try {
        StorageCodec.decodeRules(text)
    } catch (e: UnreadableDataException) {
        null
    }
}
