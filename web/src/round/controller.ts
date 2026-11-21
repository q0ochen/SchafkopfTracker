/*
 * Hält die gemeinsame Runde in der Web-App am Laufen (docs/ARCHITEKTUR.md, Abschnitt 8.6 und 8.7) und sagt der
 * Oberfläche, wie es um sie steht. Entspricht dem `RoundCoordinator` der Android-App: Anmeldung, Verbinden, Wechseln
 * zwischen Runden, „Das bin ich“, Abgleich mit Planer, Statuszeile, Hinweise und Meldungen. Auch die Runden des Kontos
 * stehen hier, damit Start-Bildschirm und „Gemeinsame Runden“ dieselbe Liste sehen.
 *
 * Die Anbindung an den Server (supabase-js) wird erst hier und erst bei Bedarf geladen. Solange sie nicht da ist,
 * weiß die Oberfläche noch nicht, wer angemeldet ist (`loaded` ist `false`).
 *
 * Mit verbundener Runde spricht das Gerät nur als das Konto mit dem Server, mit dem es der Runde beigetreten ist. Mit
 * einem anderen Konto sähe der Server die Runde nicht, und der Abgleich hielte sie für aufgelöst und trennte sie. Ein
 * anderes Konto wird deshalb gleich wieder abgemeldet; die Runde bleibt verbunden, und was noch wartet, geht nicht
 * verloren.
 *
 * Lässt der Speicher des Browsers den Abgleich nicht sicher arbeiten (Speichern gescheitert, Spielerliste oder
 * Einstellungen unlesbar), ruht er; die Statuszeile sagt „Speichern auf diesem Gerät gescheitert – bitte die App neu
 * laden“ (docs/ARCHITEKTUR.md, Abschnitt 8.8 und 8.9).
 */
import { DeviceStorageError, UnreadableDataError } from "../app/store";
import type { Player, PlayerId } from "../core/model";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundConnection } from "../core/round/RoundConnection";
import {
  RoundException,
  switchFrom,
  type InviteInfo,
  type RemotePlayer,
  type RoundId,
  type RoundLink,
  type RoundSummary,
  type SwitchFrom,
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
import { RoundSync, SyncOutcome, type SyncGuard, type SyncNotice } from "../core/round/RoundSync";
import { NO_SIGN_IN_PENDING, type Account, type RoundBackend } from "./backend";
import { deferredApi } from "./deferredApi";
import { reusableInvite, type InviteMemory, type IssuedInviteMemory } from "./inviteMemory";
import { SyncScheduler, type SchedulerTiming } from "./scheduler";
import type { SignInReturn } from "./signInReturn";
import { SIGN_IN_MESSAGES, type RoundMessage, type RoundProblem } from "./texts";
import { WindowBusyError } from "./windows";
import { SIGN_IN_EXCHANGE_MILLIS, TimeoutError, within } from "./within";

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
 * @property rounds Die Runden des angemeldeten Kontos (`my_rounds`) in ihrer Reihenfolge; `null`, solange sie für
 *   dieses Konto noch nicht gelesen sind (und ohne Anmeldung). Gelesen werden sie, wenn ein Bildschirm sie braucht
 *   ({@link RoundController.watchRounds}), auf Wunsch und nach jedem Anlegen, Beitreten, Wechseln und Verlassen.
 * @property roundsFailed Das letzte Lesen der Runden ist gescheitert (kein Netz, Server gestört). `rounds` ist dann die
 *   zuletzt gelesene Liste; gab es noch keine, bleibt es `null`.
 * @property switching Ein Wechsel in eine andere Runde wird vorbereitet oder läuft ({@link RoundController.switchWork}):
 *   Bis er fertig ist, steht über allem „Einen Moment …“, und nichts lässt sich bedienen (docs/ARCHITEKTUR.md 8.9).
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
  readonly rounds: readonly RoundSummary[] | null;
  readonly roundsFailed: boolean;
  readonly switching: boolean;
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
  rounds: null,
  roundsFailed: false,
  switching: false,
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
   *
   * @param notify Auch melden (Standard); vor einem Abgleich ist das nicht nötig.
   */
  invalidate?(notify?: boolean): void;
};

