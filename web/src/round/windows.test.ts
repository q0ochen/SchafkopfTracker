/*
 * Mehrere Fenster derselben Web-App (docs/ARCHITEKTUR.md, Abschnitt 8.8): Meldungen zwischen den Fenstern, die Sperre
 * über alle Fenster und das frische Lesen vor jedem Abgleich – mit echter Datenbank (fake-indexeddb), echtem Zustand
 * der App, echter Anbindung und echtem Abgleich, wie `setup.ts` sie zusammensteckt.
 */
import "fake-indexeddb/auto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { roundStoreCodec, storageCodec } from "../app/formatAdapters";
import { AppStore } from "../app/store";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { RoundConnection } from "../core/round/RoundConnection";
import type { RoundId } from "../core/round/RoundModels";
import { RoundSync } from "../core/round/RoundSync";
import { Device, evening, game, HookedApi, ids, PLACEHOLDERS, TestClock } from "../core/round/testing/fixtures";
import { IndexedDbRepository, openDatabase } from "../data/repository";
import { needsSignIn } from "./controller";
import { IndexedDbRoundStore } from "./indexedDbRoundStore";
import { announcingRepository, syncGuard } from "./setup";
import { StoreAdapters } from "./storeAdapters";
import { account, Phone } from "./testing/phone";
import { roundStatus } from "./texts";
import {
  CHANNEL_NAME,
  coalescing,
  createTabChannel,
  LOCK_NAME,
  TAB_STAMP_KEY,
  windowGuard,
  windowLock,
  WindowBusyError,
  type ChannelHost,
  type LockHost,
  type TabChange,
  type TabChannel,
} from "./windows";

/** Eine Sperre wie `navigator.locks`, für alle Fenster eines Tests gemeinsam: der Reihe nach, abbrechbar beim Warten. */
class FakeLocks {
  private readonly tails = new Map<string, Promise<void>>();
  /** Wer gerade die Sperre hält (Name der Sperre). */
  readonly held = new Set<string>();

  request<T>(name: string, options: { signal?: AbortSignal }, callback: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(name) ?? Promise.resolve();
    let release = (): void => undefined;
    const mine = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.tails.set(
      name,
      previous.then(() => mine),
    );
    return new Promise<T>((resolve, reject) => {
      const signal = options.signal;
      const onAbort = (): void => {
        reject(new DOMException("Abgebrochen", "AbortError"));
        void previous.then(release);
      };
      signal?.addEventListener("abort", onAbort, { once: true });
      void previous.then(async () => {
        if (signal?.aborted === true) return;
        signal?.removeEventListener("abort", onAbort);
        this.held.add(name);
        try {
          resolve(await callback());
        } catch (error) {
          reject(error instanceof Error ? error : new Error(String(error)));
        } finally {
          this.held.delete(name);
          release();
        }
      });
    });
  }

  host(): LockHost {
    return { locks: this as unknown as LockManager };
  }
}

/** Lässt angestoßene Arbeit fertig werden (Datenbank im Speicher, Versprechen). */
async function settled(): Promise<void> {
  for (let round = 0; round < 5; round++) await new Promise((resolve) => setTimeout(resolve, 0));
}

let counter = 0;

/** Ein Fenster der App über der Datenbank `name`, zusammengesteckt wie in `setup.ts`. */
async function tab(name: string, server: InMemoryRoundServer, userId: string, locks: FakeLocks | null = null) {
  const db = await openDatabase(name);
  const repository = new IndexedDbRepository(db, storageCodec);
  const roundStore = new IndexedDbRoundStore(db, roundStoreCodec);
  const prefix = `fenster-${++counter}`;
  const store = await AppStore.load(repository, { now: () => "2026-12-12T18:00:00Z", newId: () => `${prefix}-${++counter}` }, {
    seedPlayers: (await roundStore.link()) === null,
  });
  const adapters = new StoreAdapters(store, storageCodec.encodeEvening);
  const session = server.sessionFor(userId);
  const api = new HookedApi(session);
  const shielded = adapters.shield(roundStore);
  const guard = syncGuard(locks?.host() ?? {}, roundStore, store);
  const sync = new RoundSync(api, shielded, adapters.players, adapters.evenings, adapters.settings, adapters.editor, guard);
  const connection = new RoundConnection(api, shielded, adapters.players, adapters.evenings, sync, { newId: () => `${prefix}-id-${++counter}` });
  return { db, store, roundStore, api, session, sync, connection };
}

function onServer(server: InMemoryRoundServer, round: RoundId, id: string): string[] {
  const found = server.evenings(round).find((it) => it.id === id);
  return found === undefined ? [] : ids(found);
}

