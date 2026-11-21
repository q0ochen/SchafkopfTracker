import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BACK_FALLBACK_MILLIS, parentOf, parseRoute, pathOf, Router, type Route } from "./router";

describe("Router", () => {
  const routes: Route[] = [
    { name: "start" },
    { name: "newEvening" },
    { name: "board", eveningId: "abc" },
    { name: "editEvening", eveningId: "abc" },
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

  it("„Abend bearbeiten“ liegt unter dem Abend", () => {
    expect(pathOf({ name: "editEvening", eveningId: "a/b" })).toBe("/abend/a%2Fb/bearbeiten");
    expect(parseRoute("#/abend/abc/bearbeiten")).toEqual({ name: "editEvening", eveningId: "abc" });
    expect(parseRoute("#/abend/abc/bearbeiten/x")).toEqual({ name: "start" });
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
    expect(parentOf({ name: "editEvening", eveningId: "a" })).toEqual({ name: "board", eveningId: "a" });
    expect(parentOf({ name: "rules", eveningId: "a" })).toEqual({ name: "board", eveningId: "a" });
    expect(parentOf({ name: "rules", eveningId: null })).toEqual({ name: "start" });
    expect(parentOf({ name: "statistics", eveningId: null })).toEqual({ name: "start" });
    expect(parentOf({ name: "board", eveningId: "a" })).toEqual({ name: "start" });
  });
});

/**
 * Ein Fenster mit History im Arbeitsspeicher: Einträge mit Zustand und Anker; `back()` meldet `popstate` erst etwas
 * später, wie ein Browser. `lost` stellt nach, dass iOS die App neu geladen und nur den letzten Eintrag behalten hat.
 */
class FakeWindow {
  entries: { state: unknown; hash: string }[] = [{ state: null, hash: "" }];
  index = 0;
  private readonly handlers = new Map<string, Array<() => void>>();

  readonly history = {
    pushState: (state: unknown, _title: string, url: string) => {
      this.entries = [...this.entries.slice(0, this.index + 1), { state, hash: url }];
      this.index = this.entries.length - 1;
    },
    replaceState: (state: unknown, _title: string, url: string) => {
      this.entries[this.index] = { state, hash: url };
    },
    back: () => {
      if (this.index === 0) return;
      this.index--;
      setTimeout(() => this.dispatch("popstate"), 10);
    },
    get state(): unknown {
      return undefined;
    },
  };

  readonly location = {
    get hash(): string {
      return "";
    },
    get href(): string {
      return "";
    },
  };

  constructor() {
    Object.defineProperty(this.history, "state", { get: () => this.entries[this.index].state });
    Object.defineProperty(this.location, "hash", { get: () => this.entries[this.index].hash });
    Object.defineProperty(this.location, "href", { get: () => `https://example.org/app/${this.entries[this.index].hash}` });
  }

  addEventListener(type: string, handler: () => void): void {
    this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler]);
  }

  setTimeout(handler: () => void, millis: number): number {
    return setTimeout(handler, millis) as unknown as number;
  }

  dispatch(type: string): void {
    for (const handler of this.handlers.get(type) ?? []) handler();
  }
}

describe("Router: Zurück", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function routerOn(win: FakeWindow): { router: Router; routes: Route[] } {
    const router = new Router(win as unknown as Window);
    const routes: Route[] = [];
    router.subscribe((route) => routes.push(route));
    return { router, routes };
  }

  it("geht einen Schritt zurück, ohne zusätzlich zum übergeordneten Bildschirm zu springen", () => {
    const win = new FakeWindow();
    const { router, routes } = routerOn(win);
    router.open({ name: "round" });
    router.back();
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS + 50);

    expect(router.current()).toEqual({ name: "start" });
    expect(routes.at(-1)).toEqual({ name: "start" });
    expect(win.entries).toHaveLength(2);
  });

  it("führt nach dem Abend zurück, wenn der Abend über den Abend hinaus geöffnet wurde", () => {
    // Vom Abend aus „Gemeinsame Runde“: Zurück führt zum Abend, nicht zum übergeordneten Start.
    const win = new FakeWindow();
    const { router } = routerOn(win);
    router.open({ name: "board", eveningId: "abend" });
    router.open({ name: "round" });
    router.back();
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS + 50);
    expect(router.current()).toEqual({ name: "board", eveningId: "abend" });
  });

  it("geht zum übergeordneten Bildschirm, wenn der Browser keinen Schritt zurück hat", () => {
    // So stellt iOS die App vom Home-Bildschirm nach dem Neuladen her: letzter Bildschirm samt Zustand, nichts davor.
    const win = new FakeWindow();
    win.entries = [{ state: { depth: 1 }, hash: "#/abend/abend" }];
    const { router, routes } = routerOn(win);

    router.back();
    expect(router.current()).toEqual({ name: "board", eveningId: "abend" });
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS);

    expect(router.current()).toEqual({ name: "start" });
    expect(routes.at(-1)).toEqual({ name: "start" });
    expect(win.entries).toEqual([{ state: { depth: 0 }, hash: "#/" }]);
  });

  it("wartet nach einem vergessenen Schritt beim nächsten Zurück nicht noch einmal", () => {
    // Formular eines Spiels, nach dem Neuladen ohne die Schritte davor: Zurück führt zum Abend (nach der Wartezeit),
    // das nächste Zurück sofort zum Start.
    const win = new FakeWindow();
    win.entries = [{ state: { depth: 2 }, hash: "#/abend/abend/spiel" }];
    const { router } = routerOn(win);

    router.back();
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS);
    expect(router.current()).toEqual({ name: "board", eveningId: "abend" });

    router.back();
    expect(router.current()).toEqual({ name: "start" });
    expect(win.entries).toEqual([{ state: { depth: 0 }, hash: "#/" }]);

    // Danach geht es ganz normal weiter: Neue Schritte lassen sich wieder zurückgehen.
    router.open({ name: "board", eveningId: "abend" });
    router.back();
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS + 50);
    expect(router.current()).toEqual({ name: "start" });
    expect(win.entries).toHaveLength(2);
  });

  it("ohne Schritte in der App geht es sofort zum übergeordneten Bildschirm", () => {
    const win = new FakeWindow();
    win.entries = [{ state: null, hash: "#/abend/abend/spiel" }];
    const { router } = routerOn(win);
    router.back();
    expect(router.current()).toEqual({ name: "board", eveningId: "abend" });
  });

  it("öffnet denselben Bildschirm nicht doppelt (doppeltes Antippen)", () => {
    const win = new FakeWindow();
    const { router, routes } = routerOn(win);
    router.open({ name: "board", eveningId: "abend" });
    router.open({ name: "board", eveningId: "abend" });
    expect(win.entries).toHaveLength(2);
    expect(routes).toHaveLength(2);

    router.back();
    vi.advanceTimersByTime(BACK_FALLBACK_MILLIS + 50);
    expect(router.current()).toEqual({ name: "start" });
  });
});

