/*
 * Was die Bildschirme der gemeinsamen Runde gemeinsam haben (Start-Bildschirm und „Gemeinsame Runden“): wie eine
 * gescheiterte Aktion als Fehler erscheint, die bekannten Runden des Kontos, die Prüfung des Namens einer neuen Runde
 * und die „Rückfrage vor dem Wechseln“ (docs/ARCHITEKTUR.md 8.9). Reine Logik ohne Oberfläche, damit sie sich prüfen
 * lässt.
 */
import { DeviceStorageError } from "../app/store";
import { RoundException, roundSummary, type RoundError, type RoundLink, type RoundSummary, type SwitchFrom } from "../core/round/RoundModels";
import { RoundNames } from "../core/round/RoundNames";
import type { RoundController, RoundState } from "./controller";

/** Ein Fehler, den ein Bildschirm in Worten zeigt. */
export type RoundUiError =
  /** Im eingefügten Text steht kein Einladungscode. */
  | { readonly kind: "NoCode" }
  /** Der Speicher des Browsers lässt sich gerade nicht verlässlich benutzen (wie in der Statuszeile). */
  | { readonly kind: "Storage" }
  /** `roundName`: Es ging um den Namen einer Runde. */
  | { readonly kind: "Server"; readonly error: RoundError; readonly roundName: boolean };

/**
 * Der Fehler zu einer gescheiterten Aktion.
 *
 * @param roundName Es ging um den Namen einer Runde (für den Fehlertext).
 */
export function uiErrorOf(error: unknown, roundName = false): RoundUiError {
  if (error instanceof RoundException) return { kind: "Server", error: error.error, roundName };
  if (error instanceof DeviceStorageError) return { kind: "Storage" };
  console.warn("Schafkopf Tracker: Aktion der gemeinsamen Runde gescheitert", error);
  return { kind: "Server", error: "SERVER", roundName: false };
}

/** Die verbundene Runde als Eintrag einer Liste (die Mitglieder nennt dann nur die Zahl). */
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
 * Die Runden des Kontos, soweit bekannt (`RoundState.rounds`, in der Reihenfolge von `my_rounds`); die verbundene ist
 * immer dabei (fehlt sie, vorn). Damit zählt ihr Name auch dann als vergeben, wenn die Liste noch nicht gelesen ist.
 * Kotlin: `knownRounds`.
 */
export function knownRounds(state: Pick<RoundState, "link" | "rounds">): RoundSummary[] {
  const rounds = state.rounds ?? [];
  const link = state.link;
  if (link === null || rounds.some((it) => it.id === link.roundId)) return [...rounds];
  return [linkedSummary(link), ...rounds];
}

/**
 * Die Runden, deren Namen für eine neue Runde vergeben sind (docs/ARCHITEKTUR.md 8.7, „Eindeutige Namen“): die bekannten
 * außer den eigenen, noch leeren, denn die nimmt das Anlegen wieder; die verbundene zählt immer (`RoundNames.blocking`).
 */
function blockingRounds(state: Pick<RoundState, "link" | "rounds">): RoundSummary[] {
  return RoundNames.blocking(knownRounds(state), state.link?.roundId ?? null);
}

/** Die Vorgabe im Feld „Name der Runde“: „Schafkopfrunde“, ist der Name vergeben, die erste freie Fassung mit Nummer. */
export function suggestedRoundName(state: Pick<RoundState, "link" | "rounds">): string {
  return RoundNames.suggest(blockingRounds(state));
}

/**
 * Was gegen den Namen einer neuen Runde spricht, bevor die App sie anlegt bzw. danach fragt (docs/ARCHITEKTUR.md 8.7,
 * „Eindeutige Namen“): `INVALID_NAME`, wenn er nach dem Bereinigen nicht 1 bis 40 Zeichen hat; `NAME_TAKEN`, wenn eine
 * Runde des Kontos schon so heißt (außer einer eigenen, noch leeren, siehe {@link blockingRounds}); sonst `null`. Ohne
 * gelesene Liste prüft das Anlegen selbst noch einmal.
 */
export function roundNameProblem(name: string, state: Pick<RoundState, "link" | "rounds">): RoundError | null {
  if (!RoundNames.valid(name)) return "INVALID_NAME";
  return RoundNames.taken(name, blockingRounds(state)) ? "NAME_TAKEN" : null;
}

/**
 * Wohin es mit verbundener Runde gehen soll:
 * - `use`: zu einer anderen Runde des Kontos wechseln,
 * - `join`: einer weiteren Runde beitreten (`roundName` aus `invite_info`),
 * - `create`: eine neue Runde anlegen (`name` bereinigt).
 */
export type SwitchTarget =
  | { readonly kind: "use"; readonly round: RoundSummary }
  | { readonly kind: "join"; readonly token: string; readonly roundName: string }
  | { readonly kind: "create"; readonly name: string };

/** Der Name des Ziels in der Rückfrage („‹Ziel›“). */
export function targetName(target: SwitchTarget): string {
  switch (target.kind) {
    case "use":
      return target.round.name;
    case "join":
      return target.roundName;
    case "create":
      return target.name;
  }
}

/**
 * Die „Rückfrage vor dem Wechseln“, nachdem vollständig abgeglichen ist.
 *
 * @property from Was gefragt wird: von welcher Runde aus und wie viele Änderungen dabei verloren gingen
 *   (`from.acceptedLoss`, etwa ein Abend, den der Server nicht annimmt). Es geht mit der Bestätigung an den Wechsel; hat
 *   sich das Gerät bis dahin geändert, wird nicht gewechselt (`CHANGED_MEANWHILE`).
 * @property fromName Der Name der bisherigen Runde („Die Abende von ‹bisherige Runde› bleiben dort …“).
 */
export interface SwitchConfirm {
  readonly target: SwitchTarget;
  readonly from: SwitchFrom;
  readonly fromName: string;
}

/**
 * Bereitet die Rückfrage vor: gleicht die verbundene Runde vollständig ab (`RoundController.prepareSwitch`). `null`, wenn
 * es nichts zu fragen gibt (nicht verbunden, oder die Runde gibt es nicht mehr: dann steht die Meldung nach `Detached`
 * da). Scheitert der Abgleich, scheitert auch dieser Aufruf, und es bleibt alles, wie es ist.
 */
export async function prepareSwitch(controller: RoundController, target: SwitchTarget): Promise<SwitchConfirm | null> {
  const before = controller.getState().link;
  const from = await controller.prepareSwitch();
  if (from === null) return null;
  // Der Abgleich kann den Namen aufgefrischt haben; hängt das Gerät inzwischen an einer anderen Runde, entscheidet der
  // Wechsel selbst (`CHANGED_MEANWHILE`), und die Rückfrage nennt die Runde, von der aus gefragt wurde.
  const now = controller.getState().link;
  const fromName = (now?.roundId === from.roundId ? now : before)?.roundName ?? "";
  return { target, from, fromName };
}

/** Führt aus, was die Rückfrage bestätigt hat, mit dem, was sie gefragt hat. */
export async function runSwitch(controller: RoundController, confirm: SwitchConfirm): Promise<void> {
  const { target, from } = confirm;
  switch (target.kind) {
    case "use":
      await controller.use(target.round, from);
      return;
    case "join":
      await controller.join(target.token, from);
      return;
    case "create":
      await controller.create(target.name, from);
      return;
  }
}
