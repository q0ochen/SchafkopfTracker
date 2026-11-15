package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.DateTimeException
import java.time.Instant

/*
 * Übersetzung zwischen den Klassen der Fachlogik und dem Dateiformat (siehe Dtos.kt), in beide Richtungen.
 * `toDto()` führt in die Datei, `toDomain()` zurück. Ein ungültiger Zeitpunkt in einer Datei löst beim
 * Zurückübersetzen eine Ausnahme aus; StorageCodec macht daraus einen sauberen Lesefehler.
 */

// ---------- Zeitpunkte ----------

/** Frühester und spätester Zeitpunkt, der sich noch in Millisekunden seit 1970 ausdrücken lässt. */
private val EARLIEST_INSTANT: Instant = Instant.ofEpochMilli(Long.MIN_VALUE)
private val LATEST_INSTANT: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)

/**
 * Liest einen Zeitpunkt aus ISO-8601-Text (zum Beispiel 2026-11-14T19:00:00Z).
 *
 * Zeitpunkte, die Hunderte Millionen Jahre entfernt liegen, gelten als ungültig: Sie lassen sich nicht mehr in
 * Millisekunden ausdrücken (so speichert sie die Datenbank) und zum Teil nicht einmal als Datum anzeigen.
 *
 * @throws DateTimeException wenn der Text kein Zeitpunkt ist oder der Zeitpunkt außerhalb dieses Bereichs liegt.
 */
internal fun parseInstant(text: String): Instant {
    val instant = Instant.parse(text)
    if (instant.isBefore(EARLIEST_INSTANT) || instant.isAfter(LATEST_INSTANT)) {
        throw DateTimeException("Der Zeitpunkt $text liegt außerhalb des Bereichs, mit dem die App rechnen kann.")
    }
    return instant
}

// ---------- Spieler ----------

internal fun Player.toDto(): PlayerDto =
    PlayerDto(id = id.value, name = name, colorIndex = colorIndex, archived = archived)

internal fun PlayerDto.toDomain(): Player =
    Player(id = PlayerId(id), name = name, colorIndex = colorIndex, archived = archived)

// ---------- Regelwerk ----------

internal fun BockRules.toDto(): BockRulesDto = BockRulesDto(
    enabled = enabled,
    gamesPerTrigger = gamesPerTrigger,
    factor = factor,
    stacking = stacking.toDto(),
    thrownInConsumesBockGame = thrownInConsumesBockGame,
)

internal fun BockRulesDto.toDomain(): BockRules = BockRules(
    enabled = enabled,
    gamesPerTrigger = gamesPerTrigger,
    factor = factor,
    stacking = stacking.toDomain(),
    thrownInConsumesBockGame = thrownInConsumesBockGame,
)

/** Die Spielarten stehen immer in derselben Reihenfolge in der Datei, damit gleiche Regeln gleiche Dateien ergeben. */
internal fun RuleSet.toDto(): RuleSetDto = RuleSetDto(
    name = name,
    unit = unit,
    prices = prices.toSortedMap().mapKeys { it.key.toDto() },
    enabledTypes = enabledTypes.sorted().map { it.toDto() },
    laufendeMin = laufendeMin.toSortedMap().mapKeys { it.key.toDto() },
    laufendeMax = laufendeMax.toSortedMap().mapKeys { it.key.toDto() },
    klopfenEnabled = klopfenEnabled,
    kontraEnabled = kontraEnabled,
    retourEnabled = retourEnabled,
    toutFactor = toutFactor,
    sieFactor = sieFactor,
    sieLaufende = sieLaufende,
    bock = bock.toDto(),
    dealerAdvancesAfterThrowIn = dealerAdvancesAfterThrowIn,
    displayAsMoney = displayAsMoney,
    payoutRule = payoutRule.toDto(),
)

