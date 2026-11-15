/*
 * Lesen und Schreiben nach einem Bauplan der Dateiklassen, genau wie die von kotlinx.serialization erzeugten
 * Serialisierer der Android-App (Dtos.kt) mit den Einstellungen von StorageCodec: unbekannte Felder werden überlesen,
 * Standardwerte geschrieben, leere (null) Angaben weggelassen.
 *
 * Gelesen wird auf zwei Wegen, weil auch Kotlin zwei hat, die sich in Grenzfällen unterscheiden:
 * - als Strom direkt aus dem Text (`StreamingJsonDecoder`; eine Datei der App), und
 * - aus einem schon zerlegten Baum (`JsonTreeDecoder`; ein Abend in einer Sicherung). Diesen Weg nimmt Kotlin auch
 *   im Strom für einen Eintrag, dessen Feld `type` nicht vorn steht.
 * Zum Beispiel nimmt der Strom `"declarerWon":"TRUE"` und `"laufende":"3"` an, aber nicht `"laufende":" 3"`; der
 * Baum nimmt `" 3"` an, verlangt Texte aber immer in Anführungszeichen.
 */
import { JsonFormatError } from "./errors";
import { type JsonElement, JsonTreeReader } from "./jsonTree";
import { BACKSLASH, BEGIN_LIST, BEGIN_OBJ, COLON, END_LIST, END_OBJ, KotlinxLexer, QUOTE, TC_COMMA } from "./kotlinxLexer";
import { booleanOrNull } from "./kotlinText";

// ---------- Bauplan ----------

export type FieldType =
  | { readonly kind: "string" }
  | { readonly kind: "int" }
  | { readonly kind: "boolean" }
  | { readonly kind: "enum"; readonly names: readonly string[] }
  | { readonly kind: "nullable"; readonly inner: FieldType }
  | { readonly kind: "list"; readonly element: FieldType }
  | { readonly kind: "map"; readonly key: FieldType; readonly value: FieldType }
  | { readonly kind: "class"; readonly schema: ClassSchema }
  | SealedType;

/** Eine versiegelte Klasse mit Unterklassen; welche gemeint ist, steht im Feld [discriminator]. */
export interface SealedType {
  readonly kind: "sealed";
  readonly discriminator: string;
  readonly subclasses: ReadonlyMap<string, ClassSchema>;
}

/** Ein Feld einer Dateiklasse; ohne [defaultValue] ist es Pflicht. */
export interface FieldSchema {
  readonly name: string;
  readonly type: FieldType;
  readonly defaultValue?: () => unknown;
}

export interface ClassSchema {
  readonly name: string;
  readonly fields: readonly FieldSchema[];
}

/** Ein gelesener Wert: Texte, Zahlen, Wahrheitswerte, `null`, Listen, `Map` (Tabellen) und Objekte (Klassen). */
export type DecodedObject = Readonly<Record<string, unknown>>;

function fail(message: string): never {
  throw new JsonFormatError(message);
}

function fieldIndex(schema: ClassSchema, key: string): number {
  return schema.fields.findIndex((field) => field.name === key);
}

/** Ergänzt fehlende Felder um ihren Standardwert; fehlt ein Pflichtfeld, ist das ein Lesefehler (Kotlin: MissingFieldException). */
function complete(schema: ClassSchema, values: ReadonlyMap<string, unknown>): DecodedObject {
  const result: Record<string, unknown> = {};
  for (const field of schema.fields) {
    if (values.has(field.name)) {
      result[field.name] = values.get(field.name);
    } else if (field.defaultValue !== undefined) {
      result[field.name] = field.defaultValue();
    } else {
      fail(`Pflichtfeld '${field.name}' fehlt in ${schema.name}`);
    }
  }
  return result;
}

const INT_MIN = -2_147_483_648n;
const INT_MAX = 2_147_483_647n;

// ---------- Lesen als Strom ----------

/** Kotlin: `StreamingJsonDecoder` mit `Json { ignoreUnknownKeys = true; explicitNulls = false }`. */
class StreamingDecoder {
  constructor(private readonly lexer: KotlinxLexer) {}

