/*
 * Zeitpunkte als ISO-8601-Text in UTC, so wie Kotlins `Instant.toString()` sie schreibt (zum Beispiel
 * „2026-11-14T19:00:00Z“ oder „2026-11-14T19:00:00.123Z“). Als Text verglichen stünde „…:00.123Z“ vor „…:00Z“;
 * deshalb wird hier immer über Sekunden und Nanosekunden verglichen (Sekunden als BigInt: Die Android-App erlaubt
 * Zeitpunkte über den ganzen Bereich von `Long`-Millisekunden, das ist mehr, als eine JavaScript-Zahl genau fasst).
 *
 * Dazu Zeitspannen wie Javas `Duration` (`durationBetween`, `plusDuration`): „Abend bearbeiten“ verschiebt das Ende
 * eines Abends um genau die Spanne, um die sich sein Beginn ändert (`EveningEdit`).
 */
import type { InstantText } from "./model";

/** Ein Zeitpunkt zerlegt in ganze Sekunden seit 1970-01-01T00:00:00Z und Nanosekunden (0 bis 999 999 999). */
export interface InstantParts {
  readonly epochSecond: bigint;
  readonly nano: number;
}

/** Eine Zeitspanne wie Javas `Duration`: ganze Sekunden (auch negativ) und dazu Nanosekunden (0 bis 999 999 999). */
export interface DurationParts {
  readonly seconds: bigint;
  readonly nano: number;
}

/** Ein Datum im (proleptischen) gregorianischen Kalender; das Jahr darf beliebig groß oder negativ sein. */
export interface CivilDate {
  readonly year: bigint;
  readonly month: number;
  readonly day: number;
}

/** Der früheste Zeitpunkt von `java.time.Instant` (`Instant.MIN`, -1000000000-01-01T00:00:00Z) in Sekunden seit 1970. */
export const INSTANT_MIN_EPOCH_SECOND = -31_557_014_167_219_200n;

/** Der späteste Zeitpunkt von `java.time.Instant` (`Instant.MAX`, +1000000000-12-31T23:59:59.999999999Z) in Sekunden seit 1970. */
export const INSTANT_MAX_EPOCH_SECOND = 31_556_889_864_403_199n;

const NANOS_PER_SECOND = 1_000_000_000;
const SECONDS_PER_DAY = 86_400n;

