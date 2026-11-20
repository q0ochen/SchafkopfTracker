package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.flow.Flow

/**
 * Der Server der gemeinsamen Runde, aus Sicht des angemeldeten Kontos (docs/ARCHITEKTUR.md, Abschnitt 8.3).
 * Jeder Aufruf kann mit einer [RoundException] scheitern.
 */
interface RoundApi {
    /** Der Kopf der Runde; `null`, wenn es sie nicht mehr gibt oder das Konto kein Mitglied (mehr) ist. */
    suspend fun head(round: RoundId): RoundHead?

    /** Die Mitglieder der Runde, das älteste zuerst. */
    suspend fun members(round: RoundId): List<RoundMember>

    /** Alle Spieler, die nach der Änderung [rev] geschrieben wurden, in der Reihenfolge der Änderungen. */
    suspend fun playersSince(round: RoundId, rev: Long): List<RemotePlayer>

    /** Alle Abende, die nach der Änderung [rev] geschrieben wurden, in der Reihenfolge der Änderungen. */
    suspend fun eveningsSince(round: RoundId, rev: Long): List<RemoteEvening>

    /** Ein einzelner Abend; `null`, wenn der Server ihn nicht kennt. */
    suspend fun evening(round: RoundId, id: EveningId): RemoteEvening?

    /**
     * Speichert einen Abend, wenn der Server noch die Fassung [expectedVersion] hat (0: Der Abend ist neu).
     *
     * @return die neue Fassung oder `null`, wenn inzwischen jemand anders gespeichert hat.
     */
    suspend fun saveEvening(round: RoundId, evening: Evening, expectedVersion: Int, deleted: Boolean = false): SavedEvening?

    /** Legt einen Gast an oder ändert einen Spieler; [deleted] löscht einen Gast. Liefert das neue `rev`. */
    suspend fun savePlayer(round: RoundId, player: Player, deleted: Boolean = false): Long

    /** Setzt das Regelwerk für neue Abende. Liefert das neue `rev`. */
    suspend fun saveRules(round: RoundId, rules: RuleSet): Long

    /** Die Runden des angemeldeten Kontos. */
    suspend fun myRounds(): List<RoundSummary>

    suspend fun createRound(name: String): RoundId

    /** Ein neuer Einladungscode für die Runde. */
    suspend fun createInvite(round: RoundId): String

    suspend fun inviteInfo(token: String): InviteInfo

    /** Tritt der Runde hinter dem Einladungscode bei. */
    suspend fun joinRound(token: String): RoundId

    suspend fun leaveRound(round: RoundId)

    /** „Das bin ich“: übernimmt den Spieler [player] oder legt ihn an und legt den eigenen Namen fest. */
    suspend fun claimPlayer(round: RoundId, player: PlayerId, name: String, colorIndex: Int)
}

/** Was sich ein Gerät zum Abgleich merkt (docs/ARCHITEKTUR.md, Abschnitt 8.4). */
interface RoundStore {
    /** Die verbundene Runde; `null`, wenn das Gerät für sich allein arbeitet. */
    val link: Flow<RoundLink?>

    suspend fun setLink(link: RoundLink?)

    /** Je Abend die Fassung, die dieses Gerät zuletzt vom Server kennt. */
    suspend fun eveningBases(): Map<EveningId, EveningBase>

    /** Setzt oder (mit `null`) entfernt die Basis eines Abends. */
    suspend fun setEveningBase(id: EveningId, base: EveningBase?)

    /** Je Spieler der Stand, den dieses Gerät zuletzt vom Server kennt. */
    suspend fun playerBases(): Map<PlayerId, RemotePlayer>

    /** Setzt oder (mit `null`) entfernt die Basis eines Spielers. */
    suspend fun setPlayerBase(id: PlayerId, base: RemotePlayer?)

    /** Vergisst alle Basen (beim Verbinden mit einer Runde und beim Trennen). */
    suspend fun clearBases()
}
