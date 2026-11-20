package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.UnreadableDataException
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Was sich ein Gerät zum Abgleich mit der gemeinsamen Runde merkt, als JSON-Text für die Datenbank: die verbundene
 * Runde und je Abend die zuletzt bekannte Fassung des Servers. Abende und Regelwerk stehen darin in derselben
 * Schreibweise wie in der Sicherung und auf dem Server.
 */
object RoundStoreJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeLink(link: RoundLink): String = json.encodeToString(
        LinkDto.serializer(),
        LinkDto(
            roundId = link.roundId.value,
            roundName = link.roundName,
            userId = link.userId,
            playerId = link.playerId?.value,
            isCreator = link.isCreator,
            cursor = link.cursor,
            rulesBase = link.rulesBase?.let(StorageCodec::encodeRulesElement),
            members = link.members.map { MemberDto(it.userId, it.playerId?.value) },
            unreadable = link.unreadable.map { it.value },
        ),
    )

    /** @return die Runde oder `null`, wenn sich der Text nicht lesen lässt. */
    fun decodeLink(text: String): RoundLink? = try {
        val dto = json.decodeFromString(LinkDto.serializer(), text)
        RoundLink(
            roundId = RoundId(dto.roundId),
            roundName = dto.roundName,
            userId = dto.userId,
            playerId = dto.playerId?.let(::PlayerId),
            isCreator = dto.isCreator,
            cursor = dto.cursor,
            // Ein Regelwerk, das sich nicht mehr lesen lässt, gilt als unbekannt: Dann zählt das des Servers.
            rulesBase = dto.rulesBase?.takeUnless { it is JsonNull }?.let { element ->
                try {
                    StorageCodec.decodeRulesElement(element)
                } catch (e: UnreadableDataException) {
                    null
                }
            },
            members = dto.members.map { RoundMember(it.userId, it.playerId?.let(::PlayerId)) },
            unreadable = dto.unreadable.mapTo(LinkedHashSet(), ::EveningId),
        )
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun encodeEvening(evening: Evening): String = StorageCodec.encodeEvening(evening)

    /** @return der Abend oder `null`, wenn sich der Text nicht lesen lässt. */
    fun decodeEvening(text: String): Evening? = try {
        StorageCodec.decodeEvening(text)
    } catch (e: UnreadableDataException) {
        null
    }

    @Serializable
    private data class LinkDto(
        val roundId: String,
        val roundName: String,
        val userId: String,
        val playerId: String? = null,
        val isCreator: Boolean = false,
        val cursor: Long = 0,
        val rulesBase: JsonElement? = null,
        val members: List<MemberDto> = emptyList(),
        // Fehlt in Ständen von vor dem 20.11.2026: dann keine.
        val unreadable: List<String> = emptyList(),
    )

    @Serializable
    private data class MemberDto(val userId: String, val playerId: String? = null)
}