/** Spieler, Abende und Einstellungen dieses Geräts, wie der Abgleich sie braucht (in der App: `StoreAdapters`). */
export interface RoundData {
  readonly players: PlayerRepository;
  readonly evenings: EveningRepository;
  readonly settings: SettingsRepository;
  readonly editor: EveningEditor;
  /** Meldet jede Änderung an Spielern, Abenden und dem Regelwerk für neue Abende, die nicht der Abgleich gemacht hat. */
  onLocalChange(listener: () => void): () => void;
  /**
   * Ob der Abgleich ruhen muss, weil sich Spielerliste oder Einstellungen auf dem Gerät nicht lesen ließen (sonst sähe
   * das aus wie „hier gelöscht“). Fehlt die Angabe: nie.
   */
  blocked?(): boolean;
}

export interface RoundControllerOptions {
  /** Lädt die Anbindung an den Server. Wird wiederholt, bis es klappt. */
  readonly loadBackend: () => Promise<RoundBackend>;
  readonly store: ObservableRoundStore;
  readonly data: RoundData;
  readonly invites: InviteMemory;
  /** Die zuletzt erzeugte Einladung je Runde (wird wieder angeboten, solange sie jünger als 13 Tage ist). */
  readonly issuedInvites: IssuedInviteMemory;
  /** Die vorbelegten Spieler der App (siehe `RoundConnection.create`). */
  readonly placeholders: readonly Player[];
  /** Die Start-Adresse der Web-App, für Einladungslinks. */
  readonly webAppUrl: string;
  readonly ids: IdGenerator;
  /** Die verbundene Runde, wie sie beim Start der App im Speicher stand (damit die Oberfläche sie sofort zeigt). */
  readonly initialLink?: RoundLink | null;
  readonly timing?: SchedulerTiming;
  /** Läuft um jeden Abgleich (in der App: Sperre über alle Fenster und frisches Lesen, siehe `windows.ts`). */
  readonly guard?: SyncGuard;
  /** So lange darf das Einlösen der Anmeldung dauern (Millisekunden); danach gilt es als „keine Verbindung“. */
  readonly signInMillis?: number;
}

function sameAccount(a: Account | null, b: Account | null): boolean {
  return a?.userId === b?.userId && a?.email === b?.email;
}

/**
 * Der Ausgang eines Abgleichs, den der Speicher des Geräts verhindert hat. Für den Planer ist es ein Fehler (er
 * versucht es mit wachsendem Abstand wieder), die Statuszeile sagt „Speichern auf diesem Gerät gescheitert …“.
 * Erkannt wird er an genau diesem Objekt.
 */
const STORAGE_FAILURE: SyncOutcome = { kind: "Failed", error: "SERVER" };

/** Ein anderes Fenster gleicht gerade ab: Dieser Versuch fällt aus; für den Planer ist es ein Fehler, sonst ändert sich nichts. */
const BUSY_ELSEWHERE: SyncOutcome = { kind: "Failed", error: "SERVER" };

/**
 * Der Abgleich der Web-App: Ruht, solange Spielerliste oder Einstellungen unlesbar sind, und macht aus einem Fehler
 * des Speichers auf dem Gerät den Ausgang {@link STORAGE_FAILURE}, statt den Fehler weiterzureichen. So ist auch beim
 * Verbinden und bei „Das bin ich“ ein gescheiterter Abgleich danach kein Fehler der Aktion.
 */
class DeviceSync extends RoundSync {
  constructor(
    api: RoundApi,
    store: RoundStore,
    private readonly data: RoundData,
    guard: SyncGuard | undefined,
  ) {
    super(api, store, data.players, data.evenings, data.settings, data.editor, guard);
  }

  override async sync(quick = false): Promise<SyncOutcome> {
    if (this.data.blocked?.() === true) return STORAGE_FAILURE;
    try {
      return await super.sync(quick);
    } catch (error) {
      if (error instanceof WindowBusyError) return BUSY_ELSEWHERE;
      if (!(error instanceof DeviceStorageError)) throw error;
      console.warn("Schafkopf Tracker: Abgleich am Speicher des Geräts gescheitert", error);
      return STORAGE_FAILURE;
    }
  }

