/*
 * Nachbau des JSON-Lesers von kotlinx.serialization 1.13.0 (`AbstractJsonLexer` und `StringJsonLexer`), mit dem die
 * Android-App ihre Dateien liest. Die Web-App muss genau dieselben Texte annehmen und ablehnen; JSON.parse taugt dafür
 * nicht, denn die Bibliothek weicht an vielen Stellen vom JSON-Standard ab, zum Beispiel:
 *
 * - Wahrheitswerte dürfen groß geschrieben und in Anführungszeichen stehen („TRUE“, "true"), ganze Zahlen ebenfalls in
 *   Anführungszeichen ("5") und mit Exponent (1e2); Kommazahlen werden abgelehnt.
 * - Steuerzeichen dürfen ungeschützt in Texten stehen.
 * - Werte unbekannter Felder werden ohne jede Prüfung von Kommas und Doppelpunkten übersprungen.
 *
 * Die Methoden folgen dem Kotlin-Quelltext Zeile für Zeile, auch in den Grenzfällen (Position -1 nach dem Ende,
 * Überlauf ganzer Zahlen wie bei `Long`). Fehler sind immer {@link JsonFormatError}.
 */
import { JsonFormatError } from "./errors";

// Zeichenklassen (Kotlin: TC_…)
export const TC_OTHER = 0;
export const TC_STRING = 1;
export const TC_STRING_ESC = 2;
export const TC_WHITESPACE = 3;
export const TC_COMMA = 4;
export const TC_COLON = 5;
export const TC_BEGIN_OBJ = 6;
export const TC_END_OBJ = 7;
export const TC_BEGIN_LIST = 8;
export const TC_END_LIST = 9;
export const TC_EOF = 10;
export const TC_INVALID = 127;

export const QUOTE = 0x22;
export const BACKSLASH = 0x5c;
export const COMMA = 0x2c;
export const COLON = 0x3a;
export const BEGIN_OBJ = 0x7b;
export const END_OBJ = 0x7d;
export const BEGIN_LIST = 0x5b;
export const END_LIST = 0x5d;

/** Grenze der Zeichentabelle (Kotlin: CTC_MAX); alle Zeichen ab hier gehören zur Klasse „anderes“. */
const CTC_MAX = 0x7e;
const CHAR_TO_TOKEN = new Uint8Array(CTC_MAX);
for (let code = 0; code <= 0x20; code++) CHAR_TO_TOKEN[code] = TC_INVALID;
CHAR_TO_TOKEN[0x09] = TC_WHITESPACE;
CHAR_TO_TOKEN[0x0a] = TC_WHITESPACE;
CHAR_TO_TOKEN[0x0d] = TC_WHITESPACE;
CHAR_TO_TOKEN[0x20] = TC_WHITESPACE;
CHAR_TO_TOKEN[COMMA] = TC_COMMA;
CHAR_TO_TOKEN[COLON] = TC_COLON;
CHAR_TO_TOKEN[BEGIN_OBJ] = TC_BEGIN_OBJ;
CHAR_TO_TOKEN[END_OBJ] = TC_END_OBJ;
CHAR_TO_TOKEN[BEGIN_LIST] = TC_BEGIN_LIST;
CHAR_TO_TOKEN[END_LIST] = TC_END_LIST;
CHAR_TO_TOKEN[QUOTE] = TC_STRING;
CHAR_TO_TOKEN[BACKSLASH] = TC_STRING_ESC;

/** Zeichenklasse eines UTF-16-Zeichens (Kotlin: `charToTokenClass`). */
export function charToTokenClass(code: number): number {
  return code < CTC_MAX ? CHAR_TO_TOKEN[code] : TC_OTHER;
}

