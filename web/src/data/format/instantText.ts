/*
 * Zeitpunkte genau wie die Android-App: lesen wie `Instant.parse` (java.time, JDK 21) mit der Bereichsprüfung von
 * `parseInstant` (Modul `data`) und schreiben wie `Instant.toString()`.
 *
 * `Instant.parse` nimmt mehr an als die Schreibweise, die die App selbst schreibt: Kleinbuchstaben („t“, „z“), einen
 * Versatz wie „+01:00“ (bis ±18 Stunden), einen Punkt ohne Ziffern, 24:00:00 (als Mitternacht des Folgetags) und die
 * Schaltsekunde 23:59:60 (als 23:59:59). Gelesen wird daraus immer die kanonische Schreibweise von `Instant.toString()`:
 * „…T19:00:00.000Z“ wird zu „…T19:00:00Z“, „…T20:00:00+01:00“ zu „…T19:00:00Z“.
 */
import type { InstantParts } from "../../core/instant";
import type { InstantText } from "../../core/model";
import { InvalidInstantError } from "./errors";

const SECONDS_PER_DAY = 86_400n;
const SECONDS_PER_10000_YEARS = 146_097n * 25n * SECONDS_PER_DAY;
const SECONDS_0000_TO_1970 = (146_097n * 5n - (30n * 365n + 7n)) * SECONDS_PER_DAY;

/** Bereich von `java.time.Instant`. */
const INSTANT_MIN_SECOND = -31_557_014_167_219_200n;
const INSTANT_MAX_SECOND = 31_556_889_864_403_199n;

/** Frühester und spätester Zeitpunkt, der sich in Millisekunden seit 1970 ausdrücken lässt (`Long.MIN_VALUE`/`MAX_VALUE`). */
const EARLIEST: InstantParts = { epochSecond: -9_223_372_036_854_776n, nano: 192_000_000 };
const LATEST: InstantParts = { epochSecond: 9_223_372_036_854_775n, nano: 807_000_000 };

function compareParts(a: InstantParts, b: InstantParts): number {
  if (a.epochSecond !== b.epochSecond) return a.epochSecond < b.epochSecond ? -1 : 1;
  return a.nano - b.nano;
}

function floorDiv(a: bigint, b: bigint): bigint {
  const quotient = a / b;
  return (a % b !== 0n && (a < 0n) !== (b < 0n)) ? quotient - 1n : quotient;
}

function floorMod(a: bigint, b: bigint): bigint {
  return a - floorDiv(a, b) * b;
}

function isLeapYear(year: number): boolean {
  return (year & 3) === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function monthLength(year: number, month: number): number {
  switch (month) {
    case 2:
      return isLeapYear(year) ? 29 : 28;
    case 4:
    case 6:
    case 9:
    case 11:
      return 30;
    default:
      return 31;
  }
}

/** Tage seit 1970-01-01 (proleptischer gregorianischer Kalender, wie `LocalDate.toEpochDay`). */
function epochDay(year: number, month: number, day: number): number {
  const y = month <= 2 ? year - 1 : year;
  const era = Math.floor(y / 400);
  const yearOfEra = y - era * 400;
  const dayOfYear = Math.floor((153 * (month > 2 ? month - 3 : month + 9) + 2) / 5) + day - 1;
  const dayOfEra = yearOfEra * 365 + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100) + dayOfYear;
  return era * 146_097 + dayOfEra - 719_468;
}

/** Datum zu Tagen seit 1970 (Umkehrung von {@link epochDay}). */
function civilFromDays(days: number): { year: number; month: number; day: number } {
  const z = days + 719_468;
  const era = Math.floor(z / 146_097);
  const dayOfEra = z - era * 146_097;
  const yearOfEra = Math.floor((dayOfEra - Math.floor(dayOfEra / 1460) + Math.floor(dayOfEra / 36_524) - Math.floor(dayOfEra / 146_096)) / 365);
  const dayOfYear = dayOfEra - (365 * yearOfEra + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100));
  const mp = Math.floor((5 * dayOfYear + 2) / 153);
  const day = dayOfYear - Math.floor((153 * mp + 2) / 5) + 1;
  const month = mp < 10 ? mp + 3 : mp - 9;
  return { year: yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month, day };
}

function invalid(text: string): never {
  throw new InvalidInstantError(`Kein gültiger Zeitpunkt: ${text}`);
}

function isAsciiDigit(code: number): boolean {
  return code >= 0x30 && code <= 0x39;
}

/** Ein Zeichen der Vorlage, Buchstaben ohne Rücksicht auf Groß- und Kleinschreibung (ISO_INSTANT liest so). */
function matchesLiteral(code: number, literal: number): boolean {
  if (code === literal) return true;
  const isLetter = (literal >= 0x41 && literal <= 0x5a) || (literal >= 0x61 && literal <= 0x7a);
  return isLetter && code === (literal ^ 0x20);
}

/**
 * Zerlegt einen Text wie `Instant.parse` (ISO_INSTANT von JDK 21) in Sekunden und Nanosekunden, im ganzen Bereich von
 * `java.time.Instant`.
 * @throws InvalidInstantError wenn Java den Text ablehnt.
 */
