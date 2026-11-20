import { describe, expect, it } from "vitest";
import { parentOf, parseRoute, pathOf, type Route } from "./router";

describe("Router", () => {
  const routes: Route[] = [
    { name: "start" },
    { name: "newEvening" },
    { name: "board", eveningId: "abc" },
    { name: "gameForm", eveningId: "abc", entryId: null },
    { name: "gameForm", eveningId: "abc", entryId: "e-1" },
    { name: "rules", eveningId: null },
    { name: "rules", eveningId: "abc" },
    { name: "statistics", eveningId: null },
    { name: "statistics", eveningId: "abc" },
    { name: "closing", eveningId: "abc" },
    { name: "players" },
    { name: "settings" },
    { name: "round" },
    { name: "invite", token: "0123456789abcdef0123456789abcdef" },
    { name: "invite", token: "" },
  ];

  it("liest jede Adresse so, wie sie geschrieben wurde", () => {
    for (const route of routes) expect(parseRoute(`#${pathOf(route)}`)).toEqual(route);
  });

  it("kodiert Kennungen mit Sonderzeichen", () => {
    const route: Route = { name: "gameForm", eveningId: "a/b c", entryId: "ä?#" };
    expect(pathOf(route)).toBe("/abend/a%2Fb%20c/spiel/%C3%A4%3F%23");
    expect(parseRoute(`#${pathOf(route)}`)).toEqual(route);
  });

  it("führt Unbekanntes und Kaputtes zum Start", () => {
    expect(parseRoute("")).toEqual({ name: "start" });
    expect(parseRoute("#")).toEqual({ name: "start" });
    expect(parseRoute("#/gibtsnicht")).toEqual({ name: "start" });
    expect(parseRoute("#/abend/%E0%A4%A")).toEqual({ name: "start" });
    expect(parseRoute("#/abend/x/spiel/y/z")).toEqual({ name: "start" });
  });

  it("verträgt Schrägstriche am Ende und fehlende Anfangs-Schrägstriche", () => {
    expect(parseRoute("#/spieler/")).toEqual({ name: "players" });
    expect(parseRoute("#einstellungen")).toEqual({ name: "settings" });
  });

  it("kennt die Seiten der gemeinsamen Runde", () => {
    expect(parseRoute("#/runde")).toEqual({ name: "round" });
    expect(pathOf({ name: "round" })).toBe("/runde");
    // Der Einladungslink, wie ihn `InviteLinks.webLink` baut.
    expect(parseRoute("#/einladung/0123456789abcdef0123456789abcdef")).toEqual({ name: "invite", token: "0123456789abcdef0123456789abcdef" });
    expect(parseRoute("#/einladung/")).toEqual({ name: "invite", token: "" });
    // Was im Link steht, prüft erst die Seite – auch ein verstümmelter Link führt dorthin (und nicht still zum Start).
    expect(parseRoute("#/einladung/kein-code")).toEqual({ name: "invite", token: "kein-code" });
    expect(parseRoute("#/einladung/a/b")).toEqual({ name: "invite", token: "a/b" });
    expect(parseRoute("#/einladung/%E0%A4%A")).toEqual({ name: "invite", token: "%E0%A4%A" });
    const token = "0123456789abcdef0123456789abcdef";
    expect(parseRoute(`#/einladung/${token}/`)).toEqual({ name: "invite", token });
    expect(parseRoute(pathOf({ name: "invite", token: "a/b" }))).toEqual({ name: "invite", token: "a/b" });
    expect(parentOf({ name: "round" })).toEqual({ name: "start" });
    expect(parentOf({ name: "invite", token: "x" })).toEqual({ name: "start" });
  });

  it("kennt den übergeordneten Bildschirm", () => {
    expect(parentOf({ name: "gameForm", eveningId: "a", entryId: null })).toEqual({ name: "board", eveningId: "a" });
    expect(parentOf({ name: "closing", eveningId: "a" })).toEqual({ name: "board", eveningId: "a" });
    expect(parentOf({ name: "rules", eveningId: "a" })).toEqual({ name: "board", eveningId: "a" });
    expect(parentOf({ name: "rules", eveningId: null })).toEqual({ name: "start" });
    expect(parentOf({ name: "statistics", eveningId: null })).toEqual({ name: "start" });
    expect(parentOf({ name: "board", eveningId: "a" })).toEqual({ name: "start" });
  });
});
