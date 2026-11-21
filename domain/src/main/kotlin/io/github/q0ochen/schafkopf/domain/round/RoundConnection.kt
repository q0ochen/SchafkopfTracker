package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.flow.first

/**
 * Verbindet dieses Gerät mit einer gemeinsamen Runde, wechselt zwischen Runden und trennt es wieder
 * (docs/ARCHITEKTUR.md, Abschnitt 8.7).
 *
 * Die Aufrufe, die mit dem Server reden, können mit einer [RoundException] scheitern; dann ist auf dem Gerät nichts
 * verändert, außer es steht ausdrücklich dabei. Ist das Gerät schon mit einer Runde verbunden, gleichen [create],
 * [join] und [use] sie zuerst vollständig ab (siehe [handOver]): Ihre Abende bleiben auf dem Server, das Gerät hält
 * danach die Daten der neuen Runde.
 */
class RoundConnection(
    private val api: RoundApi,
    private val store: RoundStore,
    private val players: PlayerRepository,
    private val evenings: EveningRepository,
    private val sync: RoundSync,
    private val ids: IdGenerator,
) {
    /**
     * Ob auf dem Gerät etwas liegt, das beim Beitreten ersetzt würde: Abende oder andere Spieler als die unberührten
     * vorbelegten. Dann fragt die Oberfläche vorher nach.
     *
     * @param placeholders Die vorbelegten Spieler, wie die App sie beim ersten Start anlegt.
     */
    suspend fun hasOwnData(placeholders: List<Player>): Boolean =
        evenings.evenings.first().isNotEmpty() || players.players.first().any { it !in placeholders }

    /**
     * Legt eine Runde an. Ohne verbundene Runde bringt sie die Daten dieses Geräts mit: Alle Abende und Spieler gehen
     * beim ersten Abgleich hoch; vorbelegte Spieler, die noch niemand umbenannt hat und die in keinem Abend vorkommen,
     * entfallen vorher. Ist das Gerät schon verbunden, beginnt die neue Runde leer, und die bisherige behält ihre Abende.
     *
     * Hat das Konto schon eine Runde dieses Namens, scheitert es mit [RoundError.NAME_TAKEN] (docs/ARCHITEKTUR.md 8.7,
     * „Eindeutige Namen“) – außer es ist die eigene, noch leere Runde eines Versuchs, dessen Antwort verloren ging.
     *
     * @param userId Das angemeldete Konto.
     */
    suspend fun create(name: String, userId: String, placeholders: List<Player>): SyncOutcome {
        val switching = store.link.first() != null
        if (switching) handOver()?.let { return it }
        val rounds = api.myRounds()
        val round = emptyOwnRound(rounds, name) ?: run {
            if (RoundNames.taken(name, rounds)) throw RoundException(RoundError.NAME_TAKEN)
            api.createRound(name)
        }
        sync.exclusive {
            if (switching) {
                clearDevice()
            } else {
                val all = evenings.evenings.first()
                players.players.first()
                    .filter { player -> player in placeholders && all.none { it.involves(player.id) } }
                    .forEach { players.delete(it.id) }
                store.clearBases()
            }
            store.setLink(RoundLink(round, RoundNames.clean(name), userId, isCreator = true))
        }
        return sync.sync()
    }

    /**
     * Tritt der Runde hinter dem Einladungscode bei und übernimmt deren Daten. **Abende und Spieler dieses Geräts
     * werden dabei gelöscht** (ohne verbundene Runde vorher nachfragen, siehe [hasOwnData]). Gehört die Einladung zur
     * verbundenen Runde selbst, wird nur abgeglichen.
     */
    suspend fun join(token: String, userId: String): SyncOutcome {
        val info = api.inviteInfo(token)
        val current = store.link.first()
        if (current != null) {
            if (current.roundId == info.roundId) return sync.sync()
            handOver()?.let { return it }
        }
        val round = api.joinRound(token)
        return attach(RoundLink(round, info.roundName, userId))
    }

    /**
     * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy) oder
     * wechselt zu ihr. **Abende und Spieler dieses Geräts werden dabei gelöscht** – ohne verbundene Runde vorher
     * nachfragen (siehe [hasOwnData]); mit verbundener Runde sind sie nach dem Abgleich in [handOver] auf dem Server.
     * Ist [round] die verbundene Runde, wird nur abgeglichen.
     */
    suspend fun use(round: RoundSummary, userId: String): SyncOutcome {
        val current = store.link.first()
        if (current != null) {
            if (current.roundId == round.id) return sync.sync()
            handOver()?.let { return it }
        }
        return attach(RoundLink(round.id, round.name, userId, playerId = round.myPlayerId, isCreator = round.isCreator))
    }

    /**
     * Vor dem Wechsel in eine andere Runde: gleicht die verbundene Runde vollständig ab, damit alles von hier auf dem
     * Server ist. Was danach noch wartet (`pendingCount() > 0`, etwa ein Abend, den der Server nicht annimmt), geht
     * beim Wechseln verloren; die Oberfläche sagt das in ihrer Rückfrage.
     *
     * @return `null`, wenn es weitergehen kann; sonst das Ergebnis, mit dem der Wechsel endet: [SyncOutcome.Detached]
     *   (die Runde gibt es nicht mehr; die Daten bleiben als Kopie) oder [SyncOutcome.NotLinked] (inzwischen getrennt).
     * @throws RoundException wenn der Abgleich nicht durchlief; dann bleibt alles, wie es ist.
     */
    private suspend fun handOver(): SyncOutcome? = when (val outcome = sync.sync()) {
        is SyncOutcome.Synced, SyncOutcome.Unchanged -> null
        SyncOutcome.Detached, SyncOutcome.NotLinked -> outcome
        SyncOutcome.Offline -> throw RoundException(RoundError.OFFLINE)
        SyncOutcome.SignedOut -> throw RoundException(RoundError.SIGNED_OUT)
        is SyncOutcome.Failed -> throw RoundException(outcome.error)
    }

    /**
     * Leert das Gerät für eine andere Runde: erst Verbindung und Basen, dann die Daten. Mit Basen sähe das Leeren aus wie
     * Löschen und ginge beim nächsten Abgleich als Löschung in die Runde hoch; und bräche es vor dem Verbinden ab, ginge
     * der Rest sonst als neu hoch. Nur innerhalb von [RoundSync.exclusive].
     */
    private suspend fun clearDevice() {
        store.setLink(null)
        store.clearBases()
        evenings.evenings.first().forEach { evenings.delete(it.id) }
        players.players.first().forEach { players.delete(it.id) }
    }

    private suspend fun attach(link: RoundLink): SyncOutcome {
        // Erst fragen, dann leeren: Ohne Antwort des Servers bleibt das Gerät, wie es ist. Sonst stünde man ohne Netz
        // mit einem leeren Gerät da, das schon mit der Runde verbunden ist.
        api.head(link.roundId) ?: throw RoundException(RoundError.NOT_MEMBER)
        sync.exclusive {
            clearDevice()
            store.setLink(link)
        }
        return sync.sync()
    }

    /**
     * Eine eigene, noch leere Runde dieses Namens. Sie stammt von einem Versuch, dessen Antwort verloren ging; ohne
     * diese Prüfung legte der zweite Versuch eine zweite Runde an, und die erste bliebe für immer leer liegen.
     */
    private fun emptyOwnRound(rounds: List<RoundSummary>, name: String): RoundId? =
        rounds.firstOrNull { it.isCreator && it.memberCount == 1 && it.rev == FRESH_REV && RoundNames.same(it.name, name) }?.id

    /**
     * „Das bin ich“: Das Konto übernimmt einen Spieler ohne Besitzer oder legt sich neu an und legt seinen Namen
     * fest. Danach lässt sich der Name nicht mehr ändern.
     *
     * @param player Der gewählte Spieler; `null` für „Ich bin neu“.
     */
    suspend fun claim(player: PlayerId?, name: String): SyncOutcome {
        val link = store.link.first() ?: return SyncOutcome.NotLinked
        val existing = players.players.first()
        val id = player ?: PlayerId(ids.newId())
        val colorIndex = existing.firstOrNull { it.id == id }?.colorIndex ?: Player.nextColorIndex(existing)
        api.claimPlayer(link.roundId, id, name, colorIndex)
        return sync.sync()
    }

    /** Verlässt die Runde (auf dem Server) und löst die Verbindung. Die Daten bleiben als Kopie auf dem Gerät. */
    suspend fun leave() {
        val link = store.link.first() ?: return
        api.leaveRound(link.roundId)
        disconnect()
    }

    /**
     * Löst nur die Verbindung auf diesem Gerät (zum Beispiel beim Abmelden). Die Daten bleiben als Kopie hier;
     * was noch nicht hochgeladen war, kommt nicht mehr in der Runde an.
     */
    suspend fun disconnect() {
        sync.exclusive {
            store.setLink(null)
            store.clearBases()
        }
    }

    private companion object {
        /** Den Stand `rev = 1` hat eine Runde nur unmittelbar nach dem Anlegen. */
        const val FRESH_REV = 1L
    }
}
