package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.flow.first

/**
 * Verbindet dieses Gerät mit einer gemeinsamen Runde und trennt es wieder (docs/ARCHITEKTUR.md, Abschnitt 8.7).
 *
 * Die Aufrufe, die mit dem Server reden, können mit einer [RoundException] scheitern; dann ist auf dem Gerät nichts
 * verändert, außer es steht ausdrücklich dabei.
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
     * Legt eine Runde an und bringt die Daten dieses Geräts mit: Alle Abende und Spieler gehen beim ersten Abgleich
     * hoch. Vorbelegte Spieler, die noch niemand umbenannt hat und die in keinem Abend vorkommen, entfallen vorher.
     *
     * @param userId Das angemeldete Konto.
     */
    suspend fun create(name: String, userId: String, placeholders: List<Player>): SyncOutcome {
        sync.exclusive {
            val round = emptyOwnRound(name) ?: api.createRound(name)
            val all = evenings.evenings.first()
            players.players.first()
                .filter { player -> player in placeholders && all.none { it.involves(player.id) } }
                .forEach { players.delete(it.id) }
            store.clearBases()
            store.setLink(RoundLink(round, name.trim(), userId, isCreator = true))
        }
        return sync.sync()
    }

    /**
     * Tritt der Runde hinter dem Einladungscode bei und übernimmt deren Daten. **Abende und Spieler dieses Geräts
     * werden dabei gelöscht** (vorher nachfragen, siehe [hasOwnData]).
     */
    suspend fun join(token: String, userId: String): SyncOutcome {
        val info = api.inviteInfo(token)
        val round = api.joinRound(token)
        return attach(RoundLink(round, info.roundName, userId))
    }

    /**
     * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy).
     * **Abende und Spieler dieses Geräts werden dabei gelöscht.**
     */
    suspend fun use(round: RoundSummary, userId: String): SyncOutcome =
        attach(RoundLink(round.id, round.name, userId, playerId = round.myPlayerId, isCreator = round.isCreator))

    private suspend fun attach(link: RoundLink): SyncOutcome {
        // Erst fragen, dann leeren: Ohne Antwort des Servers bleibt das Gerät, wie es ist. Sonst stünde man ohne Netz
        // mit einem leeren Gerät da, das schon mit der Runde verbunden ist.
        api.head(link.roundId) ?: throw RoundException(RoundError.NOT_MEMBER)
        sync.exclusive {
            // Erst Verbindung und Basen weg, dann die Daten, dann verbinden. Mit Basen sähe das Leeren aus wie Löschen
            // und ginge beim nächsten Abgleich als Löschung in die Runde hoch; und bräche es vor dem Verbinden ab, ginge
            // der Rest sonst als neu hoch.
            store.setLink(null)
            store.clearBases()
            evenings.evenings.first().forEach { evenings.delete(it.id) }
            players.players.first().forEach { players.delete(it.id) }
            store.setLink(link)
        }
        return sync.sync()
    }

    /**
     * Eine eigene, noch leere Runde dieses Namens. Sie stammt von einem Versuch, dessen Antwort verloren ging; ohne
     * diese Prüfung legte der zweite Versuch eine zweite Runde an, und die erste bliebe für immer leer liegen.
     */
    private suspend fun emptyOwnRound(name: String): RoundId? {
        val wanted = name.trim().replace(WHITESPACE, " ")
        return api.myRounds().firstOrNull { it.isCreator && it.memberCount == 1 && it.rev == FRESH_REV && it.name == wanted }?.id
    }

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
        val WHITESPACE = Regex("\\s+")
    }
}
