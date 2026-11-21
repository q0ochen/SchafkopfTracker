/*
 * Was die Bildschirme der gemeinsamen Runde gemeinsam haben (Start-Bildschirm und „Gemeinsame Runden“): wie eine
 * gescheiterte Aktion als Fehler erscheint, die Prüfung des Namens einer neuen Runde und die „Rückfrage vor dem
 * Wechseln“ (docs/ARCHITEKTUR.md 8.9). Reine Logik ohne Oberfläche, damit sie sich prüfen lässt.
 */
import { DeviceStorageError } from "../app/store";
import { RoundException, type RoundError, type RoundId, type RoundSummary } from "../core/round/RoundModels";
import { RoundNames } from "../core/round/RoundNames";
import type { RoundController } from "./controller";

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

/** Den Stand `rev = 1` hat eine Runde nur unmittelbar nach dem Anlegen (wie in `RoundConnection`). */
const FRESH_REV = 1;

/**
 * Was gegen den Namen einer neuen Runde spricht, bevor die App sie anlegt bzw. danach fragt (docs/ARCHITEKTUR.md 8.7,
 * „Eindeutige Namen“): `INVALID_NAME`, wenn er nach dem Bereinigen nicht 1 bis 40 Zeichen hat; `NAME_TAKEN`, wenn eine
 * Runde des Kontos schon so heißt; sonst `null`. Wie in `RoundConnection.create` zählt die eigene, noch leere Runde eines
 * Versuchs, dessen Antwort verloren ging, nicht: Die nimmt das Anlegen, statt eine zweite anzulegen.
 *
 * @param rounds Die bekannten Runden des Kontos (ohne Liste prüft das Anlegen selbst noch einmal).
 */
export function roundNameProblem(name: string, rounds: readonly RoundSummary[]): RoundError | null {
  if (!RoundNames.valid(name)) return "INVALID_NAME";
  const reusable = (round: RoundSummary): boolean => round.isCreator && round.memberCount === 1 && round.rev === FRESH_REV;
  return rounds.some((round) => RoundNames.same(round.name, name) && !reusable(round)) ? "NAME_TAKEN" : null;
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
 * @property fromId Die bisherige Runde; ist das Gerät beim Bestätigen nicht mehr mit ihr verbunden, gilt die Rückfrage
 *   nicht mehr.
 * @property from Ihr Name („Die Abende von ‹bisherige Runde› bleiben dort …“).
 * @property pending So viele Änderungen warten nach dem Abgleich noch; sie gingen beim Wechseln verloren.
 */
export interface SwitchConfirm {
  readonly target: SwitchTarget;
  readonly fromId: RoundId;
  readonly from: string;
  readonly pending: number;
}

/**
 * Bereitet die Rückfrage vor: gleicht die verbundene Runde vollständig ab (`RoundController.beforeSwitch`). `null`, wenn
 * es nichts zu fragen gibt (nicht mehr verbunden, oder die Runde gibt es nicht mehr: dann steht die Meldung nach
 * `Detached` da). Scheitert der Abgleich, scheitert auch dieser Aufruf, und es bleibt alles, wie es ist.
 */
export async function prepareSwitch(controller: RoundController, target: SwitchTarget): Promise<SwitchConfirm | null> {
  const link = controller.getState().link;
  if (link === null) return null;
  const pending = await controller.beforeSwitch();
  const after = controller.getState().link;
  if (pending === null || after === null || after.roundId !== link.roundId) return null;
  return { target, fromId: after.roundId, from: after.roundName, pending };
}

/** Führt aus, was die Rückfrage bestätigt hat. */
export async function runSwitch(controller: RoundController, target: SwitchTarget): Promise<void> {
  switch (target.kind) {
    case "use":
      await controller.use(target.round);
      return;
    case "join":
      await controller.join(target.token);
      return;
    case "create":
      await controller.create(target.name);
      return;
  }
}

/** Ob die Rückfrage noch gilt: Das Gerät ist noch mit der Runde verbunden, für die sie gestellt wurde. */
export function stillValid(controller: RoundController, confirm: SwitchConfirm): boolean {
  return controller.getState().link?.roundId === confirm.fromId;
}
