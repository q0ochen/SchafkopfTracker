/* Einladungscodes aus Links und eingefügtem Text. Port von `InviteLinksTest.kt`. */
import { describe, expect, it } from "vitest";
import { InviteLinks } from "./InviteLinks";

const token = "0123456789abcdef0123456789abcdef";
const web = "https://q0ochen.github.io/SchafkopfTracker/";

describe("InviteLinks", () => {
  it("der bloße Code wird erkannt, auch mit Leerraum drumherum", () => {
    expect(InviteLinks.tokenFrom(token)).toBe(token);
    expect(InviteLinks.tokenFrom(`  ${token}\n`)).toBe(token);
  });

  it("der Link zur Web-App und der Link in die App werden erkannt", () => {
    expect(InviteLinks.tokenFrom(`${web}#/einladung/${token}`)).toBe(token);
    expect(InviteLinks.tokenFrom(`schafkopftracker://einladung/${token}`)).toBe(token);
  });

  it("der Link wird auch mitten in einer Nachricht gefunden", () => {
    const message = `Servus! Komm in unsere Runde: ${web}#/einladung/${token}\nBis Samstag!`;

    expect(InviteLinks.tokenFrom(message)).toBe(token);
    expect(InviteLinks.tokenFrom(`${web}#/einladung/${token}.`)).toBe(token);
    expect(InviteLinks.tokenFrom(`${web}#/einladung/${token}?quelle=chat`)).toBe(token);
    expect(InviteLinks.tokenFrom(`Einladungscode: ${token}`)).toBe(token);
  });

  it("stehen mehrere Folgen im Text, zählt die erste mit genau 32 Zeichen", () => {
    const other = "f".repeat(32);

    expect(InviteLinks.tokenFrom(`abc ${"1".repeat(31)} ${token} ${other}`)).toBe(token);
    expect(InviteLinks.tokenFrom(`${token}0 und ${other}`)).toBe(other);
  });

  it("alles andere ist kein Code", () => {
    expect(InviteLinks.tokenFrom("")).toBeNull();
    expect(InviteLinks.tokenFrom("   ")).toBeNull();
    expect(InviteLinks.tokenFrom("Schafkopf")).toBeNull();
    expect(InviteLinks.tokenFrom(token.slice(0, -1))).toBeNull();
    expect(InviteLinks.tokenFrom(`${token}0`)).toBeNull();
    expect(InviteLinks.tokenFrom(token.toUpperCase())).toBeNull();
    expect(InviteLinks.tokenFrom(token.replaceAll("0", "g"))).toBeNull();
    expect(InviteLinks.tokenFrom(`${web}#/einladung/${token.slice(0, -1)}`)).toBeNull();
    expect(InviteLinks.tokenFrom(`${web}#/einladung/${token}0`)).toBeNull();
    // „de“ sind auch Zeichen eines Codes: Die Folge ist dann 34 Zeichen lang.
    expect(InviteLinks.tokenFrom(`Code${token}`)).toBeNull();
  });

  it("die erzeugten Links lassen sich wieder lesen", () => {
    expect(InviteLinks.webLink(web, token)).toBe(`https://q0ochen.github.io/SchafkopfTracker/#/einladung/${token}`);
    expect(InviteLinks.webLink(web.replace(/\/+$/, ""), token)).toBe(InviteLinks.webLink(web, token));
    expect(InviteLinks.appLink(token)).toBe(`schafkopftracker://einladung/${token}`);
    expect(InviteLinks.APP_SCHEME).toBe("schafkopftracker");
    expect(InviteLinks.tokenFrom(InviteLinks.webLink(web, token))).toBe(token);
    expect(InviteLinks.tokenFrom(InviteLinks.appLink(token))).toBe(token);
  });
});
