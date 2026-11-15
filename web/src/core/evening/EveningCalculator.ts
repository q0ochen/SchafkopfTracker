/*
 * Der ganze Abend durchgerechnet (Kotlin: `domain/evening/EveningCalculator.kt`).
 */
import { associateWith, INT_MAX, inIntRange, mapValues } from "../kotlin";
import type { BockRules, Evening, EveningEntry, GameEntry, PlayerId } from "../model";
import type { GameFacts, RuleViolation, ValueBreakdown } from "../rules/ScoreCalculator";
import { ScoreCalculator } from "../rules/ScoreCalculator";

/** Was an einem Eintrag nicht stimmt. Die App zeigt das an, rechnet aber trotzdem weiter (Kotlin: `EntryIssue`). */
export type EntryIssue =
  /** Das Spiel passt nicht zum Regelwerk (siehe `ScoreCalculator.validate`). */
  | "RULE_VIOLATION"
  /** Die Beträge einer freien Buchung ergeben zusammen nicht 0. */
  | "AMOUNTS_NOT_ZERO_SUM"
  /** In einer freien Buchung oder unter denen, die gelegt haben, steht jemand, der nicht am Tisch sitzt. */
  | "UNKNOWN_PLAYER"
  /** Mit diesem Eintrag passte ein Saldo nicht mehr in den Zahlenbereich; er wurde deshalb nicht gebucht. */
  | "BALANCE_OUT_OF_RANGE";

/** Alle Arten von Fehlern an einem Eintrag in der Reihenfolge des Kotlin-Enums. */
export const ENTRY_ISSUES: readonly EntryIssue[] = [
  "RULE_VIOLATION",
  "AMOUNTS_NOT_ZERO_SUM",
  "UNKNOWN_PLAYER",
  "BALANCE_OUT_OF_RANGE",
];

/**
 * Ergebnis eines Eintrags im Verlauf des Abends (Kotlin: `EntryResult`).
 *
 * @property gameNumber Laufende Nummer; nur gespielte Spiele werden gezählt (sonst `null`).
 * @property dealer Wer bei diesem Eintrag gegeben hat.
 * @property bockExponent 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden.
 * @property breakdown Aufschlüsselung des Spielwerts; `null` bei allem, was kein Spiel ist, und bei einem Spiel, das
 *   sich nicht abrechnen ließ und deshalb nichts gebucht hat.
 * @property amounts Betrag je Spieler aus diesem Eintrag in Cent, in Sitzreihenfolge. Die Summe ist 0; nur eine freie
 *   Buchung, die als fehlerhaft markiert ist, kann davon abweichen.
 * @property balances Stand je Spieler nach diesem Eintrag.
 * @property ranks Platz je Spieler nach diesem Eintrag; bei Gleichstand teilen sich Spieler einen Platz.
 */
export interface EntryResult {
  readonly entry: EveningEntry;
  readonly gameNumber: number | null;
  readonly dealer: PlayerId;
  readonly bockExponent: number;
  readonly breakdown: ValueBreakdown | null;
  readonly amounts: ReadonlyMap<PlayerId, number>;
  readonly balances: ReadonlyMap<PlayerId, number>;
  readonly ranks: ReadonlyMap<PlayerId, number>;
  readonly issues: readonly EntryIssue[];
}

/**
 * Der vollständig durchgerechnete Abend (Kotlin: `EveningState`).
 *
 * @property nextDealer Wer das nächste Spiel gibt. Ohne Sitzordnung (beschädigte Daten) der eingetragene erste Geber.
 * @property nextBockExponent Bock-Stufe des nächsten Spiels (0 = kein Bockspiel).
 * @property bockGamesRemaining Wie viele der kommenden Spiele noch Bockspiele sind.
 */
export interface EveningState {
  readonly evening: Evening;
  readonly results: readonly EntryResult[];
  readonly balances: ReadonlyMap<PlayerId, number>;
  readonly ranks: ReadonlyMap<PlayerId, number>;
  readonly nextDealer: PlayerId;
  readonly nextBockExponent: number;
  readonly bockGamesRemaining: number;
  readonly gamesPlayed: number;
}

/**
 * Regelverstöße, bei denen sich ein Spiel nicht abrechnen lässt. Alle anderen werden nur angezeigt; das gilt auch
 * für eine falsche Zahl von Spielern am Tisch (bei dreien zahlt oder bekommt der Einzelne beide Anteile).
 */
const NOT_COMPUTABLE: ReadonlySet<RuleViolation> = new Set<RuleViolation>([
  "DUPLICATE_PLAYERS",
  "DECLARER_NOT_AT_TABLE",
  "PARTNER_MISSING",
  "PARTNER_NOT_AT_TABLE",
  "PARTNER_IS_DECLARER",
  "VALUE_OUT_OF_RANGE",
]);

