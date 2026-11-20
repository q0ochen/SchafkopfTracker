/*
 * Der Rechenkern der Web-App gegen den Kotlin-Kern der Android-App: `kotlin-golden.json` enthält viele feste
 * Eingaben und die Ausgaben, die der echte Kotlin-Kern dafür berechnet hat (erzeugt und auf Aktualität geprüft von
 * domain/src/test/kotlin/io/github/q0ochen/schafkopf/domain/golden/WebGoldenTest.kt). Der Port muss für dieselben
 * Eingaben exakt dieselben Ausgaben liefern: jeden Cent, jede Kennzahl, jede Reihenfolge, jede Quote bitgleich.
 *
 * Statt tausender Einzelprüfungen sammelt jeder Test die ersten Abweichungen mit Fall-Kennung und Pfad.
 */
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { EntryIssue, Evening, GameFacts, PlayerId, RuleSet, RuleViolation } from "../index";
import {
  declarerSide,
  EveningCalculator,
  EveningStatistics,
  HistoryStatistics,
  losers,
  PayoutCalculator,
  Progression,
  Ranking,
  ScoreCalculator,
  SettleUp,
  StatisticEngine,
  winners,
} from "../index";
import { DuplicateCheck } from "../round/DuplicateCheck";
import { EveningMerge } from "../round/EveningMerge";
import { InviteLinks } from "../round/InviteLinks";
import type { EveningJson, Json, JsonObject, RuleSetJson } from "./goldenCodec";
import {
  decodeEvening,
  decodeFacts,
  decodeRuleSet,
  encodeEntryResult,
  encodeEvening,
  encodeState,
  firstDifference,
  toJson,
} from "./goldenCodec";

interface ScoreCase {
  readonly id: string;
  readonly rules: string;
  readonly facts: GameFacts;
  readonly [output: string]: Json | GameFacts;
}

interface PayoutCase {
  readonly id: string;
  readonly value: number;
  readonly winners: PlayerId[];
  readonly losers: PlayerId[];
  readonly SCHAFKOPF: Json;
  readonly POT_SPLIT: Json;
}

interface BalancesCase {
  readonly id: string;
  readonly balances: [PlayerId, number][];
  readonly isBalanced: boolean;
  readonly transfers: Json;
  readonly ranks: Json;
}

interface EveningCase {
  readonly id: string;
  readonly evening: EveningJson;
  readonly state: JsonObject;
  readonly settleUp: Json;
  readonly progression: Json;
  readonly eveningStatistics: JsonObject;
  readonly statistics: JsonObject;
}

interface HistoryCase {
  readonly id: string;
  readonly evenings: string[];
  readonly statistics: Json;
  readonly history: Json;
}

/** Drei Fassungen eines Abends für `EveningMerge.merge3` und das Ergebnis des Kotlin-Kerns. */
interface MergeCase {
  readonly id: string;
  readonly base: EveningJson | null;
  readonly local: EveningJson;
  readonly remote: EveningJson;
  readonly merged: EveningJson;
}

/** Ein Abend für `DuplicateCheck.suspects` und die gemeldeten Kennungen in ihrer Reihenfolge. */
interface DuplicateCase {
  readonly id: string;
  readonly evening: EveningJson;
  readonly suspects: string[];
}

/** Ein eingefügter Text für `InviteLinks.tokenFrom` und der gefundene Code. */
interface InviteCase {
  readonly id: string;
  readonly text: string;
  readonly token: string | null;
}

interface GoldenFile {
  readonly rules: Readonly<Record<string, RuleSetJson>>;
  readonly score: readonly ScoreCase[];
  readonly payout: readonly PayoutCase[];
  readonly balances: readonly BalancesCase[];
  readonly evenings: readonly EveningCase[];
  readonly histories: readonly HistoryCase[];
  readonly merges: readonly MergeCase[];
  readonly duplicates: readonly DuplicateCase[];
  readonly invites: readonly InviteCase[];
}

