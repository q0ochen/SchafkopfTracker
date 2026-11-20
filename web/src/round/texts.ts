/*
 * Die Texte der gemeinsamen Runde, wie docs/ARCHITEKTUR.md, Abschnitt 8.9 sie festlegt (wörtlich, in der Android-App
 * dieselben): Statuszeile, Fehler und Hinweise aus dem Abgleich. Reine Funktionen, damit sie sich einfach prüfen lassen.
 */
import type { RoundError } from "../core/round/RoundModels";
import type { SyncNotice } from "../core/round/RoundSync";

/** Warum der letzte Abgleich nicht geklappt hat. */
export type RoundProblem = "OFFLINE" | "SERVER" | "SIGNED_OUT";

/** Was die Statuszeile wissen muss. */
export interface RoundStatusInput {
  /** Verbunden, aber die Anmeldung fehlt oder ist abgelaufen. */
  readonly needsSignIn: boolean;
  readonly problem: RoundProblem | null;
  /** So viele Änderungen dieses Geräts sind noch nicht auf dem Server. */
  readonly pending: number;
  /** So viele Abende des Servers kann diese Version nicht lesen. */
  readonly unreadable: number;
  /** So viele Abende nimmt der Server nicht an. */
  readonly refused: number;
}

/** Wie es um den Abgleich steht; bestimmt das Symbol. Der Text daneben sagt dasselbe in Worten. */
export type RoundStatusKind = "SYNCED" | "PENDING" | "PROBLEM";

export interface RoundStatus {
  readonly kind: RoundStatusKind;
  readonly text: string;
}

function changes(count: number): string {
  return count === 1 ? "1 Änderung wartet" : `${count} Änderungen warten`;
}

/** Die Statuszeile einer verbundenen Runde: Es gilt die erste zutreffende Zeile der Tabelle in Abschnitt 8.9. */
export function roundStatus(state: RoundStatusInput): RoundStatus {
  if (state.needsSignIn) return { kind: "PROBLEM", text: "Anmeldung abgelaufen – bitte neu anmelden" };
  if (state.unreadable > 0) {
    return {
      kind: "PROBLEM",
      text: `${state.unreadable === 1 ? "1 Abend" : `${state.unreadable} Abende`} kann diese Version nicht lesen – bitte die App aktualisieren`,
    };
  }
  if (state.refused > 0) {
    return { kind: "PROBLEM", text: `${state.refused === 1 ? "1 Abend" : `${state.refused} Abende`} nimmt der Server nicht an` };
  }
  if (state.problem === "OFFLINE") {
    return { kind: "PROBLEM", text: state.pending > 0 ? `Kein Netz – ${changes(state.pending)}` : "Kein Netz" };
  }
  if (state.problem === "SERVER") {
    return { kind: "PROBLEM", text: state.pending > 0 ? `Server nicht erreichbar – ${changes(state.pending)}` : "Server nicht erreichbar" };
  }
  if (state.pending > 0) return { kind: "PENDING", text: changes(state.pending) };
  return { kind: "SYNCED", text: "Abgeglichen" };
}

/** Die Beschreibung des Wolken-Symbols in der Kopfzeile eines Abends. */
export function statusIconLabel(status: RoundStatus): string {
  return `Gemeinsame Runde: ${status.text}`;
}

/**
 * Der Text zu einem gescheiterten Aufruf.
 *
 * @param roundName `true`, wenn es um den Namen einer Runde ging (1 bis 40 Zeichen statt 1 bis 24).
 */
export function roundErrorText(error: RoundError, roundName = false): string {
  switch (error) {
    case "INVALID_INVITE":
      return "Die Einladung ist abgelaufen oder ungültig.";
    case "NAME_TAKEN":
      return "Den Namen gibt es in der Runde schon.";
    case "INVALID_NAME":
      return roundName ? "Bitte einen Namen mit 1 bis 40 Zeichen eingeben." : "Bitte einen Namen mit 1 bis 24 Zeichen eingeben.";
    case "PLAYER_TAKEN":
      return "Diesen Spieler hat gerade jemand anders gewählt.";
    case "ALREADY_CLAIMED":
      return "Du hast schon einen Namen in dieser Runde.";
    case "LIMIT_REACHED":
      return "Mehr geht nicht: Die Grenze für Runden, Mitglieder oder Einladungen ist erreicht.";
    case "OFFLINE":
      return "Keine Verbindung. Bitte später noch einmal versuchen.";
    case "SIGNED_OUT":
      return "Anmeldung abgelaufen – bitte neu anmelden";
    default:
      return GENERIC_ERROR;
  }
}

export const GENERIC_ERROR = "Das hat nicht geklappt. Bitte später noch einmal versuchen.";

