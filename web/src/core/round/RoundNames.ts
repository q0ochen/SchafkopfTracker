/*
 * Namen von Runden (docs/ARCHITEKTUR.md, 8.7, „Eindeutige Namen“): bereinigen und vergleichen wie der Server, einen
 * freien Namen vorschlagen und gleichnamige Runden für Listen erkennen. Ein Konto hat keine zwei Runden desselben
 * Namens; durch Beitreten kann es trotzdem welche haben (angelegt haben sie dann verschiedene Leute).
 * Port von `domain/round/RoundNames.kt`.
 */
import type { RoundId, RoundSummary } from "./RoundModels";

/** Die Vorgabe im Feld „Name der Runde“. */
const DEFAULT = "Schafkopfrunde";

/** Höchstens so viele Zeichen hat der Name einer Runde (nach dem Bereinigen). */
const MAX_LENGTH = 40;

/**
 * Ob ein Zeichen (UTF-16-Einheit) Leer- oder Steuerzeichen ist: nur ASCII, U+0000 bis U+0020 und U+007F, wie
 * `[[:space:][:cntrl:]]` auf dem Server (Kotlin: `Regex("[\\x00-\\x20\\x7F]+")`). Ein geschütztes Leerzeichen gehört
 * nicht dazu.
 */
function isSpaceOrControl(code: number): boolean {
  return code <= 0x20 || code === 0x7f;
}

/** Wie Kotlins `trim(' ')`: nur Leerzeichen am Anfang und Ende entfernen, sonst nichts. */
function trimSpaces(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && text.charCodeAt(start) === 0x20) start++;
  while (end > start && text.charCodeAt(end - 1) === 0x20) end--;
  return text.substring(start, end);
}

function clean(name: string): string {
  // Jede Folge von Leer- und Steuerzeichen wird ein Leerzeichen (ohne regulären Ausdruck mit Steuerzeichen).
  let result = "";
  let inRun = false;
  for (let index = 0; index < name.length; index++) {
    if (isSpaceOrControl(name.charCodeAt(index))) {
      if (!inRun) result += " ";
      inRun = true;
    } else {
      result += name[index];
      inRun = false;
    }
  }
  return trimSpaces(result);
}

/** Der Schlüssel für Vergleiche: bereinigt und ohne Groß/Klein (Kotlin: `lowercase()`). */
function key(name: string): string {
  return clean(name).toLowerCase();
}

function same(a: string, b: string): boolean {
  return key(a) === key(b);
}

function taken(name: string, rounds: readonly RoundSummary[]): boolean {
  return rounds.some((it) => same(it.name, name));
}

export const RoundNames = {
  DEFAULT,
  MAX_LENGTH,

  /**
   * Wie `private.clean_name` auf dem Server: Folgen von Leer- und Steuerzeichen (auch Tabulator und Zeilenumbruch)
   * werden zu einem Leerzeichen, Leerzeichen am Anfang und Ende entfallen.
   */
  clean,

  /**
   * Ob der Name nach dem Bereinigen 1 bis {@link MAX_LENGTH} Zeichen hat. Gezählt werden Zeichen wie auf dem Server
   * (Kotlin: `codePointCount`): Ein Zeichen außerhalb der Grundebene zählt einmal, nicht zweimal.
   */
  valid(name: string): boolean {
    const length = [...clean(name)].length;
    return length >= 1 && length <= MAX_LENGTH;
  },

  /** Ob zwei Namen für den Server derselbe sind: bereinigt und ohne Groß/Klein. */
  same,

  /** Ob unter `rounds` schon eine Runde `name` heißt. */
  taken,

  /** `base` oder, wenn das Konto so eine Runde schon hat, die erste freie Fassung „‹base› 2“, „‹base› 3“ … */
  suggest(rounds: readonly RoundSummary[], base: string = DEFAULT): string {
    for (let number = 1; ; number++) {
      const candidate = number === 1 ? base : `${base} ${number}`;
      if (!taken(candidate, rounds)) return candidate;
    }
  },

  /**
   * Die Runden, deren Name unter `rounds` mehr als einmal vorkommt; Listen nennen bei ihnen die Mitglieder. Die
   * Reihenfolge ist die von Kotlins `groupBy`: Gruppen nach dem ersten Vorkommen ihres Namens, darin wie in `rounds`.
   */
  duplicates(rounds: readonly RoundSummary[]): Set<RoundId> {
    const groups = new Map<string, RoundId[]>();
    for (const round of rounds) {
      const name = key(round.name);
      const group = groups.get(name);
      if (group === undefined) groups.set(name, [round.id]);
      else group.push(round.id);
    }
    const result = new Set<RoundId>();
    for (const group of groups.values()) if (group.length > 1) for (const id of group) result.add(id);
    return result;
  },
};
