/*
 * Gemeinsames Werkzeug für die Tests der gemeinsamen Runde: eine stellbare Uhr, ein fester Zufall und nachgestellte
 * Geräte. Port von `domain/src/test/kotlin/…/round/RoundFixtures.kt`.
 */
import { append } from "../../evening/Evening";
import { instantFromEpochMillis, instantToEpochMillis } from "../../instant";
import type { EntryId, Evening, EveningEntry, EveningId, GameEntry, InstantText, Player, PlayerId } from "../../model";
import { RuleSets } from "../../rules/RuleSet";
import { InMemoryEveningEditor, InMemoryEveningRepository, InMemoryPlayerRepository, InMemorySettingsRepository } from "../InMemoryRepositories";
import { InMemoryRoundStore, type InMemoryRoundServer, type InMemoryRoundSession } from "../InMemoryRound";
import { RoundConnection } from "../RoundConnection";
import { RoundException, type RoundError, type RoundId, type RoundLink, type SavedEvening } from "../RoundModels";
import { ROUND_API_METHODS, type IdGenerator, type RoundApi } from "../RoundPorts";
import { RoundSync, type Synced } from "../RoundSync";

export const P1: PlayerId = "spieler-1";
export const P2: PlayerId = "spieler-2";
export const P3: PlayerId = "spieler-3";
export const P4: PlayerId = "spieler-4";
export const SEATING: readonly PlayerId[] = [P1, P2, P3, P4];

/** Der Abend der Runde am 12.12.2026, 19 Uhr. */
export const START: InstantText = "2026-12-12T18:00:00Z";
const START_MILLIS = instantToEpochMillis(START);

/** Ein Zeitpunkt so viele Sekunden (und Millisekunden) nach Beginn des Abends. */
export function after(seconds: number, millis = 0): InstantText {
  return instantFromEpochMillis(START_MILLIS + seconds * 1000 + millis);
}

/** Die vorbelegten Spieler, wie die App sie beim ersten Start anlegt. */
export const PLACEHOLDERS: readonly Player[] = [1, 2, 3, 4].map((n) => ({
  id: `spieler-${n}`,
  name: `Spieler ${n}`,
  colorIndex: n - 1,
  archived: false,
}));

/** Ein Spieler mit den Standardwerten der Kotlin-Klasse. */
export function player(id: PlayerId, name: string, colorIndex: number, archived = false): Player {
  return { id, name, colorIndex, archived };
}

/** Eine Uhr, die nur läuft, wenn der Test sie stellt. */
export class TestClock {
  millis: number;

  constructor(now: InstantText = START) {
    this.millis = instantToEpochMillis(now);
  }

  now(): InstantText {
    return instantFromEpochMillis(this.millis);
  }

  advance(seconds: number): void {
    this.millis += seconds * 1000;
  }
}

/** Ein Rufspiel, `seconds` Sekunden nach Beginn des Abends aufgeschrieben. */
export function game(
  id: string,
  seconds: number,
  fields: { declarer?: PlayerId; partner?: PlayerId; won?: boolean; laufende?: number; note?: string } = {},
): GameEntry {
  return {
    kind: "game",
    id,
    recordedAt: after(seconds),
    type: "RUFSPIEL",
    suit: null,
    declarer: fields.declarer ?? P1,
    partner: fields.partner ?? P2,
    declarerWon: fields.won ?? true,
    level: "NORMAL",
    laufende: fields.laufende ?? 0,
    announcement: "NONE",
    klopfer: [],
    kontra: "NONE",
    kontraBy: null,
    retourBy: null,
    bockOverride: "AUTO",
    declarerEyes: null,
    note: fields.note ?? "",
  };
}

export function evening(entries: readonly EveningEntry[] = [], id = "abend"): Evening {
  return {
    id,
    startedAt: START,
    finishedAt: null,
    location: "",
    seating: [...SEATING],
    firstDealer: P1,
    rules: RuleSets.standard(),
    entries: [...entries],
  };
}

export function ids(it: Evening): string[] {
  return it.entries.map((entry) => entry.id);
}

