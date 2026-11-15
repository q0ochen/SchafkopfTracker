/*
 * Zeitpunkte als ISO-8601-Text in UTC, so wie Kotlins `Instant.toString()` sie schreibt (zum Beispiel
 * „2026-11-14T19:00:00Z“ oder „2026-11-14T19:00:00.123Z“). Als Text verglichen stünde „…:00.123Z“ vor „…:00Z“;
 * deshalb wird hier immer über Sekunden und Nanosekunden verglichen (Sekunden als BigInt: Die Android-App erlaubt
 * Zeitpunkte über den ganzen Bereich von `Long`-Millisekunden, das ist mehr, als eine JavaScript-Zahl genau fasst).
 */
import type { InstantText } from "./model";

/** Ein Zeitpunkt zerlegt in ganze Sekunden seit 1970-01-01T00:00:00Z und Nanosekunden (0 bis 999 999 999). */
export interface InstantParts {
  readonly epochSecond: bigint;
  readonly nano: number;
}

const ISO_INSTANT = /^([+-]?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

/** Tage seit 1970-01-01 für ein Datum im (proleptischen) gregorianischen Kalender. */
function daysFromCivil(year: bigint, month: number, day: number): bigint {
  const y = month <= 2 ? year - 1n : year;
  const era = (y >= 0n ? y : y - 399n) / 400n;
  const yearOfEra = y - era * 400n;
  const m = BigInt(month);
  const dayOfYear = (153n * (m > 2n ? m - 3n : m + 9n) + 2n) / 5n + BigInt(day) - 1n;
  const dayOfEra = yearOfEra * 365n + yearOfEra / 4n - yearOfEra / 100n + dayOfYear;
  return era * 146097n + dayOfEra - 719468n;
}

/**
 * Zerlegt einen Zeitpunkt in der Schreibweise von `Instant.toString()`.
 * @throws Error wenn der Text nicht diese Form hat.
 */
export function instantParts(text: InstantText): InstantParts {
  const match = ISO_INSTANT.exec(text);
  if (!match) throw new Error(`Kein Zeitpunkt: ${text}`);
  const [, year, month, day, hour, minute, second, fraction] = match;
  const days = daysFromCivil(BigInt(year), Number(month), Number(day));
  const epochSecond = days * 86400n + BigInt(Number(hour) * 3600 + Number(minute) * 60 + Number(second));
  const nano = fraction ? Number(fraction.padEnd(9, "0")) : 0;
  return { epochSecond, nano };
}

/** Vergleicht zwei Zeitpunkte: negativ, wenn `a` früher ist, 0 bei Gleichheit, positiv, wenn `a` später ist. */
export function compareInstants(a: InstantText, b: InstantText): number {
  const left = instantParts(a);
  const right = instantParts(b);
  if (left.epochSecond !== right.epochSecond) return left.epochSecond < right.epochSecond ? -1 : 1;
  return left.nano - right.nano;
}

/** Millisekunden seit 1970 (für die Anzeige mit `Date`); Bruchteile unter einer Millisekunde entfallen. */
export function instantToEpochMillis(text: InstantText): number {
  const { epochSecond, nano } = instantParts(text);
  return Number(epochSecond) * 1000 + Math.floor(nano / 1_000_000);
}

/**
 * Ein Zeitpunkt aus Millisekunden seit 1970, genau so geschrieben wie `Instant.toString()`: ohne Bruchteil, wenn
 * er null ist, sonst mit drei Stellen. Gedacht für Zeitpunkte zwischen den Jahren 0 und 9999 (zum Beispiel „jetzt“).
 */
export function instantFromEpochMillis(epochMillis: number): InstantText {
  const date = new Date(epochMillis);
  if (Number.isNaN(date.getTime())) throw new Error(`Kein gültiger Zeitpunkt: ${epochMillis}`);
  const year = date.getUTCFullYear();
  if (year < 0 || year > 9999) throw new Error(`Jahr außerhalb von 0 bis 9999: ${year}`);
  const text = date.toISOString();
  return text.endsWith(".000Z") ? `${text.slice(0, -5)}Z` : text;
}
