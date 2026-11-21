package io.github.q0ochen.schafkopf.app

import io.github.q0ochen.schafkopf.domain.ports.PlaceSearch
import io.github.q0ochen.schafkopf.domain.ports.PlaceSuggestion
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ortsvorschläge ohne Netz für die Tests der Oberfläche: Kein Test fragt den echten Dienst. „Klinikum …“ liefert zwei
 * Vorschläge, alles andere nichts; mit [offline] scheitert jede Suche wie ohne Internet.
 */
object TestPlaceSearch : PlaceSearch {
    /** Jede Suche scheitert wie ohne Internet (nach dem Test wieder auf `false` setzen). */
    @Volatile
    var offline: Boolean = false

    /** Alle Suchen in der Reihenfolge, in der sie kamen. */
    val queries: MutableList<String> = CopyOnWriteArrayList()

    val klinikum = PlaceSuggestion(
        title = "Klinikum Bamberg",
        details = "Buger Straße 80, 96049 Bamberg",
        text = "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg",
    )
    private val gaststaette = PlaceSuggestion(
        title = "Gaststätte am Klinikum",
        details = "Buger Straße 72, 96049 Bamberg",
        text = "Gaststätte am Klinikum, Buger Straße 72, 96049 Bamberg",
    )

    override suspend fun search(query: String): List<PlaceSuggestion> {
        queries += query
        if (offline) throw IOException("kein Netz")
        return if (query.startsWith("Klinikum", ignoreCase = true)) listOf(klinikum, gaststaette) else emptyList()
    }
}
