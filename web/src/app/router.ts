/*
 * Navigation über den Anker der Adresse („#/abend/…“). So braucht die App keinen Server, der unbekannte Pfade
 * umleitet, und startet ohne Netz immer mit derselben index.html.
 */
import type { EntryId, EveningId } from "../core/model";

export type Route =
  | { readonly name: "start" }
  | { readonly name: "newEvening" }
  | { readonly name: "board"; readonly eveningId: EveningId }
  | { readonly name: "editEvening"; readonly eveningId: EveningId }
  | { readonly name: "gameForm"; readonly eveningId: EveningId; readonly entryId: EntryId | null }
  | { readonly name: "rules"; readonly eveningId: EveningId | null }
  | { readonly name: "statistics"; readonly eveningId: EveningId | null }
  | { readonly name: "closing"; readonly eveningId: EveningId }
  | { readonly name: "players" }
  | { readonly name: "settings" }
  | { readonly name: "round" }
  /** Die Einladungsseite; `token` ist, was im Link steht (geprüft wird es erst auf der Seite). */
  | { readonly name: "invite"; readonly token: string };

const SEGMENT = "([^/]+)";

/** Der Text ohne %-Kodierung; lässt er sich nicht entschlüsseln, der Text, wie er ist. */
function decodedOrRaw(text: string): string {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

/** Anker (mit oder ohne „#“) → Route. Unbekanntes führt zum Start. */
export function parseRoute(hash: string): Route {
  let path = hash.startsWith("#") ? hash.slice(1) : hash;
  if (!path.startsWith("/")) path = `/${path}`;
  path = path.replace(/\/+$/, "") || "/";
  const match = (pattern: string): string[] | null => {
    const result = new RegExp(`^${pattern}$`).exec(path);
    if (!result) return null;
    try {
      return result.slice(1).map((it) => decodeURIComponent(it));
    } catch {
      return null;
    }
  };
  let m: string[] | null;
  if (path === "/") return { name: "start" };
  if (path === "/neu") return { name: "newEvening" };
  if (path === "/spieler") return { name: "players" };
  if (path === "/einstellungen") return { name: "settings" };
  if (path === "/runde") return { name: "round" };
  if (path === "/einladung") return { name: "invite", token: "" };
  // Alles hinter „/einladung/“ (auch ein verstümmelter Link) führt zur Einladungsseite; ob darin ein gültiger Code
  // steht, prüft erst die Seite („Das ist kein gültiger Einladungslink.“).
  if (path.startsWith("/einladung/")) return { name: "invite", token: decodedOrRaw(path.slice("/einladung/".length)) };
  if (path === "/regeln") return { name: "rules", eveningId: null };
  if (path === "/statistik") return { name: "statistics", eveningId: null };
  if ((m = match(`/abend/${SEGMENT}`))) return { name: "board", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/spiel`))) return { name: "gameForm", eveningId: m[0], entryId: null };
  if ((m = match(`/abend/${SEGMENT}/spiel/${SEGMENT}`))) return { name: "gameForm", eveningId: m[0], entryId: m[1] };
  if ((m = match(`/abend/${SEGMENT}/regeln`))) return { name: "rules", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/statistik`))) return { name: "statistics", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/abschluss`))) return { name: "closing", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/bearbeiten`))) return { name: "editEvening", eveningId: m[0] };
  return { name: "start" };
}

/** Route → Pfad, zum Beispiel „/abend/abc/spiel“. */
export function pathOf(route: Route): string {
  const e = (id: string): string => encodeURIComponent(id);
  switch (route.name) {
    case "start":
      return "/";
    case "newEvening":
      return "/neu";
    case "board":
      return `/abend/${e(route.eveningId)}`;
    case "editEvening":
      return `/abend/${e(route.eveningId)}/bearbeiten`;
    case "gameForm":
      return route.entryId === null
        ? `/abend/${e(route.eveningId)}/spiel`
        : `/abend/${e(route.eveningId)}/spiel/${e(route.entryId)}`;
    case "rules":
      return route.eveningId === null ? "/regeln" : `/abend/${e(route.eveningId)}/regeln`;
    case "statistics":
      return route.eveningId === null ? "/statistik" : `/abend/${e(route.eveningId)}/statistik`;
    case "closing":
      return `/abend/${e(route.eveningId)}/abschluss`;
    case "players":
      return "/spieler";
    case "settings":
      return "/einstellungen";
    case "round":
      return "/runde";
    case "invite":
      return route.token === "" ? "/einladung" : `/einladung/${e(route.token)}`;
  }
}

