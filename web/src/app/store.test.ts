import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import type { AppSettings, Evening, Player } from "../core/model";
import { IndexedDbRepository, InMemoryRepository, openDatabase, type StorageCodec } from "../data/repository";
import { game, testEvening } from "../ui/testing";
import { AppStore, sortEvenings, type Clock } from "./store";

/** Einfache Umwandlung für Tests (Maps als Paarlisten). Im Browser steckt das Dateiformat der Android-App dahinter. */
const replacer = (_key: string, value: unknown) => (value instanceof Map ? { __map: [...value] } : value);
const reviver = (_key: string, value: unknown) =>
  value !== null && typeof value === "object" && "__map" in value ? new Map((value as { __map: [unknown, unknown][] }).__map) : value;
const testCodec: StorageCodec = {
  encodeEvening: (evening) => JSON.stringify(evening, replacer),
  decodeEvening: (text) => {
    const value = JSON.parse(text, reviver) as Evening;
    if (typeof value.id !== "string") throw new Error("kein Abend");
    return value;
  },
  encodePlayers: (players) => JSON.stringify(players),
  decodePlayers: (text) => JSON.parse(text) as Player[],
  encodeSettings: (settings) => JSON.stringify(settings),
  decodeSettings: (text) => JSON.parse(text) as AppSettings,
};

function clock(): Clock {
  let next = 0;
  return { now: () => "2026-11-15T19:00:00Z", newId: () => `neu-${++next}` };
}

describe("AppStore", () => {
  it("legt beim ersten Start die feste Runde an und speichert sie", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    expect(store.getState().players.map((it) => it.name)).toEqual(["Spieler 1", "Spieler 2", "Spieler 3", "Spieler 4"]);
    expect(store.getState().settings.themeMode).toBe("DARK");
    expect(store.getState().settings.keepScreenOn).toBe(true);
    expect(repository.players).toBeDefined();
  });

  it("speichert Abende, sortiert den neuesten nach vorn und lädt alles wieder", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    const older = testEvening([game("g1")], { id: "alt", startedAt: "2026-11-01T19:00:00Z" });
    const newer = testEvening([], { id: "neu", startedAt: "2026-11-14T19:00:00Z" });
    await store.saveEvening(older);
    await store.saveEvening(newer);
    expect(store.getState().evenings.map((it) => it.id)).toEqual(["neu", "alt"]);

    await store.updateEvening("alt", (it) => ({ ...it, finishedAt: "2026-11-01T23:00:00Z" }));
    await store.deleteEvening("neu");
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().evenings).toEqual([{ ...older, finishedAt: "2026-11-01T23:00:00Z" }]);
  });

  it("legt Spieler an und ändert sie, mit denselben Namensregeln wie Android", async () => {
    const store = await AppStore.load(new InMemoryRepository(testCodec), clock());
    expect(store.addPlayer("   ")).toBeNull();
    const gast = store.addPlayer("  Gast   Eins ");
    expect(gast).toEqual({ id: "neu-1", name: "Gast Eins", colorIndex: 4, archived: false });
    expect(store.updatePlayer("neu-1", "x".repeat(25), false)).toBe(false);
    expect(store.updatePlayer("neu-1", "Gast", true)).toBe(true);
    expect(store.getState().players[4]).toEqual({ id: "neu-1", name: "Gast", colorIndex: 4, archived: true });
    expect(store.playerName("neu-1")).toBe("Gast");
    expect(store.playerName("unbekannt")).toBe("?");
  });

  it("meldet Speicherfehler und hebt die Meldung auf, sobald wieder gespeichert wird", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    repository.failWrites = true;
    await store.saveEvening(testEvening());
    expect(store.getState().saveError).toContain("Speichern fehlgeschlagen");
    // Auf dem Bildschirm ist der Abend trotzdem da.
    expect(store.getState().evenings).toHaveLength(1);
    repository.failWrites = false;
    await store.updateSettings((it) => ({ ...it, keepScreenOn: false }));
    expect(store.getState().saveError).toBeNull();
  });

  it("übernimmt eine Sicherung: ersetzt gleiche Kennungen, behält den Rest", async () => {
    const store = await AppStore.load(new InMemoryRepository(testCodec), clock());
    await store.saveEvening(testEvening([], { id: "da" }));
    const renamed: Player = { id: "spieler-1", name: "Anna", colorIndex: 0, archived: false };
    const gast: Player = { id: "gast", name: "Gast", colorIndex: 4, archived: false };
    await store.importData([renamed, gast], [testEvening([], { id: "neu", startedAt: "2026-11-20T19:00:00Z" })]);
    const state = store.getState();
    expect(state.players.map((it) => it.name)).toEqual(["Anna", "Spieler 2", "Spieler 3", "Spieler 4", "Gast"]);
    expect(state.evenings.map((it) => it.id)).toEqual(["neu", "da"]);
  });

  it("meldet, solange gespeichert wird", async () => {
    const store = await AppStore.load(new InMemoryRepository(testCodec), clock());
    const events: boolean[] = [];
    store.setSavingListener((saving) => events.push(saving));
    const first = store.saveEvening(testEvening());
    const second = store.updateSettings((it) => ({ ...it, keepScreenOn: false }));
    await Promise.all([first, second]);
    expect(events).toEqual([false, true, false]);
  });

  it("benachrichtigt Beobachter bei jeder Änderung", async () => {
    const store = await AppStore.load(new InMemoryRepository(testCodec), clock());
    let calls = 0;
    const unsubscribe = store.subscribe(() => calls++);
    await store.saveEvening(testEvening());
    unsubscribe();
    await store.deleteEvening("abend-1");
    expect(calls).toBe(1);
  });

  it("sortiert bei gleichem Beginn nach Kennung", () => {
    const a = testEvening([], { id: "b" });
    const b = testEvening([], { id: "a" });
    expect(sortEvenings([a, b]).map((it) => it.id)).toEqual(["a", "b"]);
  });
});

describe("IndexedDbRepository", () => {
  it("speichert und lädt über IndexedDB und lässt unlesbare Abende liegen", async () => {
    const db = await openDatabase(`test-${Math.random()}`);
    const repository = new IndexedDbRepository(db, testCodec);
    const store = await AppStore.load(repository, clock());
    await store.saveEvening(testEvening([game("g1")]));
    await db.put("evenings", "{kaputt", "kaputt");
    const loaded = await repository.load();
    expect(loaded.evenings).toEqual([testEvening([game("g1")])]);
    expect(loaded.unreadableEvenings).toEqual(["kaputt"]);
    expect(loaded.players?.map((it) => it.id)).toEqual(["spieler-1", "spieler-2", "spieler-3", "spieler-4"]);

    await repository.saveImport([{ id: "gast", name: "Gast", colorIndex: 4, archived: false }], [testEvening([], { id: "zwei" })]);
    const again = await repository.load();
    expect(again.evenings.map((it) => it.id).sort()).toEqual(["abend-1", "zwei"]);
    expect(again.players?.map((it) => it.id)).toEqual(["gast"]);
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().unreadableEvenings).toBe(1);
    db.close();
  });
});
