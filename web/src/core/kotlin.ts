/*
 * Kleine Hilfen, mit denen der Rechenkern das Zahlen- und Textverhalten von Kotlin genau nachbildet.
 *
 * - Kotlin rechnet Beträge als `Int` (32 Bit, Zweierkomplement). Wo Kotlin dabei überlaufen kann (Summen über
 *   unsinnig große Beträge aus beschädigten Daten), liefern `intAdd`, `intSub` und `intMul` dasselbe übergelaufene
 *   Ergebnis. Für zwei ganze Zahlen im Int-Bereich ist die Summe als JavaScript-Zahl exakt (Betrag unter 2^32);
 *   `| 0` schneidet sie dann genau wie Kotlin auf 32 Bit ab. `Math.imul` multipliziert in 32 Bit.
 * - Nebenbei verhindert `| 0`, dass eine „negative Null“ entsteht (in JavaScript ist zum Beispiel −1 × 0 = −0).
 *   Die gibt es in Kotlin nicht, und eine Anzeige könnte sie als „−0,00 €“ schreiben.
 * - Texte vergleicht Kotlin (`String.compareTo`) zeichenweise nach UTF-16-Codeeinheiten; die Operatoren `<` und `>`
 *   von JavaScript tun genau dasselbe. `localeCompare` wäre falsch.
 */

/** Größter Wert eines Kotlin-`Int`. */
export const INT_MAX = 2147483647;

/** Kleinster Wert eines Kotlin-`Int`. */
export const INT_MIN = -2147483648;

/** `a + b` wie Kotlin-`Int`: läuft über, statt größer zu werden. */
export function intAdd(a: number, b: number): number {
  return (a + b) | 0;
}

/** `a - b` wie Kotlin-`Int`. */
export function intSub(a: number, b: number): number {
  return (a - b) | 0;
}

/** `a * b` wie Kotlin-`Int`. */
export function intMul(a: number, b: number): number {
  return Math.imul(a, b);
}

/**
 * Summe wie Kotlins `sumOf { … }` mit `Int`-Werten: Der Überlauf wird wie in Kotlin bei jedem Schritt abgeschnitten.
 * (Weil das Abschneiden auf 32 Bit mit der Addition verträglich ist, käme dasselbe heraus, wenn man erst am Ende
 * abschnitte – solange die Zwischensumme exakt bleibt.)
 */
export function intSum<T>(items: Iterable<T>, selector: (item: T) => number): number {
  let sum = 0;
  for (const item of items) sum = intAdd(sum, selector(item));
  return sum;
}

/** Ob eine Zahl in einen Kotlin-`Int` passt. */
export function inIntRange(value: number): boolean {
  return value >= INT_MIN && value <= INT_MAX;
}

/** Wie Kotlins `coerceIn(min, max)`. */
export function coerceIn(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

/**
 * Vergleicht zwei Texte wie Kotlins `String.compareTo` (nach UTF-16-Codeeinheiten, ohne Sprachregeln):
 * negativ, 0 oder positiv.
 */
export function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Vergleicht zwei Zahlen wie Kotlins `compareValues` bzw. `Int.compareTo`. */
export function compareNumbers(a: number, b: number): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Vergleicht die Produkte `a1 × b1` und `a2 × b2` exakt, wie Kotlin es mit `Long` tut (`a1.toLong() * b1`).
 * Gerechnet wird mit `BigInt`, weil ein Produkt zweier Int-Werte bis 2^62 groß werden kann – mehr, als eine
 * JavaScript-Zahl genau fasst. Die Faktoren sind hier immer gezählte Spiele, also ganze Zahlen.
 */
export function compareProducts(a1: number, b1: number, a2: number, b2: number): number {
  const left = BigInt(a1) * BigInt(b1);
  const right = BigInt(a2) * BigInt(b2);
  return left < right ? -1 : left > right ? 1 : 0;
}

/**
 * Wie Kotlins `associateWith`: Je Schlüssel ein Wert, in der Reihenfolge der Schlüssel. Kommt ein Schlüssel mehrfach
 * vor, bleibt er an seiner ersten Stelle und bekommt den zuletzt berechneten Wert (wie bei `LinkedHashMap.put`).
 */
export function associateWith<K, V>(keys: Iterable<K>, value: (key: K) => V): Map<K, V> {
  const map = new Map<K, V>();
  for (const key of keys) map.set(key, value(key));
  return map;
}

/** Wie Kotlins `mapValues`: dieselben Schlüssel in derselben Reihenfolge, neue Werte. */
export function mapValues<K, V, R>(map: ReadonlyMap<K, V>, value: (value: V, key: K) => R): Map<K, R> {
  const result = new Map<K, R>();
  for (const [key, entry] of map) result.set(key, value(entry, key));
  return result;
}

/** Wie Kotlins `distinct()`: jedes Element einmal, an der Stelle seines ersten Vorkommens. */
export function distinct<T>(items: Iterable<T>): T[] {
  return [...new Set(items)];
}

/** Wie Kotlins `maxOrNull()`: der größte Wert; `null` ohne Wert. */
export function maxOrNull(values: Iterable<number>): number | null {
  let max: number | null = null;
  for (const value of values) if (max === null || value > max) max = value;
  return max;
}

/** Wie Kotlins `minOrNull()`: der kleinste Wert; `null` ohne Wert. */
export function minOrNull(values: Iterable<number>): number | null {
  let min: number | null = null;
  for (const value of values) if (min === null || value < min) min = value;
  return min;
}

/** Wie Kotlins `maxByOrNull`: bei Gleichstand das erste Element mit dem größten Wert; `null` ohne Element. */
export function maxByOrNull<T>(items: readonly T[], selector: (item: T) => number): T | null {
  let best: T | null = null;
  let bestValue = 0;
  for (const item of items) {
    const value = selector(item);
    if (best === null || value > bestValue) {
      best = item;
      bestValue = value;
    }
  }
  return best;
}

/** Wie Kotlins `minWithOrNull`: bei Gleichstand das erste kleinste Element; `null` ohne Element. */
export function minWithOrNull<T>(items: readonly T[], comparator: (a: T, b: T) => number): T | null {
  let best: T | null = null;
  for (const item of items) {
    if (best === null || comparator(best, item) > 0) best = item;
  }
  return best;
}
