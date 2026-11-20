/* Die Anbindung des Abgleichs an den Zustand der Web-App: lesen, schreiben, Änderungen melden. */
import { describe, expect, it } from "vitest";
import { storageCodec } from "../app/formatAdapters";
import { AppStore, StorageFailedError, UnreadableDataError, type Clock } from "../app/store";
import { InMemoryRoundStore } from "../core/round/InMemoryRound";
import { EveningNotStorableError } from "../core/round/RoundPorts";
import { RuleSets } from "../core/rules/RuleSet";
import { InMemoryRepository } from "../data/repository";
import { game, testEvening } from "../ui/testing";
import { StoreAdapters } from "./storeAdapters";

function clock(): Clock {
  let next = 0;
  return { now: () => "2026-12-12T19:00:00Z", newId: () => `neu-${++next}` };
}

async function setup() {
  const repository = new InMemoryRepository(storageCodec);
  const store = await AppStore.load(repository, clock());
  const adapters = new StoreAdapters(store, storageCodec.encodeEvening);
  let changes = 0;
  adapters.onLocalChange(() => changes++);
  return { repository, store, adapters, changes: () => changes };
}

describe("StoreAdapters", () => {
  it("liest Spieler, Abende und Einstellungen aus dem Zustand", async () => {
    const { store, adapters } = await setup();
    await store.saveEvening(testEvening([game("g1")]));

    expect(await adapters.players.players()).toBe(store.getState().players);
    expect(await adapters.evenings.evenings()).toBe(store.getState().evenings);
    expect(await adapters.evenings.evening("abend-1")).toEqual(testEvening([game("g1")]));
    expect(await adapters.evenings.evening("gibt-es-nicht")).toBeNull();
    expect((await adapters.settings.settings()).defaultRules).toEqual(RuleSets.standard());
  });

  it("was der Abgleich schreibt, liegt danach auf dem Gerät und zählt nicht als Änderung von hier", async () => {
    const { repository, store, adapters, changes } = await setup();

    await adapters.players.save({ id: "gast", name: "Gast", colorIndex: 4, archived: false });
    await adapters.players.delete("spieler-4");
    await adapters.evenings.save(testEvening());
    await adapters.editor.replace("abend-1", (current) => ({ ...(current ?? testEvening()), location: "Wirtshaus" }));
    await adapters.editor.replace("abend-2", () => testEvening([], { id: "abend-2" }));
    await adapters.evenings.delete("abend-2");
    await adapters.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));

    expect(changes()).toBe(0);
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.getState().players.map((it) => it.id)).toEqual(["spieler-1", "spieler-2", "spieler-3", "gast"]);
    expect(reloaded.getState().evenings).toEqual([{ ...testEvening(), location: "Wirtshaus" }]);
    expect(reloaded.getState().settings.defaultRules).toEqual(RuleSets.sauspiel());
    expect(store.getState().evenings).toEqual(reloaded.getState().evenings);
  });

  it("Eingaben am Tisch, eine Sicherung und geänderte Regeln melden sich als Änderung", async () => {
    const { store, adapters, changes } = await setup();

    await store.saveEvening(testEvening());
    expect(changes()).toBe(1);
    await store.updateEvening("abend-1", (it) => ({ ...it, entries: [game("g1")] }));
    expect(changes()).toBe(2);
    store.addPlayer("Gast");
    expect(changes()).toBe(3);
    await store.updateSettings((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect(changes()).toBe(4);
    await store.importData([], [testEvening([], { id: "aus-sicherung" })]);
    expect(changes()).toBe(5);
    await store.deleteEvening("aus-sicherung");
    expect(changes()).toBe(6);

    // Was den Abgleich nichts angeht, meldet sich nicht.
    await store.updateSettings((it) => ({ ...it, keepScreenOn: false, themeMode: "LIGHT" }));
    expect(changes()).toBe(6);
    // Eine Eingabe, während der Abgleich gerade schreibt, zählt trotzdem.
    const writing = adapters.players.save({ id: "vom-server", name: "Wirt", colorIndex: 5, archived: false });
    store.addPlayer("Noch ein Gast");
    await writing;
    expect(changes()).toBe(7);
  });

  it("das Abbestellen der Meldungen wirkt", async () => {
    const { store, adapters } = await setup();
    let calls = 0;
    const unsubscribe = adapters.onLocalChange(() => calls++);
    store.addPlayer("Gast");
    unsubscribe();
    store.addPlayer("Zweiter Gast");
    expect(calls).toBe(1);
  });

  it("ein Abend, der nicht ins Dateiformat passt, wird abgelehnt, bevor er den Zustand ändert", async () => {
    const { store, adapters, repository } = await setup();
    await store.saveEvening(testEvening([game("g1")]));
    const broken = testEvening([game("g1", { laufende: 1.5 })]);

    await expect(adapters.editor.replace("abend-1", () => broken)).rejects.toBeInstanceOf(EveningNotStorableError);
    await expect(adapters.evenings.save(testEvening([game("g2", { laufende: 2 ** 40 })], { id: "abend-2" }))).rejects.toBeInstanceOf(
      EveningNotStorableError,
    );

    expect(store.getState().evenings).toEqual([testEvening([game("g1")])]);
    expect(store.getState().saveError).toBeNull();
    expect([...repository.evenings.keys()]).toEqual(["abend-1"]);
    // Danach schreibt der Abgleich weiter wie gewohnt.
    await adapters.editor.replace("abend-1", (current) => ({ ...(current ?? testEvening()), location: "Wirtshaus" }));
    expect(store.evening("abend-1")?.location).toBe("Wirtshaus");
  });

  it("lässt sich nicht speichern, scheitert der Aufruf – und das Lesen holt es später nach", async () => {
    const { store, adapters, repository } = await setup();
    await store.saveEvening(testEvening());
    repository.failWrites = true;

    await expect(adapters.editor.replace("abend-1", (current) => ({ ...(current ?? testEvening()), location: "Wirtshaus" }))).rejects.toBeInstanceOf(
      StorageFailedError,
    );
    await expect(adapters.players.save({ id: "gast", name: "Gast", colorIndex: 4, archived: false })).rejects.toBeInstanceOf(StorageFailedError);
    // Solange nicht alles auf dem Gerät liegt, bekommt der Abgleich die Listen nicht zu lesen.
    await expect(adapters.evenings.evenings()).rejects.toBeInstanceOf(StorageFailedError);
    await expect(adapters.players.players()).rejects.toBeInstanceOf(StorageFailedError);

    repository.failWrites = false;
    expect((await adapters.evenings.evenings())[0].location).toBe("Wirtshaus");
    expect((await adapters.players.players()).map((it) => it.id)).toContain("gast");
    const reloaded = await AppStore.load(repository, clock());
    expect(reloaded.evening("abend-1")?.location).toBe("Wirtshaus");
    expect(reloaded.getState().players.map((it) => it.id)).toContain("gast");
  });

  it("ein einzelner Abend und die Einstellungen kommen erst, wenn alles gespeichert ist", async () => {
    const { store, adapters, repository } = await setup();
    await store.saveEvening(testEvening([game("g1")]));
    repository.failWrites = true;
    // Eine Eingabe am Tisch, die der Browser nicht speichert: Sie steht nur im Arbeitsspeicher.
    await store.updateEvening("abend-1", (it) => ({ ...it, entries: [...it.entries, game("g2")] }));
    await store.updateSettings((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));

    await expect(adapters.evenings.evening("abend-1")).rejects.toBeInstanceOf(StorageFailedError);
    await expect(adapters.settings.settings()).rejects.toBeInstanceOf(StorageFailedError);

    repository.failWrites = false;
    expect((await adapters.evenings.evening("abend-1"))?.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
    expect((await adapters.settings.settings()).defaultRules).toEqual(RuleSets.sauspiel());
  });

  it("Ersetzen wartet aufs Speichern, auch wenn sich nichts ändern müsste: Sonst nähme der Abgleich einen ungespeicherten Stand für gespeichert", async () => {
    const { store, adapters, repository } = await setup();
    await store.saveEvening(testEvening([game("g1")]));
    repository.failWrites = true;
    await store.updateEvening("abend-1", (it) => ({ ...it, entries: [...it.entries, game("g2")] }));
    let calls = 0;

    await expect(
      adapters.editor.replace("abend-1", (current) => {
        calls++;
        return current;
      }),
    ).rejects.toBeInstanceOf(StorageFailedError);
    // Nicht einmal angesehen hat der Abgleich den Abend.
    expect(calls).toBe(0);

    repository.failWrites = false;
    const same = await adapters.editor.replace("abend-1", (current) => current);
    expect(same?.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
    expect((await AppStore.load(repository, clock())).evening("abend-1")?.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
  });

  it("eine Eingabe, die während des Wartens dazukommt, liegt ebenfalls auf dem Gerät, bevor gelesen wird", async () => {
    const { store, adapters, repository } = await setup();
    await store.saveEvening(testEvening([game("g1")]));
    const reading = adapters.evenings.evening("abend-1");
    void store.updateEvening("abend-1", (it) => ({ ...it, entries: [...it.entries, game("g2")] }));

    expect((await reading)?.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
    expect((await AppStore.load(repository, clock())).evening("abend-1")?.entries.map((it) => it.id)).toEqual(["g1", "g2"]);
  });
});

describe("StoreAdapters: was sich auf dem Gerät nicht lesen lässt", () => {
  it("einen unlesbaren Abend überschreibt der Abgleich nicht; seine Basis ist für ihn nicht da, bleibt aber liegen", async () => {
    const repository = new InMemoryRepository(storageCodec);
    repository.evenings.set("kaputt", '{"schemaVersion":99}');
    const store = await AppStore.load(repository, clock());
    const adapters = new StoreAdapters(store, storageCodec.encodeEvening);
    const roundStore = new InMemoryRoundStore();
    const base = { version: 3, evening: testEvening([], { id: "kaputt" }) };
    await roundStore.setEveningBase("kaputt", base);
    await roundStore.setEveningBase("abend-2", { version: 1, evening: testEvening([], { id: "abend-2" }) });
    const shielded = adapters.shield(roundStore);

    expect([...(await shielded.eveningBases()).keys()]).toEqual(["abend-2"]);
    await expect(adapters.editor.replace("kaputt", () => testEvening([], { id: "kaputt" }))).rejects.toBeInstanceOf(EveningNotStorableError);
    await expect(adapters.evenings.save(testEvening([], { id: "kaputt" }))).rejects.toBeInstanceOf(EveningNotStorableError);
    await shielded.setEveningBase("kaputt", null);

    expect(repository.evenings.get("kaputt")).toBe('{"schemaVersion":99}');
    expect((await roundStore.eveningBases()).get("kaputt")).toEqual(base);
    expect(await adapters.evenings.evening("kaputt")).toBeNull();
  });

  it("sind Spielerliste oder Einstellungen unlesbar, bekommt der Abgleich nichts zu lesen", async () => {
    const repository = new InMemoryRepository(storageCodec);
    repository.players = "{kaputt";
    const store = await AppStore.load(repository, clock(), { seedPlayers: false });
    const adapters = new StoreAdapters(store, storageCodec.encodeEvening);

    expect(store.getState().unreadableDocuments).toEqual(["players"]);
    expect(adapters.blocked()).toBe(true);
    await expect(adapters.players.players()).rejects.toBeInstanceOf(UnreadableDataError);
    await expect(adapters.evenings.evenings()).rejects.toBeInstanceOf(UnreadableDataError);
    await expect(adapters.settings.settings()).rejects.toBeInstanceOf(UnreadableDataError);
    await expect(adapters.editor.replace("abend-1", () => testEvening())).rejects.toBeInstanceOf(UnreadableDataError);
    expect(repository.players).toBe("{kaputt");

    // Auch unlesbare Einstellungen zählen; mit lesbaren Daten ist alles wieder frei.
    repository.players = undefined;
    repository.settings = "[]";
    expect((await AppStore.load(repository, clock())).getState().unreadableDocuments).toEqual(["settings"]);
    repository.settings = undefined;
    const fine = new StoreAdapters(await AppStore.load(repository, clock()), storageCodec.encodeEvening);
    expect(fine.blocked()).toBe(false);
  });
});
