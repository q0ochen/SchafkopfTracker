/*
 * Ein nachgestelltes Handy mit der Web-App für Tests der gemeinsamen Runde: echter Zustand der App (`AppStore` über
 * einem Speicher im Arbeitsspeicher, im Dateiformat der App), echte Anbindung an den Zustand, echter Abgleich mit
 * Planer – nur der Server und die Anmeldung sind nachgestellt (`InMemoryRoundServer`, {@link FakeAuth}).
 */
import { storageCodec } from "../../app/formatAdapters";
import { defaultPlayers } from "../../app/players";
import { AppStore, type Clock } from "../../app/store";
import { instantFromEpochMillis } from "../../core/instant";
import type { Evening, EveningId, GameEntry, PlayerId } from "../../core/model";
import { InMemoryRoundStore, type InMemoryRoundServer, type InMemoryRoundSession } from "../../core/round/InMemoryRound";
import { RoundException, type EveningBase, type RemotePlayer, type RoundLink } from "../../core/round/RoundModels";
import type { SyncGuard } from "../../core/round/RoundSync";
import { InMemoryRepository } from "../../data/repository";
import { NO_SIGN_IN_PENDING, type Account, type RoundAuth, type RoundBackend } from "../backend";
import { RoundController, type ObservableRoundStore } from "../controller";
import { deferredApi } from "../deferredApi";
import type { InviteMemory, IssuedInvite, IssuedInviteMemory } from "../inviteMemory";
import { RoundScreenModel } from "../screenModel";
import type { SignInReturn } from "../signInReturn";
import { StoreAdapters } from "../storeAdapters";

/** Die Start-Adresse der Web-App in Tests. */
export const WEB_APP_URL = "https://app.example/SchafkopfTracker/";

/** Die Anmeldung, nachgestellt: Wer sich anmeldet, bestimmt der Test. */
export class FakeAuth implements RoundAuth {
  private current: Account | null;
  private readonly listeners = new Set<(account: Account | null) => void>();
  /** So oft wurde die Anmeldung bei Google begonnen. */
  signInsStarted = 0;
  /** So oft wurde abgemeldet. */
  signOuts = 0;
  /** Mit diesem Konto kehrt die nächste Anmeldung zurück (Code „gut“). */
  nextAccount: Account | null = null;
  /** Zur Rückkehr gibt es keine begonnene Anmeldung (zum Beispiel, weil dieselbe Adresse ein zweites Mal kommt). */
  noPendingSignIn = false;
  /** Solange gesetzt, hängt das Einlösen der Anmeldung, bis der Test das Versprechen erfüllt. */
  exchange: Promise<void> | null = null;

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
    if (this.exchange !== null) await this.exchange;
    if (this.noPendingSignIn) throw new RoundException("SIGNED_OUT", NO_SIGN_IN_PENDING);
    if (code !== "gut" || this.nextAccount === null) throw new RoundException("SIGNED_OUT", "Anmeldung");
    this.set(this.nextAccount);
    return this.nextAccount;
  }

  async signOut(): Promise<void> {
    this.signOuts++;
    this.set(null);
  }

  /** Setzt das angemeldete Konto, wie es die Anmeldung von sich aus tut (zum Beispiel, wenn die Sitzung abläuft). */
  set(account: Account | null): void {
    this.current = account;
    for (const listener of [...this.listeners]) listener(account);
  }
}

/**
 * Eine Sicht auf die Ablage, die wie `IndexedDbRoundStore` aus dem Arbeitsspeicher antwortet: Was ein anderes Fenster
 * in die Ablage schreibt, sieht sie erst nach {@link invalidate}. Die Basen reicht sie unverändert durch.
 */
export class CachedRoundStore implements ObservableRoundStore {
  private cached: { readonly link: RoundLink | null } | null = null;
  private readonly listeners = new Set<() => void>();

  constructor(private readonly inner: InMemoryRoundStore) {}

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify(): void {
    for (const listener of [...this.listeners]) listener();
  }

  invalidate(notify = true): void {
    this.cached = null;
    if (notify) this.notify();
  }

  async link(): Promise<RoundLink | null> {
    this.cached ??= { link: await this.inner.link() };
    return this.cached.link;
  }

  async setLink(link: RoundLink | null): Promise<void> {
    await this.inner.setLink(link);
    this.cached = { link };
    this.notify();
  }

  eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    return this.inner.eveningBases();
  }

  setEveningBase(id: EveningId, base: EveningBase | null): Promise<void> {
    return this.inner.setEveningBase(id, base);
  }

  playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    return this.inner.playerBases();
  }

  setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    return this.inner.setPlayerBase(id, base);
  }

  async clearBases(): Promise<void> {
    await this.inner.clearBases();
    this.notify();
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
  /** Die Anbindung an den Zustand; nach {@link restart} eine neue. */
  adapters: StoreAdapters;
  /** Die Steuerung; nach {@link reload} eine neue, wie nach dem Neuladen der Seite. */
  controller: RoundController;
  /** Was sich das Handy als Einladung gemerkt hat. */
  invite: string | null = null;
  /** Die zuletzt erzeugte Einladung, wie sie das Handy im Speicher des Browsers hätte. */
  issued: IssuedInvite | null = null;
  /** `true`: Die Anbindung an den Server lässt sich gerade nicht laden. */
  backendFails = false;
  /** Solange gesetzt, hängt das Laden der Anbindung, bis der Test das Versprechen erfüllt (langsames Netz). */
  backendGate: Promise<void> | null = null;
  /** So oft wurde versucht, die Anbindung zu laden. */
  backendLoads = 0;
  private readonly sessions = new Map<string, InMemoryRoundSession>();
  private readonly backend: RoundBackend;
  /** Was die Steuerung als Ablage sieht: die Ablage selbst oder eine zwischenspeichernde Sicht darauf. */
  private readonly view: ObservableRoundStore;

  private constructor(
    private readonly server: InMemoryRoundServer,
    /** Der Zustand der App; nach {@link restart} ein neu geladener. */
    public store: AppStore,
    /** Der Speicher des Handys (zum Stören: `failWrites`). */
    readonly repository: InMemoryRepository,
    signedIn: Account | null,
    cachedStore: boolean,
    private readonly name: string,
    /** Läuft um jeden Abgleich (wie in der App die Sperre über alle Fenster). */
    private readonly guard: SyncGuard | undefined,
  ) {
    this.view = cachedStore ? new CachedRoundStore(this.roundStore) : this.roundStore;
    this.auth = new FakeAuth(signedIn);
    this.adapters = new StoreAdapters(store, storageCodec.encodeEvening);
    this.backend = {
      auth: this.auth,
      api: deferredApi(async () => {
        const current = this.auth.account();
        if (current === null) throw new RoundException("SIGNED_OUT");
        return this.session(current.userId);
      }),
    };
    this.controller = this.newController(null);
  }

  private newController(initialLink: RoundLink | null): RoundController {
    const invites: InviteMemory = {
      get: () => this.invite,
      set: (token) => {
        this.invite = token;
      },
    };
    const issuedInvites: IssuedInviteMemory = {
      get: () => this.issued,
      set: (invite) => {
        this.issued = invite;
      },
    };
    return new RoundController({
      loadBackend: async () => {
        this.backendLoads++;
        if (this.backendGate !== null) await this.backendGate;
        if (this.backendFails) throw new Error("Anbindung nicht ladbar (Test)");
        return this.backend;
      },
      store: this.adapters.shield(this.view),
      data: this.adapters,
      invites,
      issuedInvites,
      placeholders: defaultPlayers(),
      webAppUrl: WEB_APP_URL,
      ids: { newId: () => this.store.clock.newId() },
      initialLink,
      guard: this.guard,
    });
  }

  /**
   * Lädt die Seite neu: Die alte Steuerung hört auf, eine neue beginnt über denselben Daten, derselben Anmeldung und
   * demselben Gemerkten der Runde – wie nach der Rückkehr aus der Anmeldung.
   */
  async reload(signInReturn: SignInReturn | null = null, visible = true): Promise<RoundController> {
    this.controller.stop();
    this.view.invalidate?.();
    this.controller = this.newController(await this.view.link());
    this.controller.start(visible, signInReturn);
    return this.controller;
  }

  /**
   * Startet die App ganz neu, wie nach dem Neuladen der Seite: Auch der Zustand der App wird neu aus dem Speicher
   * geladen (zum Beispiel, nachdem ein Test ihn beschädigt hat). Die Steuerung beginnt noch nicht von selbst.
   */
  async restart(): Promise<RoundController> {
    this.controller.stop();
    this.view.invalidate?.();
    const link = await this.view.link();
    this.store = await AppStore.load(this.repository, testClock(`${this.name}-neu`), { seedPlayers: link === null });
    this.adapters = new StoreAdapters(this.store, storageCodec.encodeEvening);
    this.controller = this.newController(link);
    return this.controller;
  }

  /**
   * Ein frisch installiertes Handy; `signedIn`: Dieses Konto ist schon angemeldet.
   *
   * @param options.cachedStore Die Steuerung sieht die Ablage wie im Browser durch einen Zwischenspeicher
   *   ({@link CachedRoundStore}); was ein anderes Fenster direkt in {@link roundStore} schreibt, sieht sie erst später.
   */
  static async create(
    server: InMemoryRoundServer,
    name: string,
    signedIn: Account | null = null,
    options: { cachedStore?: boolean; guard?: SyncGuard } = {},
  ): Promise<Phone> {
    const repository = new InMemoryRepository(storageCodec);
    return new Phone(
      server,
      await AppStore.load(repository, testClock(name)),
      repository,
      signedIn,
      options.cachedStore ?? false,
      name,
      options.guard,
    );
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

  /** Der Bildschirm „Gemeinsame Runden“ dieses Handys. */
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
