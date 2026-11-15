/*
 * Übersetzt zwischen den Daten der Web-App und JSON-Text, genau wie die Android-App ihre Dateien schreibt und liest
 * (Kotlin: StorageCodec und RuleSetJson). Alle `decode…`-Funktionen werfen genau eine Art von Fehler,
 * {@link UnreadableDataError}; ihr Sonderfall {@link NewerSchemaError} heißt: Die Datei stammt aus einer neueren App.
 */
import type { AppSettings, Evening, Player, RuleSet } from "../../core/model";
import {
  EVENING_TYPE,
  type EveningDto,
  PLAYER_LIST_TYPE,
  type PlayerDto,
  PLAYERS_FILE_TYPE,
  type PlayersFileDto,
  RULE_SET_TYPE,
  type RuleSetDto,
  type SettingsDto,
  settingsType,
  standardRules,
} from "./dtos";
import { JsonFormatError, messageOf, NewerSchemaError, SCHEMA_VERSION, UnreadableDataError } from "./errors";
import { type JsonElement, parseToJsonElement } from "./jsonTree";
import {
  eveningFromDto,
  eveningOut,
  playerFromDto,
  playerOut,
  rulesFromDto,
  rulesOut,
  rulesToDto,
  settingsFromDto,
  settingsOut,
} from "./mappers";
import { decodeStreaming, decodeTree, OutObject, writeJson } from "./schemaCodec";

/** Unsichtbare Markierung am Dateianfang (U+FEFF); beim Lesen wird sie einmal überlesen. */
export const BYTE_ORDER_MARK = "﻿";

export function withoutByteOrderMark(text: string): string {
  return text.startsWith(BYTE_ORDER_MARK) ? text.substring(1) : text;
}

/** Macht aus jedem Fehler beim Lesen und Übersetzen einen {@link UnreadableDataError} (Kotlin: `reading`). */
function reading<T>(block: () => T): T {
  try {
    return block();
  } catch (error) {
    if (error instanceof UnreadableDataError) throw error;
    throw new UnreadableDataError(`Die Daten lassen sich nicht lesen: ${messageOf(error)}`, { cause: error });
  }
}

function checkVersion(version: number): void {
  if (version > SCHEMA_VERSION) throw new NewerSchemaError(version);
  if (version < 1) throw new UnreadableDataError(`Ungültige Schema-Version ${version}.`);
}

// ---------- Spieler ----------

/** Inhalt der Datei mit den Spielern (Kotlin: `players.json`). */
export function encodePlayers(players: readonly Player[]): string {
  return writeJson(new OutObject([["schemaVersion", SCHEMA_VERSION], ["players", players.map(playerOut)]]), false);
}

/** @throws UnreadableDataError oder NewerSchemaError */
export function decodePlayers(text: string): Player[] {
  return reading(() => {
    const file = decodeStreaming(withoutByteOrderMark(text), PLAYERS_FILE_TYPE) as PlayersFileDto;
    checkVersion(file.schemaVersion);
    return file.players.map(playerFromDto);
  });
}

/** Liest eine Spielerliste aus einem JSON-Baum, wie sie in der Sicherung steht (ohne Hülle mit Schema-Version). */
export function decodePlayerList(element: JsonElement): Player[] {
  return reading(() => (decodeTree(PLAYER_LIST_TYPE, element) as PlayerDto[]).map(playerFromDto));
}

// ---------- Abend ----------

/** Inhalt der Datei eines Abends (Kotlin: `evenings/<Kennung>.json`). */
export function encodeEvening(evening: Evening): string {
  return writeJson(eveningOut(evening), false);
}

function checked(dto: EveningDto): Evening {
  checkVersion(dto.schemaVersion);
  return eveningFromDto(dto);
}

/** @throws UnreadableDataError oder NewerSchemaError */
export function decodeEvening(text: string): Evening {
  return reading(() => checked(decodeStreaming(withoutByteOrderMark(text), EVENING_TYPE) as EveningDto));
}

/** Liest einen Abend aus einem bereits zerlegten JSON-Baum (ein Element der Sicherung). */
export function decodeEveningElement(element: JsonElement): Evening {
  return reading(() => checked(decodeTree(EVENING_TYPE, element) as EveningDto));
}

// ---------- Regelwerk ----------

/** Ein einzelnes Regelwerk als JSON-Text, so wie es auch im Abend und in der Sicherung steht (Kotlin: RuleSetJson.encode). */
export function encodeRules(rules: RuleSet): string {
  return writeJson(rulesOut(rules), false);
}

/**
 * Liest ein Regelwerk. Unbekannte Felder werden überlesen; fehlt ein Wert, gilt der Wert der Vorlage
 * „Sauspiel 10-20-50 mit Bockrunde“.
 * @throws UnreadableDataError
 */
export function decodeRules(text: string): RuleSet {
  return reading(() => rulesFromDto(decodeStreaming(withoutByteOrderMark(text), RULE_SET_TYPE) as RuleSetDto));
}

/** Wie {@link decodeRules}, aber `null`, wenn sich der Text nicht lesen lässt (Kotlin: RuleSetJson.decode). */
export function decodeRulesOrNull(text: string): RuleSet | null {
  try {
    return decodeRules(text);
  } catch (error) {
    if (error instanceof UnreadableDataError) return null;
    throw error;
  }
}

/** Liest ein Regelwerk aus einem bereits zerlegten JSON-Baum (die Vorlage in der Sicherung). */
export function decodeRulesElement(element: JsonElement): RuleSet {
  return reading(() => rulesFromDto(decodeTree(RULE_SET_TYPE, element) as RuleSetDto));
}

// ---------- Einstellungen ----------

const SETTINGS_TYPE = settingsType(() => rulesToDto(standardRules()));

/**
 * Die Einstellungen der Web-App als JSON-Text. Das Format ist das der Android-App ohne Vibration und ohne
 * KI-Zusammenfassung; die Android-App könnte es lesen, muss es aber nicht.
 */
export function encodeSettings(settings: AppSettings): string {
  return writeJson(settingsOut(settings), false);
}

/**
 * Liest Einstellungen, auch eine `settings.json` der Android-App (deren Felder `haptics` und `ai` werden geprüft, aber
 * nicht übernommen). Fehlt ein Wert, gilt der Standard: Regelwerk der Runde, Bildschirm an, dunkles Erscheinungsbild.
 * @throws UnreadableDataError oder NewerSchemaError
 */
export function decodeSettings(text: string): AppSettings {
  return reading(() => {
    const file = decodeStreaming(withoutByteOrderMark(text), SETTINGS_TYPE) as SettingsDto;
    checkVersion(file.schemaVersion);
    return settingsFromDto(file);
  });
}

/** Die Einstellungen einer frisch eingerichteten App. */
export function defaultSettings(): AppSettings {
  return { defaultRules: rulesFromDto(rulesToDto(standardRules())), keepScreenOn: true, themeMode: "DARK" };
}

// ---------- Sonstiges ----------

/** Ob der Text ein vollständiges JSON-Objekt ist, ganz gleich welchen Inhalts (Kotlin: `isJsonObject`). */
export function isJsonObject(text: string): boolean {
  try {
    return parseToJsonElement(withoutByteOrderMark(text)).kind === "object";
  } catch (error) {
    if (error instanceof JsonFormatError) return false;
    throw error;
  }
}