export function parseInstantValue(text: string): InstantParts {
  const length = text.length;
  let position = 0;

  // Jahr: 4 bis 10 Ziffern; ein Plus nur bei mehr als 4 Ziffern und dann Pflicht, ein Minus immer erlaubt (außer bei 0).
  if (position === length) invalid(text);
  let negative = false;
  let positive = false;
  const sign = text.charCodeAt(position);
  if (sign === 0x2b) {
    positive = true;
    position++;
  } else if (sign === 0x2d) {
    negative = true;
    position++;
  }
  const yearStart = position;
  if (yearStart + 4 > length) invalid(text);
  const yearEnd = Math.min(yearStart + 10, length);
  while (position < yearEnd && isAsciiDigit(text.charCodeAt(position))) position++;
  if (position < yearStart + 4) invalid(text);
  let yearParsed = Number(text.substring(yearStart, position));
  const yearDigits = position - yearStart;
  if (negative) {
    if (yearParsed === 0) invalid(text);
    yearParsed = -yearParsed;
  } else if (positive ? yearDigits <= 4 : yearDigits > 4) {
    invalid(text);
  }

  const literal = (expected: number): void => {
    if (position === length || !matchesLiteral(text.charCodeAt(position), expected)) invalid(text);
    position++;
  };
  /** Genau zwei Ziffern ohne Vorzeichen (`appendValue(field, 2)`). */
  const twoDigits = (): number => {
    if (position === length || position + 2 > length) invalid(text);
    const first = text.charCodeAt(position);
    const second = text.charCodeAt(position + 1);
    if (!isAsciiDigit(first) || !isAsciiDigit(second)) invalid(text);
    position += 2;
    return (first - 0x30) * 10 + (second - 0x30);
  };

  literal(0x2d);
  const month = twoDigits();
  literal(0x2d);
  const day = twoDigits();
  literal(0x54);
  let hour = twoDigits();
  literal(0x3a);
  const minute = twoDigits();
  literal(0x3a);
  let second = twoDigits();

  // Bruchteil: ein Punkt und 0 bis 9 Ziffern.
  let nano = 0;
  if (position < length && text.charCodeAt(position) === 0x2e) {
    position++;
    const fractionStart = position;
    const fractionEnd = Math.min(position + 9, length);
    while (position < fractionEnd && isAsciiDigit(text.charCodeAt(position))) position++;
    const digits = text.substring(fractionStart, position);
    nano = digits.length === 0 ? 0 : Number(digits.padEnd(9, "0"));
  }

  // Versatz: „Z“ oder ±HH:MM mit wahlweise :ss.
  if (position === length) invalid(text);
  let offsetSeconds = 0;
  if (matchesLiteral(text.charCodeAt(position), 0x5a)) {
    position++;
  } else {
    const offsetSign = text.charCodeAt(position);
    if (offsetSign !== 0x2b && offsetSign !== 0x2d) invalid(text);
    let offsetPosition = position + 1;
    const offsetDigits = (withColon: boolean): number | null => {
      let pos = offsetPosition;
      if (withColon) {
        if (pos + 1 > length || text.charCodeAt(pos) !== 0x3a) return null;
        pos++;
      }
      if (pos + 2 > length) return null;
      const first = text.charCodeAt(pos);
      const second = text.charCodeAt(pos + 1);
      if (!isAsciiDigit(first) || !isAsciiDigit(second)) return null;
      const value = (first - 0x30) * 10 + (second - 0x30);
      if (value > 59) return null;
      offsetPosition = pos + 2;
      return value;
    };
    const offsetHours = offsetDigits(false);
    if (offsetHours === null) invalid(text);
    const offsetMinutes = offsetDigits(true);
    if (offsetMinutes === null) invalid(text);
    const offsetSecondsPart = offsetDigits(true) ?? 0;
    // Java wirft hier eine Ausnahme statt eines Lesefehlers; abgelehnt wird der Text so oder so.
    if (offsetHours > 23) invalid(text);
    offsetSeconds = (offsetSign === 0x2d ? -1 : 1) * (offsetHours * 3600 + offsetMinutes * 60 + offsetSecondsPart);
    position = offsetPosition;
  }
  if (position !== length) invalid(text);

  // Umrechnung wie InstantPrinterParser.parse: 24:00:00 ist Mitternacht des Folgetags, 23:59:60 wird zu 23:59:59.
  let extraDays = 0;
  if (hour === 24 && minute === 0 && second === 0 && nano === 0) {
    hour = 0;
    extraDays = 1;
  } else if (hour === 23 && minute === 59 && second === 60) {
    second = 59;
  }
  const yearBig = BigInt(yearParsed);
  // Java: `(int) yearParsed % 10_000` – der Rest der Jahre kommt danach in ganzen 10 000 Jahren dazu.
  const year = Number(BigInt.asIntN(32, yearBig)) % 10_000;
  if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month)) invalid(text);
  if (hour > 23 || minute > 59 || second > 59) invalid(text);
  if (offsetSeconds < -64_800 || offsetSeconds > 64_800) invalid(text);
  const days = BigInt(epochDay(year, month, day) + extraDays);
  const secondOfDay = BigInt(hour * 3600 + minute * 60 + second);
  const epochSecond = days * SECONDS_PER_DAY + secondOfDay - BigInt(offsetSeconds) + (yearBig / 10_000n) * SECONDS_PER_10000_YEARS;
  if (epochSecond < INSTANT_MIN_SECOND || epochSecond > INSTANT_MAX_SECOND) invalid(text);
  return { epochSecond, nano };
}