const channels: TabChannel[] = [];
afterEach(() => {
  for (const channel of channels.splice(0)) channel.close();
  vi.useRealTimers();
});

describe("Meldungen zwischen den Fenstern", () => {
  it("über BroadcastChannel: Jede Meldung kommt an, auch wenn das andere Fenster gleich danach selbst etwas meldet", async () => {
    const host: ChannelHost = { addEventListener: () => undefined, removeEventListener: () => undefined, BroadcastChannel };
    const storage = new Map<string, string>();
    const browser = { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => void storage.set(key, value), removeItem: () => undefined };
    const a = createTabChannel(host, browser, "A");
    const b = createTabChannel(host, browser, "B");
    channels.push(a, b);
    const seenByA: TabChange[] = [];
    const seenByB: TabChange[] = [];
    a.subscribe((change) => seenByA.push(change));
    b.subscribe((change) => seenByB.push(change));

    // A speichert, B speichert kurz danach etwas anderes (beim „letzten Stempel“ verpasste B die Meldung von A).
    a.post("data");
    b.post("round");
    await vi.waitFor(() => {
      expect(seenByB).toEqual(["data"]);
      expect(seenByA).toEqual(["round"]);
    });
    // Ein Fenster bekommt seine eigenen Meldungen nicht, und fremde Nachrichten auf dem Kanal zählen nicht.
    const stranger = new BroadcastChannel(CHANNEL_NAME);
    stranger.postMessage({ change: "alles" });
    stranger.postMessage("kaputt");
    stranger.close();
    a.post("data");
    await vi.waitFor(() => expect(seenByB).toEqual(["data", "data"]));
    expect(seenByA).toEqual(["round"]);
  });

  it("ohne BroadcastChannel über das storage-Ereignis: ebenfalls jede Meldung", () => {
    // Ein nachgestellter Browser: Schreibt ein Fenster in den Speicher, bekommen alle anderen das Ereignis.
    const values = new Map<string, string>();
    const windows: Array<{ listeners: Set<(event: StorageEvent) => void> }> = [];
    const open = (tabId: string) => {
      const own = { listeners: new Set<(event: StorageEvent) => void>() };
      windows.push(own);
      const host: ChannelHost = {
        addEventListener: (_type, listener) => own.listeners.add(listener),
        removeEventListener: (_type, listener) => own.listeners.delete(listener),
      };
      const storage = {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => {
          const old = values.get(key) ?? null;
          values.set(key, value);
          if (old === value) return;
          for (const other of windows) {
            if (other === own) continue;
            for (const listener of other.listeners) listener({ key, newValue: value, oldValue: old } as StorageEvent);
          }
        },
        removeItem: (key: string) => void values.delete(key),
      };
      return createTabChannel(host, storage, tabId);
    };
    const a = open("A");
    const b = open("B");
    const seenByA: TabChange[] = [];
    const seenByB: TabChange[] = [];
    a.subscribe((change) => seenByA.push(change));
    b.subscribe((change) => seenByB.push(change));

    a.post("data");
    b.post("round");
    a.post("data");

    expect(seenByB).toEqual(["data", "data"]);
    expect(seenByA).toEqual(["round"]);
    expect(values.get(TAB_STAMP_KEY)).toContain('"from":"A"');
    // Wird der ganze Speicher geleert, liest jedes Fenster sicherheitshalber alles neu.
    for (const listener of windows[1].listeners) listener({ key: null, newValue: null, oldValue: null } as StorageEvent);
    expect(seenByB).toEqual(["data", "data", "data", "round"]);
    a.close();
    b.post("round");
    expect(seenByA).toEqual(["round"]);
  });

  it("viele Meldungen kurz hintereinander kommen zusammengefasst an, keine geht verloren", () => {
    vi.useFakeTimers();
    const sent: TabChange[] = [];
    const inner: TabChannel = { post: (change) => sent.push(change), subscribe: () => () => undefined, close: () => undefined };
    const channel = coalescing(inner, 100);

    for (let index = 0; index < 50; index++) channel.post("round");
    channel.post("data");
    expect(sent).toEqual([]);
    vi.advanceTimersByTime(100);
    expect(sent).toEqual(["round", "data"]);

    channel.post("data");
    channel.close();
    expect(sent).toEqual(["round", "data", "data"]);
  });
});

