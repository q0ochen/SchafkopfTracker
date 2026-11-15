/*
 * Gemeinsames Rechenwerk für die Statistik eines Abends und die Statistik über viele Abende
 * (Kotlin: `domain/stats/EveningDigest.kt`, dort `internal`). Alles hier ist reine Rechnung ohne Zustand nach außen;
 * gespeichert wird nichts. Nicht Teil der öffentlichen Schnittstelle (`../index.ts`).
 */
import type { EntryResult, EveningState } from "../evening/EveningCalculator";
import { EveningCalculator } from "../evening/EveningCalculator";
import { declarerSide } from "../evening/Evening";
import { distinct, intAdd, intSub, mapValues, maxOrNull, minOrNull } from "../kotlin";
import type { BockTriggerEntry, EveningId, GameEntry, GameType, PlayerId, RuleSet } from "../model";
import { GAME_TYPES } from "../model";
import { hasDeclarer, isSolo } from "../rules/RuleSet";
import type { ValueBreakdown } from "../rules/ScoreCalculator";
import { ScoreCalculator } from "../rules/ScoreCalculator";
import type { ExpensiveGame } from "./EveningStatistics";
import type { EveningBalance, GameAmount, PlayerStats, RoleRecord } from "./PlayerStats";
import { playerStats, roleRecord } from "./PlayerStats";

/**
 * Ein gespieltes Spiel, das abgerechnet werden konnte, so aufbereitet, wie die Statistik es braucht.
 *
 * `seating` ist die Sitzordnung des Abends, so wie der Abend sie gespeichert hat; `rules` das Regelwerk des Abends.
 */
export class PlayedGame {
  readonly eveningId: EveningId;
  readonly entry: GameEntry;
  readonly gameNumber: number;
  readonly bockExponent: number;
  readonly breakdown: ValueBreakdown;
  private readonly amounts: ReadonlyMap<PlayerId, number>;

  /** Der Ansager und, beim Rufspiel und bei Über Kreuz, sein Mitspieler. */
  readonly declarerSide: ReadonlySet<PlayerId>;

  /**
   * Die Beträge desselben Spiels, hätte einer weniger gelegt; `null`, wenn niemand gelegt hat. Nachgerechnet mit dem
   * `ScoreCalculator`, damit auch die Rest-Cent der Topf-Teilung stimmen.
   */
  private readonly amountsWithOneKlopferLess: ReadonlyMap<PlayerId, number> | null;

  constructor(
    eveningId: EveningId,
    entry: GameEntry,
    gameNumber: number,
    bockExponent: number,
    breakdown: ValueBreakdown,
    amounts: ReadonlyMap<PlayerId, number>,
    seating: readonly PlayerId[],
    rules: RuleSet,
  ) {
    this.eveningId = eveningId;
    this.entry = entry;
    this.gameNumber = gameNumber;
    this.bockExponent = bockExponent;
    this.breakdown = breakdown;
    this.amounts = amounts;
    this.declarerSide = declarerSide(entry);
    if (entry.klopfer.length === 0) {
      this.amountsWithOneKlopferLess = null;
    } else {
      const facts = EveningCalculator.toFacts(entry, seating, bockExponent);
      this.amountsWithOneKlopferLess = ScoreCalculator.amounts(
        { ...facts, klopferCount: intSub(facts.klopferCount, 1) },
        rules,
      );
    }
  }

  /** Der Spielwert: so viel zahlt ein Verlierer an einen Gewinner. */
  get value(): number {
    return this.breakdown.value;
  }

  /** Ob das Spiel als Bockspiel doppelt (oder mehrfach) zählte. */
  get isBockGame(): boolean {
    return this.bockExponent > 0;
  }

  /** Ob der Spieler auf der Gewinnerseite stand. */
  won(player: PlayerId): boolean {
    return this.declarerSide.has(player) === this.entry.declarerWon;
  }

  /** Betrag des Spielers aus diesem Spiel in Cent. */
  amount(player: PlayerId): number {
    return this.amounts.get(player) ?? 0;
  }

  /**
   * Ob der Spieler in diesem Spiel selbst gespielt hat: als Ansager, als Mitspieler oder als Alleinspieler.
   * Bei Über Kreuz sagt niemand an; dort spielen alle vier.
   */
  playedBy(player: PlayerId): boolean {
    return !hasDeclarer(this.entry.type) || this.declarerSide.has(player);
  }

