/*
 * Abrechnung eines einzelnen Spiels (Kotlin: `domain/rules/ScoreCalculator.kt`).
 */
import { losers, winners } from "../evening/Evening";
import { associateWith, coerceIn, INT_MAX, INT_MIN, intMul } from "../kotlin";
import type { Announcement, GameType, KontraLevel, PlayerId, ResultLevel, RuleSet } from "../model";
import { PayoutCalculator } from "./PayoutCalculator";
import { allowsSie, allowsTout, isSolo, kontraFactor, laufendeMax, laufendeMin, levelSteps, price } from "./RuleSet";

/**
 * Alles, was für die Abrechnung eines gespielten Spiels zählt (Kotlin: `GameFacts`).
 *
 * Die abgeleiteten Eigenschaften von Kotlin liefern die Funktionen aus `../evening/Evening.ts`:
 * `declarerSide(facts)`, `winners(facts, facts.players)` und `losers(facts, facts.players)`.
 *
 * @property players Die vier Spieler am Tisch in Sitzreihenfolge.
 * @property declarer Wer das Spiel angesagt hat (der „Spieler“). Bei Über Kreuz einer aus dem einen Paar.
 * @property partner Der Mitspieler beim Rufspiel und bei Über Kreuz; bei Einzelspielen `null`.
 * @property declarerWon Ob die Spielerseite gewonnen hat (61 Augen oder mehr; bei 60:60 hat sie verloren).
 * @property laufende Eingetragene Zahl der Laufenden; unterhalb der Schwelle des Regelwerks zählen sie nicht.
 * @property klopferCount Wie viele Spieler gelegt (geklopft) haben; jeder verdoppelt.
 * @property bockExponent 0 = kein Bockspiel, 1 = Bockspiel, 2 = zwei überlagerte Bockrunden usw.
 */
export interface GameFacts {
  readonly type: GameType;
  readonly players: readonly PlayerId[];
  readonly declarer: PlayerId;
  readonly partner: PlayerId | null;
  readonly declarerWon: boolean;
  readonly level: ResultLevel;
  readonly laufende: number;
  readonly announcement: Announcement;
  readonly klopferCount: number;
  readonly kontra: KontraLevel;
  readonly bockExponent: number;
}

/**
 * Aufschlüsselung des Spielwerts, so wie man sie am Tisch vorrechnet: erst alle Zuschläge addieren, dann
 * multipliziert jeder Verdoppler die ganze Summe (Kotlin: `ValueBreakdown`).
 *
 * @property value Der Spielwert T: so viel zahlt ein Verlierer an einen Gewinner.
 * @property totalFactor Das Produkt aller Faktoren. Wie in Kotlin als `Int` gerechnet: Bei unsinnig großen Faktoren
 *   (beschädigte Daten) läuft es über; für die Abrechnung zählt nur [value].
 */
export interface ValueBreakdown {
  readonly basePrice: number;
  readonly laufendeCounted: number;
  readonly laufendeAmount: number;
  readonly levelAmount: number;
  readonly sum: number;
  readonly announcementFactor: number;
  readonly klopferFactor: number;
  readonly kontraFactor: number;
  readonly bockFactor: number;
  readonly value: number;
  readonly totalFactor: number;
}

/** Eine Aufschlüsselung samt dem abgeleiteten Feld `totalFactor` (wie der Konstruktor in Kotlin). */
export function valueBreakdown(fields: Omit<ValueBreakdown, "totalFactor">): ValueBreakdown {
  const { announcementFactor, klopferFactor, kontraFactor: kontra, bockFactor } = fields;
  return { ...fields, totalFactor: intMul(intMul(intMul(announcementFactor, klopferFactor), kontra), bockFactor) };
}

