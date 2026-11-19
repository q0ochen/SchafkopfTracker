package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.UnreadableDataException
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.RemoteEvening
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundApi
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import io.github.q0ochen.schafkopf.domain.round.RoundHead
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.SavedEvening
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Der Server der gemeinsamen Runde über die Datenschnittstelle von Supabase (PostgREST): gelesen wird direkt aus den
 * Tabellen (der Server zeigt jedem Konto nur seine Runden), geschrieben nur über die Funktionen aus
 * docs/ARCHITEKTUR.md, Abschnitt 8.3.
 *
 * Jeder Aufruf scheitert höchstens mit einer [RoundException]: ohne Netz [RoundError.OFFLINE], ohne gültige
 * Anmeldung [RoundError.SIGNED_OUT], mit einem Fehlercode der Funktionen dem passenden [RoundError], sonst
 * [RoundError.SERVER]. Nach einer Antwort 401 wird genau einmal mit einem erneuerten Token wiederholt.
 */
class SupabaseRoundApi(
    private val http: SupabaseHttp,
    private val tokens: AccessTokenSource,
) : RoundApi {

    // --- Lesen ---

    override suspend fun head(round: RoundId): RoundHead? {
        val rows = read(
            "rounds",
            listOf("id" to "eq.${round.value}", "select" to "id,name,rev,rules,created_by"),
            ListSerializer(RoundRow.serializer()),
        )
        val row = rows.firstOrNull() ?: return null
        val document = row.rules?.takeUnless { it is JsonNull }
        val rules = document?.let(::rulesOrNull)
        return RoundHead(
            id = RoundId(row.id),
            name = row.name,
            rev = row.rev,
            rules = rules,
            createdBy = row.createdBy,
            rulesUnreadable = document != null && rules == null,
        )
    }

    override suspend fun members(round: RoundId): List<RoundMember> = read(
        "round_members",
        listOf("round_id" to "eq.${round.value}", "select" to "user_id,player_id,joined_at", "order" to "joined_at.asc"),
        ListSerializer(MemberRow.serializer()),
    ).map { RoundMember(it.userId, it.playerId?.let(::PlayerId)) }

    override suspend fun playersSince(round: RoundId, rev: Long): List<RemotePlayer> = read(
        "round_players",
        listOf(
            "round_id" to "eq.${round.value}",
            "rev" to "gt.$rev",
            "select" to "id,name,color_index,archived,position,owner,name_locked,deleted,rev",
            "order" to "rev.asc",
        ),
        ListSerializer(PlayerRow.serializer()),
    ).map { RemotePlayer(PlayerId(it.id), it.name, it.colorIndex, it.archived, it.position, it.owner, it.nameLocked, it.deleted, it.rev) }

    override suspend fun eveningsSince(round: RoundId, rev: Long): List<RemoteEvening> {
        val all = ArrayList<RemoteEvening>()
        var after = rev
        // Seitenweise, solange volle Seiten kommen; die Grenze schützt vor einem Server, der den Filter nicht beachtet.
        repeat(MAX_PAGES) {
            val page = evenings(round, listOf("rev" to "gt.$after", "order" to "rev.asc", "limit" to PAGE_SIZE.toString()))
            all += page
            val last = page.lastOrNull()?.rev
            if (page.size < PAGE_SIZE || last == null || last <= after) return all
            after = last
        }
        return all
    }

    override suspend fun evening(round: RoundId, id: EveningId): RemoteEvening? =
        evenings(round, listOf("id" to "eq.${id.value}")).firstOrNull()

    private suspend fun evenings(round: RoundId, filter: List<Pair<String, String>>): List<RemoteEvening> = read(
        "round_evenings",
        listOf("round_id" to "eq.${round.value}", "select" to "id,doc,version,deleted,rev") + filter,
        ListSerializer(EveningRow.serializer()),
    ).map { RemoteEvening(EveningId(it.id), it.version, it.deleted, it.rev, eveningOrNull(it.id, it.doc)) }

    // --- Schreiben über die Funktionen des Servers ---

    override suspend fun saveEvening(round: RoundId, evening: Evening, expectedVersion: Int, deleted: Boolean): SavedEvening? {
        val body = buildJsonObject {
            put("p_round", round.value)
            put("p_id", evening.id.value)
            put("p_doc", StorageCodec.encodeEveningElement(evening))
            put("p_expected_version", expectedVersion)
            put("p_deleted", deleted)
        }
        // Passt die erwartete Fassung nicht, kommt kein Fehler, sondern nichts.
        return decode(rpc("save_evening", body), ListSerializer(SavedRow.serializer())).firstOrNull()
            ?.let { SavedEvening(it.version, it.rev) }
    }

    override suspend fun savePlayer(round: RoundId, player: Player, deleted: Boolean): Long = decode(
        rpc(
            "save_player",
            buildJsonObject {
                put("p_round", round.value)
                put("p_id", player.id.value)
                put("p_name", player.name)
                put("p_color_index", player.colorIndex)
                put("p_archived", player.archived)
                put("p_deleted", deleted)
            },
        ),
        Long.serializer(),
    )

    override suspend fun saveRules(round: RoundId, rules: RuleSet): Long = decode(
        rpc(
            "save_rules",
            buildJsonObject {
                put("p_round", round.value)
                put("p_rules", StorageCodec.encodeRulesElement(rules))
            },
        ),
        Long.serializer(),
    )

    override suspend fun myRounds(): List<RoundSummary> =
        decode(rpc("my_rounds", JsonObject(emptyMap())), ListSerializer(SummaryRow.serializer()))
            .map { RoundSummary(RoundId(it.id), it.name, it.rev, it.memberCount, it.myPlayerId?.let(::PlayerId), it.isCreator) }

    override suspend fun createRound(name: String): RoundId =
        RoundId(decode(rpc("create_round", buildJsonObject { put("p_name", name) }), String.serializer()))

    override suspend fun createInvite(round: RoundId): String =
        decode(rpc("create_invite", buildJsonObject { put("p_round", round.value) }), String.serializer())

    override suspend fun inviteInfo(token: String): InviteInfo {
        val row = decode(rpc("invite_info", buildJsonObject { put("p_token", token) }), ListSerializer(InviteRow.serializer()))
            .firstOrNull() ?: throw RoundException(RoundError.INVALID_INVITE)
        return InviteInfo(RoundId(row.roundId), row.roundName, row.memberCount, row.alreadyMember)
    }

    override suspend fun joinRound(token: String): RoundId =
        RoundId(decode(rpc("join_round", buildJsonObject { put("p_token", token) }), String.serializer()))

    override suspend fun leaveRound(round: RoundId) {
        rpc("leave_round", buildJsonObject { put("p_round", round.value) })
    }

    override suspend fun claimPlayer(round: RoundId, player: PlayerId, name: String, colorIndex: Int) {
        rpc(
            "claim_player",
            buildJsonObject {
                put("p_round", round.value)
                put("p_player", player.value)
                put("p_name", name)
                put("p_color_index", colorIndex)
            },
        )
    }

    // --- Gemeinsames ---

    private suspend fun <T> read(table: String, query: List<Pair<String, String>>, serializer: KSerializer<T>): T {
        val url = http.url("rest/v1/$table", query)
        return decode(call { token -> http.get(url, token) }, serializer)
    }

    private suspend fun rpc(name: String, body: JsonObject): String {
        val url = http.url("rest/v1/rpc/$name")
        return call { token -> http.post(url, body, token) }
    }

    /** Führt den Aufruf im Namen des Kontos aus und liefert den Inhalt der Antwort. */
    private suspend fun call(send: suspend (token: String) -> HttpAnswer): String {
        val token = tokens.accessToken()
        var answer = send(token)
        if (answer.status == UNAUTHORIZED) answer = send(tokens.refreshAfterUnauthorized(token))
        if (answer.isSuccessful) return answer.body
        val code = answer.errorCode().orEmpty()
        throw when {
            answer.status == UNAUTHORIZED -> RoundException(RoundError.SIGNED_OUT, "HTTP 401 $code".trim())
            // Die Datenbank kann mit dem Inhalt nichts anfangen (zum Beispiel ein Nullzeichen in einem Text): Das
            // trifft nur dieses eine Stück und wird durch Wiederholen nicht besser.
            answer.status == BAD_REQUEST && (code.startsWith(DATA_EXCEPTION_CLASS) || code == INVALID_BODY) ->
                RoundException(RoundError.INVALID_DOC, "HTTP 400 $code")
            else -> RoundError.fromCode(code)?.let(::RoundException)
                ?: RoundException(RoundError.SERVER, "HTTP ${answer.status} $code".trim())
        }
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw RoundException(RoundError.SERVER, "Antwort nicht lesbar", e)
    } catch (e: IllegalArgumentException) {
        throw RoundException(RoundError.SERVER, "Antwort nicht lesbar", e)
    }

    /** Der Abend aus dem Dokument; `null`, wenn es sich nicht lesen lässt (zum Beispiel von einer neueren App). */
    private fun eveningOrNull(id: String, document: JsonElement): Evening? = try {
        StorageCodec.decodeEveningElement(document).takeIf { it.id.value == id }
    } catch (e: UnreadableDataException) {
        null
    }

    private fun rulesOrNull(document: JsonElement): RuleSet? = try {
        StorageCodec.decodeRulesElement(document)
    } catch (e: UnreadableDataException) {
        null
    }

    @Serializable
    private data class RoundRow(
        val id: String,
        val name: String,
        val rev: Long,
        val rules: JsonElement? = null,
        @SerialName("created_by") val createdBy: String? = null,
    )

    @Serializable
    private data class MemberRow(
        @SerialName("user_id") val userId: String,
        @SerialName("player_id") val playerId: String? = null,
    )

    @Serializable
    private data class PlayerRow(
        val id: String,
        val name: String,
        @SerialName("color_index") val colorIndex: Int,
        val archived: Boolean,
        val position: Int,
        val owner: String? = null,
        @SerialName("name_locked") val nameLocked: Boolean,
        val deleted: Boolean,
        val rev: Long,
    )

    @Serializable
    private data class EveningRow(val id: String, val doc: JsonElement, val version: Int, val deleted: Boolean, val rev: Long)

    @Serializable
    private data class SavedRow(val version: Int, val rev: Long)

    @Serializable
    private data class SummaryRow(
        val id: String,
        val name: String,
        val rev: Long,
        @SerialName("member_count") val memberCount: Int,
        @SerialName("my_player_id") val myPlayerId: String? = null,
        @SerialName("is_creator") val isCreator: Boolean,
    )

    @Serializable
    private data class InviteRow(
        @SerialName("round_id") val roundId: String,
        @SerialName("round_name") val roundName: String,
        @SerialName("member_count") val memberCount: Int,
        @SerialName("already_member") val alreadyMember: Boolean,
    )

    private companion object {
        const val UNAUTHORIZED = 401
        const val BAD_REQUEST = 400

        /** SQLSTATE-Klasse 22: Die Datenbank lehnt einen Wert ab („data exception“). */
        const val DATA_EXCEPTION_CLASS = "22"

        /** Die Datenschnittstelle konnte den gesendeten Inhalt nicht lesen. */
        const val INVALID_BODY = "PGRST102"

        /** So viele Abende kommen höchstens je Anfrage. */
        const val PAGE_SIZE = 100

        /** Mehr Seiten gibt es nie: Eine Runde hat höchstens 3000 Abende. */
        const val MAX_PAGES = 40

        val json = Json { ignoreUnknownKeys = true }
    }
}
