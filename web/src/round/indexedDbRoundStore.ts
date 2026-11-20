/*
 * Was sich dieses Gerät zum Abgleich der gemeinsamen Runde merkt (docs/ARCHITEKTUR.md, Abschnitt 8.4), in IndexedDB:
 * die verbundene Runde, je Abend die zuletzt bekannte Fassung des Servers und je Spieler seinen Stand dort. Abende und
 * Regelwerk stehen darin als JSON-Text im Dateiformat der Android-App, wie überall im Speicher der Web-App.
 *
 * Gelesen wird einmal und dann aus dem Arbeitsspeicher geantwortet; geschrieben wird immer erst in die Datenbank.
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

interface Loaded {
  link: RoundLink | null;
  readonly evenings: Map<EveningId, EveningBase>;
  readonly players: Map<PlayerId, RemotePlayer>;
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

export class IndexedDbRoundStore implements RoundStore {
  private loaded: Promise<Loaded> | null = null;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly db: Database,
    private readonly codec: RoundStoreCodec,
  ) {}

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

  /** Vergisst, was im Arbeitsspeicher steht (ein anderes Fenster der App hat die Datenbank geändert), und meldet es. */
  invalidate(): void {
    this.loaded = null;
    this.notify();
  }

  private notify(): void {
    for (const listener of [...this.listeners]) listener();
  }

  private state(): Promise<Loaded> {
    this.loaded ??= this.read().catch((error: unknown) => {
      this.loaded = null;
      throw error;
    });
    return this.loaded;
  }

  private async read(): Promise<Loaded> {
    const tx = this.db.transaction(["round", "eveningBases", "playerBases"], "readonly");
    const [linkText, eveningKeys, eveningTexts, playerKeys, playerTexts] = await Promise.all([
      tx.objectStore("round").get(LINK_KEY),
      tx.objectStore("eveningBases").getAllKeys(),
      tx.objectStore("eveningBases").getAll(),
      tx.objectStore("playerBases").getAllKeys(),
      tx.objectStore("playerBases").getAll(),
    ]);
    await tx.done;
    const evenings = new Map<EveningId, EveningBase>();
    eveningKeys.forEach((key, index) => {
      const base = this.decodeEveningBase(key, eveningTexts[index]);
      if (base !== null) evenings.set(key, base);
    });
    const players = new Map<PlayerId, RemotePlayer>();
    playerKeys.forEach((key, index) => {
      const base = decodePlayerBase(key, playerTexts[index]);
      if (base !== null) players.set(key, base);
    });
    return { link: linkText === undefined ? null : this.decodeLink(linkText), evenings, players };
  }

  // --- Verbundene Runde ---

  async link(): Promise<RoundLink | null> {
    return (await this.state()).link;
  }

  async setLink(link: RoundLink | null): Promise<void> {
    const state = await this.state();
    if (link === null) await this.db.delete("round", LINK_KEY);
    else await this.db.put("round", this.encodeLink(link), LINK_KEY);
    state.link = link;
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
    };
  }

  // --- Abende ---

  async eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    return (await this.state()).evenings;
  }

  async setEveningBase(id: EveningId, base: EveningBase | null): Promise<void> {
    const state = await this.state();
    if (base === null) {
      await this.db.delete("eveningBases", id);
      state.evenings.delete(id);
    } else {
      await this.db.put("eveningBases", JSON.stringify({ version: base.version, evening: this.codec.encodeEvening(base.evening) }), id);
      state.evenings.set(id, base);
    }
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

  async playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    return (await this.state()).players;
  }

  async setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    const state = await this.state();
    if (base === null) {
      await this.db.delete("playerBases", id);
      state.players.delete(id);
    } else {
      await this.db.put("playerBases", JSON.stringify(base), id);
      state.players.set(id, base);
    }
  }

  async clearBases(): Promise<void> {
    const state = await this.state();
    const tx = this.db.transaction(["eveningBases", "playerBases"], "readwrite");
    await Promise.all([tx.objectStore("eveningBases").clear(), tx.objectStore("playerBases").clear(), tx.done]);
    state.evenings.clear();
    state.players.clear();
    this.notify();
  }
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
