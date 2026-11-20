/*
 * Verbindet das Dateiformat (`data/format`) mit dem Speicher und dem Rechenkern der Web-App. Beim Einlesen einer
 * Sicherung prüft das Format jeden Abend mit dem Rechenkern – genau wie `EveningCheck` in der Android-App.
 */
import { EveningCalculator } from "../core/evening/EveningCalculator";
import { ScoreCalculator } from "../core/rules/ScoreCalculator";
import {
  decodeEvening,
  decodePlayers,
  decodeRules,
  decodeSettings,
  encodeEvening,
  encodePlayers,
  encodeRules,
  encodeSettings,
  readBackup,
  writeBackup,
  type EveningChecker,
} from "../data/format";
import type { StorageCodec } from "../data/repository";
import type { RoundStoreCodec } from "../round/indexedDbRoundStore";
import type { BackupPort } from "./backup";

/** Speichern im Dateiformat der Android-App. */
export const storageCodec: StorageCodec = {
  encodeEvening,
  decodeEvening,
  encodePlayers,
  decodePlayers,
  encodeSettings,
  decodeSettings,
};

/** Was sich das Gerät zur gemeinsamen Runde merkt, im selben Dateiformat. */
export const roundStoreCodec: RoundStoreCodec = {
  encodeEvening,
  decodeEvening,
  encodeRules,
  decodeRulesOrNull: (text) => {
    try {
      return decodeRules(text);
    } catch {
      return null;
    }
  },
};

/** Was die Prüfung eines Abends beim Einlesen vom Rechenkern braucht (Kotlin: `EveningCheck`). */
export const coreChecker: EveningChecker = {
  computeResults: (evening) => EveningCalculator.compute(evening).results,
  isValueOutOfRange: (evening, entry, bockExponent) =>
    ScoreCalculator.validate(EveningCalculator.toFacts(entry, evening.seating, bockExponent), evening.rules).includes(
      "VALUE_OUT_OF_RANGE",
    ),
};

/** Sicherungen schreiben und lesen wie die Android-App. */
export const backupPort: BackupPort = {
  write: (players, evenings, defaultRules, appVersion, exportedAt) => writeBackup(players, evenings, defaultRules, appVersion, exportedAt),
  read: (text, knownPlayers, knownEvenings) => readBackup(text, knownPlayers, knownEvenings, coreChecker),
};