  /**
   * Was das eigene Legen den Spieler in diesem Spiel gekostet (negativ) oder ihm gebracht hat (positiv): der gebuchte
   * Betrag minus der Betrag desselben Spiels ohne den eigenen Leger. 0, wenn er nicht gelegt hat.
   */
  klopferEffect(player: PlayerId): number {
    const without = this.amountsWithOneKlopferLess;
    if (without === null) return 0;
    if (!this.entry.klopfer.includes(player)) return 0;
    return intSub(this.amount(player), without.get(player) ?? 0);
  }
}

/** Ob dieser Eintrag ein gespieltes Spiel ist, das abgerechnet werden konnte. */
export function isPlayedGame(result: EntryResult): boolean {
  return result.entry.kind === "game" && result.gameNumber !== null && result.breakdown !== null;
}

/**
 * Zählt, wie oft die Führung gewechselt hat: wie oft ein anderer Spieler alleiniger Erster ist als der letzte
 * alleinige Erste davor. Der erste alleinige Erste des Abends ist noch kein Wechsel.
 */
function countLeadChanges(points: readonly ReadonlyMap<PlayerId, number>[]): number {
  let changes = 0;
  let lastLeader: PlayerId | null = null;
  for (const point of points) {
    const best = maxOrNull(point.values());
    if (best === null) continue;
    const leaders = [...point].filter(([, balance]) => balance === best);
    if (leaders.length !== 1) continue;
    const leader = leaders[0][0];
    if (lastLeader !== null && leader !== lastLeader) changes += 1;
    lastLeader = leader;
  }
  return changes;
}

/**
 * Ein durchgerechneter Abend, einmal für die Statistik vorsortiert.
 *
 * Als gespielte Spiele zählen nur Spiele, die abgerechnet werden konnten. Ein Spiel mit unbekanntem Ansager oder
 * fehlendem Mitspieler bekommt zwar eine Spielnummer, bucht aber nichts und bleibt in der Statistik außen vor.
 */
export class EveningDigest {
  readonly state: EveningState;
  readonly eveningId: EveningId;

  /** Die Spieler in Sitzreihenfolge, jeder einmal. */
  readonly seating: readonly PlayerId[];

  readonly games: readonly PlayedGame[];
  readonly bockTriggers: readonly BockTriggerEntry[];
  readonly thrownInCount: number;

  /** Summe aller positiven Beträge aller Einträge, auch der freien Buchungen (als `Int`, wie in Kotlin). */
  readonly turnover: number;

  /**
   * Die Salden aller Spieler am Start und nach jedem abgerechneten Spiel. Freie Buchungen zählen zum nächsten
   * abgerechneten Spiel; stehen sie hinter dem letzten, zählen sie zu dessen Punkt. Ohne ein einziges Spiel gibt es
   * nur einen Punkt, den Stand des Abends.
   *
   * Anders als im Verlauf des Abend-Bildschirms (`Progression`) hat ein Spiel, das sich nicht abrechnen ließ, hier
   * keinen eigenen Punkt: Die Statistik zählt solche Spiele nirgends mit.
   */
  readonly balancePoints: readonly ReadonlyMap<PlayerId, number>[];

  readonly leadChanges: number;
  readonly highestBalance: number;
  readonly lowestBalance: number;

  /** Stehen am Ende alle gleich, gibt es weder einen Sieger noch einen Letzten. */
  readonly allEqual: boolean;

  constructor(state: EveningState) {
    const { evening } = state;
    this.state = state;
    this.eveningId = evening.id;
    this.seating = distinct(evening.seating);

    const games: PlayedGame[] = [];
    for (const result of state.results) {
      const { entry, gameNumber, breakdown } = result;
      if (entry.kind !== "game" || gameNumber === null || breakdown === null) continue;
      games.push(
        new PlayedGame(
          this.eveningId,
          entry,
          gameNumber,
          result.bockExponent,
          breakdown,
          result.amounts,
          evening.seating,
          evening.rules,
        ),
      );
    }
    this.games = games;

    this.bockTriggers = evening.entries.filter((entry): entry is BockTriggerEntry => entry.kind === "bock");
    this.thrownInCount = evening.entries.filter((entry) => entry.kind === "thrown_in").length;

    let turnover = 0;
    for (const result of state.results) {
      let positive = 0;
      for (const amount of result.amounts.values()) if (amount > 0) positive = intAdd(positive, amount);
      turnover = intAdd(turnover, positive);
    }
    this.turnover = turnover;

    const points: ReadonlyMap<PlayerId, number>[] = [new Map(evening.seating.map((player) => [player, 0]))];
    for (const result of state.results) if (isPlayedGame(result)) points.push(result.balances);
    points[points.length - 1] = state.balances;
    this.balancePoints = points;

    this.leadChanges = countLeadChanges(points);
    this.highestBalance = maxOrNull(state.balances.values()) ?? 0;
    this.lowestBalance = minOrNull(state.balances.values()) ?? 0;
    this.allEqual = this.highestBalance === this.lowestBalance;
  }

