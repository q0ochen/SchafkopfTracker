package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.model.IdGenerator
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Verbindet dieses Gerät mit einer gemeinsamen Runde, wechselt zwischen Runden und trennt es wieder
 * (docs/ARCHITEKTUR.md, Abschnitt 8.7).
 *
 * Die Aufrufe, die mit dem Server reden, können mit einer [RoundException] scheitern; dann ist auf dem Gerät nichts
 * verändert, außer es steht ausdrücklich dabei. Ist das Gerät schon mit einer Runde verbunden, wechseln [create],
 * [join] und [use] in die neue Runde (siehe [switchDevice]): Die Abende der bisherigen bleiben auf dem Server, das Gerät
 * hält danach die Daten der neuen.
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
     * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
     */
    suspend fun create(name: String, userId: String, placeholders: List<Player>, from: SwitchFrom? = null): SyncOutcome {
        val expected = from ?: SwitchFrom(store.link.first()?.roundId)
        val rounds = api.myRounds()
        val round = emptyOwnRound(rounds, name, expected.roundId) ?: run {
            if (RoundNames.taken(name, rounds)) throw RoundException(RoundError.NAME_TAKEN)
            api.createRound(name)
        }
        val link = RoundLink(round, RoundNames.clean(name), userId, isCreator = true)
        val ended = switchDevice(expected, link) {
            // Ohne Runde kommen die Daten des Geräts mit; nur unberührte vorbelegte Spieler ohne Abend entfallen.
            val all = evenings.evenings.first()
            players.players.first()
                .filter { player -> player in placeholders && all.none { it.involves(player.id) } }
                .forEach { players.delete(it.id) }
        }
        return ended ?: sync.sync()
    }

    /**
     * Tritt der Runde hinter dem Einladungscode bei und übernimmt deren Daten. **Abende und Spieler dieses Geräts
     * werden dabei gelöscht** (ohne verbundene Runde vorher nachfragen, siehe [hasOwnData]). Gehört die Einladung zur
     * verbundenen Runde selbst, wird nur abgeglichen.
     *
     * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
     */
    suspend fun join(token: String, userId: String, from: SwitchFrom? = null): SyncOutcome {
        val expected = from ?: SwitchFrom(store.link.first()?.roundId)
        val info = api.inviteInfo(token)
        if (store.link.first()?.roundId == info.roundId) return sync.sync()
        val round = api.joinRound(token)
        return attach(RoundLink(round, info.roundName, userId), expected)
    }

    /**
     * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy) oder
     * wechselt zu ihr. **Abende und Spieler dieses Geräts werden dabei gelöscht** – ohne verbundene Runde vorher
     * nachfragen (siehe [hasOwnData]); mit verbundener Runde sind sie nach dem Abgleich in [switchDevice] auf dem
     * Server. Ist [round] die verbundene Runde, wird nur abgeglichen.
     *
     * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
     */
    suspend fun use(round: RoundSummary, userId: String, from: SwitchFrom? = null): SyncOutcome {
        val expected = from ?: SwitchFrom(store.link.first()?.roundId)
        if (store.link.first()?.roundId == round.id) return sync.sync()
        return attach(RoundLink(round.id, round.name, userId, playerId = round.myPlayerId, isCreator = round.isCreator), expected)
    }

    private suspend fun attach(link: RoundLink, from: SwitchFrom): SyncOutcome {
        // Erst fragen, dann leeren: Ohne Antwort des Servers bleibt das Gerät, wie es ist. Sonst stünde man ohne Netz
        // mit einem leeren Gerät da, das schon mit der Runde verbunden ist.
        api.head(link.roundId) ?: throw RoundException(RoundError.NOT_MEMBER)
        return switchDevice(from, link, keep = null) ?: sync.sync()
    }

    /**
     * Verbindet das Gerät mit [link], am Stück und ohne Abbruch von außen (auch wenn der Aufrufer abgebrochen wird).
     *
     * War das Gerät mit einer Runde verbunden, wird in derselben Sperre zuerst noch einmal vollständig abgeglichen, damit
     * auch ein gerade eingetragenes Spiel oben ist, dann das Gerät geleert. Wartet danach mehr, als die Rückfrage nannte
     * ([SwitchFrom.acceptedLoss]), oder hängt das Gerät inzwischen an einer anderen (oder keiner) Runde, wird nicht
     * gewechselt ([RoundError.CHANGED_MEANWHILE]).
     *
     * Ohne verbundene Runde bleiben die Daten, wenn [keep] gesetzt ist (es räumt vorher auf, siehe [create]); sonst wird
     * das Gerät geleert (Beitreten, Verwenden).
     *
     * @return `null`, wenn verbunden ist; [SyncOutcome.Detached], wenn es die bisherige Runde nicht mehr gibt (die Daten
     *   bleiben dann als Kopie, und es wird nicht gewechselt).
     * @throws RoundException ohne Netz, bei abgelaufener Anmeldung, einem Fehler des Servers oder CHANGED_MEANWHILE;
     *   dann bleibt alles, wie es ist.
     */
    private suspend fun switchDevice(from: SwitchFrom, link: RoundLink, keep: (suspend () -> Unit)?): SyncOutcome? =
        withContext(NonCancellable) {
            sync.syncThen { outcome ->
                if (from.roundId != null) {
                    when (outcome) {
                        is SyncOutcome.Synced, SyncOutcome.Unchanged -> Unit
                        SyncOutcome.Detached -> return@syncThen outcome
                        SyncOutcome.NotLinked -> throw RoundException(RoundError.CHANGED_MEANWHILE)
                        SyncOutcome.Offline -> throw RoundException(RoundError.OFFLINE)
                        SyncOutcome.SignedOut -> throw RoundException(RoundError.SIGNED_OUT)
                        is SyncOutcome.Failed -> throw RoundException(outcome.error)
                    }
                    if (sync.pendingCount() > from.acceptedLoss) throw RoundException(RoundError.CHANGED_MEANWHILE)
                }
                if (store.link.first()?.roundId != from.roundId) throw RoundException(RoundError.CHANGED_MEANWHILE)
                if (from.roundId == null && keep != null) {
                    keep()
                    store.clearBases()
                } else {
                    clearDevice()
                }
                store.setLink(link)
                null
            }
        }

    /**
     * Leert das Gerät für eine andere Runde: erst Verbindung und Basen, dann die Daten. Mit Basen sähe das Leeren aus wie
     * Löschen und ginge beim nächsten Abgleich als Löschung in die Runde hoch; und bräche es vor dem Verbinden ab, ginge
     * der Rest sonst als neu hoch. Nur innerhalb der Sperre des Abgleichs.
     */
    private suspend fun clearDevice() {
        store.setLink(null)
        store.clearBases()
        evenings.evenings.first().forEach { evenings.delete(it.id) }
        players.players.first().forEach { players.delete(it.id) }
    }

    /**
     * Eine eigene, noch leere Runde dieses Namens (nie die verbundene [linked]). Sie stammt von einem Versuch, dessen
     * Antwort verloren ging; ohne diese Prüfung legte der zweite Versuch eine zweite Runde an, und die erste bliebe für
     * immer leer liegen.
     */
    private fun emptyOwnRound(rounds: List<RoundSummary>, name: String, linked: RoundId?): RoundId? =
        rounds.firstOrNull { RoundNames.reusable(it) && it.id != linked && RoundNames.same(it.name, name) }?.id

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
}