/** Was hinter einem Rückstrich steht und welches Zeichen daraus wird (Kotlin: ESCAPE_2_CHAR); 0 heißt ungültig. */
const ESC2C_MAX = 0x75;
const ESCAPE_2_CHAR = new Uint16Array(ESC2C_MAX);
ESCAPE_2_CHAR[0x62] = 0x08; // \b
ESCAPE_2_CHAR[0x74] = 0x09; // \t
ESCAPE_2_CHAR[0x6e] = 0x0a; // \n
ESCAPE_2_CHAR[0x66] = 0x0c; // \f
ESCAPE_2_CHAR[0x72] = 0x0d; // \r
ESCAPE_2_CHAR[0x2f] = 0x2f; // \/
ESCAPE_2_CHAR[QUOTE] = QUOTE;
ESCAPE_2_CHAR[BACKSLASH] = BACKSLASH;

function escapeToChar(code: number): number {
  return code < ESC2C_MAX ? ESCAPE_2_CHAR[code] : 0;
}

function isWs(code: number): boolean {
  return code === 0x20 || code === 0x0a || code === 0x0d || code === 0x09;
}

function isValidValueStart(code: number): boolean {
  return code !== END_OBJ && code !== END_LIST && code !== COLON && code !== COMMA;
}

export const LONG_MIN = -(2n ** 63n);
export const LONG_MAX = 2n ** 63n - 1n;

/** 2⁶³ als Gleitkommazahl: so groß ist `Long.MAX_VALUE`, wenn Kotlin es mit einer Gleitkommazahl vergleicht. */
const TWO_POW_63 = 2 ** 63;

/** Der Lexer über einem ganzen Text (Kotlin: `StringJsonLexer`, ohne Kommentare, nicht nachsichtig). */
export class KotlinxLexer {
  readonly source: string;
  /** Lese-Position; -1 nach dem Ende des Textes, genau wie in Kotlin. */
  currentPosition = 0;
  private peekedString: string | null = null;
  private escaped: string[] = [];

  constructor(source: string) {
    this.source = source;
  }

  fail(message: string): never {
    throw new JsonFormatError(`${message} (Stelle ${this.currentPosition})`);
  }

  private prefetchOrEof(position: number): number {
    return position < this.source.length ? position : -1;
  }

  /** Nächstes Zeichen nach Leerraum, verbraucht (Kotlin: `consumeNextToken()`). */
  consumeNextToken(): number {
    const source = this.source;
    let cpos = this.currentPosition;
    while (cpos !== -1 && cpos < source.length) {
      const code = source.charCodeAt(cpos++);
      if (isWs(code)) continue;
      this.currentPosition = cpos;
      return charToTokenClass(code);
    }
    this.currentPosition = source.length;
    return TC_EOF;
  }

  /** Kotlin: `consumeNextToken(expected: Byte)`. */
  consumeNextTokenOf(expected: number): number {
    const token = this.consumeNextToken();
    if (token !== expected) this.fail(`Zeichenklasse ${expected} erwartet, ${token} gefunden`);
    return token;
  }

  /** Kotlin: `consumeNextToken(expected: Char)`. */
  consumeNextChar(expected: number): void {
    if (this.currentPosition === -1) this.unexpectedToken(expected);
    const source = this.source;
    let cpos = this.currentPosition;
    while (cpos < source.length) {
      const code = source.charCodeAt(cpos++);
      if (isWs(code)) continue;
      this.currentPosition = cpos;
      if (code === expected) return;
      this.unexpectedToken(expected);
    }
    this.currentPosition = -1;
    this.unexpectedToken(expected);
  }

  private unexpectedToken(expected: number): never {
    this.fail(`'${String.fromCharCode(expected)}' erwartet`);
  }

  peekNextToken(): number {
    const source = this.source;
    let cpos = this.currentPosition;
    for (;;) {
      cpos = this.prefetchOrEof(cpos);
      if (cpos === -1) break;
      const code = source.charCodeAt(cpos);
      if (isWs(code)) {
        ++cpos;
        continue;
      }
      this.currentPosition = cpos;
      return charToTokenClass(code);
    }
    this.currentPosition = cpos;
    return TC_EOF;
  }

