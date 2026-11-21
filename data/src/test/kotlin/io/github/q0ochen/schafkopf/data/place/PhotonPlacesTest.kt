package io.github.q0ochen.schafkopf.data.place

import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PhotonPlacesTest {

    private fun answer(vararg properties: String): String =
        """{"type":"FeatureCollection","features":[${properties.joinToString(",") { """{"type":"Feature","properties":$it}""" }}]}"""

    @Test
    fun `Lokal mit Adresse ergibt Name, Strasse mit Hausnummer und Postleitzahl mit Ort`() {
        val json = answer(
            """{"osm_key":"amenity","osm_value":"hospital","name":"Klinikum Bamberg","street":"Buger Straße",
               "housenumber":"80","postcode":"96049","city":"Bamberg","district":"Bug","state":"Bayern",
               "country":"Deutschland","countrycode":"DE","type":"house"}""",
        )
        assertEquals(
            listOf(
                PlaceSuggestion(
                    title = "Klinikum Bamberg",
                    details = "Buger Straße 80, 96049 Bamberg",
                    text = "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg",
                ),
            ),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `ohne Namen wird die Strasse zum Titel und steht nicht doppelt da`() {
        val json = answer("""{"street":"Buger Straße","housenumber":"80","postcode":"96049","city":"Bamberg","countrycode":"DE"}""")
        assertEquals(
            listOf(PlaceSuggestion("Buger Straße 80", "96049 Bamberg", "Buger Straße 80, 96049 Bamberg")),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `eine Hausnummer ohne Strasse zaehlt nicht, ein Ort ohne Stadt nimmt locality oder district`() {
        val json = answer(
            """{"name":"Keller","housenumber":"3","locality":"Wunderburg","postcode":"96050"}""",
            """{"name":"Bierkeller","district":"Gaustadt"}""",
        )
        assertEquals(
            listOf(
                PlaceSuggestion("Keller", "96050 Wunderburg", "Keller, 96050 Wunderburg"),
                PlaceSuggestion("Bierkeller", "Gaustadt", "Bierkeller, Gaustadt"),
            ),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `im Ausland steht das Land dahinter, in Deutschland nicht`() {
        val json = answer(
            """{"name":"Gasthof Post","street":"Hauptplatz","housenumber":"1","postcode":"5020","city":"Salzburg",
               "country":"Österreich","countrycode":"AT"}""",
            """{"name":"Post","city":"Bamberg","country":"Deutschland","countrycode":"de"}""",
            """{"name":"Ohne Code","city":"Irgendwo","country":"Nirgendland"}""",
        )
        assertEquals(
            listOf(
                PlaceSuggestion(
                    "Gasthof Post",
                    "Hauptplatz 1, 5020 Salzburg, Österreich",
                    "Gasthof Post, Hauptplatz 1, 5020 Salzburg, Österreich",
                ),
                PlaceSuggestion("Post", "Bamberg", "Post, Bamberg"),
                PlaceSuggestion("Ohne Code", "Irgendwo", "Ohne Code, Irgendwo"),
            ),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `ein Ort als Treffer wird zum Titel, Bundesland und Land sind die letzten Titel`() {
        val json = answer(
            """{"name":"Bamberg","postcode":"96047","state":"Bayern","countrycode":"DE","type":"city"}""",
            """{"city":"Bamberg","state":"Bayern"}""",
            """{"state":"Bayern","country":"Deutschland","countrycode":"DE"}""",
            """{"country":"Österreich","countrycode":"AT"}""",
        )
        assertEquals(
            listOf(
                PlaceSuggestion("Bamberg", "96047", "Bamberg, 96047"),
                // Die Ortszeile wäre nur „Bamberg“ wie der Titel: Sie entfällt.
                PlaceSuggestion("Bamberg", "", "Bamberg"),
                PlaceSuggestion("Bayern", "", "Bayern"),
                // Das Land ist hier Titel und Zusatz zugleich: nur einmal.
                PlaceSuggestion("Österreich", "", "Österreich"),
            ),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `Leerraum wird getrimmt, Werte ohne Text zaehlen als leer`() {
        val json = answer(
            """{"name":"  Schlenkerla \t","street":" Dominikanerstraße ","housenumber":6,"postcode":96049,"city":" Bamberg ",
               "countrycode":null}""",
            """{"name":"   ","street":["x"],"city":{"a":1},"state":true}""",
        )
        assertEquals(
            listOf(PlaceSuggestion("Schlenkerla", "Dominikanerstraße, Bamberg", "Schlenkerla, Dominikanerstraße, Bamberg")),
            PhotonPlaces.suggestions(json),
        )
    }

    @Test
    fun `doppelte Vorschlaege erscheinen einmal, hoechstens fuenf, Eintraege ohne Objekt entfallen`() {
        val same = """{"name":"Wirtshaus","city":"Bamberg"}"""
        val json = """{"features":[1,null,"x",{"properties":"y"},{"type":"Feature"},{"properties":$same},{"properties":$same},""" +
            (1..6).joinToString(",") { """{"properties":{"name":"Keller $it"}}""" } + "]}"
        val result = PhotonPlaces.suggestions(json)
        assertEquals(PhotonPlaces.MAX_SUGGESTIONS, result.size)
        assertEquals(listOf("Wirtshaus, Bamberg", "Keller 1", "Keller 2", "Keller 3", "Keller 4"), result.map { it.text })
    }

    @Test
    fun `ein zu langer Vorschlag wird wie jeder Ort auf 100 Zeichen gekuerzt`() {
        val name = "Gasthaus " + "z".repeat(95)
        val result = PhotonPlaces.suggestions(answer("""{"name":"$name","city":"Bamberg"}"""))
        assertEquals(name, result.single().title)
        assertEquals("Bamberg", result.single().details)
        assertEquals(name.take(100), result.single().text)
    }

    @Test
    fun `eine echte Antwort von Photon`() {
        // Abgerufen am 21.11.2026 mit q=Brandenburger Tor, lang=de, limit=8 (Daten © OpenStreetMap-Mitwirkende, ODbL).
        // Bahnhof und Haltestelle „Unter den Linden“ ergeben denselben Text und erscheinen nur einmal.
        val json = checkNotNull(javaClass.getResource("/place/photon-brandenburger-tor.json")).readText()
        assertEquals(
            listOf(
                "Brandenburger Tor, Pariser Platz 1, 10117 Berlin",
                "Brandenburger Tor, Unter den Linden, 10117 Berlin",
                "Brandenburger Tor, Alter Garten 137, 238400 Kaliningrad, Russland",
                "Brandenburger Tor, Brandenburger Straße, 14467 Potsdam",
                "Brandenburger Tor, Rudolf-Breitscheid-Straße, 17087 Altentreptow",
            ),
            PhotonPlaces.suggestions(json).map { it.text },
        )
        assertEquals(
            PlaceSuggestion("Brandenburger Tor", "Pariser Platz 1, 10117 Berlin", "Brandenburger Tor, Pariser Platz 1, 10117 Berlin"),
            PhotonPlaces.suggestions(json).first(),
        )
    }

    @Test
    fun `leere Antworten ergeben keine Vorschlaege`() {
        assertEquals(emptyList<PlaceSuggestion>(), PhotonPlaces.suggestions("""{"features":[]}"""))
        assertEquals(emptyList<PlaceSuggestion>(), PhotonPlaces.suggestions(answer("{}")))
    }

    @Test
    fun `unbrauchbare Antworten sind ein Fehler`() {
        for (json in listOf("", "kein json", "[]", "null", "5", "\"x\"", "{}", """{"features":{}}""", """{"features":null}""")) {
            assertThrows<UnusablePlaceAnswerException>(json) { PhotonPlaces.suggestions(json) }
        }
    }
}