/** Warum ein Spiel so nicht eingetragen werden kann (Kotlin: `RuleViolation`). */
export type RuleViolation =
  | "WRONG_PLAYER_COUNT"
  | "DUPLICATE_PLAYERS"
  | "DECLARER_NOT_AT_TABLE"
  | "PARTNER_MISSING"
  | "PARTNER_NOT_AT_TABLE"
  | "PARTNER_IS_DECLARER"
  | "PARTNER_NOT_ALLOWED"
  | "GAME_TYPE_DISABLED"
  | "LAUFENDE_OUT_OF_RANGE"
  | "TOUT_NOT_ALLOWED"
  | "SIE_NOT_ALLOWED"
  | "SIE_CANNOT_BE_LOST"
  | "KLOPFER_OUT_OF_RANGE"
  | "KLOPFEN_DISABLED"
  | "KONTRA_DISABLED"
  | "RETOUR_DISABLED"
  | "BOCK_EXPONENT_NEGATIVE"
  /**
   * Der Spielwert ist negativ oder größer als `ScoreCalculator.MAX_VALUE`. Das kann nur aus einem beschädigten
   * Regelwerk oder Eintrag stammen; so ein Spiel wird nicht abgerechnet.
   */
  | "VALUE_OUT_OF_RANGE";

/** Alle Regelverstöße in der Reihenfolge des Kotlin-Enums. */
export const RULE_VIOLATIONS: readonly RuleViolation[] = [
  "WRONG_PLAYER_COUNT",
  "DUPLICATE_PLAYERS",
  "DECLARER_NOT_AT_TABLE",
  "PARTNER_MISSING",
  "PARTNER_NOT_AT_TABLE",
  "PARTNER_IS_DECLARER",
  "PARTNER_NOT_ALLOWED",
  "GAME_TYPE_DISABLED",
  "LAUFENDE_OUT_OF_RANGE",
  "TOUT_NOT_ALLOWED",
  "SIE_NOT_ALLOWED",
  "SIE_CANNOT_BE_LOST",
  "KLOPFER_OUT_OF_RANGE",
  "KLOPFEN_DISABLED",
  "KONTRA_DISABLED",
  "RETOUR_DISABLED",
  "BOCK_EXPONENT_NEGATIVE",
  "VALUE_OUT_OF_RANGE",
];

const PLAYERS_AT_TABLE = 4;

/**
 * Größter Spielwert T, den die App abrechnet: Auch der dreifache Betrag des Alleinspielers passt dann noch in
 * einen `Int` (`Int.MAX_VALUE / 3`).
 */
const MAX_VALUE = Math.trunc(INT_MAX / 3);

/** Eine gerade Zahl von Schritten, nach der jede Potenz den Zahlenbereich eines `Int` verlassen hat (Basis mit Betrag ab 2). */
const POWER_STEPS = 32;

/**
 * Begrenzt auf den Zahlenbereich eines `Int`, statt überzulaufen (Kotlin: `limited(value: Long)`).
 *
 * Kotlin begrenzt einen exakt gerechneten `Long`. Hier kommt eine JavaScript-Zahl herein, die oberhalb von 2^53
 * gerundet sein kann. Das ändert nichts: Wer hier über 2^53 liegt, liegt (gerundet wie exakt) mit demselben
 * Vorzeichen weit außerhalb des Int-Bereichs und landet am selben Rand. `| 0` macht aus einer „negativen Null“ 0.
 */
function limited(value: number): number {
  return value >= INT_MAX ? INT_MAX : value <= INT_MIN ? INT_MIN : value | 0;
}

/**
 * Produkt zweier Int-Zahlen; passt es nicht in einen `Int`, bleibt es am Rand des Zahlenbereichs stehen. Das Produkt
 * ist bis 2^53 exakt; darüber (bis 2^62) ist es zwar gerundet, aber sicher außerhalb des Int-Bereichs.
 */
function product(a: number, b: number): number {
  return limited(a * b);
}

