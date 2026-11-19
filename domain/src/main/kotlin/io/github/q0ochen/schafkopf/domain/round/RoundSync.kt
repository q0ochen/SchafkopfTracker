package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Der Server hat eine Änderung an einem Spieler abgelehnt; die Oberfläche sagt es dem Nutzer.
 *
 * @property name Der Name, den dieses Gerät hochladen wollte.
 * @property reason Der Grund, zum Beispiel [RoundError.NAME_TAKEN] oder [RoundError.NAME_LOCKED].
 * @property nowName Wie der Spieler jetzt auf diesem Gerät heißt; `null`, wenn es ihn hier nicht mehr gibt.
 */
data class SyncNotice(val player: PlayerId, val name: String, val reason: RoundError, val nowName: String?)

/** Wie ein Abgleich ausgegangen ist. */
sealed interface SyncOutcome {
    /**
     * Der Abgleich ist durchgelaufen.
     *
     * @property pulled So viele Abende, Spieler und Regelwerke haben sich auf diesem Gerät durch den Server geändert.
     * @property pushed So viele Änderungen hat dieses Gerät hochgeladen.
     * @property pending So viele Änderungen warten danach noch (zum Beispiel, weil gerade jemand anders schrieb).
     * @property unreadable So viele Abende des Servers kann diese Version der App nicht lesen; sie bleiben unberührt.
     */
    data class Synced(
        val pulled: Int,
        val pushed: Int,
        val pending: Int,
        val unreadable: Int = 0,
        val notices: List<SyncNotice> = emptyList(),
    ) : SyncOutcome

    /** Der Server hat nichts Neues; ob hier etwas wartet, wurde nicht geprüft (nur bei `quick`). */
    data object Unchanged : SyncOutcome

    /** Das Gerät ist mit keiner Runde verbunden. */
    data object NotLinked : SyncOutcome

    /** Die Runde gibt es nicht mehr, oder das Konto gehört nicht mehr dazu. Die Verbindung ist gelöst, die Daten bleiben. */
    data object Detached : SyncOutcome

    /** Die Anmeldung ist abgelaufen; nach erneutem Anmelden geht es weiter. */
    data object SignedOut : SyncOutcome

    /** Keine Verbindung zum Server. */
    data object Offline : SyncOutcome

    /** Der Server antwortet mit einem Fehler (zum Beispiel, weil das Projekt pausiert ist). */
    data class Failed(val error: RoundError) : SyncOutcome
}

/**
 * Gleicht dieses Gerät mit der gemeinsamen Runde ab (docs/ARCHITEKTUR.md, Abschnitt 8.6): holt, was andere geändert
 * haben, führt es mit dem Stand von hier zusammen und lädt hoch, was hier neu ist. Das Gerät bleibt dabei jederzeit
 * vollständig benutzbar; scheitert etwas, wartet die Änderung einfach bis zum nächsten Versuch.
 *
 * Es läuft höchstens ein Abgleich gleichzeitig.
 */
