/*
 * Was der Bildschirm „Gemeinsame Runden“ über den Stand der Runde hinaus braucht (in der Android-App `RoundViewModel`):
 * die wartende Einladung, Rückfragen, Fehler, Meldungen und ob gerade eine Aktion läuft. Die Runden des Kontos stehen
 * im Zustand der Steuerung (`RoundState.rounds`), damit der Start-Bildschirm dieselbe Liste sieht; solange der
 * Bildschirm offen ist, hält er sie aktuell. Reine Logik ohne Oberfläche, damit sie sich prüfen lässt.
 *
 * Ohne verbundene Runde fragt Beitreten und Verwenden „Daten dieses Geräts ersetzen?“, wenn auf dem Gerät etwas liegt.
 * Mit verbundener Runde gleichen Wechseln, Beitreten und Anlegen sie vorher vollständig ab und fragen dann „Zur Runde
 * ‹Ziel› wechseln?“ bzw. „Der Runde ‹Ziel› beitreten?“ und „Neue Runde ‹Ziel› anlegen?“ (docs/ARCHITEKTUR.md 8.9).
 * Solange das vorbereitet wird oder der Wechsel läuft, steht über allem „Einen Moment …“ (`RoundController.switchWork`).
 * Eine offene Rückfrage bleibt, bis man sie beantwortet: Hat sich das Gerät bis dahin geändert (etwa in einem anderen
 * Fenster), wird beim Bestätigen nicht gewechselt (`CHANGED_MEANWHILE`), und der Fehler steht da.
 */
import type { PlayerId } from "../core/model";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundException, switchFrom, type InviteInfo, type RoundSummary } from "../core/round/RoundModels";
import { RoundNames } from "../core/round/RoundNames";
import { prepareSwitch, roundNameProblem, runSwitch, uiErrorOf, type RoundUiError, type SwitchConfirm, type SwitchTarget } from "./actions";
import type { RoundController } from "./controller";
import type { RoundMessage } from "./texts";

export type { RoundUiError } from "./actions";

/** Was beim Beitreten oder Verwenden einer Runde ohne verbundene Runde noch bestätigt werden muss. */
export type PendingAttach = { readonly kind: "join"; readonly token: string } | { readonly kind: "use"; readonly round: RoundSummary };

/** Eine Rückfrage, bevor das Gerät von der Runde getrennt wird. */
export type Farewell = "LEAVE" | "SIGN_OUT";

/**
 * @property message Eine Meldung dieses Bildschirms („Du bist schon in dieser Runde.“); bleibt, bis man sie wegtippt.
 * @property invite Was die wartende Einladung verrät; `null`, solange unbekannt.
 * @property replace Wartet auf die Rückfrage „Daten dieses Geräts ersetzen?“ (ohne verbundene Runde).
 * @property eveningCount So viele Abende lägen beim Ersetzen auf dem Gerät.
 * @property switching Wartet auf die Rückfrage vor dem Wechseln, Beitreten oder Anlegen mit verbundener Runde.
 * @property farewell Wartet auf die Rückfrage vor dem Verlassen oder Abmelden; `farewellPending` Änderungen warten noch.
 * @property leaving Wartet auf die Rückfrage vor dem Verlassen dieser Runde aus „Deine Runden“ (nicht verbunden).
 * @property shareLink Dieser Einladungslink ist erzeugt und kann weitergegeben werden.
 */
export interface RoundUi {
  readonly busy: boolean;
  readonly error: RoundUiError | null;
  readonly message: RoundMessage | null;
  readonly invite: InviteInfo | null;
  readonly replace: PendingAttach | null;
  readonly eveningCount: number;
  readonly switching: SwitchConfirm | null;
  readonly farewell: Farewell | null;
  readonly farewellPending: number;
  readonly leaving: RoundSummary | null;
  readonly shareLink: string | null;
}

const INITIAL: RoundUi = {
  busy: false,
  error: null,
  message: null,
  invite: null,
  replace: null,
  eveningCount: 0,
  switching: null,
  farewell: null,
  farewellPending: 0,
  leaving: null,
  shareLink: null,
};

