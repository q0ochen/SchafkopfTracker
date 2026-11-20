/*
 * Hält die gemeinsame Runde in der Web-App am Laufen (docs/ARCHITEKTUR.md, Abschnitt 8.6 und 8.7) und sagt der
 * Oberfläche, wie es um sie steht. Entspricht dem `RoundCoordinator` der Android-App: Anmeldung, Verbinden, „Das bin
 * ich“, Abgleich mit Planer, Statuszeile, Hinweise und Meldungen.
 *
 * Die Anbindung an den Server (supabase-js) wird erst hier und erst bei Bedarf geladen. Solange sie nicht da ist,
 * weiß die Oberfläche noch nicht, wer angemeldet ist (`loaded` ist `false`).
 */
import type { Player, PlayerId } from "../core/model";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundConnection } from "../core/round/RoundConnection";
import {
  RoundException,
  type InviteInfo,
  type RemotePlayer,
  type RoundLink,
  type RoundSummary,
} from "../core/round/RoundModels";
import type {
  EveningEditor,
  EveningRepository,
  IdGenerator,
  PlayerRepository,
  RoundApi,
  RoundStore,
  SettingsRepository,
} from "../core/round/RoundPorts";
import { RoundSync, SyncOutcome, type SyncNotice } from "../core/round/RoundSync";
import type { Account, RoundBackend } from "./backend";
import { deferredApi } from "./deferredApi";
import type { InviteMemory } from "./inviteMemory";
import { SyncScheduler, type SchedulerTiming } from "./scheduler";
import type { SignInReturn } from "./signInReturn";
import type { RoundMessage, RoundProblem } from "./texts";

/**
 * Der Stand der gemeinsamen Runde für die Oberfläche (in der Android-App `RoundState`).
 *
 * @property configured `false`: Diese App wurde ohne Adresse des Servers gebaut; die Funktion ist abgeschaltet.
 * @property loaded Die Anmeldung ist gelesen (vorher weiß die Oberfläche noch nicht, ob jemand angemeldet ist).
 * @property pending So viele Änderungen dieses Geräts sind noch nicht auf dem Server.
 * @property players Der Stand der Spieler auf dem Server (Besitzer, fester Name), solange eine Runde verbunden ist.
 */
export interface RoundState {
  readonly configured: boolean;
  readonly loaded: boolean;
  readonly account: Account | null;
  readonly link: RoundLink | null;
  readonly syncing: boolean;
  readonly problem: RoundProblem | null;
  readonly pending: number;
  readonly unreadable: number;
  readonly refused: number;
  readonly notices: readonly SyncNotice[];
  readonly players: ReadonlyMap<PlayerId, RemotePlayer>;
  readonly pendingInvite: string | null;
  readonly message: RoundMessage | null;
}

/** Der Stand einer App, die ohne Server gebaut wurde. */
export const NOT_CONFIGURED: RoundState = {
  configured: false,
  loaded: true,
  account: null,
  link: null,
  syncing: false,
  problem: null,
  pending: 0,
  unreadable: 0,
  refused: 0,
  notices: [],
  players: new Map(),
  pendingInvite: null,
  message: null,
};

/** Verbunden, aber die Anmeldung fehlt oder ist abgelaufen: Bis zur neuen Anmeldung wird nichts abgeglichen. */
export function needsSignIn(state: RoundState): boolean {
  return state.loaded && state.link !== null && (state.account === null || state.problem === "SIGNED_OUT");
}

/** So viele Hinweise aus dem Abgleich bleiben höchstens stehen. */
const MAX_NOTICES = 20;

/** Was sich das Gerät zum Abgleich merkt, mit Meldung bei Änderungen. */
export type ObservableRoundStore = RoundStore & { subscribe(listener: () => void): () => void };

/** Spieler, Abende und Einstellungen dieses Geräts, wie der Abgleich sie braucht (in der App: `StoreAdapters`). */
export interface RoundData {
  readonly players: PlayerRepository;
  readonly evenings: EveningRepository;
  readonly settings: SettingsRepository;
  readonly editor: EveningEditor;
  /** Meldet jede Änderung an Spielern, Abenden und dem Regelwerk für neue Abende, die nicht der Abgleich gemacht hat. */
  onLocalChange(listener: () => void): () => void;
}

