/*
 * Überschrift und Einzelheiten eines Eintrags in der Liste eines Abends – wie in der Android-App
 * (`ui/board/EntryTexts.kt`).
 */
import type { EntryResult } from "../core/evening/EveningCalculator";
import type { PlayerId } from "../core/model";
import { hasDeclarer } from "../core/rules/RuleSet";
import { formatAmount, GAME_TYPE_LABELS, LEVEL_LABELS, SUIT_LABELS } from "./format";

export interface EntryText {
  readonly title: string;
  readonly detail: string;
}

/** Baut die Texte eines Eintrags aus den Tatsachen und den Spielernamen. */
export function entryText(result: EntryResult, names: ReadonlyMap<PlayerId, string>, asMoney: boolean): EntryText {
  const name = (player: PlayerId | null): string => (player === null ? "?" : (names.get(player) ?? "?"));
  const entry = result.entry;
  switch (entry.kind) {
    case "thrown_in":
      return { title: "Zusammengeworfen", detail: "" };
    case "bock":
      return {
        title: entry.player !== null ? `Bockrunde: 6 Nixer bei ${name(entry.player)}` : "Bockrunde ausgelöst",
        detail: entry.handThrownIn ? "Zusammengeworfen" : "",
      };
    case "manual":
      return { title: "Freie Buchung", detail: entry.note };
    case "game": {
      let who: string;
      if (entry.partner === null) who = name(entry.declarer);
      // Über Kreuz kennt keinen Ansager: Beide stehen gleichberechtigt nebeneinander.
      else if (!hasDeclarer(entry.type)) who = `${name(entry.declarer)} und ${name(entry.partner)}`;
      else who = `${name(entry.declarer)} mit ${name(entry.partner)}`;
      const type = GAME_TYPE_LABELS[entry.type] + (entry.suit !== null ? ` (${SUIT_LABELS[entry.suit]})` : "");
      const details: string[] = [entry.declarerWon ? "gewonnen" : "verloren"];
      if (entry.announcement === "NONE") {
        if (entry.level !== "NORMAL") details.push(LEVEL_LABELS[entry.level]);
      } else {
        details.push(entry.announcement === "TOUT" ? "Tout" : "Sie");
      }
      const counted = result.breakdown?.laufendeCounted ?? 0;
      if (counted > 0) details.push(`${counted} ${counted === 1 ? "Laufender" : "Laufende"}`);
      if (entry.klopfer.length > 0) details.push(`${entry.klopfer.length}× gelegt`);
      if (entry.kontra !== "NONE") details.push(entry.kontra === "KONTRA" ? "Kontra" : "Retour");
      if (result.bockExponent === 1) details.push("Bock");
      else if (result.bockExponent > 1) details.push(`Bock ×${2 ** result.bockExponent}`);
      if (result.breakdown) details.push(`Wert ${formatAmount(result.breakdown.value, asMoney)}`);
      return { title: `${result.gameNumber ?? 0} · ${type} · ${who}`, detail: details.join(" · ") };
    }
  }
}
