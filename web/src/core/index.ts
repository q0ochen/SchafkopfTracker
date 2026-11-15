/*
 * Öffentliche Schnittstelle des Rechenkerns der Web-App: die Abrechnung, der Abend und die Statistik, verhaltensgleich
 * zum Kotlin-Kern der Android-App (Modul `domain`). Dass beide für dieselben Eingaben exakt dasselbe liefern, prüft
 * `golden/kotlinGolden.test.ts` mit Daten, die der Kotlin-Kern selbst erzeugt hat.
 *
 * Konventionen:
 * - Kotlin-`object`s sind gleichnamige Objekte mit Funktionen (`ScoreCalculator.breakdown(facts, rules)`).
 * - Kotlin-Datenklassen sind Schnittstellen mit `readonly`-Feldern; abgeleitete Eigenschaften (`winRate`, `badge` …)
 *   sind fertig berechnete Felder. Kotlin-`null` ist `null`.
 * - Methoden und Eigenschaften der Daten aus `model.ts` sind Funktionen (`price(rules, type)`, `isSolo(type)`,
 *   `winners(entry, seating)`), ebenso die Abfragen auf Ergebnissen (`standingOf(finance, player)`).
 * - Maps sind `ReadonlyMap` in derselben Reihenfolge wie in Kotlin.
 * - Beträge sind ganze Cent im Bereich eines Kotlin-`Int`; wo Kotlin dabei überläuft, läuft der Kern genauso über.
 */
export * from "./model";
export { compareInstants, instantFromEpochMillis, instantParts, instantToEpochMillis } from "./instant";
export type { InstantParts } from "./instant";

export {
  allowsSie,
  allowsTout,
  hasDeclarer,
  isSolo,
  kontraFactor,
  laufendeMax,
  laufendeMin,
  levelSteps,
  price,
  RuleSets,
} from "./rules/RuleSet";
export { PayoutCalculator, PotSplitPayout, SchafkopfPayout } from "./rules/PayoutCalculator";
export { RULE_VIOLATIONS, ScoreCalculator, valueBreakdown } from "./rules/ScoreCalculator";
export type { GameFacts, RuleViolation, ValueBreakdown } from "./rules/ScoreCalculator";

export { append, declarerSide, insertAt, isRunning, losers, remove, replace, winners } from "./evening/Evening";
export type { GameSides } from "./evening/Evening";
export { ENTRY_ISSUES, EveningCalculator, Ranking } from "./evening/EveningCalculator";
export type { EntryIssue, EntryResult, EveningState } from "./evening/EveningCalculator";
export { SettleUp } from "./evening/SettleUp";
export type { Transfer } from "./evening/SettleUp";

export { playerStats, roleRecord } from "./stats/PlayerStats";
export type { EveningBalance, GameAmount, PlayerStats, PlayerStatsFields, RoleRecord } from "./stats/PlayerStats";
export { EveningStatistics, statsFor } from "./stats/EveningStatistics";
export type { EveningStats, ExpensiveGame } from "./stats/EveningStatistics";
export { HistoryStatistics, isPair, pairRecord, partnerRecord } from "./stats/HistoryStatistics";
export type { HistoryStats, PairRecord, PartnerRecord } from "./stats/HistoryStatistics";
export { Progression } from "./stats/Progression";
export {
  bockRecord,
  GELEGT_BADGES,
  GelegtBadge,
  gameModesOf,
  gelegtOf,
  gelegtRecord,
  modeRecord,
  pairOf,
  partnerPair,
  recordOf,
  soloRecord,
  standingOf,
  StatisticEngine,
} from "./stats/StatisticEngine";
export type {
  BockOverview,
  BockRecord,
  FinanceOverview,
  GelegtRecord,
  ModeRecord,
  PartnerOverview,
  PartnerPair,
  PlayerGameModes,
  SoloOverview,
  SoloRecord,
  Standing,
  Statistics,
} from "./stats/StatisticEngine";