/** Potenz; passt sie nicht in einen `Int`, bleibt sie am Rand des Zahlenbereichs stehen. */
function power(base: number, exponent: number): number {
  // Nach 32 Schritten ist jede Basis mit Betrag ab 2 am Rand angekommen. Weitere Schritte könnten nur noch das
  // Vorzeichen wechseln (negative Basis); deshalb genügt es, gerade oder ungerade beizubehalten.
  const steps = exponent > POWER_STEPS ? POWER_STEPS + (exponent % 2) : exponent;
  let result = 1;
  for (let step = 0; step < steps; step++) result = product(result, base);
  return result;
}

/**
 * Die Aufschlüsselung des Spielwerts. Gerechnet wird ohne Überlauf: Ergeben unsinnige Werte (beschädigte Daten)
 * mehr, als in einen `Int` passt, bleibt das Ergebnis am Rand des Zahlenbereichs stehen. Ein solcher Wert ist dann
 * erkennbar zu groß oder negativ (siehe `MAX_VALUE`) und sieht nie zufällig wieder harmlos aus.
 */
function breakdown(facts: GameFacts, rules: RuleSet): ValueBreakdown {
  const hasAnnouncement = facts.announcement !== "NONE";
  const basePrice = price(rules, facts.type);
  const laufende = facts.announcement === "SIE" ? rules.sieLaufende : facts.laufende;
  const laufendeCounted = laufende >= laufendeMin(rules, facts.type) ? laufende : 0;
  // Kotlin rechnet die Summe als Long genau und begrenzt erst dann: So können sich ein riesiger Zuschlag und ein
  // riesiger negativer Grundwert nicht scheinbar zu einem gültigen Wert aufheben. Mit JavaScript-Zahlen kommt
  // dasselbe heraus: Liegt der Zuschlag für die Laufenden unter 2^52, ist alles exakt (Beträge unter 2^53). Liegt er
  // darüber, liegt auch die Summe weit außerhalb des Int-Bereichs, ob gerundet oder nicht, und wird gleich begrenzt.
  const laufendeAmount = laufendeCounted * rules.unit;
  const levelAmount = hasAnnouncement ? 0 : levelSteps(facts.level) * rules.unit;
  const sum = limited(basePrice + laufendeAmount + levelAmount);

  const announcementFactor =
    facts.announcement === "TOUT" ? rules.toutFactor : facts.announcement === "SIE" ? rules.sieFactor : 1;
  const klopferFactor = 1 << coerceIn(facts.klopferCount, 0, PLAYERS_AT_TABLE);
  const kontra = kontraFactor(facts.kontra);
  const bockFactor = power(rules.bock.factor, Math.max(facts.bockExponent, 0));

  return valueBreakdown({
    basePrice,
    laufendeCounted,
    laufendeAmount: limited(laufendeAmount),
    levelAmount: limited(levelAmount),
    sum,
    announcementFactor,
    klopferFactor,
    kontraFactor: kontra,
    bockFactor,
    value: [announcementFactor, klopferFactor, kontra, bockFactor].reduce(product, sum),
  });
}

/** Ob sich mit dem Spielwert rechnen lässt: zwischen 0 und `MAX_VALUE`. */
function isComputable(value: number): boolean {
  return value >= 0 && value <= MAX_VALUE;
}

/**
 * Betrag je Spieler in Cent (positiv = bekommt Geld), in Sitzreihenfolge. Die Summe ist immer 0.
 *
 * Mit der Schafkopf-Regel: Rufspiel und Über Kreuz je ±T; beim Einzelspiel bekommt oder zahlt der Alleinspieler
 * 3 T, jeder Gegner T. Mit der Topf-Teilung teilen sich die drei Gegner einen Spielwert.
 *
 * Lässt sich mit dem Spielwert nicht rechnen (negativ oder größer als `MAX_VALUE`) oder lässt er sich nicht sinnvoll
 * verteilen (siehe `PayoutCalculator`), bekommen alle 0.
 */
