/*
 * Navigation über den Anker der Adresse („#/abend/…“). So braucht die App keinen Server, der unbekannte Pfade
 * umleitet, und startet ohne Netz immer mit derselben index.html.
 */
import type { EntryId, EveningId } from "../core/model";

export type Route =
  | { readonly name: "start" }
  | { readonly name: "newEvening" }
  | { readonly name: "board"; readonly eveningId: EveningId }
  | { readonly name: "gameForm"; readonly eveningId: EveningId; readonly entryId: EntryId | null }
  | { readonly name: "rules"; readonly eveningId: EveningId | null }
  | { readonly name: "statistics"; readonly eveningId: EveningId | null }
  | { readonly name: "closing"; readonly eveningId: EveningId }
  | { readonly name: "players" }
  | { readonly name: "settings" };

const SEGMENT = "([^/]+)";

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
  if (path === "/regeln") return { name: "rules", eveningId: null };
  if (path === "/statistik") return { name: "statistics", eveningId: null };
  if ((m = match(`/abend/${SEGMENT}`))) return { name: "board", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/spiel`))) return { name: "gameForm", eveningId: m[0], entryId: null };
  if ((m = match(`/abend/${SEGMENT}/spiel/${SEGMENT}`))) return { name: "gameForm", eveningId: m[0], entryId: m[1] };
  if ((m = match(`/abend/${SEGMENT}/regeln`))) return { name: "rules", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/statistik`))) return { name: "statistics", eveningId: m[0] };
  if ((m = match(`/abend/${SEGMENT}/abschluss`))) return { name: "closing", eveningId: m[0] };
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
  }
}

/** Wohin „Zurück“ führt, wenn es keinen vorigen Bildschirm in der App gibt (zum Beispiel nach dem Neuladen). */
export function parentOf(route: Route): Route {
  switch (route.name) {
    case "gameForm":
    case "closing":
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

/** Kleiner Router über die History-API mit Anker-Adressen. */
export class Router {
  private readonly listeners = new Set<(route: Route) => void>();

  constructor(private readonly win: Window = window) {
    win.addEventListener("popstate", () => this.emit());
    win.addEventListener("hashchange", () => this.emit());
  }

  current(): Route {
    return parseRoute(this.win.location.hash);
  }

  /** Öffnet einen Bildschirm; `replace` ersetzt den aktuellen (zum Beispiel nach dem Anlegen eines Abends). */
  open(route: Route, options: { replace?: boolean } = {}): void {
    const depth = depthOf(this.win.history.state);
    const href = `#${pathOf(route)}`;
    if (options.replace) this.win.history.replaceState({ depth } satisfies HistoryState, "", href);
    else this.win.history.pushState({ depth: depth + 1 } satisfies HistoryState, "", href);
    this.emit();
  }

  /** Zurück: innerhalb der App einen Schritt zurück, sonst zum übergeordneten Bildschirm. */
  back(): void {
    if (depthOf(this.win.history.state) > 0) {
      this.win.history.back();
    } else {
      this.open(parentOf(this.current()), { replace: true });
    }
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
