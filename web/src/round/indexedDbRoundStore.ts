/*
 * Was sich dieses Gerät zum Abgleich der gemeinsamen Runde merkt (docs/ARCHITEKTUR.md, Abschnitt 8.4), in IndexedDB:
 * die verbundene Runde, je Abend die zuletzt bekannte Fassung des Servers und je Spieler seinen Stand dort. Abende und
 * Regelwerk stehen darin als JSON-Text im Dateiformat der Android-App, wie überall im Speicher der Web-App.
 *
 * Gelesen wird bei Bedarf (die Verbindung, die Basen der Abende und die der Spieler je für sich) und dann aus dem
 * Arbeitsspeicher geantwortet; geschrieben wird immer erst in die Datenbank. Ein anderes Fenster der App kann dieselbe
 * Datenbank ändern: Vor jedem Abgleich wird deshalb alles verworfen und frisch gelesen ({@link invalidate}), und jedes
 * Schreiben meldet sich bei den anderen Fenstern (`onWrite`).
 *
 * Was sich nicht lesen lässt, gilt als nicht vorhanden: Ohne Basis wird ein Abend beim nächsten Abgleich mit der
 * Fassung des Servers zusammengeführt, und dabei geht nichts verloren.
 */
import type { Evening, EveningId, PlayerId, RuleSet } from "../core/model";
import type { EveningBase, RemotePlayer, RoundLink, RoundMember } from "../core/round/RoundModels";
import type { RoundStore } from "../core/round/RoundPorts";
import type { Database } from "../data/repository";

/** Umwandlung der Abende und des Regelwerks in gespeicherten Text (Dateiformat der Android-App). */
export interface RoundStoreCodec {
  encodeEvening(evening: Evening): string;
  /** Wirft, wenn der Text kein lesbarer Abend ist. */
  decodeEvening(text: string): Evening;
  encodeRules(rules: RuleSet): string;
  /** `null`, wenn sich der Text nicht lesen lässt. */
  decodeRulesOrNull(text: string): RuleSet | null;
}

const LINK_KEY = "link";

/** Was beim Schreiben außer der Reihe passieren soll. */
export interface RoundStoreOptions {
  /** Nach jedem gelungenen Schreiben (die anderen Fenster der App erfahren es so). */
  readonly onWrite?: () => void;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

function textOrNull(value: unknown): string | null | undefined {
  return value === null || typeof value === "string" ? value : undefined;
}

function wholeNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) ? value : undefined;
}

/**
 * Ein Wert aus der Datenbank, einmal gelesen und dann gemerkt. Wird er verworfen, während noch gelesen wird, merkt er
 * sich das Gelesene nicht: Es könnte von vor einer Änderung stammen.
 */
class Cached<T> {
  private value: Promise<T> | null = null;
  private generation = 0;

  constructor(private readonly read: () => Promise<T>) {}

  get(): Promise<T> {
    if (this.value === null) {
      const generation = this.generation;
      const reading = this.read().catch((error: unknown) => {
        if (this.generation === generation && this.value === reading) this.value = null;
        throw error;
      });
      this.value = reading;
    }
    return this.value;
  }

  /** Merkt sich einen Wert, der gerade so in die Datenbank geschrieben wurde. */
  set(value: T): void {
    this.value = Promise.resolve(value);
    this.generation++;
  }

  /**
   * Ändert den gemerkten Wert, falls er schon gelesen ist (sonst steht die Änderung beim Lesen ohnehin in der
   * Datenbank). Scheiterte das Lesen, wird beim nächsten Zugriff neu gelesen.
   */
  async update(change: (value: T) => T): Promise<void> {
    const current = this.value;
    if (current === null) return;
    const generation = this.generation;
    let next: T;
    try {
      next = change(await current);
    } catch {
      return;
    }
    if (this.generation === generation && this.value === current) this.set(next);
  }

  forget(): void {
    this.value = null;
    this.generation++;
  }
}

