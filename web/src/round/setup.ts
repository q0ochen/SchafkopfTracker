/*
 * Steckt die gemeinsame Runde für den Browser zusammen: Anbindung an den Zustand der App, gemerkte Einladungen,
 * Rückkehr aus der Anmeldung, Sichtbarkeit der Seite und Netz, mehrere Fenster (`windows.ts`). Wird nur aufgerufen,
 * wenn die App mit einem Server gebaut wurde; die Bibliothek für den Server lädt erst hier, bei Bedarf.
 */
import { defaultPlayers } from "../app/players";
import type { AppStore } from "../app/store";
import type { Evening, EveningId, Player } from "../core/model";
import type { RoundLink } from "../core/round/RoundModels";
import type { SyncGuard } from "../core/round/RoundSync";
import type { AppRepository } from "../data/repository";
import type { RoundConfig } from "./config";
import { RoundController } from "./controller";
import type { IndexedDbRoundStore } from "./indexedDbRoundStore";
import { storedInviteMemory, storedIssuedInvites } from "./inviteMemory";
import { parseSignInReturn, type SignInReturn } from "./signInReturn";
import { StoreAdapters } from "./storeAdapters";
import { windowGuard, windowLock, type BrowserStorage, type LockHost, type TabChannel } from "./windows";

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

/**
 * Der Speicher der App, der nach jedem gelungenen Schreiben Bescheid gibt (die anderen Fenster lesen dann neu). Ohne
 * das arbeitete ein zweites Fenster mit einem veralteten Stand weiter.
 */
export function announcingRepository(inner: AppRepository, announce: () => void): AppRepository {
  const after = async (write: Promise<void>): Promise<void> => {
    await write;
    announce();
  };
  return {
    load: () => inner.load(),
    isCurrent: () => inner.isCurrent(),
    saveEvening: (evening: Evening) => after(inner.saveEvening(evening)),
    deleteEvening: (id: EveningId) => after(inner.deleteEvening(id)),
    savePlayers: (players: readonly Player[]) => after(inner.savePlayers(players)),
    saveSettings: (settings) => after(inner.saveSettings(settings)),
    saveImport: (players: readonly Player[], evenings: readonly Evening[]) => after(inner.saveImport(players, evenings)),
  };
}

/**
 * Läuft um jeden Abgleich und jedes Verbinden oder Trennen: höchstens einer über alle Fenster, und vorher liest er den
 * Gerätezustand frisch – das Gemerkte der Runde immer, die Daten der App, sobald ein anderes Fenster gespeichert hat.
 */
export function syncGuard(host: LockHost, roundStore: Pick<IndexedDbRoundStore, "invalidate">, store: Pick<AppStore, "catchUp">): SyncGuard {
  return windowGuard(windowLock(host), async () => {
    roundStore.invalidate(false);
    await store.catchUp();
  });
}

/**
 * Hält dieses Fenster auf dem Stand der anderen: Hat ein anderes Fenster Daten gespeichert, liest es neu (unter der
 * Sperre des Abgleichs, damit nichts dazwischenkommt); mehrere Meldungen kurz hintereinander ergeben ein Neulesen.
 */
function catchingUp(controller: RoundController, store: AppStore): () => void {
  let running = false;
  let again = false;
  const run = (): void => {
    if (running) {
      again = true;
      return;
    }
    running = true;
    again = false;
    controller
      .changedElsewhere(async () => {
        await store.catchUp();
      })
      .catch((error: unknown) => console.warn("Schafkopf Tracker: Stand des anderen Fensters nicht gelesen", error))
      .finally(() => {
        running = false;
        if (again) run();
      });
  };
  return run;
}

// --- Rückkehr aus der Anmeldung ---

/**
 * Liest die Rückkehr aus der Anmeldung aus der Adresse und nimmt sie sofort heraus: Der Code soll weder im Verlauf
 * noch in einem Lesezeichen landen. Danach zeigt die Adresse auf „Gemeinsame Runden“.
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
  /** Was sich das Gerät zur Runde merkt; meldet jedes Schreiben über {@link channel} (siehe `main.tsx`). */
  readonly roundStore: IndexedDbRoundStore;
  /** Die verbundene Runde, wie sie beim Start im Speicher stand. */
  readonly initialLink: RoundLink | null;
  /** Meldungen von und zu den anderen Fenstern der App. */
  readonly channel: TabChannel;
  readonly storage: BrowserStorage;
  /** Schreibt einen Abend ins Dateiformat des Speichers (zum Prüfen vor dem Übernehmen). */
  readonly encodeEvening: (evening: Evening) => string;
  /** Die Start-Adresse der Web-App: Rückkehr-Adresse der Anmeldung und Anfang der Einladungslinks. */
  readonly webAppUrl: string;
  readonly win: Window;
}

/** Baut die Steuerung der gemeinsamen Runde und lässt sie arbeiten. */
export function startRound(setup: RoundSetup): RoundController {
  const { config, store, roundStore, storage, channel, win } = setup;
  const signInReturn = takeSignInReturn(win);
  const data = new StoreAdapters(store, setup.encodeEvening);
  const controller = new RoundController({
    loadBackend: async () => {
      const { createSupabaseBackend } = await import("./supabase/index");
      return createSupabaseBackend(config, { redirectTo: setup.webAppUrl, openUrl: (url) => win.location.assign(url), storage });
    },
    store: data.shield(roundStore),
    data,
    invites: storedInviteMemory(storage),
    issuedInvites: storedIssuedInvites(storage),
    placeholders: defaultPlayers(),
    webAppUrl: setup.webAppUrl,
    ids: { newId: () => store.clock.newId() },
    initialLink: setup.initialLink,
    guard: syncGuard(win.navigator as LockHost, roundStore, store),
  });

  const doc = win.document;
  const visible = (): boolean => doc.visibilityState === "visible";
  const catchUp = catchingUp(controller, store);

  channel.subscribe((change) => {
    // Das Gemerkte der Runde gleich frisch lesen (für die Oberfläche); die Daten der App unter der Sperre.
    if (change === "round") roundStore.invalidate();
    else catchUp();
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
