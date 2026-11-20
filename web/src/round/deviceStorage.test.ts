/*
 * Der Abgleich der gemeinsamen Runde und der Speicher des Browsers (docs/ARCHITEKTUR.md, Abschnitt 8.8): Er arbeitet
 * nur mit Gespeichertem, und was sich auf dem Gerät nicht lesen lässt, gilt nie als gelöscht oder leer. Echter Zustand
 * der App über einem Speicher im Arbeitsspeicher, echte Anbindung, echter Abgleich, Server im Speicher.
 */
import { describe, expect, it } from "vitest";
import { storageCodec } from "../app/formatAdapters";
import { AppStore, StorageFailedError, UnreadableDataError } from "../app/store";
import type { Evening, EveningId } from "../core/model";
import { InMemoryRoundServer, InMemoryRoundStore } from "../core/round/InMemoryRound";
import { RoundConnection } from "../core/round/RoundConnection";
import type { RoundId } from "../core/round/RoundModels";
import { RoundSync } from "../core/round/RoundSync";
import { after, evening, game, HookedApi, ids, PLACEHOLDERS } from "../core/round/testing/fixtures";
import { RuleSets } from "../core/rules/RuleSet";
import { InMemoryRepository } from "../data/repository";
import { StoreAdapters } from "./storeAdapters";

let counter = 0;
const clock = { now: () => "2026-12-12T18:00:00Z", newId: () => `id-${++counter}` };

/** Ein Handy: Zustand der App über `repository` (wie nach dem Laden der Seite), Anbindung und Abgleich wie in der App. */
async function device(repository: InMemoryRepository, roundStore: InMemoryRoundStore, api: HookedApi) {
  const store = await AppStore.load(repository, clock, { seedPlayers: (await roundStore.link()) === null });
  const adapters = new StoreAdapters(store, storageCodec.encodeEvening);
  const shielded = adapters.shield(roundStore);
  const sync = new RoundSync(api, shielded, adapters.players, adapters.evenings, adapters.settings, adapters.editor);
  const connection = new RoundConnection(api, shielded, adapters.players, adapters.evenings, sync, { newId: () => `neu-${++counter}` });
  return { store, adapters, sync, connection };
}

function entriesOn(server: InMemoryRoundServer, round: RoundId, id: EveningId): string[] {
  const found = server.evenings(round).find((it) => it.id === id);
  return found === undefined ? [] : ids(found);
}

/** Ein Gerät mit einer Runde „Stammtisch“ und den Abenden `evenings`, alles hochgeladen. */
async function founded(...evenings: Evening[]) {
  const server = new InMemoryRoundServer();
  const api = new HookedApi(server.sessionFor("konto-1"));
  const repository = new InMemoryRepository(storageCodec);
  const roundStore = new InMemoryRoundStore();
  const one = await device(repository, roundStore, api);
  for (const it of evenings) await one.store.saveEvening(it);
  expect((await one.connection.create("Stammtisch", "konto-1", PLACEHOLDERS)).kind).toBe("Synced");
  const round = (await roundStore.link())?.roundId ?? "";
  return { server, api, repository, roundStore, one, round };
}

describe("Basis nie neuer als das Gerät", () => {
  it("scheitert das Speichern während eines Abgleichs, bricht er ab: keine Basis, kein Hochladen; nach dem Neuladen geht der richtige Stand hoch", async () => {
    const { server, api, repository, roundStore, one, round } = await founded(
      evening([game("a1", 10)], "alt"),
      // „neu“ beginnt später und wird deshalb zuerst abgeglichen.
      { ...evening([game("n1", 10)], "neu"), startedAt: after(3600) },
    );
    // Am Tisch wird in beiden Abenden etwas eingetragen und gespeichert.
    await one.store.updateEvening("neu", (it) => ({ ...it, entries: [...it.entries, game("n2", 20)] }));
    await one.store.updateEvening("alt", (it) => ({ ...it, entries: [...it.entries, game("a2", 20)] }));

    // Während „neu“ hochgeht, wird in „alt“ ein Spiel eingetragen – und genau jetzt speichert der Browser nicht mehr.
    api.beforeSave = async () => {
      repository.failWrites = true;
      await one.store.updateEvening("alt", (it) => ({ ...it, entries: [...it.entries, game("a3", 30)] }));
    };
    await expect(one.sync.sync()).rejects.toBeInstanceOf(StorageFailedError);

    // „neu“ lag auf dem Gerät und ist oben; „alt“ mit dem nicht gespeicherten Spiel ging weder hoch, noch wurde es Basis.
    expect(entriesOn(server, round, "neu")).toEqual(["n1", "n2"]);
    expect(entriesOn(server, round, "alt")).toEqual(["a1"]);
    expect(ids((await roundStore.eveningBases()).get("alt")?.evening ?? evening())).toEqual(["a1"]);
    expect(one.store.getState().saveError).not.toBeNull();

    // Die Seite wird neu geladen, der Speicher geht wieder. Auf dem Gerät liegt „alt“ ohne a3 (das sagte die Meldung
    // „Speichern fehlgeschlagen“); dieser Stand geht hoch, und nichts sieht nach einer Löschung aus.
    repository.failWrites = false;
    const two = await device(repository, roundStore, api);
    expect(ids(two.store.evening("alt") ?? evening())).toEqual(["a1", "a2"]);
    expect((await two.sync.sync()).kind).toBe("Synced");
    expect(entriesOn(server, round, "alt")).toEqual(["a1", "a2"]);
    expect(await two.sync.pendingCount()).toBe(0);
  });

});

