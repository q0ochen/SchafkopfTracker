/*
 * Speicher der Web-App im Browser (IndexedDB). Wie die Android-App (`data/storage/FileStorage`) speichert sie jeden
 * Abend, die Spielerliste und die Einstellungen als JSON-Text im Dateiformat der Android-App. So bleibt alles
 * lesbar, auch wenn sich die Web-App ändert, und eine Sicherung ist nur das Zusammenfassen dieser Texte.
 *
 * Ohne gemeinsame Runde verlassen die Daten das Gerät nie. Mit ihr merkt sich dieselbe Datenbank zusätzlich, mit
 * welcher Runde das Gerät verbunden ist und was es zuletzt vom Server kannte (`round/indexedDbRoundStore.ts`).
 *
 * Jedes Schreiben zählt in derselben Transaktion einen Zähler hoch (`documents`, Schlüssel „stand“). Daran erkennt ein
 * Fenster der App, ob ein anderes Fenster inzwischen gespeichert hat ({@link AppRepository.isCurrent}).
 */
import { openDB, type DBSchema, type IDBPDatabase, type IDBPTransaction } from "idb";
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
  /**
   * Ob seit dem letzten {@link load} nur dieses Fenster gespeichert hat: Dann entspricht der geladene Stand samt den
   * eigenen Änderungen dem Gerät. `false`, sobald ein anderes Fenster der App etwas geschrieben hat.
   */
  isCurrent(): Promise<boolean>;
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
  /** Weitere Dokumente als JSON-Text: „players“ und „settings“; dazu der Zähler der Änderungen unter „stand“. */
  documents: { key: string; value: string };
  /** Gemeinsame Runde (seit Version 2): die verbundene Runde als JSON-Text unter dem Schlüssel „link“. */
  round: { key: string; value: string };
  /** Gemeinsame Runde: je Abend die zuletzt bekannte Fassung des Servers als JSON-Text, Schlüssel ist die Kennung des Abends. */
  eveningBases: { key: string; value: string };
  /** Gemeinsame Runde: je Spieler der zuletzt bekannte Stand des Servers als JSON-Text, Schlüssel ist die Kennung des Spielers. */
  playerBases: { key: string; value: string };
}

export const DATABASE_NAME = "schafkopf-tracker";

/**
 * Version der Datenbank. 1: Abende und Dokumente. 2: dazu die drei Ablagen der gemeinsamen Runde; vorhandene Daten
 * bleiben beim Übergang unberührt.
 */
export const DATABASE_VERSION = 2;
const PLAYERS_KEY = "players";
const SETTINGS_KEY = "settings";

/** Unter diesem Schlüssel zählt die Ablage `documents` jedes Schreiben der Daten, über alle Fenster der App hinweg. */
export const STAMP_KEY = "stand";

/** Der nächste Zählerstand nach `stamp` (ein fehlender oder unbrauchbarer Zähler beginnt von vorn). */
function nextStamp(stamp: string | undefined): string {
  const count = Number(stamp);
  return String(Number.isSafeInteger(count) && count >= 0 ? count + 1 : 1);
}

export type Database = IDBPDatabase<SchafkopfSchema>;

/** Was beim Öffnen der Datenbank außer der Reihe passieren kann. */
export interface DatabaseHooks {
  /**
   * Die Datenbank lässt sich gerade nicht auf die neue Version heben, weil die App noch in einem anderen Fenster mit
   * einer älteren Version geöffnet ist. Das Öffnen wartet, bis dieses Fenster geschlossen oder neu geladen ist.
   */
  blocked?(): void;
  /** Eine neuere Version der App (in einem anderen Fenster) will die Datenbank heben; diese Verbindung ist dann schon geschlossen. */
  outdated?(): void;
}

export function openDatabase(name: string = DATABASE_NAME, hooks: DatabaseHooks = {}): Promise<Database> {
  const opened: Promise<Database> = openDB<SchafkopfSchema>(name, DATABASE_VERSION, {
    upgrade(db, oldVersion) {
      if (oldVersion < 1) {
        db.createObjectStore("evenings");
        db.createObjectStore("documents");
      }
      if (oldVersion < 2) {
        db.createObjectStore("round");
        db.createObjectStore("eveningBases");
        db.createObjectStore("playerBases");
      }
    },
    blocked() {
      hooks.blocked?.();
    },
    blocking() {
      // Nicht im Weg stehen: Sonst hinge das andere Fenster beim Start fest.
      void opened.then((db) => db.close()).then(() => hooks.outdated?.());
    },
  });
  return opened;
}

/** Ein Schreibauftrag in einer Transaktion über Abende und Dokumente. */
type WriteTransaction = IDBPTransaction<SchafkopfSchema, ["evenings", "documents"], "readwrite">;

/** Speicher in IndexedDB. */
export class IndexedDbRepository implements AppRepository {
  /** Der Zählerstand, bis zu dem dieses Fenster alles kennt (beim Laden gelesen, mit jedem eigenen Schreiben weiter). */
  private known: string | undefined = undefined;

  constructor(
    private readonly db: Database,
    private readonly codec: StorageCodec,
  ) {}