  /** @param depth Tiefe des umgebenden Bauteils (Kotlin: `lexer.path.currentDepth`). */
  decode(type: FieldType, depth: number): unknown {
    const lexer = this.lexer;
    switch (type.kind) {
      case "string":
        return lexer.consumeString();
      case "int": {
        const value = lexer.consumeNumericLiteral();
        if (value < INT_MIN || value > INT_MAX) lexer.fail("Zahl außerhalb des Bereichs von Int");
        return Number(value);
      }
      case "boolean":
        return lexer.consumeBooleanLenient();
      case "enum": {
        const name = lexer.consumeString();
        if (!type.names.includes(name)) lexer.fail(`Unbekannter Wert '${name}'`);
        return name;
      }
      case "nullable":
        return lexer.tryConsumeNull() ? null : this.decode(type.inner, depth);
      case "list":
        return this.decodeList(type.element, depth);
      case "map":
        return this.decodeMap(type.key, type.value, depth);
      case "class":
        return this.decodeClass(type.schema, depth);
      case "sealed":
        return this.decodeSealed(type, depth);
    }
  }

  /** beginStructure: öffnende Klammer, kein Komma direkt dahinter. */
  private begin(open: number): void {
    this.lexer.consumeNextChar(open);
    if (this.lexer.peekNextToken() === TC_COMMA) this.lexer.fail("Komma am Anfang");
  }

  /** endStructure: kein Komma am Ende, dann die schließende Klammer. */
  private end(close: number): void {
    if (this.lexer.tryConsumeComma()) this.lexer.fail("Komma am Ende");
    this.lexer.consumeNextChar(close);
  }

  private decodeClass(schema: ClassSchema, depth: number): DecodedObject {
    const lexer = this.lexer;
    this.begin(BEGIN_OBJ);
    const values = new Map<string, unknown>();
    for (;;) {
      // Kotlin: decodeObjectIndex. Ein fehlendes Komma zwischen zwei Feldern fällt dabei nicht auf.
      let hasComma = lexer.tryConsumeComma();
      let index = -1;
      while (lexer.canConsumeValue()) {
        hasComma = false;
        const key = lexer.consumeKeyString();
        lexer.consumeNextChar(COLON);
        index = fieldIndex(schema, key);
        if (index !== -1) break;
        lexer.skipElement();
        hasComma = lexer.tryConsumeComma();
      }
      if (index === -1) {
        if (hasComma) lexer.fail("Komma am Ende");
        break;
      }
      const field = schema.fields[index];
      values.set(field.name, this.decode(field.type, depth + 1));
    }
    this.end(END_OBJ);
    return complete(schema, values);
  }

  private decodeList(element: FieldType, depth: number): unknown[] {
    const lexer = this.lexer;
    this.begin(BEGIN_LIST);
    const result: unknown[] = [];
    let currentIndex = -1;
    for (;;) {
      const hasComma = lexer.tryConsumeComma();
      if (!lexer.canConsumeValue()) {
        if (hasComma) lexer.fail("Komma am Ende einer Liste");
        break;
      }
      if (currentIndex !== -1 && !hasComma) lexer.fail("Ende der Liste oder Komma erwartet");
      ++currentIndex;
      result.push(this.decode(element, depth + 1));
    }
    this.end(END_LIST);
    return result;
  }

  private decodeMap(keyType: FieldType, valueType: FieldType, depth: number): Map<string, unknown> {
    const lexer = this.lexer;
    this.begin(BEGIN_OBJ);
    const result = new Map<string, unknown>();
    let currentIndex = -1;
    // Kotlin: decodeMapIndex; Schlüssel und Wert wechseln sich ab.
    const nextIndex = (): number => {
      let hasComma = false;
      const decodingKey = currentIndex % 2 !== 0;
      if (decodingKey) {
        if (currentIndex !== -1) hasComma = lexer.tryConsumeComma();
      } else {
        lexer.consumeNextChar(COLON);
      }
      if (lexer.canConsumeValue()) {
        if (decodingKey) {
          if (currentIndex === -1) {
            if (hasComma) lexer.fail("Komma am Anfang");
          } else if (!hasComma) {
            lexer.fail("Komma nach einem Paar aus Schlüssel und Wert erwartet");
          }
        }
        return ++currentIndex;
      }
      if (hasComma) lexer.fail("Komma am Ende");
      return -1;
    };
    for (;;) {
      const index = nextIndex();
      if (index === -1) break;
      const key = this.decode(keyType, depth + 1) as string;
      if (nextIndex() !== index + 1) fail("Auf einen Schlüssel muss ein Wert folgen");
      result.set(key, this.decode(valueType, depth + 2));
    }
    this.end(END_OBJ);
    return result;
  }

