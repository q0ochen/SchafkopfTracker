/*
 * Einladungen in eine gemeinsame Runde: ein Code aus 32 Zeichen (`0-9a-f`), verpackt in einen Link zur Web-App oder
 * direkt in die Android-App. Port von `domain/round/InviteLinks.kt`.
 */

/** Eigenes Schema der Android-App (auch für die Rückkehr aus der Anmeldung). */
const APP_SCHEME = "schafkopftracker";
const PATH = "einladung/";
const CODE_LENGTH = 32;

export const InviteLinks = {
  APP_SCHEME,

  /**
   * Der Einladungscode aus dem, was jemand einfügt oder antippt: der bloße Code, ein Link zur Web-App
   * (`…#/einladung/<code>`) oder der Link in die Android-App, auch mitten in einer Nachricht. Genommen wird die erste
   * Folge aus genau 32 Zeichen `0-9a-f`; gibt es keine, `null`.
   */
  tokenFrom(text: string): string | null {
    return text.match(/[0-9a-f]+/g)?.find((run) => run.length === CODE_LENGTH) ?? null;
  },

  /** Der Link zum Weitergeben: öffnet die Web-App, die auch „In der Android-App öffnen“ anbietet. */
  webLink(webAppUrl: string, token: string): string {
    return `${webAppUrl.replace(/\/+$/, "")}/#/${PATH}${token}`;
  },

  /** Der Link, der die Android-App direkt öffnet. */
  appLink(token: string): string {
    return `${APP_SCHEME}://${PATH}${token}`;
  },
};
