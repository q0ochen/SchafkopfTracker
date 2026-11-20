/*
 * Hält die gemeinsame Runde in der Web-App am Laufen (docs/ARCHITEKTUR.md, Abschnitt 8.6 und 8.7) und sagt der
 * Oberfläche, wie es um sie steht. Entspricht dem `RoundCoordinator` der Android-App: Anmeldung, Verbinden, „Das bin
 * ich“, Abgleich mit Planer, Statuszeile, Hinweise und Meldungen.
 *
 * Die Anbindung an den Server (supabase-js) wird erst hier und erst bei Bedarf geladen. Solange sie nicht da ist,
 * weiß die Oberfläche noch nicht, wer angemeldet ist (`loaded` ist `false`).
 *
 * Mit verbundener Runde spricht das Gerät nur als das Konto mit dem Server, mit dem es der Runde beigetreten ist. Mit
 * einem anderen Konto sähe der Server die Runde nicht, und der Abgleich hielte sie für aufgelöst und trennte sie. Ein
 * anderes Konto wird deshalb gleich wieder abgemeldet; die Runde bleibt verbunden, und was noch wartet, geht nicht
 * verloren.
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
import { NO_SIGN_IN_PENDING, type Account, type RoundBackend } from "./backend";
import { deferredApi } from "./deferredApi";
import type { InviteMemory } from "./inviteMemory";
import { SyncScheduler, type SchedulerTiming } from "./scheduler";
import type { SignInReturn } from "./signInReturn";
import { SIGN_IN_MESSAGES, type RoundMessage, type RoundProblem } from "./texts";

/**
 * Der Stand der gemeinsamen Runde für die Oberfläche (in der Android-App `RoundState`).
 *
 * @property configured `false`: Diese App wurde ohne Adresse des Servers gebaut; die Funktion ist abgeschaltet.
 * @property loaded Die Anmeldung ist gelesen (vorher weiß die Oberfläche noch nicht, ob jemand angemeldet ist).
 * @property pending So viele Änderungen dieses Geräts sind noch nicht auf dem Server.
 * @property unreadable So viele Abende des Servers kann diese Version nicht lesen (gemerkt in `RoundLink.unreadable`).
 * @property refused So viele Abende hat der Server in dieser Sitzung nicht angenommen.
 * @property players Der Stand der Spieler auf dem Server (Besitzer, fester Name), solange eine Runde verbunden ist.
 * @property signingIn Die Anmeldung wird nach der Rückkehr aus dem Browser gerade eingelöst; solange ruhen die Knöpfe.
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
  readonly signingIn: boolean;
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
  signingIn: false,
};

/**
 * Ob angemeldet ist, wer zur verbundenen Runde gehört. Nur dann wird abgeglichen: Mit einem anderen Konto sähe der
 * Server die Runde nicht, und der Abgleich hielte sie für aufgelöst.
 */
function signedInForRound(state: Pick<RoundState, "account" | "link">): boolean {
  return state.link !== null && state.account !== null && state.account.userId === state.link.userId;
}

/**
 * Verbunden, aber die Anmeldung fehlt, ist abgelaufen oder gehört (für einen Augenblick, bis es abgemeldet ist) zu
 * einem anderen Konto: Bis zur neuen Anmeldung wird nichts abgeglichen.
 */
export function needsSignIn(state: RoundState): boolean {
  return state.loaded && state.link !== null && (!signedInForRound(state) || state.problem === "SIGNED_OUT");
}

/** So viele Hinweise aus dem Abgleich bleiben höchstens stehen. */
const MAX_NOTICES = 20;