  async load(): Promise<LoadedData> {
    const tx = this.db.transaction(["evenings", "documents"], "readonly");
    const [keys, texts, playersText, settingsText, stamp] = await Promise.all([
      tx.objectStore("evenings").getAllKeys(),
      tx.objectStore("evenings").getAll(),
      tx.objectStore("documents").get(PLAYERS_KEY),
      tx.objectStore("documents").get(SETTINGS_KEY),
      tx.objectStore("documents").get(STAMP_KEY),
    ]);
    await tx.done;
    this.known = stamp;
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

  async isCurrent(): Promise<boolean> {
    return (await this.db.get("documents", STAMP_KEY)) === this.known;
  }

  /**
   * Schreibt in einer Transaktion zusammen mit dem Zähler; umgewandelt wird vorher, damit ein Fehler dabei nichts halb
   * schreibt. Hatte seit dem letzten Stand dieses Fensters niemand sonst geschrieben, kennt es danach weiter alles.
   */
  private async write(work: (tx: WriteTransaction) => Promise<unknown>[]): Promise<void> {
    const tx = this.db.transaction(["evenings", "documents"], "readwrite");
    const documents = tx.objectStore("documents");
    const before = await documents.get(STAMP_KEY);
    const next = nextStamp(before);
    await Promise.all([documents.put(next, STAMP_KEY), ...work(tx), tx.done]);
    if (before === this.known) this.known = next;
  }

  async saveEvening(evening: Evening): Promise<void> {
    const text = this.codec.encodeEvening(evening);
    await this.write((tx) => [tx.objectStore("evenings").put(text, evening.id)]);
  }

  async deleteEvening(id: EveningId): Promise<void> {
    await this.write((tx) => [tx.objectStore("evenings").delete(id)]);
  }

  async savePlayers(players: readonly Player[]): Promise<void> {
    const text = this.codec.encodePlayers(players);
    await this.write((tx) => [tx.objectStore("documents").put(text, PLAYERS_KEY)]);
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    const text = this.codec.encodeSettings(settings);
    await this.write((tx) => [tx.objectStore("documents").put(text, SETTINGS_KEY)]);
  }

  async saveImport(players: readonly Player[], evenings: readonly Evening[]): Promise<void> {
    // Erst alles umwandeln, dann in einer einzigen Transaktion schreiben: Bricht etwas ab, bleibt alles beim Alten.
    const playersText = this.codec.encodePlayers(players);
    const eveningTexts = evenings.map((evening) => [evening.id, this.codec.encodeEvening(evening)] as const);
    await this.write((tx) => [
      tx.objectStore("documents").put(playersText, PLAYERS_KEY),
      ...eveningTexts.map(([id, text]) => tx.objectStore("evenings").put(text, id)),
    ]);
  }
}

/**
 * Flüchtiger Speicher für Tests (rundet über den Codec, damit Tests dasselbe prüfen wie im Browser). Was sich nicht
 * lesen lässt, meldet er wie der Speicher im Browser.
 */
export class InMemoryRepository implements AppRepository {
  readonly evenings = new Map<EveningId, string>();
  players: string | undefined;
  settings: string | undefined;
  /** Zum Testen von Speicherfehlern. */
  failWrites = false;
  /** Zählt jedes Schreiben; ein Test kann ihn erhöhen, als hätte ein anderes Fenster geschrieben. */
  stamp = 0;
  private known = 0;

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
    const unreadableDocuments: string[] = [];
    const read = <T>(text: string | undefined, key: string, decode: (text: string) => T): T | null => {
      if (text === undefined) return null;
      try {
        return decode(text);
      } catch {
        unreadableDocuments.push(key);
        return null;
      }
    };
    this.known = this.stamp;
    return {
      players: read(this.players, PLAYERS_KEY, (text) => this.codec.decodePlayers(text)),
      evenings,
      settings: read(this.settings, SETTINGS_KEY, (text) => this.codec.decodeSettings(text)),
      unreadableEvenings,
      unreadableDocuments,
    };
  }

  async isCurrent(): Promise<boolean> {
    return this.stamp === this.known;
  }

  /** Prüft, ob sich schreiben lässt, und zählt das Schreiben. */
  private check(): void {
    if (this.failWrites) throw new Error("Speichern fehlgeschlagen (Test)");
    if (this.stamp === this.known) this.known++;
    this.stamp++;
  }

  async saveEvening(evening: Evening): Promise<void> {
    const text = this.codec.encodeEvening(evening);
    this.check();
    this.evenings.set(evening.id, text);
  }

  async deleteEvening(id: EveningId): Promise<void> {
    this.check();
    this.evenings.delete(id);
  }

  async savePlayers(players: readonly Player[]): Promise<void> {
    const text = this.codec.encodePlayers(players);
    this.check();
    this.players = text;
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    const text = this.codec.encodeSettings(settings);
    this.check();
    this.settings = text;
  }

  async saveImport(players: readonly Player[], evenings: readonly Evening[]): Promise<void> {
    const playersText = this.codec.encodePlayers(players);
    const eveningTexts = evenings.map((evening) => [evening.id, this.codec.encodeEvening(evening)] as const);
    this.check();
    this.players = playersText;
    for (const [id, text] of eveningTexts) this.evenings.set(id, text);
  }
}
