/*
 * Hilfen zum Abend und zu seinen Einträgen (Kotlin: `domain/evening/Evening.kt`). Die Daten selbst stehen in
 * `../model.ts`; was in Kotlin Methode oder abgeleitete Eigenschaft ist, ist hier eine Funktion.
 *
 * Gespeichert werden nur Tatsachen; Beträge, Salden, Geber und Bock-Status berechnet `EveningCalculator` daraus jedes
 * Mal neu. Alle Funktionen hier verändern nichts, sondern liefern neue Objekte.
 */
import type { EntryId, Evening, EveningEntry, GameEntry, PlayerId } from "../model";
import { isSolo } from "../rules/RuleSet";

/**
 * Was es braucht, um die beiden Seiten eines Spiels zu bestimmen. Das hat ein Spiel (`GameEntry`) ebenso wie seine
 * Tatsachen für die Abrechnung (`GameFacts`).
 */
export type GameSides = Pick<GameEntry, "type" | "declarer" | "partner" | "declarerWon">;

/**
 * Die Spielerseite: der Ansager und – außer beim Einzelspiel – sein Mitspieler (Kotlin: `GameEntry.declarerSide`
 * und `GameFacts.declarerSide`). Eine Menge in der Reihenfolge Ansager, Mitspieler.
 */
export function declarerSide(game: Pick<GameSides, "type" | "declarer" | "partner">): ReadonlySet<PlayerId> {
  const side = new Set<PlayerId>([game.declarer]);
  if (game.partner !== null && !isSolo(game.type)) side.add(game.partner);
  return side;
}

/**
 * Die Gewinner eines Spiels in Sitzreihenfolge: zwei beim Rufspiel und bei Über Kreuz, beim Einzelspiel der
 * Alleinspieler oder seine drei Gegner (Kotlin: `GameEntry.winners(seating)`; für `GameFacts` ist es
 * `winners(facts, facts.players)`, in Kotlin `GameFacts.winners`).
 */
export function winners(game: GameSides, seating: readonly PlayerId[]): PlayerId[] {
  const side = declarerSide(game);
  return seating.filter((player) => side.has(player) === game.declarerWon);
}

/** Die Verlierer eines Spiels in Sitzreihenfolge (Kotlin: `GameEntry.losers(seating)` bzw. `GameFacts.losers`). */
export function losers(game: GameSides, seating: readonly PlayerId[]): PlayerId[] {
  const side = declarerSide(game);
  return seating.filter((player) => side.has(player) !== game.declarerWon);
}

/** Ob der Abend noch läuft (Kotlin: `Evening.isRunning`). */
export function isRunning(evening: Evening): boolean {
  return evening.finishedAt == null;
}

/** Hängt einen Eintrag ans Ende an (Kotlin: `Evening.append`). */
export function append(evening: Evening, entry: EveningEntry): Evening {
  return { ...evening, entries: [...evening.entries, entry] };
}

/** Ersetzt den Eintrag mit derselben Kennung; die Position in der Liste bleibt (Kotlin: `Evening.replace`). */
export function replace(evening: Evening, entry: EveningEntry): Evening {
  return { ...evening, entries: evening.entries.map((it) => (it.id === entry.id ? entry : it)) };
}

/** Entfernt den Eintrag mit dieser Kennung (Kotlin: `Evening.remove`). */
export function remove(evening: Evening, entryId: EntryId): Evening {
  return { ...evening, entries: evening.entries.filter((it) => it.id !== entryId) };
}

/**
 * Fügt einen Eintrag an einer bestimmten Stelle ein, für „Rückgängig“ nach dem Löschen (Kotlin: `Evening.insertAt`).
 * Eine Stelle außerhalb der Liste wird auf Anfang bzw. Ende begrenzt.
 */
export function insertAt(evening: Evening, index: number, entry: EveningEntry): Evening {
  const position = Math.min(Math.max(index, 0), evening.entries.length);
  return {
    ...evening,
    entries: [...evening.entries.slice(0, position), entry, ...evening.entries.slice(position)],
  };
}

/**
 * Ob der Spieler in diesem Abend vorkommt: am Tisch, als Geber oder in irgendeinem Eintrag, auch in beschädigten
 * Daten, in denen ein Eintrag jemanden nennt, der nicht am Tisch sitzt (Kotlin: `Evening.involves`). Solche Spieler
 * dürfen nicht gelöscht werden, sonst verlöre der Abend seine Namen und ließe sich nicht mehr sichern und einlesen.
 */
export function involves(evening: Evening, player: PlayerId): boolean {
  if (evening.seating.includes(player) || evening.firstDealer === player) return true;
  return evening.entries.some((entry) => {
    switch (entry.kind) {
      case "game":
        return (
          entry.declarer === player ||
          entry.partner === player ||
          entry.klopfer.includes(player) ||
          entry.kontraBy === player ||
          entry.retourBy === player
        );
      case "bock":
        return entry.player === player;
      case "manual":
        return entry.amounts.has(player);
      case "thrown_in":
        return false;
    }
  });
}