  tryConsumeComma(): boolean {
    const current = this.skipWhitespaces();
    if (current >= this.source.length || current === -1) return false;
    if (this.source.charCodeAt(current) === COMMA) {
      ++this.currentPosition;
      return true;
    }
    return false;
  }

  canConsumeValue(): boolean {
    let current = this.currentPosition;
    if (current === -1) return false;
    const source = this.source;
    while (current < source.length) {
      const code = source.charCodeAt(current);
      if (isWs(code)) {
        ++current;
        continue;
      }
      this.currentPosition = current;
      return isValidValueStart(code);
    }
    this.currentPosition = current;
    return false;
  }

  skipWhitespaces(): number {
    let current = this.currentPosition;
    if (current === -1) return current;
    const source = this.source;
    while (current < source.length) {
      if (isWs(source.charCodeAt(current))) ++current;
      else break;
    }
    this.currentPosition = current;
    return current;
  }

  expectEof(): void {
    if (this.consumeNextToken() !== TC_EOF) this.fail("Nach dem Ende des JSON-Werts folgt noch etwas");
  }

  /** Verbraucht ein `null`, wenn eines folgt (Kotlin: `tryConsumeNull`). */
  tryConsumeNull(): boolean {
    let current = this.skipWhitespaces();
    current = this.prefetchOrEof(current);
    const length = this.source.length - current;
    if (length < 4 || current === -1) return false;
    for (let i = 0; i < 4; i++) {
      if ("null".charCodeAt(i) !== this.source.charCodeAt(current + i)) return false;
    }
    if (length > 4 && charToTokenClass(this.source.charCodeAt(current + 4)) === TC_OTHER) return false;
    this.currentPosition = current + 4;
    return true;
  }

  private peekString(): string | null {
    const token = this.peekNextToken();
    if (token !== TC_STRING) return null;
    const string = this.consumeString();
    this.peekedString = string;
    return string;
  }

  consumeString(): string {
    if (this.peekedString !== null) {
      const peeked = this.peekedString;
      this.peekedString = null;
      return peeked;
    }
    return this.consumeKeyString();
  }

  /** Ein Text in Anführungszeichen (Kotlin: `consumeKeyString`), mit schnellem Weg ohne Rückstrich. */
  consumeKeyString(): string {
    this.consumeNextChar(QUOTE);
    const current = this.currentPosition;
    const closingQuote = this.source.indexOf('"', current);
    if (closingQuote === -1) {
      this.consumeStringLenient();
      this.fail("Schließendes Anführungszeichen fehlt");
    }
    for (let i = current; i < closingQuote; i++) {
      if (this.source.charCodeAt(i) === BACKSLASH) return this.consumeStringWithEscapes(this.currentPosition, i);
    }
    this.currentPosition = closingQuote + 1;
    return this.source.substring(current, closingQuote);
  }

  /** Kotlin: `consumeString(source, startPosition, current)`. */
  private consumeStringWithEscapes(startPosition: number, current: number): string {
    const source = this.source;
    let currentPosition = current;
    let lastPosition = startPosition;
    let code = source.charCodeAt(currentPosition);
    let usedAppend = false;
    while (code !== QUOTE) {
      if (code === BACKSLASH) {
        usedAppend = true;
        currentPosition = this.prefetchOrEof(this.appendEscape(lastPosition, currentPosition));
        if (currentPosition === -1) this.fail("Unerwartetes Ende");
        lastPosition = currentPosition;
      } else if (++currentPosition >= source.length) {
        usedAppend = true;
        this.appendRange(lastPosition, currentPosition);
        currentPosition = this.prefetchOrEof(currentPosition);
        if (currentPosition === -1) this.fail("Unerwartetes Ende");
        lastPosition = currentPosition;
      }
      code = source.charCodeAt(currentPosition);
    }
    const string = usedAppend ? this.decodedString(lastPosition, currentPosition) : source.substring(lastPosition, currentPosition);
    this.currentPosition = currentPosition + 1;
    return string;
  }

