/*
 * Steckt die gemeinsame Runde für den Browser zusammen: Anbindung an den Zustand der App, gemerkte Einladung,
 * Rückkehr aus der Anmeldung, Sichtbarkeit der Seite und Netz, mehrere Fenster. Wird nur aufgerufen, wenn die App
 * mit einem Server gebaut wurde; die Bibliothek für den Server lädt erst hier, bei Bedarf.
 */
import { defaultPlayers } from "../app/players";
import type { AppStore } from "../app/store";
import type { Evening, EveningId, Player } from "../core/model";
import type { RoundLink } from "../core/round/RoundModels";
import type { AppRepository } from "../data/repository";
import type { RoundConfig } from "./config";
import { RoundController } from "./controller";
import type { IndexedDbRoundStore } from "./indexedDbRoundStore";
import { storedInviteMemory } from "./inviteMemory";
import { parseSignInReturn, type SignInReturn } from "./signInReturn";
import { StoreAdapters } from "./storeAdapters";

type BrowserStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/**
 * Der Speicher des Browsers (`localStorage`), der nie scheitert: Ist er gesperrt (zum Beispiel im privaten Modus
 * mancher Browser), gilt alles nur, solange die Seite offen ist.
 */
export function safeLocalStorage(win: Window): BrowserStorage {
  const memory = new Map<string, string>();
  return {
    getItem(key) {
      try {
        return win.localStorage.getItem(key) ?? memory.get(key) ?? null;
      } catch {
        return memory.get(key) ?? null;
      }
    },
    setItem(key, value) {
      try {
        win.localStorage.setItem(key, value);
        memory.delete(key);
      } catch {
        memory.set(key, value);
      }
    },
    removeItem(key) {
      memory.delete(key);
      try {
        win.localStorage.removeItem(key);
      } catch {
        // Dann steht dort ohnehin nichts Lesbares.
      }
    },
  };
}

// --- Mehrere Fenster ---

/** Unter diesem Schlüssel steht, welches Fenster zuletzt Daten gespeichert hat. */
export const TAB_STAMP_KEY = "schafkopf-tracker-stand";

/**
 * Sagt den anderen Fenstern der App, dass dieses Fenster Daten gespeichert hat, und erfährt es von ihnen. Ohne das
 * arbeitete ein zweites Fenster mit einem veralteten Stand weiter – und in der gemeinsamen Runde sähe das, was dort
 * fehlt, wie gelöscht aus.
 */
export interface TabSignal {
  /** Dieses Fenster hat gespeichert. */
  announce(): void;

  /** Ob ein anderes Fenster gespeichert hat, seit dieses zuletzt gelesen oder gespeichert hat; merkt sich den neuen Stand. */
  changedElsewhere(): boolean;
}

export function createTabSignal(storage: BrowserStorage, tabId: string): TabSignal {
  let counter = 0;
  let seen = storage.getItem(TAB_STAMP_KEY);
  return {
    announce() {
      seen = `${tabId}:${++counter}`;
      storage.setItem(TAB_STAMP_KEY, seen);
    },
    changedElsewhere() {
      const current = storage.getItem(TAB_STAMP_KEY);
      if (current === seen) return false;
      seen = current;
      return true;
    },
  };
}

/** Der Speicher der App, der nach jedem gelungenen Schreiben Bescheid gibt. */
export function announcingRepository(inner: AppRepository, announce: () => void): AppRepository {
  const after = async (write: Promise<void>): Promise<void> => {
    await write;
    announce();
  };
  return {
    load: () => inner.load(),
    saveEvening: (evening: Evening) => after(inner.saveEvening(evening)),
    deleteEvening: (id: EveningId) => after(inner.deleteEvening(id)),
    savePlayers: (players: readonly Player[]) => after(inner.savePlayers(players)),
    saveSettings: (settings) => after(inner.saveSettings(settings)),
    saveImport: (players: readonly Player[], evenings: readonly Evening[]) => after(inner.saveImport(players, evenings)),
  };
}

// --- Rückkehr aus der Anmeldung ---

/**
 * Liest die Rückkehr aus der Anmeldung aus der Adresse und nimmt sie sofort heraus: Der Code soll weder im Verlauf
 * noch in einem Lesezeichen landen. Danach zeigt die Adresse auf „Gemeinsame Runde“.
 */
export function takeSignInReturn(win: Window): SignInReturn | null {
  const back = parseSignInReturn(win.location.search, win.location.hash);
  if (back !== null) win.history.replaceState(win.history.state, "", `${win.location.pathname}#/runde`);
  return back;
}

// --- Zusammenstecken ---

export interface RoundSetup {
  readonly config: RoundConfig;
  readonly store: AppStore;
  readonly roundStore: IndexedDbRoundStore;
  /** Die verbundene Runde, wie sie beim Start im Speicher stand. */
  readonly initialLink: RoundLink | null;
  readonly tabs: TabSignal;
  readonly storage: BrowserStorage;
  /** Schreibt einen Abend ins Dateiformat des Speichers (zum Prüfen vor dem Übernehmen). */
  readonly encodeEvening: (evening: Evening) => string;
  /** Die Start-Adresse der Web-App: Rückkehr-Adresse der Anmeldung und Anfang der Einladungslinks. */
  readonly webAppUrl: string;
  readonly win: Window;
}

/** Baut die Steuerung der gemeinsamen Runde und lässt sie arbeiten. */
export function startRound(setup: RoundSetup): RoundController {
  const { config, store, roundStore, storage, tabs, win } = setup;
  const signInReturn = takeSignInReturn(win);
  const controller = new RoundController({
    loadBackend: async () => {
      const { createSupabaseBackend } = await import("./supabase/index");
      return createSupabaseBackend(config, { redirectTo: setup.webAppUrl, openUrl: (url) => win.location.assign(url), storage });
    },
    store: roundStore,
    data: new StoreAdapters(store, setup.encodeEvening),
    invites: storedInviteMemory(storage),
    placeholders: defaultPlayers(),
    webAppUrl: setup.webAppUrl,
    ids: { newId: () => store.clock.newId() },
    initialLink: setup.initialLink,
  });

  const doc = win.document;
  const visible = (): boolean => doc.visibilityState === "visible";

  /** Hat ein anderes Fenster gespeichert, liest dieses alles neu – erst das Gemerkte der Runde, dann die Daten. */
  const catchUp = (): void => {
    if (!tabs.changedElsewhere()) return;
    void controller
      .changedElsewhere(async () => {
        roundStore.invalidate();
        await roundStore.link();
        await store.reload();
      })
      .catch((error: unknown) => console.warn("Schafkopf Tracker: Stand des anderen Fensters nicht gelesen", error));
  };

  win.addEventListener("storage", (event) => {
    if (event.key === TAB_STAMP_KEY || event.key === null) catchUp();
  });
  doc.addEventListener("visibilitychange", () => {
    if (visible()) catchUp();
    controller.setVisible(visible());
  });
  // Am iPhone kommt beim Verlassen nicht immer „visibilitychange“.
  win.addEventListener("pagehide", () => controller.setVisible(false));
  win.addEventListener("pageshow", () => {
    if (visible()) catchUp();
    controller.setVisible(visible());
  });
  win.addEventListener("online", () => controller.wake());

  controller.start(visible(), signInReturn);
  return controller;
}