function amounts(facts: GameFacts, rules: RuleSet): ReadonlyMap<PlayerId, number> {
  const value = breakdown(facts, rules).value;
  if (!isComputable(value)) return associateWith(facts.players, () => 0);
  const payout = PayoutCalculator.of(rules.payoutRule).payout(
    value,
    winners(facts, facts.players),
    losers(facts, facts.players),
  );
  return associateWith(facts.players, (player) => payout.get(player) ?? 0);
}

/** Prüft, ob das Spiel zum Regelwerk passt. Eine leere Liste heißt: alles in Ordnung. */
function validate(facts: GameFacts, rules: RuleSet): RuleViolation[] {
  const violations: RuleViolation[] = [];
  const { players } = facts;
  if (players.length !== PLAYERS_AT_TABLE) violations.push("WRONG_PLAYER_COUNT");
  if (new Set(players).size !== players.length) violations.push("DUPLICATE_PLAYERS");
  if (!players.includes(facts.declarer)) violations.push("DECLARER_NOT_AT_TABLE");

  if (isSolo(facts.type)) {
    if (facts.partner !== null) violations.push("PARTNER_NOT_ALLOWED");
  } else if (facts.partner === null) {
    violations.push("PARTNER_MISSING");
  } else if (facts.partner === facts.declarer) {
    violations.push("PARTNER_IS_DECLARER");
  } else if (!players.includes(facts.partner)) {
    violations.push("PARTNER_NOT_AT_TABLE");
  }

  if (!rules.enabledTypes.includes(facts.type)) violations.push("GAME_TYPE_DISABLED");
  if (facts.laufende < 0 || facts.laufende > laufendeMax(rules, facts.type)) violations.push("LAUFENDE_OUT_OF_RANGE");

  if (facts.announcement === "TOUT") {
    if (!allowsTout(rules, facts.type)) violations.push("TOUT_NOT_ALLOWED");
  } else if (facts.announcement === "SIE") {
    if (!allowsSie(rules, facts.type)) violations.push("SIE_NOT_ALLOWED");
    if (!facts.declarerWon) violations.push("SIE_CANNOT_BE_LOST");
  }

  if (facts.klopferCount < 0 || facts.klopferCount > PLAYERS_AT_TABLE) violations.push("KLOPFER_OUT_OF_RANGE");
  if (facts.klopferCount > 0 && !rules.klopfenEnabled) violations.push("KLOPFEN_DISABLED");
  if (facts.kontra !== "NONE" && !rules.kontraEnabled) violations.push("KONTRA_DISABLED");
  if (facts.kontra === "RETOUR" && !rules.retourEnabled) violations.push("RETOUR_DISABLED");
  if (facts.bockExponent < 0) violations.push("BOCK_EXPONENT_NEGATIVE");
  if (!isComputable(breakdown(facts, rules).value)) violations.push("VALUE_OUT_OF_RANGE");
  return violations;
}

/**
 * Rechnet ein einzelnes Spiel ab. Reine Funktionen ohne Zustand.
 *
 * Formel: Spielwert T = (Grundwert + Laufende × Einheit + Schneider + Schwarz) × Bock × 2^(Anzahl gelegt)
 * × Kontra × Tout. Schneider zählt eine Einheit, Schwarz zwei (Schwarz schließt Schneider ein); bei Tout und Sie
 * entfallen beide.
 *
 * Wie T auf die Spieler verteilt wird, bestimmt der `PayoutCalculator` des Regelwerks.
 */
export const ScoreCalculator = {
  PLAYERS_AT_TABLE,
  /**
   * Größter Spielwert T, den die App abrechnet (715 827 882 Cent): Auch der dreifache Betrag des Alleinspielers
   * passt dann noch in einen `Int`. Ein größerer oder ein negativer Wert kann nur aus beschädigten Daten stammen;
   * `validate` meldet ihn als `VALUE_OUT_OF_RANGE`, und `amounts` verteilt dann nichts.
   */
  MAX_VALUE,
  breakdown,
  amounts,
  validate,
} as const;