const ISO_INSTANT = /^([+-]?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

/** Ganzzahlige Division, abgerundet wie Javas `Math.floorDiv` (BigInt rundet sonst zur Null hin). */
function floorDiv(a: bigint, b: bigint): bigint {
  const quotient = a / b;
  return a % b !== 0n && (a < 0n) !== (b < 0n) ? quotient - 1n : quotient;
}

/** Tage seit 1970-01-01 für ein Datum im (proleptischen) gregorianischen Kalender (H. Hinnant, `days_from_civil`). */
export function daysFromCivil(year: bigint, month: number, day: number): bigint {
  const y = month <= 2 ? year - 1n : year;
  const era = (y >= 0n ? y : y - 399n) / 400n;
  const yearOfEra = y - era * 400n;
  const m = BigInt(month);
  const dayOfYear = (153n * (m > 2n ? m - 3n : m + 9n) + 2n) / 5n + BigInt(day) - 1n;
  const dayOfEra = yearOfEra * 365n + yearOfEra / 4n - yearOfEra / 100n + dayOfYear;
  return era * 146097n + dayOfEra - 719468n;
}

/**
 * Das Datum zu einer Zahl von Tagen seit 1970-01-01, die Umkehrung von {@link daysFromCivil} (H. Hinnant,
 * `civil_from_days`). Die Jahre zählen in Zyklen von 400 Jahren (146 097 Tagen); nur die Zahl der Zyklen ist ein
 * BigInt, alles innerhalb eines Zyklus passt genau in eine JavaScript-Zahl.
 */
export function civilFromDays(days: bigint): CivilDate {
  const z = days + 719_468n;
  const era = floorDiv(z, 146_097n);
  // Tag im Zyklus (0 bis 146 096) und Jahr im Zyklus (0 bis 399); das Jahr beginnt hier am 1. März.
  const dayOfEra = Number(z - era * 146_097n);
  const yearOfEra = Math.floor(
    (dayOfEra - Math.floor(dayOfEra / 1460) + Math.floor(dayOfEra / 36_524) - Math.floor(dayOfEra / 146_096)) / 365,
  );
  const dayOfYear = dayOfEra - (365 * yearOfEra + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100));
  // Monat ab März gezählt (0 = März, 11 = Februar).
  const monthFromMarch = Math.floor((5 * dayOfYear + 2) / 153);
  const day = dayOfYear - Math.floor((153 * monthFromMarch + 2) / 5) + 1;
  const month = monthFromMarch < 10 ? monthFromMarch + 3 : monthFromMarch - 9;
  return { year: era * 400n + BigInt(yearOfEra) + (month <= 2 ? 1n : 0n), month, day };
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

function pad(value: bigint | number, width: number): string {
  return String(value).padStart(width, "0");
}

/**
 * Schreibt einen Zeitpunkt genau wie Kotlins `Instant.toString()` (`DateTimeFormatter.ISO_INSTANT`): das Jahr mit
 * mindestens vier Stellen, mit „+“ vor Jahren über 9999 und „-“ vor Jahren vor 0; die Sekunden immer; einen Bruchteil
 * nur, wenn er nicht null ist, dann in Dreiergruppen mit 3, 6 oder 9 Stellen. Gilt im ganzen Bereich von
 * `java.time.Instant`.
 * @throws Error wenn `nano` keine ganze Zahl von 0 bis 999 999 999 ist.
 */
export function formatInstant(parts: InstantParts): InstantText {
  const { epochSecond, nano } = parts;
  if (!Number.isInteger(nano) || nano < 0 || nano >= NANOS_PER_SECOND) throw new Error(`Keine Nanosekunden: ${nano}`);
  const days = floorDiv(epochSecond, SECONDS_PER_DAY);
  const secondOfDay = Number(epochSecond - days * SECONDS_PER_DAY);
  const { year, month, day } = civilFromDays(days);
  const yearText = year > 9999n ? `+${year}` : year < 0n ? `-${pad(-year, 4)}` : pad(year, 4);
  const time = `${pad(Math.floor(secondOfDay / 3600), 2)}:${pad(Math.floor(secondOfDay / 60) % 60, 2)}:${pad(secondOfDay % 60, 2)}`;
  let fraction = "";
  if (nano > 0) {
    const digits = pad(nano, 9);
    fraction = `.${nano % 1_000_000 === 0 ? digits.slice(0, 3) : nano % 1000 === 0 ? digits.slice(0, 6) : digits}`;
  }
  return `${yearText}-${pad(month, 2)}-${pad(day, 2)}T${time}${fraction}Z`;
}

/** Vergleicht zwei Zeitpunkte: negativ, wenn `a` früher ist, 0 bei Gleichheit, positiv, wenn `a` später ist. */
export function compareInstants(a: InstantText, b: InstantText): number {
  const left = instantParts(a);
  const right = instantParts(b);
  if (left.epochSecond !== right.epochSecond) return left.epochSecond < right.epochSecond ? -1 : 1;
  return left.nano - right.nano;
}

/** Die Zeitspanne von `start` bis `end`, genau wie Kotlins `Duration.between(start, end)` (negativ, wenn `end` früher ist). */
export function durationBetween(start: InstantParts, end: InstantParts): DurationParts {
  const nano = end.nano - start.nano;
  const seconds = end.epochSecond - start.epochSecond;
  return nano < 0 ? { seconds: seconds - 1n, nano: nano + NANOS_PER_SECOND } : { seconds, nano };
}

function inInstantRange(epochSecond: bigint): boolean {
  return epochSecond >= INSTANT_MIN_EPOCH_SECOND && epochSecond <= INSTANT_MAX_EPOCH_SECOND;
}

/**
 * Der um `duration` verschobene Zeitpunkt, wie Kotlins `instant.plus(duration)`. Java rechnet in zwei Schritten,
 * erst die ganzen Sekunden, dann die Nanosekunden, und wirft nach jedem Schritt eine `DateTimeException`, wenn der
 * Zeitpunkt außerhalb von `Instant.MIN` bis `Instant.MAX` liegt; dann ist das Ergebnis hier `null`. Deshalb scheitert
 * zum Beispiel -1000000000-01-01T00:00:00.600Z minus eine halbe Sekunde, obwohl das Ergebnis selbst im Bereich läge:
 * Eine halbe Sekunde zurück ist für Java eine ganze zurück und eine halbe vor.
 */
export function plusDuration(instant: InstantParts, duration: DurationParts): InstantParts | null {
  let { epochSecond, nano } = instant;
  if (duration.seconds !== 0n) {
    epochSecond += duration.seconds;
    if (!inInstantRange(epochSecond)) return null;
  }
  if (duration.nano !== 0) {
    nano += duration.nano;
    if (nano >= NANOS_PER_SECOND) {
      nano -= NANOS_PER_SECOND;
      epochSecond += 1n;
    }
    if (!inInstantRange(epochSecond)) return null;
  }
  return { epochSecond, nano };
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
