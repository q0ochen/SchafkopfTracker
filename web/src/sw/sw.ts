/**
 * Service Worker der Web-App (bewusst klein, ohne Bibliothek): Alle ausgelieferten Dateien werden beim
 * Installieren zwischengespeichert, damit die App auch ohne Netz startet (im Wirtshaus ist der Empfang oft
 * schlecht). Seitenaufrufe gehen zuerst ans Netz, damit Updates sofort ankommen, und fallen ohne Netz auf die
 * gespeicherte index.html zurück. Daten verlassen das Gerät nie: Der Service Worker sieht nur Dateien der App.
 */
declare const __SKT_PRECACHE__: string[];
declare const __SKT_SW_VERSION__: string;

// Minimale Typen der Service-Worker-Schnittstellen (die DOM-Typen der App enthalten sie nicht).
interface ExtendableEvent extends Event {
  waitUntil(promise: Promise<unknown>): void;
}
interface FetchEvent extends ExtendableEvent {
  readonly request: Request;
  respondWith(response: Response | Promise<Response>): void;
}
interface ServiceWorkerScope {
  readonly registration: ServiceWorkerRegistration;
  readonly clients: { claim(): Promise<void> };
  skipWaiting(): Promise<void>;
  addEventListener(type: "install" | "activate", listener: (event: ExtendableEvent) => void): void;
  addEventListener(type: "fetch", listener: (event: FetchEvent) => void): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
}

const sw = self as unknown as ServiceWorkerScope;
const CACHE_PREFIX = "schafkopf-tracker-";
const CACHE = `${CACHE_PREFIX}${__SKT_SW_VERSION__}`;
const scope = (): string => sw.registration.scope;
const absolute = (file: string): string => new URL(file, scope()).href;
const PRECACHE_URLS = (): Set<string> => new Set(__SKT_PRECACHE__.map(absolute));

sw.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([...PRECACHE_URLS()])));
});

sw.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (name.startsWith(CACHE_PREFIX) && name !== CACHE) await caches.delete(name);
      }
      await sw.clients.claim();
    })(),
  );
});

// Die App bittet um sofortige Übernahme einer neuen Version, sobald der Nutzer „Neu laden“ antippt.
sw.addEventListener("message", (event) => {
  if (event.data === "skip-waiting") void sw.skipWaiting();
});

sw.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || !url.href.startsWith(scope())) return;

  if (request.mode === "navigate") {
    event.respondWith(
      (async () => {
        try {
          const response = await fetch(request);
          if (response.ok) return response;
          return (await caches.match(absolute("index.html"))) ?? response;
        } catch {
          return (await caches.match(absolute("index.html"))) ?? Response.error();
        }
      })(),
    );
    return;
  }

  url.search = "";
  if (PRECACHE_URLS().has(url.href)) {
    event.respondWith(caches.match(url.href).then((cached) => cached ?? fetch(request)));
  }
});
