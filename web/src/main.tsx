/*
 * Kompositionswurzel der Web-App: Speicher öffnen, Daten laden, Oberfläche zeigen, Service Worker anmelden.
 * Nur hier werden die konkreten Umsetzungen zusammengesteckt.
 */
import { render } from "preact";
import { useEffect, useState } from "preact/hooks";
import { backupPort, storageCodec } from "./app/formatAdapters";
import { Router } from "./app/router";
import { AppStore } from "./app/store";
import { IndexedDbRepository, openDatabase } from "./data/repository";
import { browserPlatform } from "./platform/platform";
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

async function start(): Promise<void> {
  const root = document.getElementById("app");
  if (!root) return;
  const db = await openDatabase();
  const store = await AppStore.load(new IndexedDbRepository(db, storageCodec));
  const platform = browserPlatform();
  const services: Services = {
    store,
    router: new Router(),
    platform,
    backup: backupPort,
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
  // Die Daten liegen nur hier: den Browser bitten, sie nicht bei Platzmangel zu räumen.
  void platform.requestPersistentStorage();
  registerServiceWorker(updates);
}

start().catch((error: unknown) => {
  console.error(error);
  const root = document.getElementById("app");
  if (root) {
    const main = document.createElement("main");
    main.className = "content";
    const title = document.createElement("h1");
    title.textContent = "Schafkopf Tracker";
    const text = document.createElement("p");
    text.textContent =
      "Die App konnte nicht starten. Bitte den Website-Speicher erlauben (zum Beispiel nicht im privaten Modus) und die Seite neu laden.";
    main.append(title, text);
    root.replaceChildren(main);
  }
});
