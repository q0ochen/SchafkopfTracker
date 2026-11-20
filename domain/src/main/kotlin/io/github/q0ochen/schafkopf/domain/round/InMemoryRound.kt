package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/*
 * Die gemeinsame Runde ganz im Speicher: ein Gerätezustand und ein nachgestellter Server. Beides dient Tests und
 * Vorschauen der Oberfläche, wie die Umsetzungen in `domain.ports`. Der Server verhält sich nach dem Vertrag aus
 * docs/ARCHITEKTUR.md, Abschnitt 8.2 und 8.3; maßgeblich bleibt der echte Server in `supabase/` mit seinen Tests.
 */

/** Was sich ein Gerät zum Abgleich merkt, nur im Speicher. */
class InMemoryRoundStore(initial: RoundLink? = null) : RoundStore {
    private val state = MutableStateFlow(initial)
    private val evenings = LinkedHashMap<EveningId, EveningBase>()
    private val players = LinkedHashMap<PlayerId, RemotePlayer>()

    override val link: Flow<RoundLink?> = state

    override suspend fun setLink(link: RoundLink?) {
        state.value = link
    }

    override suspend fun eveningBases(): Map<EveningId, EveningBase> = synchronized(this) { evenings.toMap() }

    override suspend fun setEveningBase(id: EveningId, base: EveningBase?): Unit = synchronized(this) {
        if (base == null) evenings.remove(id) else evenings[id] = base
    }

    override suspend fun playerBases(): Map<PlayerId, RemotePlayer> = synchronized(this) { players.toMap() }

    override suspend fun setPlayerBase(id: PlayerId, base: RemotePlayer?): Unit = synchronized(this) {
        if (base == null) players.remove(id) else players[id] = base
    }

    override suspend fun clearBases(): Unit = synchronized(this) {
        evenings.clear()
        players.clear()
    }
}

/**
 * Ein Server für gemeinsame Runden im Speicher. Jedes Konto bekommt mit [sessionFor] seinen eigenen Zugang; alle
 * Zugänge sehen dieselben Runden.
 */
class InMemoryRoundServer(private val clock: Clock = Clock.systemUTC()) {

    private class Round(val id: RoundId, val name: String, val createdBy: String?) {
        var rev = 0L
        var rules: RuleSet? = null
        var rulesUnreadable = false

        /** Konto → sein Spieler, in der Reihenfolge des Beitritts. */
        val members = LinkedHashMap<String, PlayerId?>()
        val players = LinkedHashMap<PlayerId, RemotePlayer>()
        val evenings = LinkedHashMap<EveningId, RemoteEvening>()

        /** Einladungscode → Ablauf. */
        val invites = LinkedHashMap<String, Instant>()

        fun nextRev(): Long = ++rev
    }

    private val rounds = LinkedHashMap<RoundId, Round>()
    private var created = 0

    /** Der Zugang eines Kontos. */
    fun sessionFor(userId: String): Session = Session(userId)

    // --- Für Tests: nachsehen und stören ---

    /** Die aktuelle Änderungsnummer der Runde; 0, wenn es sie nicht gibt. */
    fun rev(round: RoundId): Long = synchronized(this) { rounds[round]?.rev ?: 0 }

    /** Der Abend, wie er auf dem Server liegt. */
    fun eveningRow(round: RoundId, id: EveningId): RemoteEvening? = synchronized(this) { rounds[round]?.evenings?.get(id) }

    /** Alle Abende der Runde, die nicht gelöscht sind. */
    fun evenings(round: RoundId): List<Evening> = synchronized(this) {
        rounds[round]?.evenings?.values.orEmpty().filterNot { it.deleted }.mapNotNull { it.evening }
    }

    /** Alle Spieler der Runde, die nicht gelöscht sind, in der Reihenfolge des Anlegens. */
    fun players(round: RoundId): List<RemotePlayer> = synchronized(this) {
        rounds[round]?.players?.values.orEmpty().filterNot { it.deleted }.sortedBy { it.position }
    }

    /** Ersetzt das Dokument eines Abends durch eines, das sich nicht lesen lässt (wie von einer neueren App). */
    fun makeUnreadable(round: RoundId, id: EveningId): Unit = synchronized(this) {
        val state = rounds.getValue(round)
        val row = state.evenings.getValue(id)
        state.evenings[id] = row.copy(version = row.version + 1, rev = state.nextRev(), evening = null)
    }

