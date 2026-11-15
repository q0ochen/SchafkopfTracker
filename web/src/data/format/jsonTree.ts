/*
 * JSON-Bäume wie in kotlinx.serialization (`JsonElement`) und ihr Leser (`JsonTreeReader`). Die Android-App liest
 * ihre Sicherung so: erst den ganzen Text als Baum, dann jeden Abend einzeln aus seinem Teilbaum. Der Baum-Leser nimmt
 * Werte ohne Anführungszeichen beliebiger Form an (zum Beispiel `hallo` oder `1.2.3`) und lehnt Verschachtelungen ab
 * 400 Ebenen ab.
 */
import { JsonFormatError } from "./errors";
import { KotlinxLexer, TC_BEGIN_LIST, TC_BEGIN_OBJ, TC_COLON, TC_COMMA, TC_END_LIST, TC_END_OBJ, TC_OTHER, TC_STRING } from "./kotlinxLexer";

/** Ein Objekt; die Reihenfolge der Schlüssel bleibt erhalten, ein doppelter Schlüssel behält den Platz des ersten und den Wert des letzten. */
export interface JsonObjectElement {
  readonly kind: "object";
  readonly entries: ReadonlyMap<string, JsonElement>;
}

export interface JsonArrayElement {
  readonly kind: "array";
  readonly items: readonly JsonElement[];
}

/** Ein Wert: Text in Anführungszeichen (`isString`) oder alles andere ohne (Zahl, Wahrheitswert, beliebiges Wort). */
export interface JsonLiteralElement {
  readonly kind: "literal";
  readonly content: string;
  readonly isString: boolean;
}

export interface JsonNullElement {
  readonly kind: "null";
}

export type JsonElement = JsonObjectElement | JsonArrayElement | JsonLiteralElement | JsonNullElement;

export const JSON_NULL: JsonNullElement = { kind: "null" };

export const EMPTY_ARRAY: JsonArrayElement = { kind: "array", items: [] };

/** Tiefste Verschachtelung, die kotlinx.serialization annimmt (Standardwert von `maxNestingDepth`). */
const MAX_NESTING_DEPTH = 400;

/** Kotlin: `JsonTreeReader`. */
export class JsonTreeReader {
  private stackDepth: number;

  /** @param startDepth Tiefe, auf der der Leser beginnt (Kotlin: `lexer.path.currentDepth`; -1 für einen ganzen Text). */
  constructor(
    private readonly lexer: KotlinxLexer,
    startDepth: number,
  ) {
    this.stackDepth = startDepth;
  }

  read(): JsonElement {
    const token = this.lexer.peekNextToken();
    switch (token) {
      case TC_STRING:
        return this.readValue(true);
      case TC_OTHER:
        return this.readValue(false);
      case TC_BEGIN_OBJ:
      case TC_BEGIN_LIST: {
        if (++this.stackDepth >= MAX_NESTING_DEPTH) {
          throw new JsonFormatError(`Das JSON ist tiefer als ${MAX_NESTING_DEPTH} Ebenen verschachtelt.`);
        }
        const result = token === TC_BEGIN_OBJ ? this.readObject() : this.readArray();
        --this.stackDepth;
        return result;
      }
      default:
        return this.lexer.fail("Hier kann kein JSON-Wert beginnen");
    }
  }

  private readObject(): JsonObjectElement {
    const lexer = this.lexer;
    let lastToken = lexer.consumeNextTokenOf(TC_BEGIN_OBJ);
    if (lexer.peekNextToken() === TC_COMMA) lexer.fail("Komma am Anfang");
    const result = new Map<string, JsonElement>();
    while (lexer.canConsumeValue()) {
      const key = lexer.consumeString();
      lexer.consumeNextTokenOf(TC_COLON);
      const element = this.read();
      result.set(key, element);
      lastToken = lexer.consumeNextToken();
      if (lastToken === TC_COMMA) continue;
      if (lastToken === TC_END_OBJ) break;
      lexer.fail("Ende des Objekts oder Komma erwartet");
    }
    if (lastToken === TC_BEGIN_OBJ) {
      lexer.consumeNextTokenOf(TC_END_OBJ);
    } else if (lastToken === TC_COMMA) {
      lexer.fail("Komma am Ende eines Objekts");
    }
    return { kind: "object", entries: result };
  }

  private readArray(): JsonArrayElement {
    const lexer = this.lexer;
    let lastToken = lexer.consumeNextToken();
    if (lexer.peekNextToken() === TC_COMMA) lexer.fail("Komma am Anfang");
    const result: JsonElement[] = [];
    while (lexer.canConsumeValue()) {
      result.push(this.read());
      lastToken = lexer.consumeNextToken();
      if (lastToken !== TC_COMMA) {
        if (lastToken !== TC_END_LIST) lexer.fail("Ende der Liste oder Komma erwartet");
        break;
      }
    }
    if (lastToken === TC_BEGIN_LIST) {
      lexer.consumeNextTokenOf(TC_END_LIST);
    } else if (lastToken === TC_COMMA) {
      lexer.fail("Komma am Ende einer Liste");
    }
    return { kind: "array", items: result };
  }

  private readValue(isString: boolean): JsonElement {
    const content = isString ? this.lexer.consumeString() : this.lexer.consumeStringLenient();
    if (!isString && content === "null") return JSON_NULL;
    return { kind: "literal", content, isString };
  }
}

/** Zerlegt einen ganzen Text in einen Baum (Kotlin: `Json.parseToJsonElement`). @throws JsonFormatError */
export function parseToJsonElement(text: string): JsonElement {
  const lexer = new KotlinxLexer(text);
  const element = new JsonTreeReader(lexer, -1).read();
  lexer.expectEof();
  return element;
}

/** Der Text unter diesem Schlüssel; `null`, wenn er fehlt oder kein Text in Anführungszeichen ist. */
export function textOf(object: JsonObjectElement, key: string): string | null {
  const element = object.entries.get(key);
  return element !== undefined && element.kind === "literal" && element.isString ? element.content : null;
}
