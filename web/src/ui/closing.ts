/* Abschluss eines Abends: Endstand, „Wer zahlt wem“ und der Text zum Teilen. */
import type { EveningState } from "../core/evening/EveningCalculator";
import { SettleUp, type Transfer } from "../core/evening/SettleUp";
import type { PlayerId } from "../core/model";
import { formatAmount, formatDate, plural } from "./format";

export interface FinalStanding {
  readonly player: PlayerId;
  readonly name: string;
  readonly balance: number;
  readonly rank: number;
}

export interface Closing {
  readonly standings: readonly FinalStanding[];
  readonly transfers: readonly Transfer[];
  /** `false` nur bei fehlerhaften freien Buchungen: Dann bleibt beim Ausgleich ein Rest offen. */
  readonly balanced: boolean;
}

export function closingOf(state: EveningState, names: ReadonlyMap<PlayerId, string>): Closing {
  const standings = state.evening.seating
    .map((player) => ({
      player,
      name: names.get(player) ?? "?",
      balance: state.balances.get(player) ?? 0,
      rank: state.ranks.get(player) ?? 0,
    }))
    .sort((a, b) => a.rank - b.rank);
  return {
    standings,
    transfers: SettleUp.transfers(state.balances),
    balanced: SettleUp.isBalanced(state.balances),
  };
}

/** Der Abschluss als schlichter Text, zum Beispiel für eine Nachricht an die Runde. */
export function closingText(state: EveningState, closing: Closing, names: ReadonlyMap<PlayerId, string>): string {
  const evening = state.evening;
  const asMoney = evening.rules.displayAsMoney;
  const date = formatDate(evening.startedAt);
  const lines: string[] = [];
  lines.push(`Schafkopf-Abend vom ${date}${evening.location.trim() !== "" ? ` · ${evening.location.trim()}` : ""}`);
  lines.push(plural(state.gamesPlayed, "Spiel", "Spiele"));
  lines.push("");
  for (const standing of closing.standings) {
    lines.push(`${standing.rank}. ${standing.name} ${formatAmount(standing.balance, asMoney, true)}`);
  }
  if (closing.transfers.length > 0) {
    lines.push("");
    lines.push("Wer zahlt wem:");
    for (const transfer of closing.transfers) {
      lines.push(`${names.get(transfer.from) ?? "?"} → ${names.get(transfer.to) ?? "?"}: ${formatAmount(transfer.amount, asMoney)}`);
    }
  }
  return lines.join("\n");
}