    /**
     * Macht das Dokument eines Abends wieder lesbar, ohne dass sich auf dem Server etwas ändert – wie nach einem
     * Update der App auf dem Gerät, das ihn bisher nicht lesen konnte.
     */
    fun makeReadable(round: RoundId, id: EveningId, evening: Evening): Unit = synchronized(this) {
        val state = rounds.getValue(round)
        state.evenings[id] = state.evenings.getValue(id).copy(evening = evening)
    }

    /** Vergisst einen Abend ganz, als hätte es ihn auf dem Server nie gegeben. */
    fun forgetEvening(round: RoundId, id: EveningId): Unit = synchronized(this) {
        rounds.getValue(round).evenings.remove(id)
    }

    /** Hinterlegt ein Regelwerk, das sich nicht lesen lässt. */
    fun makeRulesUnreadable(round: RoundId): Unit = synchronized(this) {
        val state = rounds.getValue(round)
        state.rules = null
        state.rulesUnreadable = true
        state.nextRev()
    }

    /** Entfernt ein Konto aus der Runde, wie es `leave_round` täte. */
    fun removeMember(round: RoundId, userId: String): Unit = synchronized(this) {
        val state = rounds[round] ?: return
        state.members.remove(userId)
        state.nextRev()
        if (state.members.isEmpty()) rounds.remove(round)
    }

    /** Setzt die Runde auf einen früheren Stand zurück, wie nach dem Einspielen einer alten Sicherung des Servers. */
    fun rollBack(round: RoundId, id: EveningId, version: Int, evening: Evening, rev: Long): Unit = synchronized(this) {
        val state = rounds.getValue(round)
        state.rev = rev
        state.evenings[id] = RemoteEvening(id, version, deleted = false, rev = rev, evening = evening)
    }

