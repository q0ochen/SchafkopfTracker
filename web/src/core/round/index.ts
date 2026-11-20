/*
 * Die gemeinsame Runde im Rechenkern der Web-App (docs/ARCHITEKTUR.md, Abschnitt 8): Zusammenführen, Hinweis auf
 * doppelt eingetragene Spiele, Einladungslinks, der Abgleich und das Verbinden mit einer Runde. Port des Pakets
 * `domain.round` der Android-App, mit denselben Namen; reines TypeScript ohne Browser-Funktionen.
 */
export { DuplicateCheck } from "./DuplicateCheck";
export { sameEntry, sameEvening, sameEveningOrNull, sameInstant, samePlayer, sameRules, sameRulesOrNull } from "./equality";
export { EveningMerge } from "./EveningMerge";
export { InviteLinks } from "./InviteLinks";
export { RoundConnection } from "./RoundConnection";
export {
  remotePlayer,
  ROUND_ERRORS,
  RoundError,
  RoundException,
  roundLink,
  sameContent,
  toPlayer,
} from "./RoundModels";
export type {
  EveningBase,
  InviteInfo,
  RemoteEvening,
  RemotePlayer,
  RoundHead,
  RoundId,
  RoundLink,
  RoundMember,
  RoundSummary,
  SavedEvening,
} from "./RoundModels";
export { EveningNotStorableError, ROUND_API_METHODS } from "./RoundPorts";
export type {
  EveningEditor,
  EveningRepository,
  IdGenerator,
  PlayerRepository,
  RoundApi,
  RoundStore,
  SettingsRepository,
} from "./RoundPorts";
export { RoundSync, SyncOutcome } from "./RoundSync";
export type { Synced, SyncGuard, SyncNotice } from "./RoundSync";
