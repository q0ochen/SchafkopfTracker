/*
 * Was die Einladungsseite der Web-App zeigt (`#/einladung/‹Code›`, docs/ARCHITEKTUR.md, Abschnitt 8.9), als reine
 * Funktion. Am iPhone öffnen sich Links aus Nachrichten in Safari (oder im Browser des Messengers), und die App vom
 * Home-Bildschirm hat einen eigenen Speicher: Dort hilft nur, den Code zu kopieren und in der App einzufügen.
 */
import type { DeviceOs } from "../platform/platform";

/** Der Hinweis fürs iPhone, wenn die Seite nicht als App vom Home-Bildschirm läuft. */
export const IPHONE_HINT =
  "Benutzt du den Schafkopf Tracker vom Home-Bildschirm? Dann kopiere den Code, öffne die App über ihr Symbol und füge ihn unter „Gemeinsame Runde“ → „Einladung einlösen“ ein.";

/** Der Satz, wenn dieses Gerät schon mit einer Runde verbunden ist. */
export function alreadyLinkedText(roundName: string): string {
  return `Dieses Gerät ist schon mit der Runde ${roundName} verbunden.`;
}

export interface InvitePageInput {
  readonly os: DeviceOs;
  /** Die Seite läuft als App vom Home-Bildschirm. */
  readonly standalone: boolean;
  /** Der Name der Runde, mit der dieses Gerät schon verbunden ist; `null` ohne Runde. */
  readonly linkedRound: string | null;
}

/**
 * @property iphoneHint Der Hinweis fürs iPhone (nur auf iOS außerhalb der App vom Home-Bildschirm).
 * @property androidApp Der Knopf „In der Android-App öffnen“ (nur auf Android).
 * @property joinHere „Hier beitreten“; ist das Gerät schon verbunden, stattdessen der Satz dazu mit „Zur gemeinsamen
 *   Runde“ (der Code wird dann nicht gemerkt).
 * @property primary Der hervorgehobene Knopf: Am iPhone im Browser ist es „Code kopieren“, auf Android die App,
 *   sonst „Hier beitreten“ bzw. „Zur gemeinsamen Runde“.
 */
export interface InvitePage {
  readonly iphoneHint: boolean;
  readonly androidApp: boolean;
  readonly joinHere: boolean;
  readonly primary: "copy" | "android" | "join" | "round";
}

export function invitePage({ os, standalone, linkedRound }: InvitePageInput): InvitePage {
  const iphoneHint = os === "ios" && !standalone;
  const androidApp = os === "android";
  const joinHere = linkedRound === null;
  const primary = iphoneHint ? "copy" : androidApp ? "android" : joinHere ? "join" : "round";
  return { iphoneHint, androidApp, joinHere, primary };
}
