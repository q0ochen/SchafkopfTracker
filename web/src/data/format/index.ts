/*
 * Das Datei- und Sicherungsformat der Android-App für die Web-App: dieselben JSON-Texte, dieselben Prüfungen und
 * dieselben deutschen Meldungen, nachgewiesen mit Golden-Daten aus dem echten Kotlin-Code
 * (web/src/data/golden/kotlin-format-golden.json). Reine Funktionen ohne Zugriff auf einen Speicher.
 */
export { InvalidInstantError, InvalidModelError, NewerSchemaError, SCHEMA_VERSION, UnreadableDataError } from "./errors";
export { canonicalInstant, parseInstant, tryParseInstant } from "./instantText";
export {
  decodeEvening,
  decodePlayers,
  decodeRules,
  decodeRulesOrNull,
  decodeSettings,
  defaultSettings,
  encodeEvening,
  encodePlayers,
  encodeRules,
  encodeSettings,
  isJsonObject,
} from "./storageCodec";
export {
  BACKUP_FORMAT_NAME,
  BACKUP_FORMAT_VERSION,
  type BackupError,
  type BackupErrorKind,
  type CheckedEntryResult,
  type EveningChecker,
  type EveningFinding,
  type EveningProblem,
  firstEveningProblem,
  hasNewData,
  type ImportPlan,
  type ImportPreview,
  type ImportResult,
  importResultOf,
  type InvalidEvening,
  isReadable,
  planImport,
  readBackup,
  runImport,
  STORAGE_FAILED_ERROR,
  writeBackup,
} from "./backupFormat";