describe("Unlesbares auf dem Gerät gilt nie als gelöscht", () => {
  it("ein unlesbarer Abend bleibt in der Runde, seine Basis bleibt liegen; was der Server Neues hat, überschreibt ihn nicht", async () => {
    const { server, api, repository, roundStore, round } = await founded(evening([game("g1", 10)], "abend"), {
      ...evening([game("x1", 10)], "anderer"),
      startedAt: after(60),
    });
    // Der gespeicherte Text lässt sich nicht mehr lesen (zum Beispiel von einer neueren Version geschrieben).
    const text = repository.evenings.get("abend") ?? "";
    repository.evenings.set("abend", text.replace(/"schemaVersion":\s*\d+/, '"schemaVersion":99'));

    const two = await device(repository, roundStore, api);
    expect(two.store.getState().unreadableEvenings).toEqual(["abend"]);
    // Für den Abgleich ist nichts „hier gelöscht“.
    expect(await two.sync.pendingCount()).toBe(0);
    const outcome = await two.sync.sync();
    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0 });
    expect(server.eveningRow(round, "abend")?.deleted).toBe(false);
    expect((await roundStore.eveningBases()).has("abend")).toBe(true);

    // Ein anderes Handy ändert den Abend: Dieses Gerät überschreibt seinen unlesbaren Text nicht, sondern merkt sich
    // den Abend als unlesbar (Statuszeile „1 Abend kann diese Version nicht lesen …“) und liest ihn immer wieder.
    const other = server.sessionFor("konto-1");
    const row = await other.evening(round, "abend");
    await other.saveEvening(round, { ...evening([game("g1", 10), game("g2", 20)], "abend") }, row?.version ?? 0);
    const later = await two.sync.sync();
    expect(later).toMatchObject({ kind: "Synced", unreadable: 1 });
    expect(repository.evenings.get("abend")).toContain('"schemaVersion":99');
    expect(entriesOn(server, round, "abend")).toEqual(["g1", "g2"]);
    expect((await roundStore.link())?.unreadable).toEqual(["abend"]);

    // Lässt sich der Abend wieder lesen (zum Beispiel nach einem Update), kommt die Fassung des Servers an.
    repository.evenings.set("abend", text);
    const three = await device(repository, roundStore, api);
    expect(await three.sync.sync()).toMatchObject({ kind: "Synced", unreadable: 0 });
    expect(ids(three.store.evening("abend") ?? evening())).toEqual(["g1", "g2"]);
    expect(entriesOn(server, round, "abend")).toEqual(["g1", "g2"]);
  });

  it("ist die Spielerliste unlesbar, liest und schreibt der Abgleich nichts", async () => {
    const { server, api, repository, roundStore, round } = await founded(evening([game("g1", 10)], "abend"));
    await server.sessionFor("konto-1").savePlayer(round, { id: "gast", name: "Gast", colorIndex: 4, archived: false });
    const players = repository.players ?? "";
    repository.players = "{kaputt";

    const two = await device(repository, roundStore, api);
    expect(two.store.getState().unreadableDocuments).toEqual(["players"]);
    expect(two.adapters.blocked()).toBe(true);
    await expect(two.sync.sync()).rejects.toBeInstanceOf(UnreadableDataError);
    await expect(two.sync.pendingCount()).rejects.toBeInstanceOf(UnreadableDataError);

    // Kein Spieler wurde auf dem Server gelöscht, und die Spielerliste auf dem Gerät ist unberührt.
    expect(server.players(round).filter((it) => it.deleted)).toEqual([]);
    expect(repository.players).toBe("{kaputt");
    repository.players = players;
    const three = await device(repository, roundStore, api);
    expect((await three.sync.sync()).kind).toBe("Synced");
    expect(server.players(round).filter((it) => it.deleted)).toEqual([]);
    expect(three.store.getState().players.map((it) => it.id)).toContain("gast");
  });

  it("sind die Einstellungen unlesbar, geht das Standard-Regelwerk nicht in die Runde", async () => {
    const { server, api, repository, roundStore, one, round } = await founded(evening([game("g1", 10)], "abend"));
    await one.store.updateSettings((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect((await one.sync.sync()).kind).toBe("Synced");
    expect((await server.sessionFor("konto-1").head(round))?.rules).toEqual(RuleSets.sauspiel());
    repository.settings = "{kaputt";

    const two = await device(repository, roundStore, api);
    expect(two.store.getState().unreadableDocuments).toEqual(["settings"]);
    await expect(two.sync.sync()).rejects.toBeInstanceOf(UnreadableDataError);

    expect((await server.sessionFor("konto-1").head(round))?.rules).toEqual(RuleSets.sauspiel());
    expect(repository.settings).toBe("{kaputt");
  });
});
