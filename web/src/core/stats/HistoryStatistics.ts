/*
 * Die Statistik über viele Abende (Kotlin: `domain/stats/HistoryStatistics.kt`).
 */
import { EveningCalculator } from "../evening/EveningCalculator";
import { compareInstants } from "../instant";
import { compareNumbers, compareText, distinct, intAdd, intSub } from "../kotlin";
import type { Evening, EveningId, GameType, PlayerId } from "../model";
import { isSolo } from "../rules/RuleSet";
import type { ExpensiveGame } from "./EveningStatistics";
import type { PlayedGame } from "./EveningDigest";
import { EveningDigest, PlayerAccumulator, TableAccumulator } from "./EveningDigest";
import type { PlayerStats } from "./PlayerStats";

/**
 * Bilanz eines Spielerpaars aus den Spielen zu zweit gegen zwei (Rufspiel und Über Kreuz), in denen beide auf
 * derselben Seite standen (Kotlin: `PairRecord`).
 *
 * @property balance Gemeinsamer Saldo dieser Spiele in Cent: die Summe der Beträge beider Spieler.
 * @property lost Wie viele dieser Spiele das Paar verloren hat (abgeleitet).
 * @property rate Anteil der Siege zwischen 0 und 1; `null`, wenn das Paar so nie zusammen gespielt hat (abgeleitet).
 */
export interface PairRecord {
  readonly played: number;
  readonly won: number;
  readonly balance: number;
  readonly lost: number;
  readonly rate: number | null;
}

export function pairRecord(played: number, won: number, balance: number): PairRecord {
  return { played, won, balance, lost: intSub(played, won), rate: played === 0 ? null : won / played };
}

/**
 * Wie gut zwei Spieler zusammenpassen. Das Paar ist ungeordnet; `first` ist der Spieler mit der kleineren Kennung
 * (Kotlin: `PartnerRecord`).
 *
 * Bei Über Kreuz sagt niemand an: Dort ist das „Spielerpaar“ einfach das Paar, das im Eintrag als Ansager und
 * Mitspieler steht, das „Gegenspielerpaar“ das andere. Wer nur wissen will, wie zwei zusammen abschneiden, nimmt die
 * Paare der `StatisticEngine` (`Statistics.partners`).
 *
 * @property asDeclarerPair Spiele als Spielerpaar: einer hat angesagt, der andere war sein Mitspieler.
 * @property asOpponentPair Spiele, in denen die beiden gemeinsam die Gegenspieler waren.
 */
export interface PartnerRecord {
  readonly first: PlayerId;
  readonly second: PlayerId;
  readonly asDeclarerPair: PairRecord;
  readonly asOpponentPair: PairRecord;
}

/**
 * Ob dieser Eintrag zu den beiden Spielern gehört, egal in welcher Reihenfolge (Kotlin: `PartnerRecord.isPair` und
 * `PartnerPair.isPair`).
 */
export function isPair(pair: { readonly first: PlayerId; readonly second: PlayerId }, a: PlayerId, b: PlayerId): boolean {
  return (pair.first === a && pair.second === b) || (pair.first === b && pair.second === a);
}

