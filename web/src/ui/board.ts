/*
 * Aktionen der Abend-Ansicht als reine Funktionen – wie in der Android-App (`ui/board/BoardViewModel.kt`).
 */
import { append, insertAt, remove } from "../core/evening/Evening";
import type { Evening, EveningEntry, PlayerId } from "../core/model";
import type { EntryFactory } from "./gameForm";

/** Alle haben „weiter“ gesagt. */
export function addThrownIn(evening: Evening, factory: EntryFactory): Evening {
  return append(evening, { kind: "thrown_in", id: factory.newId(), recordedAt: factory.now() });
}

/** Jemand hat 6 Nixer: Bockrunde auslösen. */
export function addBockTrigger(evening: Evening, player: PlayerId | null, handThrownIn: boolean, factory: EntryFactory): Evening {
  return append(evening, {
    kind: "bock",
    id: factory.newId(),
    recordedAt: factory.now(),
    reason: "SECHS_NIXER",
    player,
    handThrownIn,
  });
}

/** Ein zurückgenommener Eintrag und seine Stelle, damit „Wiederherstellen“ ihn genau dort wieder einsetzt. */
export interface RemovedEntry {
  readonly entry: EveningEntry;
  readonly index: number;
}

/** Nimmt den letzten Eintrag zurück; `null`, wenn es keinen gibt. */
export function removeLast(evening: Evening): { evening: Evening; removed: RemovedEntry } | null {
  const index = evening.entries.length - 1;
  if (index < 0) return null;
  const entry = evening.entries[index];
  return { evening: remove(evening, entry.id), removed: { entry, index } };
}

/** Setzt einen zurückgenommenen Eintrag wieder ein (nicht doppelt). */
export function restore(evening: Evening, removed: RemovedEntry): Evening {
  if (evening.entries.some((it) => it.id === removed.entry.id)) return evening;
  return insertAt(evening, removed.index, removed.entry);
}