const golden = JSON.parse(readFileSync(new URL("./kotlin-golden.json", import.meta.url), "utf8")) as GoldenFile;

const rulesByName = new Map<string, RuleSet>(
  Object.entries(golden.rules).map(([name, json]) => [name, decodeRuleSet(json)]),
);

/** Die Abende der Datei, je Fall genau ein Objekt (eine Gruppe, die einen Fall zweimal nennt, übergibt es zweimal). */
const eveningsById = new Map<string, Evening>(golden.evenings.map((c) => [c.id, decodeEvening(c.evening)]));

/** Prüft alle Fälle und liefert die ersten Abweichungen (höchstens zehn) als lesbare Texte. */
function mismatches<T extends { readonly id: string }>(cases: readonly T[], check: (item: T) => [Json, Json]): string[] {
  const failures: string[] = [];
  for (const item of cases) {
    let difference: string | null;
    try {
      const [expected, actual] = check(item);
      difference = firstDifference(expected, actual);
    } catch (error) {
      difference = `Ausnahme: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`;
    }
    if (difference !== null) failures.push(`${item.id}: ${difference}`);
    if (failures.length >= 10) break;
  }
  return failures;
}

/** Nur die genannten Felder eines Falls (die Ausgaben des Kotlin-Kerns). */
function pick(source: object, keys: readonly string[]): JsonObject {
  const record = source as Record<string, Json>;
  return Object.fromEntries(keys.map((key) => [key, record[key]]));
}

