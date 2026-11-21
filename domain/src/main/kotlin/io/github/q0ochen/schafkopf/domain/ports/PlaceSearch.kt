package io.github.q0ochen.schafkopf.domain.ports

/**
 * Ein Ortsvorschlag beim Feld „Ort“ (docs/ARCHITEKTUR.md, Abschnitt 4.3).
 *
 * @property title Erste Zeile des Vorschlags, zum Beispiel der Name des Lokals.
 * @property details Zweite Zeile: Straße, Postleitzahl und Ort, im Ausland das Land; kann leer sein.
 * @property text Was beim Antippen ins Feld kommt: Titel und Zusatz in einer Zeile, gesäubert wie jeder Ort.
 */
data class PlaceSuggestion(val title: String, val details: String, val text: String)

/** Sucht Orte per Name (Ortsvorschläge beim Feld „Ort“, Abschnitt 4.3). */
fun interface PlaceSearch {
    /**
     * Vorschläge für den schon getrimmten [query], höchstens fünf, in der Reihenfolge des Dienstes.
     *
     * @throws Exception wenn die Suche nicht gelingt (kein Netz, Zeitüberschreitung, unbrauchbare Antwort). Ein Abbruch
     *   der Coroutine wird wie üblich als `CancellationException` durchgereicht.
     */
    suspend fun search(query: String): List<PlaceSuggestion>
}