  /**
   * Steht `type` als erstes Feld da und ist ein Text, liest Kotlin den Eintrag gleich als Strom; sonst liest es ihn
   * erst als Baum und übersetzt den Baum (Kotlin: `decodeSerializableValue` mit `peekLeadingMatchingValue`).
   */
  private decodeSealed(type: SealedType, depth: number): DecodedObject {
    const typeName = this.lexer.peekLeadingMatchingValue(type.discriminator);
    if (typeName === null) return decodeSealedTree(type, new JsonTreeReader(this.lexer, depth).read());
    const schema = type.subclasses.get(typeName);
    if (schema === undefined) return this.lexer.fail(`Unbekannte Art '${typeName}'`);
    return { ...this.decodeClass(schema, depth), [type.discriminator]: typeName };
  }
}

/** Liest einen ganzen Text als Strom (Kotlin: `Json.decodeFromString`). @throws JsonFormatError */
export function decodeStreaming(text: string, type: FieldType): unknown {
  const lexer = new KotlinxLexer(text);
  const value = new StreamingDecoder(lexer).decode(type, -1);
  lexer.expectEof();
  return value;
}

// ---------- Lesen aus dem Baum ----------

function primitiveContent(element: JsonElement, what: string): string {
  if (element.kind === "null") return "null";
  if (element.kind !== "literal") return fail(`${what} erwartet, aber ${element.kind === "object" ? "ein Objekt" : "eine Liste"} gefunden`);
  return element.content;
}

/** Kotlin: `JsonTreeDecoder` und seine Verwandten. @throws JsonFormatError */
export function decodeTree(type: FieldType, element: JsonElement): unknown {
  switch (type.kind) {
    case "string":
      if (element.kind === "null") fail("Text erwartet, aber null gefunden");
      if (element.kind !== "literal") return fail("Text erwartet");
      if (!element.isString) fail("Text ohne Anführungszeichen");
      return element.content;
    case "int": {
      const content = primitiveContent(element, "Zahl");
      let value: bigint;
      try {
        value = new KotlinxLexer(content).consumeNumericLiteralFully();
      } catch {
        return fail(`'${content}' ist keine ganze Zahl`);
      }
      if (value < INT_MIN || value > INT_MAX) fail(`'${content}' liegt außerhalb des Bereichs von Int`);
      return Number(value);
    }
    case "boolean": {
      const content = primitiveContent(element, "Wahrheitswert");
      const value = booleanOrNull(content);
      if (value === null) fail(`'${content}' ist kein Wahrheitswert`);
      return value;
    }
    case "enum": {
      const content = primitiveContent(element, "Aufzählungswert");
      if (!type.names.includes(content)) fail(`Unbekannter Wert '${content}'`);
      return content;
    }
    case "nullable":
      return element.kind === "null" ? null : decodeTree(type.inner, element);
    case "list":
      if (element.kind !== "array") return fail("Liste erwartet");
      return element.items.map((item) => decodeTree(type.element, item));
    case "map": {
      if (element.kind !== "object") return fail("Objekt erwartet");
      const result = new Map<string, unknown>();
      for (const [key, value] of element.entries) {
        const decodedKey = decodeTree(type.key, { kind: "literal", content: key, isString: true }) as string;
        result.set(decodedKey, decodeTree(type.value, value));
      }
      return result;
    }
    case "class":
      return decodeTreeClass(type.schema, element);
    case "sealed":
      return decodeSealedTree(type, element);
  }
}