  balance(player: PlayerId): number {
    return this.state.balances.get(player) ?? 0;
  }
}

/** Zählt Fälle und wie viele davon gut ausgingen. */
class Tally {
  private played = 0;
  private won = 0;

  add(success: boolean): void {
    this.played += 1;
    if (success) this.won += 1;
  }

  toRecord(): RoleRecord {
    return roleRecord(this.played, this.won);
  }
}

/** Sammelt die Kennzahlen eines Spielers über beliebig viele Abende, an denen er am Tisch saß. */
export class PlayerAccumulator {
  private readonly player: PlayerId;
  private evenings = 0;
  private games = 0;
  private balance = 0;
  private gamesWon = 0;
  private readonly asDeclarer = new Tally();
  private readonly asPartner = new Tally();
  private readonly asOpponent = new Tally();
  private readonly declarerByType: ReadonlyMap<GameType, Tally> = new Map(GAME_TYPES.map((type) => [type, new Tally()]));
  private readonly klopferGiven = new Tally();
  private klopferEffect = 0;
  private readonly kontraGiven = new Tally();
  private readonly retourGiven = new Tally();
  private schneiderWon = 0;
  private schneiderLost = 0;
  private schwarzWon = 0;
  private schwarzLost = 0;
  private readonly toutPlayed = new Tally();
  private biggestWin: GameAmount | null = null;
  private biggestLoss: GameAmount | null = null;
  private longestWinStreak = 0;
  private longestLoseStreak = 0;
  private nixerTriggers = 0;
  private eveningWins = 0;
  private eveningLastPlaces = 0;
  private bestEvening: EveningBalance | null = null;
  private worstEvening: EveningBalance | null = null;

  constructor(player: PlayerId) {
    this.player = player;
  }

  /** Nimmt einen Abend auf. Die Abende müssen in zeitlicher Reihenfolge kommen. */
  add(digest: EveningDigest): void {
    const { player } = this;
    const eveningBalance = digest.balance(player);
    this.balance = intAdd(this.balance, eveningBalance);
    // Nur Bockrunden wegen 6 Nixern zählen; bei einer von Hand ausgelösten gibt es keinen Nixer-Spieler.
    this.nixerTriggers += digest.bockTriggers.filter(
      (trigger) => trigger.reason === "SECHS_NIXER" && trigger.player === player,
    ).length;
    if (digest.games.length === 0) return;

    this.evenings += 1;
    if (!digest.allEqual) {
      if (eveningBalance === digest.highestBalance) this.eveningWins += 1;
      if (eveningBalance === digest.lowestBalance) this.eveningLastPlaces += 1;
    }
    const best = this.bestEvening;
    if (best === null || eveningBalance > best.balance) {
      this.bestEvening = { eveningId: digest.eveningId, balance: eveningBalance };
    }
    const worst = this.worstEvening;
    if (worst === null || eveningBalance < worst.balance) {
      this.worstEvening = { eveningId: digest.eveningId, balance: eveningBalance };
    }

    let winRun = 0;
    let loseRun = 0;
    for (const game of digest.games) {
      const won = game.won(player);
      this.addGame(game, won);
      if (won) {
        winRun += 1;
        loseRun = 0;
      } else {
        loseRun += 1;
        winRun = 0;
      }
      this.longestWinStreak = Math.max(this.longestWinStreak, winRun);
      this.longestLoseStreak = Math.max(this.longestLoseStreak, loseRun);
    }
  }

