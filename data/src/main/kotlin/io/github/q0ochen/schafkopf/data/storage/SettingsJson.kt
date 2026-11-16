package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.model.AppSettings

/**
 * Die Einstellungen der App als JSON-Text, in genau der Schreibweise der Datei `settings.json`. Gedacht für Stellen,
 * die die Einstellungen als Text ablegen, zum Beispiel in der Datenbank.
 */
object SettingsJson {

    fun encode(settings: AppSettings): String = StorageCodec.encodeSettings(settings)

    /**
     * @return die Einstellungen oder `null`, wenn sich der Text nicht lesen lässt (kaputtes JSON, unbekannte Werte
     *   oder Einstellungen einer neueren App).
     */
    fun decode(text: String): AppSettings? = try {
        StorageCodec.decodeSettings(text)
    } catch (e: UnreadableDataException) {
        null
    }
}
