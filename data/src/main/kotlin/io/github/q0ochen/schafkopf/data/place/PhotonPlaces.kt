package io.github.q0ochen.schafkopf.data.place

import io.github.q0ochen.schafkopf.domain.evening.EveningEdit
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** Die Antwort von Photon war nicht zu gebrauchen (kein JSON-Objekt oder `features` keine Liste). */
class UnusablePlaceAnswerException(message: String) : IOException(message)

/**
 * Macht aus einer Antwort von Photon (GeoJSON) die Ortsvorschläge, genau nach docs/ARCHITEKTUR.md, Abschnitt 4.3. Die
 * Web-App rechnet dasselbe (`web/src/data/place`); der Golden-Abschnitt `places` hält beide gleich.
 */
object PhotonPlaces {
    /** Höchstens so viele Vorschläge zeigt die App. */
    const val MAX_SUGGESTIONS = 5

    /**
     * Die Vorschläge aus dem Text einer Antwort.
     *
     * @throws UnusablePlaceAnswerException wenn der Text kein JSON-Objekt ist oder `features` keine Liste.
     */
    fun suggestions(json: String): List<PlaceSuggestion> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw UnusablePlaceAnswerException("Antwort ist kein JSON: ${e::class.simpleName}")
        }
        val features = ((root as? JsonObject) ?: throw UnusablePlaceAnswerException("Antwort ist kein JSON-Objekt"))["features"]
            as? JsonArray ?: throw UnusablePlaceAnswerException("features ist keine Liste")
        val seen = HashSet<String>()
        val result = ArrayList<PlaceSuggestion>()
        for (feature in features) {
            if (result.size == MAX_SUGGESTIONS) break
            val properties = ((feature as? JsonObject)?.get("properties") as? JsonObject) ?: continue
            val suggestion = suggestion(properties) ?: continue
            if (seen.add(suggestion.text)) result += suggestion
        }
        return result
    }

    /** Ein Vorschlag aus den `properties` eines Eintrags, oder `null`, wenn nichts Brauchbares darin steht. */
    private fun suggestion(properties: JsonObject): PlaceSuggestion? {
        fun value(key: String): String = properties.text(key)

        val street = value("street").let { street ->
            val number = value("housenumber")
            if (street.isEmpty() || number.isEmpty()) street else "$street $number"
        }
        val town = listOf(value("city"), value("locality"), value("district")).firstOrNull { it.isNotEmpty() }.orEmpty()
        val townLine = listOf(value("postcode"), town).filter { it.isNotEmpty() }.joinToString(" ")
        val countryCode = value("countrycode")
        val country = if (countryCode.isNotEmpty() && !countryCode.equals("DE", ignoreCase = true)) value("country") else ""

        val title = listOf(value("name"), street, town, value("state"), value("country")).firstOrNull { it.isNotEmpty() }
            ?: return null
        val details = listOf(street, townLine, country).filter { it.isNotEmpty() && it != title }.joinToString(", ")
        val text = EveningEdit.cleanLocation(if (details.isEmpty()) title else "$title, $details")
        if (text.isEmpty()) return null
        return PlaceSuggestion(title = title, details = details, text = text)
    }

    /** Der getrimmte Text unter [key]; leer, wenn es dort keinen Text gibt. */
    private fun JsonObject.text(key: String): String {
        val element: JsonElement = this[key] ?: return ""
        val primitive = element as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content.trim() else ""
    }
}
