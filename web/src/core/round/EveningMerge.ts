/*
 * Führt zwei Fassungen eines Abends zusammen: die des eigenen Geräts und die des Servers. Die Basis ist die Fassung,
 * die das Gerät zuletzt vom Server kannte; an ihr lässt sich ablesen, wer was geändert hat.
 *
 * Port von `domain/round/EveningMerge.kt`, mit denselben Fällen geprüft (`golden/kotlin-golden.json`, Abschnitt
 * `merges`). Reine Funktion ohne Uhr und Zufall. Regeln: docs/ARCHITEKTUR.md, Abschnitt 8.5.
 *
 * „Gleich“ heißt wie in Kotlin: alle Felder gleich (siehe `equality.ts`). Kennungen werden nach UTF-16-Einheiten
 * verglichen (`<`, nie `localeCompare`), Zeitpunkte als Zeit und nicht als Text.
 */
import { compareInstants } from "../instant";
import type { EntryId, Evening, EveningEntry } from "../model";
import { sameEntry, sameHeaderField } from "./equality";

type HeaderField = keyof typeof sameHeaderField;

export const EveningMerge = {
  /**
   * @param base Die zuletzt bekannte Fassung des Servers; `null`, wenn das Gerät den Abend noch nie abgeglichen hat.
   * @param local Der Abend auf diesem Gerät.
   * @param remote Der Abend auf dem Server.
   */
  merge3(base: Evening | null, local: Evening, remote: Evening): Evening {
    // Kopf: Was hier geändert wurde, bleibt; alles andere kommt vom Server.
    const field = <K extends HeaderField>(key: K): Evening[K] => {
      const mine = local[key];
      const same = sameHeaderField[key] as (a: Evening[K], b: Evening[K]) => boolean;
      return base === null || same(mine, base[key]) ? remote[key] : mine;
    };
    return {
      id: local.id,
      startedAt: field("startedAt"),
      finishedAt: field("finishedAt"),
      location: field("location"),
      seating: field("seating"),
      firstDealer: field("firstDealer"),
      rules: field("rules"),
      entries: mergeEntries(base?.entries ?? [], local.entries, remote.entries),
    };
  },
};

function mergeEntries(
  base: readonly EveningEntry[],
  local: readonly EveningEntry[],
  remote: readonly EveningEntry[],
): EveningEntry[] {
  // Wie Kotlins `associateBy`: Bei doppelten Kennungen in der Basis bleibt der letzte Eintrag.
  const baseById = new Map<EntryId, EveningEntry>();
  for (const entry of base) baseById.set(entry.id, entry);
  const localById = new Map<EntryId, EveningEntry>();
  const localIndex = new Map<EntryId, number>();
  // Bei doppelten Kennungen (beschädigte Daten) zählt hier und beim Server jeweils der erste Eintrag.
  local.forEach((entry, index) => {
    if (!localById.has(entry.id)) {
      localById.set(entry.id, entry);
      localIndex.set(entry.id, index);
    }
  });
  const remoteIds = new Set<EntryId>();
  const result: EveningEntry[] = [];

  // 1. Die Reihenfolge des Servers gilt für alles, was er kennt.
  for (const theirs of remote) {
    if (remoteIds.has(theirs.id)) continue;
    remoteIds.add(theirs.id);
    const mine = localById.get(theirs.id);
    const known = baseById.get(theirs.id);
    if (mine !== undefined) {
      // Hier unverändert seit der Basis: Die Fassung des Servers gilt. Sonst gewinnt die Änderung von hier.
      result.push(!sameEntry(mine, theirs) && known !== undefined && sameEntry(mine, known) ? theirs : mine);
    } else if (known !== undefined && sameEntry(theirs, known)) {
      // Hier gelöscht und auf dem Server seitdem unverändert: bleibt gelöscht.
    } else {
      // Neu vom Server, oder die Änderung des Servers gewinnt gegen das Löschen hier.
      result.push(theirs);
    }
  }

  // 2. Was nur dieses Gerät hat, in dessen Reihenfolge einsortieren.
  local.forEach((mine, index) => {
    if (remoteIds.has(mine.id) || localIndex.get(mine.id) !== index) return;
    const known = baseById.get(mine.id);
    // Auf dem Server gelöscht und hier seitdem unverändert: entfällt.
    if (known !== undefined && sameEntry(mine, known)) return;
    result.splice(insertPosition(result, mine, index, localIndex, baseById), 0, mine);
  });
  return result;
}

/**
 * Die Stelle für einen Eintrag, den nur dieses Gerät hat: hinter dem letzten Eintrag des Ergebnisses, der hier vor
 * ihm steht; von dort weiter über alle direkt folgenden Einträge, die neu vom Server sind und früher aufgeschrieben
 * wurden. So stehen gleichzeitig auf zwei Geräten eingetragene Spiele in der Reihenfolge ihrer Uhrzeit.
 */
function insertPosition(
  result: readonly EveningEntry[],
  mine: EveningEntry,
  index: number,
  localIndex: ReadonlyMap<EntryId, number>,
  baseById: ReadonlyMap<EntryId, EveningEntry>,
): number {
  let position = 0;
  for (let i = result.length - 1; i >= 0; i--) {
    const other = localIndex.get(result[i].id);
    if (other !== undefined && other < index) {
      position = i + 1;
      break;
    }
  }
  while (position < result.length) {
    const next = result[position];
    const newFromServer = !localIndex.has(next.id) && !baseById.has(next.id);
    if (newFromServer && comesBefore(next, mine)) position++;
    else break;
  }
  return position;
}

/** Ob `first` nach Zeitpunkt und – bei gleichem Zeitpunkt – nach Kennung vor `second` liegt. */
function comesBefore(first: EveningEntry, second: EveningEntry): boolean {
  const byTime = compareInstants(first.recordedAt, second.recordedAt);
  return byTime !== 0 ? byTime < 0 : first.id < second.id;
}
