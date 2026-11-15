/*
 * Fehlerarten beim Lesen der Dateien, genau unterscheidbar wie in der Android-App (Kotlin, Modul `data`, Paket
 * `storage`): `UnreadableDataException` und ihr Sonderfall `NewerSchemaException`.
 */

/** Aktuelle Version des Dateiformats (Kotlin: `SCHEMA_VERSION`). Dateien mit höherer Version stammen aus einer neueren App. */
export const SCHEMA_VERSION = 1;

/**
 * Der Inhalt einer Datei lässt sich nicht lesen: kaputtes JSON, eine fehlende Pflichtangabe, ein unbekannter Wert einer
 * Aufzählung oder ein ungültiger Zeitpunkt (Kotlin: `UnreadableDataException`).
 *
 * Fehler beim Zerlegen und Übersetzen tragen ihre Ursache in `cause` (wie in Kotlin); nur die eigenen Meldungen der App
 * (zum Beispiel „Ungültige Schema-Version 0.“) haben keine.
 */
export class UnreadableDataError extends Error {
  constructor(message: string, options?: { readonly cause?: unknown }) {
    super(message, options);
    this.name = "UnreadableDataError";
  }
}

/** Die Daten stammen aus einer neueren Version der App, die diese Version noch nicht versteht (Kotlin: `NewerSchemaException`). */
export class NewerSchemaError extends UnreadableDataError {
  /** Die Schema-Version in der Datei. */
  readonly found: number;

  constructor(found: number) {
    super(`Schema-Version ${found} ist neuer als die unterstützte Version ${SCHEMA_VERSION}.`);
    this.name = "NewerSchemaError";
    this.found = found;
  }
}

/**
 * JSON, das die Bibliothek der Android-App (kotlinx.serialization) nicht annimmt, oder ein Wert, der nicht zum Aufbau
 * der Datei passt (Kotlin: `JsonDecodingException` und andere Laufzeitfehler beim Lesen). Nach außen erscheint dieser
 * Fehler nur als Ursache eines {@link UnreadableDataError}.
 */
export class JsonFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "JsonFormatError";
  }
}

/** Ein Text ist kein Zeitpunkt, den die Android-App annimmt (Kotlin: `DateTimeException`). */
export class InvalidInstantError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InvalidInstantError";
  }
}

/** Ein Model lässt sich nicht schreiben, weil ein Wert nicht in das Dateiformat passt (in Kotlin unmöglich, hier ein Programmfehler). */
export class InvalidModelError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InvalidModelError";
  }
}

/** Meldung eines beliebigen Fehlers für die Weitergabe. */
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
