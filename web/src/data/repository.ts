/*
 * Speicher der Web-App im Browser (IndexedDB). Wie die Android-App (`data/storage/FileStorage`) speichert sie jeden
 * Abend, die Spielerliste und die Einstellungen als JSON-Text im Dateiformat der Android-App. So bleibt alles
 * lesbar, auch wenn sich die Web-App ändert, und eine Sicherung ist nur das Zusammenfassen dieser Texte.
 *
 * Daten verlassen das Gerät nie: Es gibt keinen Server.
 */
import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import type { AppSettings, Evening, EveningId, Player } from "../core/model";

/** Umwandlung zwischen Daten und gespeichertem Text (Dateiformat der Android-App). */
export interface StorageCodec {
  encodeEvening(evening: Evening): string;
  /** Wirft, wenn der Text kein lesbarer Abend ist. */
  decodeEvening(text: string): Evening;
  encodePlayers(players: readonly Player[]): string;
  decodePlayers(text: string): Player[];
  encodeSettings(settings: AppSettings): string;
  decodeSettings(text: string): AppSettings;
}

/** Was beim Start geladen wurde. */
export interface LoadedData {
  readonly players: Player[] | null;
  readonly evenings: Evening[];
  readonly settings: AppSettings | null;
  /** Gespeicherte Abende, die sich nicht lesen ließen. Sie bleiben unangetastet liegen. */
  readonly unreadableEvenings: readonly EveningId[];
  /** Spielerliste oder Einstellungen ließen sich nicht lesen (dann gelten Standardwerte, bis neu gespeichert wird). */
  readonly unreadableDocuments: readonly string[];
}

/** Lesen und Schreiben aller Daten. */
export interface AppRepository {
  load(): Promise<LoadedData>;
  saveEvening(evening: Evening): Promise<void>;
  deleteEvening(id: EveningId): Promise<void>;
  savePlayers(players: readonly Player[]): Promise<void>;
  saveSettings(settings: AppSettings): Promise<void>;
  /** Speichert Spieler und mehrere Abende in einem Zug (Einlesen einer Sicherung): alles oder nichts. */
  saveImport(players: readonly Player[], evenings: readonly Evening[]): Promise<void>;
}

interface SchafkopfSchema extends DBSchema {
  /** Je Abend ein JSON-Text, Schlüssel ist die Kennung des Abends. */
  evenings: { key: string; value: string };
  /** Weitere Dokumente als JSON-Text: „players“ und „settings“. */
  documents: { key: string; value: string };
}

export const DATABASE_NAME = "schafkopf-tracker";
const DATABASE_VERSION = 1;
const PLAYERS_KEY = "players";
const SETTINGS_KEY = "settings";

export type Database = IDBPDatabase<SchafkopfSchema>;

export function openDatabase(name: string = DATABASE_NAME): Promise<Database> {
  return openDB<SchafkopfSchema>(name, DATABASE_VERSION, {
    upgrade(db, oldVersion) {
      if (oldVersion < 1) {
        db.createObjectStore("evenings");
        db.createObjectStore("documents");
      }
    },
  });
}

/** Speicher in IndexedDB. */
export class IndexedDbRepository implements AppRepository {
  constructor(
    private readonly db: Database,
    private readonly codec: StorageCodec,
  ) {}

  async load(): Promise<LoadedData> {
    const tx = this.db.transaction(["evenings", "documents"], "readonly");
    const [keys, texts, playersText, settingsText] = await Promise.all([
      tx.objectStore("evenings").getAllKeys(),
      tx.objectStore("evenings").getAll(),
      tx.objectStore("documents").get(PLAYERS_KEY),
      tx.objectStore("documents").get(SETTINGS_KEY),
    ]);
    await tx.done;
    const evenings: Evening[] = [];
    const unreadableEvenings: EveningId[] = [];
    keys.forEach((key, index) => {
      try {
        evenings.push(this.codec.decodeEvening(texts[index]));
      } catch (error) {
        console.warn(`Schafkopf Tracker: Abend ${key} ist nicht lesbar`, error);
        unreadableEvenings.push(key);
      }
    });
    const unreadableDocuments: string[] = [];
    const players = this.readDocument(playersText, PLAYERS_KEY, (text) => this.codec.decodePlayers(text), unreadableDocuments);
    const settings = this.readDocument(settingsText, SETTINGS_KEY, (text) => this.codec.decodeSettings(text), unreadableDocuments);
    return { players, evenings, settings, unreadableEvenings, unreadableDocuments };
  }