  private appendRange(fromIndex: number, toIndex: number): void {
    this.escaped.push(this.source.substring(fromIndex, toIndex));
  }

  private decodedString(lastPosition: number, currentPosition: number): string {
    this.appendRange(lastPosition, currentPosition);
    const result = this.escaped.join("");
    this.escaped = [];
    return result;
  }

  private appendEscape(lastPosition: number, current: number): number {
    this.appendRange(lastPosition, current);
    return this.appendEsc(current + 1);
  }

  private appendEsc(startPosition: number): number {
    let currentPosition = this.prefetchOrEof(startPosition);
    if (currentPosition === -1) this.fail("Escape-Folge bricht am Ende ab");
    const currentChar = this.source.charCodeAt(currentPosition++);
    if (currentChar === 0x75) return this.appendHex(currentPosition);
    const char = escapeToChar(currentChar);
    if (char === 0) this.fail(`Ungültige Escape-Folge '\\${String.fromCharCode(currentChar)}'`);
    this.escaped.push(String.fromCharCode(char));
    return currentPosition;
  }

  private appendHex(startPos: number): number {
    if (startPos + 4 >= this.source.length) {
      this.currentPosition = startPos;
      this.fail("Unerwartetes Ende in einer Unicode-Escape-Folge");
    }
    const code =
      (this.fromHexChar(startPos) << 12) +
      (this.fromHexChar(startPos + 1) << 8) +
      (this.fromHexChar(startPos + 2) << 4) +
      this.fromHexChar(startPos + 3);
    this.escaped.push(String.fromCharCode(code));
    return startPos + 4;
  }

  private fromHexChar(position: number): number {
    const code = this.source.charCodeAt(position);
    if (code >= 0x30 && code <= 0x39) return code - 0x30;
    if (code >= 0x61 && code <= 0x66) return code - 0x61 + 10;
    if (code >= 0x41 && code <= 0x46) return code - 0x41 + 10;
    return this.fail("Ungültige Hex-Ziffer in einer Unicode-Escape-Folge");
  }

  /** Ein Wert ohne Anführungszeichen bis zum nächsten Trennzeichen, oder ein Text (Kotlin: `consumeStringLenient`). */
  consumeStringLenient(): string {
    if (this.peekedString !== null) return this.consumeString();
    let current = this.skipWhitespaces();
    const source = this.source;
    if (current >= source.length || current === -1) this.fail("Unerwartetes Ende");
    const token = charToTokenClass(source.charCodeAt(current));
    if (token === TC_STRING) return this.consumeString();
    if (token !== TC_OTHER) this.fail("Anfang eines Werts erwartet");
    let usedAppend = false;
    while (charToTokenClass(source.charCodeAt(current)) === TC_OTHER) {
      ++current;
      if (current >= source.length) {
        usedAppend = true;
        this.appendRange(this.currentPosition, current);
        const eof = this.prefetchOrEof(current);
        if (eof === -1) {
          this.currentPosition = current;
          return this.decodedString(0, 0);
        }
        current = eof;
      }
    }
    const result = usedAppend ? this.decodedString(this.currentPosition, current) : source.substring(this.currentPosition, current);
    this.currentPosition = current;
    return result;
  }

