/*
 * Kompositionswurzel der Web-App: Speicher öffnen, Daten laden, Oberfläche zeigen, Service Worker anmelden.
 * Nur hier werden die konkreten Umsetzungen zusammengesteckt.
 */
import { render } from "preact";
import { useEffect, useState } from "preact/hooks";
import { backupPort, roundStoreCodec, storageCodec } from "./app/formatAdapters";
import { Router } from "./app/router";
import { AppStore, systemClock } from "./app/store";
import { DATABASE_NAME, IndexedDbRepository, openDatabase, type AppRepository } from "./data/repository";
import { browserPlatform } from "./platform/platform";
import { readRoundConfig } from "./round/config";
import type { RoundController } from "./round/controller";
import { IndexedDbRoundStore } from "./round/indexedDbRoundStore";
import { announcingRepository, createTabSignal, safeLocalStorage, startRound } from "./round/setup";
import { App } from "./ui/App";
import type { Services } from "./ui/context";
import "./ui/styles.css";

/** Meldet, wenn eine neue Version bereitsteht, und übernimmt sie auf Wunsch. */
class UpdateNotifier {
  private apply: (() => void) | null = null;
  private readonly listeners = new Set<() => void>();

  offer(apply: () => void): void {
    this.apply = apply;
    for (const listener of this.listeners) listener();
  }

  ready(): boolean {
    return this.apply !== null;
  }

  install(): void {
    this.apply?.();
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
}

function Root({ services, updates }: { services: Services; updates: UpdateNotifier }) {
  const [updateReady, setUpdateReady] = useState(updates.ready());
  useEffect(() => updates.subscribe(() => setUpdateReady(updates.ready())), [updates]);
  return <App services={services} updateReady={updateReady} onReload={() => updates.install()} />;
}

/**
 * Meldet den Service Worker an (nur im fertigen Build). Liegt eine neue Version bereit, fragt die App, ob sie neu
 * laden soll; erst dann übernimmt der neue Service Worker (nie mitten in einer Eingabe).
 */
function registerServiceWorker(updates: UpdateNotifier): void {
  if (!import.meta.env.PROD || !("serviceWorker" in navigator)) return;
  const base = import.meta.env.BASE_URL;
  const hadController = navigator.serviceWorker.controller !== null;
  let reloading = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    // Beim allerersten Besuch übernimmt der Service Worker ohne Neuladen.
    if (!hadController || reloading) return;
    reloading = true;
    window.location.reload();
  });
  navigator.serviceWorker
    .register(`${base}sw.js`, { scope: base })
    .then((registration: ServiceWorkerRegistration | undefined) => {
      // Ohne Anmeldung (zum Beispiel in Tests, die Service Worker sperren) gibt es nichts zu tun.
      if (!registration) return;
      const offer = (worker: ServiceWorker) => updates.offer(() => worker.postMessage("skip-waiting"));
      if (registration.waiting && navigator.serviceWorker.controller) offer(registration.waiting);
      registration.addEventListener("updatefound", () => {
        const worker = registration.installing;
        worker?.addEventListener("statechange", () => {
          if (worker.state === "installed" && navigator.serviceWorker.controller) offer(worker);
        });
      });
      // Beim Zurückkehren in die App nach einer neuen Version sehen.
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible") registration.update().catch(() => {});
      });
    })
    .catch((error: unknown) => console.warn("Schafkopf Tracker: Service Worker nicht angemeldet", error));
}

/** Zeigt statt der App einen Hinweis (wenn sie in diesem Fenster gerade nicht arbeiten kann). */
function showNotice(root: HTMLElement, message: string, reload: boolean): void {
  const main = document.createElement("main");
  main.className = "content";
  const title = document.createElement("h1");
  title.textContent = "Schafkopf Tracker";
  const text = document.createElement("p");
  text.textContent = message;
  main.append(title, text);
  if (reload) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "button primary main";
    button.textContent = "Neu laden";
    button.addEventListener("click", () => window.location.reload());
    main.append(button);
  }
  root.replaceChildren(main);
}

async function start(): Promise<void> {
  const root = document.getElementById("app");
  if (!root) return;
  let round: RoundController | null = null;
  const db = await openDatabase(DATABASE_NAME, {
    blocked: () => {
      // Die App ist noch in einem anderen Fenster mit der vorigen Version geöffnet; solange wartet die Datenbank.
      // Sobald sie offen ist, ersetzt die Oberfläche den Hinweis.
      showNotice(
        root,
        "Die App ist noch in einem anderen Fenster oder Tab geöffnet. Bitte dort schließen oder neu laden; danach geht es hier von selbst weiter.",
        false,
      );
    },
    outdated: () => {
      // Eine neuere Version der App (in einem anderen Fenster) hat die Datenbank übernommen: Hier geht nichts mehr.
      round?.stop();
      showNotice(root, "Die App wurde in einem anderen Fenster aktualisiert. Bitte diese Seite neu laden.", true);
    },
  });

  // Gemeinsame Runde: nur, wenn die App mit einem Server gebaut wurde. Sonst bleibt alles wie ohne sie – der Build
  // lässt dann auch den ganzen Zweig weg.
  const roundConfig = import.meta.env.VITE_ROUND_ENABLED
    ? readRoundConfig(import.meta.env.VITE_SUPABASE_URL, import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY)
    : null;
  let repository: AppRepository = new IndexedDbRepository(db, storageCodec);
  let store: AppStore;
  if (import.meta.env.VITE_ROUND_ENABLED && roundConfig !== null) {
    const storage = safeLocalStorage(window);
    const tabs = createTabSignal(storage, systemClock.newId());
    repository = announcingRepository(repository, () => tabs.announce());
    const roundStore = new IndexedDbRoundStore(db, roundStoreCodec);
    const link = await roundStore.link();
    // Mit verbundener Runde kommen die Spieler vom Server; Platzhalter gingen sonst als neue Spieler hoch.
    store = await AppStore.load(repository, systemClock, { seedPlayers: link === null });
    round = startRound({
      config: roundConfig,
      store,
      roundStore,
      initialLink: link,
      tabs,
      storage,
      encodeEvening: storageCodec.encodeEvening,
      webAppUrl: new URL(import.meta.env.BASE_URL, window.location.origin).href,
      win: window,
    });
  } else {
    store = await AppStore.load(repository);
  }
  const platform = browserPlatform();
  const services: Services = {
    store,
    // Erst jetzt: Die Rückkehr aus der Anmeldung hat die Adresse schon auf „Gemeinsame Runde“ gestellt.
    router: new Router(),
    platform,
    backup: backupPort,
    round,
    version: import.meta.env.VITE_APP_VERSION ?? "",
  };
  // Solange noch gespeichert wird, steht das an der Seite (für Tests, die vor dem Neuladen darauf warten).
  store.setSavingListener((saving) => {
    if (saving) document.documentElement.dataset.saving = "true";
    else delete document.documentElement.dataset.saving;
  });
  const updates = new UpdateNotifier();
  root.textContent = "";
  render(<Root services={services} updates={updates} />, root);
  // Den Browser bitten, die Daten nicht bei Platzmangel zu räumen (ohne gemeinsame Runde liegen sie nur hier).
  void platform.requestPersistentStorage();
  registerServiceWorker(updates);
}

start().catch((error: unknown) => {
  console.error(error);
  const root = document.getElementById("app");
  if (root) {
    showNotice(
      root,
      "Die App konnte nicht starten. Bitte den Website-Speicher erlauben (zum Beispiel nicht im privaten Modus) und die Seite neu laden.",
      false,
    );
  }
});
