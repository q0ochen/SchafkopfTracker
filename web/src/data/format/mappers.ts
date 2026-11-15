/*
 * Übersetzung zwischen den Daten der Web-App (core/model.ts) und dem Dateiformat (dtos.ts), in beide Richtungen
 * (Kotlin: Mappers.kt). Beim Schreiben stehen Spielarten immer in derselben Reihenfolge in der Datei (der der
 * Aufzählung), damit gleiche Regeln gleiche Dateien ergeben; Mengen (Spielarten, wer gelegt hat) enthalten jeden Wert
 * höchstens einmal. Zeitpunkte werden kanonisch geschrieben, wie `Instant.toString()`.
 */
import type {
  AppSettings,
  BockRules,
  Evening,
  EveningEntry,
  GameType,
  Player,
  RuleSet,
} from "../../core/model";
import {
  ANNOUNCEMENT,
  BOCK_OVERRIDE,
  BOCK_REASON,
  BOCK_STACKING,
  type BockRulesDto,
  type EntryDto,
  type EnumMapping,
  type EveningDto,
  GAME_TYPE,
  KONTRA_LEVEL,
  PAYOUT_RULE,
  type PlayerDto,
  RESULT_LEVEL,
  type RuleSetDto,
  type SettingsDto,
  SUIT,
  THEME_MODE,
} from "./dtos";
import { InvalidModelError, JsonFormatError, SCHEMA_VERSION } from "./errors";
import { canonicalInstant, parseInstant } from "./instantText";
import { OutObject, type OutValue } from "./schemaCodec";

// ---------- Aufzählungen ----------

function fromSerialName<T extends string>(mapping: EnumMapping<T>, serialName: string): T {
  const index = mapping.serialNames.indexOf(serialName);
  if (index === -1) throw new JsonFormatError(`Unbekannter Wert '${serialName}'`);
  return mapping.values[index];
}

function toSerialName<T extends string>(mapping: EnumMapping<T>, value: T, what: string): string {
  const index = mapping.values.indexOf(value);
  if (index === -1) throw new InvalidModelError(`${what}: unbekannter Wert '${String(value)}'`);
  return mapping.serialNames[index];
}

// ---------- Prüfung beim Schreiben ----------

/** Eine ganze Zahl im Bereich von Kotlins `Int`; alles andere kann ein Kotlin-Model nicht enthalten. */
function int(value: number, what: string): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < -2_147_483_648 || value > 2_147_483_647) {
    throw new InvalidModelError(`${what}: ${String(value)} ist keine ganze Zahl im Bereich von −2 147 483 648 bis 2 147 483 647`);
  }
  return value === 0 ? 0 : value;
}

function text(value: string, what: string): string {
  if (typeof value !== "string") throw new InvalidModelError(`${what}: kein Text`);
  return value;
}

function bool(value: boolean, what: string): boolean {
  if (typeof value !== "boolean") throw new InvalidModelError(`${what}: kein Wahrheitswert`);
  return value;
}

/** Ein Feld, das nur geschrieben wird, wenn es einen Wert hat (`explicitNulls = false`). */
function optionalField(key: string, value: OutValue | null): Array<readonly [string, OutValue]> {
  return value === null ? [] : [[key, value]];
}

/** Jeder Wert höchstens einmal, in der Reihenfolge des ersten Auftretens (wie Kotlins `LinkedHashSet`). */
function distinct<T>(values: readonly T[]): T[] {
  return [...new Set(values)];
}

// ---------- Spieler ----------

export function playerFromDto(dto: PlayerDto): Player {
  return { id: dto.id, name: dto.name, colorIndex: dto.colorIndex, archived: dto.archived };
}

export function playerOut(player: Player): OutObject {
  return new OutObject([
    ["id", text(player.id, "Kennung des Spielers")],
    ["name", text(player.name, "Name des Spielers")],
    ["colorIndex", int(player.colorIndex, "Farbe des Spielers")],
    ["archived", bool(player.archived, "archiviert")],
  ]);
}

// ---------- Regelwerk ----------

type GameTypeTable = Readonly<Partial<Record<GameType, number>>>;