  /**
   * Der Abgleich vor dem Leeren beim Wechsel in eine andere Runde. Lässt der Speicher des Geräts ihn nicht zu, wird
   * nicht gewechselt: Der Fehler (oder `WindowBusyError`) geht an den Aufrufer, und das Gerät bleibt, wie es ist.
   */
  override syncThen<T>(block: (outcome: SyncOutcome) => Promise<T>): Promise<T> {
    if (this.data.blocked?.() === true) return Promise.reject(new UnreadableDataError());
    return super.syncThen(block);
  }
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
  /** Wer beim letzten Auffrischen nach dem Laden angemeldet war; `undefined` vor dem ersten Mal. */
  private lastAccount: string | null | undefined = undefined;
  private refreshing: Promise<void> = Promise.resolve();

  /** Für dieses Konto gelten die Runden in `rounds`; `null` ohne Anmeldung (oder bevor sie gelesen ist). */
  private roundsOwner: string | null = null;
  /** Zählt das Lesen der Runden: Eine Antwort, nach der schon neu gelesen wurde, gilt nicht mehr. */
  private roundsReads = 0;
  /** So viele Bildschirme brauchen gerade die Runden des Kontos (siehe {@link watchRounds}). */
  private roundsWatchers = 0;
  /** So viele Wechsel werden gerade vorbereitet oder laufen (siehe {@link switchWork}). */
  private switchWorks = 0;

  constructor(private readonly options: RoundControllerOptions) {
    this.api = deferredApi(async () => {
      const backend = await this.loadBackend();
      await this.requireRoundAccount(backend);
      return backend.api;
    });
    const { store, data } = options;
    this.sync = new DeviceSync(this.api, store, data, options.guard);
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
      // Mit Zeitlimit: Danach gilt es als „keine Verbindung“, und die Knöpfe sind wieder frei. Kommt die Antwort doch
      // noch, meldet die Anmeldung das Konto von selbst (siehe `refreshNow`).
      account = await within(backend.auth.completeSignIn(code), this.options.signInMillis ?? SIGN_IN_EXCHANGE_MILLIS).catch((error: unknown) => {
        throw error instanceof TimeoutError ? new RoundException("OFFLINE", "Anmeldung", error) : error;
      });
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
    // Eine Anmeldung, die nach einer Meldung doch noch geglückt ist (zum Beispiel, weil die Antwort beim Einlösen nach
    // dem Zeitlimit kam), räumt die Meldungen zur Anmeldung weg – mit dem Konto der Runde oder ohne Runde.
    const lateSignIn = this.lastAccount === null && account !== null && (link === null || link.userId === account.userId);
    this.lastAccount = account?.userId ?? null;
    if (lateSignIn && current.message !== null && SIGN_IN_MESSAGES.has(current.message)) this.setState({ message: null });
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
      // Eine andere Runde (gewechselt, beigetreten, neu angelegt): Hinweise und nicht angenommene Abende der bisherigen
      // gelten hier nicht; was die neue Runde dazu sagt, bringt ihr erster Abgleich.
      const otherRound = current.link?.roundId !== link.roundId;
      this.setState({
        account,
        link,
        players,
        unreadable: link.unreadable.length,
        // Nach einer neuen Anmeldung gilt die alte Meldung nicht mehr.
        problem: current.problem === "SIGNED_OUT" && !sameAccount(account, current.account) ? null : current.problem,
        ...(otherRound ? { notices: [], refused: 0 } : {}),
      });
    }
    // Die Runden gehören zum Konto: Ein anderes (oder keines) sieht die Liste des vorigen nicht.
    const owner = account?.userId ?? null;
    if (owner !== this.roundsOwner) {
      this.roundsOwner = owner;
      this.roundsReads++;
      this.setState({ rounds: null, roundsFailed: false });
      if (owner !== null && this.roundsWatchers > 0) void this.refreshRounds();
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
    this.options.issuedInvites.set(null);
    await (await this.loadBackend()).auth.signOut();
    await this.refresh();
  }

  // --- Runden ---

  /** Die Runden des angemeldeten Kontos, frisch vom Server (ohne sie in den Zustand zu übernehmen). */
  myRounds(): Promise<RoundSummary[]> {
    return this.api.myRounds();
  }

