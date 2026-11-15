/*
 * Textvergleiche genau wie in Kotlin auf der JVM. JavaScript kennt andere Leerzeichen (`trim` entfernt zum Beispiel
 * auch U+FEFF, Kotlin dagegen U+001C bis U+001F) und vergleicht ohne Groß- und Kleinschreibung nur über ganze Texte
 * (`"ß".toUpperCase()` ist „SS“). Die Android-App vergleicht Zeichen für Zeichen mit den einfachen Zuordnungen von
 * Unicode (`String.equalsIgnoreCase`).
 */

/**
 * Kotlins `Char.isWhitespace()` auf der JVM: `Character.isWhitespace` oder `Character.isSpaceChar`, also Tabulator,
 * Zeilenumbrüche, U+001C bis U+001F und alle Leer- und Trennzeichen von Unicode (Zs, Zl, Zp) einschließlich der
 * geschützten Leerzeichen.
 */
export function isKotlinWhitespace(code: number): boolean {
  if (code <= 0x20) return code === 0x20 || (code >= 0x09 && code <= 0x0d) || (code >= 0x1c && code <= 0x1f);
  if (code < 0x00a0) return false;
  return (
    code === 0x00a0 ||
    code === 0x1680 ||
    (code >= 0x2000 && code <= 0x200a) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x202f ||
    code === 0x205f ||
    code === 0x3000
  );
}

/** Kotlins `String.isBlank()`: leer oder nur aus Leerraum. */
export function kotlinIsBlank(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    if (!isKotlinWhitespace(text.charCodeAt(i))) return false;
  }
  return true;
}

/** Kotlins `String.trim()`: Leerraum an beiden Enden entfernt. */
export function kotlinTrim(text: string): string {
  let start = 0;
  let end = text.length;
  while (start < end && isKotlinWhitespace(text.charCodeAt(start))) start++;
  while (end > start && isKotlinWhitespace(text.charCodeAt(end - 1))) end--;
  return text.substring(start, end);
}

/**
 * Großbuchstaben, für die JavaScript mehrere Zeichen liefert, Java aber ein einzelnes (die griechischen Buchstaben mit
 * Ypogegrammeni werden zur Titelform). Alle anderen Zeichen mit mehrteiliger Großschreibung bleiben in Java unverändert.
 */
const SIMPLE_UPPER_OVERRIDES = new Map<number, number>([
  [0x1fb3, 0x1fbc],
  [0x1fc3, 0x1fcc],
  [0x1ff3, 0x1ffc],
]);
for (const start of [0x1f80, 0x1f90, 0x1fa0]) {
  for (let offset = 0; offset < 8; offset++) SIMPLE_UPPER_OVERRIDES.set(start + offset, start + offset + 8);
}

function singleCodePoint(text: string, fallback: number): number {
  const first = text.codePointAt(0);
  if (first === undefined) return fallback;
  return text.length === (first > 0xffff ? 2 : 1) ? first : fallback;
}

/** Javas `Character.toUpperCase(int)`: die einfache Großschreibung eines Zeichens. */
function simpleUpperCase(codePoint: number): number {
  const override = SIMPLE_UPPER_OVERRIDES.get(codePoint);
  if (override !== undefined) return override;
  return singleCodePoint(String.fromCodePoint(codePoint).toUpperCase(), codePoint);
}

/** Javas `Character.toLowerCase(int)`: die einfache Kleinschreibung eines Zeichens. */
function simpleLowerCase(codePoint: number): number {
  if (codePoint === 0x0130) return 0x0069;
  return singleCodePoint(String.fromCodePoint(codePoint).toLowerCase(), codePoint);
}

/** Vergleich zweier Zeichen ohne Groß- und Kleinschreibung wie in Java (`compareCodePointCI`); 0 heißt gleich. */
function compareCodePointCI(first: number, second: number): number {
  let a = simpleUpperCase(first);
  let b = simpleUpperCase(second);
  if (a !== b) {
    a = simpleLowerCase(a);
    b = simpleLowerCase(b);
    if (a !== b) return a - b;
  }
  return 0;
}

function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff;
}

/**
 * Das Zeichen an [index], mit dem Partner eines Ersatzzeichenpaars zusammengesetzt (Java: `codePointIncluding`).
 * Negativ, wenn hier ein hohes Ersatzzeichen mit folgendem niedrigen steht (dann wird eine Stelle mehr verbraucht).
 */
function codePointIncluding(text: string, code: number, index: number, end: number): number {
  if (!isHighSurrogate(code) && !isLowSurrogate(code)) return code;
  if (isLowSurrogate(code)) {
    if (index > 0) {
      const previous = text.charCodeAt(index - 1);
      if (isHighSurrogate(previous)) return (previous - 0xd800) * 0x400 + (code - 0xdc00) + 0x10000;
    }
  } else if (index + 1 < end) {
    const next = text.charCodeAt(index + 1);
    if (isLowSurrogate(next)) return -((code - 0xd800) * 0x400 + (next - 0xdc00) + 0x10000);
  }
  return code;
}

/** Javas `String.equalsIgnoreCase` (JDK 21), das Kotlins `equals(other, ignoreCase = true)` auf der JVM benutzt. */
export function javaEqualsIgnoreCase(first: string, second: string): boolean {
  if (first === second) return true;
  if (first.length !== second.length) return false;
  const end = first.length;
  for (let k1 = 0, k2 = 0; k1 < end && k2 < end; k1++, k2++) {
    let cp1 = first.charCodeAt(k1);
    let cp2 = second.charCodeAt(k2);
    if (cp1 === cp2 || compareCodePointCI(cp1, cp2) === 0) continue;
    cp1 = codePointIncluding(first, cp1, k1, end);
    if (cp1 < 0) {
      k1++;
      cp1 = -cp1;
    }
    cp2 = codePointIncluding(second, cp2, k2, end);
    if (cp2 < 0) {
      k2++;
      cp2 = -cp2;
    }
    if (compareCodePointCI(cp1, cp2) !== 0) return false;
  }
  return true;
}

/** Wahrheitswert aus einem Text wie in kotlinx.serialization (`toBooleanStrictOrNull`): ohne Groß- und Kleinschreibung. */
export function booleanOrNull(content: string): boolean | null {
  if (javaEqualsIgnoreCase(content, "true")) return true;
  if (javaEqualsIgnoreCase(content, "false")) return false;
  return null;
}