/** Was sich das Gerät zum Abgleich merkt, mit Meldung bei Änderungen. */
export type ObservableRoundStore = RoundStore & {
  subscribe(listener: () => void): () => void;
  /**
   * Vergisst, was im Arbeitsspeicher steht, und liest beim nächsten Mal neu (ein anderes Fenster der App kann
   * geschrieben haben). Fehlt bei einem Speicher, der nichts zwischenspeichert.
   */
  invalidate?(): void;
};

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
  private stopped = false;
  /** Was beim Aufhören abbestellt wird (Speicher, Daten, Anmeldung). */
  private readonly subscriptions: Array<() => void> = [];

  /** Wer angemeldet und welche Runde verbunden ist, als ein Text: Ändert er sich, wird sofort abgeglichen. */
  private known: string | null = null;
  private refreshing: Promise<void> = Promise.resolve();

  constructor(private readonly options: RoundControllerOptions) {
    this.api = deferredApi(async () => {
      const backend = await this.loadBackend();
      await this.requireRoundAccount(backend);
      return backend.api;
    });
    const { store, data } = options;
    this.sync = new RoundSync(this.api, store, data.players, data.evenings, data.settings, data.editor);
    this.connection = new RoundConnection(this.api, store, data.players, data.evenings, this.sync, options.ids);
    this.scheduler = new SyncScheduler(
      {
        // Solange die Anmeldung nicht gelesen ist, versucht die Schleife das (mit wachsendem Abstand, wenn es scheitert).
        canSync: () => !this.state.loaded || signedInForRound(this.state),
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
    this.subscriptions.push(this.options.store.subscribe(() => void this.refresh()));
    this.subscriptions.push(this.options.data.onLocalChange(() => this.scheduler.localChange()));
    // Die Runde und was darin schon wartet gleich zeigen, noch bevor die Anmeldung gelesen ist.
    void this.refresh();
    // Die Anmeldung auch dann lesen, wenn die App im Hintergrund startet; abgeglichen wird erst, wenn sie zu sehen ist.
    void this.ensureLoaded();
    this.scheduler.start(visible);
  }

  /** Hört auf zu arbeiten (zum Beispiel, wenn die Datenbank nicht mehr benutzbar ist, und in Tests). */
  stop(): void {
    this.stopped = true;
    this.scheduler.stop();
    for (const unsubscribe of this.subscriptions.splice(0)) unsubscribe();
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
        if (!this.stopped) this.subscriptions.push(backend.auth.subscribe(() => void this.refresh()));
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

  /**
   * Mit verbundener Runde spricht dieses Gerät nur als das Konto der Runde mit dem Server. Ist (für einen Augenblick,
   * bis es abgemeldet ist) ein anderes angemeldet, scheitert jeder Aufruf, als wäre die Anmeldung abgelaufen.
   */
  private async requireRoundAccount(backend: RoundBackend): Promise<void> {
    const link = await this.options.store.link();
    const account = backend.auth.account();
    if (link !== null && account !== null && account.userId !== link.userId) throw new RoundException("SIGNED_OUT", "anderes Konto");
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
    let signedIn = false;
    if (back?.kind === "failure") {
      this.setState({ message: back.error === "access_denied" ? "SIGN_IN_CANCELLED" : "SIGN_IN_FAILED" });
    } else if (back?.kind === "code") {
      // Solange die Anmeldung eingelöst wird, ruhen die Knöpfe, und es steht „Einen Moment …“ da.
      this.setState({ signingIn: true });
      try {
        signedIn = await this.completeSignIn(backend, back.code);
      } finally {
        this.setState({ signingIn: false });
      }
    }
    this.setState({ loaded: true });
    await this.refresh();
    if (signedIn) this.scheduler.syncNow();
  }

  /**
   * Löst die Rückkehr aus der Anmeldung ein. Gehört die verbundene Runde zu einem anderen Konto, wird das neue Konto
   * gleich wieder abgemeldet: Der Abgleich darf nie unter fremdem Namen laufen; trennen wäre aber zu viel (was noch
   * wartet, käme nie mehr an).
   *
   * @returns ob jetzt das richtige Konto angemeldet ist.
   */
  private async completeSignIn(backend: RoundBackend, code: string): Promise<boolean> {
    let account: Account;
    try {
      account = await backend.auth.completeSignIn(code);
    } catch (error) {
      // Eine Rückkehr, zu der keine Anmeldung (mehr) offen ist, während längst jemand angemeldet ist (zum Beispiel
      // dieselbe Adresse ein zweites Mal): kein Grund für eine Fehlermeldung.
      const stale = error instanceof RoundException && error.detail === NO_SIGN_IN_PENDING;
      const someone = backend.auth.account() !== null && this.state.problem !== "SIGNED_OUT";
      if (!(stale && someone)) {
        console.warn("Schafkopf Tracker: Anmeldung nicht abgeschlossen", error);
        this.setState({ message: "SIGN_IN_FAILED" });
      }
      return false;
    }
    const link = await this.options.store.link();
    if (link !== null && link.userId !== account.userId) {
      await backend.auth.signOut();
      this.setState({ message: "OTHER_ACCOUNT" });
      return false;
    }
    // Geklappt: Ältere Meldungen zur Anmeldung gelten nicht mehr.
    const message = this.state.message;
    if (message !== null && SIGN_IN_MESSAGES.has(message)) this.setState({ message: null });
    return true;
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
      // Die Anmeldung ist noch nicht gelesen: nur die Verbindung zeigen – und gleich, was darin schon wartet, statt bis
      // zum ersten Abgleich „Abgeglichen“.
      const players = link === null ? new Map<PlayerId, RemotePlayer>() : new Map(await this.options.store.playerBases());
      this.setState({ link, players, unreadable: link?.unreadable.length ?? 0 });
      if (link !== null) await this.refreshPending();
      return;
    }
    const signedIn = this.backend?.auth.account() ?? null;
    if (this.backend !== null && signedIn !== null && link !== null && link.userId !== signedIn.userId) {
      // Angemeldet ist ein anderes Konto als das der Runde (zum Beispiel aus einem anderen Fenster). Vorher frisch
      // nachsehen: Vielleicht hat das andere Fenster die Runde schon gelöst, dann ist das neue Konto ganz richtig.
      this.options.store.invalidate?.();
      link = await this.options.store.link();
      if (link !== null && link.userId !== signedIn.userId) {
        // Gleich wieder abmelden. Die Runde bleibt verbunden; was noch wartet, geht nicht verloren.
        this.setState({ message: "OTHER_ACCOUNT" });
        await this.backend.auth.signOut();
      }
    }
    const players = link === null ? new Map<PlayerId, RemotePlayer>() : new Map(await this.options.store.playerBases());
    // Ab hier ohne Unterbrechung: Was inzwischen an Meldungen und Zählern dazukam, bleibt stehen.
    const account = this.backend?.auth.account() ?? null;
    const current = this.state;
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
      });
    } else {
      this.setState({
        account,
        link,
        players,
        unreadable: link.unreadable.length,
        // Nach einer neuen Anmeldung gilt die alte Meldung nicht mehr.
        problem: current.problem === "SIGNED_OUT" && !sameAccount(account, current.account) ? null : current.problem,
      });
    }
    const now = `${account?.userId ?? ""}|${link?.roundId ?? ""}`;
    if (now !== this.known) {
      this.known = now;
      // Was schon vor dem ersten Abgleich wartet, soll die Statuszeile gleich zeigen.
      if (link !== null) await this.refreshPending();
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

  /**
   * Legt eine Runde an. Geschafft ist das, sobald der Server sie angenommen hat; scheitert nur der Abgleich danach,
   * sagt es die Statuszeile, und die App holt ihn von selbst nach.
   */
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

  /** Das angemeldete Konto, wie die Anmeldung es gerade kennt (der Stand der Oberfläche kann einen Augenblick nachhängen). */
  private userId(): string {
    const account = this.backend !== null ? this.backend.auth.account() : this.state.account;
    if (account === null) throw new RoundException("SIGNED_OUT");
    return account.userId;
  }

  private async run(full: boolean): Promise<SyncOutcome> {
    if (!(await this.ensureLoaded())) {
      await this.apply(SyncOutcome.Offline);
      return SyncOutcome.Offline;
    }
    // Nie unter fremdem Namen (siehe oben); ohne Runde oder Anmeldung gibt es ohnehin nichts abzugleichen.
    if (!signedInForRound(this.state)) return SyncOutcome.NotLinked;
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

  /**
   * Das Ergebnis eines Abgleichs außerhalb der Schleife (Verbinden, „Das bin ich“). Ist es kein Erfolg, ist das kein
   * Fehler der Aktion: Die Statuszeile sagt es.
   */
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

  /** Zählt, was auf dem Gerät noch wartet; lässt sich das gerade nicht lesen, bleibt der gemerkte Wert. */
  private async refreshPending(): Promise<void> {
    let pending: number;
    try {
      pending = await this.sync.pendingCount();
    } catch {
      return;
    }
    this.setState({ pending });
  }
}