function checkTableKeys(table: GameTypeTable, what: string): void {
  for (const key of Object.keys(table)) {
    if (!GAME_TYPE.values.includes(key as GameType)) throw new InvalidModelError(`${what}: unbekannte Spielart '${key}'`);
  }
}

/** Die Spielarten einer Tabelle in der Reihenfolge der Aufzählung (Kotlin: `toSortedMap()`). */
function sortedTable(table: GameTypeTable, what: string): Array<readonly [string, number]> {
  checkTableKeys(table, what);
  const result: Array<readonly [string, number]> = [];
  for (const type of GAME_TYPE.values) {
    const value = table[type];
    if (value !== undefined) result.push([toSerialName(GAME_TYPE, type, what), int(value, `${what} ${type}`)]);
  }
  return result;
}

function sortedTypes(types: readonly GameType[]): string[] {
  for (const type of types) toSerialName(GAME_TYPE, type, "Spielart");
  return GAME_TYPE.values.filter((type) => types.includes(type)).map((type) => toSerialName(GAME_TYPE, type, "Spielart"));
}

function tableFromDto(table: ReadonlyMap<string, number>): Partial<Record<GameType, number>> {
  const result: Partial<Record<GameType, number>> = {};
  for (const [key, value] of table) result[fromSerialName(GAME_TYPE, key)] = value;
  return result;
}

function bockFromDto(dto: BockRulesDto): BockRules {
  return {
    enabled: dto.enabled,
    gamesPerTrigger: dto.gamesPerTrigger,
    factor: dto.factor,
    stacking: fromSerialName(BOCK_STACKING, dto.stacking),
    thrownInConsumesBockGame: dto.thrownInConsumesBockGame,
  };
}

export function rulesFromDto(dto: RuleSetDto): RuleSet {
  return {
    name: dto.name,
    unit: dto.unit,
    prices: tableFromDto(dto.prices),
    enabledTypes: distinct(dto.enabledTypes.map((name) => fromSerialName(GAME_TYPE, name))),
    laufendeMin: tableFromDto(dto.laufendeMin),
    laufendeMax: tableFromDto(dto.laufendeMax),
    klopfenEnabled: dto.klopfenEnabled,
    kontraEnabled: dto.kontraEnabled,
    retourEnabled: dto.retourEnabled,
    toutFactor: dto.toutFactor,
    sieFactor: dto.sieFactor,
    sieLaufende: dto.sieLaufende,
    bock: bockFromDto(dto.bock),
    dealerAdvancesAfterThrowIn: dto.dealerAdvancesAfterThrowIn,
    displayAsMoney: dto.displayAsMoney,
    payoutRule: fromSerialName(PAYOUT_RULE, dto.payoutRule),
  };
}

/** Das Regelwerk als Dateiklasse, so wie Kotlins `RuleSet.toDto()` es bildet (Tabellen sortiert). */
export function rulesToDto(rules: RuleSet): RuleSetDto {
  return {
    name: text(rules.name, "Name des Regelwerks"),
    unit: int(rules.unit, "Grundeinheit"),
    prices: new Map(sortedTable(rules.prices, "Grundwerte")),
    enabledTypes: sortedTypes(rules.enabledTypes),
    laufendeMin: new Map(sortedTable(rules.laufendeMin, "Laufende ab")),
    laufendeMax: new Map(sortedTable(rules.laufendeMax, "Laufende bis")),
    klopfenEnabled: bool(rules.klopfenEnabled, "Klopfen"),
    kontraEnabled: bool(rules.kontraEnabled, "Kontra"),
    retourEnabled: bool(rules.retourEnabled, "Retour"),
    toutFactor: int(rules.toutFactor, "Faktor Tout"),
    sieFactor: int(rules.sieFactor, "Faktor Sie"),
    sieLaufende: int(rules.sieLaufende, "Laufende bei Sie"),
    bock: {
      enabled: bool(rules.bock.enabled, "Bockrunde"),
      gamesPerTrigger: int(rules.bock.gamesPerTrigger, "Spiele je Bockrunde"),
      factor: int(rules.bock.factor, "Faktor Bock"),
      stacking: toSerialName(BOCK_STACKING, rules.bock.stacking, "Bock-Überlagerung"),
      thrownInConsumesBockGame: bool(rules.bock.thrownInConsumesBockGame, "Zusammengeworfen verbraucht Bockspiel"),
    },
    dealerAdvancesAfterThrowIn: bool(rules.dealerAdvancesAfterThrowIn, "Geber wechselt nach Zusammenwerfen"),
    displayAsMoney: bool(rules.displayAsMoney, "Anzeige in Euro"),
    payoutRule: toSerialName(PAYOUT_RULE, rules.payoutRule, "Auszahlungsregel"),
  };
}