/** Im eingefügten Text steht kein Einladungscode. */
export const NO_CODE_ERROR = "Das ist kein gültiger Einladungslink.";

/** Der Hinweis zu einer Änderung an einem Spieler, die der Server abgelehnt hat. */
export function noticeText(notice: SyncNotice): string {
  const now = notice.nowName;
  switch (notice.reason) {
    case "NAME_LOCKED":
      return `${now ?? notice.name} hat den Namen selbst festgelegt. Er bleibt.`;
    case "NAME_TAKEN":
      return now === null
        ? `Den Namen ${notice.name} gibt es in der Runde schon. Der neue Spieler wurde nicht angelegt.`
        : `Den Namen ${notice.name} gibt es in der Runde schon. Der Spieler heißt jetzt ${now}.`;
    case "PLAYER_OWNED":
      return `${notice.name} gehört zu einem Mitglied und lässt sich nicht löschen.`;
    case "LIMIT_REACHED":
      return `Die Runde hat schon 40 Spieler. ${notice.name} wurde nicht hochgeladen.`;
    default:
      return `Der Name ${notice.name} ist nicht erlaubt.`;
  }
}

/**
 * Eine einmalige Meldung für den Bildschirm „Gemeinsame Runde“:
 * - `SIGN_IN_FAILED`: Die Anmeldung ist gescheitert.
 * - `SIGN_IN_CANCELLED`: Die Anmeldung wurde im Browser abgebrochen.
 * - `OTHER_ACCOUNT`: Angemeldet wurde ein anderes Konto als das der verbundenen Runde. Es wurde gleich wieder
 *   abgemeldet; die Runde bleibt verbunden.
 * - `DETACHED`: Die Runde gibt es nicht mehr, oder das Konto gehört nicht mehr dazu.
 */
export type RoundMessage = "SIGN_IN_FAILED" | "SIGN_IN_CANCELLED" | "OTHER_ACCOUNT" | "DETACHED";

/** Meldungen, die eine geglückte Anmeldung erledigt. */
export const SIGN_IN_MESSAGES: ReadonlySet<RoundMessage> = new Set<RoundMessage>(["SIGN_IN_FAILED", "SIGN_IN_CANCELLED", "OTHER_ACCOUNT"]);

export function messageText(message: RoundMessage): string {
  switch (message) {
    case "SIGN_IN_FAILED":
      return "Die Anmeldung hat nicht geklappt. Bitte noch einmal versuchen.";
    case "SIGN_IN_CANCELLED":
      return "Die Anmeldung wurde abgebrochen.";
    case "OTHER_ACCOUNT":
      return "Diese Runde gehört zu einem anderen Google-Konto. Melde dich mit dem Konto an, mit dem du der Runde beigetreten bist.";
    case "DETACHED":
      return "Die Runde gibt es nicht mehr, oder du gehörst nicht mehr dazu. Die Daten bleiben als Kopie auf diesem Gerät.";
  }
}

/** „1 Mitglied“ oder „3 Mitglieder“. */
export function memberCountText(count: number): string {
  return count === 1 ? "1 Mitglied" : `${count} Mitglieder`;
}

/** Der Text der Rückfrage „Daten dieses Geräts ersetzen?“. */
export function replaceText(eveningCount: number): string {
  if (eveningCount <= 0) return "Die Spieler dieses Geräts werden durch die der Runde ersetzt.";
  return eveningCount === 1
    ? "Auf diesem Gerät liegt 1 Abend. Er wird durch die Daten der Runde ersetzt. Sichere ihn vorher, wenn du ihn behalten willst."
    : `Auf diesem Gerät liegen ${eveningCount} Abende. Sie werden durch die Daten der Runde ersetzt. Sichere sie vorher, wenn du sie behalten willst.`;
}

/** Der Zusatz in den Rückfragen vor dem Verlassen und Abmelden, wenn noch etwas wartet. */
export function pendingWarning(pending: number): string {
  return pending === 1
    ? "1 Änderung ist noch nicht hochgeladen und kommt nicht mehr in der Runde an."
    : `${pending} Änderungen sind noch nicht hochgeladen und kommen nicht mehr in der Runde an.`;
}

/** Der Text, mit dem der Einladungslink weitergegeben wird. */
export function inviteMessage(roundName: string, link: string): string {
  return `Komm in unsere Schafkopfrunde ${roundName}: ${link}`;
}

/** Wer gibt (über der Eingabe eines laufenden Abends). */
export function dealerHint(isMe: boolean, dealerName: string): string {
  return isMe ? "Du gibst – trag dieses Spiel ein." : `${dealerName} gibt und trägt ein.`;
}
