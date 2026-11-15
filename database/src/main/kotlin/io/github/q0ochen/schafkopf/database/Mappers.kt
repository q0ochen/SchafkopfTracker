package io.github.q0ochen.schafkopf.database

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.Instant

/**
 * Wandelt das Regelwerk eines Abends in Text für die Spalte `rules_json` und zurück.
 * Die Umsetzung liegt im Modul `data` (dieselbe Darstellung wie in der Sicherungsdatei).
 */
interface RulesCodec {
    fun encode(rules: RuleSet): String

    /** @return `null`, wenn sich der Text nicht lesen lässt. */
    fun decode(text: String): RuleSet?
}

/*
 * Übersetzung zwischen den Tabellenzeilen und den Modellen der Fachlogik.
 * Aufzählungen werden mit ihrem Namen gespeichert: Die Namen der Enum-Werte sind damit Teil des
 * Datenbank-Schemas und dürfen nicht umbenannt werden (ein Test wacht darüber).
 */

internal fun PlayerEntity.toDomain() = Player(PlayerId(id), name, colorIndex, archived)

internal fun Evening.toEntity(rulesCodec: RulesCodec) = EveningEntity(
    id = id.value,
    startedAt = startedAt.toEpochMilli(),
    finishedAt = finishedAt?.toEpochMilli(),
    location = location,
    seat1 = seating.getOrNull(0)?.value.orEmpty(),
    seat2 = seating.getOrNull(1)?.value.orEmpty(),
    seat3 = seating.getOrNull(2)?.value.orEmpty(),
    seat4 = seating.getOrNull(3)?.value.orEmpty(),
    firstDealer = firstDealer.value,
    rulesJson = rulesCodec.encode(rules),
)

/**
 * Baut den Abend aus den Tabellenzeilen. Lässt sich das gespeicherte Regelwerk nicht lesen, gelten die
 * Standardregeln; unlesbare Einträge werden übersprungen. Abstürzen darf die App deswegen nie.
 */
internal fun EveningWithEntries.toDomain(rulesCodec: RulesCodec) = Evening(
    id = EveningId(evening.id),
    startedAt = Instant.ofEpochMilli(evening.startedAt),
    finishedAt = evening.finishedAt?.let(Instant::ofEpochMilli),
    location = evening.location,
    seating = listOf(evening.seat1, evening.seat2, evening.seat3, evening.seat4).filter { it.isNotEmpty() }.map(::PlayerId),
    firstDealer = PlayerId(evening.firstDealer),
    rules = rulesCodec.decode(evening.rulesJson) ?: RuleSet.standard(),
    entries = entries.sortedBy { it.position }.mapNotNull { it.toDomain() },
)

internal fun EveningEntry.toEntity(eveningId: String, position: Int): EntryEntity {
    val base = EntryEntity(
        id = id.value,
        eveningId = eveningId,
        position = position,
        kind = "",
        recordedAt = recordedAt.toEpochMilli(),
    )
    return when (this) {
        is GameEntry -> base.copy(
            kind = EntryEntity.KIND_GAME,
            gameType = type.name,
            suit = suit?.name,
            declarer = declarer.value,
            partner = partner?.value,
            declarerWon = declarerWon,
            level = level.name,
            laufende = laufende,
            announcement = announcement.name,
            gelegtBy = klopfer.joinToString(LIST_SEPARATOR) { it.value },
            kontra = kontra.name,
            kontraBy = kontraBy?.value,
            retourBy = retourBy?.value,
            bockOverride = bockOverride.name,
            declarerEyes = declarerEyes,
            note = note,
        )

        is ThrownInEntry -> base.copy(kind = EntryEntity.KIND_THROWN_IN)

        is BockTriggerEntry -> base.copy(
            kind = EntryEntity.KIND_BOCK,
            bockReason = reason.name,
            bockPlayer = player?.value,
            handThrownIn = handThrownIn,
        )

        is ManualEntry -> base.copy(
            kind = EntryEntity.KIND_MANUAL,
            manualAmounts = amounts.entries.joinToString(LIST_SEPARATOR) { "${it.key.value}$PAIR_SEPARATOR${it.value}" },
            note = note,
        )
    }
}

/** @return `null`, wenn die Zeile nicht lesbar ist (unbekannte Art oder unbekannter Wert). */
internal fun EntryEntity.toDomain(): EveningEntry? {
    val entryId = EntryId(id)
    val time = Instant.ofEpochMilli(recordedAt)
    return when (kind) {
        EntryEntity.KIND_GAME -> GameEntry(
            id = entryId,
            recordedAt = time,
            type = enumOrNull<GameType>(gameType) ?: return null,
            suit = enumOrNull<Suit>(suit),
            declarer = PlayerId(declarer ?: return null),
            partner = partner?.let(::PlayerId),
            declarerWon = declarerWon ?: return null,
            level = enumOrNull<ResultLevel>(level) ?: ResultLevel.NORMAL,
            laufende = laufende ?: 0,
            announcement = enumOrNull<Announcement>(announcement) ?: Announcement.NONE,
            klopfer = gelegtBy.orEmpty().split(LIST_SEPARATOR).filter { it.isNotEmpty() }.map(::PlayerId).toSet(),
            kontra = enumOrNull<KontraLevel>(kontra) ?: KontraLevel.NONE,
            kontraBy = kontraBy?.let(::PlayerId),
            retourBy = retourBy?.let(::PlayerId),
            bockOverride = enumOrNull<BockOverride>(bockOverride) ?: BockOverride.AUTO,
            declarerEyes = declarerEyes,
            note = note.orEmpty(),
        )

        EntryEntity.KIND_THROWN_IN -> ThrownInEntry(entryId, time)

        EntryEntity.KIND_BOCK -> BockTriggerEntry(
            id = entryId,
            recordedAt = time,
            reason = enumOrNull<BockReason>(bockReason) ?: BockReason.MANUAL,
            player = bockPlayer?.let(::PlayerId),
            handThrownIn = handThrownIn ?: true,
        )

        EntryEntity.KIND_MANUAL -> ManualEntry(
            id = entryId,
            recordedAt = time,
            amounts = manualAmounts.orEmpty().split(LIST_SEPARATOR).mapNotNull { pair ->
                val player = pair.substringBefore(PAIR_SEPARATOR, "")
                val cents = pair.substringAfter(PAIR_SEPARATOR, "").toIntOrNull()
                if (player.isEmpty() || cents == null) null else PlayerId(player) to cents
            }.toMap(),
            note = note.orEmpty(),
        )

        else -> null
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { wanted -> enumValues<T>().firstOrNull { it.name == wanted } }

private const val LIST_SEPARATOR = ","
private const val PAIR_SEPARATOR = "="