describe("Sperre über alle Fenster", () => {
  it("lässt Aufträge der Reihe nach laufen; wartet einer zu lange, gibt er auf", async () => {
    const locks = new FakeLocks();
    const lock = windowLock(locks.host(), 50);
    if (lock === null) throw new Error("keine Sperre");
    let release = (): void => undefined;
    const first = lock(
      () =>
        new Promise<string>((resolve) => {
          release = () => resolve("erster");
        }),
    );
    await settled();
    expect(locks.held).toEqual(new Set([LOCK_NAME]));

    await expect(lock(async () => "zweiter")).rejects.toBeInstanceOf(WindowBusyError);
    const third = lock(async () => "dritter");
    release();
    expect(await first).toBe("erster");
    expect(await third).toBe("dritter");
    // Ein Fehler im Auftrag ist kein „besetzt“.
    await expect(lock(() => Promise.reject(new Error("kaputt")))).rejects.toThrow("kaputt");
  });

  it("wo der Browser keine Sperre kennt, gibt es keine (dann gilt die Sperre je Fenster)", async () => {
    expect(windowLock({})).toBeNull();
    const order: string[] = [];
    const guard = windowGuard(null, async () => void order.push("frisch gelesen"));
    expect(await guard(async () => (order.push("Auftrag"), 7))).toBe(7);
    expect(order).toEqual(["frisch gelesen", "Auftrag"]);
  });

  it("zwei Fenster gleichen nie gleichzeitig ab", async () => {
    const server = new InMemoryRoundServer();
    const locks = new FakeLocks();
    const name = `sperre-${Math.random()}`;
    const a = await tab(name, server, "konto-1", locks);
    await a.store.saveEvening(evening([game("g1", 10)], "abend"));
    expect((await a.connection.create("Stammtisch", "konto-1", PLACEHOLDERS)).kind).toBe("Synced");
    const b = await tab(name, server, "konto-1", locks);

    // A lädt ein neues Spiel hoch und hängt dabei (langsames Netz).
    await a.store.updateEvening("abend", (it) => ({ ...it, entries: [...it.entries, game("g2", 20)] }));
    let answer = (): void => undefined;
    let uploading = false;
    a.api.beforeSave = () =>
      new Promise<void>((resolve) => {
        uploading = true;
        answer = resolve;
      });
    const syncA = a.sync.sync();
    await vi.waitFor(() => expect(uploading).toBe(true));

    // B will abgleichen, wartet aber, bis A fertig ist: B fragt den Server nicht einmal.
    let bDone = false;
    const syncB = b.sync.sync().finally(() => {
      bDone = true;
    });
    await settled();
    expect(b.session.calls).toEqual([]);
    expect(bDone).toBe(false);

    answer();
    expect((await syncA).kind).toBe("Synced");
    expect((await syncB).kind).toBe("Synced");
    expect(ids(b.store.evening("abend") ?? evening())).toEqual(["g1", "g2"]);
    a.db.close();
    b.db.close();
  });
});