internal fun RuleSetDto.toDomain(): RuleSet = RuleSet(
    name = name,
    unit = unit,
    prices = prices.mapKeys { it.key.toDomain() },
    enabledTypes = enabledTypes.mapTo(LinkedHashSet()) { it.toDomain() },
    laufendeMin = laufendeMin.mapKeys { it.key.toDomain() },
    laufendeMax = laufendeMax.mapKeys { it.key.toDomain() },
    klopfenEnabled = klopfenEnabled,
    kontraEnabled = kontraEnabled,
    retourEnabled = retourEnabled,
    toutFactor = toutFactor,
    sieFactor = sieFactor,
    sieLaufende = sieLaufende,
    bock = bock.toDomain(),
    dealerAdvancesAfterThrowIn = dealerAdvancesAfterThrowIn,
    displayAsMoney = displayAsMoney,
    payoutRule = payoutRule.toDomain(),
)

// ---------- Abend und Einträge ----------

internal fun Evening.toDto(): EveningDto = EveningDto(
    schemaVersion = SCHEMA_VERSION,
    id = id.value,
    startedAt = startedAt.toString(),
    finishedAt = finishedAt?.toString(),
    location = location,
    seating = seating.map { it.value },
    firstDealer = firstDealer.value,
    rules = rules.toDto(),
    entries = entries.map { it.toDto() },
)

internal fun EveningDto.toDomain(): Evening = Evening(
    id = EveningId(id),
    startedAt = parseInstant(startedAt),
    finishedAt = finishedAt?.let { parseInstant(it) },
    location = location,
    seating = seating.map { PlayerId(it) },
    firstDealer = PlayerId(firstDealer),
    rules = rules.toDomain(),
    entries = entries.map { it.toDomain() },
)

internal fun EveningEntry.toDto(): EntryDto = when (this) {
    is GameEntry -> GameEntryDto(
        id = id.value,
        recordedAt = recordedAt.toString(),
        gameType = type.toDto(),
        suit = suit?.toDto(),
        declarer = declarer.value,
        partner = partner?.value,
        declarerWon = declarerWon,
        level = level.toDto(),
        laufende = laufende,
        announcement = announcement.toDto(),
        klopfer = klopfer.map { it.value },
        kontra = kontra.toDto(),
        kontraBy = kontraBy?.value,
        retourBy = retourBy?.value,
        bockOverride = bockOverride.toDto(),
        declarerEyes = declarerEyes,
        note = note,
    )

    is ThrownInEntry -> ThrownInEntryDto(id = id.value, recordedAt = recordedAt.toString())

    is BockTriggerEntry -> BockTriggerEntryDto(
        id = id.value,
        recordedAt = recordedAt.toString(),
        reason = reason.toDto(),
        player = player?.value,
        handThrownIn = handThrownIn,
    )

    is ManualEntry -> ManualEntryDto(
        id = id.value,
        recordedAt = recordedAt.toString(),
        amounts = amounts.mapKeys { it.key.value },
        note = note,
    )
}

internal fun EntryDto.toDomain(): EveningEntry = when (this) {
    is GameEntryDto -> GameEntry(
        id = EntryId(id),
        recordedAt = parseInstant(recordedAt),
        type = gameType.toDomain(),
        suit = suit?.toDomain(),
        declarer = PlayerId(declarer),
        partner = partner?.let { PlayerId(it) },
        declarerWon = declarerWon,
        level = level.toDomain(),
        laufende = laufende,
        announcement = announcement.toDomain(),
        klopfer = klopfer.mapTo(LinkedHashSet()) { PlayerId(it) },
        kontra = kontra.toDomain(),
        kontraBy = kontraBy?.let { PlayerId(it) },
        retourBy = retourBy?.let { PlayerId(it) },
        bockOverride = bockOverride.toDomain(),
        declarerEyes = declarerEyes,
        note = note,
    )

    is ThrownInEntryDto -> ThrownInEntry(id = EntryId(id), recordedAt = parseInstant(recordedAt))

    is BockTriggerEntryDto -> BockTriggerEntry(
        id = EntryId(id),
        recordedAt = parseInstant(recordedAt),
        reason = reason.toDomain(),
        player = player?.let { PlayerId(it) },
        handThrownIn = handThrownIn,
    )

    is ManualEntryDto -> ManualEntry(
        id = EntryId(id),
        recordedAt = parseInstant(recordedAt),
        amounts = amounts.mapKeys { PlayerId(it.key) },
        note = note,
    )
}

