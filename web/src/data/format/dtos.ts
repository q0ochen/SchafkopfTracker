/*
 * Das Dateiformat der App, Schema Version 1 (Kotlin: Dtos.kt). Jeder Schlüssel und jeder Text einer Aufzählung ist
 * hier fest vergeben, genau wie in der Android-App. Felder mit Standardwert dürfen in einer Datei fehlen (ältere
 * Dateien), unbekannte Felder werden überlesen (neuere Dateien). Ein unbekannter Wert einer Aufzählung macht die Datei
 * unlesbar.
 */
import type { Announcement, BockOverride, BockReason, BockStacking, GameType, KontraLevel, PayoutRule, ResultLevel, RuleSet, Suit, ThemeMode } from "../../core/model";
import type { ClassSchema, FieldSchema, FieldType, SealedType } from "./schemaCodec";
import { SCHEMA_VERSION } from "./errors";

// ---------- Aufzählungen: Name im Model ⇄ Text in der Datei ----------

/** Eine Aufzählung mit den Namen des Models (Kotlin-Namen) und den Texten in der Datei, in derselben Reihenfolge. */
export interface EnumMapping<T extends string> {
  readonly values: readonly T[];
  readonly serialNames: readonly string[];
}

function enumMapping<T extends string>(pairs: ReadonlyArray<readonly [T, string]>): EnumMapping<T> {
  return { values: pairs.map(([value]) => value), serialNames: pairs.map(([, serialName]) => serialName) };
}

export const GAME_TYPE = enumMapping<GameType>([
  ["RUFSPIEL", "rufspiel"],
  ["UEBER_KREUZ", "ueber_kreuz"],
  ["FARBSOLO", "farbsolo"],
  ["WENZ", "wenz"],
  ["GEIER", "geier"],
]);
export const PAYOUT_RULE = enumMapping<PayoutRule>([
  ["SCHAFKOPF", "schafkopf"],
  ["POT_SPLIT", "pot_split"],
]);
export const SUIT = enumMapping<Suit>([
  ["EICHEL", "eichel"],
  ["GRAS", "gras"],
  ["HERZ", "herz"],
  ["SCHELLEN", "schellen"],
]);
export const RESULT_LEVEL = enumMapping<ResultLevel>([
  ["NORMAL", "normal"],
  ["SCHNEIDER", "schneider"],
  ["SCHWARZ", "schwarz"],
]);
export const ANNOUNCEMENT = enumMapping<Announcement>([
  ["NONE", "none"],
  ["TOUT", "tout"],
  ["SIE", "sie"],
]);
export const KONTRA_LEVEL = enumMapping<KontraLevel>([
  ["NONE", "none"],
  ["KONTRA", "kontra"],
  ["RETOUR", "retour"],
]);
export const BOCK_STACKING = enumMapping<BockStacking>([
  ["APPEND", "append"],
  ["STACK", "stack"],
  ["IGNORE", "ignore"],
]);
export const BOCK_OVERRIDE = enumMapping<BockOverride>([
  ["AUTO", "auto"],
  ["FORCE_ON", "force_on"],
  ["FORCE_OFF", "force_off"],
]);
export const BOCK_REASON = enumMapping<BockReason>([
  ["SECHS_NIXER", "sechs_nixer"],
  ["MANUAL", "manual"],
]);
export const THEME_MODE = enumMapping<ThemeMode>([
  ["SYSTEM", "system"],
  ["LIGHT", "light"],
  ["DARK", "dark"],
]);
const AI_PROVIDER = enumMapping<"GEMINI" | "OPENAI_COMPATIBLE">([
  ["GEMINI", "gemini"],
  ["OPENAI_COMPATIBLE", "openai_compatible"],
]);

// ---------- Bausteine des Bauplans ----------

const STRING: FieldType = { kind: "string" };
const INT: FieldType = { kind: "int" };
const BOOLEAN: FieldType = { kind: "boolean" };

function enumType(mapping: EnumMapping<string>): FieldType {
  return { kind: "enum", names: mapping.serialNames };
}

function nullable(inner: FieldType): FieldType {
  return { kind: "nullable", inner };
}

function list(element: FieldType): FieldType {
  return { kind: "list", element };
}

function map(key: FieldType, value: FieldType): FieldType {
  return { kind: "map", key, value };
}