function decodeTreeClass(schema: ClassSchema, element: JsonElement): DecodedObject {
  if (element.kind !== "object") return fail(`Objekt für ${schema.name} erwartet`);
  const values = new Map<string, unknown>();
  for (const [key, value] of element.entries) {
    const index = fieldIndex(schema, key);
    if (index === -1) continue; // unbekanntes Feld oder die Art des Eintrags
    values.set(key, decodeTree(schema.fields[index].type, value));
  }
  return complete(schema, values);
}

/**
 * Kotlin: `decodeSerializableValuePolymorphic`. Die Art steht unter [discriminator]; ein doppelter Schlüssel zählt mit
 * seinem letzten Wert, und auch ein Wert ohne Anführungszeichen wird angenommen.
 */
function decodeSealedTree(type: SealedType, element: JsonElement): DecodedObject {
  if (element.kind !== "object") return fail("Objekt erwartet");
  const typeElement = element.entries.get(type.discriminator);
  let typeName: string | null = null;
  if (typeElement !== undefined) {
    if (typeElement.kind === "object" || typeElement.kind === "array") fail("Die Art ist kein einfacher Wert");
    typeName = typeElement.kind === "null" ? null : typeElement.content;
  }
  const schema = typeName === null ? undefined : type.subclasses.get(typeName);
  if (schema === undefined || typeName === null) return fail(`Unbekannte Art '${String(typeName)}'`);
  return { ...decodeTreeClass(schema, element), [type.discriminator]: typeName };
}

// ---------- Schreiben ----------

/** Ein zu schreibender Wert. Objekte behalten die Reihenfolge ihrer Felder. */
export type OutValue = string | number | boolean | OutObject | readonly OutValue[];

export class OutObject {
  constructor(readonly entries: ReadonlyArray<readonly [string, OutValue]>) {}
}

/** Ein Text in Anführungszeichen wie kotlinx.serialization (`printQuoted`): nur Steuerzeichen, `"` und `\` werden geschützt. */
export function quote(text: string): string {
  let out = '"';
  let last = 0;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    let escape: string | null = null;
    if (code < 0x20) {
      switch (code) {
        case 0x08:
          escape = "\\b";
          break;
        case 0x09:
          escape = "\\t";
          break;
        case 0x0a:
          escape = "\\n";
          break;
        case 0x0c:
          escape = "\\f";
          break;
        case 0x0d:
          escape = "\\r";
          break;
        default:
          escape = "\\u" + code.toString(16).padStart(4, "0");
      }
    } else if (code === QUOTE) {
      escape = '\\"';
    } else if (code === BACKSLASH) {
      escape = "\\\\";
    }
    if (escape !== null) {
      out += text.substring(last, i) + escape;
      last = i + 1;
    }
  }
  return out + text.substring(last) + '"';
}

/**
 * Schreibt einen Wert wie `StreamingJsonEncoder`: kompakt ohne jeden Leerraum oder lesbar mit vier Leerzeichen je
 * Ebene (`prettyPrint`); leere Listen und Objekte stehen in beiden Fällen als `[]` und `{}`.
 */
export function writeJson(value: OutValue, pretty: boolean): string {
  const parts: string[] = [];
  const newline = (level: number): void => {
    if (pretty) parts.push("\n" + "    ".repeat(level));
  };
  const write = (current: OutValue, level: number): void => {
    if (typeof current === "string") {
      parts.push(quote(current));
    } else if (typeof current === "number") {
      parts.push(String(current));
    } else if (typeof current === "boolean") {
      parts.push(current ? "true" : "false");
    } else if (current instanceof OutObject) {
      parts.push("{");
      current.entries.forEach(([key, item], index) => {
        if (index > 0) parts.push(",");
        newline(level + 1);
        parts.push(quote(key), pretty ? ": " : ":");
        write(item, level + 1);
      });
      if (current.entries.length > 0) newline(level);
      parts.push("}");
    } else {
      parts.push("[");
      current.forEach((item, index) => {
        if (index > 0) parts.push(",");
        newline(level + 1);
        write(item, level + 1);
      });
      if (current.length > 0) newline(level);
      parts.push("]");
    }
  };
  write(value, 0);
  return parts.join("");
}