// ---------- Einstellungen ----------

internal fun AiSettings.toDto(): AiSettingsDto = AiSettingsDto(
    enabled = enabled,
    provider = provider.toDto(),
    model = model,
    baseUrl = baseUrl,
    sendRealNames = sendRealNames,
    consentHost = consentHost,
)

internal fun AiSettingsDto.toDomain(): AiSettings = AiSettings(
    enabled = enabled,
    provider = provider.toDomain(),
    model = model,
    baseUrl = baseUrl,
    sendRealNames = sendRealNames,
    consentHost = consentHost,
)

internal fun AppSettings.toDto(): SettingsDto = SettingsDto(
    schemaVersion = SCHEMA_VERSION,
    defaultRules = defaultRules.toDto(),
    keepScreenOn = keepScreenOn,
    themeMode = themeMode.toDto(),
    haptics = haptics,
    ai = ai.toDto(),
)

internal fun SettingsDto.toDomain(): AppSettings = AppSettings(
    defaultRules = defaultRules.toDomain(),
    keepScreenOn = keepScreenOn,
    themeMode = themeMode.toDomain(),
    haptics = haptics,
    ai = ai.toDomain(),
)

// ---------- Aufzählungen ----------
// Jede Zuordnung ist ausgeschrieben: Kommt in der Fachlogik ein Wert dazu, lässt sich das Modul erst wieder
// übersetzen, wenn hier entschieden wurde, wie er in der Datei heißt.

internal fun GameType.toDto(): GameTypeDto = when (this) {
    GameType.RUFSPIEL -> GameTypeDto.RUFSPIEL
    GameType.UEBER_KREUZ -> GameTypeDto.UEBER_KREUZ
    GameType.FARBSOLO -> GameTypeDto.FARBSOLO
    GameType.WENZ -> GameTypeDto.WENZ
    GameType.GEIER -> GameTypeDto.GEIER
}

internal fun GameTypeDto.toDomain(): GameType = when (this) {
    GameTypeDto.RUFSPIEL -> GameType.RUFSPIEL
    GameTypeDto.UEBER_KREUZ -> GameType.UEBER_KREUZ
    GameTypeDto.FARBSOLO -> GameType.FARBSOLO
    GameTypeDto.WENZ -> GameType.WENZ
    GameTypeDto.GEIER -> GameType.GEIER
}

internal fun PayoutRule.toDto(): PayoutRuleDto = when (this) {
    PayoutRule.SCHAFKOPF -> PayoutRuleDto.SCHAFKOPF
    PayoutRule.POT_SPLIT -> PayoutRuleDto.POT_SPLIT
}

internal fun PayoutRuleDto.toDomain(): PayoutRule = when (this) {
    PayoutRuleDto.SCHAFKOPF -> PayoutRule.SCHAFKOPF
    PayoutRuleDto.POT_SPLIT -> PayoutRule.POT_SPLIT
}

internal fun Suit.toDto(): SuitDto = when (this) {
    Suit.EICHEL -> SuitDto.EICHEL
    Suit.GRAS -> SuitDto.GRAS
    Suit.HERZ -> SuitDto.HERZ
    Suit.SCHELLEN -> SuitDto.SCHELLEN
}

internal fun SuitDto.toDomain(): Suit = when (this) {
    SuitDto.EICHEL -> Suit.EICHEL
    SuitDto.GRAS -> Suit.GRAS
    SuitDto.HERZ -> Suit.HERZ
    SuitDto.SCHELLEN -> Suit.SCHELLEN
}

internal fun ResultLevel.toDto(): ResultLevelDto = when (this) {
    ResultLevel.NORMAL -> ResultLevelDto.NORMAL
    ResultLevel.SCHNEIDER -> ResultLevelDto.SCHNEIDER
    ResultLevel.SCHWARZ -> ResultLevelDto.SCHWARZ
}

internal fun ResultLevelDto.toDomain(): ResultLevel = when (this) {
    ResultLevelDto.NORMAL -> ResultLevel.NORMAL
    ResultLevelDto.SCHNEIDER -> ResultLevel.SCHNEIDER
    ResultLevelDto.SCHWARZ -> ResultLevel.SCHWARZ
}

