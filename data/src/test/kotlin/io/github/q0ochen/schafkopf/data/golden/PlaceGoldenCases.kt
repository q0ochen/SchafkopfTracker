package io.github.q0ochen.schafkopf.data.golden

import io.github.q0ochen.schafkopf.data.place.PhotonPlaces
import io.github.q0ochen.schafkopf.data.place.UnusablePlaceAnswerException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/**
 * Golden-Abschnitt `places`: Antworten von Photon und die Ortsvorschläge, die Kotlin daraus macht
 * (docs/ARCHITEKTUR.md, Abschnitt 4.3). Die Web-App muss aus demselben Text dasselbe machen.
 */
internal object PlaceGoldenCases {

    fun cases(): List<JsonElement> = handwritten() + generated()

    private fun case(id: String, json: String): JsonElement = buildJsonObject {
        put("id", id)
        put("json", json)
        try {
            val suggestions = PhotonPlaces.suggestions(json)
            putJsonArray("suggestions") {
                for (suggestion in suggestions) {
                    addJsonObject {
                        put("title", suggestion.title)
                        put("details", suggestion.details)
                        put("text", suggestion.text)
                    }
                }
            }
        } catch (_: UnusablePlaceAnswerException) {
            put("error", true)
        }
    }

    /** Eine Antwort mit genau diesen `properties` je Eintrag (als JSON-Text). */
    private fun answer(vararg properties: String): String =
        """{"type":"FeatureCollection","features":[${properties.joinToString(",") { """{"type":"Feature","properties":$it}""" }}]}"""

