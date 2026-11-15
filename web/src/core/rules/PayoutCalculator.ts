/*
 * Verteilung des Spielwerts auf die Spieler (Kotlin: `domain/rules/PayoutCalculator.kt`).
 */
import { INT_MAX, intMul } from "../kotlin";
import type { PayoutRule, PlayerId } from "../model";

/**
 * Verteilt den Spielwert T eines Spiels auf die Spieler. Jede Umsetzung muss ein Nullsummenspiel liefern:
 * Die Beträge aller Spieler summieren sich zu 0 – auch bei unsinnigen Eingaben (beschädigte Daten). Lässt sich
 * nichts sinnvoll verteilen, bekommen alle 0.
 */
export interface PayoutCalculator {
  /**
   * @param value Der Spielwert T in Cent.
   * @param winners Die Gewinner (2 beim Rufspiel und bei Über Kreuz; 1 oder 3 beim Einzelspiel).
   * @param losers Die Verlierer, in Sitzreihenfolge.
   * @returns Betrag je Spieler in Cent, positiv für Gewinner.
   */
  payout(value: number, winners: readonly PlayerId[], losers: readonly PlayerId[]): ReadonlyMap<PlayerId, number>;
}

/**
 * Die übliche Schafkopf-Regel.
 *
 * Zwei gegen zwei: Jeder Verlierer zahlt T, jeder Gewinner bekommt T.
 * Einer gegen drei: Jeder der drei zahlt bzw. bekommt T, der Einzelne bekommt bzw. zahlt 3 T.
 */
export const SchafkopfPayout: PayoutCalculator = {
  payout(value, winners, losers) {
    // Die größere Seite rechnet pro Kopf mit T; die kleinere Seite teilt sich deren Summe.
    return distribute(value, winners, losers, winners.length > losers.length);
  },
};

/**
 * Topf-Teilung: Im Einzelspiel bekommt oder zahlt der Solist nur T; die drei Gegner teilen sich diesen Betrag.
 * Geht T nicht durch drei, bekommen bzw. zahlen die in der Sitzreihenfolge vorderen Gegner je einen Cent mehr,
 * damit kein Cent verloren geht. Zwei gegen zwei wird wie bei der Schafkopf-Regel abgerechnet.
 */
export const PotSplitPayout: PayoutCalculator = {
  payout(value, winners, losers) {
    // Die kleinere Seite rechnet pro Kopf mit T; die größere Seite teilt sich deren Summe.
    return distribute(value, winners, losers, winners.length < losers.length);
  },
};

/** Die Auszahlungsregel eines Regelwerks (Kotlin: `PayoutCalculator.of(rule)`). */
export const PayoutCalculator = {
  of(rule: PayoutRule): PayoutCalculator {
    return rule === "POT_SPLIT" ? PotSplitPayout : SchafkopfPayout;
  },
};

/**
 * Gemeinsamer Kern beider Regeln: Die eine Seite rechnet pro Kopf mit T ab, die andere teilt sich deren Summe.
 * Geht die Summe nicht auf, wird der Rest vorn beginnend centweise verteilt. So ist das Ergebnis immer ein
 * Nullsummenspiel; bei zwei gegen zwei und einem gegen drei mit der Schafkopf-Regel bleibt nie ein Rest.
 *
 * Wo sich nichts sinnvoll verteilen lässt, bekommen alle 0: ohne Gegenseite, wenn ein Spieler doppelt genannt ist,
 * bei negativem Spielwert und wenn die Summe nicht mehr in einen `Int` passt.
 *
 * @param winnersPerHead `true`: Jeder Gewinner bekommt T, die Verlierer teilen sich die Summe.
 *   `false`: Jeder Verlierer zahlt T, die Gewinner teilen sich die Summe.
 */
function distribute(
  value: number,
  winners: readonly PlayerId[],
  losers: readonly PlayerId[],
  winnersPerHead: boolean,
): Map<PlayerId, number> {
  const nothing = new Map<PlayerId, number>();
  for (const player of [...winners, ...losers]) nothing.set(player, 0);
  const perHead = winnersPerHead ? winners : losers;
  const sharing = winnersPerHead ? losers : winners;
  // In Kotlin als Long gerechnet. Hier genügt eine JavaScript-Zahl: Bis 2^53 ist das Produkt exakt, und darüber
  // liegt es (exakt wie gerundet) weit außerhalb des Int-Bereichs, sodass die Prüfung unten gleich ausgeht.
  const total = value * perHead.length;
  const everyoneOnce = nothing.size === winners.length + losers.length;
  if (winners.length === 0 || losers.length === 0 || !everyoneOnce || !(total >= 0 && total <= INT_MAX)) {
    return nothing;
  }

  // Gewinner bekommen (+), Verlierer zahlen (−).
  const perHeadSign = winnersPerHead ? 1 : -1;
  const shares = splitEvenly(total, sharing.length);
  const result = new Map<PlayerId, number>();
  for (const player of perHead) result.set(player, intMul(perHeadSign, value));
  sharing.forEach((player, index) => result.set(player, intMul(-perHeadSign, shares[index])));
  return result;
}

/** Teilt `total` Cent (nie negativ) auf `parts` Teile auf; ein Rest wird vorn beginnend centweise verteilt. */
function splitEvenly(total: number, parts: number): number[] {
  // Ganzzahl-Division wie in Kotlin; für Werte im Int-Bereich ist der gerundete Quotient nie über eine ganze Zahl
  // gerutscht, `Math.trunc` liefert also genau den ganzzahligen Teil.
  const base = Math.trunc(total / parts);
  const remainder = total % parts;
  return Array.from({ length: parts }, (_, index) => (index < remainder ? base + 1 : base));
}