describe("vor jedem Abgleich frisch gelesen", () => {
  /** Zwei Fenster mit einer Runde „Stammtisch“ (Abend mit g1) und ein zweites Handy in derselben Runde. */
  async function twoWindows() {
    const server = new InMemoryRoundServer();
    const name = `zwei-fenster-${Math.random()}`;
    const a = await tab(name, server, "konto-1");
    await a.store.saveEvening(evening([game("g1", 10)], "abend"));
    expect((await a.connection.create("Stammtisch", "konto-1", PLACEHOLDERS)).kind).toBe("Synced");
    const round = (await a.roundStore.link())?.roundId ?? "";
    const other = new Device(server, "konto-2", new TestClock());
    await other.firstStart();
    expect((await other.connection.join(await server.sessionFor("konto-1").createInvite(round), "konto-2")).kind).toBe("Synced");
    const b = await tab(name, server, "konto-1");
    expect((await b.roundStore.eveningBases()).get("abend")?.version).toBe(1);
    return { server, round, a, b, other };
  }

  it("ein Fenster mit veralteter Basis macht das Löschen eines anderen Handys nicht rückgängig", async () => {
    const { server, round, a, b, other } = await twoWindows();

    // In A wird g2 eingetragen; B liest daraufhin neu (wie nach einer Meldung) und hat g2.
    await a.store.updateEvening("abend", (it) => ({ ...it, entries: [...it.entries, game("g2", 20)] }));
    await b.sync.exclusive(() => b.store.catchUp());
    expect(ids(b.store.evening("abend") ?? evening())).toEqual(["g1", "g2"]);

    // A gleicht ab (g2 geht hoch, Version 2). Davon erfährt B nichts – keine Meldung kommt an.
    expect((await a.sync.sync()).kind).toBe("Synced");
    expect(server.eveningRow(round, "abend")?.version).toBe(2);

    // Das andere Handy holt g2 und nimmt es zurück.
    await other.synced();
    const there = (await other.evening("abend")) ?? evening();
    await other.evenings.save({ ...there, entries: there.entries.filter((it) => it.id !== "g2") });
    await other.synced();
    expect(onServer(server, round, "abend")).toEqual(["g1"]);

    // B gleicht ab: Es liest vorher frisch, das Zurücknehmen gilt.
    expect((await b.sync.sync()).kind).toBe("Synced");
    expect(onServer(server, round, "abend")).toEqual(["g1"]);
    expect(ids(b.store.evening("abend") ?? evening())).toEqual(["g1"]);
    a.db.close();
    b.db.close();
  });

  it("ein Fenster, das die Eingabe eines anderen nie gemeldet bekam, löscht sie nicht", async () => {
    const { server, round, a, b } = await twoWindows();

    // A trägt g2 ein und gleicht ab; B bekommt keine einzige Meldung.
    await a.store.updateEvening("abend", (it) => ({ ...it, entries: [...it.entries, game("g2", 20)] }));
    expect((await a.sync.sync()).kind).toBe("Synced");
    expect(ids(b.store.evening("abend") ?? evening())).toEqual(["g1"]);

    // B gleicht ab: Die Basis kennt g2 schon, der Abend in B noch nicht. Ohne frisches Lesen sähe das aus wie
    // „hier gelöscht“.
    expect((await b.sync.sync()).kind).toBe("Synced");
    expect(onServer(server, round, "abend")).toEqual(["g1", "g2"]);
    expect(ids(b.store.evening("abend") ?? evening())).toEqual(["g1", "g2"]);
    a.db.close();
    b.db.close();
  });

  it("speichern zwei Fenster kurz nacheinander, erfährt jedes vom anderen", async () => {
    const server = new InMemoryRoundServer();
    const name = `nacheinander-${Math.random()}`;
    const a = await tab(name, server, "konto-1");
    const b = await tab(name, server, "konto-1");

    await a.store.saveEvening(evening([game("g1", 10)], "abend"));
    b.store.addPlayer("Gast");
    await b.store.flush();

    // B hat selbst gespeichert, nachdem A gespeichert hatte – und erfährt trotzdem, dass A etwas hatte.
    expect(await b.store.catchUp()).toBe(true);
    expect(b.store.evening("abend")).toBeDefined();
    expect(await a.store.catchUp()).toBe(true);
    expect(a.store.getState().players.map((it) => it.name)).toContain("Gast");
    // Danach ist alles auf dem Stand; neu gelesen wird erst wieder, wenn ein anderes Fenster speichert.
    expect(await a.store.catchUp()).toBe(false);
    expect(await b.store.catchUp()).toBe(false);
    a.db.close();
    b.db.close();
  });

  it("Schreiben meldet sich bei den anderen Fenstern: Daten über den Speicher der App, die Runde über ihre Ablage", async () => {
    const name = `meldet-${Math.random()}`;
    const db = await openDatabase(name);
    const posted: TabChange[] = [];
    const repository = announcingRepository(new IndexedDbRepository(db, storageCodec), () => posted.push("data"));
    const roundStore = new IndexedDbRoundStore(db, roundStoreCodec, { onWrite: () => posted.push("round") });
    const store = await AppStore.load(repository);
    await store.flush();
    expect(posted).toEqual(["data"]);

    await store.saveEvening(evening([], "abend"));
    await roundStore.setEveningBase("abend", { version: 1, evening: evening([], "abend") });
    await roundStore.clearBases();
    expect(posted).toEqual(["data", "data", "round", "round"]);
    db.close();
  });
});

describe("Steuerung mit Sperre", () => {
  it("gleicht gerade ein anderes Fenster ab, fällt dieser Versuch aus; die Statuszeile bleibt, und später geht es weiter", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-12-12T19:00:00Z"));
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const server = new InMemoryRoundServer(() => Date.now());
    let busy = true;
    const guard = windowGuard(
      async (block) => {
        if (busy) throw new WindowBusyError();
        return block();
      },
      async () => undefined,
    );
    const one = await Phone.create(server, "eins", account(1), { guard });
    await one.startEvening("abend-1");
    busy = false;
    one.controller.start(true);
    await vi.advanceTimersByTimeAsync(0);
    expect((await one.controller.create("Stammtisch")).kind).toBe("Synced");
    await vi.advanceTimersByTimeAsync(0);

    busy = true;
    await one.addGame("abend-1");
    await vi.advanceTimersByTimeAsync(500);
    const state = one.controller.getState();
    expect(state.problem).toBeNull();
    expect(roundStatus({ ...state, needsSignIn: needsSignIn(state) }).text).toBe("1 Änderung wartet");

    busy = false;
    await vi.advanceTimersByTimeAsync(4000);
    const after = one.controller.getState();
    expect(roundStatus({ ...after, needsSignIn: needsSignIn(after) }).text).toBe("Abgeglichen");
    expect(server.evenings(after.link?.roundId ?? "")[0].entries).toHaveLength(1);
    one.controller.stop();
  });
});