  /**
   * Ein Bildschirm braucht die Runden des Kontos (Start-Bildschirm, „Gemeinsame Runden“): Sie werden gleich gelesen,
   * sobald jemand angemeldet ist, und wieder, wenn sich jemand anderes anmeldet. Der Bildschirm ruft das, wenn er
   * erscheint (docs/ARCHITEKTUR.md 8.9, „Runden-Auswahl auf dem Start-Bildschirm“).
   *
   * @returns Zum Abbestellen, wenn der Bildschirm verschwindet.
   */
  watchRounds(): () => void {
    this.roundsWatchers++;
    void this.refreshRounds();
    let watching = true;
    return () => {
      if (!watching) return;
      watching = false;
      this.roundsWatchers--;
    };
  }

  /**
   * Liest die Runden des angemeldeten Kontos neu (`my_rounds`) und übernimmt sie in den Zustand. Scheitert das, bleibt
   * die zuletzt gelesene Liste stehen, und `roundsFailed` sagt es. Ohne Anmeldung passiert nichts. Scheitert nie.
   */
  async refreshRounds(): Promise<void> {
    const owner = this.roundsOwner;
    if (owner === null) return;
    const read = ++this.roundsReads;
    let rounds: RoundSummary[] | null = null;
    try {
      rounds = await this.api.myRounds();
    } catch (error) {
      console.warn("Schafkopf Tracker: Runden des Kontos nicht gelesen", error);
    }
    // Inzwischen ein anderes Konto, abgemeldet oder schon neu gelesen: Dann gilt diese Antwort nicht mehr.
    if (read !== this.roundsReads || owner !== this.roundsOwner) return;
    this.setState(rounds === null ? { roundsFailed: true } : { rounds, roundsFailed: false });
  }

  /**
   * Führt `block` als Teil eines Wechsels in eine andere Runde aus: die Vorbereitung bis zur Rückfrage (Einladung
   * nachsehen, {@link prepareSwitch}) bzw. den bestätigten Wechsel. Solange einer läuft, ist `switching` gesetzt, und über
   * allem steht „Einen Moment …“ (docs/ARCHITEKTUR.md 8.9): Sonst ließe sich mitten im Wechsel ein Spiel in die Runde
   * eintragen, die das Gerät gerade verlässt. Der Stand hängt an der Steuerung, nicht am Bildschirm, denn die Zurück-Taste
   * des Browsers lässt sich nicht sperren: Wer damit den Bildschirm verlässt, sieht „Einen Moment …“ auch auf dem
   * nächsten, bis der Wechsel fertig ist.
   */
  async switchWork<T>(block: () => Promise<T>): Promise<T> {
    if (this.switchWorks++ === 0) this.setState({ switching: true });
    try {
      return await block();
    } finally {
      if (--this.switchWorks === 0) this.setState({ switching: false });
    }
  }

  /**
   * Nach einer Aktion, die die Runden des Kontos ändern kann (Anlegen, Beitreten, Wechseln, Verlassen): neu lesen –
   * auch wenn die Aktion scheiterte, denn ein Versuch kann auf dem Server angekommen sein (Antwort verloren).
   */
  private async afterRoundsChange<T>(action: () => Promise<T>): Promise<T> {
    try {
      return await action();
    } finally {
      await this.refreshRounds();
    }
  }

  inviteInfo(token: string): Promise<InviteInfo> {
    return this.api.inviteInfo(token);
  }

  /** Ob auf dem Gerät etwas liegt, das beim Beitreten ersetzt würde. */
  async hasOwnData(): Promise<boolean> {
    this.requireReadableDevice();
    return this.connection.hasOwnData(this.options.placeholders);
  }

  /**
   * Ließen sich Spielerliste oder Einstellungen nicht lesen, verbindet dieses Gerät nichts: Was im Zustand steht, ist
   * nicht der echte Stand (beim Anlegen ginge er in die Runde, beim Beitreten ersetzte er nur einen Teil).
   */
  private requireReadableDevice(): void {
    if (this.options.data.blocked?.() === true) throw new UnreadableDataError();
  }

  /**
   * Legt eine Runde an. Geschafft ist das, sobald der Server sie angenommen hat; scheitert nur der Abgleich danach,
   * sagt es die Statuszeile, und die App holt ihn von selbst nach. Ist das Gerät schon verbunden, beginnt die neue Runde
   * leer, und die bisherige behält ihre Abende (docs/ARCHITEKTUR.md 8.7).
   *
   * @param from Was die Rückfrage gezeigt hat ({@link prepareSwitch}); ohne Runde `SwitchFrom(null)`.
   */
  async create(name: string, from: SwitchFrom | null = null): Promise<SyncOutcome> {
    this.requireReadableDevice();
    const userId = this.userId();
    const seen = this.scheduler.seen();
    return this.afterRoundsChange(async () =>
      this.applied(await this.connection.create(name, userId, this.options.placeholders, from), seen),
    );
  }