function classType(schema: ClassSchema): FieldType {
  return { kind: "class", schema };
}

function required(name: string, type: FieldType): FieldSchema {
  return { name, type };
}

function optional(name: string, type: FieldType, defaultValue: () => unknown): FieldSchema {
  return { name, type, defaultValue };
}

// ---------- Die Dateiklassen ----------

export interface PlayerDto {
  readonly id: string;
  readonly name: string;
  readonly colorIndex: number;
  readonly archived: boolean;
}

export const PLAYER_SCHEMA: ClassSchema = {
  name: "PlayerDto",
  fields: [required("id", STRING), required("name", STRING), optional("colorIndex", INT, () => 0), optional("archived", BOOLEAN, () => false)],
};

export const PLAYER_LIST_TYPE: FieldType = list(classType(PLAYER_SCHEMA));

export interface PlayersFileDto {
  readonly schemaVersion: number;
  readonly players: readonly PlayerDto[];
}

export const PLAYERS_FILE_TYPE: FieldType = classType({
  name: "PlayersFileDto",
  fields: [optional("schemaVersion", INT, () => SCHEMA_VERSION), optional("players", PLAYER_LIST_TYPE, () => [])],
});

export interface BockRulesDto {
  readonly enabled: boolean;
  readonly gamesPerTrigger: number;
  readonly factor: number;
  readonly stacking: string;
  readonly thrownInConsumesBockGame: boolean;
}

const BOCK_RULES_SCHEMA: ClassSchema = {
  name: "BockRulesDto",
  fields: [
    optional("enabled", BOOLEAN, () => true),
    optional("gamesPerTrigger", INT, () => 4),
    optional("factor", INT, () => 2),
    optional("stacking", enumType(BOCK_STACKING), () => "append"),
    optional("thrownInConsumesBockGame", BOOLEAN, () => false),
  ],
};

const defaultBockRules = (): BockRulesDto => ({ enabled: true, gamesPerTrigger: 4, factor: 2, stacking: "append", thrownInConsumesBockGame: false });

export interface RuleSetDto {
  readonly name: string;
  readonly unit: number;
  readonly prices: ReadonlyMap<string, number>;
  readonly enabledTypes: readonly string[];
  readonly laufendeMin: ReadonlyMap<string, number>;
  readonly laufendeMax: ReadonlyMap<string, number>;
  readonly klopfenEnabled: boolean;
  readonly kontraEnabled: boolean;
  readonly retourEnabled: boolean;
  readonly toutFactor: number;
  readonly sieFactor: number;
  readonly sieLaufende: number;
  readonly bock: BockRulesDto;
  readonly dealerAdvancesAfterThrowIn: boolean;
  readonly displayAsMoney: boolean;
  readonly payoutRule: string;
}

const GAME_TYPE_TABLE = map(enumType(GAME_TYPE), INT);

/**
 * Das Regelwerk. Fehlt ein Wert in einer Datei, gilt der Wert der Vorlage „Sauspiel 10-20-50 mit Bockrunde“, so wie
 * sie bei Schema Version 1 war; diese Standardwerte stehen absichtlich fest hier.
 */
export const RULE_SET_SCHEMA: ClassSchema = {
  name: "RuleSetDto",
  fields: [
    optional("name", STRING, () => "Sauspiel 10-20-50 mit Bockrunde"),
    optional("unit", INT, () => 10),
    optional("prices", GAME_TYPE_TABLE, () => new Map([["rufspiel", 20], ["farbsolo", 50], ["wenz", 50], ["geier", 50]])),
    optional("enabledTypes", list(enumType(GAME_TYPE)), () => ["rufspiel", "farbsolo", "wenz", "geier"]),
    optional("laufendeMin", GAME_TYPE_TABLE, () => new Map([["rufspiel", 3], ["farbsolo", 3], ["wenz", 2], ["geier", 2]])),
    optional("laufendeMax", GAME_TYPE_TABLE, () => new Map([["rufspiel", 14], ["farbsolo", 14], ["wenz", 4], ["geier", 4]])),
    optional("klopfenEnabled", BOOLEAN, () => true),
    optional("kontraEnabled", BOOLEAN, () => true),
    optional("retourEnabled", BOOLEAN, () => true),
    optional("toutFactor", INT, () => 2),
    optional("sieFactor", INT, () => 2),
    optional("sieLaufende", INT, () => 8),
    optional("bock", classType(BOCK_RULES_SCHEMA), defaultBockRules),
    optional("dealerAdvancesAfterThrowIn", BOOLEAN, () => true),
    optional("displayAsMoney", BOOLEAN, () => true),
    // Fehlt in älteren Dateien: Dann gilt die Schafkopf-Regel, nach der bis dahin immer abgerechnet wurde.
    optional("payoutRule", enumType(PAYOUT_RULE), () => "schafkopf"),
  ],
};

