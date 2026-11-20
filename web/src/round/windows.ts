/*
 * Mehrere Fenster derselben Web-App teilen sich die Datenbank im Browser (docs/ARCHITEKTUR.md, Abschnitt 8.8):
 * - Höchstens ein Abgleich gleichzeitig über alle Fenster: Sperre über `navigator.locks`; wo es sie nicht gibt, wie
 *   bisher nur je Fenster.
 * - Vor jedem Abgleich liest er den Gerätezustand frisch: das Gemerkte der Runde, und die Daten der App, sobald ein
 *   anderes Fenster gespeichert hat. So schreibt kein Fenster mit einem veralteten Stand.
 * - Änderungen meldet ein Fenster den anderen als Ereignis (`BroadcastChannel`; wo es fehlt, über das `storage`-
 *   Ereignis des Browsers), damit jedes gleich zeigt, was ein anderes gespeichert hat.
 */
import type { SyncGuard } from "../core/round/RoundSync";

/** Der Speicher des Browsers (`localStorage`) in der Form, die die Runde braucht. */
export type BrowserStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** Was ein anderes Fenster gespeichert hat: Daten der App (Spieler, Abende, Einstellungen) oder das Gemerkte der Runde. */
export type TabChange = "data" | "round";

/** Meldungen zwischen den Fenstern der App. Ein Fenster bekommt seine eigenen Meldungen nicht. */
export interface TabChannel {
  post(change: TabChange): void;
  subscribe(listener: (change: TabChange) => void): () => void;
  close(): void;
}

/** Der Name des Kanals zwischen den Fenstern. */
export const CHANNEL_NAME = "schafkopf-tracker";

/** Ohne `BroadcastChannel`: Unter diesem Schlüssel im `localStorage` steht die letzte Meldung. */
export const TAB_STAMP_KEY = "schafkopf-tracker-stand";

/** Was das Fenster dafür können muss (in Tests nachstellbar). */
export interface ChannelHost {
  addEventListener(type: "storage", listener: (event: StorageEvent) => void): void;
  removeEventListener(type: "storage", listener: (event: StorageEvent) => void): void;
  readonly BroadcastChannel?: typeof BroadcastChannel;
}

function changeOf(value: unknown): TabChange | null {
  const change = typeof value === "object" && value !== null ? (value as { change?: unknown }).change : undefined;
  return change === "data" || change === "round" ? change : null;
}

/**
 * Der Kanal zu den anderen Fenstern: über `BroadcastChannel`, sonst über das `storage`-Ereignis. In beiden Fällen ist
 * jede Meldung ein eigenes Ereignis; keine geht verloren, weil ein anderes Fenster kurz danach selbst etwas meldet.
 *
 * @param tabId Kennung dieses Fensters (macht im Ersatzweg jede Meldung einzigartig).
 */
export function createTabChannel(host: ChannelHost, storage: BrowserStorage, tabId: string): TabChannel {
  const listeners = new Set<(change: TabChange) => void>();
  const deliver = (change: TabChange): void => {
    for (const listener of [...listeners]) listener(change);
  };
  const subscribe = (listener: (change: TabChange) => void): (() => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  };

  if (typeof host.BroadcastChannel === "function") {
    const channel = new host.BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = (event: MessageEvent) => {
      const change = changeOf(event.data);
      if (change !== null) deliver(change);
    };
    return {
      post: (change) => channel.postMessage({ change }),
      subscribe,
      close: () => channel.close(),
    };
  }

  // Ohne BroadcastChannel: Jede Meldung schreibt einen neuen Wert, und das `storage`-Ereignis kommt in allen anderen
  // Fenstern an (nie im schreibenden).
  let counter = 0;
  const onStorage = (event: StorageEvent): void => {
    if (event.key === null) {
      // Der ganze Speicher wurde geleert: sicherheitshalber alles neu lesen.
      deliver("data");
      deliver("round");
      return;
    }
    if (event.key !== TAB_STAMP_KEY || event.newValue === null) return;
    let value: unknown;
    try {
      value = JSON.parse(event.newValue) as unknown;
    } catch {
      return;
    }
    const change = changeOf(value);
    if (change !== null) deliver(change);
  };
  host.addEventListener("storage", onStorage);
  return {
    post: (change) => storage.setItem(TAB_STAMP_KEY, JSON.stringify({ change, from: tabId, number: ++counter })),
    subscribe,
    close: () => host.removeEventListener("storage", onStorage),
  };
}

