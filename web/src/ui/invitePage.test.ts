/* Was die Einladungsseite der Web-App zeigt (docs/ARCHITEKTUR.md, Abschnitt 8.9), und woran die App das Gerät erkennt. */
import { describe, expect, it } from "vitest";
import { deviceOs, runsStandalone } from "../platform/platform";
import { alreadyLinkedText, invitePage, IPHONE_HINT } from "./invitePage";

const IPHONE_SAFARI =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
const IPAD_AS_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15";
const ANDROID_CHROME = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const DESKTOP_CHROME = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
const WHATSAPP_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 WhatsApp/24.12";

describe("Gerät erkennen", () => {
  it("iPhone (auch im Browser eines Messengers), iPad, Android, sonst", () => {
    expect(deviceOs({ userAgent: IPHONE_SAFARI, maxTouchPoints: 5 })).toBe("ios");
    expect(deviceOs({ userAgent: WHATSAPP_IPHONE })).toBe("ios");
    // Ein iPad meldet sich wie ein Mac, hat aber einen Touchscreen; ein echter Mac nicht.
    expect(deviceOs({ userAgent: IPAD_AS_MAC, maxTouchPoints: 5 })).toBe("ios");
    expect(deviceOs({ userAgent: IPAD_AS_MAC, maxTouchPoints: 0 })).toBe("other");
    expect(deviceOs({ userAgent: ANDROID_CHROME, maxTouchPoints: 5 })).toBe("android");
    expect(deviceOs({ userAgent: DESKTOP_CHROME, maxTouchPoints: 0 })).toBe("other");
  });

  it("als App vom Home-Bildschirm: am iPhone `navigator.standalone`, sonst die Anzeigeart", () => {
    const media = (matches: boolean) => () => ({ matches });
    expect(runsStandalone({ navigator: { standalone: true }, matchMedia: media(false) })).toBe(true);
    expect(runsStandalone({ navigator: { standalone: false }, matchMedia: media(false) })).toBe(false);
    expect(runsStandalone({ navigator: {}, matchMedia: media(true) })).toBe(true);
    expect(runsStandalone({ navigator: {}, matchMedia: media(false) })).toBe(false);
    expect(runsStandalone({ navigator: {} })).toBe(false);
    expect(
      runsStandalone({
        navigator: {},
        matchMedia: () => {
          throw new Error("nicht unterstützt");
        },
      }),
    ).toBe(false);
  });
});

describe("Einladungsseite", () => {
  it("am iPhone im Browser: der Hinweis, „Code kopieren“ vorn, „Hier beitreten“ trotzdem", () => {
    expect(invitePage({ os: "ios", standalone: false, linkedRound: null })).toEqual({
      iphoneHint: true,
      androidApp: false,
      alreadyLinked: false,
      primary: "copy",
    });
  });

  it("am iPhone als App vom Home-Bildschirm: kein Hinweis, gleich hier beitreten", () => {
    expect(invitePage({ os: "ios", standalone: true, linkedRound: null })).toEqual({
      iphoneHint: false,
      androidApp: false,
      alreadyLinked: false,
      primary: "join",
    });
  });

  it("auf Android: „In der Android-App öffnen“ vorn, kein Hinweis fürs iPhone", () => {
    expect(invitePage({ os: "android", standalone: false, linkedRound: null })).toEqual({
      iphoneHint: false,
      androidApp: true,
      alreadyLinked: false,
      primary: "android",
    });
  });

  it("am Computer: nur „Hier beitreten“", () => {
    expect(invitePage({ os: "other", standalone: false, linkedRound: null })).toEqual({
      iphoneHint: false,
      androidApp: false,
      alreadyLinked: false,
      primary: "join",
    });
  });

  it("ist das Gerät schon verbunden, steht der Satz dazu über „Hier beitreten“; beitreten geht trotzdem", () => {
    expect(invitePage({ os: "other", standalone: false, linkedRound: "Stammtisch" })).toEqual({
      iphoneHint: false,
      androidApp: false,
      alreadyLinked: true,
      primary: "join",
    });
    expect(invitePage({ os: "ios", standalone: false, linkedRound: "Stammtisch" })).toMatchObject({ alreadyLinked: true, iphoneHint: true, primary: "copy" });
    expect(invitePage({ os: "android", standalone: false, linkedRound: "Stammtisch" })).toMatchObject({ alreadyLinked: true, primary: "android" });
    expect(alreadyLinkedText("Stammtisch")).toBe(
      "Dieses Gerät ist schon mit der Runde Stammtisch verbunden. Du kannst trotzdem beitreten und dann zwischen den Runden wechseln.",
    );
  });

  it("der Hinweis fürs iPhone wörtlich", () => {
    expect(IPHONE_HINT).toBe(
      "Benutzt du den Schafkopf Tracker vom Home-Bildschirm? Dann kopiere den Code, öffne die App über ihr Symbol und füge ihn unter „Gemeinsame Runden“ → „Einladung einlösen“ ein.",
    );
  });
});