    private fun handwritten(): List<JsonElement> = listOf(
        // Eine echte Antwort, abgerufen am 21.11.2026 (Daten © OpenStreetMap-Mitwirkende, ODbL).
        case("echte-antwort-brandenburger-tor", checkNotNull(javaClass.getResource("/place/photon-brandenburger-tor.json")).readText()),
        case(
            "klinikum",
            """{"features":[{"geometry":{"coordinates":[10.91,49.88],"type":"Point"},"type":"Feature","properties":""" +
                """{"osm_type":"W","osm_id":4711,"extent":[10.9,49.89,10.92,49.87],"country":"Deutschland","city":"Bamberg",""" +
                """"countrycode":"DE","postcode":"96049","county":"Bamberg","type":"house","osm_key":"amenity",""" +
                """"housenumber":"80","street":"Buger Straße","district":"Bug","osm_value":"hospital",""" +
                """"name":"Klinikum Bamberg","state":"Bayern"}}],"type":"FeatureCollection"}""",
        ),
        case("ohne-name", answer("""{"street":"Buger Straße","housenumber":"80","postcode":"96049","city":"Bamberg","countrycode":"DE"}""")),
        case("strasse-ohne-hausnummer", answer("""{"name":"Dominikanerstraße","postcode":"96049","city":"Bamberg"}""")),
        case("hausnummer-ohne-strasse", answer("""{"name":"Keller","housenumber":"3","locality":"Wunderburg","postcode":"96050"}""")),
        case("district", answer("""{"name":"Bierkeller","district":"Gaustadt"}""")),
        case("stadt-vor-locality", answer("""{"name":"Post","city":"Bamberg","locality":"Altstadt","district":"Mitte"}""")),
        case(
            "ausland",
            answer(
                """{"name":"Gasthof Post","street":"Hauptplatz","housenumber":"1","postcode":"5020","city":"Salzburg",""" +
                    """"country":"Österreich","countrycode":"AT"}""",
                """{"name":"Post","city":"Bamberg","country":"Deutschland","countrycode":"de"}""",
                """{"name":"Wirt","city":"Bamberg","country":"Deutschland","countrycode":"De"}""",
                """{"name":"Ohne Code","city":"Irgendwo","country":"Nirgendland"}""",
                """{"name":"Leerer Code","city":"Irgendwo","country":"Nirgendland","countrycode":"  "}""",
                """{"name":"Code ohne Land","city":"Irgendwo","countrycode":"CH"}""",
            ),
        ),
        case(
            "orte-und-laender",
            answer(
                """{"name":"Bamberg","postcode":"96047","state":"Bayern","countrycode":"DE","type":"city"}""",
                """{"city":"Bamberg","state":"Bayern"}""",
                """{"state":"Bayern","country":"Deutschland","countrycode":"DE"}""",
                """{"country":"Österreich","countrycode":"AT"}""",
            ),
        ),
        case(
            "leerraum",
            answer(
                """{"name":"  Schlenkerla \t","street":" Dominikanerstraße ","housenumber":" 6 ","postcode":"\n96049","city":" Bamberg "}""",
                """{"name":" Mahrs Bräu ","city":"Bamberg"}""",
                """{"name":"﻿Spezial","city":"\u001cBamberg"}""",
            ),
        ),
        case(
            "werte-ohne-text",
            answer(
                """{"name":"Schlenkerla","street":"Dominikanerstraße","housenumber":6,"postcode":96049,"city":"Bamberg","countrycode":null}""",
                """{"name":"   ","street":["x"],"city":{"a":1},"state":true}""",
                """{"name":null,"street":"Lange Straße","housenumber":false}""",
            ),
        ),
        case(
            "doppelt-und-hoechstens-fuenf",
            """{"features":[1,null,"x",[],{"properties":"y"},{"properties":null},{"type":"Feature"},""" +
                """{"properties":{"name":"Wirtshaus","city":"Bamberg"}},{"properties":{"name":"Wirtshaus","city":"Bamberg"}},""" +
                (1..6).joinToString(",") { """{"properties":{"name":"Keller $it"}}""" } + "]}",
        ),
        case("gleicher-text-anderer-titel", answer("""{"name":"Post","city":"Bamberg"}""", """{"name":"Post, Bamberg"}""")),
        case("zu-lang", answer("""{"name":"Gasthaus ${"z".repeat(95)}","city":"Bamberg"}""")),
        case("zu-lang-leerraum-an-der-grenze", answer("""{"name":"${"x".repeat(98)}","street":"Am Kranen","housenumber":"1"}""")),
        case("zu-lang-zeichen-ausserhalb-bmp", answer("""{"name":"${"y".repeat(99)}🍺 Biergarten"}""")),
        case("unicode", answer("""{"name":"Zum Gänsewirt 🍻","street":"Große Straße","housenumber":"1½","city":"Würzburg"}""")),
        case("leer", """{"features":[]}"""),
        case("leere-properties", answer("{}")),
        case("unbekannte-felder", """{"type":"FeatureCollection","bbox":[1,2,3,4],"features":[{"properties":{"name":"Post","x":{"y":[1]}},"id":5}],"z":null}"""),
        case("features-fehlt", "{}"),
        case("features-kein-array", """{"features":{}}"""),
        case("features-null", """{"features":null}"""),
        case("liste", "[]"),
        case("null", "null"),
        case("zahl", "5"),
        case("text", "\"x\""),
        case("leerer-text", ""),
        case("kein-json", "Bad Gateway"),
        case("abgeschnitten", """{"features":[{"properties":{"name":"Po"""),
        case("html", "<html><body>Wartung</body></html>"),
    )

    /** Zufällige `properties` aus einem kleinen Vorrat (mit Leerraum, Sonderzeichen und Werten ohne Text). */
    private fun generated(): List<JsonElement> {
        val random = Random(20261121)
        val texts = listOf(
            "Post", " Post ", "", "   ", "Bamberg", "96049", "DE", "de", "AT", "Österreich", "Hauptstraße", "1a", "Bayern",
            "﻿X", "\u001cY", " Z ", "Wirtshaus zum Löwen", "x".repeat(60),
        )
        val others = listOf(JsonPrimitive(5), JsonNull, JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))
        val keys = listOf("name", "street", "housenumber", "postcode", "city", "locality", "district", "state", "country", "countrycode")
        return List(40) { number ->
            val features = buildJsonArray {
                repeat(random.nextInt(0, 8)) {
                    addJsonObject {
                        putJsonObject("properties") {
                            for (key in keys) {
                                when (random.nextInt(4)) {
                                    0 -> Unit
                                    1 -> put(key, others[random.nextInt(others.size)])
                                    else -> put(key, texts[random.nextInt(texts.size)])
                                }
                            }
                        }
                    }
                }
            }
            case("zufall-${number + 1}", buildJsonObject { put("features", features) }.toString())
        }
    }
}