/** Wohin „Zurück“ führt, wenn es keinen vorigen Bildschirm in der App gibt (zum Beispiel nach dem Neuladen). */
export function parentOf(route: Route): Route {
  switch (route.name) {
    case "gameForm":
    case "closing":
    case "editEvening":
      return { name: "board", eveningId: route.eveningId };
    case "rules":
    case "statistics":
      return route.eveningId === null ? { name: "start" } : { name: "board", eveningId: route.eveningId };
    default:
      return { name: "start" };
  }
}

interface HistoryState {
  /** Wie viele Schritte innerhalb der App vor diesem Bildschirm liegen. */
  readonly depth: number;
}

function depthOf(state: unknown): number {
  const depth = (state as HistoryState | null)?.depth;
  return typeof depth === "number" && Number.isInteger(depth) && depth >= 0 ? depth : 0;
}

/**
 * So lange wartet „Zurück“ darauf, dass der Browser einen Schritt zurückgeht. Geschieht in dieser Zeit nichts, geht es
 * zum übergeordneten Bildschirm.
 */
export const BACK_FALLBACK_MILLIS = 400;

/** Kleiner Router über die History-API mit Anker-Adressen. */
export class Router {
  private readonly listeners = new Set<(route: Route) => void>();
  /** Wie oft der Browser seit dem Laden einen Schritt zurück oder vor gegangen ist (für „Zurück“). */
  private pops = 0;

  constructor(private readonly win: Window = window) {
    win.addEventListener("popstate", () => {
      this.pops++;
      this.emit();
    });
    win.addEventListener("hashchange", () => this.emit());
  }

  current(): Route {
    return parseRoute(this.win.location.hash);
  }

  /**
   * Öffnet einen Bildschirm; `replace` ersetzt den aktuellen (zum Beispiel nach dem Anlegen eines Abends). Ist der
   * Bildschirm schon offen (etwa nach doppeltem Antippen), entsteht kein zweiter Eintrag: Sonst führte „Zurück“
   * scheinbar ins Leere, nämlich auf denselben Bildschirm.
   */
  open(route: Route, options: { replace?: boolean } = {}): void {
    const depth = depthOf(this.win.history.state);
    const href = `#${pathOf(route)}`;
    if (options.replace) this.win.history.replaceState({ depth } satisfies HistoryState, "", href);
    else if (href !== this.win.location.hash) this.win.history.pushState({ depth: depth + 1 } satisfies HistoryState, "", href);
    this.emit();
  }

  /**
   * Zurück: innerhalb der App einen Schritt zurück, sonst zum übergeordneten Bildschirm.
   *
   * Ob es den vorigen Schritt wirklich gibt, weiß nur der Browser: Lädt iOS die App vom Home-Bildschirm neu (zum
   * Beispiel, nachdem sie eine Weile im Hintergrund war), stellt es den letzten Bildschirm samt Zustand wieder her,
   * aber nicht die Schritte davor. Dann bewegt `history.back()` nichts, und „Zurück“ reagierte gar nicht. Deshalb geht
   * es, wenn nach {@link BACK_FALLBACK_MILLIS} kein Schritt zurück kam, zum übergeordneten Bildschirm. Der gilt dann als
   * erster Bildschirm der App, damit das nächste „Zurück“ nicht wieder warten muss.
   */
  back(): void {
    if (depthOf(this.win.history.state) === 0) {
      this.open(parentOf(this.current()), { replace: true });
      return;
    }
    const pops = this.pops;
    const href = this.win.location.href;
    this.win.history.back();
    this.win.setTimeout(() => {
      if (this.pops !== pops || this.win.location.href !== href) return;
      this.win.history.replaceState({ depth: 0 } satisfies HistoryState, "", `#${pathOf(parentOf(this.current()))}`);
      this.emit();
    }, BACK_FALLBACK_MILLIS);
  }

  subscribe(listener: (route: Route) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    const route = this.current();
    for (const listener of this.listeners) listener(route);
  }
}