/** Plätze aus Salden: Platz = 1 + Anzahl der Spieler mit höherem Saldo (Gleichstand ergibt denselben Platz). */
export const Ranking = {
  ranks(balances: ReadonlyMap<PlayerId, number>): ReadonlyMap<PlayerId, number> {
    const values = [...balances.values()];
    return mapValues(balances, (balance) => 1 + values.filter((it) => it > balance).length);
  },
} as const;

/**
 * Zähler der offenen Bockspiele. Je nach Regel werden neue Bockrunden angehängt, überlagert oder ignoriert
 * (Kotlin: `BockQueue`, nur innerhalb des Kerns verwendet).
 */
export class BockQueue {
  private readonly rules: BockRules;

  /** Verbleibende Spiele je aktiver Bockrunde. Bei „anhängen“ und „ignorieren“ gibt es höchstens eine. */
  private rounds: number[] = [];

  constructor(rules: BockRules) {
    this.rules = rules;
  }

  /** Bock-Stufe des nächsten Spiels. */
  get exponent(): number {
    return this.rounds.length;
  }

  /** Anzahl der kommenden Spiele, die noch Bockspiele sind. */
  get remaining(): number {
    let max = 0;
    for (const games of this.rounds) if (games > max) max = games;
    return max;
  }

  trigger(): void {
    const { enabled, gamesPerTrigger, stacking } = this.rules;
    if (!enabled || gamesPerTrigger <= 0) return;
    switch (stacking) {
      case "APPEND":
        if (this.rounds.length === 0) {
          this.rounds.push(gamesPerTrigger);
        } else {
          // In Kotlin als Long addiert und begrenzt: Eine unsinnig lange Bockrunde darf nicht ins Negative
          // überlaufen. Die Summe zweier Int-Werte ist als JavaScript-Zahl exakt.
          this.rounds[0] = Math.min(this.rounds[0] + gamesPerTrigger, INT_MAX);
        }
        break;
      case "STACK":
        this.rounds.push(gamesPerTrigger);
        break;
      case "IGNORE":
        if (this.rounds.length === 0) this.rounds.push(gamesPerTrigger);
        break;
    }
  }

  /** Ein Bockspiel ist gespielt: Jede aktive Bockrunde wird um ein Spiel kürzer. */
  consume(): void {
    this.rounds = this.rounds.map((games) => games - 1).filter((games) => games > 0);
  }
}

/**
 * Wer an dieser Stelle der Sitzordnung gibt. Ohne Sitzordnung (beschädigte Daten) gibt es keine Geberfolge; dann
 * bleibt es beim eingetragenen ersten Geber, damit die Rechnung nicht abstürzt.
 */
function dealerAt(evening: Evening, index: number): PlayerId {
  return index >= 0 && index < evening.seating.length ? evening.seating[index] : evening.firstDealer;
}

/** Die abrechnungsrelevanten Tatsachen eines Spiels (Kotlin: `GameEntry.toFacts`). */
function toFacts(entry: GameEntry, seating: readonly PlayerId[], bockExponent: number): GameFacts {
  return {
    type: entry.type,
    players: seating,
    declarer: entry.declarer,
    partner: entry.partner,
    declarerWon: entry.declarerWon,
    level: entry.level,
    laufende: entry.laufende,
    announcement: entry.announcement,
    // `klopfer` ist in Kotlin eine Menge; doppelte Nennungen zählen deshalb nur einmal.
    klopferCount: new Set(entry.klopfer).size,
    kontra: entry.kontra,
    bockExponent,
  };
}

/**
 * Summe der Beträge einer freien Buchung, exakt wie in Kotlin als `Long` gerechnet: Riesige Beträge dürfen sich nicht
 * durch einen Überlauf scheinbar zu 0 ergänzen. Als JavaScript-Zahl ist die Summe von Int-Werten exakt, solange es
 * weniger als 2^21 Summanden sind (jede Zwischensumme bleibt unter 2^53).
 */
function exactSum(values: Iterable<number>): number {
  let sum = 0;
  for (const value of values) sum += value;
  return sum;
}