internal fun Announcement.toDto(): AnnouncementDto = when (this) {
    Announcement.NONE -> AnnouncementDto.NONE
    Announcement.TOUT -> AnnouncementDto.TOUT
    Announcement.SIE -> AnnouncementDto.SIE
}

internal fun AnnouncementDto.toDomain(): Announcement = when (this) {
    AnnouncementDto.NONE -> Announcement.NONE
    AnnouncementDto.TOUT -> Announcement.TOUT
    AnnouncementDto.SIE -> Announcement.SIE
}

internal fun KontraLevel.toDto(): KontraLevelDto = when (this) {
    KontraLevel.NONE -> KontraLevelDto.NONE
    KontraLevel.KONTRA -> KontraLevelDto.KONTRA
    KontraLevel.RETOUR -> KontraLevelDto.RETOUR
}

internal fun KontraLevelDto.toDomain(): KontraLevel = when (this) {
    KontraLevelDto.NONE -> KontraLevel.NONE
    KontraLevelDto.KONTRA -> KontraLevel.KONTRA
    KontraLevelDto.RETOUR -> KontraLevel.RETOUR
}

internal fun BockStacking.toDto(): BockStackingDto = when (this) {
    BockStacking.APPEND -> BockStackingDto.APPEND
    BockStacking.STACK -> BockStackingDto.STACK
    BockStacking.IGNORE -> BockStackingDto.IGNORE
}

internal fun BockStackingDto.toDomain(): BockStacking = when (this) {
    BockStackingDto.APPEND -> BockStacking.APPEND
    BockStackingDto.STACK -> BockStacking.STACK
    BockStackingDto.IGNORE -> BockStacking.IGNORE
}

internal fun BockOverride.toDto(): BockOverrideDto = when (this) {
    BockOverride.AUTO -> BockOverrideDto.AUTO
    BockOverride.FORCE_ON -> BockOverrideDto.FORCE_ON
    BockOverride.FORCE_OFF -> BockOverrideDto.FORCE_OFF
}

internal fun BockOverrideDto.toDomain(): BockOverride = when (this) {
    BockOverrideDto.AUTO -> BockOverride.AUTO
    BockOverrideDto.FORCE_ON -> BockOverride.FORCE_ON
    BockOverrideDto.FORCE_OFF -> BockOverride.FORCE_OFF
}

internal fun BockReason.toDto(): BockReasonDto = when (this) {
    BockReason.SECHS_NIXER -> BockReasonDto.SECHS_NIXER
    BockReason.MANUAL -> BockReasonDto.MANUAL
}

internal fun BockReasonDto.toDomain(): BockReason = when (this) {
    BockReasonDto.SECHS_NIXER -> BockReason.SECHS_NIXER
    BockReasonDto.MANUAL -> BockReason.MANUAL
}

internal fun ThemeMode.toDto(): ThemeModeDto = when (this) {
    ThemeMode.SYSTEM -> ThemeModeDto.SYSTEM
    ThemeMode.LIGHT -> ThemeModeDto.LIGHT
    ThemeMode.DARK -> ThemeModeDto.DARK
}

internal fun ThemeModeDto.toDomain(): ThemeMode = when (this) {
    ThemeModeDto.SYSTEM -> ThemeMode.SYSTEM
    ThemeModeDto.LIGHT -> ThemeMode.LIGHT
    ThemeModeDto.DARK -> ThemeMode.DARK
}

internal fun AiProvider.toDto(): AiProviderDto = when (this) {
    AiProvider.GEMINI -> AiProviderDto.GEMINI
    AiProvider.OPENAI_COMPATIBLE -> AiProviderDto.OPENAI_COMPATIBLE
}

internal fun AiProviderDto.toDomain(): AiProvider = when (this) {
    AiProviderDto.GEMINI -> AiProvider.GEMINI
    AiProviderDto.OPENAI_COMPATIBLE -> AiProvider.OPENAI_COMPATIBLE
}
