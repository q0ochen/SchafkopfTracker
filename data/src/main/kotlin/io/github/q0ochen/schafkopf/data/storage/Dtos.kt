package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Das Dateiformat der App, Schema Version 1.
 *
 * Diese Klassen beschreiben genau, was in den JSON-Dateien steht. Sie sind bewusst von den Klassen der Fachlogik
 * getrennt: Wird dort etwas umbenannt, bleiben alte Dateien trotzdem lesbar, denn jeder Schlüssel ist hier fest
 * vergeben. Felder mit Standardwert dürfen in einer Datei fehlen (ältere Dateien), unbekannte Felder werden
 * überlesen (neuere Dateien). Ein unbekannter Wert einer Aufzählung macht die Datei dagegen unlesbar.
 *
 * Kennungen stehen als Text in der Datei, Zeitpunkte als ISO-8601-Text (zum Beispiel 2026-11-14T19:00:00Z),
 * Beträge als ganze Cent.
 */

/** Aktuelle Version des Dateiformats. Dateien mit höherer Version stammen aus einer neueren App. */
internal const val SCHEMA_VERSION = 1

@Serializable
internal enum class GameTypeDto {
    @SerialName("rufspiel") RUFSPIEL,
    @SerialName("ueber_kreuz") UEBER_KREUZ,
    @SerialName("farbsolo") FARBSOLO,
    @SerialName("wenz") WENZ,
    @SerialName("geier") GEIER,
}

/** Wie der Spielwert verteilt wird: nach der Schafkopf-Regel oder als Topf-Teilung. */
@Serializable
internal enum class PayoutRuleDto {
    @SerialName("schafkopf") SCHAFKOPF,
    @SerialName("pot_split") POT_SPLIT,
}

@Serializable
internal enum class SuitDto {
    @SerialName("eichel") EICHEL,
    @SerialName("gras") GRAS,
    @SerialName("herz") HERZ,
    @SerialName("schellen") SCHELLEN,
}

@Serializable
internal enum class ResultLevelDto {
    @SerialName("normal") NORMAL,
    @SerialName("schneider") SCHNEIDER,
    @SerialName("schwarz") SCHWARZ,
}

@Serializable
internal enum class AnnouncementDto {
    @SerialName("none") NONE,
    @SerialName("tout") TOUT,
    @SerialName("sie") SIE,
}

@Serializable
internal enum class KontraLevelDto {
    @SerialName("none") NONE,
    @SerialName("kontra") KONTRA,
    @SerialName("retour") RETOUR,
}

@Serializable
internal enum class BockStackingDto {
    @SerialName("append") APPEND,
    @SerialName("stack") STACK,
    @SerialName("ignore") IGNORE,
}

@Serializable
internal enum class BockOverrideDto {
    @SerialName("auto") AUTO,
    @SerialName("force_on") FORCE_ON,
    @SerialName("force_off") FORCE_OFF,
}

@Serializable
internal enum class BockReasonDto {
    @SerialName("sechs_nixer") SECHS_NIXER,
    @SerialName("manual") MANUAL,
}

@Serializable
internal enum class ThemeModeDto {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
internal enum class AiProviderDto {
    @SerialName("gemini") GEMINI,
    @SerialName("openai_compatible") OPENAI_COMPATIBLE,
}

/** Ein Spieler, so wie er in `players.json` und in der Sicherung steht. */
@Serializable
internal data class PlayerDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("colorIndex") val colorIndex: Int = 0,
    @SerialName("archived") val archived: Boolean = false,
)

/** Inhalt der Datei `players.json`. */
@Serializable
internal data class PlayersFileDto(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("players") val players: List<PlayerDto> = emptyList(),
)

/** Die Hausregel Bockrunde. */
@Serializable
internal data class BockRulesDto(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("gamesPerTrigger") val gamesPerTrigger: Int = 4,
    @SerialName("factor") val factor: Int = 2,
    @SerialName("stacking") val stacking: BockStackingDto = BockStackingDto.APPEND,
    @SerialName("thrownInConsumesBockGame") val thrownInConsumesBockGame: Boolean = false,
)

/**
 * Das Regelwerk, jeder Wert einzeln ausgeschrieben. Fehlt ein Wert in einer Datei, gilt der Wert der Vorlage
 * „Sauspiel 10-20-50 mit Bockrunde“, so wie sie bei Schema Version 1 war. Diese Standardwerte stehen absichtlich
 * fest hier und folgen späteren Änderungen an der Vorlage nicht.
 */
