/*
 * Die Datenbank der Web-App mit den Ablagen der gemeinsamen Runde: der Übergang von Version 1 (vorhandene Daten
 * bleiben), und was sich das Gerät zum Abgleich merkt.
 */
import "fake-indexeddb/auto";
import { openDB } from "idb";
import { describe, expect, it } from "vitest";
import { roundStoreCodec, storageCodec } from "../app/formatAdapters";
import { AppStore } from "../app/store";
import type { Player } from "../core/model";
import { remotePlayer, roundLink, type RoundLink } from "../core/round/RoundModels";
import { evening, game, P1, P2, PLACEHOLDERS } from "../core/round/testing/fixtures";
import { RuleSets } from "../core/rules/RuleSet";
import { DATABASE_VERSION, IndexedDbRepository, openDatabase } from "../data/repository";
import { IndexedDbRoundStore } from "./indexedDbRoundStore";

let counter = 0;
function freshName(): string {
  return `runde-test-${++counter}-${Math.random()}`;
}

/** Legt eine Datenbank an, wie sie die Web-App vor der gemeinsamen Runde hatte (Version 1), mit Daten darin. */
async function databaseOfVersion1(name: string, players: readonly Player[]): Promise<void> {
  const db = await openDB(name, 1, {
    upgrade(database) {
      database.createObjectStore("evenings");
      database.createObjectStore("documents");
    },
  });
  await db.put("evenings", storageCodec.encodeEvening(evening([game("g1", 60)], "abend-1")), "abend-1");
  await db.put("evenings", storageCodec.encodeEvening(evening([], "abend-2")), "abend-2");
  await db.put("evenings", "{kaputt", "kaputt");
  await db.put("documents", storageCodec.encodePlayers(players), "players");
  await db.put("documents", storageCodec.encodeSettings({ defaultRules: RuleSets.sauspiel(), keepScreenOn: false, themeMode: "LIGHT" }), "settings");
  db.close();
}

const LINK: RoundLink = roundLink({
  roundId: "runde-1",
  roundName: "Stammtisch",
  userId: "konto-1",
  playerId: P1,
  isCreator: true,
  cursor: 17,
  rulesBase: RuleSets.standard(),
  members: [
    { userId: "konto-1", playerId: P1 },
    { userId: "konto-2", playerId: null },
  ],
  unreadable: ["abend-von-morgen", "noch-einer"],
});