export interface RoundControllerOptions {
  /** Lädt die Anbindung an den Server. Wird wiederholt, bis es klappt. */
  readonly loadBackend: () => Promise<RoundBackend>;
  readonly store: ObservableRoundStore;
  readonly data: RoundData;
  readonly invites: InviteMemory;
  /** Die vorbelegten Spieler der App (siehe `RoundConnection.create`). */
  readonly placeholders: readonly Player[];
  /** Die Start-Adresse der Web-App, für Einladungslinks. */
  readonly webAppUrl: string;
  readonly ids: IdGenerator;
  /** Die verbundene Runde, wie sie beim Start der App im Speicher stand (damit die Oberfläche sie sofort zeigt). */
  readonly initialLink?: RoundLink | null;
  readonly timing?: SchedulerTiming;
}

function sameAccount(a: Account | null, b: Account | null): boolean {
  return a?.userId === b?.userId && a?.email === b?.email;
}

export class RoundController {
  private state: RoundState;
  private readonly listeners = new Set<() => void>();
  /** Der Server; die Anbindung dahinter wird beim ersten Aufruf geladen. */
  private readonly api: RoundApi;
  private readonly sync: RoundSync;
  private readonly connection: RoundConnection;
  private readonly scheduler: SyncScheduler;

  private backend: RoundBackend | null = null;
  private backendLoading: Promise<RoundBackend> | null = null;
  private loading: Promise<boolean> | null = null;
  private signInReturn: SignInReturn | null = null;
  private started = false;

  /** Wer angemeldet und welche Runde verbunden ist, als ein Text: Ändert er sich, wird sofort abgeglichen. */
  private known: string | null = null;
  private refreshing: Promise<void> = Promise.resolve();

  constructor(private readonly options: RoundControllerOptions) {
    this.api = deferredApi(async () => (await this.loadBackend()).api);
    const { store, data } = options;
    this.sync = new RoundSync(this.api, store, data.players, data.evenings, data.settings, data.editor);
    this.connection = new RoundConnection(this.api, store, data.players, data.evenings, this.sync, options.ids);
    this.scheduler = new SyncScheduler(
      {
        // Solange die Anmeldung nicht gelesen ist, versucht die Schleife das (mit wachsendem Abstand, wenn es scheitert).
        canSync: () => !this.state.loaded || (this.state.link !== null && this.state.account !== null),
        pending: () => this.state.pending,
        run: (full) => this.run(full),
      },
      options.timing,
    );
    this.state = { ...NOT_CONFIGURED, configured: true, loaded: false, link: options.initialLink ?? null };
  }