export function rulesOut(rules: RuleSet): OutObject {
  const dto = rulesToDto(rules);
  return new OutObject([
    ["name", dto.name],
    ["unit", dto.unit],
    ["prices", new OutObject([...dto.prices])],
    ["enabledTypes", dto.enabledTypes],
    ["laufendeMin", new OutObject([...dto.laufendeMin])],
    ["laufendeMax", new OutObject([...dto.laufendeMax])],
    ["klopfenEnabled", dto.klopfenEnabled],
    ["kontraEnabled", dto.kontraEnabled],
    ["retourEnabled", dto.retourEnabled],
    ["toutFactor", dto.toutFactor],
    ["sieFactor", dto.sieFactor],
    ["sieLaufende", dto.sieLaufende],
    [
      "bock",
      new OutObject([
        ["enabled", dto.bock.enabled],
        ["gamesPerTrigger", dto.bock.gamesPerTrigger],
        ["factor", dto.bock.factor],
        ["stacking", dto.bock.stacking],
        ["thrownInConsumesBockGame", dto.bock.thrownInConsumesBockGame],
      ]),
    ],
    ["dealerAdvancesAfterThrowIn", dto.dealerAdvancesAfterThrowIn],
    ["displayAsMoney", dto.displayAsMoney],
    ["payoutRule", dto.payoutRule],
  ]);
}

// ---------- Abend und Einträge ----------

export function eveningFromDto(dto: EveningDto): Evening {
  return {
    id: dto.id,
    startedAt: parseInstant(dto.startedAt),
    finishedAt: dto.finishedAt === null ? null : parseInstant(dto.finishedAt),
    location: dto.location,
    seating: [...dto.seating],
    firstDealer: dto.firstDealer,
    rules: rulesFromDto(dto.rules),
    entries: dto.entries.map(entryFromDto),
  };
}

function entryFromDto(dto: EntryDto): EveningEntry {
  switch (dto.type) {
    case "game":
      return {
        kind: "game",
        id: dto.id,
        recordedAt: parseInstant(dto.recordedAt),
        type: fromSerialName(GAME_TYPE, dto.gameType),
        suit: dto.suit === null ? null : fromSerialName(SUIT, dto.suit),
        declarer: dto.declarer,
        partner: dto.partner,
        declarerWon: dto.declarerWon,
        level: fromSerialName(RESULT_LEVEL, dto.level),
        laufende: dto.laufende,
        announcement: fromSerialName(ANNOUNCEMENT, dto.announcement),
        klopfer: distinct(dto.klopfer),
        kontra: fromSerialName(KONTRA_LEVEL, dto.kontra),
        kontraBy: dto.kontraBy,
        retourBy: dto.retourBy,
        bockOverride: fromSerialName(BOCK_OVERRIDE, dto.bockOverride),
        declarerEyes: dto.declarerEyes,
        note: dto.note,
      };
    case "thrown_in":
      return { kind: "thrown_in", id: dto.id, recordedAt: parseInstant(dto.recordedAt) };
    case "bock":
      return {
        kind: "bock",
        id: dto.id,
        recordedAt: parseInstant(dto.recordedAt),
        reason: fromSerialName(BOCK_REASON, dto.reason),
        player: dto.player,
        handThrownIn: dto.handThrownIn,
      };
    case "manual":
      return { kind: "manual", id: dto.id, recordedAt: parseInstant(dto.recordedAt), amounts: new Map(dto.amounts), note: dto.note };
  }
}

function instantOut(value: string, what: string): string {
  return canonicalInstant(text(value, what));
}

function nullableText(value: string | null, what: string): string | null {
  return value === null ? null : text(value, what);
}