/** Der Fehler, mit dem `block` scheitert; `null`, wenn er durchläuft. */
export async function errorOf(block: () => Promise<unknown>): Promise<RoundError | null> {
  try {
    await block();
    return null;
  } catch (error) {
    if (error instanceof RoundException) return error.error;
    throw error;
  }
}

/** Reicht jeden Aufruf an `inner` weiter; einzelne Aufrufe lassen sich danach überschreiben (Kotlin: `RoundApi by inner`). */
export function delegate(inner: RoundApi, overrides: Partial<RoundApi> = {}): RoundApi {
  const api: Record<string, unknown> = {};
  for (const name of ROUND_API_METHODS) {
    const method = inner[name] as (...args: unknown[]) => unknown;
    api[name] = overrides[name] ?? ((...args: unknown[]) => method.apply(inner, args));
  }
  return api as unknown as RoundApi;
}

/** Ein Server-Zugang, in den sich ein Test einklinken kann: `beforeSave` läuft vor dem nächsten Hochladen eines Abends. */
export class HookedApi implements RoundApi {
  beforeSave: (() => Promise<void>) | null = null;

  private readonly inner: RoundApi;
  readonly head: RoundApi["head"];
  readonly members: RoundApi["members"];
  readonly playersSince: RoundApi["playersSince"];
  readonly eveningsSince: RoundApi["eveningsSince"];
  readonly evening: RoundApi["evening"];
  readonly savePlayer: RoundApi["savePlayer"];
  readonly saveRules: RoundApi["saveRules"];
  readonly myRounds: RoundApi["myRounds"];
  readonly createRound: RoundApi["createRound"];
  readonly createInvite: RoundApi["createInvite"];
  readonly inviteInfo: RoundApi["inviteInfo"];
  readonly joinRound: RoundApi["joinRound"];
  readonly leaveRound: RoundApi["leaveRound"];
  readonly claimPlayer: RoundApi["claimPlayer"];

  constructor(inner: RoundApi) {
    const all = delegate(inner);
    this.inner = inner;
    this.head = all.head;
    this.members = all.members;
    this.playersSince = all.playersSince;
    this.eveningsSince = all.eveningsSince;
    this.evening = all.evening;
    this.savePlayer = all.savePlayer;
    this.saveRules = all.saveRules;
    this.myRounds = all.myRounds;
    this.createRound = all.createRound;
    this.createInvite = all.createInvite;
    this.inviteInfo = all.inviteInfo;
    this.joinRound = all.joinRound;
    this.leaveRound = all.leaveRound;
    this.claimPlayer = all.claimPlayer;
  }

  async saveEvening(round: RoundId, it: Evening, expectedVersion: number, deleted = false): Promise<SavedEvening | null> {
    const hook = this.beforeSave;
    this.beforeSave = null;
    if (hook !== null) await hook();
    return this.inner.saveEvening(round, it, expectedVersion, deleted);
  }
}

/** Ein nachgestelltes Handy: eigene Daten, eigener Zugang zum Server, eigener Abgleich. */
export class Device {
  readonly session: InMemoryRoundSession;
  readonly api: HookedApi;
  readonly players = new InMemoryPlayerRepository();
  readonly evenings = new InMemoryEveningRepository();
  readonly settings = new InMemorySettingsRepository();
  readonly store = new InMemoryRoundStore();
  private counter = 0;
  readonly ids: IdGenerator = { newId: () => `${this.userId}-${++this.counter}` };
  readonly editor: InMemoryEveningEditor;
  readonly sync: RoundSync;
  readonly connection: RoundConnection;

  /**
   * @param wiring Für Tests der Anbindung: der Zugang des Kontos beim Server im Speicher (zum Stören und Zählen) und
   *   der Weg dorthin, wenn er nicht direkt, sondern zum Beispiel über supabase-js führen soll.
   */
  constructor(
    server: InMemoryRoundServer,
    readonly userId: string,
    private readonly clock: TestClock,
    wiring: { session?: InMemoryRoundSession; api?: RoundApi } = {},
  ) {
    this.session = wiring.session ?? server.sessionFor(userId);
    this.api = new HookedApi(wiring.api ?? this.session);
    this.editor = new InMemoryEveningEditor(this.evenings, clock, this.ids);
    this.sync = new RoundSync(this.api, this.store, this.players, this.evenings, this.settings, this.editor);
    this.connection = new RoundConnection(this.api, this.store, this.players, this.evenings, this.sync, this.ids);
  }