export class IndexedDbRoundStore implements RoundStore {
  private readonly listeners = new Set<() => void>();
  private readonly cachedLink: Cached<RoundLink | null>;
  private readonly cachedEvenings: Cached<ReadonlyMap<EveningId, EveningBase>>;
  private readonly cachedPlayers: Cached<ReadonlyMap<PlayerId, RemotePlayer>>;

  constructor(
    private readonly db: Database,
    private readonly codec: RoundStoreCodec,
    private readonly options: RoundStoreOptions = {},
  ) {
    this.cachedLink = new Cached(() => this.readLink());
    this.cachedEvenings = new Cached(() => this.readEveningBases());
    this.cachedPlayers = new Cached(() => this.readPlayerBases());
  }

  /**
   * Meldet, wenn sich die verbundene Runde geändert hat (auch ihr Lesezeichen nach einem Abgleich) oder alle Basen
   * vergessen wurden. Einzelne Basen melden sich nicht: Davon schreibt ein Abgleich viele hintereinander.
   */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Vergisst, was im Arbeitsspeicher steht (ein anderes Fenster der App kann die Datenbank geändert haben); gelesen
   * wird beim nächsten Zugriff neu.
   *
   * @param notify Auch melden (für die Oberfläche); vor einem Abgleich ist das nicht nötig.
   */
  invalidate(notify = true): void {
    this.cachedLink.forget();
    this.cachedEvenings.forget();
    this.cachedPlayers.forget();
    if (notify) this.notify();
  }

  private notify(): void {
    for (const listener of [...this.listeners]) listener();
  }

  private written(): void {
    this.options.onWrite?.();
  }

  private async readLink(): Promise<RoundLink | null> {
    const text = await this.db.get("round", LINK_KEY);
    return text === undefined ? null : this.decodeLink(text);
  }