  private readDocument<T>(text: string | undefined, key: string, decode: (text: string) => T, unreadable: string[]): T | null {
    if (text === undefined) return null;
    try {
      return decode(text);
    } catch (error) {
      console.warn(`Schafkopf Tracker: ${key} ist nicht lesbar`, error);
      unreadable.push(key);
      return null;
    }
  }

  async saveEvening(evening: Evening): Promise<void> {
    await this.db.put("evenings", this.codec.encodeEvening(evening), evening.id);
  }

  async deleteEvening(id: EveningId): Promise<void> {
    await this.db.delete("evenings", id);
  }

  async savePlayers(players: readonly Player[]): Promise<void> {
    await this.db.put("documents", this.codec.encodePlayers(players), PLAYERS_KEY);
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    await this.db.put("documents", this.codec.encodeSettings(settings), SETTINGS_KEY);
  }

  async saveImport(players: readonly Player[], evenings: readonly Evening[]): Promise<void> {
    // Erst alles umwandeln, dann in einer einzigen Transaktion schreiben: Bricht etwas ab, bleibt alles beim Alten.
    const playersText = this.codec.encodePlayers(players);
    const eveningTexts = evenings.map((evening) => [evening.id, this.codec.encodeEvening(evening)] as const);
    const tx = this.db.transaction(["evenings", "documents"], "readwrite");
    const writes: Promise<unknown>[] = [tx.objectStore("documents").put(playersText, PLAYERS_KEY)];
    for (const [id, text] of eveningTexts) writes.push(tx.objectStore("evenings").put(text, id));
    await Promise.all([...writes, tx.done]);
  }
}

/** Flüchtiger Speicher für Tests (rundet über den Codec, damit Tests dasselbe prüfen wie im Browser). */
export class InMemoryRepository implements AppRepository {
  readonly evenings = new Map<EveningId, string>();
  players: string | undefined;
  settings: string | undefined;
  /** Zum Testen von Speicherfehlern. */
  failWrites = false;

  constructor(private readonly codec: StorageCodec) {}

  async load(): Promise<LoadedData> {
    const evenings: Evening[] = [];
    const unreadableEvenings: EveningId[] = [];
    for (const [id, text] of this.evenings) {
      try {
        evenings.push(this.codec.decodeEvening(text));
      } catch {
        unreadableEvenings.push(id);
      }
    }
    return {
      players: this.players === undefined ? null : this.codec.decodePlayers(this.players),
      evenings,
      settings: this.settings === undefined ? null : this.codec.decodeSettings(this.settings),
      unreadableEvenings,
      unreadableDocuments: [],
    };
  }

  private check(): void {
    if (this.failWrites) throw new Error("Speichern fehlgeschlagen (Test)");
  }

  async saveEvening(evening: Evening): Promise<void> {
    this.check();
    this.evenings.set(evening.id, this.codec.encodeEvening(evening));
  }

  async deleteEvening(id: EveningId): Promise<void> {
    this.check();
    this.evenings.delete(id);
  }

  async savePlayers(players: readonly Player[]): Promise<void> {
    this.check();
    this.players = this.codec.encodePlayers(players);
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    this.check();
    this.settings = this.codec.encodeSettings(settings);
  }

  async saveImport(players: readonly Player[], evenings: readonly Evening[]): Promise<void> {
    this.check();
    this.players = this.codec.encodePlayers(players);
    for (const evening of evenings) this.evenings.set(evening.id, this.codec.encodeEvening(evening));
  }
}
