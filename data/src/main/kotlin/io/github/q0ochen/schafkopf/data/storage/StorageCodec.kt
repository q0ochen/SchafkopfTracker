package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Unsichtbare Markierung am Dateianfang (BOM, das Zeichen U+FEFF), an der Programme UTF-8-Text erkennen.
 * Beim Lesen wird sie überlesen; die CSV-Datei bekommt sie, damit Excel die Umlaute richtig anzeigt.
 * Bewusst über die Zeichennummer gebildet, damit kein unsichtbares Zeichen im Quelltext steht.
 */
internal val BYTE_ORDER_MARK: String = Char(0xFEFF).toString()

/**
 * Der Inhalt einer Datei lässt sich nicht lesen: kaputtes JSON, eine fehlende Pflichtangabe, ein unbekannter
 * Wert einer Aufzählung oder ein ungültiger Zeitpunkt.
 */
internal open class UnreadableDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Die Daten stammen aus einer neueren Version der App, die diese Version noch nicht versteht. */
internal class NewerSchemaException(found: Int) :
    UnreadableDataException("Schema-Version $found ist neuer als die unterstützte Version $SCHEMA_VERSION.")

/**
 * Übersetzt zwischen den Klassen der Fachlogik und JSON-Text.
 *
 * Alle `decode…`-Funktionen werfen genau eine Art von Fehler, die [UnreadableDataException]. Wer liest, muss
 * also nur diesen einen Fall behandeln.
 */
internal object StorageCodec {

    /**
     * Einstellungen für alle Dateien der App: Unbekannte Felder werden überlesen, Standardwerte werden
     * ausgeschrieben, leere (null) Angaben werden weggelassen.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodePlayers(players: List<Player>): String =
        json.encodeToString(PlayersFileDto.serializer(), PlayersFileDto(players = players.map { it.toDto() }))

    fun decodePlayers(text: String): List<Player> = reading {
        val file = json.decodeFromString(PlayersFileDto.serializer(), text.withoutByteOrderMark())
        checkVersion(file.schemaVersion)
        file.players.map { it.toDomain() }
    }

    /** Liest eine Spielerliste, wie sie in der Sicherung steht (ohne die Hülle mit der Schema-Version). */
    fun decodePlayerList(element: JsonElement): List<Player> = reading {
        json.decodeFromJsonElement(ListSerializer(PlayerDto.serializer()), element).map { it.toDomain() }
    }

    fun encodeEvening(evening: Evening): String = json.encodeToString(EveningDto.serializer(), evening.toDto())

    fun decodeEvening(text: String): Evening = reading {
        json.decodeFromString(EveningDto.serializer(), text.withoutByteOrderMark()).checked()
    }

    /** Liest einen Abend aus einem bereits zerlegten JSON-Baum (ein Element der Sicherung). */
    fun decodeEveningElement(element: JsonElement): Evening = reading {
        json.decodeFromJsonElement(EveningDto.serializer(), element).checked()
    }

    /** Ein einzelnes Regelwerk, so wie es auch im Abend und in der Sicherung steht. */
    fun encodeRules(rules: RuleSet): String = json.encodeToString(RuleSetDto.serializer(), rules.toDto())

    fun decodeRules(text: String): RuleSet = reading {
        json.decodeFromString(RuleSetDto.serializer(), text.withoutByteOrderMark()).toDomain()
    }

    /** Liest ein Regelwerk aus einem bereits zerlegten JSON-Baum (die Vorlage in der Sicherung). */
    fun decodeRulesElement(element: JsonElement): RuleSet = reading {
        json.decodeFromJsonElement(RuleSetDto.serializer(), element).toDomain()
    }

    fun encodeSettings(settings: AppSettings): String = json.encodeToString(SettingsDto.serializer(), settings.toDto())

    fun decodeSettings(text: String): AppSettings = reading {
        val file = json.decodeFromString(SettingsDto.serializer(), text.withoutByteOrderMark())
        checkVersion(file.schemaVersion)
        file.toDomain()
    }

    /** Ob der Text ein vollständiges JSON-Objekt ist, ganz gleich welchen Inhalts. */
    fun isJsonObject(text: String): Boolean = try {
        json.parseToJsonElement(text.withoutByteOrderMark()) is JsonObject
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun EveningDto.checked(): Evening {
        checkVersion(schemaVersion)
        return toDomain()
    }

    private fun checkVersion(version: Int) {
        if (version > SCHEMA_VERSION) throw NewerSchemaException(version)
        if (version < 1) throw UnreadableDataException("Ungültige Schema-Version $version.")
    }

    /** Macht aus jedem Fehler beim Lesen und Übersetzen eine [UnreadableDataException]. */
    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw UnreadableDataException("Die Daten lassen sich nicht lesen: ${e.message}", e)
    }

    /** Manche Editoren setzen eine unsichtbare Markierung an den Dateianfang; sie gehört nicht zum Inhalt. */
    private fun String.withoutByteOrderMark(): String = removePrefix(BYTE_ORDER_MARK)
}