  link(): Promise<RoundLink | null> {
    return this.store.link();
  }

  async roundId(): Promise<RoundId> {
    const link = await this.link();
    if (link === null) throw new Error(`${this.userId} ist mit keiner Runde verbunden`);
    return link.roundId;
  }

  evening(id: EveningId): Promise<Evening | null> {
    return this.evenings.evening(id);
  }

  /** Alle Abende, nach Kennung geordnet. */
  async allEvenings(): Promise<Evening[]> {
    return [...(await this.evenings.evenings())].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  async allPlayers(): Promise<readonly Player[]> {
    return this.players.players();
  }

  /** Legt die vorbelegten Spieler an, wie die App beim ersten Start. */
  async firstStart(): Promise<void> {
    for (const placeholder of PLACEHOLDERS) await this.players.save(placeholder);
  }

  async startEvening(id: string, seating: readonly PlayerId[] = SEATING): Promise<EveningId> {
    await this.evenings.save({
      id,
      startedAt: this.clock.now(),
      finishedAt: null,
      location: "",
      seating: [...seating],
      firstDealer: seating[0],
      rules: (await this.settings.settings()).defaultRules,
      entries: [],
    });
    return id;
  }

  /** Trägt ein Rufspiel ein, mit Kennung und Zeit wie in der App. */
  async addGame(eveningId: EveningId, won = true, laufende = 0): Promise<EntryId> {
    const id = this.editor.newEntryId();
    await this.editor.update(eveningId, (it) =>
      append(it, {
        ...game(id, 0, { declarer: it.seating[0], partner: it.seating[1], won, laufende }),
        recordedAt: this.editor.now(),
      }),
    );
    return id;
  }

  /** Ein Abgleich, der durchgelaufen sein muss. */
  async synced(): Promise<Synced> {
    const outcome = await this.sync.sync();
    if (outcome.kind !== "Synced") throw new Error(`Abgleich von ${this.userId}: ${JSON.stringify(outcome)}`);
    return outcome;
  }
}

/**
 * Ein kleiner Zufallsgenerator mit festem Startwert (Mulberry32): Jeder Lauf zieht dieselben Zahlen, auf jedem
 * Rechner. Er ersetzt Kotlins `Random(seed)`; die Zahlenfolge ist eine andere, die geprüften Eigenschaften dieselben.
 */
export class TestRandom {
  private state: number;

  constructor(seed: number) {
    this.state = seed >>> 0;
  }

  /** Eine Zahl in [0, 1). */
  private next(): number {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  /** Eine ganze Zahl in [0, until) bzw. mit zwei Angaben in [from, until). */
  nextInt(fromOrUntil: number, until?: number): number {
    const from = until === undefined ? 0 : fromOrUntil;
    const end = until === undefined ? fromOrUntil : until;
    return from + Math.floor(this.next() * (end - from));
  }

  nextBoolean(): boolean {
    return this.next() < 0.5;
  }

  /** Ein zufälliges Element (Kotlin: `random(random)`); die Liste darf nicht leer sein. */
  pick<T>(list: readonly T[]): T {
    if (list.length === 0) throw new Error("leere Liste");
    return list[this.nextInt(list.length)];
  }

  /** Ein zufälliges Element oder `null` für eine leere Liste (Kotlin: `randomOrNull(random)`). */
  pickOrNull<T>(list: readonly T[]): T | null {
    return list.length === 0 ? null : list[this.nextInt(list.length)];
  }

  /** Die Liste in zufälliger Reihenfolge (Kotlin: `shuffled(random)`). */
  shuffled<T>(list: readonly T[]): T[] {
    const result = [...list];
    for (let index = result.length - 1; index > 0; index--) {
      const other = this.nextInt(index + 1);
      [result[index], result[other]] = [result[other], result[index]];
    }
    return result;
  }
}
