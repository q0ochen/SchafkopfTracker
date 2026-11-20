/*
 * Findet Spiele, die vermutlich zweimal eingetragen wurden, weil zwei am Tisch gleichzeitig getippt haben. Die App
 * weist nur darauf hin; gelöscht wird nichts von selbst. Port von `domain/round/DuplicateCheck.kt`.
 */
import { instantParts } from "../instant";
import type { EntryId, Evening, GameEntry, InstantText } from "../model";
import { sameEntry } from "./equality";

/** So weit dürfen die beiden Einträge höchstens auseinanderliegen (Kotlin: `DuplicateCheck.WINDOW`). */
const WINDOW_SECONDS = 120;
const NANOS_PER_SECOND = 1_000_000_000n;

export const DuplicateCheck = {
  WINDOW_SECONDS,

  /**
   * Die Kennungen der Spiele, die genauso direkt davor schon einmal stehen: gleich bis auf Kennung, Zeitpunkt und
   * Notiz und höchstens 120 Sekunden nach oder vor dem anderen aufgeschrieben. Gemeldet wird jeweils das hintere, in
   * der Reihenfolge der Liste.
   */
  suspects(evening: Evening): Set<EntryId> {
    const result = new Set<EntryId>();
    for (let index = 1; index < evening.entries.length; index++) {
      const first = evening.entries[index - 1];
      const second = evening.entries[index];
      if (first.kind === "game" && second.kind === "game" && sameGame(first, second)) result.add(second.id);
    }
    return result;
  },
};

function sameGame(first: GameEntry, second: GameEntry): boolean {
  return (
    sameEntry({ ...first, id: second.id, recordedAt: second.recordedAt, note: second.note }, second) &&
    withinWindow(first.recordedAt, second.recordedAt)
  );
}

/** Ob zwei Zeitpunkte höchstens 120 Sekunden auseinanderliegen, auf die Nanosekunde genau (wie `Duration`). */
function withinWindow(first: InstantText, second: InstantText): boolean {
  const a = instantParts(first);
  const b = instantParts(second);
  const difference = (b.epochSecond - a.epochSecond) * NANOS_PER_SECOND + BigInt(b.nano - a.nano);
  const absolute = difference < 0n ? -difference : difference;
  return absolute <= BigInt(WINDOW_SECONDS) * NANOS_PER_SECOND;
}