export class RoundScreenModel {
  private ui: RoundUi = INITIAL;
  private readonly listeners = new Set<() => void>();
  private readonly unsubscribe: () => void;
  private readonly unwatchRounds: () => void;
  /** Konto und verbundene Runde als ein Text: Ändert er sich, gelten ein erzeugter Link und „Verlassen?“ nicht mehr. */
  private situation: string | null = null;
  /** Konto und wartende Einladung als ein Text: Ändert er sich, wird die Einladung neu nachgesehen. */
  private watchedInvite: string | null = null;
  /** Zählt die Wechsel von {@link watchedInvite}: Eine Antwort, die nach einem Wechsel kommt, gilt nicht mehr. */
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
    // Solange der Bildschirm offen ist, braucht er die Runden des Kontos („Deine Runden“, „Deine anderen Runden“).
    this.unwatchRounds = controller.watchRounds();
  }

  /** Der Bildschirm ist zu: nichts mehr beobachten. */
  dispose(): void {
    this.disposed = true;
    this.unsubscribe();
    this.unwatchRounds();
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

  /** Behält Konto, verbundene Runde und wartende Einladung im Blick. */
  private watch(): void {
    const state = this.controller.getState();
    const userId = state.loaded ? (state.account?.userId ?? null) : null;
    const situation = JSON.stringify([userId, state.link?.roundId ?? null]);
    if (situation !== this.situation) {
      this.situation = situation;
      // Ein erzeugter Link gilt nur für die Runde, für die er erzeugt wurde, und „Verlassen?“ fragt nach einer Runde, die
      // nicht verbunden ist. Die Rückfragen zum Wechseln und Ersetzen bleiben: Beim Bestätigen entscheidet der Wechsel
      // selbst, ob er noch passt (`CHANGED_MEANWHILE`).
      if (this.ui.leaving !== null || this.ui.shareLink !== null) this.update({ leaving: null, shareLink: null });
    }
    const invite = JSON.stringify([userId, state.pendingInvite]);
    if (invite === this.watchedInvite) return;
    this.watchedInvite = invite;
    this.generation++;
    // Was eine frühere Einladung verriet, gilt nicht für eine andere; solange die neue gelesen wird, steht „…“ da.
    if (this.ui.invite !== null) this.update({ invite: null });
    if (userId !== null && state.pendingInvite !== null) void this.readInvite();
  }

  /** Liest, was die wartende Einladung verrät. Scheitert das ohne Netz, bleibt sie einfach stehen. */
  private async readInvite(): Promise<void> {
    const generation = this.generation;
    const token = this.controller.getState().pendingInvite;
    if (token === null) return;
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
    return this.busy(async () => {
      await this.controller.refreshRounds();
      await this.readInvite();
    });
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

  // --- Eine Runde aus „Deine Runden“ verlassen ---

  /** „Verlassen“ bei einer Runde des Kontos, die nicht mit diesem Gerät verbunden ist: erst die Rückfrage. */
  askLeave(round: RoundSummary): void {
    if (this.ui.busy) return;
    this.update({ leaving: round });
  }

  cancelLeave(): void {
    this.update({ leaving: null });
  }

  /** Verlässt die Runde; danach liest die Steuerung die Runden neu. Auf diesem Gerät ändert sich nichts. */
  confirmLeave(): Promise<void> {
    const round = this.ui.leaving;
    if (round === null) return Promise.resolve();
    this.update({ leaving: null });
    return this.busy(() => this.controller.leaveRound(round.id));
  }

  // --- Runde anlegen, beitreten, verwenden, wechseln ---

  /**
   * „Runde anlegen“. Erst wird der Name geprüft (1 bis 40 Zeichen, keine Runde des Kontos heißt schon so). Ohne
   * verbundene Runde wird gleich angelegt; mit verbundener erst abgeglichen und gefragt.
   */
  create(name: string): Promise<void> {
    if (this.ui.busy) return Promise.resolve();
    const state = this.controller.getState();
    const problem = roundNameProblem(name, state);
    if (problem !== null) {
      this.update({ error: { kind: "Server", error: problem, roundName: true } });
      return Promise.resolve();
    }
    const clean = RoundNames.clean(name);
    if (state.link !== null) return this.switching(() => this.askSwitch({ kind: "create", name: clean }), true);
    return this.busy(async () => {
      await this.controller.create(clean, switchFrom(null));
    }, true);
  }

  /** Löst einen eingefügten Link oder Code ein. */
  redeem(text: string): Promise<void> {
    const token = InviteLinks.tokenFrom(text);
    if (token === null) {
      this.update({ error: { kind: "NoCode" } });
      return Promise.resolve();
    }
    return this.join(token);
  }

  /** Tritt der Runde der wartenden Einladung bei. */
  joinInvited(): Promise<void> {
    const token = this.controller.getState().pendingInvite;
    return token === null ? Promise.resolve() : this.join(token);
  }

  /**
   * „Auf diesem Gerät verwenden“ (ohne verbundene Runde) bzw. „Wechseln“ (mit verbundener Runde, „Deine anderen
   * Runden“).
   */
  use(round: RoundSummary): Promise<void> {
    if (this.controller.getState().link !== null) return this.switching(() => this.askSwitch({ kind: "use", round }));
    return this.requestAttach({ kind: "use", round });
  }

  discardInvite(): void {
    this.controller.rememberInvite(null);
  }

  /**
   * Beitreten über einen Code. Mit verbundener Runde wird erst die Einladung nachgesehen: Gehört sie zur verbundenen
   * Runde selbst, gibt es nichts zu tun („Du bist schon in dieser Runde.“), und die Einladung wird vergessen; sonst
   * folgt die Rückfrage „Der Runde ‹Ziel› beitreten?“.
   */
  private join(token: string): Promise<void> {
    if (this.controller.getState().link === null) return this.requestAttach({ kind: "join", token });
    return this.switching(async () => {
      let info: InviteInfo;
      try {
        info = await this.controller.inviteInfo(token);
      } catch (error) {
        // Abgelaufen oder ungültig: Eine gemerkte Einladung wird dann vergessen (wie beim Nachsehen).
        if (error instanceof RoundException && error.error === "INVALID_INVITE") this.forgetInvite(token);
        throw error;
      }
      const link = this.controller.getState().link;
      if (link !== null && link.roundId === info.roundId) {
        this.forgetInvite(token);
        this.update({ message: "ALREADY_IN_ROUND" });
        return;
      }
      await this.askSwitch({ kind: "join", token, roundName: info.roundName });
    });
  }

  /** Vergisst die wartende Einladung, wenn es diese ist. */
  private forgetInvite(token: string): void {
    if (this.controller.getState().pendingInvite === token) this.controller.rememberInvite(null);
  }

  /**
   * Mit verbundener Runde: vollständig abgleichen, dann fragen. Gibt es die verbundene Runde nicht mehr, steht die
   * Meldung nach `Detached` da, und es kommt keine Rückfrage. Nur innerhalb von {@link switching}.
   */
  private async askSwitch(target: SwitchTarget): Promise<void> {
    const confirm = await prepareSwitch(this.controller, target);
    if (confirm !== null) this.update({ switching: confirm });
  }

  cancelSwitch(): void {
    this.update({ switching: null });
  }

  /**
   * Wechseln, Beitreten oder Anlegen ist bestätigt. Hat sich das Gerät seit der Rückfrage geändert, wird nicht gewechselt
   * (`CHANGED_MEANWHILE`, siehe `RoundConnection`).
   */
  confirmSwitch(): Promise<void> {
    const confirm = this.ui.switching;
    if (confirm === null || this.ui.busy) return Promise.resolve();
    this.update({ switching: null });
    return this.switching(() => runSwitch(this.controller, confirm), confirm.target.kind === "create");
  }

  /** Liegt auf dem Gerät etwas, das ersetzt würde, kommt erst die Rückfrage (ohne verbundene Runde). */
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
    if (target === null || this.ui.busy) return Promise.resolve();
    this.update({ replace: null });
    return this.busy(() => this.attach(target));
  }

  /**
   * Beitreten oder Verwenden ohne verbundene Runde. Hängt das Gerät inzwischen doch an einer (etwa aus einem anderen
   * Fenster), wird nicht ohne die Rückfrage vor dem Wechseln gewechselt (`CHANGED_MEANWHILE`).
   */
  private async attach(target: PendingAttach): Promise<void> {
    const from = switchFrom(null);
    if (target.kind === "join") await this.controller.join(target.token, from);
    else await this.controller.use(target.round, from);
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

  clearMessage(): void {
    this.update({ message: null });
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
      this.update({ error: uiErrorOf(error, roundName) });
    } finally {
      this.update({ busy: false });
    }
  }

  /**
   * Wie {@link busy}, aber für das Wechseln (vorbereiten bis zur Rückfrage oder der bestätigte Wechsel): Solange es
   * läuft, steht über allem „Einen Moment …“, und nichts lässt sich bedienen, auch „Zurück“ nicht.
   */
  private switching(block: () => Promise<void>, roundName = false): Promise<void> {
    return this.busy(() => this.controller.switchWork(block), roundName);
  }
}
