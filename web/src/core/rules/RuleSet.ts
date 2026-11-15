/*
 * Regelwerk und Spielarten (Kotlin: `domain/rules/RuleSet.kt`).
 *
 * Die Daten selbst (`RuleSet`, `BockRules`, die Aufzählungen) stehen in `../model.ts`. Was in Kotlin an den
 * Aufzählungen und am Regelwerk hängt (`GameType.isSolo`, `RuleSet.price(type)` …), sind hier Funktionen, und die
 * beiden Vorlagen heißen `RuleSets.standard()` und `RuleSets.sauspiel()`.
 */
import type { GameType, KontraLevel, ResultLevel, RuleSet } from "../model";
import { GAME_TYPES } from "../model";

/** `true`: einer spielt gegen drei. `false`: zwei gegen zwei (Rufspiel, Über Kreuz). Kotlin: `GameType.isSolo`. */
export function isSolo(type: GameType): boolean {
  return type === "FARBSOLO" || type === "WENZ" || type === "GEIER";
}

/**
 * Ob jemand das Spiel angesagt hat (Kotlin: `GameType.hasDeclarer`). Bei Über Kreuz sagt niemand an: Dort stehen
 * sich einfach zwei Paare gegenüber, und „Ansager“ und „Mitspieler“ bezeichnen nur das eine der beiden Paare.
 */
export function hasDeclarer(type: GameType): boolean {
  return type !== "UEBER_KREUZ";
}

/** Anzahl der Grundeinheiten, um die der Spielwert steigt (Kotlin: `ResultLevel.steps`). */
export function levelSteps(level: ResultLevel): number {
  return level === "SCHWARZ" ? 2 : level === "SCHNEIDER" ? 1 : 0;
}

/** Faktor von Kontra (×2) und Retour (×4) (Kotlin: `KontraLevel.factor`). */
export function kontraFactor(kontra: KontraLevel): number {
  return kontra === "RETOUR" ? 4 : kontra === "KONTRA" ? 2 : 1;
}

/** Eine Tabelle je Spielart, wie sie im Regelwerk steht (darf unvollständig sein). */
type PerType = Readonly<Partial<Record<GameType, number>>>;

const STANDARD_PRICES: Readonly<Record<GameType, number>> = {
  RUFSPIEL: 10,
  UEBER_KREUZ: 10,
  FARBSOLO: 50,
  WENZ: 50,
  GEIER: 50,
};

const STANDARD_LAUFENDE_MIN: Readonly<Record<GameType, number>> = {
  RUFSPIEL: 3,
  UEBER_KREUZ: 3,
  FARBSOLO: 3,
  WENZ: 2,
  GEIER: 2,
};

const STANDARD_LAUFENDE_MAX: Readonly<Record<GameType, number>> = {
  RUFSPIEL: 8,
  UEBER_KREUZ: 8,
  FARBSOLO: 8,
  WENZ: 4,
  GEIER: 4,
};

/**
 * Liest einen Wert je Spielart. Fehlt „Über Kreuz“ in einem Regelwerk (zum Beispiel in einem älteren gespeicherten
 * Abend), gilt dafür der Wert des Rufspiels, weil es genauso abgerechnet wird; fehlt auch der, der Standardwert.
 */
function lookup(values: PerType, type: GameType, standard: Readonly<Record<GameType, number>>): number {
  return values[type] ?? (type === "UEBER_KREUZ" ? values.RUFSPIEL : undefined) ?? standard[type];
}

/** Grundwert einer Spielart (Kotlin: `RuleSet.price(type)`). */
export function price(rules: RuleSet, type: GameType): number {
  return lookup(rules.prices, type, STANDARD_PRICES);
}

/** Ab wie vielen Laufenden gezählt wird; darunter zählen sie nicht (Kotlin: `RuleSet.laufendeMin(type)`). */
export function laufendeMin(rules: RuleSet, type: GameType): number {
  return lookup(rules.laufendeMin, type, STANDARD_LAUFENDE_MIN);
}

