/*
 * Was der Bildschirm „Gemeinsame Runde“ über den Stand der Runde hinaus braucht (in der Android-App `RoundViewModel`):
 * die Runden des Kontos, die wartende Einladung, Rückfragen, Fehler und ob gerade eine Aktion läuft. Reine Logik
 * ohne Oberfläche, damit sie sich prüfen lässt.
 */
import type { PlayerId } from "../core/model";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundException, type InviteInfo, type RoundError, type RoundSummary } from "../core/round/RoundModels";
import type { RoundController } from "./controller";

/** Was beim Beitreten oder Verwenden einer Runde noch bestätigt werden muss. */
export type PendingAttach = { readonly kind: "join"; readonly token: string } | { readonly kind: "use"; readonly round: RoundSummary };

/** Eine Rückfrage, bevor das Gerät von der Runde getrennt wird. */
export type Farewell = "LEAVE" | "SIGN_OUT";

/** Ein Fehler, den der Bildschirm in Worten zeigt. */
export type RoundUiError =
  /** Im eingefügten Text steht kein Einladungscode. */
  | { readonly kind: "NoCode" }
  /** `roundName`: Es ging um den Namen einer Runde. */
  | { readonly kind: "Server"; readonly error: RoundError; readonly roundName: boolean };

/**
 * @property rounds Die Runden des Kontos; `null`, solange sie nicht gelesen sind.
 * @property roundsFailed Die Runden ließen sich nicht lesen (kein Netz, Server gestört). Sonst sähe es auf einem neuen
 *   Handy aus, als gäbe es keine.
 * @property invite Was die wartende Einladung verrät; `null`, solange unbekannt.
 * @property replace Wartet auf die Rückfrage „Daten dieses Geräts ersetzen?“.
 * @property eveningCount So viele Abende lägen beim Ersetzen auf dem Gerät.
 * @property farewell Wartet auf die Rückfrage vor dem Verlassen oder Abmelden; `farewellPending` Änderungen warten noch.
 * @property shareLink Dieser Einladungslink ist erzeugt und kann weitergegeben werden.
 */
export interface RoundUi {
  readonly busy: boolean;
  readonly error: RoundUiError | null;
  readonly rounds: readonly RoundSummary[] | null;
  readonly roundsFailed: boolean;
  readonly invite: InviteInfo | null;
  readonly replace: PendingAttach | null;
  readonly eveningCount: number;
  readonly farewell: Farewell | null;
  readonly farewellPending: number;
  readonly shareLink: string | null;
}

const INITIAL: RoundUi = {
  busy: false,
  error: null,
  rounds: null,
  roundsFailed: false,
  invite: null,
  replace: null,
  eveningCount: 0,
  farewell: null,
  farewellPending: 0,
  shareLink: null,
};

export class RoundScreenModel {
  private ui: RoundUi = INITIAL;
  private readonly listeners = new Set<() => void>();
  private readonly unsubscribe: () => void;
  /** Konto, „ohne Runde“ und wartende Einladung als ein Text: Ändert er sich, wird neu nachgesehen. */
  private watched: string | null = null;
  /** Zählt die Wechsel von {@link watched}: Eine Antwort, die nach einem Wechsel kommt, gilt nicht mehr. */
  private generation = 0;
  private disposed = false;

  /**
   * @param eveningCount So viele Abende liegen gerade auf dem Gerät (für die Rückfrage vor dem Ersetzen).
   */
  constructor(
    private readonly controller: RoundController,
    private readonly eveningCount: () => number,
  ) {
    this.unsubscribe = controller.subscribe(() => this.watch());
    this.watch();
  }

  /** Der Bildschirm ist zu: nichts mehr beobachten. */
  dispose(): void {
    this.disposed = true;
    this.unsubscribe();
  }