describe("Datenbank Version 2", () => {
  it("der Übergang von Version 1 behält Spieler, Abende und Einstellungen und legt die neuen Ablagen leer an", async () => {
    const name = freshName();
    const players: Player[] = [
      { id: P1, name: "Nord", colorIndex: 0, archived: false },
      { id: "gast", name: "Gast", colorIndex: 4, archived: true },
    ];
    await databaseOfVersion1(name, players);

    const db = await openDatabase(name);

    expect(DATABASE_VERSION).toBe(2);
    expect(db.version).toBe(2);
    expect([...db.objectStoreNames].sort()).toEqual(["documents", "eveningBases", "evenings", "playerBases", "round"]);
    const loaded = await new IndexedDbRepository(db, storageCodec).load();
    expect(loaded.players).toEqual(players);
    expect(loaded.evenings.map((it) => it.id).sort()).toEqual(["abend-1", "abend-2"]);
    expect(loaded.evenings.find((it) => it.id === "abend-1")).toEqual(evening([game("g1", 60)], "abend-1"));
    expect(loaded.settings).toEqual({ defaultRules: RuleSets.sauspiel(), keepScreenOn: false, themeMode: "LIGHT" });
    // Auch was sich nicht lesen ließ, liegt noch da.
    expect(loaded.unreadableEvenings).toEqual(["kaputt"]);
    expect(await db.get("evenings", "kaputt")).toBe("{kaputt");
    const round = new IndexedDbRoundStore(db, roundStoreCodec);
    expect(await round.link()).toBeNull();
    expect(await round.eveningBases()).toEqual(new Map());
    expect(await round.playerBases()).toEqual(new Map());
    db.close();
  });

  it("nach dem Übergang startet die App mit den alten Daten und legt keine Platzhalter an", async () => {
    const name = freshName();
    await databaseOfVersion1(name, [{ id: "gast", name: "Gast", colorIndex: 4, archived: false }]);
    const db = await openDatabase(name);

    const store = await AppStore.load(new IndexedDbRepository(db, storageCodec));

    expect(store.getState().players.map((it) => it.name)).toEqual(["Gast"]);
    expect(store.getState().evenings.map((it) => it.id).sort()).toEqual(["abend-1", "abend-2"]);
    expect(store.getState().unreadableEvenings).toEqual(["kaputt"]);
    db.close();
  });

  it("eine neue Datenbank hat gleich alle Ablagen", async () => {
    const db = await openDatabase(freshName());
    expect([...db.objectStoreNames].sort()).toEqual(["documents", "eveningBases", "evenings", "playerBases", "round"]);
    db.close();
  });

  it("ist die App noch in einem anderen Fenster mit der alten Version offen, wartet das Öffnen und meldet es", async () => {
    const name = freshName();
    await databaseOfVersion1(name, []);
    // Das alte Fenster: Version 1, ohne Rücksicht auf neuere Versionen.
    const old = await openDB(name, 1);
    let blocked = 0;
    let opened = false;

    const opening = openDatabase(name, { blocked: () => blocked++ }).then((db) => {
      opened = true;
      return db;
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(blocked).toBe(1);
    expect(opened).toBe(false);

    old.close();
    const db = await opening;
    expect(db.version).toBe(2);
    db.close();
  });

  it("will eine neuere Version der App die Datenbank heben, gibt diese Verbindung sie frei und meldet es", async () => {
    const name = freshName();
    let outdated = 0;
    const db = await openDatabase(name, { outdated: () => outdated++ });

    const newer = await openDB(name, DATABASE_VERSION + 1);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(outdated).toBe(1);
    await expect(db.get("documents", "players")).rejects.toThrow();
    newer.close();
  });
});

describe("Was sich das Gerät zur Runde merkt", () => {
  it("die verbundene Runde übersteht das Schließen der App", async () => {
    const name = freshName();
    const db = await openDatabase(name);
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    expect(await store.link()).toBeNull();

    await store.setLink(LINK);
    expect(await store.link()).toEqual(LINK);
    db.close();

    const again = await openDatabase(name);
    const reopened = new IndexedDbRoundStore(again, roundStoreCodec);
    expect(await reopened.link()).toEqual(LINK);

    await reopened.setLink(null);
    expect(await reopened.link()).toBeNull();
    expect(await again.get("round", "link")).toBeUndefined();
    again.close();
  });

  it("eine Runde ohne Regelwerk und ohne eigenen Spieler", async () => {
    const db = await openDatabase(freshName());
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    const plain = roundLink({ roundId: "runde-2", roundName: "Neu", userId: "konto-2" });

    await store.setLink(plain);

    expect(await new IndexedDbRoundStore(db, roundStoreCodec).link()).toEqual(plain);
    db.close();
  });

  it("die Basen der Abende und Spieler überstehen das Schließen der App", async () => {
    const name = freshName();
    const db = await openDatabase(name);
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    const first = evening([game("g1", 60, { laufende: 3 })], "abend-1");
    const second = evening([], "abend-2");
    const nord = { ...remotePlayer({ id: P1, name: "Nord", colorIndex: 0, archived: false }), position: 1, owner: "konto-1", nameLocked: true, rev: 5 };
    const ost = { ...remotePlayer({ id: P2, name: "Ost", colorIndex: 1, archived: true }), position: 2, rev: 6 };

    await store.setEveningBase("abend-1", { version: 3, evening: first });
    await store.setEveningBase("abend-2", { version: 1, evening: second });
    await store.setPlayerBase(P1, nord);
    await store.setPlayerBase(P2, ost);
    db.close();

    const again = await openDatabase(name);
    const reopened = new IndexedDbRoundStore(again, roundStoreCodec);
    expect(await reopened.eveningBases()).toEqual(
      new Map([
        ["abend-1", { version: 3, evening: first }],
        ["abend-2", { version: 1, evening: second }],
      ]),
    );
    expect(await reopened.playerBases()).toEqual(
      new Map([
        [P1, nord],
        [P2, ost],
      ]),
    );

    await reopened.setEveningBase("abend-1", null);
    await reopened.setPlayerBase(P2, null);
    expect([...(await reopened.eveningBases()).keys()]).toEqual(["abend-2"]);
    expect([...(await reopened.playerBases()).keys()]).toEqual([P1]);
    expect(await again.getAllKeys("eveningBases")).toEqual(["abend-2"]);
    expect(await again.getAllKeys("playerBases")).toEqual([P1]);

    await reopened.clearBases();
    expect(await reopened.eveningBases()).toEqual(new Map());
    expect(await reopened.playerBases()).toEqual(new Map());
    expect(await again.getAllKeys("eveningBases")).toEqual([]);
    expect(await again.getAllKeys("playerBases")).toEqual([]);
    again.close();
  });

  it("was sich nicht lesen lässt, gilt als nicht vorhanden", async () => {
    const db = await openDatabase(freshName());
    await db.put("round", "{kaputt", "link");
    await db.put("eveningBases", "{kaputt", "abend-1");
    await db.put("eveningBases", JSON.stringify({ version: 2, evening: "{auch kaputt" }), "abend-2");
    // Die Kennung im Inhalt passt nicht zum Schlüssel.
    await db.put("eveningBases", JSON.stringify({ version: 2, evening: roundStoreCodec.encodeEvening(evening([], "anders")) }), "abend-3");
    await db.put("eveningBases", JSON.stringify({ version: "zwei", evening: roundStoreCodec.encodeEvening(evening([], "abend-4")) }), "abend-4");
    await db.put("eveningBases", JSON.stringify({ version: 2, evening: roundStoreCodec.encodeEvening(evening([], "abend-5")) }), "abend-5");
    await db.put("playerBases", "[]", P1);
    await db.put("playerBases", JSON.stringify({ id: P2, name: "Ost" }), P2);
    const store = new IndexedDbRoundStore(db, roundStoreCodec);

    expect(await store.link()).toBeNull();
    expect([...(await store.eveningBases()).keys()]).toEqual(["abend-5"]);
    expect(await store.playerBases()).toEqual(new Map());
    db.close();
  });

  it("ein Stand von vor dem 20.11.2026 kennt keine unlesbaren Abende; Doppeltes und Fremdes darin zählt nicht", async () => {
    const db = await openDatabase(freshName());
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    await store.setLink(LINK);
    const { unreadable: _, ...older } = JSON.parse((await db.get("round", "link")) ?? "{}") as Record<string, unknown>;
    await db.put("round", JSON.stringify(older), "link");

    expect(await new IndexedDbRoundStore(db, roundStoreCodec).link()).toEqual({ ...LINK, unreadable: [] });

    await db.put("round", JSON.stringify({ ...older, unreadable: ["abend-1", 7, "abend-1", null, "abend-2"] }), "link");
    expect((await new IndexedDbRoundStore(db, roundStoreCodec).link())?.unreadable).toEqual(["abend-1", "abend-2"]);
    await db.put("round", JSON.stringify({ ...older, unreadable: "abend-1" }), "link");
    expect((await new IndexedDbRoundStore(db, roundStoreCodec).link())?.unreadable).toEqual([]);
    db.close();
  });

  it("ein unlesbares gemerktes Regelwerk gilt als unbekannt; die Verbindung bleibt", async () => {
    const db = await openDatabase(freshName());
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    await store.setLink(LINK);
    const stored = JSON.parse((await db.get("round", "link")) ?? "{}") as Record<string, unknown>;
    await db.put("round", JSON.stringify({ ...stored, rulesBase: "{kaputt" }), "link");

    expect(await new IndexedDbRoundStore(db, roundStoreCodec).link()).toEqual({ ...LINK, rulesBase: null });
    db.close();
  });

  it("meldet Änderungen an der Verbindung, aber nicht jede einzelne Basis", async () => {
    const db = await openDatabase(freshName());
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    let calls = 0;
    const unsubscribe = store.subscribe(() => calls++);

    await store.setLink(LINK);
    expect(calls).toBe(1);
    await store.setEveningBase("abend-1", { version: 1, evening: evening([], "abend-1") });
    await store.setPlayerBase(P1, remotePlayer(PLACEHOLDERS[0]));
    expect(calls).toBe(1);
    await store.clearBases();
    expect(calls).toBe(2);
    unsubscribe();
    await store.setLink(null);
    expect(calls).toBe(2);
    db.close();
  });

  it("hat ein anderes Fenster geschrieben, liest die Ablage nach dem Verwerfen neu", async () => {
    const db = await openDatabase(freshName());
    const here = new IndexedDbRoundStore(db, roundStoreCodec);
    const elsewhere = new IndexedDbRoundStore(db, roundStoreCodec);
    expect(await here.link()).toBeNull();
    await elsewhere.setLink(LINK);
    await elsewhere.setEveningBase("abend-1", { version: 4, evening: evening([], "abend-1") });
    // Noch der alte Stand aus dem Arbeitsspeicher.
    expect(await here.link()).toBeNull();
    let calls = 0;
    here.subscribe(() => calls++);

    here.invalidate();

    expect(calls).toBe(1);
    expect(await here.link()).toEqual(LINK);
    expect((await here.eveningBases()).get("abend-1")?.version).toBe(4);
    db.close();
  });

  it("lässt sich nicht in die Datenbank schreiben, bleibt auch der Arbeitsspeicher beim alten Stand", async () => {
    const db = await openDatabase(freshName());
    const store = new IndexedDbRoundStore(db, roundStoreCodec);
    await store.setLink(LINK);
    await store.setEveningBase("abend-1", { version: 1, evening: evening([], "abend-1") });
    // Gelesen wird erst bei Bedarf; hier ist alles schon einmal gelesen.
    await store.eveningBases();
    await store.playerBases();
    db.close();

    await expect(store.setLink(null)).rejects.toThrow();
    await expect(store.setEveningBase("abend-1", null)).rejects.toThrow();
    await expect(store.setPlayerBase(P1, remotePlayer(PLACEHOLDERS[0]))).rejects.toThrow();

    expect(await store.link()).toEqual(LINK);
    expect([...(await store.eveningBases()).keys()]).toEqual(["abend-1"]);
    expect(await store.playerBases()).toEqual(new Map());
  });
});