/**
 * Fasst Meldungen kurz zusammen: Ein Abgleich schreibt viele Basen hintereinander, die anderen Fenster brauchen davon
 * nur eine Meldung. Keine Meldung geht verloren; sie kommt höchstens `millis` später.
 */
export function coalescing(channel: TabChannel, millis = 100): TabChannel {
  const waiting = new Set<TabChange>();
  let timer: ReturnType<typeof setTimeout> | null = null;
  const send = (): void => {
    timer = null;
    const changes = [...waiting];
    waiting.clear();
    for (const change of changes) channel.post(change);
  };
  return {
    post(change) {
      waiting.add(change);
      timer ??= setTimeout(send, millis);
    },
    subscribe: (listener) => channel.subscribe(listener),
    close() {
      if (timer !== null) clearTimeout(timer);
      send();
      channel.close();
    },
  };
}

// --- Sperre über alle Fenster ---

/** Führt `block` unter der Sperre über alle Fenster aus. */
export type WindowLock = <T>(block: () => Promise<T>) => Promise<T>;

/** Der Name der Sperre, unter der abgeglichen (und verbunden oder getrennt) wird. */
export const LOCK_NAME = "schafkopf-tracker-abgleich";

/**
 * So lange wartet ein Fenster höchstens auf die Sperre. Hält ein anderes Fenster sie länger (zum Beispiel, weil der
 * Browser es mitten im Abgleich angehalten hat), gibt dieser Versuch auf; der Abgleich kommt später wieder.
 */
export const LOCK_WAIT_MILLIS = 15_000;

/** Ein anderes Fenster der App gleicht gerade ab und ist nicht rechtzeitig fertig geworden. */
export class WindowBusyError extends Error {
  constructor() {
    super("Ein anderes Fenster der App gleicht gerade ab");
    this.name = "WindowBusyError";
  }
}

/** Das, was die Sperre braucht (`navigator.locks`), in Tests nachstellbar. */
export interface LockHost {
  readonly locks?: Pick<LockManager, "request">;
}

/** Die Sperre über alle Fenster; `null`, wo der Browser sie nicht kennt (dann gilt die Sperre je Fenster). */
export function windowLock(host: LockHost, waitMillis = LOCK_WAIT_MILLIS): WindowLock | null {
  const locks = host.locks;
  if (locks === undefined || typeof locks.request !== "function") return null;
  return async <T>(block: () => Promise<T>): Promise<T> => {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), waitMillis);
    try {
      return await locks.request(LOCK_NAME, { signal: abort.signal }, async () => {
        clearTimeout(timer);
        return block();
      });
    } catch (error) {
      if (abort.signal.aborted && error instanceof Error && error.name === "AbortError") throw new WindowBusyError();
      throw error;
    } finally {
      clearTimeout(timer);
    }
  };
}

/**
 * Läuft um jeden Abgleich und jedes Verbinden oder Trennen (`RoundSync`): unter der Sperre über alle Fenster (wo es
 * sie gibt) und nach dem frischen Lesen des Gerätezustands.
 *
 * @param refresh Liest den Gerätezustand frisch; scheitert es, läuft auch der Abgleich nicht.
 */
export function windowGuard(lock: WindowLock | null, refresh: () => Promise<void>): SyncGuard {
  return <T>(block: () => Promise<T>): Promise<T> => {
    const run = async (): Promise<T> => {
      await refresh();
      return block();
    };
    return lock === null ? run() : lock(run);
  };
}