@Serializable
internal data class RuleSetDto(
    @SerialName("name") val name: String = "Sauspiel 10-20-50 mit Bockrunde",
    @SerialName("unit") val unit: Int = 10,
    @SerialName("prices") val prices: Map<GameTypeDto, Int> = mapOf(
        GameTypeDto.RUFSPIEL to 20,
        GameTypeDto.FARBSOLO to 50,
        GameTypeDto.WENZ to 50,
        GameTypeDto.GEIER to 50,
    ),
    @SerialName("enabledTypes") val enabledTypes: List<GameTypeDto> = listOf(
        GameTypeDto.RUFSPIEL,
        GameTypeDto.FARBSOLO,
        GameTypeDto.WENZ,
        GameTypeDto.GEIER,
    ),
    @SerialName("laufendeMin") val laufendeMin: Map<GameTypeDto, Int> = mapOf(
        GameTypeDto.RUFSPIEL to 3,
        GameTypeDto.FARBSOLO to 3,
        GameTypeDto.WENZ to 2,
        GameTypeDto.GEIER to 2,
    ),
    @SerialName("laufendeMax") val laufendeMax: Map<GameTypeDto, Int> = mapOf(
        GameTypeDto.RUFSPIEL to 14,
        GameTypeDto.FARBSOLO to 14,
        GameTypeDto.WENZ to 4,
        GameTypeDto.GEIER to 4,
    ),
    @SerialName("klopfenEnabled") val klopfenEnabled: Boolean = true,
    @SerialName("kontraEnabled") val kontraEnabled: Boolean = true,
    @SerialName("retourEnabled") val retourEnabled: Boolean = true,
    @SerialName("toutFactor") val toutFactor: Int = 2,
    @SerialName("sieFactor") val sieFactor: Int = 2,
    @SerialName("sieLaufende") val sieLaufende: Int = 8,
    @SerialName("bock") val bock: BockRulesDto = BockRulesDto(),
    @SerialName("dealerAdvancesAfterThrowIn") val dealerAdvancesAfterThrowIn: Boolean = true,
    @SerialName("displayAsMoney") val displayAsMoney: Boolean = true,
    // Fehlt in älteren Dateien: Dann gilt die Schafkopf-Regel, nach der bis dahin immer abgerechnet wurde.
    @SerialName("payoutRule") val payoutRule: PayoutRuleDto = PayoutRuleDto.SCHAFKOPF,
)

/**
 * Ein Eintrag eines Abends. In der Datei sagt das Feld `type`, um welche Art es sich handelt:
 * `game`, `thrown_in`, `bock` oder `manual`.
 */
@Serializable
internal sealed interface EntryDto {
    val id: String
    val recordedAt: String
}

/** Ein gespieltes Spiel. Die Spielart steht im Feld `gameType`, weil `type` schon die Art des Eintrags nennt. */
@Serializable
@SerialName("game")
internal data class GameEntryDto(
    @SerialName("id") override val id: String,
    @SerialName("recordedAt") override val recordedAt: String,
    @SerialName("gameType") val gameType: GameTypeDto,
    @SerialName("suit") val suit: SuitDto? = null,
    @SerialName("declarer") val declarer: String,
    @SerialName("partner") val partner: String? = null,
    @SerialName("declarerWon") val declarerWon: Boolean,
    @SerialName("level") val level: ResultLevelDto = ResultLevelDto.NORMAL,
    @SerialName("laufende") val laufende: Int = 0,
    @SerialName("announcement") val announcement: AnnouncementDto = AnnouncementDto.NONE,
    @SerialName("klopfer") val klopfer: List<String> = emptyList(),
    @SerialName("kontra") val kontra: KontraLevelDto = KontraLevelDto.NONE,
    @SerialName("kontraBy") val kontraBy: String? = null,
    @SerialName("retourBy") val retourBy: String? = null,
    @SerialName("bockOverride") val bockOverride: BockOverrideDto = BockOverrideDto.AUTO,
    @SerialName("declarerEyes") val declarerEyes: Int? = null,
    @SerialName("note") val note: String = "",
) : EntryDto

/** Das Blatt wurde zusammengeworfen. */
@Serializable
@SerialName("thrown_in")
internal data class ThrownInEntryDto(
    @SerialName("id") override val id: String,
    @SerialName("recordedAt") override val recordedAt: String,
) : EntryDto

/** Eine Bockrunde wurde ausgelöst. */
@Serializable
@SerialName("bock")
internal data class BockTriggerEntryDto(
    @SerialName("id") override val id: String,
    @SerialName("recordedAt") override val recordedAt: String,
    @SerialName("reason") val reason: BockReasonDto,
    @SerialName("player") val player: String? = null,
    @SerialName("handThrownIn") val handThrownIn: Boolean = true,
) : EntryDto

/** Eine freie Buchung: Betrag in Cent je Kennung eines Spielers. */
@Serializable
@SerialName("manual")
internal data class ManualEntryDto(
    @SerialName("id") override val id: String,
    @SerialName("recordedAt") override val recordedAt: String,
    @SerialName("amounts") val amounts: Map<String, Int>,
    @SerialName("note") val note: String = "",
) : EntryDto

/** Ein Abend: Inhalt einer Datei unter `evenings/` und ein Element der Sicherung. */
@Serializable
internal data class EveningDto(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("id") val id: String,
    @SerialName("startedAt") val startedAt: String,
    @SerialName("finishedAt") val finishedAt: String? = null,
    @SerialName("location") val location: String = "",
    @SerialName("seating") val seating: List<String>,
    @SerialName("firstDealer") val firstDealer: String,
    @SerialName("rules") val rules: RuleSetDto = RuleSetDto(),
    @SerialName("entries") val entries: List<EntryDto> = emptyList(),
)

/** Einstellungen der optionalen KI-Zusammenfassung. Der Schlüssel selbst steht nie in einer Datei der App. */
@Serializable
internal data class AiSettingsDto(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("provider") val provider: AiProviderDto = AiProviderDto.GEMINI,
    @SerialName("model") val model: String = "",
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("sendRealNames") val sendRealNames: Boolean = false,
    @SerialName("consentHost") val consentHost: String = "",
)

/** Inhalt der Datei `settings.json`. */
@Serializable
internal data class SettingsDto(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    // Fehlt die Vorlage in der Datei, gilt das Regelwerk der Runde – wie bei einer frisch eingerichteten App.
    @SerialName("defaultRules") val defaultRules: RuleSetDto = RuleSet.standard().toDto(),
    @SerialName("keepScreenOn") val keepScreenOn: Boolean = true,
    @SerialName("themeMode") val themeMode: ThemeModeDto = ThemeModeDto.DARK,
    @SerialName("haptics") val haptics: Boolean = true,
    @SerialName("ai") val ai: AiSettingsDto = AiSettingsDto(),
)
