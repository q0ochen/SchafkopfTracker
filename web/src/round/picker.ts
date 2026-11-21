/*
 * Die Runden-Auswahl auf dem Start-Bildschirm (docs/ARCHITEKTUR.md 8.9, „Runden-Auswahl auf dem Start-Bildschirm“):
 * welche Runden zur Wahl stehen, wie sie heißen, und was geschieht, wenn jemand eine andere wählt – vorher vollständig
 * abgleichen, dann „Zur Runde ‹Ziel› wechseln?“. Reine Logik ohne Oberfläche, damit sie sich prüfen lässt.
 */
import { roundSummary, type RoundId, type RoundLink, type RoundSummary } from "../core/round/RoundModels";
import { RoundNames } from "../core/round/RoundNames";
import { prepareSwitch, runSwitch, stillValid, uiErrorOf, type RoundUiError, type SwitchConfirm } from "./actions";
import { needsSignIn, type RoundController, type RoundState } from "./controller";
import { roundChoiceLabel } from "./texts";

/** Ein Eintrag der Auswahl. */
export interface RoundChoice {
  readonly round: RoundSummary;
  readonly label: string;
}

/** Die verbundene Runde als Eintrag, wenn sie unter den Runden des Kontos fehlt (die Mitglieder nennt dann nur die Zahl). */
function linkedSummary(link: RoundLink): RoundSummary {
  return roundSummary({
    id: link.roundId,
    name: link.roundName,
    rev: link.cursor,
    memberCount: link.members.length,
    myPlayerId: link.playerId,
    isCreator: link.isCreator,
  });
}

/**
 * Die Einträge der Auswahl: die Runden des Kontos in der Reihenfolge von `my_rounds`; fehlt die verbundene darin, steht
 * sie vorn. Haben zwei Einträge denselben Namen (`RoundNames.same`), steht bei beiden dahinter „ (‹Mitglieder›)“.
 */
export function roundChoices(link: RoundLink, rounds: readonly RoundSummary[]): RoundChoice[] {
  const entries = rounds.some((it) => it.id === link.roundId) ? [...rounds] : [linkedSummary(link), ...rounds];
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
 * @property busy Vor der Rückfrage wird abgeglichen bzw. gerade gewechselt: Die Auswahl ist gesperrt, darunter steht
 *   „Einen Moment …“.
 * @property error Ein Fehler (Texte wie auf dem Bildschirm „Gemeinsame Runden“); bleibt, bis man ihn wegtippt.
 * @property confirm Wartet auf die Rückfrage „Zur Runde ‹Ziel› wechseln?“.
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
  private readonly unsubscribe: () => void;
  private readonly unwatchRounds: () => void;
  private disposed = false;

  /** Erscheint der Start-Bildschirm, werden die Runden des Kontos gelesen (sobald jemand angemeldet ist). */
  constructor(private readonly controller: RoundController) {
    this.unsubscribe = controller.subscribe(() => this.watch());
    this.unwatchRounds = controller.watchRounds();
  }

  /** Der Start-Bildschirm ist weg: nichts mehr beobachten. */
  dispose(): void {
    this.disposed = true;
    this.unsubscribe();
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

  /** Ist das Gerät inzwischen nicht mehr mit der Runde verbunden, für die gefragt wurde, gilt die Rückfrage nicht mehr. */
  private watch(): void {
    const confirm = this.ui.confirm;
    if (confirm !== null && !stillValid(this.controller, confirm)) this.update({ confirm: null });
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
      const confirm = await prepareSwitch(this.controller, { kind: "use", round });
      if (confirm !== null) this.update({ confirm });
    });
  }

  cancel(): void {
    this.update({ confirm: null });
  }

  /** „Wechseln“ in der Rückfrage. */
  confirm(): Promise<void> {
    const confirm = this.ui.confirm;
    if (confirm === null) return Promise.resolve();
    this.update({ confirm: null });
    if (!stillValid(this.controller, confirm)) return Promise.resolve();
    return this.busy(() => runSwitch(this.controller, confirm.target));
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