  getUi(): RoundUi {
    return this.ui;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private update(changes: Partial<RoundUi>): void {
    if (this.disposed) return;
    this.ui = { ...this.ui, ...changes };
    for (const listener of [...this.listeners]) listener();
  }

  /** Sobald jemand angemeldet ist und noch keine Runde verbunden hat: seine Runden und die Einladung nachsehen. */
  private watch(): void {
    const state = this.controller.getState();
    const userId = state.loaded ? (state.account?.userId ?? null) : null;
    const unlinked = state.link === null;
    const now = JSON.stringify([userId, unlinked, state.pendingInvite]);
    if (now === this.watched) return;
    this.watched = now;
    this.generation++;
    if (userId !== null && unlinked) void this.refresh();
    else this.update({ rounds: null, roundsFailed: false, invite: null });
  }

  /** Liest die Runden des Kontos und die wartende Einladung. Scheitert das, bleibt der Bildschirm benutzbar. */
  async refresh(): Promise<void> {
    const generation = this.generation;
    let rounds: readonly RoundSummary[] | null = null;
    try {
      rounds = await this.controller.myRounds();
    } catch {
      // Ohne Netz bleibt die Liste, wie sie war; der Bildschirm sagt, dass sie sich nicht laden ließ.
    }
    // Inzwischen abgemeldet, verbunden oder ein anderes Konto: Dann gilt die Antwort nicht mehr.
    if (generation !== this.generation) return;
    this.update({ rounds: rounds ?? this.ui.rounds, roundsFailed: rounds === null });
    const token = this.controller.getState().pendingInvite;
    if (token === null) {
      this.update({ invite: null });
      return;
    }
    try {
      const invite = await this.controller.inviteInfo(token);
      // Inzwischen verworfen oder eine andere: Dann gilt die Antwort nicht mehr.
      if (generation === this.generation && this.controller.getState().pendingInvite === token) this.update({ invite });
    } catch (error) {
      if (generation !== this.generation) return;
      if (error instanceof RoundException && error.error === "INVALID_INVITE") {
        // Abgelaufen oder ungültig: sagen und vergessen, sonst käme die Meldung bei jedem Öffnen wieder.
        this.controller.rememberInvite(null);
        this.update({ invite: null, error: { kind: "Server", error: error.error, roundName: false } });
      }
      // Ohne Netz bleibt die Einladung einfach stehen; „Beitreten“ versucht es dann noch einmal.
    }
  }

  /** „Erneut versuchen“, wenn sich die Runden nicht laden ließen; solange ruhen die Knöpfe. */
  retry(): Promise<void> {
    return this.busy(() => this.refresh());
  }

  // --- Anmelden und Abmelden ---

  signIn(): Promise<void> {
    return this.busy(() => this.controller.signIn());
  }

  askFarewell(kind: Farewell): Promise<void> {
    return this.busy(async () => {
      const pending = await this.controller.pendingCount();
      this.update({ farewell: kind, farewellPending: pending });
    });
  }

  cancelFarewell(): void {
    this.update({ farewell: null });
  }

  confirmFarewell(): Promise<void> {
    const kind = this.ui.farewell;
    if (kind === null) return Promise.resolve();
    this.update({ farewell: null });
    return this.busy(() => (kind === "LEAVE" ? this.controller.leave() : this.controller.signOut()));
  }

  // --- Runde anlegen, beitreten, verwenden ---

  create(name: string): Promise<void> {
    return this.busy(async () => {
      await this.controller.create(name);
    }, true);
  }

  /** Löst einen eingefügten Link oder Code ein. */
  redeem(text: string): Promise<void> {
    const token = InviteLinks.tokenFrom(text);
    if (token === null) {
      this.update({ error: { kind: "NoCode" } });
      return Promise.resolve();
    }
    return this.requestAttach({ kind: "join", token });
  }

  /** Tritt der Runde der wartenden Einladung bei. */
  joinInvited(): Promise<void> {
    const token = this.controller.getState().pendingInvite;
    return token === null ? Promise.resolve() : this.requestAttach({ kind: "join", token });
  }

  use(round: RoundSummary): Promise<void> {
    return this.requestAttach({ kind: "use", round });
  }

  discardInvite(): void {
    this.controller.rememberInvite(null);
  }

  /** Liegt auf dem Gerät etwas, das ersetzt würde, kommt erst die Rückfrage. */
  private requestAttach(target: PendingAttach): Promise<void> {
    return this.busy(async () => {
      if (await this.controller.hasOwnData()) this.update({ replace: target, eveningCount: this.eveningCount() });
      else await this.attach(target);
    });
  }

  cancelReplace(): void {
    this.update({ replace: null });
  }

  confirmReplace(): Promise<void> {
    const target = this.ui.replace;
    if (target === null) return Promise.resolve();
    this.update({ replace: null });
    return this.busy(() => this.attach(target));
  }

  private async attach(target: PendingAttach): Promise<void> {
    if (target.kind === "join") await this.controller.join(target.token);
    else await this.controller.use(target.round);
  }

  // --- Verbunden ---

  /** „Das bin ich“; `player === null` heißt „Ich bin neu“. */
  claim(player: PlayerId | null, name: string): Promise<void> {
    return this.busy(async () => {
      await this.controller.claim(player, name);
    });
  }

  /** Erzeugt einen Einladungslink zum Weitergeben. */
  invite(): Promise<void> {
    return this.busy(async () => this.update({ shareLink: await this.controller.inviteLink() }));
  }

  /** Der Einladungslink ist weitergegeben (oder wird nicht mehr gebraucht). */
  shared(): void {
    this.update({ shareLink: null });
  }

  clearError(): void {
    this.update({ error: null });
  }

  // --- Gemeinsames ---

  /*
   * Anlegen, Beitreten und „Das bin ich“ sind geschafft, sobald der Server sie angenommen hat. Scheitert nur der
   * Abgleich danach, ist das kein Fehler dieser Aktion: Die Statuszeile sagt es („Kein Netz“), und die App holt ihn
   * von selbst nach.
   */

  /**
   * Führt eine Aktion aus; währenddessen sind die Knöpfe gesperrt. Läuft schon eine, passiert nichts.
   *
   * @param roundName Es geht um den Namen einer Runde (für den Fehlertext).
   */
  private async busy(block: () => Promise<void>, roundName = false): Promise<void> {
    if (this.ui.busy) return;
    this.update({ busy: true, error: null });
    try {
      await block();
    } catch (error) {
      if (error instanceof RoundException) this.update({ error: { kind: "Server", error: error.error, roundName } });
      else {
        console.warn("Schafkopf Tracker: Aktion der gemeinsamen Runde gescheitert", error);
        this.update({ error: { kind: "Server", error: "SERVER", roundName: false } });
      }
    } finally {
      this.update({ busy: false });
    }
  }
}