  private async readEveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    const tx = this.db.transaction("eveningBases", "readonly");
    const [keys, texts] = await Promise.all([tx.store.getAllKeys(), tx.store.getAll(), tx.done]);
    const evenings = new Map<EveningId, EveningBase>();
    keys.forEach((key, index) => {
      const base = this.decodeEveningBase(key, texts[index]);
      if (base !== null) evenings.set(key, base);
    });
    return evenings;
  }

  private async readPlayerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    const tx = this.db.transaction("playerBases", "readonly");
    const [keys, texts] = await Promise.all([tx.store.getAllKeys(), tx.store.getAll(), tx.done]);
    const players = new Map<PlayerId, RemotePlayer>();
    keys.forEach((key, index) => {
      const base = decodePlayerBase(key, texts[index]);
      if (base !== null) players.set(key, base);
    });
    return players;
  }

  // --- Verbundene Runde ---

  link(): Promise<RoundLink | null> {
    return this.cachedLink.get();
  }

  async setLink(link: RoundLink | null): Promise<void> {
    if (link === null) await this.db.delete("round", LINK_KEY);
    else await this.db.put("round", this.encodeLink(link), LINK_KEY);
    this.cachedLink.set(link);
    this.written();
    this.notify();
  }

  private encodeLink(link: RoundLink): string {
    return JSON.stringify({
      roundId: link.roundId,
      roundName: link.roundName,
      userId: link.userId,
      playerId: link.playerId,
      isCreator: link.isCreator,
      cursor: link.cursor,
      rulesBase: link.rulesBase === null ? null : this.codec.encodeRules(link.rulesBase),
      members: link.members.map((member) => ({ userId: member.userId, playerId: member.playerId })),
      unreadable: link.unreadable,
    });
  }

  private decodeLink(text: string): RoundLink | null {
    const value = parse(text);
    if (!isRecord(value)) return null;
    const { roundId, roundName, userId, isCreator } = value;
    const playerId = textOrNull(value.playerId);
    const cursor = wholeNumber(value.cursor);
    const rulesText = textOrNull(value.rulesBase);
    if (typeof roundId !== "string" || typeof roundName !== "string" || typeof userId !== "string") return null;
    if (playerId === undefined || typeof isCreator !== "boolean" || cursor === undefined || rulesText === undefined) return null;
    const members: RoundMember[] = [];
    for (const member of Array.isArray(value.members) ? (value.members as unknown[]) : []) {
      if (!isRecord(member) || typeof member.userId !== "string") continue;
      members.push({ userId: member.userId, playerId: textOrNull(member.playerId) ?? null });
    }
    // Fehlt in Ständen von vor dem 20.11.2026: dann keine. Jede Kennung zählt nur einmal; was keine ist, entfällt.
    const unreadable = new Set<EveningId>();
    for (const id of Array.isArray(value.unreadable) ? (value.unreadable as unknown[]) : []) {
      if (typeof id === "string") unreadable.add(id);
    }
    return {
      roundId,
      roundName,
      userId,
      playerId,
      isCreator,
      cursor,
      // Lässt sich das gemerkte Regelwerk nicht lesen, gilt es als unbekannt: Dann gewinnt beim nächsten Abgleich das des Servers.
      rulesBase: rulesText === null ? null : this.codec.decodeRulesOrNull(rulesText),
      members,
      unreadable: [...unreadable],
    };
  }

  // --- Abende ---

  eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    return this.cachedEvenings.get();
  }

  async setEveningBase(id: EveningId, base: EveningBase | null): Promise<void> {
    if (base === null) await this.db.delete("eveningBases", id);
    else await this.db.put("eveningBases", JSON.stringify({ version: base.version, evening: this.codec.encodeEvening(base.evening) }), id);
    await this.cachedEvenings.update((bases) => withEntry(bases, id, base));
    this.written();
  }

  private decodeEveningBase(id: EveningId, text: string): EveningBase | null {
    const value = parse(text);
    if (!isRecord(value)) return null;
    const version = wholeNumber(value.version);
    if (version === undefined || typeof value.evening !== "string") return null;
    try {
      const evening = this.codec.decodeEvening(value.evening);
      return evening.id === id ? { version, evening } : null;
    } catch {
      return null;
    }
  }

  // --- Spieler ---

  playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    return this.cachedPlayers.get();
  }

  async setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    if (base === null) await this.db.delete("playerBases", id);
    else await this.db.put("playerBases", JSON.stringify(base), id);
    await this.cachedPlayers.update((bases) => withEntry(bases, id, base));
    this.written();
  }

  async clearBases(): Promise<void> {
    const tx = this.db.transaction(["eveningBases", "playerBases"], "readwrite");
    await Promise.all([tx.objectStore("eveningBases").clear(), tx.objectStore("playerBases").clear(), tx.done]);
    this.cachedEvenings.set(new Map());
    this.cachedPlayers.set(new Map());
    this.written();
    this.notify();
  }
}

/** Eine neue Liste mit dem Eintrag (oder, mit `null`, ohne ihn); die alte bleibt, wie sie war, für alle, die sie gerade lesen. */
function withEntry<K, V>(map: ReadonlyMap<K, V>, key: K, value: V | null): ReadonlyMap<K, V> {
  const next = new Map(map);
  if (value === null) next.delete(key);
  else next.set(key, value);
  return next;
}

function decodePlayerBase(id: PlayerId, text: string): RemotePlayer | null {
  const value = parse(text);
  if (!isRecord(value)) return null;
  const { name, archived, nameLocked, deleted } = value;
  const colorIndex = wholeNumber(value.colorIndex);
  const position = wholeNumber(value.position);
  const rev = wholeNumber(value.rev);
  const owner = textOrNull(value.owner);
  if (value.id !== id || typeof name !== "string" || typeof archived !== "boolean") return null;
  if (typeof nameLocked !== "boolean" || typeof deleted !== "boolean") return null;
  if (colorIndex === undefined || position === undefined || rev === undefined || owner === undefined) return null;
  return { id, name, colorIndex, archived, position, owner, nameLocked, deleted, rev };
}