    /** Der Zugang eines Kontos zum Server. Lässt sich für Tests vom Netz nehmen. */
    inner class Session(val userId: String) : RoundApi {
        /** Kein Netz: Jeder Aufruf scheitert mit [RoundError.OFFLINE]. */
        @Volatile
        var offline = false

        /** Die Anmeldung ist abgelaufen: Jeder Aufruf scheitert mit [RoundError.SIGNED_OUT]. */
        @Volatile
        var signedOut = false

        /** Die nächste Änderung kommt auf dem Server an, aber ihre Antwort geht verloren. */
        @Volatile
        var loseNextAnswer = false

        /** Die Namen aller Aufrufe in ihrer Reihenfolge (zum Zählen der Anfragen). */
        val calls: MutableList<String> = ArrayList()

        private fun <T> call(name: String, write: Boolean = false, block: () -> T): T = synchronized(this@InMemoryRoundServer) {
            calls += name
            if (offline) throw RoundException(RoundError.OFFLINE)
            if (signedOut) throw RoundException(RoundError.SIGNED_OUT)
            val result = block()
            if (write && loseNextAnswer) {
                loseNextAnswer = false
                throw RoundException(RoundError.OFFLINE, "Antwort verloren")
            }
            result
        }

        /** Die Runde, wenn das Konto ihr angehört; sonst der Fehler `not_member`. */
        private fun member(round: RoundId): Round =
            rounds[round]?.takeIf { userId in it.members } ?: throw RoundException(RoundError.NOT_MEMBER)

        /** Die Runde zum Lesen: Wer nicht dazugehört, sieht einfach nichts. */
        private fun visible(round: RoundId): Round? = rounds[round]?.takeIf { userId in it.members }

        override suspend fun head(round: RoundId): RoundHead? = call("head") {
            visible(round)?.let { RoundHead(it.id, it.name, it.rev, it.rules, it.createdBy, it.rulesUnreadable) }
        }

        override suspend fun members(round: RoundId): List<RoundMember> = call("members") {
            visible(round)?.members.orEmpty().map { (user, player) -> RoundMember(user, player) }
        }

        override suspend fun playersSince(round: RoundId, rev: Long): List<RemotePlayer> = call("playersSince") {
            visible(round)?.players?.values.orEmpty().filter { it.rev > rev }.sortedBy { it.rev }
        }

        override suspend fun eveningsSince(round: RoundId, rev: Long): List<RemoteEvening> = call("eveningsSince") {
            visible(round)?.evenings?.values.orEmpty().filter { it.rev > rev }.sortedBy { it.rev }
        }

        override suspend fun evening(round: RoundId, id: EveningId): RemoteEvening? = call("evening") {
            visible(round)?.evenings?.get(id)
        }

        override suspend fun saveEvening(round: RoundId, evening: Evening, expectedVersion: Int, deleted: Boolean): SavedEvening? =
            call("saveEvening", write = true) {
                val state = member(round)
                val current = state.evenings[evening.id]
                if (evening.id.value.length !in 1..MAX_ID_LENGTH) throw RoundException(RoundError.INVALID_DOC)
                if (evening.entries.size > MAX_ENTRIES) throw RoundException(RoundError.TOO_LARGE)
                when {
                    current == null && expectedVersion != 0 -> null
                    current != null && current.version != expectedVersion -> null
                    current == null && state.evenings.size >= MAX_EVENINGS -> throw RoundException(RoundError.LIMIT_REACHED)
                    else -> {
                        val row = RemoteEvening(evening.id, expectedVersion + 1, deleted, state.nextRev(), evening)
                        state.evenings[evening.id] = row
                        SavedEvening(row.version, row.rev)
                    }
                }
            }

        override suspend fun savePlayer(round: RoundId, player: Player, deleted: Boolean): Long = call("savePlayer", write = true) {
            val state = member(round)
            if (player.id.value.length !in 1..MAX_ID_LENGTH) throw RoundException(RoundError.INVALID_DOC)
            val current = state.players[player.id]
            if (deleted) {
                if (current != null && !current.deleted) {
                    if (current.owner != null) throw RoundException(RoundError.PLAYER_OWNED)
                    state.players[player.id] = current.copy(deleted = true, rev = state.nextRev())
                }
                return@call state.rev
            }
            val name = Player.cleanName(player.name) ?: throw RoundException(RoundError.INVALID_NAME)
            val colorIndex = Math.floorMod(player.colorIndex, COLOR_COUNT)
            if (current != null && !current.deleted) {
                if (name != current.name) {
                    if (current.nameLocked && state.createdBy != userId) throw RoundException(RoundError.NAME_LOCKED)
                    state.requireFreeName(name, player.id)
                }
                state.players[player.id] =
                    current.copy(name = name, colorIndex = colorIndex, archived = player.archived, rev = state.nextRev())
            } else {
                if (state.players.values.count { !it.deleted } >= MAX_PLAYERS) throw RoundException(RoundError.LIMIT_REACHED)
                state.requireFreeName(name, player.id)
                state.players[player.id] = RemotePlayer(
                    id = player.id,
                    name = name,
                    colorIndex = colorIndex,
                    archived = player.archived,
                    position = current?.position ?: state.nextPosition(),
                    rev = state.nextRev(),
                )
            }
            state.rev
        }

        override suspend fun saveRules(round: RoundId, rules: RuleSet): Long = call("saveRules", write = true) {
            val state = member(round)
            if (rules.name.length > MAX_RULES_NAME_LENGTH) throw RoundException(RoundError.TOO_LARGE)
            state.rules = rules
            state.rulesUnreadable = false
            state.nextRev()
        }

        override suspend fun myRounds(): List<RoundSummary> = call("myRounds") {
            rounds.values.filter { userId in it.members }.map {
                RoundSummary(it.id, it.name, it.rev, it.members.size, it.members[userId], it.createdBy == userId)
            }
        }

        override suspend fun createRound(name: String): RoundId = call("createRound", write = true) {
            val clean = name.trim().takeIf { it.length in 1..MAX_ROUND_NAME_LENGTH } ?: throw RoundException(RoundError.INVALID_NAME)
            requireRoomForRound()
            val round = Round(RoundId("runde-${++created}"), clean, userId)
            // Das Anlegen mit dem ersten Mitglied ist die erste Änderung der Runde.
            round.rev = 1
            round.members[userId] = null
            rounds[round.id] = round
            round.id
        }

        override suspend fun createInvite(round: RoundId): String = call("createInvite", write = true) {
            val state = member(round)
            val now = clock.instant()
            state.invites.values.removeIf { !it.isAfter(now) }
            if (state.invites.size >= MAX_INVITES) throw RoundException(RoundError.LIMIT_REACHED)
            val token = "%032x".format(++created)
            state.invites[token] = now.plus(INVITE_LIFETIME)
            token
        }

        override suspend fun inviteInfo(token: String): InviteInfo = call("inviteInfo") {
            val state = invited(token)
            InviteInfo(state.id, state.name, state.members.size, userId in state.members)
        }

        override suspend fun joinRound(token: String): RoundId = call("joinRound", write = true) {
            val state = invited(token)
            if (userId !in state.members) {
                if (state.members.size >= MAX_MEMBERS) throw RoundException(RoundError.LIMIT_REACHED)
                requireRoomForRound()
                // Wer schon einmal dabei war, bekommt seinen Spieler zurück.
                state.members[userId] = state.players.values.firstOrNull { it.owner == userId && !it.deleted }?.id
                state.nextRev()
            }
            state.id
        }

        override suspend fun leaveRound(round: RoundId): Unit = call("leaveRound", write = true) {
            val state = member(round)
            state.members.remove(userId)
            state.nextRev()
            if (state.members.isEmpty()) rounds.remove(round)
        }

        override suspend fun claimPlayer(round: RoundId, player: PlayerId, name: String, colorIndex: Int): Unit =
            call("claimPlayer", write = true) {
                val state = member(round)
                val mine = state.members[userId]
                // Derselbe Spieler noch einmal ist erlaubt (zum Beispiel nach einer verlorenen Antwort).
                if (mine != null && mine != player) throw RoundException(RoundError.ALREADY_CLAIMED)
                val current = state.players[player]?.takeIf { !it.deleted }
                if (current?.owner != null && current.owner != userId) throw RoundException(RoundError.PLAYER_TAKEN)
                val color = Math.floorMod(colorIndex, COLOR_COUNT)
                when {
                    // Gehörte der Spieler dem Konto schon, bleibt sein Name.
                    current?.owner == userId ->
                        state.players[player] = current.copy(nameLocked = true, colorIndex = color, rev = state.nextRev())
                    current != null -> {
                        val clean = state.freeName(name, player)
                        state.players[player] =
                            current.copy(name = clean, owner = userId, nameLocked = true, colorIndex = color, rev = state.nextRev())
                    }
                    else -> {
                        if (state.players.values.count { !it.deleted } >= MAX_PLAYERS) throw RoundException(RoundError.LIMIT_REACHED)
                        state.players[player] = RemotePlayer(
                            id = player,
                            name = state.freeName(name, player),
                            colorIndex = color,
                            archived = false,
                            position = state.players[player]?.position ?: state.nextPosition(),
                            owner = userId,
                            nameLocked = true,
                            rev = state.nextRev(),
                        )
                    }
                }
                state.members[userId] = player
            }

        private fun invited(token: String): Round {
            val now = clock.instant()
            return rounds.values.firstOrNull { round -> round.invites[token]?.isAfter(now) == true }
                ?: throw RoundException(RoundError.INVALID_INVITE)
        }

        private fun requireRoomForRound() {
            if (rounds.values.count { userId in it.members } >= MAX_ROUNDS) throw RoundException(RoundError.LIMIT_REACHED)
        }

        /** Namen sind je Runde eindeutig, ohne auf Groß- und Kleinschreibung zu achten. */
        private fun Round.requireFreeName(name: String, except: PlayerId) {
            val taken = players.values.any { !it.deleted && it.id != except && it.name.lowercase() == name.lowercase() }
            if (taken) throw RoundException(RoundError.NAME_TAKEN)
        }

        /** Der bereinigte Name, wenn er gültig und noch frei ist. */
        private fun Round.freeName(name: String, except: PlayerId): String {
            val clean = Player.cleanName(name) ?: throw RoundException(RoundError.INVALID_NAME)
            requireFreeName(clean, except)
            return clean
        }

        /** Die Position für einen neuen Spieler: die größte der Runde plus eins (der erste bekommt 1). */
        private fun Round.nextPosition(): Int = (players.values.maxOfOrNull { it.position } ?: 0) + 1
    }

    companion object {
        const val MAX_ROUNDS = 5
        const val MAX_MEMBERS = 12
        const val MAX_PLAYERS = 40
        const val MAX_EVENINGS = 3000
        const val MAX_INVITES = 20
        const val MAX_ROUND_NAME_LENGTH = 40
        const val MAX_ID_LENGTH = 64

        /**
         * Der echte Server begrenzt die Größe eines Abends auf 300 000 Bytes und die des Regelwerks auf 20 000. Hier
         * stehen dafür einfache Zahlen, an denen sich die Ablehnung zeigen lässt.
         */
        const val MAX_ENTRIES = 1000
        const val MAX_RULES_NAME_LENGTH = 10_000

        /** Der Server speichert Farbnummern von 0 bis 31. */
        const val COLOR_COUNT = 32
        val INVITE_LIFETIME: Duration = Duration.ofDays(14)
    }
}