  private addGame(game: PlayedGame, won: boolean): void {
    const { player } = this;
    const { entry } = game;
    const amount = game.amount(player);
    this.games += 1;
    if (won) this.gamesWon += 1;

    if (!hasDeclarer(entry.type)) {
      // Bei Über Kreuz sagt niemand an: Dort gibt es weder Ansager noch Mitspieler noch Gegenspieler.
    } else if (entry.declarer === player) {
      this.asDeclarer.add(won);
      this.declarerByType.get(entry.type)?.add(won);
      if (entry.announcement !== "NONE") this.toutPlayed.add(won);
    } else if (!isSolo(entry.type) && entry.partner === player) {
      this.asPartner.add(won);
    } else {
      this.asOpponent.add(won);
    }

    if (entry.klopfer.includes(player)) {
      this.klopferGiven.add(won);
      // Exakt nachgerechnet: Mit der Topf-Teilung ist es nicht immer genau die Hälfte des Betrags.
      this.klopferEffect = intAdd(this.klopferEffect, game.klopferEffect(player));
    }
    if (entry.kontra !== "NONE" && entry.kontraBy === player) this.kontraGiven.add(won);
    if (entry.kontra === "RETOUR" && entry.retourBy === player) this.retourGiven.add(won);

    // Bei Tout und Sie gibt es keinen Zuschlag für Schneider oder Schwarz, also zählt die Stufe dort nicht.
    if (entry.announcement === "NONE") {
      if (entry.level === "SCHNEIDER") {
        if (won) this.schneiderWon += 1;
        else this.schneiderLost += 1;
      } else if (entry.level === "SCHWARZ") {
        if (won) this.schwarzWon += 1;
        else this.schwarzLost += 1;
      }
    }

    const highest = this.biggestWin;
    if (amount > 0 && (highest === null || amount > highest.amount)) {
      this.biggestWin = { eveningId: game.eveningId, entryId: entry.id, gameNumber: game.gameNumber, amount };
    }
    const lowest = this.biggestLoss;
    if (amount < 0 && (lowest === null || amount < lowest.amount)) {
      this.biggestLoss = { eveningId: game.eveningId, entryId: entry.id, gameNumber: game.gameNumber, amount };
    }
  }

  build(): PlayerStats {
    return playerStats({
      player: this.player,
      evenings: this.evenings,
      games: this.games,
      balance: this.balance,
      gamesWon: this.gamesWon,
      asDeclarer: this.asDeclarer.toRecord(),
      asPartner: this.asPartner.toRecord(),
      asOpponent: this.asOpponent.toRecord(),
      declarerByType: mapValues(this.declarerByType, (tally) => tally.toRecord()),
      klopferGiven: this.klopferGiven.toRecord(),
      klopferEffect: this.klopferEffect,
      kontraGiven: this.kontraGiven.toRecord(),
      retourGiven: this.retourGiven.toRecord(),
      schneiderWon: this.schneiderWon,
      schneiderLost: this.schneiderLost,
      schwarzWon: this.schwarzWon,
      schwarzLost: this.schwarzLost,
      toutPlayed: this.toutPlayed.toRecord(),
      biggestWin: this.biggestWin,
      biggestLoss: this.biggestLoss,
      longestWinStreak: this.longestWinStreak,
      longestLoseStreak: this.longestLoseStreak,
      nixerTriggers: this.nixerTriggers,
      eveningWins: this.eveningWins,
      eveningLastPlaces: this.eveningLastPlaces,
      bestEvening: this.bestEvening,
      worstEvening: this.worstEvening,
    });
  }
}

/** Sammelt die Kennzahlen, die den ganzen Tisch betreffen, über beliebig viele Abende. */
export class TableAccumulator {
  private readonly typeCounts = new Map<GameType, number>(GAME_TYPES.map((type) => [type, 0]));
  gameCount = 0;
  bockGamesCount = 0;
  bockTriggerCount = 0;
  thrownInCount = 0;
  leadChanges = 0;
  turnover = 0;
  mostExpensiveGame: ExpensiveGame | null = null;

  get gameTypeCounts(): ReadonlyMap<GameType, number> {
    return new Map(this.typeCounts);
  }

  /** Nimmt einen Abend auf. Die Abende müssen in zeitlicher Reihenfolge kommen. */
  add(digest: EveningDigest): void {
    for (const game of digest.games) {
      this.gameCount += 1;
      this.typeCounts.set(game.entry.type, (this.typeCounts.get(game.entry.type) ?? 0) + 1);
      if (game.bockExponent > 0) this.bockGamesCount += 1;
      // Nur ein echt höherer Wert verdrängt: Bei Gleichstand bleibt das frühere Spiel.
      const top = this.mostExpensiveGame;
      if (top === null || game.value > top.value) {
        this.mostExpensiveGame = {
          eveningId: game.eveningId,
          entryId: game.entry.id,
          gameNumber: game.gameNumber,
          value: game.value,
        };
      }
    }
    this.bockTriggerCount += digest.bockTriggers.length;
    this.thrownInCount += digest.thrownInCount;
    this.leadChanges += digest.leadChanges;
    this.turnover = intAdd(this.turnover, digest.turnover);
  }
}