  /**
   * Tritt bei und übernimmt die Daten der Runde; die Daten dieses Geräts werden dabei ersetzt (gehört die Einladung zur
   * verbundenen Runde, wird nur abgeglichen). Die Einladung ist danach erledigt – außer es gibt die bisherige Runde
   * nicht mehr (`Detached`): Dann ist das Gerät nicht gewechselt, und die Einladungskarte bleibt für den nächsten
   * Versuch stehen.
   *
   * @param from Was die Rückfrage gezeigt hat ({@link prepareSwitch}); ohne Runde `SwitchFrom(null)`.
   */
  async join(token: string, from: SwitchFrom | null = null): Promise<SyncOutcome> {
    this.requireReadableDevice();
    const userId = this.userId();
    const seen = this.scheduler.seen();
    return this.afterRoundsChange(async () => {
      const outcome = await this.connection.join(token, userId, from);
      if (outcome.kind !== "Detached") this.rememberInvite(null);
      return this.applied(outcome, seen);
    });
  }

  /**
   * Verwendet eine Runde des Kontos auf diesem Gerät oder wechselt zu ihr (docs/ARCHITEKTUR.md 8.7, „Runde wechseln“):
   * Mit verbundener Runde wird diese unmittelbar vor dem Leeren noch einmal vollständig abgeglichen; ihre Abende bleiben
   * auf dem Server.
   *
   * @param from Was die Rückfrage gezeigt hat ({@link prepareSwitch}); ohne Runde `SwitchFrom(null)`.
   */
  async use(round: RoundSummary, from: SwitchFrom | null = null): Promise<SyncOutcome> {
    this.requireReadableDevice();
    const userId = this.userId();
    const seen = this.scheduler.seen();
    return this.afterRoundsChange(async () => this.applied(await this.connection.use(round, userId, from), seen));
  }

  /**
   * Vor der Rückfrage zum Wechseln, Beitreten oder Anlegen mit verbundener Runde (docs/ARCHITEKTUR.md 8.9, „Rückfrage
   * vor dem Wechseln“): gleicht vollständig ab und sagt, von welcher Runde aus gefragt wird und wie viele Änderungen
   * danach noch warten (die gingen beim Wechseln verloren, etwa ein Abend, den der Server nicht annimmt). Das Ergebnis
   * geht mit der Bestätigung an {@link use}, {@link join} bzw. {@link create}: Weicht das Gerät bis dahin davon ab,
   * wird nicht gewechselt (`CHANGED_MEANWHILE`, siehe `RoundConnection`).
   *
   * @returns `null`, wenn es nichts zu fragen gibt: Das Gerät ist nicht (mehr) verbunden, oder es gibt die verbundene
   *   Runde nicht mehr – dann steht die Meldung nach `Detached` da, und die Daten bleiben als Kopie.
   * @throws RoundException ohne Netz (`OFFLINE`), wenn die Anmeldung fehlt oder abgelaufen ist (`SIGNED_OUT`) oder bei
   *   einem Fehler des Servers; `DeviceStorageError`, wenn der Speicher des Geräts den Abgleich nicht zulässt. Dann
   *   bleibt alles, wie es ist.
   */
  async prepareSwitch(): Promise<SwitchFrom | null> {
    const link = this.state.link;
    if (link === null) return null;
    this.requireReadableDevice();
    // Nie unter fremdem Namen und nicht ohne gültige Anmeldung (siehe `run`).
    if (!signedInForRound(this.state)) throw new RoundException("SIGNED_OUT");
    const seen = this.scheduler.seen();
    const outcome = await this.applied(await this.sync.sync(), seen);
    let pending: number;
    switch (outcome.kind) {
      case "Synced":
        pending = outcome.pending;
        break;
      case "Unchanged":
        // Kommt bei einem vollständigen Abgleich nicht vor; dann zählt, was auf dem Gerät wartet.
        pending = await this.sync.pendingCount();
        break;
      case "NotLinked":
      case "Detached":
        return null;
      case "Offline":
        throw new RoundException("OFFLINE");
      case "SignedOut":
        throw new RoundException("SIGNED_OUT");
      case "Failed":
        if (outcome === STORAGE_FAILURE) throw new DeviceStorageError("Abgleich vor dem Wechseln am Speicher des Geräts gescheitert");
        throw new RoundException(outcome.error);
    }
    return switchFrom(link.roundId, pending);
  }

