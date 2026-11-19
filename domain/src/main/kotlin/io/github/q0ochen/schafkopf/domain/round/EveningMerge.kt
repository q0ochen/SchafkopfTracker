package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId

/**
 * Führt zwei Fassungen eines Abends zusammen: die des eigenen Geräts und die des Servers. Die Basis ist die Fassung,
 * die das Gerät zuletzt vom Server kannte; an ihr lässt sich ablesen, wer was geändert hat.
 *
 * Reine Funktion ohne Uhr und Zufall; der Port für die Web-App liegt in `web/src/core/round` und wird mit denselben
 * Fällen geprüft. Regeln: docs/ARCHITEKTUR.md, Abschnitt 8.5.
 */
object EveningMerge {

    /**
     * @param base Die zuletzt bekannte Fassung des Servers; `null`, wenn das Gerät den Abend noch nie abgeglichen hat.
     * @param local Der Abend auf diesem Gerät.
     * @param remote Der Abend auf dem Server.
     */
    fun merge3(base: Evening?, local: Evening, remote: Evening): Evening {
        // Kopf: Was hier geändert wurde, bleibt; alles andere kommt vom Server.
        fun <T> field(pick: (Evening) -> T): T {
            val mine = pick(local)
            return if (base == null || mine == pick(base)) pick(remote) else mine
        }
        return Evening(
            id = local.id,
            startedAt = field { it.startedAt },
            finishedAt = field { it.finishedAt },
            location = field { it.location },
            seating = field { it.seating },
            firstDealer = field { it.firstDealer },
            rules = field { it.rules },
            entries = mergeEntries(base?.entries.orEmpty(), local.entries, remote.entries),
        )
    }

    private fun mergeEntries(
        base: List<EveningEntry>,
        local: List<EveningEntry>,
        remote: List<EveningEntry>,
    ): List<EveningEntry> {
        val baseById = base.associateBy { it.id }
        val localById = HashMap<EntryId, EveningEntry>()
        val localIndex = HashMap<EntryId, Int>()
        // Bei doppelten Kennungen (beschädigte Daten) zählt jeweils der erste Eintrag.
        local.forEachIndexed { index, entry ->
            if (entry.id !in localById) {
                localById[entry.id] = entry
                localIndex[entry.id] = index
            }
        }
        val remoteIds = HashSet<EntryId>()
        val result = ArrayList<EveningEntry>(local.size + remote.size)

        // 1. Die Reihenfolge des Servers gilt für alles, was er kennt.
        for (theirs in remote) {
            if (!remoteIds.add(theirs.id)) continue
            val mine = localById[theirs.id]
            val known = baseById[theirs.id]
            when {
                // Hier unverändert seit der Basis: Die Fassung des Servers gilt. Sonst gewinnt die Änderung von hier.
                mine != null -> result += if (mine != theirs && known != null && mine == known) theirs else mine
                // Hier gelöscht und auf dem Server seitdem unverändert: bleibt gelöscht.
                known != null && theirs == known -> Unit
                // Neu vom Server, oder die Änderung des Servers gewinnt gegen das Löschen hier.
                else -> result += theirs
            }
        }

        // 2. Was nur dieses Gerät hat, in dessen Reihenfolge einsortieren.
        for ((index, mine) in local.withIndex()) {
            if (mine.id in remoteIds || localIndex[mine.id] != index) continue
            val known = baseById[mine.id]
            // Auf dem Server gelöscht und hier seitdem unverändert: entfällt.
            if (known != null && mine == known) continue
            result.add(insertPosition(result, mine, index, localIndex, baseById), mine)
        }
        return result
    }

    /**
     * Die Stelle für einen Eintrag, den nur dieses Gerät hat: hinter dem letzten Eintrag des Ergebnisses, der hier vor
     * ihm steht; von dort weiter über alle direkt folgenden Einträge, die neu vom Server sind und früher aufgeschrieben
     * wurden. So stehen gleichzeitig auf zwei Geräten eingetragene Spiele in der Reihenfolge ihrer Uhrzeit.
     */
    private fun insertPosition(
        result: List<EveningEntry>,
        mine: EveningEntry,
        index: Int,
        localIndex: Map<EntryId, Int>,
        baseById: Map<EntryId, EveningEntry>,
    ): Int {
        var position = 0
        for (i in result.indices.reversed()) {
            val other = localIndex[result[i].id]
            if (other != null && other < index) {
                position = i + 1
                break
            }
        }
        while (position < result.size) {
            val next = result[position]
            val newFromServer = next.id !in localIndex && next.id !in baseById
            if (newFromServer && comesBefore(next, mine)) position++ else break
        }
        return position
    }

    /** Ob [first] nach Zeitpunkt und – bei gleichem Zeitpunkt – nach Kennung vor [second] liegt. */
    private fun comesBefore(first: EveningEntry, second: EveningEntry): Boolean {
        val byTime = first.recordedAt.compareTo(second.recordedAt)
        return if (byTime != 0) byTime < 0 else first.id.value < second.id.value
    }
}