function entryOut(entry: EveningEntry, index: number): OutObject {
  const what = `Eintrag Nr. ${index + 1}`;
  const head: Array<readonly [string, OutValue]> = [
    ["type", entry.kind],
    ["id", text(entry.id, `${what}: Kennung`)],
    ["recordedAt", instantOut(entry.recordedAt, `${what}: Zeitpunkt`)],
  ];
  switch (entry.kind) {
    case "game":
      return new OutObject([
        ...head,
        ["gameType", toSerialName(GAME_TYPE, entry.type, `${what}: Spielart`)],
        ...optionalField("suit", entry.suit === null ? null : toSerialName(SUIT, entry.suit, `${what}: Farbe`)),
        ["declarer", text(entry.declarer, `${what}: Ansager`)],
        ...optionalField("partner", nullableText(entry.partner, `${what}: Mitspieler`)),
        ["declarerWon", bool(entry.declarerWon, `${what}: gewonnen`)],
        ["level", toSerialName(RESULT_LEVEL, entry.level, `${what}: Ausgang`)],
        ["laufende", int(entry.laufende, `${what}: Laufende`)],
        ["announcement", toSerialName(ANNOUNCEMENT, entry.announcement, `${what}: Ansage`)],
        ["klopfer", distinct(entry.klopfer).map((player) => text(player, `${what}: gelegt`))],
        ["kontra", toSerialName(KONTRA_LEVEL, entry.kontra, `${what}: Kontra`)],
        ...optionalField("kontraBy", nullableText(entry.kontraBy, `${what}: Kontra von`)),
        ...optionalField("retourBy", nullableText(entry.retourBy, `${what}: Retour von`)),
        ["bockOverride", toSerialName(BOCK_OVERRIDE, entry.bockOverride, `${what}: Bock-Schalter`)],
        ...optionalField("declarerEyes", entry.declarerEyes === null ? null : int(entry.declarerEyes, `${what}: Augen`)),
        ["note", text(entry.note, `${what}: Notiz`)],
      ]);
    case "thrown_in":
      return new OutObject(head);
    case "bock":
      return new OutObject([
        ...head,
        ["reason", toSerialName(BOCK_REASON, entry.reason, `${what}: Grund`)],
        ...optionalField("player", nullableText(entry.player, `${what}: Spieler`)),
        ["handThrownIn", bool(entry.handThrownIn, `${what}: zusammengeworfen`)],
      ]);
    case "manual":
      return new OutObject([
        ...head,
        ["amounts", new OutObject([...entry.amounts].map(([player, amount]) => [text(player, `${what}: Spieler`), int(amount, `${what}: Betrag`)] as const))],
        ["note", text(entry.note, `${what}: Notiz`)],
      ]);
    default:
      throw new InvalidModelError(`${what}: unbekannte Art '${String((entry as { kind: unknown }).kind)}'`);
  }
}

export function eveningOut(evening: Evening): OutObject {
  return new OutObject([
    ["schemaVersion", SCHEMA_VERSION],
    ["id", text(evening.id, "Kennung des Abends")],
    ["startedAt", instantOut(evening.startedAt, "Beginn des Abends")],
    ...optionalField("finishedAt", evening.finishedAt === null ? null : instantOut(evening.finishedAt, "Ende des Abends")),
    ["location", text(evening.location, "Ort")],
    ["seating", evening.seating.map((player) => text(player, "Sitzordnung"))],
    ["firstDealer", text(evening.firstDealer, "erster Geber")],
    ["rules", rulesOut(evening.rules)],
    ["entries", evening.entries.map(entryOut)],
  ]);
}

// ---------- Einstellungen ----------

export function settingsFromDto(dto: SettingsDto): AppSettings {
  return {
    defaultRules: rulesFromDto(dto.defaultRules),
    keepScreenOn: dto.keepScreenOn,
    themeMode: fromSerialName(THEME_MODE, dto.themeMode),
  };
}

export function settingsOut(settings: AppSettings): OutObject {
  return new OutObject([
    ["schemaVersion", SCHEMA_VERSION],
    ["defaultRules", rulesOut(settings.defaultRules)],
    ["keepScreenOn", bool(settings.keepScreenOn, "Bildschirm anlassen")],
    ["themeMode", toSerialName(THEME_MODE, settings.themeMode, "Erscheinungsbild")],
  ]);
}