  getState(): RoundState {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private setState(changes: Partial<RoundState>): void {
    this.state = { ...this.state, ...changes };
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * Beginnt zu arbeiten; einmal beim Start der App.
   *
   * @param visible Ob die App gerade zu sehen ist.
   * @param signInReturn Die Rückkehr aus der Anmeldung, wenn die App damit geöffnet wurde.
   */
  start(visible: boolean, signInReturn: SignInReturn | null = null): void {
    if (this.started) return;
    this.started = true;
    this.signInReturn = signInReturn;
    this.setState({ pendingInvite: this.options.invites.get() });
    this.options.store.subscribe(() => void this.refresh());
    this.options.data.onLocalChange(() => this.scheduler.localChange());
    // Die Anmeldung auch dann lesen, wenn die App im Hintergrund startet; abgeglichen wird erst, wenn sie zu sehen ist.
    void this.ensureLoaded();
    this.scheduler.start(visible);
  }

  /** Hört auf zu arbeiten (zum Beispiel, wenn die Datenbank nicht mehr benutzbar ist, und in Tests). */
  stop(): void {
    this.scheduler.stop();
  }

  /** Die App ist zu sehen bzw. nicht mehr zu sehen. */
  setVisible(visible: boolean): void {
    this.scheduler.setVisible(visible);
  }

  /** Ein Anstoß von außen, zum Beispiel wenn das Gerät wieder Netz hat. */
  wake(): void {
    this.scheduler.wake();
  }

  /** „Jetzt abgleichen“: sofort und vollständig. */
  syncNow(): void {
    this.scheduler.syncNow();
  }

  // --- Anbindung und Anmeldung laden ---

  private loadBackend(): Promise<RoundBackend> {
    if (this.backend !== null) return Promise.resolve(this.backend);
    this.backendLoading ??= this.options.loadBackend().then(
      (backend) => {
        this.backend = backend;
        backend.auth.subscribe(() => void this.refresh());
        return backend;
      },
      (error: unknown) => {
        // Beim nächsten Mal noch einmal versuchen.
        this.backendLoading = null;
        throw new RoundException("OFFLINE", "Anbindung nicht geladen", error);
      },
    );
    return this.backendLoading;
  }

  /** Lädt die Anbindung und liest die Anmeldung. @returns `false`, wenn das (noch) nicht geklappt hat. */
  private ensureLoaded(): Promise<boolean> {
    if (this.state.loaded) return Promise.resolve(true);
    this.loading ??= this.load().then(
      () => true,
      (error: unknown) => {
        console.warn("Schafkopf Tracker: gemeinsame Runde noch nicht bereit", error);
        this.loading = null;
        return false;
      },
    );
    return this.loading;
  }

  private async load(): Promise<void> {
    const backend = await this.loadBackend();
    await backend.auth.load();
    const back = this.signInReturn;
    this.signInReturn = null;
    let message: RoundMessage | null = null;
    let signedIn = false;
    if (back?.kind === "failure") {
      message = back.error === "access_denied" ? "SIGN_IN_CANCELLED" : "SIGN_IN_FAILED";
    } else if (back?.kind === "code") {
      try {
        await backend.auth.completeSignIn(back.code);
        signedIn = true;
      } catch (error) {
        console.warn("Schafkopf Tracker: Anmeldung nicht abgeschlossen", error);
        message = "SIGN_IN_FAILED";
      }
    }
    this.setState({ loaded: true, ...(message === null ? {} : { message }) });
    await this.refresh();
    if (signedIn) this.scheduler.syncNow();
  }

  /**
   * Liest, wer angemeldet und welche Runde verbunden ist, und sagt es der Oberfläche. Läuft nach jeder Änderung der
   * Anmeldung und der Verbindung, immer der Reihe nach.
   */
  private refresh(): Promise<void> {
    const run = this.refreshing.then(() => this.refreshNow());
    this.refreshing = run.catch((error: unknown) => {
      console.warn("Schafkopf Tracker: Stand der gemeinsamen Runde nicht gelesen", error);
    });
    return this.refreshing;
  }

  private async refreshNow(): Promise<void> {
    let link = await this.options.store.link();
    if (!this.state.loaded) {
      // Die Anmeldung ist noch nicht gelesen: nur die Verbindung zeigen.
      const players = link === null ? new Map<PlayerId, RemotePlayer>() : new Map(await this.options.store.playerBases());
      this.setState({ link, players });
      return;
    }
    let otherAccount = false;
    const signedIn = this.backend?.auth.account() ?? null;
    if (signedIn !== null && link !== null && link.userId !== signedIn.userId) {
      // Die Runde hängt an einem anderen Konto: lösen, sonst liefe der Abgleich unter falschem Namen.
      await this.connection.disconnect();
      link = await this.options.store.link();
      otherAccount = true;
    }
    const players = link === null ? new Map<PlayerId, RemotePlayer>() : new Map(await this.options.store.playerBases());
    // Ab hier ohne Unterbrechung: Was inzwischen an Meldungen und Zählern dazukam, bleibt stehen.
    const account = this.backend?.auth.account() ?? null;
    const current = this.state;
    const message: Partial<RoundState> = otherAccount ? { message: "OTHER_ACCOUNT" } : {};
    if (link === null) {
      // Ohne Runde gibt es nichts abzugleichen: alle Zähler zurück.
      this.setState({
        account,
        link: null,
        syncing: false,
        problem: null,
        pending: 0,
        unreadable: 0,
        refused: 0,
        notices: [],
        players,
        ...message,
      });
    } else {
      this.setState({
        account,
        link,
        players,
        // Nach einer neuen Anmeldung gilt die alte Meldung nicht mehr.
        problem: current.problem === "SIGNED_OUT" && !sameAccount(account, current.account) ? null : current.problem,
        ...message,
      });
    }
    const now = `${account?.userId ?? ""}|${link?.roundId ?? ""}`;
    if (now !== this.known) {
      this.known = now;
      this.scheduler.wake();
    }
  }

  // --- Anmelden ---

  /** Beginnt die Anmeldung bei Google; die Seite wird dafür verlassen. */
  async signIn(): Promise<void> {
    await (await this.loadBackend()).auth.signIn();
  }

  /** Meldet ab und löst die Verbindung auf diesem Gerät. Die Daten bleiben als Kopie hier. */
  async signOut(): Promise<void> {
    await this.connection.disconnect();
    await (await this.loadBackend()).auth.signOut();
    await this.refresh();
  }

  // --- Runden ---

  /** Die Runden des angemeldeten Kontos. */
  myRounds(): Promise<RoundSummary[]> {
    return this.api.myRounds();
  }

  inviteInfo(token: string): Promise<InviteInfo> {
    return this.api.inviteInfo(token);
  }

  /** Ob auf dem Gerät etwas liegt, das beim Beitreten ersetzt würde. */
  hasOwnData(): Promise<boolean> {
    return this.connection.hasOwnData(this.options.placeholders);
  }

  async create(name: string): Promise<SyncOutcome> {
    const seen = this.scheduler.seen();
    return this.applied(await this.connection.create(name, this.userId(), this.options.placeholders), seen);
  }

  /** Tritt bei und übernimmt die Daten der Runde; die Daten dieses Geräts werden dabei ersetzt. */
  async join(token: string): Promise<SyncOutcome> {
    const seen = this.scheduler.seen();
    const outcome = await this.connection.join(token, this.userId());
    this.rememberInvite(null);
    return this.applied(outcome, seen);
  }

  async use(round: RoundSummary): Promise<SyncOutcome> {
    const seen = this.scheduler.seen();
    return this.applied(await this.connection.use(round, this.userId()), seen);
  }

  /** „Das bin ich“; `player === null` heißt „Ich bin neu“. */
  async claim(player: PlayerId | null, name: string): Promise<SyncOutcome> {
    const seen = this.scheduler.seen();
    return this.applied(await this.connection.claim(player, name), seen);
  }

  async leave(): Promise<void> {
    await this.connection.leave();
    await this.refresh();
  }

  /** Erzeugt eine Einladung und liefert den Link zum Weitergeben. */
  async inviteLink(): Promise<string> {
    const link = this.state.link;
    if (link === null) throw new RoundException("NOT_MEMBER");
    return InviteLinks.webLink(this.options.webAppUrl, await this.api.createInvite(link.roundId));
  }

  /** Wie viele Änderungen gerade noch warten (für die Rückfrage vor dem Verlassen). */
  pendingCount(): Promise<number> {
    return this.sync.pendingCount();
  }

  rememberInvite(token: string | null): void {
    this.options.invites.set(token);
    this.setState({ pendingInvite: token });
  }

  clearMessage(): void {
    this.setState({ message: null });
  }

  clearNotices(): void {
    this.setState({ notices: [] });
  }

  /**
   * Ein anderes Fenster der App hat die Daten auf dem Gerät geändert: neu lesen, während kein Abgleich läuft.
   *
   * @param reload Liest Spieler, Abende, Einstellungen und das Gemerkte der Runde neu aus dem Speicher.
   */
  async changedElsewhere(reload: () => Promise<void>): Promise<void> {
    await this.sync.exclusive(reload);
    await this.refresh();
  }

  // --- Abgleich ---

  private userId(): string {
    const userId = this.state.account?.userId;
    if (userId === undefined) throw new RoundException("SIGNED_OUT");
    return userId;
  }

  private async run(full: boolean): Promise<SyncOutcome> {
    if (!(await this.ensureLoaded())) {
      await this.apply(SyncOutcome.Offline);
      return SyncOutcome.Offline;
    }
    if (this.state.link === null || this.state.account === null) return SyncOutcome.NotLinked;
    this.setState({ syncing: true });
    let outcome: SyncOutcome;
    try {
      outcome = await this.sync.sync(!full);
    } catch (error) {
      // Zum Beispiel ein Fehler der Datenbank: Der nächste Versuch kommt von selbst.
      console.warn("Schafkopf Tracker: Abgleich abgebrochen", error);
      outcome = SyncOutcome.failed("SERVER");
    }
    await this.apply(outcome);
    return outcome;
  }

  /** Das Ergebnis eines Abgleichs außerhalb der Schleife (Verbinden, „Das bin ich“). */
  private async applied(outcome: SyncOutcome, seen: number): Promise<SyncOutcome> {
    this.scheduler.applied(outcome, seen);
    await this.refresh();
    await this.apply(outcome);
    return outcome;
  }

  private async apply(outcome: SyncOutcome): Promise<void> {
    switch (outcome.kind) {
      case "Synced":
        this.setState({
          syncing: false,
          problem: null,
          pending: outcome.pending,
          unreadable: outcome.unreadable,
          refused: outcome.refused,
          notices: [...this.state.notices, ...outcome.notices].slice(-MAX_NOTICES),
        });
        await this.refresh();
        break;
      case "Unchanged":
        this.setState({ syncing: false, problem: null });
        break;
      case "Offline":
        await this.failed("OFFLINE");
        break;
      case "Failed":
        await this.failed("SERVER");
        break;
      case "SignedOut":
        await this.failed("SIGNED_OUT");
        break;
      case "Detached":
        this.setState({ syncing: false, message: "DETACHED" });
        await this.refresh();
        break;
      case "NotLinked":
        this.setState({ syncing: false });
        break;
    }
  }

  private async failed(problem: RoundProblem): Promise<void> {
    let pending = this.state.pending;
    try {
      pending = await this.sync.pendingCount();
    } catch {
      // Der gemerkte Wert bleibt stehen.
    }
    this.setState({ syncing: false, problem, pending });
  }
}