export const RULE_SET_TYPE: FieldType = classType(RULE_SET_SCHEMA);

/** Ein Eintrag eines Abends; die Art steht im Feld `type`: `game`, `thrown_in`, `bock` oder `manual`. */
export type EntryDto = GameEntryDto | ThrownInEntryDto | BockTriggerEntryDto | ManualEntryDto;

export interface GameEntryDto {
  readonly type: "game";
  readonly id: string;
  readonly recordedAt: string;
  readonly gameType: string;
  readonly suit: string | null;
  readonly declarer: string;
  readonly partner: string | null;
  readonly declarerWon: boolean;
  readonly level: string;
  readonly laufende: number;
  readonly announcement: string;
  readonly klopfer: readonly string[];
  readonly kontra: string;
  readonly kontraBy: string | null;
  readonly retourBy: string | null;
  readonly bockOverride: string;
  readonly declarerEyes: number | null;
  readonly note: string;
}

export interface ThrownInEntryDto {
  readonly type: "thrown_in";
  readonly id: string;
  readonly recordedAt: string;
}

export interface BockTriggerEntryDto {
  readonly type: "bock";
  readonly id: string;
  readonly recordedAt: string;
  readonly reason: string;
  readonly player: string | null;
  readonly handThrownIn: boolean;
}

export interface ManualEntryDto {
  readonly type: "manual";
  readonly id: string;
  readonly recordedAt: string;
  readonly amounts: ReadonlyMap<string, number>;
  readonly note: string;
}

const GAME_ENTRY_SCHEMA: ClassSchema = {
  name: "GameEntryDto",
  fields: [
    required("id", STRING),
    required("recordedAt", STRING),
    required("gameType", enumType(GAME_TYPE)),
    optional("suit", nullable(enumType(SUIT)), () => null),
    required("declarer", STRING),
    optional("partner", nullable(STRING), () => null),
    required("declarerWon", BOOLEAN),
    optional("level", enumType(RESULT_LEVEL), () => "normal"),
    optional("laufende", INT, () => 0),
    optional("announcement", enumType(ANNOUNCEMENT), () => "none"),
    optional("klopfer", list(STRING), () => []),
    optional("kontra", enumType(KONTRA_LEVEL), () => "none"),
    optional("kontraBy", nullable(STRING), () => null),
    optional("retourBy", nullable(STRING), () => null),
    optional("bockOverride", enumType(BOCK_OVERRIDE), () => "auto"),
    optional("declarerEyes", nullable(INT), () => null),
    optional("note", STRING, () => ""),
  ],
};

const THROWN_IN_ENTRY_SCHEMA: ClassSchema = {
  name: "ThrownInEntryDto",
  fields: [required("id", STRING), required("recordedAt", STRING)],
};

const BOCK_TRIGGER_ENTRY_SCHEMA: ClassSchema = {
  name: "BockTriggerEntryDto",
  fields: [
    required("id", STRING),
    required("recordedAt", STRING),
    required("reason", enumType(BOCK_REASON)),
    optional("player", nullable(STRING), () => null),
    optional("handThrownIn", BOOLEAN, () => true),
  ],
};

const MANUAL_ENTRY_SCHEMA: ClassSchema = {
  name: "ManualEntryDto",
  fields: [required("id", STRING), required("recordedAt", STRING), required("amounts", map(STRING, INT)), optional("note", STRING, () => "")],
};

const ENTRY_TYPE: SealedType = {
  kind: "sealed",
  discriminator: "type",
  subclasses: new Map([
    ["game", GAME_ENTRY_SCHEMA],
    ["thrown_in", THROWN_IN_ENTRY_SCHEMA],
    ["bock", BOCK_TRIGGER_ENTRY_SCHEMA],
    ["manual", MANUAL_ENTRY_SCHEMA],
  ]),
};