/**
 * Die Statistik über viele Abende (Kotlin: `HistoryStats`).
 *
 * Ein Abend ohne abgerechnetes Spiel gilt nicht als gespielter Abend: Er zählt weder in `eveningCount` noch bei den
 * Abenden, Abendsiegen und besten oder schlechtesten Abenden der Spieler. Was dort trotzdem eingetragen wurde, zählt
 * aber mit, damit kein gebuchter Betrag verloren geht: freie Buchungen (Saldo und Umsatz), zusammengeworfene Blätter
 * und ausgelöste Bockrunden. Laufende und beendete Abende zählen gleich.
 *
 * @property playerStats Kennzahlen je Spieler über alle Abende, an denen er am Tisch saß. Reihenfolge: höchster Saldo
 *   zuerst, bei Gleichstand nach Kennung.
 * @property eveningCount Abende mit mindestens einem abgerechneten Spiel.
 * @property gameCount Gespielte Spiele über alle Abende.
 * @property partnerRecords Je Spielerpaar die gemeinsamen Spiele zu zweit gegen zwei (Rufspiel und Über Kreuz),
 *   sortiert nach den Kennungen. Paare ohne gemeinsames Spiel fehlen.
 * @property gameTypeCounts Wie oft jede Spielart gespielt wurde. Jede Spielart ist enthalten, notfalls mit 0.
 * @property bockGamesCount Gespielte Spiele, die als Bockspiel doppelt (oder mehrfach) zählten.
 * @property bockTriggerCount Wie oft eine Bockrunde ausgelöst wurde. Gezählt wird jeder solche Eintrag, auch wenn er
 *   nach dem Regelwerk des Abends ohne Wirkung blieb (Bockrunde abgeschaltet oder Regel „ignorieren“).
 * @property thrownInCount Wie oft das Blatt zusammengeworfen wurde.
 * @property leadChanges Führungswechsel, über alle Abende zusammengezählt.
 * @property mostExpensiveGame Das Spiel mit dem höchsten Spielwert, bei Gleichstand das frühere; `null` ohne Spiel.
 * @property turnover Umsatz: die Summe aller positiven Beträge aller Einträge, auch der freien Buchungen.
 */
export interface HistoryStats {
  readonly playerStats: readonly PlayerStats[];
  readonly eveningCount: number;
  readonly gameCount: number;
  readonly partnerRecords: readonly PartnerRecord[];
  readonly gameTypeCounts: ReadonlyMap<GameType, number>;
  readonly bockGamesCount: number;
  readonly bockTriggerCount: number;
  readonly thrownInCount: number;
  readonly leadChanges: number;
  readonly mostExpensiveGame: ExpensiveGame | null;
  readonly turnover: number;
}

/** Die gemeinsamen Spiele zweier Spieler zu zweit gegen zwei (Kotlin: `HistoryStats.partnerRecord`); sonst `null`. */
export function partnerRecord(stats: HistoryStats, a: PlayerId, b: PlayerId): PartnerRecord | null {
  return stats.partnerRecords.find((record) => isPair(record, a, b)) ?? null;
}

/** Rechnet jeden Abend genau einmal durch: ohne Dubletten (die erste Fassung zählt) und in zeitlicher Reihenfolge. */
export function historyDigests(evenings: readonly Evening[]): EveningDigest[] {
  const seen = new Set<EveningId>();
  const unique: Evening[] = [];
  for (const evening of evenings) {
    if (seen.has(evening.id)) continue;
    seen.add(evening.id);
    unique.push(evening);
  }
  // Zeitpunkte nie als Text vergleichen: „…:00.5Z“ liegt nach „…:00Z“.
  unique.sort((a, b) => compareInstants(a.startedAt, b.startedAt) || compareText(a.id, b.id));
  return unique.map((evening) => new EveningDigest(EveningCalculator.compute(evening)));
}

/** Die Statistik über viele schon durchgerechnete Abende (in zeitlicher Reihenfolge, jeder genau einmal). */
export function historyFromDigests(digests: readonly EveningDigest[]): HistoryStats {
  const table = new TableAccumulator();
  for (const digest of digests) table.add(digest);

  const playerStats = distinct(digests.flatMap((digest) => digest.seating))
    .map((player) => {
      const accumulator = new PlayerAccumulator(player);
      for (const digest of digests) if (digest.seating.includes(player)) accumulator.add(digest);
      return accumulator.build();
    })
    .sort((a, b) => compareNumbers(b.balance, a.balance) || compareText(a.player, b.player));

  return {
    playerStats,
    eveningCount: digests.filter((digest) => digest.games.length > 0).length,
    gameCount: table.gameCount,
    partnerRecords: partnerRecords(digests),
    gameTypeCounts: table.gameTypeCounts,
    bockGamesCount: table.bockGamesCount,
    bockTriggerCount: table.bockTriggerCount,
    thrownInCount: table.thrownInCount,
    leadChanges: table.leadChanges,
    mostExpensiveGame: table.mostExpensiveGame,
    turnover: table.turnover,
  };
}

