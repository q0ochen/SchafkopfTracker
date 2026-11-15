/*
 * „Wer zahlt wem“ am Ende des Abends (Kotlin: `domain/evening/SettleUp.kt`).
 */
import type { PlayerId } from "../model";

/** Eine Ausgleichszahlung am Ende des Abends (Kotlin: `Transfer`). */
export interface Transfer {
  readonly from: PlayerId;
  readonly to: PlayerId;
  readonly amount: number;
}

/**
 * Gleicht die Endstände mit möglichst wenigen Zahlungen aus.
 *
 * Verfahren: Der größte Schuldner zahlt an den größten Gläubiger, bis alle bei 0 sind. Bei Gleichstand ist es der
 * weiter vorn Stehende. Das ergibt höchstens (Spielerzahl − 1) Zahlungen; bei vier Spielern ist das zugleich das
 * Minimum.
 */
export const SettleUp = {
  /**
   * Ob sich die Salden zu 0 summieren. Nur eine fehlerhafte freie Buchung kann das verhindern. Kotlin summiert als
   * `Long`, damit riesige Salden nicht durch einen Überlauf scheinbar aufgehen; die Summe von Int-Werten ist als
   * JavaScript-Zahl ebenso exakt (bei weniger als 2^21 Spielern bleibt jede Zwischensumme unter 2^53).
   */
  isBalanced(balances: ReadonlyMap<PlayerId, number>): boolean {
    let sum = 0;
    for (const balance of balances.values()) sum += balance;
    return sum === 0;
  },

  /**
   * Die Zahlungen, mit denen alle auf 0 kommen. Gehen die Salden nicht auf (siehe `isBalanced`), wird so weit wie
   * möglich ausgeglichen; der Rest bleibt offen. Wirft nie.
   *
   * Jede Zahlung bringt mindestens einen Spieler auf 0; deshalb endet das Verfahren immer. Die größte denkbare Schuld
   * (2 147 483 648) passt in Kotlin nicht in einen `Int` und wird dort als `Long` gerechnet; als JavaScript-Zahl ist
   * sie exakt, und gezahlt wird nie mehr, als der Gläubiger bekommt (höchstens 2 147 483 647).
   */
  transfers(balances: ReadonlyMap<PlayerId, number>): Transfer[] {
    const open = new Map(balances);
    const transfers: Transfer[] = [];
    for (;;) {
      let debtor: [PlayerId, number] | null = null;
      let creditor: [PlayerId, number] | null = null;
      for (const [player, balance] of open) {
        // Wie Kotlins `minByOrNull` und `maxByOrNull`: bei Gleichstand der erste.
        if (balance < 0 && (debtor === null || balance < debtor[1])) debtor = [player, balance];
        if (balance > 0 && (creditor === null || balance > creditor[1])) creditor = [player, balance];
      }
      if (debtor === null || creditor === null) break;
      const amount = Math.min(-debtor[1], creditor[1]);
      transfers.push({ from: debtor[0], to: creditor[0], amount });
      open.set(debtor[0], debtor[1] + amount);
      open.set(creditor[0], creditor[1] - amount);
    }
    return transfers;
  },
} as const;
