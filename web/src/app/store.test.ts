import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import type { AppSettings, Evening, Player } from "../core/model";
import { IndexedDbRepository, InMemoryRepository, openDatabase, type StorageCodec } from "../data/repository";
import { game, testEvening } from "../ui/testing";
import { AppStore, sortEvenings, StorageFailedError, type Clock } from "./store";

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

  it("löscht nur Spieler, die in keinem Abend vorkommen, und das dauerhaft", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    const gast = store.addPlayer("Gast");
    await store.saveEvening(testEvening());
    expect(store.deletePlayer("spieler-1")).toBe(false);
    expect(store.deletePlayer("gibt-es-nicht")).toBe(false);
    expect(gast).not.toBeNull();
    expect(store.deletePlayer(gast?.id ?? "")).toBe(true);
    await store.flush();
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().players.map((it) => it.id)).toEqual(["spieler-1", "spieler-2", "spieler-3", "spieler-4"]);
    // Die Farbe des gelöschten Gasts ist wieder frei.
    expect(reloaded.addPlayer("Neu")?.colorIndex).toBe(4);
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

describe("AppStore für den Abgleich der gemeinsamen Runde", () => {
  it("mit verbundener Runde werden keine Platzhalter angelegt", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock(), { seedPlayers: false });
    expect(store.getState().players).toEqual([]);
    expect(repository.players).toBeUndefined();
    // Vorhandene Spieler bleiben natürlich.
    await store.putPlayer({ id: "gast", name: "Gast", colorIndex: 0, archived: false });
    const again = await AppStore.load(repository, clock(), { seedPlayers: false });
    expect(again.getState().players.map((it) => it.id)).toEqual(["gast"]);
  });

  it("ersetzt einen Abend in einem Zug: anlegen, ändern, unverändert lassen, löschen", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    const seen: Array<string | null> = [];

    const created = await store.replaceEvening("abend-1", (current) => {
      seen.push(current?.id ?? null);
      return testEvening();
    });
    expect(created).toEqual(testEvening());
    expect(repository.evenings.has("abend-1")).toBe(true);

    const changed = await store.replaceEvening("abend-1", (current) => ({ ...(current ?? testEvening()), location: "Wirtshaus" }));
    expect(changed?.location).toBe("Wirtshaus");
    expect(store.evening("abend-1")?.location).toBe("Wirtshaus");

    // Inhaltlich gleich: nichts wird geschrieben, niemand benachrichtigt.
    let calls = 0;
    store.subscribe(() => calls++);
    const before = repository.evenings.get("abend-1");
    repository.failWrites = true;
    const same = await store.replaceEvening("abend-1", (current) => ({ ...(current ?? testEvening()) }));
    expect(same?.location).toBe("Wirtshaus");
    expect(calls).toBe(0);
    expect(repository.evenings.get("abend-1")).toBe(before);
    repository.failWrites = false;

    expect(await store.replaceEvening("abend-1", () => null)).toBeNull();
    expect(store.evening("abend-1")).toBeUndefined();
    expect(repository.evenings.has("abend-1")).toBe(false);
    expect(await store.replaceEvening("gibt-es-nicht", () => null)).toBeNull();
    expect(seen).toEqual([null]);
  });

  it("eine Eingabe am Tisch und das Zusammenführen überschreiben einander nicht", async () => {
    const store = await AppStore.load(new InMemoryRepository(testCodec), clock());
    await store.saveEvening(testEvening([game("g1")]));

    // Beides wird angestoßen, bevor irgendetwas gespeichert ist.
    const typed = store.updateEvening("abend-1", (it) => ({ ...it, entries: [...it.entries, game("getippt")] }));
    const merged = store.replaceEvening("abend-1", (current) => ({
      ...(current ?? testEvening()),
      entries: [...(current?.entries ?? []), game("vom-server")],
    }));
    const typedAgain = store.updateEvening("abend-1", (it) => ({ ...it, entries: [...it.entries, game("noch-eins")] }));
    await Promise.all([typed, merged, typedAgain]);

    expect(store.evening("abend-1")?.entries.map((it) => it.id)).toEqual(["g1", "getippt", "vom-server", "noch-eins"]);
  });

  it("die Aufrufe des Abgleichs scheitern, wenn sich nicht speichern lässt", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    repository.failWrites = true;

    await expect(store.putEvening(testEvening())).rejects.toBeInstanceOf(StorageFailedError);
    await expect(store.replaceEvening("abend-2", () => testEvening([], { id: "abend-2" }))).rejects.toBeInstanceOf(StorageFailedError);
    await expect(store.putPlayer({ id: "gast", name: "Gast", colorIndex: 4, archived: false })).rejects.toBeInstanceOf(StorageFailedError);
    await expect(store.removePlayer("spieler-1")).rejects.toBeInstanceOf(StorageFailedError);
    await expect(store.putSettings((it) => ({ ...it, keepScreenOn: false }))).rejects.toBeInstanceOf(StorageFailedError);
    expect(store.getState().saveError).toContain("Speichern fehlgeschlagen");
    // Was es nicht gibt, muss auch nicht gespeichert werden.
    await store.removeEvening("gibt-es-nicht");
    await store.removePlayer("gibt-es-nicht");
  });

  it("legt Spieler an ihrer Stelle ab und entfernt sie ohne Rückfrage", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());

    await store.putPlayer({ id: "spieler-2", name: "  Ost  ", colorIndex: 9, archived: true });
    await store.putPlayer({ id: "gast", name: "Gast", colorIndex: 4, archived: false });
    await store.removePlayer("spieler-3");

    expect(store.getState().players).toEqual([
      { id: "spieler-1", name: "Spieler 1", colorIndex: 0, archived: false },
      // Der Name kommt vom Server und wird nicht noch einmal bereinigt.
      { id: "spieler-2", name: "  Ost  ", colorIndex: 9, archived: true },
      { id: "spieler-4", name: "Spieler 4", colorIndex: 3, archived: false },
      { id: "gast", name: "Gast", colorIndex: 4, archived: false },
    ]);
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().players).toEqual(store.getState().players);
  });

  it("holt nach, was sich zuletzt nicht speichern ließ", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    await store.saveEvening(testEvening([], { id: "bleibt" }));
    await store.saveEvening(testEvening([], { id: "geht" }));
    repository.failWrites = true;
    await store.saveEvening(testEvening([game("g1")], { id: "neu" }));
    await store.updateEvening("bleibt", (it) => ({ ...it, location: "Wirtshaus" }));
    await store.deleteEvening("geht");
    store.updatePlayer("spieler-1", "Nord", false);
    await store.updateSettings((it) => ({ ...it, keepScreenOn: false }));
    await store.flush();

    // Solange der Speicher gestört ist, scheitert auch das Nachholen.
    await expect(store.settle()).rejects.toBeInstanceOf(StorageFailedError);

    repository.failWrites = false;
    await store.settle();

    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().evenings).toEqual(store.getState().evenings);
    expect(reloaded.getState().evenings.map((it) => it.id).sort()).toEqual(["bleibt", "neu"]);
    expect(reloaded.evening("bleibt")?.location).toBe("Wirtshaus");
    expect(reloaded.getState().players[0].name).toBe("Nord");
    expect(reloaded.getState().settings.keepScreenOn).toBe(false);
    expect(store.getState().saveError).toBeNull();

    // Ist alles gespeichert, schreibt das Nachholen nichts.
    repository.failWrites = true;
    await store.settle();
  });

  it("gelingt ein späterer Auftrag für dasselbe Ziel, ist nichts nachzuholen", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    repository.failWrites = true;
    await store.saveEvening(testEvening());
    repository.failWrites = false;
    await store.updateEvening("abend-1", (it) => ({ ...it, location: "Wirtshaus" }));

    repository.failWrites = true;
    await store.settle();
    expect((await AppStore.load(repository, clock())).evening("abend-1")?.location).toBe("Wirtshaus");
  });

  it("auch eine nicht gespeicherte Sicherung wird nachgeholt", async () => {
    const repository = new InMemoryRepository(testCodec);
    const store = await AppStore.load(repository, clock());
    repository.failWrites = true;
    await store.importData([{ id: "gast", name: "Gast", colorIndex: 4, archived: false }], [testEvening([], { id: "aus-sicherung" })]);
    repository.failWrites = false;

    await store.settle();

    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().players.map((it) => it.id)).toContain("gast");
    expect(reloaded.getState().evenings.map((it) => it.id)).toEqual(["aus-sicherung"]);
  });

  it("liest neu aus dem Speicher, wenn ein anderes Fenster ihn geändert hat", async () => {
    const repository = new InMemoryRepository(testCodec);
    const here = await AppStore.load(repository, clock());
    const elsewhere = await AppStore.load(repository, clock());
    await elsewhere.saveEvening(testEvening([game("g1")]));
    elsewhere.updatePlayer("spieler-1", "Nord", false);
    await elsewhere.updateSettings((it) => ({ ...it, themeMode: "LIGHT" }));
    await elsewhere.flush();
    let calls = 0;
    here.subscribe(() => calls++);

    await here.reload();

    expect(here.getState().evenings).toEqual([testEvening([game("g1")])]);
    expect(here.getState().players[0].name).toBe("Nord");
    expect(here.getState().settings.themeMode).toBe("LIGHT");
    expect(calls).toBe(1);
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