/** Ein ungeordnetes Spielerpaar: Die kleinere Kennung steht immer vorn. */
interface PairKey {
  readonly first: PlayerId;
  readonly second: PlayerId;
}

class PairTally {
  private played = 0;
  private won = 0;
  private balance = 0;

  /** `pair` sind die beiden Spieler einer Seite in Sitzreihenfolge. */
  add(game: PlayedGame, pair: readonly PlayerId[]): void {
    this.played += 1;
    if (game.won(pair[0])) this.won += 1;
    for (const player of pair) this.balance = intAdd(this.balance, game.amount(player));
  }

  toRecord(): PairRecord {
    return pairRecord(this.played, this.won, this.balance);
  }
}

const NO_GAMES = pairRecord(0, 0, 0);

function partnerRecords(digests: readonly EveningDigest[]): PartnerRecord[] {
  // Schlüssel ist das Paar als JSON-Text; so kann keine Kennung zwei Paare verwechseln.
  const keys = new Map<string, PairKey>();
  const declarerPairs = new Map<string, PairTally>();
  const opponentPairs = new Map<string, PairTally>();
  const keyOf = (pair: readonly PlayerId[]): string => {
    const [first, second] = [...pair].sort(compareText);
    const key = JSON.stringify([first, second]);
    if (!keys.has(key)) keys.set(key, { first, second });
    return key;
  };
  const tally = (tallies: Map<string, PairTally>, key: string): PairTally => {
    let found = tallies.get(key);
    if (found === undefined) {
      found = new PairTally();
      tallies.set(key, found);
    }
    return found;
  };

  for (const digest of digests) {
    for (const game of digest.games) {
      if (isSolo(game.entry.type)) continue;
      const declarers = digest.seating.filter((player) => game.declarerSide.has(player));
      const opponents = digest.seating.filter((player) => !game.declarerSide.has(player));
      if (declarers.length !== 2 || opponents.length !== 2) continue;
      tally(declarerPairs, keyOf(declarers)).add(game, declarers);
      tally(opponentPairs, keyOf(opponents)).add(game, opponents);
    }
  }
  return distinct([...declarerPairs.keys(), ...opponentPairs.keys()])
    .map((key) => keys.get(key) as PairKey)
    .sort((a, b) => compareText(a.first, b.first) || compareText(a.second, b.second))
    .map((key) => {
      const id = JSON.stringify([key.first, key.second]);
      return {
        first: key.first,
        second: key.second,
        asDeclarerPair: declarerPairs.get(id)?.toRecord() ?? NO_GAMES,
        asOpponentPair: opponentPairs.get(id)?.toRecord() ?? NO_GAMES,
      };
    });
}

/**
 * Berechnet die Statistik über viele Abende. Reine Funktion; nichts wird gespeichert.
 *
 * Jeder Abend wird mit dem `EveningCalculator` neu durchgerechnet. Die Reihenfolge der übergebenen Abende spielt
 * keine Rolle: Ausgewertet wird in zeitlicher Reihenfolge (bei gleichem Beginn nach der Kennung), damit bei
 * Gleichstand immer das frühere Ereignis gewinnt.
 *
 * Jede Kennung darf nur einen Abend bezeichnen. Derselbe Abend doppelt in der Liste zählt nur einmal. Stehen unter
 * einer Kennung zwei verschiedene Fassungen in der Liste (das lässt die Datenhaltung nicht zu), zählt die zuerst
 * übergebene; nur in diesem Fall kommt es auf die Reihenfolge an.
 */
export const HistoryStatistics = {
  compute(evenings: readonly Evening[]): HistoryStats {
    return historyFromDigests(historyDigests(evenings));
  },
} as const;