  /**
   * Eine ganze Zahl wie `Long` (Kotlin: `consumeNumericLiteral`): wahlweise in Anführungszeichen, mit Exponent, ohne
   * Nachkommastellen. Gerechnet wird wie in Kotlin mit 64-Bit-Überlauf, der Exponent mit Gleitkommazahlen.
   */
  consumeNumericLiteral(): bigint {
    const source = this.source;
    let current = this.skipWhitespaces();
    current = this.prefetchOrEof(current);
    if (current >= source.length || current === -1) this.fail("Unerwartetes Ende");
    let hasQuotation = false;
    if (source.charCodeAt(current) === QUOTE) {
      if (++current === source.length) this.fail("Unerwartetes Ende");
      hasQuotation = true;
    }
    let accumulator = 0n;
    let exponentAccumulator = 0n;
    let isNegative = false;
    let isExponentPositive = false;
    let hasExponent = false;
    const start = current;
    while (current !== source.length) {
      const code = source.charCodeAt(current);
      if ((code === 0x65 || code === 0x45) && !hasExponent) {
        if (current === start) this.fail("Unerwartetes Zeichen in einer Zahl");
        isExponentPositive = true;
        hasExponent = true;
        ++current;
        continue;
      }
      if (code === 0x2d && hasExponent) {
        if (current === start) this.fail("Unerwartetes Zeichen in einer Zahl");
        isExponentPositive = false;
        ++current;
        continue;
      }
      if (code === 0x2b && hasExponent) {
        if (current === start) this.fail("Unerwartetes Zeichen in einer Zahl");
        isExponentPositive = true;
        ++current;
        continue;
      }
      if (code === 0x2d) {
        if (current !== start) this.fail("Unerwartetes Zeichen in einer Zahl");
        isNegative = true;
        ++current;
        continue;
      }
      if (charToTokenClass(code) !== TC_OTHER) break;
      ++current;
      const digit = code - 0x30;
      if (digit < 0 || digit > 9) this.fail("Unerwartetes Zeichen in einer Zahl");
      if (hasExponent) {
        exponentAccumulator = BigInt.asIntN(64, exponentAccumulator * 10n + BigInt(digit));
        continue;
      }
      accumulator = BigInt.asIntN(64, accumulator * 10n - BigInt(digit));
      if (accumulator > 0n) this.fail("Überlauf einer Zahl");
    }
    const hasChars = current !== start;
    if (start === current || (isNegative && start === current - 1)) this.fail("Zahl erwartet");
    if (hasQuotation) {
      if (!hasChars) this.fail("Unerwartetes Ende");
      // In Kotlin führt das fehlende Zeichen am Ende zu einem Laufzeitfehler; auch das ist ein Lesefehler.
      if (current >= source.length || source.charCodeAt(current) !== QUOTE) this.fail("Schließendes Anführungszeichen erwartet");
      ++current;
    }
    this.currentPosition = current;
    if (hasExponent) {
      const exponent = Number(exponentAccumulator);
      const factor = isExponentPositive ? Math.pow(10, exponent) : Math.pow(10, -exponent);
      const doubleAccumulator = Number(accumulator) * factor;
      // Kotlin vergleicht mit Long.MAX_VALUE und Long.MIN_VALUE als Gleitkommazahl, also mit ±2⁶³.
      if (doubleAccumulator > TWO_POW_63 || doubleAccumulator < -TWO_POW_63) this.fail("Überlauf einer Zahl");
      if (Math.floor(doubleAccumulator) !== doubleAccumulator) this.fail("Keine ganze Zahl");
      accumulator = doubleToLong(doubleAccumulator);
    }
    if (isNegative) return accumulator;
    if (accumulator !== LONG_MIN) return -accumulator;
    return this.fail("Überlauf einer Zahl");
  }

  /** Die ganze Zahl muss den ganzen Text ausmachen (Kotlin: `consumeNumericLiteralFully`). */
  consumeNumericLiteralFully(): bigint {
    const result = this.consumeNumericLiteral();
    if (this.consumeNextToken() !== TC_EOF) this.fail("Nach der Zahl folgt noch etwas");
    return result;
  }

  /** Ein Wahrheitswert, wahlweise in Anführungszeichen und in beliebiger Groß- und Kleinschreibung (ASCII). */
  consumeBooleanLenient(): boolean {
    const source = this.source;
    let current = this.skipWhitespaces();
    if (current === source.length || current === -1) this.fail("Unerwartetes Ende");
    let hasQuotation = false;
    if (source.charCodeAt(current) === QUOTE) {
      ++current;
      hasQuotation = true;
    }
    const result = this.consumeBooleanAt(current);
    if (hasQuotation) {
      if (this.currentPosition === source.length) this.fail("Unerwartetes Ende");
      if (source.charCodeAt(this.currentPosition) !== QUOTE) this.fail("Schließendes Anführungszeichen erwartet");
      ++this.currentPosition;
    }
    return result;
  }