/**
 * Liest einen Zeitpunkt wie die Android-App (Kotlin: `parseInstant`): wie `Instant.parse`, aber nur innerhalb des
 * Bereichs, der sich in Millisekunden ausdrücken lässt (rund 292 Millionen Jahre um 1970).
 * @returns die kanonische Schreibweise von `Instant.toString()`.
 * @throws InvalidInstantError wenn die Android-App den Text ablehnt.
 */
export function parseInstant(text: string): InstantText {
  const parts = parseInstantValue(text);
  if (compareParts(parts, EARLIEST) < 0 || compareParts(parts, LATEST) > 0) {
    throw new InvalidInstantError(`Der Zeitpunkt ${text} liegt außerhalb des Bereichs, mit dem die App rechnen kann.`);
  }
  return formatInstant(parts);
}

/** Wie {@link parseInstant}, aber `null` statt eines Fehlers (Kotlin: `instantOrNull` in BackupFormat). */
export function tryParseInstant(text: string): InstantText | null {
  try {
    return parseInstant(text);
  } catch (error) {
    if (error instanceof InvalidInstantError) return null;
    throw error;
  }
}

/**
 * Die Schreibweise, in der Kotlin diesen Zeitpunkt in eine Datei schreibt. Zum Schreiben eines Models: Ein Zeitpunkt
 * darf hier im ganzen Bereich von `java.time.Instant` liegen, so wie ihn auch die Android-App schreiben würde.
 * @throws InvalidInstantError wenn der Text kein Zeitpunkt ist.
 */
export function canonicalInstant(text: InstantText): InstantText {
  return formatInstant(parseInstantValue(text));
}

function pad(value: number, width: number): string {
  return String(value).padStart(width, "0");
}

/** `LocalDateTime.toString()` für eine Sekunde ohne Bruchteil, immer mit Sekunden („…:00“ wie in InstantPrinterParser). */
function localDateTimeText(epochSecond: bigint): { text: string; year: number } {
  const days = Number(floorDiv(epochSecond, SECONDS_PER_DAY));
  const secondOfDay = Number(floorMod(epochSecond, SECONDS_PER_DAY));
  const { year, month, day } = civilFromDays(days);
  let yearText: string;
  const absYear = Math.abs(year);
  if (absYear < 1000) {
    yearText = (year < 0 ? "-" : "") + pad(absYear, 4);
  } else {
    yearText = (year > 9999 ? "+" : "") + String(year);
  }
  const hour = Math.floor(secondOfDay / 3600);
  const minute = Math.floor((secondOfDay % 3600) / 60);
  const second = secondOfDay % 60;
  return { text: `${yearText}-${pad(month, 2)}-${pad(day, 2)}T${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}`, year };
}

/** Schreibt einen Zeitpunkt wie `Instant.toString()` (InstantPrinterParser.format von JDK 21). */
export function formatInstant(parts: InstantParts): InstantText {
  const inSecond = parts.epochSecond;
  let text: string;
  if (inSecond >= -SECONDS_0000_TO_1970) {
    const zeroSeconds = inSecond - SECONDS_PER_10000_YEARS + SECONDS_0000_TO_1970;
    const hi = floorDiv(zeroSeconds, SECONDS_PER_10000_YEARS) + 1n;
    const lo = floorMod(zeroSeconds, SECONDS_PER_10000_YEARS);
    text = (hi > 0n ? `+${hi}` : "") + localDateTimeText(lo - SECONDS_0000_TO_1970).text;
  } else {
    const zeroSeconds = inSecond + SECONDS_0000_TO_1970;
    const hi = zeroSeconds / SECONDS_PER_10000_YEARS;
    const lo = zeroSeconds % SECONDS_PER_10000_YEARS;
    const local = localDateTimeText(lo - SECONDS_0000_TO_1970);
    text = local.text;
    if (hi < 0n) {
      if (local.year === -10_000) {
        text = String(hi - 1n) + text.substring(2);
      } else if (lo === 0n) {
        text = String(hi) + text;
      } else {
        text = text.substring(0, 1) + String(-hi) + text.substring(1);
      }
    }
  }
  const nano = parts.nano;
  if (nano > 0) {
    const digits = pad(nano, 9);
    text += "." + (nano % 1_000_000 === 0 ? digits.substring(0, 3) : nano % 1000 === 0 ? digits.substring(0, 6) : digits);
  }
  return `${text}Z`;
}