describe("Rechenkern gegen den Kotlin-Kern (kotlin-golden.json)", () => {
  it("Einzelspiele: Spielwert, Verteilung, Prüfung und Seiten", () => {
    const outputs = ["declarerSide", "winners", "losers", "breakdown", "amounts", "validate"];
    const failures = mismatches(golden.score, (c) => {
      const rules = rulesByName.get(c.rules);
      if (rules === undefined) throw new Error(`Regelwerk ${c.rules} fehlt`);
      const facts = decodeFacts(c.facts);
      const actual = {
        declarerSide: toJson(declarerSide(facts)),
        winners: winners(facts, facts.players),
        losers: losers(facts, facts.players),
        breakdown: toJson(ScoreCalculator.breakdown(facts, rules)),
        amounts: toJson(ScoreCalculator.amounts(facts, rules)),
        validate: ScoreCalculator.validate(facts, rules),
      };
      return [pick(c, outputs), actual];
    });
    expect(failures).toEqual([]);
    expect(golden.score.length).toBeGreaterThan(800);
  });

  it("Verteilung: Schafkopf-Regel und Topf-Teilung mit beliebigen Listen", () => {
    const failures = mismatches(golden.payout, (c) => [
      pick(c, ["SCHAFKOPF", "POT_SPLIT"]),
      {
        SCHAFKOPF: toJson(PayoutCalculator.of("SCHAFKOPF").payout(c.value, c.winners, c.losers)),
        POT_SPLIT: toJson(PayoutCalculator.of("POT_SPLIT").payout(c.value, c.winners, c.losers)),
      },
    ]);
    expect(failures).toEqual([]);
    expect(golden.payout.length).toBeGreaterThan(300);
  });

  it("Salden: wer zahlt wem, ob sie aufgehen, Plätze", () => {
    const failures = mismatches(golden.balances, (c) => {
      const balances = new Map(c.balances);
      return [
        pick(c, ["isBalanced", "transfers", "ranks"]),
        {
          isBalanced: SettleUp.isBalanced(balances),
          transfers: toJson(SettleUp.transfers(balances)),
          ranks: toJson(Ranking.ranks(balances)),
        },
      ];
    });
    expect(failures).toEqual([]);
    expect(golden.balances.length).toBeGreaterThan(100);
  });

  it("Abende: Abrechnung, Geber, Bock, Salden, Verlauf und Statistik eines Abends", () => {
    const failures = mismatches(golden.evenings, (c) => {
      const evening = eveningsById.get(c.id) as Evening;
      const state = EveningCalculator.compute(evening);
      if (state.evening !== evening) throw new Error("state.evening ist nicht der übergebene Abend");
      const results = state.results.map((result, index) => {
        const entry = result.entry;
        if (entry !== evening.entries[index]) throw new Error(`results[${index}].entry ist nicht der Eintrag`);
        const violations =
          entry.kind === "game"
            ? ScoreCalculator.validate(
                EveningCalculator.toFacts(entry, evening.seating, result.bockExponent),
                evening.rules,
              )
            : null;
        return encodeEntryResult(result, violations);
      });
      const actual = {
        state: encodeState(state, results),
        settleUp: { isBalanced: SettleUp.isBalanced(state.balances), transfers: toJson(SettleUp.transfers(state.balances)) },
        progression: {
          balanceSeries: toJson(Progression.balanceSeries(state)),
          rankSeries: toJson(Progression.rankSeries(state)),
        },
        eveningStatistics: toJson(EveningStatistics.compute(state)),
        statistics: toJson(StatisticEngine.forEvening(evening)),
      };
      return [withHistoryPlayerStats(c), actual];
    });
    expect(failures).toEqual([]);
    expect(golden.evenings.length).toBeGreaterThan(60);
  });

  it("Ewige Tabelle: StatisticEngine.forEvenings und HistoryStatistics.compute", () => {
    const failures = mismatches(golden.histories, (c) => {
      const evenings = c.evenings.map((id) => eveningsById.get(id) as Evening);
      const statistics = StatisticEngine.forEvenings(evenings);
      const { history, ...withoutHistory } = statistics;
      return [
        { statistics: c.statistics, history: c.history, forEveningsHistory: c.history },
        {
          statistics: toJson(withoutHistory),
          history: toJson(HistoryStatistics.compute(evenings)),
          forEveningsHistory: toJson(history),
        },
      ];
    });
    expect(failures).toEqual([]);
    expect(golden.histories.length).toBeGreaterThanOrEqual(40);
  });

  it("Gemeinsame Runde: EveningMerge.merge3 liefert für jeden Fall denselben Abend", () => {
    // Alle Abweichungen sammeln, nicht nur die ersten zehn: Hier muss wirklich jeder Fall stimmen.
    const failures: string[] = [];
    for (const c of golden.merges) {
      const base = c.base === null ? null : decodeEvening(c.base);
      const local = decodeEvening(c.local);
      const remote = decodeEvening(c.remote);
      const snapshot = (): string => JSON.stringify([base, local, remote].map((it) => (it === null ? null : encodeEvening(it))));
      const before = snapshot();
      const merged = EveningMerge.merge3(base, local, remote);
      const difference = firstDifference(c.merged as unknown as Json, encodeEvening(merged));
      if (difference !== null) failures.push(`${c.id}: ${difference}`);
      // Reine Funktion: Die Eingaben bleiben, wie sie waren.
      if (snapshot() !== before) failures.push(`${c.id}: Die Eingaben wurden verändert.`);
    }
    expect(failures).toEqual([]);
    expect(golden.merges.length).toBe(132);
  });

  it("Gemeinsame Runde: DuplicateCheck.suspects meldet für jeden Fall dieselben Spiele", () => {
    const failures: string[] = [];
    for (const c of golden.duplicates) {
      const suspects = [...DuplicateCheck.suspects(decodeEvening(c.evening))];
      const difference = firstDifference(c.suspects, suspects);
      if (difference !== null) failures.push(`${c.id}: ${difference}`);
    }
    expect(failures).toEqual([]);
    expect(golden.duplicates.length).toBe(60);
  });

  it("Gemeinsame Runde: InviteLinks.tokenFrom findet in jedem Text denselben Code", () => {
    const failures: string[] = [];
    for (const c of golden.invites) {
      const difference = firstDifference(c.token, InviteLinks.tokenFrom(c.text));
      if (difference !== null) failures.push(`${c.id}: ${difference}`);
    }
    expect(failures).toEqual([]);
    expect(golden.invites.length).toBe(22);
  });

  it("die Golden-Daten der gemeinsamen Runde enthalten die gezielten Fallen", () => {
    const ids = new Set(golden.merges.map((c) => c.id));
    for (const id of ["kennung-emoji", "kennung-gross-klein", "kennung-ziffern", "kennung-umlaut", "zeit-mit-millisekunden", "doppelte-kennungen"]) {
      expect(ids).toContain(id);
    }
    expect(golden.merges.filter((c) => c.base === null).length).toBeGreaterThan(10);
    // Als Text verglichen stünde „…:05:00.500Z“ vor „…:05:00Z“; als Zeit ist es umgekehrt.
    const millis = golden.merges.find((c) => c.id === "zeit-mit-millisekunden");
    expect(millis?.merged.entries.slice(-2).map((entry) => entry.id)).toEqual(["t", "m"]);
    // Nach UTF-16-Einheiten steht das Emoji (ein Ersatzzeichenpaar) vor „～“, nach Unicode-Zeichen dahinter.
    const emoji = golden.merges.find((c) => c.id === "kennung-emoji");
    expect(emoji?.merged.entries.slice(-2).map((entry) => entry.id)).toEqual(["😀", "～"]);
    expect(golden.duplicates.filter((c) => c.suspects.length > 0).length).toBeGreaterThan(10);
    expect(golden.duplicates.find((c) => c.id === "gelegt-gleich-andere-reihenfolge")?.suspects).toEqual(["zwei"]);
    expect(golden.invites.filter((c) => c.token === null).length).toBeGreaterThan(5);
  });

  it("die Golden-Daten decken die Grenzfälle wirklich ab", () => {
    const violations = new Set(golden.score.flatMap((c) => c.validate as RuleViolation[]));
    for (const violation of ["VALUE_OUT_OF_RANGE", "DUPLICATE_PLAYERS", "WRONG_PLAYER_COUNT", "BOCK_EXPONENT_NEGATIVE"]) {
      expect(violations).toContain(violation);
    }
    const results = golden.evenings.flatMap((c) => c.state.results as JsonObject[]);
    const issues = new Set(results.flatMap((result) => result.issues as EntryIssue[]));
    expect([...issues].sort()).toEqual(["AMOUNTS_NOT_ZERO_SUM", "BALANCE_OUT_OF_RANGE", "RULE_VIOLATION", "UNKNOWN_PLAYER"]);
    expect(results.filter((result) => result.breakdown !== null).length).toBeGreaterThan(500);
    // Int-Summen der Statistik, die wie in Kotlin überlaufen: Der Umsatz eines Abends ist dann negativ.
    expect(golden.evenings.some((c) => (c.eveningStatistics.turnover as number) < 0)).toBe(true);
    // Gruppen mit doppelt übergebenem Abend und mit leerer Liste.
    expect(golden.histories.some((c) => new Set(c.evenings).size < c.evenings.length)).toBe(true);
    expect(golden.histories.some((c) => c.evenings.length === 0)).toBe(true);
  });
});

/**
 * Für einen einzelnen Abend stehen die Kennzahlen je Spieler von `statistics.history` nur einmal in der Datei: Sie sind
 * genau die der Abend-Statistik (das prüft der Kotlin-Test), dort in Sitzreihenfolge; `playerStatsOrder` nennt die
 * Reihenfolge in `history`. Hier wird daraus wieder die vollständige erwartete Ausgabe.
 */
function withHistoryPlayerStats(c: EveningCase): JsonObject {
  const { playerStatsOrder, ...history } = c.statistics.history as JsonObject;
  const byPlayer = new Map(
    (c.eveningStatistics.playerStats as JsonObject[]).map((stats) => [stats.player as string, stats]),
  );
  const playerStats = (playerStatsOrder as string[]).map((player) => byPlayer.get(player) ?? null);
  return {
    ...pick(c, ["state", "settleUp", "progression", "eveningStatistics"]),
    statistics: { ...c.statistics, history: { ...history, playerStats } },
  };
}
