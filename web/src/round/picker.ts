/*
 * Die Runden-Auswahl auf dem Start-Bildschirm (docs/ARCHITEKTUR.md 8.9, „Runden-Auswahl auf dem Start-Bildschirm“):
 * welche Runden zur Wahl stehen, wie sie heißen, und was geschieht, wenn jemand eine andere wählt – vorher vollständig
 * abgleichen, dann „Zur Runde ‹Ziel› wechseln?“. Reine Logik ohne Oberfläche, damit sie sich prüfen lässt.
 */
import type { RoundId, RoundLink, RoundSummary } from "../core/round/RoundModels";
import { RoundNames } from "../core/round/RoundNames";
import { knownRounds, prepareSwitch, runSwitch, uiErrorOf, type RoundUiError, type SwitchConfirm } from "./actions";
import { needsSignIn, type RoundController, type RoundState } from "./controller";
import { roundChoiceLabel } from "./texts";

/** Ein Eintrag der Auswahl. */
export interface RoundChoice {
  readonly round: RoundSummary;
  readonly label: string;
}

/**
 * Die Einträge der Auswahl: die Runden des Kontos in der Reihenfolge von `my_rounds`; fehlt die verbundene darin, steht
 * sie vorn (die Mitglieder nennt sie dann nur als Zahl). Haben zwei Einträge denselben Namen (`RoundNames.same`), steht
 * bei beiden dahinter „ (‹Mitglieder›)“.
 */
export function roundChoices(link: RoundLink, rounds: readonly RoundSummary[]): RoundChoice[] {
  const entries = knownRounds({ link, rounds });
  const duplicates = RoundNames.duplicates(entries);
  return entries.map((round) => ({ round, label: roundChoiceLabel(round, duplicates.has(round.id)) }));
}

/**
 * Ob die Auswahl dasteht: Das Gerät ist mit einer Runde verbunden, keine neue Anmeldung ist nötig, die Runden des Kontos
 * sind (einmal) gelesen, und außer der verbundenen gibt es mindestens eine weitere. Scheitert ein späteres Lesen,
 * bleibt die zuletzt gelesene Liste.
 */
export function showsPicker(state: RoundState): boolean {
  const link = state.link;
  if (link === null || needsSignIn(state) || state.rounds === null) return false;
  return state.rounds.some((it) => it.id !== link.roundId);
}

/**
 * @property busy Vor der Rückfrage wird abgeglichen bzw. gerade gewechselt: Die Auswahl ist gesperrt, und über allem
 *   steht „Einen Moment …“ (`RoundState.switching`).
 * @property error Ein Fehler (Texte wie auf dem Bildschirm „Gemeinsame Runden“); bleibt, bis man ihn wegtippt.
 * @property confirm Wartet auf die Rückfrage „Zur Runde ‹Ziel› wechseln?“. Sie bleibt, bis man sie beantwortet; hat sich
 *   das Gerät bis dahin geändert, wird beim Bestätigen nicht gewechselt (`CHANGED_MEANWHILE`).
 */
export interface PickerUi {
  readonly busy: boolean;
  readonly error: RoundUiError | null;
  readonly confirm: SwitchConfirm | null;
}

const INITIAL: PickerUi = { busy: false, error: null, confirm: null };

export class RoundPicker {
  private ui: PickerUi = INITIAL;
  private readonly listeners = new Set<() => void>();
  private readonly unwatchRounds: () => void;
  private disposed = false;

  /** Erscheint der Start-Bildschirm, werden die Runden des Kontos gelesen (sobald jemand angemeldet ist). */
  constructor(private readonly controller: RoundController) {
    this.unwatchRounds = controller.watchRounds();
  }

  /**
   * Der Start-Bildschirm ist weg: nichts mehr beobachten. Ein laufender Wechsel läuft trotzdem zu Ende (siehe
   * `RoundConnection`).
   */
  dispose(): void {
    this.disposed = true;
    this.unwatchRounds();
  }

  getUi(): PickerUi {
    return this.ui;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private update(changes: Partial<PickerUi>): void {
    if (this.disposed) return;
    this.ui = { ...this.ui, ...changes };
    for (const listener of [...this.listeners]) listener();
  }

  /**
   * Jemand hat in der Auswahl eine Runde gewählt. Ist es eine andere als die verbundene, wird vollständig abgeglichen
   * und dann gefragt; die Auswahl zeigt bis zum Wechsel weiter die verbundene Runde.
   */
  choose(roundId: RoundId): Promise<void> {
    const state = this.controller.getState();
    const round = state.rounds?.find((it) => it.id === roundId) ?? null;
    if (round === null || state.link === null || round.id === state.link.roundId) return Promise.resolve();
    return this.busy(async () => {
      const confirm = await this.controller.switchWork(() => prepareSwitch(this.controller, { kind: "use", round }));
      if (confirm !== null) this.update({ confirm });
    });
  }

  cancel(): void {
    this.update({ confirm: null });
  }

  /** „Wechseln“ in der Rückfrage. */
  confirm(): Promise<void> {
    const confirm = this.ui.confirm;
    if (confirm === null || this.ui.busy) return Promise.resolve();
    this.update({ confirm: null });
    return this.busy(() => this.controller.switchWork(() => runSwitch(this.controller, confirm)));
  }

  clearError(): void {
    this.update({ error: null });
  }

  /** Führt eine Aktion aus; währenddessen ist die Auswahl gesperrt. Läuft schon eine, passiert nichts. */
  private async busy(block: () => Promise<void>): Promise<void> {
    if (this.ui.busy) return;
    this.update({ busy: true, error: null });
    try {
      await block();
    } catch (error) {
      this.update({ error: uiErrorOf(error) });
    } finally {
      this.update({ busy: false });
    }
  }
}