  private consumeBooleanAt(start: number): boolean {
    let current = this.prefetchOrEof(start);
    if (current >= this.source.length || current === -1) this.fail("Unerwartetes Ende");
    // Groß- und Kleinbuchstaben unterscheiden sich in ASCII nur im sechsten Bit.
    const first = this.source.charCodeAt(current++) | 0x20;
    if (first === 0x74) {
      this.consumeBooleanLiteral("rue", current);
      return true;
    }
    if (first === 0x66) {
      this.consumeBooleanLiteral("alse", current);
      return false;
    }
    return this.fail("Wahrheitswert erwartet");
  }

  private consumeBooleanLiteral(suffix: string, current: number): void {
    if (this.source.length - current < suffix.length) this.fail("Wahrheitswert bricht ab");
    for (let i = 0; i < suffix.length; i++) {
      if (suffix.charCodeAt(i) !== (this.source.charCodeAt(current + i) | 0x20)) this.fail("Wahrheitswert erwartet");
    }
    this.currentPosition = current + suffix.length;
  }

  /**
   * Überspringt den Wert eines unbekannten Felds (Kotlin: `skipElement`). In Listen und Objekten werden nur die
   * Klammern gezählt und Texte gelesen; Kommas, Doppelpunkte und Werte ohne Anführungszeichen prüft Kotlin dabei nicht.
   */
  skipElement(): void {
    const tokenStack: number[] = [];
    let lastToken = this.peekNextToken();
    if (lastToken !== TC_BEGIN_LIST && lastToken !== TC_BEGIN_OBJ) {
      this.consumeStringLenient();
      return;
    }
    for (;;) {
      lastToken = this.peekNextToken();
      if (lastToken === TC_STRING) {
        this.consumeKeyString();
        continue;
      }
      switch (lastToken) {
        case TC_BEGIN_LIST:
        case TC_BEGIN_OBJ:
          tokenStack.push(lastToken);
          break;
        case TC_END_LIST:
          if (tokenStack[tokenStack.length - 1] !== TC_BEGIN_LIST) this.fail("']' statt '}' gefunden");
          tokenStack.pop();
          break;
        case TC_END_OBJ:
          if (tokenStack[tokenStack.length - 1] !== TC_BEGIN_OBJ) this.fail("'}' statt ']' gefunden");
          tokenStack.pop();
          break;
        case TC_EOF:
          this.fail("Unerwartetes Ende beim Überspringen eines unbekannten Felds");
      }
      this.consumeNextToken();
      if (tokenStack.length === 0) return;
    }
  }

  /**
   * Der Text unter dem Schlüssel [keyToMatch], wenn er der erste Schlüssel des folgenden Objekts ist; die Position
   * bleibt unverändert (Kotlin: `peekLeadingMatchingValue`).
   */
  peekLeadingMatchingValue(keyToMatch: string): string | null {
    const positionSnapshot = this.currentPosition;
    try {
      if (this.consumeNextToken() !== TC_BEGIN_OBJ) return null;
      const firstKey = this.peekString();
      if (firstKey !== keyToMatch) return null;
      this.peekedString = null;
      if (this.consumeNextToken() !== TC_COLON) return null;
      return this.peekString();
    } finally {
      this.currentPosition = positionSnapshot;
      this.peekedString = null;
    }
  }
}

/** Kotlins `Double.toLong()`: abgeschnitten, am Rand des Bereichs von `Long` festgehalten. */
function doubleToLong(value: number): bigint {
  if (Number.isNaN(value)) return 0n;
  if (value >= TWO_POW_63) return LONG_MAX;
  if (value <= -TWO_POW_63) return LONG_MIN;
  return BigInt(Math.trunc(value));
}
