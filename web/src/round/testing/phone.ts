/*
 * Ein nachgestelltes Handy mit der Web-App für Tests der gemeinsamen Runde: echter Zustand der App (`AppStore` über
 * einem Speicher im Arbeitsspeicher, im Dateiformat der App), echte Anbindung an den Zustand, echter Abgleich mit
 * Planer – nur der Server und die Anmeldung sind nachgestellt (`InMemoryRoundServer`, {@link FakeAuth}).
 */
import { storageCodec } from "../../app/formatAdapters";
import { defaultPlayers } from "../../app/players";
import { AppStore, type Clock } from "../../app/store";
import { instantFromEpochMillis } from "../../core/instant";
import type { Evening, EveningId, GameEntry } from "../../core/model";
import { InMemoryRoundStore, type InMemoryRoundServer, type InMemoryRoundSession } from "../../core/round/InMemoryRound";
import { RoundException } from "../../core/round/RoundModels";
import { InMemoryRepository } from "../../data/repository";
import type { Account, RoundAuth, RoundBackend } from "../backend";
import { RoundController } from "../controller";
import { deferredApi } from "../deferredApi";
import type { InviteMemory } from "../inviteMemory";
import { RoundScreenModel } from "../screenModel";
import { StoreAdapters } from "../storeAdapters";

/** Die Start-Adresse der Web-App in Tests. */
export const WEB_APP_URL = "https://app.example/SchafkopfTracker/";

/** Die Anmeldung, nachgestellt: Wer sich anmeldet, bestimmt der Test. */
export class FakeAuth implements RoundAuth {
  private current: Account | null;
  private readonly listeners = new Set<(account: Account | null) => void>();
  /** So oft wurde die Anmeldung bei Google begonnen. */
  signInsStarted = 0;
  /** Mit diesem Konto kehrt die nächste Anmeldung zurück (Code „gut“). */
  nextAccount: Account | null = null;

  constructor(initial: Account | null = null) {
    this.current = initial;
  }

  async load(): Promise<Account | null> {
    return this.current;
  }

  account(): Account | null {
    return this.current;
  }

  subscribe(listener: (account: Account | null) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  async signIn(): Promise<void> {
    this.signInsStarted++;
  }

  async completeSignIn(code: string): Promise<Account> {
    if (code !== "gut" || this.nextAccount === null) throw new RoundException("SIGNED_OUT", "Anmeldung");
    this.set(this.nextAccount);
    return this.nextAccount;
  }

  async signOut(): Promise<void> {
    this.set(null);
  }

  /** Setzt das angemeldete Konto, wie es die Anmeldung von sich aus tut (zum Beispiel, wenn die Sitzung abläuft). */
  set(account: Account | null): void {
    this.current = account;
    for (const listener of [...this.listeners]) listener(account);
  }
}

export function account(number: number): Account {
  return { userId: `konto-${number}`, email: `konto${number}@example.com` };
}

/** Eine Uhr, die mit der gestellten Uhr des Tests läuft (`vi.useFakeTimers`), und fortlaufende Kennungen. */
export function testClock(prefix: string): Clock {
  let next = 0;
  return { now: () => instantFromEpochMillis(Date.now()), newId: () => `${prefix}-${++next}` };
}

export class Phone {
  readonly auth: FakeAuth;
  readonly roundStore = new InMemoryRoundStore();
  readonly adapters: StoreAdapters;
  readonly controller: RoundController;
  /** Was sich das Handy als Einladung gemerkt hat. */
  invite: string | null = null;
  /** `true`: Die Anbindung an den Server lässt sich gerade nicht laden. */
  backendFails = false;
  /** So oft wurde versucht, die Anbindung zu laden. */
  backendLoads = 0;
  private readonly sessions = new Map<string, InMemoryRoundSession>();

  private constructor(
    private readonly server: InMemoryRoundServer,
    readonly store: AppStore,
    /** Der Speicher des Handys (zum Stören: `failWrites`). */
    readonly repository: InMemoryRepository,
    signedIn: Account | null,
  ) {
    this.auth = new FakeAuth(signedIn);
    this.adapters = new StoreAdapters(store, storageCodec.encodeEvening);
    const invites: InviteMemory = {
      get: () => this.invite,
      set: (token) => {
        this.invite = token;
      },
    };
    const backend: RoundBackend = {
      auth: this.auth,
      api: deferredApi(async () => {
        const current = this.auth.account();
        if (current === null) throw new RoundException("SIGNED_OUT");
        return this.session(current.userId);
      }),
    };
    this.controller = new RoundController({
      loadBackend: async () => {
        this.backendLoads++;
        if (this.backendFails) throw new Error("Anbindung nicht ladbar (Test)");
        return backend;
      },
      store: this.roundStore,
      data: this.adapters,
      invites,
      placeholders: defaultPlayers(),
      webAppUrl: WEB_APP_URL,
      ids: { newId: () => store.clock.newId() },
    });
  }

  /** Ein frisch installiertes Handy; `signedIn`: Dieses Konto ist schon angemeldet. */
  static async create(server: InMemoryRoundServer, name: string, signedIn: Account | null = null): Promise<Phone> {
    const repository = new InMemoryRepository(storageCodec);
    return new Phone(server, await AppStore.load(repository, testClock(name)), repository, signedIn);
  }

  /** Der Zugang des Kontos beim Server (zum Stören und Zählen). */
  session(userId: string): InMemoryRoundSession {
    let session = this.sessions.get(userId);
    if (session === undefined) {
      session = this.server.sessionFor(userId);
      this.sessions.set(userId, session);
    }
    return session;
  }

  /** Der Zugang des angemeldeten Kontos. */
  get mine(): InMemoryRoundSession {
    const current = this.auth.account();
    if (current === null) throw new Error("niemand angemeldet");
    return this.session(current.userId);
  }

  get state() {
    return this.controller.getState();
  }

  /** Der Bildschirm „Gemeinsame Runde“ dieses Handys. */
  screen(): RoundScreenModel {
    return new RoundScreenModel(this.controller, () => this.store.getState().evenings.length);
  }

  /** Startet einen Abend mit den ersten vier Spielern des Handys. */
  async startEvening(id: EveningId): Promise<Evening> {
    const seating = this.store.getState().players.slice(0, 4).map((it) => it.id);
    const evening: Evening = {
      id,
      startedAt: this.store.clock.now(),
      finishedAt: null,
      location: "",
      seating,
      firstDealer: seating[0],
      rules: this.store.getState().settings.defaultRules,
      entries: [],
    };
    await this.store.saveEvening(evening);
    return evening;
  }

  /** Trägt ein Rufspiel ein, wie die Eingabemaske. */
  async addGame(eveningId: EveningId): Promise<string> {
    const id = this.store.clock.newId();
    await this.store.updateEvening(eveningId, (evening) => {
      const entry: GameEntry = {
        kind: "game",
        id,
        recordedAt: this.store.clock.now(),
        type: "RUFSPIEL",
        suit: null,
        declarer: evening.seating[0],
        partner: evening.seating[1],
        declarerWon: true,
        level: "NORMAL",
        laufende: 0,
        announcement: "NONE",
        klopfer: [],
        kontra: "NONE",
        kontraBy: null,
        retourBy: null,
        bockOverride: "AUTO",
        declarerEyes: null,
        note: "",
      };
      return { ...evening, entries: [...evening.entries, entry] };
    });
    return id;
  }

  entryIds(eveningId: EveningId): string[] {
    return (this.store.evening(eveningId)?.entries ?? []).map((it) => it.id);
  }
}