/** Höchste eintragbare Zahl von Laufenden (Kotlin: `RuleSet.laufendeMax(type)`). */
export function laufendeMax(rules: RuleSet, type: GameType): number {
  return lookup(rules.laufendeMax, type, STANDARD_LAUFENDE_MAX);
}

/** Tout gibt es nur bei Einzelspielen (Kotlin: `RuleSet.allowsTout(type)`). */
export function allowsTout(_rules: RuleSet, type: GameType): boolean {
  return isSolo(type);
}

/** Ein Sie (alle vier Ober und alle vier Unter) gibt es nur beim Farbsolo (Kotlin: `RuleSet.allowsSie(type)`). */
export function allowsSie(_rules: RuleSet, type: GameType): boolean {
  return type === "FARBSOLO";
}

/** Die beiden Vorlagen für ein Regelwerk (Kotlin: `RuleSet.standard()` und `RuleSet.sauspiel()`). */
export const RuleSets = {
  /**
   * Das Regelwerk der Runde, so wie es der Hauptentwickler vorgegeben hat: Rufspiel und Über Kreuz 10 Cent,
   * Farbsolo, Wenz und Geier 50 Cent, je Laufendem 10 Cent (bis 8, bei Wenz und Geier bis 4), Schneider 10,
   * Schwarz weitere 10 Cent. Jeder, der gelegt hat, und ein Bockspiel verdoppeln. Dazu Kontra, Retour und
   * Tout wie auf sauspiel.de (abschaltbar) und die Bockrunde über vier Spiele.
   */
  standard(): RuleSet {
    return {
      name: "Runde 10/50 mit Bockrunde",
      unit: 10,
      prices: { ...STANDARD_PRICES },
      enabledTypes: [...GAME_TYPES],
      laufendeMin: { ...STANDARD_LAUFENDE_MIN },
      laufendeMax: { ...STANDARD_LAUFENDE_MAX },
      klopfenEnabled: true,
      kontraEnabled: true,
      retourEnabled: true,
      toutFactor: 2,
      sieFactor: 2,
      sieLaufende: 8,
      bock: defaultBockRules(),
      dealerAdvancesAfterThrowIn: true,
      displayAsMoney: true,
      payoutRule: "SCHAFKOPF",
    };
  },

  /**
   * Regeln von sauspiel.de als wählbare Vorlage: Tarif 10-20-50 (Rufspiel 20 Cent), bis zu 14 Laufende,
   * Klopfen, Kontra und Retour, Tout, Sie als Tout mit 8 Laufenden; ohne „Über Kreuz“.
   * Dazu die Bockrunde der Runde über vier Spiele.
   */
  sauspiel(): RuleSet {
    return {
      name: "Sauspiel 10-20-50 mit Bockrunde",
      unit: 10,
      prices: { RUFSPIEL: 20, FARBSOLO: 50, WENZ: 50, GEIER: 50 },
      enabledTypes: ["RUFSPIEL", "FARBSOLO", "WENZ", "GEIER"],
      laufendeMin: { RUFSPIEL: 3, FARBSOLO: 3, WENZ: 2, GEIER: 2 },
      laufendeMax: { RUFSPIEL: 14, FARBSOLO: 14, WENZ: 4, GEIER: 4 },
      klopfenEnabled: true,
      kontraEnabled: true,
      retourEnabled: true,
      toutFactor: 2,
      sieFactor: 2,
      sieLaufende: 8,
      bock: defaultBockRules(),
      dealerAdvancesAfterThrowIn: true,
      displayAsMoney: true,
      payoutRule: "SCHAFKOPF",
    };
  },
};

/** Die Bockrunde mit den Standardwerten von Kotlins `BockRules()`: vier Spiele, Faktor 2, anhängen. */
function defaultBockRules(): RuleSet["bock"] {
  return { enabled: true, gamesPerTrigger: 4, factor: 2, stacking: "APPEND", thrownInConsumesBockGame: false };
}