class RoundSync(
    private val api: RoundApi,
    private val store: RoundStore,
    private val players: PlayerRepository,
    private val evenings: EveningRepository,
    private val settings: SettingsRepository,
    private val editor: EveningEditor,
) {
    private val mutex = Mutex()

    /** Spieler, die der Server in dieser Sitzung abgelehnt hat: nicht bei jedem Abgleich dasselbe erneut versuchen. */
    private val rejected = HashMap<PlayerId, Player>()

    /**
     * Ein Abgleich.
     *
     * @param quick Nur nachsehen, ob der Server etwas Neues hat (eine kleine Anfrage), und sonst sofort mit
     *   [SyncOutcome.Unchanged] aufhören. Für das regelmäßige Nachfragen, solange hier nichts geändert wurde.
     */
    suspend fun sync(quick: Boolean = false): SyncOutcome = mutex.withLock {
        val link = store.link.first() ?: return@withLock SyncOutcome.NotLinked
        try {
            Run(link).execute(quick)
        } catch (e: RoundException) {
            when (e.error) {
                RoundError.OFFLINE -> SyncOutcome.Offline
                RoundError.SIGNED_OUT -> SyncOutcome.SignedOut
                else -> SyncOutcome.Failed(e.error)
            }
        }
    }

    /**
     * Wie viele Änderungen dieses Geräts noch nicht auf dem Server sind: geänderte, neue und gelöschte Abende und
     * Spieler sowie ein geändertes Regelwerk für neue Abende. Ohne Runde 0.
     */
    suspend fun pendingCount(): Int {
        val link = store.link.first() ?: return 0
        val localEvenings = evenings.evenings.first()
        val eveningBases = store.eveningBases()
        val localPlayers = players.players.first()
        val playerBases = store.playerBases()
        val eveningIds = localEvenings.mapTo(HashSet()) { it.id }
        val playerIds = localPlayers.mapTo(HashSet()) { it.id }
        return localEvenings.count { eveningBases[it.id]?.evening != it } +
            eveningBases.keys.count { it !in eveningIds } +
            localPlayers.count { playerBases[it.id]?.sameContent(it) != true } +
            playerBases.keys.count { it !in playerIds } +
            (if (settings.settings.first().defaultRules != link.rulesBase) 1 else 0)
    }

    /** Führt [block] aus, während kein Abgleich läuft (für das Verbinden und Trennen, siehe [RoundConnection]). */
    internal suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Ein Durchlauf von [sync] mit seinem Zwischenstand. */
    private inner class Run(private var link: RoundLink) {
        private var pulled = 0
        private var pushed = 0
        private val unreadable = HashSet<EveningId>()
        private val notices = ArrayList<SyncNotice>()

        /** Die Nummer, die der Server der nächsten Änderung gibt, wenn außer diesem Gerät niemand schreibt. */
        private var nextRev = 0L

        /** Zwischen den eigenen Änderungen hat jemand anders geschrieben: Es gibt noch etwas zu holen. */
        private var gap = false

        /** Es ist noch etwas hochzuladen, das erst in diesem Durchgang entstanden ist. */
        private var again = false

        private var eveningBases: MutableMap<EveningId, EveningBase> = HashMap()
        private var playerBases: MutableMap<PlayerId, RemotePlayer> = HashMap()

        /** Spieler, die der Server als gelöscht meldet. Ob sie hier wirklich gehen, entscheidet sich nach den Abenden. */
        private val removals = LinkedHashSet<PlayerId>()

        suspend fun execute(quick: Boolean): SyncOutcome {
            for (pass in 1..MAX_PASSES) {
                val head = api.head(link.roundId) ?: return detach()
                if (quick && pass == 1 && head.rev == link.cursor) return SyncOutcome.Unchanged
                pass(head)
                if (!gap && !again) break
            }
            return SyncOutcome.Synced(pulled, pushed, pendingCount(), unreadable.size, notices)
        }

        /** Der Kopf der Runde fehlt: Gibt es sie wirklich nicht mehr, wird die Verbindung gelöst. */
        private suspend fun detach(): SyncOutcome {
            // Sicherheitshalber gegenprüfen, damit eine einzelne seltsame Antwort die Runde nicht vom Gerät trennt.
            if (api.myRounds().any { it.id == link.roundId }) return SyncOutcome.Failed(RoundError.SERVER)
            store.setLink(null)
            store.clearBases()
            return SyncOutcome.Detached
        }

        private suspend fun pass(head: RoundHead) {
            val stored = link
            // Ein Server, der hinter den bekannten Stand zurückfällt (neu aufgesetzt): alles noch einmal lesen.
            if (head.rev < link.cursor) link = link.copy(cursor = 0)
            nextRev = head.rev + 1
            gap = false
            again = false
            link = link.copy(roundName = head.name, isCreator = head.createdBy == link.userId)

            var remotePlayers = emptyList<RemotePlayer>()
            var remoteEvenings = emptyList<RemoteEvening>()
            if (head.rev > link.cursor) {
                val members = api.members(link.roundId)
                remotePlayers = api.playersSince(link.roundId, link.cursor)
                remoteEvenings = api.eveningsSince(link.roundId, link.cursor)
                link = link.copy(members = members, playerId = members.firstOrNull { it.userId == link.userId }?.playerId)
            }
            // Spieler vor den Abenden: Kein Abend soll auf dem Server oder hier jemanden nennen, den es dort nicht gibt.
            // Gelöscht wird erst danach, wenn feststeht, dass kein Abend den Spieler braucht.
            applyPlayers(remotePlayers)
            pushPlayers()
            syncRules(head)
            syncEvenings(remoteEvenings)
            removePlayers()

            // Alles bis zum Stand des Kopfes ist verarbeitet, dazu die eigenen Änderungen, solange niemand dazwischen
            // geschrieben hat. Was darüber hinaus gelesen wurde, kommt beim nächsten Mal noch einmal und ändert nichts.
            link = link.copy(cursor = nextRev - 1)
            if (link != stored) store.setLink(link)
        }

        /** Merkt sich eine hochgeladene Änderung und ob die Nummern des Servers noch lückenlos die eigenen sind. */
        private fun countPush(rev: Long) {
            pushed++
            if (!gap && rev == nextRev) nextRev++ else gap = true
        }

        // --- Spieler ---

        private suspend fun setPlayerBase(id: PlayerId, base: RemotePlayer?) {
            if (base == null) playerBases.remove(id) else playerBases[id] = base
            store.setPlayerBase(id, base)
        }

        /** Was der Server an Spielern geändert hat, gilt. Neue Spieler in der Reihenfolge, in der sie angelegt wurden. */
        private suspend fun applyPlayers(rows: List<RemotePlayer>) {
            playerBases = store.playerBases().toMutableMap()
            removals.clear()
            if (rows.isEmpty()) return
            val local = players.players.first().associateBy { it.id }
            for (row in rows.sortedWith(compareBy<RemotePlayer> { it.position }.thenBy { it.rev })) {
                val mine = local[row.id]
                if (row.deleted) {
                    if (mine == null) setPlayerBase(row.id, null) else removals += row.id
                    continue
                }
                val base = playerBases[row.id]
                val theirs = row.toPlayer()
                // Steht auf dem Server noch, was dieses Gerät zuletzt kannte, bleibt eine Änderung von hier bestehen.
                val newOnServer = base == null || !base.sameContent(theirs)
                if (newOnServer && mine != theirs) {
                    players.save(theirs)
                    pulled++
                }
                setPlayerBase(row.id, row)
            }
        }

        /** Was hier an Spielern neu oder geändert ist, hochladen. */
        private suspend fun pushPlayers() {
            val local = players.players.first()
            var eveningsNow: List<Evening>? = null
            suspend fun inUse(id: PlayerId): Boolean {
                val list = eveningsNow ?: evenings.evenings.first().also { eveningsNow = it }
                return list.any { it.involves(id) }
            }
            for (current in local) {
                if (current.id in removals) continue
                val base = playerBases[current.id]
                if (base != null && base.sameContent(current)) continue
                val player = withServerColor(current)
                if (rejected[player.id] == player) continue
                try {
                    val rev = api.savePlayer(link.roundId, player)
                    countPush(rev)
                    val saved = base ?: RemotePlayer(player.id, player.name, player.colorIndex, player.archived)
                    setPlayerBase(
                        player.id,
                        saved.copy(name = player.name, colorIndex = player.colorIndex, archived = player.archived, deleted = false, rev = rev),
                    )
                } catch (e: RoundException) {
                    if (e.error !in PLAYER_REJECTIONS) throw e
                    when {
                        // Der Server bleibt bei seinem Stand: zurück auf das, was dort steht.
                        base != null -> {
                            players.save(base.toPlayer())
                            notices += SyncNotice(player.id, player.name, e.error, base.name)
                        }
                        // Hier neu angelegt und noch nirgends gebraucht: wieder weg.
                        !inUse(player.id) -> {
                            players.delete(player.id)
                            notices += SyncNotice(player.id, player.name, e.error, null)
                        }
                        // Wird in einem Abend gebraucht, aber der Name ist vergeben: unter anderem Namen hochladen.
                        e.error == RoundError.NAME_TAKEN -> {
                            val renamed = player.copy(name = freeName(player.name, local.map { it.name }))
                            players.save(renamed)
                            notices += SyncNotice(player.id, player.name, e.error, renamed.name)
                            again = true
                        }
                        else -> {
                            rejected[player.id] = player
                            notices += SyncNotice(player.id, player.name, e.error, player.name)
                        }
                    }
                }
            }
        }

        /**
         * Löscht Spieler, die der Server als gelöscht meldet, und lädt hoch, was hier gelöscht wurde. Beides erst nach
         * den Abenden und nie, solange ein Abend auf diesem Gerät den Spieler nennt: Dann bleibt er (oder kommt zurück).
         */
        private suspend fun removePlayers() {
            val local = players.players.first().mapTo(HashSet()) { it.id }
            val deletedHere = playerBases.keys.filter { it !in local }
            if (removals.isEmpty() && deletedHere.isEmpty()) return
            val all = evenings.evenings.first()
            for (id in removals) {
                when {
                    id !in local -> Unit
                    // Wird hier noch gebraucht: Der Spieler bleibt und geht im nächsten Durchgang als neu wieder hoch.
                    all.any { it.involves(id) } -> again = true
                    else -> {
                        players.delete(id)
                        pulled++
                    }
                }
                setPlayerBase(id, null)
            }
            for (id in deletedHere) {
                val base = playerBases[id] ?: continue
                if (all.any { it.involves(id) }) {
                    // Inzwischen nennt ihn ein Abend, der von einem anderen Gerät kam: Der Spieler kommt zurück.
                    players.save(base.toPlayer())
                    pulled++
                    continue
                }
                try {
                    countPush(api.savePlayer(link.roundId, base.toPlayer(), deleted = true))
                    setPlayerBase(id, null)
                } catch (e: RoundException) {
                    if (e.error !in PLAYER_REJECTIONS) throw e
                    // Der Spieler gehört einem Mitglied und lässt sich nicht löschen: Er kommt zurück.
                    players.save(base.toPlayer())
                    notices += SyncNotice(id, base.name, e.error, base.name)
                }
            }
        }

        /** Der Server kennt Farben von 0 bis 31; größere Nummern (dieselbe Farbe) werden vorher hier umgestellt. */
        private suspend fun withServerColor(player: Player): Player {
            if (player.colorIndex in 0 until SERVER_COLOR_COUNT) return player
            val changed = player.copy(colorIndex = Math.floorMod(player.colorIndex, SERVER_COLOR_COUNT))
            players.save(changed)
            return changed
        }

        // --- Regelwerk für neue Abende ---

        private suspend fun syncRules(head: RoundHead) {
            if (head.rulesUnreadable) return
            val local = settings.settings.first().defaultRules
            val theirs = head.rules
            when {
                // Der Server hat ein anderes als zuletzt bekannt: Es gilt, auch gegen eine Änderung von hier.
                theirs != null && theirs != link.rulesBase -> {
                    if (local != theirs) {
                        settings.update { it.copy(defaultRules = theirs) }
                        pulled++
                    }
                    link = link.copy(rulesBase = theirs)
                }
                // Der Server hat noch keines, oder hier wurde es geändert.
                theirs == null || local != link.rulesBase -> {
                    countPush(api.saveRules(link.roundId, local))
                    link = link.copy(rulesBase = local)
                }
            }
        }

        // --- Abende ---

        private suspend fun syncEvenings(rows: List<RemoteEvening>) {
            eveningBases = store.eveningBases().toMutableMap()
            val local = evenings.evenings.first()
            // Kommt ein Abend mehrfach (er wurde während des Lesens noch einmal geändert), zählt die neueste Fassung.
            val latest = LinkedHashMap<EveningId, RemoteEvening>()
            for (row in rows) {
                val known = latest[row.id]
                if (known == null || row.version >= known.version) latest[row.id] = row
            }
            val ids = LinkedHashSet(latest.keys)
            val localIds = HashSet<EveningId>()
            for (evening in local) {
                localIds += evening.id
                if (eveningBases[evening.id]?.evening != evening) ids += evening.id
            }
            eveningBases.keys.filterTo(ids) { it !in localIds }
            for (id in ids) syncEvening(id, latest[id])
        }

        private suspend fun setBase(id: EveningId, base: EveningBase?) {
            if (base == null) eveningBases.remove(id) else eveningBases[id] = base
            store.setEveningBase(id, base)
        }

        private suspend fun syncEvening(id: EveningId, pulledRow: RemoteEvening?) {
            var row = pulledRow
            repeat(MAX_TRIES) {
                if (attempt(id, row)) return
                // Jemand anders war schneller: die Fassung des Servers neu lesen und noch einmal. Dann gibt es auch
                // sonst vielleicht Neues, das der nächste Durchgang holt.
                gap = true
                row = api.evening(link.roundId, id)
                if (row == null) setBase(id, null)
            }
        }

        /** @return `false`, wenn der Server inzwischen eine andere Fassung hat als angenommen. */
        private suspend fun attempt(id: EveningId, row: RemoteEvening?): Boolean {
            // Fällt der Server hinter die bekannte Fassung zurück, wird ohne Basis zusammengeführt: So geht nichts verloren.
            val base = eveningBases[id]?.takeIf { row == null || row.version >= it.version }
            if (row == null) return pushLocal(id, base)
            if (row.deleted) return applyDeletion(id, row, base)
            val remote = row.evening
            if (remote == null) {
                unreadable += id
                return true
            }
            return mergeWith(id, row, remote, base)
        }

        /** Der Server hat nichts Neueres als die Basis (oder kennt den Abend nicht): den Stand von hier hochladen. */
        private suspend fun pushLocal(id: EveningId, base: EveningBase?): Boolean {
            val local = evenings.evening(id).first()
            val saved = when {
                base == null -> api.saveEvening(link.roundId, local ?: return true, expectedVersion = 0)
                local == null -> api.saveEvening(link.roundId, base.evening, base.version, deleted = true)
                local == base.evening -> return true
                else -> api.saveEvening(link.roundId, local, base.version)
            } ?: return false
            setBase(id, local?.let { EveningBase(saved.version, it) })
            countPush(saved.rev)
            return true
        }

        /** Der Server meldet den Abend als gelöscht. */
        private suspend fun applyDeletion(id: EveningId, row: RemoteEvening, base: EveningBase?): Boolean {
            var removed = false
            val kept = editor.replace(id) { current ->
                removed = current != null && current == base?.evening
                if (removed) null else current
            }
            if (kept == null) {
                if (removed) pulled++
                setBase(id, null)
                return true
            }
            // Hier wurde der Abend geändert, während ihn ein anderes Gerät gelöscht hat: Die Änderung gewinnt.
            val saved = api.saveEvening(link.roundId, kept, row.version, deleted = false) ?: return false
            setBase(id, EveningBase(saved.version, kept))
            countPush(saved.rev)
            return true
        }

        /** Der Server hat eine lesbare Fassung: mit dem Stand von hier zusammenführen und das Ergebnis hochladen. */
        private suspend fun mergeWith(id: EveningId, row: RemoteEvening, remote: Evening, base: EveningBase?): Boolean {
            var changedHere = false
            val merged = editor.replace(id) { current ->
                val result = when {
                    current != null -> EveningMerge.merge3(base?.evening, current, remote)
                    // Hier unbekannt, oder hier gelöscht und auf dem Server seitdem geändert: (wieder) anlegen.
                    base == null || row.version != base.version -> remote
                    // Hier gelöscht und auf dem Server unverändert: bleibt gelöscht.
                    else -> null
                }
                changedHere = result != current
                result
            }
            if (changedHere) pulled++
            if (merged == null) {
                val saved = api.saveEvening(link.roundId, remote, row.version, deleted = true) ?: return false
                setBase(id, null)
                countPush(saved.rev)
                return true
            }
            // Erst der Abend, dann die Basis: Bricht es dazwischen ab, führt der nächste Abgleich noch einmal zusammen
            // und kommt zum selben Ergebnis. Andersherum sähe Neues vom Server aus, als wäre es hier gelöscht worden.
            setBase(id, EveningBase(row.version, remote))
            if (merged == remote) return true
            val saved = api.saveEvening(link.roundId, merged, row.version) ?: return false
            setBase(id, EveningBase(saved.version, merged))
            countPush(saved.rev)
            return true
        }
    }

    private companion object {
        /** Höchstens so oft läuft ein Abgleich hintereinander durch, bevor er auf den nächsten Anstoß wartet. */
        const val MAX_PASSES = 2

        /** Höchstens so oft wird ein Abend je Durchgang hochgeladen, wenn andere gleichzeitig schreiben. */
        const val MAX_TRIES = 3

        /** Der Server speichert Farbnummern von 0 bis 31 (ein Vielfaches der Farben der Oberfläche). */
        const val SERVER_COLOR_COUNT = 32

        /** Ablehnungen, die nur den einen Spieler betreffen; alles andere bricht den Abgleich ab. */
        val PLAYER_REJECTIONS = setOf(
            RoundError.NAME_LOCKED,
            RoundError.NAME_TAKEN,
            RoundError.INVALID_NAME,
            RoundError.PLAYER_OWNED,
            RoundError.LIMIT_REACHED,
        )

        /** Ein Name, den noch niemand trägt: der gewünschte mit einer angehängten Nummer. */
        fun freeName(name: String, taken: Collection<String>): String {
            val used = taken.mapTo(HashSet()) { it.lowercase() }
            var number = 2
            while (true) {
                val suffix = " $number"
                val candidate = name.take(Player.MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
                if (candidate.lowercase() !in used) return candidate
                number++
            }
        }
    }
}
