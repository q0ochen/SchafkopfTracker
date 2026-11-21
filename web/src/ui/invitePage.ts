/*
 * Was die Einladungsseite der Web-App zeigt (`#/einladung/‹Code›`, docs/ARCHITEKTUR.md, Abschnitt 8.9), als reine
 * Funktion. Am iPhone öffnen sich Links aus Nachrichten in Safari (oder im Browser des Messengers), und die App vom
 * Home-Bildschirm hat einen eigenen Speicher: Dort hilft nur, den Code zu kopieren und in der App einzufügen.
 */
import type { DeviceOs } from "../platform/platform";

/** Der Hinweis fürs iPhone, wenn die Seite nicht als App vom Home-Bildschirm läuft. */
export const IPHONE_HINT =
  "Benutzt du den Schafkopf Tracker vom Home-Bildschirm? Dann kopiere den Code, öffne die App über ihr Symbol und füge ihn unter „Gemeinsame Runden“ → „Einladung einlösen“ ein.";

/**
 * Der Satz über „Hier beitreten“, wenn dieses Gerät schon mit einer Runde verbunden ist (seit dem 21.11.2026 geht
 * Beitreten trotzdem; danach lässt sich zwischen den Runden wechseln).
 */
export function alreadyLinkedText(roundName: string): string {
  return `Dieses Gerät ist schon mit der Runde ${roundName} verbunden. Du kannst trotzdem beitreten und dann zwischen den Runden wechseln.`;
}

export interface InvitePageInput {
  readonly os: DeviceOs;
  /** Die Seite läuft als App vom Home-Bildschirm. */
  readonly standalone: boolean;
  /** Der Name der Runde, mit der dieses Gerät schon verbunden ist; `null` ohne Runde. */
  readonly linkedRound: string | null;
}

/**
 * „Hier beitreten“ (weiter zu „Gemeinsame Runden“, der Code wird gemerkt) gibt es immer.
 *
 * @property iphoneHint Der Hinweis fürs iPhone (nur auf iOS außerhalb der App vom Home-Bildschirm).
 * @property androidApp Der Knopf „In der Android-App öffnen“ (nur auf Android).
 * @property alreadyLinked Das Gerät ist schon mit einer Runde verbunden: Über „Hier beitreten“ steht der Satz dazu
 *   ({@link alreadyLinkedText}).
 * @property primary Der hervorgehobene Knopf: Am iPhone im Browser ist es „Code kopieren“, auf Android die App, sonst
 *   „Hier beitreten“.
 */
export interface InvitePage {
  readonly iphoneHint: boolean;
  readonly androidApp: boolean;
  readonly alreadyLinked: boolean;
  readonly primary: "copy" | "android" | "join";
}

export function invitePage({ os, standalone, linkedRound }: InvitePageInput): InvitePage {
  const iphoneHint = os === "ios" && !standalone;
  const androidApp = os === "android";
  const primary = iphoneHint ? "copy" : androidApp ? "android" : "join";
  return { iphoneHint, androidApp, alreadyLinked: linkedRound !== null, primary };
}