  /** „Das bin ich“; `player === null` heißt „Ich bin neu“. */
  async claim(player: PlayerId | null, name: string): Promise<SyncOutcome> {
    this.requireReadableDevice();
    const seen = this.scheduler.seen();
    return this.applied(await this.connection.claim(player, name), seen);
  }

  /** Verlässt die verbundene Runde und löst die Verbindung; die Daten bleiben als Kopie hier. */
  async leave(): Promise<void> {
    await this.afterRoundsChange(async () => {
      await this.connection.leave();
      this.options.issuedInvites.set(null);
      await this.refresh();
    });
  }

  /**
   * Verlässt eine Runde des Kontos, die nicht mit diesem Gerät verbunden ist („Deine Runden“, „Deine anderen
   * Runden“). Auf diesem Gerät ändert sich nichts; ist das Konto das letzte Mitglied, löscht der Server die Runde.
   */
  async leaveRound(round: RoundId): Promise<void> {
    if (this.state.link?.roundId === round) {
      await this.leave();
      return;
    }
    await this.afterRoundsChange(async () => {
      await this.api.leaveRound(round);
      if (this.options.issuedInvites.get()?.roundId === round) this.options.issuedInvites.set(null);
    });
  }

  /**
   * Der Link zum Weitergeben: die zuletzt erzeugte Einladung dieser Runde, solange sie jünger als 13 Tage ist, sonst
   * eine neue. So stößt wiederholtes „Einladen“ nicht an die Grenze von 20 gültigen Einladungen je Runde.
   */
  async inviteLink(): Promise<string> {
    const link = this.state.link;
    if (link === null) throw new RoundException("NOT_MEMBER");
    const kept = reusableInvite(this.options.issuedInvites.get(), link.roundId, Date.now());
    if (kept !== null) return InviteLinks.webLink(this.options.webAppUrl, kept);
    const token = await this.api.createInvite(link.roundId);
    this.options.issuedInvites.set({ roundId: link.roundId, token, createdAt: Date.now() });
    return InviteLinks.webLink(this.options.webAppUrl, token);
  }

  /**
   * Wie viele Änderungen gerade noch warten (für die Rückfrage vor dem Verlassen). Lässt sich das auf dem Gerät gerade
   * nicht lesen, gilt der zuletzt bekannte Wert: Verlassen und Abmelden sollen auch dann gehen.
   */
  async pendingCount(): Promise<number> {
    try {
      return await this.sync.pendingCount();
    } catch (error) {
      if (error instanceof DeviceStorageError) return this.state.pending;
      throw error;
    }
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
   * Ein anderes Fenster der App hat die Daten auf dem Gerät geändert: neu lesen, während kein Abgleich läuft (auch in
   * keinem anderen Fenster).
   *
   * @param reload Liest Spieler, Abende, Einstellungen und das Gemerkte der Runde neu aus dem Speicher, soweit nötig.
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
      // Zum Beispiel ein unerwarteter Fehler der Datenbank: Der nächste Versuch kommt von selbst.
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
        if (outcome === BUSY_ELSEWHERE) {
          // Gleicht gerade ein anderes Fenster ab, fällt nur dieser Versuch aus: keine Störung, aber was wartet, zählt.
          this.setState({ syncing: false });
          await this.refreshPending();
        } else {
          await this.failed(outcome === STORAGE_FAILURE ? "STORAGE" : "SERVER");
        }
        break;
      case "SignedOut":
        await this.failed("SIGNED_OUT");
        break;
      case "Detached":
        this.options.issuedInvites.set(null);
        this.setState({ syncing: false, message: "DETACHED" });
        await this.refresh();
        break;
      case "NotLinked":
        // Zum Beispiel hat ein anderes Fenster die Runde gelöst: zeigen.
        this.setState({ syncing: false });
        await this.refresh();
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