function compute(evening: Evening): EveningState {
  const { seating, rules } = evening;
  const balances = associateWith(seating, () => 0);
  const bock = new BockQueue(rules.bock);
  // Sitzt der eingetragene erste Geber nicht am Tisch, beginnt der erste Platz.
  let dealerIndex = Math.max(seating.indexOf(evening.firstDealer), 0);
  let gamesPlayed = 0;
  const results: EntryResult[] = [];

  for (const entry of evening.entries) {
    const dealer = dealerAt(evening, dealerIndex);
    let gameNumber: number | null = null;
    let bockExponent = 0;
    let breakdown: ValueBreakdown | null = null;
    let amounts: ReadonlyMap<PlayerId, number> = associateWith(seating, () => 0);
    const issues: EntryIssue[] = [];
    let dealerAdvances = false;

    switch (entry.kind) {
      case "game": {
        if (entry.bockOverride === "AUTO") {
          bockExponent = bock.exponent;
          bock.consume();
        } else if (entry.bockOverride === "FORCE_ON") {
          bockExponent = Math.max(1, bock.exponent);
          bock.consume();
        }
        const facts = toFacts(entry, seating, bockExponent);
        const violations = ScoreCalculator.validate(facts, rules);
        if (violations.length > 0) issues.push("RULE_VIOLATION");
        // Wer gelegt hat, verdoppelt den Betrag: Ein Unbekannter darunter wird deshalb gemeldet.
        if (entry.klopfer.some((player) => !seating.includes(player))) issues.push("UNKNOWN_PLAYER");
        // Abgerechnet wird nur, wenn feststeht, wer auf welcher Seite stand, wenn niemand doppelt am Tisch sitzt und
        // wenn sich mit dem Spielwert rechnen lässt.
        if (!violations.some((violation) => NOT_COMPUTABLE.has(violation))) {
          breakdown = ScoreCalculator.breakdown(facts, rules);
          amounts = ScoreCalculator.amounts(facts, rules);
        }
        gamesPlayed += 1;
        gameNumber = gamesPlayed;
        dealerAdvances = true;
        break;
      }

      case "thrown_in":
        if (rules.bock.thrownInConsumesBockGame) bock.consume();
        dealerAdvances = rules.dealerAdvancesAfterThrowIn;
        break;

      case "bock":
        bock.trigger();
        dealerAdvances = entry.handThrownIn && rules.dealerAdvancesAfterThrowIn;
        break;

      case "manual": {
        if ([...entry.amounts.keys()].some((player) => !seating.includes(player))) issues.push("UNKNOWN_PLAYER");
        if (exactSum(entry.amounts.values()) !== 0) issues.push("AMOUNTS_NOT_ZERO_SUM");
        const booked = entry.amounts;
        // `| 0` macht aus einer „negativen Null“ (etwa aus −0 in der Eingabe) eine 0, wie sie ein Kotlin-Int hätte.
        amounts = associateWith(seating, (player) => (booked.get(player) ?? 0) | 0);
        break;
      }
    }

    // Passt ein Saldo danach nicht mehr in einen Int, wird der Eintrag gemeldet und nicht gebucht, statt mit einem
    // übergelaufenen Saldo weiterzurechnen. (Die Summe zweier Int-Werte ist als JavaScript-Zahl exakt.)
    if ([...amounts].some(([player, amount]) => !inIntRange((balances.get(player) ?? 0) + amount))) {
      issues.push("BALANCE_OUT_OF_RANGE");
      breakdown = null;
      amounts = associateWith(seating, () => 0);
    }
    for (const [player, amount] of amounts) balances.set(player, ((balances.get(player) ?? 0) + amount) | 0);
    const balancesAfter = new Map(balances);
    results.push({
      entry,
      gameNumber,
      dealer,
      bockExponent,
      breakdown,
      amounts,
      balances: balancesAfter,
      ranks: Ranking.ranks(balancesAfter),
      issues,
    });
    if (dealerAdvances && seating.length > 0) dealerIndex = (dealerIndex + 1) % seating.length;
  }

  const finalBalances = new Map(balances);
  return {
    evening,
    results,
    balances: finalBalances,
    ranks: Ranking.ranks(finalBalances),
    nextDealer: dealerAt(evening, dealerIndex),
    nextBockExponent: bock.exponent,
    bockGamesRemaining: bock.remaining,
    gamesPlayed,
  };
}

/**
 * Leitet aus der Liste der Einträge alles Übrige ab: Beträge, laufende Salden, Plätze, Geberfolge und Bock-Zähler.
 *
 * Nichts davon wird gespeichert. Wird ein früherer Eintrag geändert oder gelöscht, stimmt danach wieder alles, weil
 * der ganze Abend neu durchgerechnet wird.
 *
 * Auch mit beschädigten Daten (leere oder falsche Sitzordnung, unbekannte Spieler, unsinnige Werte im Regelwerk)
 * wirft `compute` nie. Ein Spiel bucht dann entweder ein Nullsummenspiel oder gar nichts; was nichts bucht, ist als
 * fehlerhaft markiert (`EntryResult.issues`).
 */
export const EveningCalculator = {
  compute,
  toFacts,
} as const;