export interface EveningDto {
  readonly schemaVersion: number;
  readonly id: string;
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly location: string;
  readonly seating: readonly string[];
  readonly firstDealer: string;
  readonly rules: RuleSetDto;
  readonly entries: readonly EntryDto[];
}

/** Ein Abend: Inhalt einer Datei unter `evenings/` und ein Element der Sicherung. */
export const EVENING_TYPE: FieldType = classType({
  name: "EveningDto",
  fields: [
    optional("schemaVersion", INT, () => SCHEMA_VERSION),
    required("id", STRING),
    required("startedAt", STRING),
    optional("finishedAt", nullable(STRING), () => null),
    optional("location", STRING, () => ""),
    required("seating", list(STRING)),
    required("firstDealer", STRING),
    optional("rules", RULE_SET_TYPE, () => sauspielRulesDto()),
    optional("entries", list(ENTRY_TYPE), () => []),
  ],
});

/** Die Standardwerte des Regelwerks als Dateiklasse (Kotlin: `RuleSetDto()`). */
function sauspielRulesDto(): RuleSetDto {
  const result: Record<string, unknown> = {};
  for (const field of RULE_SET_SCHEMA.fields) result[field.name] = field.defaultValue?.();
  return result as unknown as RuleSetDto;
}

export interface SettingsDto {
  readonly schemaVersion: number;
  readonly defaultRules: RuleSetDto;
  readonly keepScreenOn: boolean;
  readonly themeMode: string;
}

const AI_SETTINGS_SCHEMA: ClassSchema = {
  name: "AiSettingsDto",
  fields: [
    optional("enabled", BOOLEAN, () => false),
    optional("provider", enumType(AI_PROVIDER), () => "gemini"),
    optional("model", STRING, () => ""),
    optional("baseUrl", STRING, () => ""),
    optional("sendRealNames", BOOLEAN, () => false),
    optional("consentHost", STRING, () => ""),
  ],
};

/**
 * Inhalt der Datei `settings.json`. Die Felder `haptics` und `ai` gibt es nur in der Android-App; sie werden gelesen
 * und geprüft wie dort (damit eine Datei der Android-App genauso angenommen oder abgelehnt wird), aber nicht übernommen.
 *
 * @param standardRules Die Vorlage, die gilt, wenn die Datei keine nennt (Kotlin: `RuleSet.standard()`).
 */
export function settingsType(standardRules: () => RuleSetDto): FieldType {
  return classType({
    name: "SettingsDto",
    fields: [
      optional("schemaVersion", INT, () => SCHEMA_VERSION),
      optional("defaultRules", RULE_SET_TYPE, standardRules),
      optional("keepScreenOn", BOOLEAN, () => true),
      optional("themeMode", enumType(THEME_MODE), () => "dark"),
      optional("haptics", BOOLEAN, () => true),
      optional("ai", classType(AI_SETTINGS_SCHEMA), () => ({})),
    ],
  });
}

/**
 * Das Regelwerk der Runde (Kotlin: `RuleSet.standard()`): Vorlage für neue Abende, wenn die Einstellungen keine nennen.
 */
export function standardRules(): RuleSet {
  return {
    name: "Runde 10/50 mit Bockrunde",
    unit: 10,
    prices: { RUFSPIEL: 10, UEBER_KREUZ: 10, FARBSOLO: 50, WENZ: 50, GEIER: 50 },
    enabledTypes: ["RUFSPIEL", "UEBER_KREUZ", "FARBSOLO", "WENZ", "GEIER"],
    laufendeMin: { RUFSPIEL: 3, UEBER_KREUZ: 3, FARBSOLO: 3, WENZ: 2, GEIER: 2 },
    laufendeMax: { RUFSPIEL: 8, UEBER_KREUZ: 8, FARBSOLO: 8, WENZ: 4, GEIER: 4 },
    klopfenEnabled: true,
    kontraEnabled: true,
    retourEnabled: true,
    toutFactor: 2,
    sieFactor: 2,
    sieLaufende: 8,
    bock: { enabled: true, gamesPerTrigger: 4, factor: 2, stacking: "APPEND", thrownInConsumesBockGame: false },
    dealerAdvancesAfterThrowIn: true,
    displayAsMoney: true,
    payoutRule: "SCHAFKOPF",
  };
}
