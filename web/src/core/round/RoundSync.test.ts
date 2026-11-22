/*
 * Der Abgleich mit der gemeinsamen Runde (docs/ARCHITEKTUR.md, Abschnitt 8.6): mehrere nachgestellte Handys an einem
 * Server im Speicher, mit Funklöchern, verlorenen Antworten und gleichzeitigen Eingaben. Port von `RoundSyncTest.kt`.
 */
import { beforeEach, describe, expect, it } from "vitest";
import { instantToEpochMillis } from "../instant";
import type { Evening, GameEntry } from "../model";
import { RuleSets } from "../rules/RuleSet";
import { DuplicateCheck } from "./DuplicateCheck";
import { EveningMerge } from "./EveningMerge";
import { InMemoryRoundServer } from "./InMemoryRound";
import { RoundConnection } from "./RoundConnection";
import { RoundException, roundSummary, toPlayer, type RemoteEvening, type RoundId, type RoundSummary } from "./RoundModels";
import { EveningNotStorableError } from "./RoundPorts";
import { RoundSync, SyncOutcome, type SyncNotice } from "./RoundSync";
import { delegate, Device, errorOf, game, P1, P2, P3, P4, PLACEHOLDERS, player, START, TestClock } from "./testing/fixtures";

let clock: TestClock;
let server: InMemoryRoundServer;
let one: Device;
let two: Device;
let three: Device;
const abend = "abend-1";

beforeEach(() => {
  clock = new TestClock();
  server = new InMemoryRoundServer(() => clock.millis);
  one = new Device(server, "konto-1", clock);
  two = new Device(server, "konto-2", clock);
  three = new Device(server, "konto-3", clock);
});

/** Die Runde des Tests: Handy eins legt sie mit vier Spielern und einem Abend an, Handy zwei tritt bei. */
async function round(): Promise<RoundId> {
  await one.firstStart();
  const names = ["Nord", "Ost", "Süd", "West"];
  for (let index = 0; index < names.length; index++) await one.players.save({ ...PLACEHOLDERS[index], name: names[index] });
  await one.startEvening(abend);
  clock.advance(60);
  await one.addGame(abend);
  await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
  await join(two);
  one.session.calls.length = 0;
  two.session.calls.length = 0;
  return one.roundId();
}

async function join(device: Device): Promise<void> {
  const outcome = await device.connection.join(await one.api.createInvite(await one.roundId()), device.userId);
  if (outcome.kind !== "Synced") throw new Error(`Beitritt von ${device.userId}: ${JSON.stringify(outcome)}`);
  await one.synced();
}

async function expectSameEverywhere(id: RoundId, ...devices: Device[]): Promise<void> {
  const onServer = [...server.evenings(id)].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  for (const device of devices) {
    expect(await device.allEvenings(), `Abende auf ${device.userId}`).toEqual(onServer);
    expect(await device.allPlayers(), `Spieler auf ${device.userId}`).toEqual(server.players(id).map(toPlayer));
    expect(await device.sync.pendingCount(), `Wartendes auf ${device.userId}`).toBe(0);
    expect((await device.link())?.cursor, `Stand von ${device.userId}`).toBe(server.rev(id));
  }
}

const synced = SyncOutcome.synced;

function notice(playerId: string, name: string, reason: SyncNotice["reason"], nowName: string | null): SyncNotice {
  return { player: playerId, name, reason, nowName };
}

/** So viele Sekunden nach Beginn des Abends wurde jeder Eintrag aufgeschrieben. */
function secondsOf(evening: Evening | null): number[] {
  return (evening?.entries ?? []).map((entry) => (instantToEpochMillis(entry.recordedAt) - instantToEpochMillis(START)) / 1000);
}

describe("Verbinden", () => {
  it("wer eine Runde anlegt, bringt Spieler, Abende und Regelwerk mit", async () => {
    await one.firstStart();
    await one.players.save({ ...PLACEHOLDERS[0], name: "Nord" });
    await one.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    await one.startEvening(abend);
    await one.addGame(abend);

    const outcome = await one.connection.create("  Stammtisch ", one.userId, PLACEHOLDERS);

    expect(outcome).toEqual(synced({ pulled: 0, pushed: 6, pending: 0 }));
    const id = await one.roundId();
    expect(server.players(id).map((it) => it.name)).toEqual(["Nord", "Spieler 2", "Spieler 3", "Spieler 4"]);
    expect(server.players(id).map((it) => it.position)).toEqual([1, 2, 3, 4]);
    expect(server.evenings(id)).toEqual(await one.allEvenings());
    expect((await one.api.head(id))?.rules).toEqual(RuleSets.sauspiel());
    expect(await one.link()).toEqual({
      roundId: id,
      roundName: "Stammtisch",
      userId: one.userId,
      playerId: null,
      isCreator: true,
      cursor: 7,
      rulesBase: RuleSets.sauspiel(),
      members: [{ userId: one.userId, playerId: null }],
      unreadable: [],
    });
    expect(one.session.calls).toEqual([
      "myRounds",
      "createRound",
      "head",
      "members",
      "playersSince",
      "eveningsSince",
      "savePlayer",
      "savePlayer",
      "savePlayer",
      "savePlayer",
      "saveRules",
      "saveEvening",
      "head",
    ]);
  });

  it("geht die Antwort auf das Anlegen verloren, entsteht beim zweiten Versuch keine zweite Runde", async () => {
    await one.firstStart();
    one.session.loseNextAnswer = true;

    expect(await errorOf(() => one.connection.create("Stammtisch", one.userId, PLACEHOLDERS))).toBe("OFFLINE");

    expect(await one.link()).toBeNull();
    expect(await one.api.myRounds()).toHaveLength(1);
    // Der zweite Versuch nimmt die leere Runde vom ersten, auch mit anderer Groß- und Kleinschreibung.
    expect((await one.connection.create("  STAMMTISCH ", one.userId, PLACEHOLDERS)).kind).toBe("Synced");
    expect((await one.api.myRounds()).map((it) => it.id)).toEqual([await one.roundId()]);
    expect((await one.link())?.roundName).toBe("Stammtisch");
    // Eine Runde, in der schon etwas liegt, wird nie wiederverwendet; ihren Namen gibt es dann kein zweites Mal.
    await one.connection.disconnect();
    expect(await errorOf(() => one.connection.create("Stammtisch", one.userId, PLACEHOLDERS))).toBe("NAME_TAKEN");
    expect(await one.link()).toBeNull();
    expect(await one.api.myRounds()).toHaveLength(1);
    // Eine leere Runde mit anderem Namen wird ebenso wenig wiederverwendet.
    one.session.loseNextAnswer = true;
    expect(await errorOf(() => one.connection.create("Leer", one.userId, PLACEHOLDERS))).toBe("OFFLINE");
    expect((await one.connection.create("Zweite Runde", one.userId, PLACEHOLDERS)).kind).toBe("Synced");
    expect((await one.api.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Leer", "Zweite Runde"]);
  });

  it("auch ein Name mit Leerraum innen findet die leere Runde vom ersten Versuch wieder", async () => {
    await one.firstStart();
    one.session.loseNextAnswer = true;
    expect(await errorOf(() => one.connection.create("Stamm tisch", one.userId, PLACEHOLDERS))).toBe("OFFLINE");

    expect((await one.connection.create(" Stamm \t tisch ", one.userId, PLACEHOLDERS)).kind).toBe("Synced");

    expect(await one.api.myRounds()).toHaveLength(1);
    expect((await one.link())?.roundName).toBe("Stamm tisch");
  });

  it("ohne Netz bleibt das Gerät beim Verwenden einer Runde, wie es ist", async () => {
    await round();
    await three.firstStart();
    await three.startEvening("eigener");
    await three.api.joinRound(await one.api.createInvite(await one.roundId()));
    const [summary] = await three.api.myRounds();
    three.session.offline = true;

    expect(await errorOf(() => three.connection.use(summary, three.userId))).toBe("OFFLINE");

    expect(await three.link()).toBeNull();
    expect((await three.allEvenings()).map((it) => it.id)).toEqual(["eigener"]);
    expect(await three.allPlayers()).toEqual(PLACEHOLDERS);
    // Mit Netz klappt es, und erst dann werden die Daten des Geräts ersetzt.
    three.session.offline = false;
    expect((await three.connection.use(summary, three.userId)).kind).toBe("Synced");
    await one.synced();
    expect(await three.allEvenings()).toEqual(await one.allEvenings());
    expect(await three.allPlayers()).toEqual(await one.allPlayers());
  });

  it("eine Runde, die es nicht mehr gibt, lässt das Gerät unberührt", async () => {
    await three.firstStart();
    await three.startEvening("eigener");
    const gone: RoundSummary = roundSummary({ id: "gibt-es-nicht", name: "Verschwunden", rev: 3, memberCount: 1, myPlayerId: null, isCreator: false });

    expect(await errorOf(() => three.connection.use(gone, three.userId))).toBe("NOT_MEMBER");

    expect(await three.link()).toBeNull();
    expect(await three.allEvenings()).toHaveLength(1);
    expect(await three.allPlayers()).toEqual(PLACEHOLDERS);
  });

  it("ohne Netz bleibt das Gerät auch beim Beitreten, wie es ist", async () => {
    await round();
    await three.firstStart();
    await three.startEvening("eigener");
    const token = await one.api.createInvite(await one.roundId());
    // Die Einladung lässt sich noch lesen und annehmen, danach reißt die Verbindung ab.
    const cut = delegate(three.api, {
      head: async () => {
        throw new RoundException("OFFLINE");
      },
    });
    const connection = new RoundConnection(cut, three.store, three.players, three.evenings, three.sync, three.ids);

    expect(await errorOf(() => connection.join(token, three.userId))).toBe("OFFLINE");

    expect(await three.link()).toBeNull();
    expect((await three.allEvenings()).map((it) => it.id)).toEqual(["eigener"]);
    // Mitglied ist das Konto jetzt schon; der zweite Versuch verbindet das Gerät.
    expect((await three.connection.join(token, three.userId)).kind).toBe("Synced");
    expect(await three.allEvenings()).toEqual(await one.allEvenings());
  });

  it("beim Anlegen entfallen vorbelegte Spieler, die niemand umbenannt hat und kein Abend braucht", async () => {
    await one.firstStart();
    await one.players.save({ ...PLACEHOLDERS[2], name: "Süd" });

    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);

    expect((await one.allPlayers()).map((it) => it.name)).toEqual(["Süd"]);
    expect(server.players(await one.roundId()).map((it) => it.name)).toEqual(["Süd"]);
  });

  it("wer beitritt, übernimmt die Daten der Runde und verliert die eigenen", async () => {
    await two.firstStart();
    await two.startEvening("eigener-abend");
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect(await two.connection.hasOwnData(PLACEHOLDERS)).toBe(true);
    await one.firstStart();
    await one.players.save({ ...PLACEHOLDERS[0], name: "Nord" });
    await one.startEvening(abend);
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);

    const outcome = await two.connection.join(await one.api.createInvite(await one.roundId()), two.userId);

    // Vier Spieler, das Regelwerk und ein Abend kamen vom Server.
    expect(outcome).toEqual(synced({ pulled: 6, pushed: 0, pending: 0 }));
    expect(await two.allEvenings()).toEqual(await one.allEvenings());
    expect(await two.allPlayers()).toEqual(await one.allPlayers());
    expect((await two.settings.settings()).defaultRules).toEqual(RuleSets.standard());
    expect((await two.link())?.roundName).toBe("Stammtisch");
    expect((await two.link())?.isCreator).toBe(false);
    await one.synced();
    expect((await one.link())?.members).toEqual([
      { userId: one.userId, playerId: null },
      { userId: two.userId, playerId: null },
    ]);
    await expectSameEverywhere(await one.roundId(), one, two);
  });

  it("ein unberührtes Gerät hat nichts, was beim Beitreten verloren ginge", async () => {
    expect(await one.connection.hasOwnData(PLACEHOLDERS)).toBe(false);
    await one.firstStart();
    expect(await one.connection.hasOwnData(PLACEHOLDERS)).toBe(false);
    await one.players.save({ ...PLACEHOLDERS[0], name: "Nord" });
    expect(await one.connection.hasOwnData(PLACEHOLDERS)).toBe(true);
  });

  it("ein zweites Handy desselben Kontos verwendet die Runde und bekommt alles", async () => {
    const id = await round();
    await one.connection.claim(P1, "Eins");
    const second = new Device(server, one.userId, clock);
    await second.firstStart();

    const rounds = await second.api.myRounds();
    expect(rounds).toHaveLength(1);
    const outcome = await second.connection.use(rounds[0], second.userId);

    expect(outcome.kind).toBe("Synced");
    expect((await second.link())?.playerId).toBe(P1);
    expect((await second.link())?.isCreator).toBe(true);
    await expectSameEverywhere(id, one, second);
  });
});

describe("Spiele eintragen", () => {
  it("ein eingetragenes Spiel kommt mit zwei Anfragen hoch und beim anderen Handy an", async () => {
    const id = await round();
    clock.advance(300);
    await one.addGame(abend, false);

    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(one.session.calls).toEqual(["head", "saveEvening"]);

    expect(await two.sync.sync(true)).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));
    expect(two.session.calls).toEqual(["head", "members", "playersSince", "eveningsSince"]);
    expect((await two.evening(abend))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });

  it("das regelmäßige Nachfragen kostet eine Anfrage, solange nichts Neues da ist", async () => {
    await round();

    expect(await two.sync.sync(true)).toEqual(SyncOutcome.Unchanged);
    expect(await two.sync.sync(true)).toEqual(SyncOutcome.Unchanged);

    expect(two.session.calls).toEqual(["head", "head"]);
  });

  it("das schnelle Nachfragen prüft nicht, ob hier etwas wartet – der volle Abgleich schon", async () => {
    await round();
    await two.addGame(abend);

    expect(await two.sync.sync(true)).toEqual(SyncOutcome.Unchanged);
    expect(await two.sync.pendingCount()).toBe(1);
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
  });

  it("gleichzeitig eingetragene Spiele stehen danach auf beiden Handys in derselben Reihenfolge", async () => {
    const id = await round();
    clock.advance(100);
    const later = await one.addGame(abend, true, 3);
    clock.advance(-10);
    const earlier = await two.addGame(abend, false);

    await one.synced();
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));
    await one.synced();

    expect((await one.evening(abend))?.entries.slice(1).map((it) => it.id)).toEqual([earlier, later]);
    await expectSameEverywhere(id, one, two);
  });

  it("tragen zwei dasselbe Spiel ein, steht es doppelt da und fällt auf", async () => {
    await round();
    clock.advance(100);
    await one.addGame(abend, false, 4);
    clock.advance(8);
    const second = await two.addGame(abend, false, 4);

    await one.synced();
    await two.synced();
    await one.synced();

    const evening = (await one.evening(abend)) as Evening;
    expect(evening.entries).toHaveLength(3);
    expect(DuplicateCheck.suspects(evening)).toEqual(new Set([second]));
    expect(await two.evening(abend)).toEqual(evening);
  });
});

describe("ohne Netz", () => {
  it("ohne Netz wird weiter eingetragen, und später kommt alles zusammen", async () => {
    const id = await round();
    one.session.offline = true;
    clock.advance(100);
    await one.addGame(abend);
    clock.advance(100);
    await one.addGame(abend);

    expect(await one.sync.sync()).toEqual(SyncOutcome.Offline);
    expect(await one.sync.sync(true)).toEqual(SyncOutcome.Offline);
    expect(await one.sync.pendingCount()).toBe(1);

    clock.advance(-50);
    await two.addGame(abend, false);
    await two.synced();
    one.session.offline = false;
    expect(await one.sync.sync()).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));
    await two.synced();

    expect((await one.evening(abend))?.entries).toHaveLength(4);
    expect(secondsOf(await one.evening(abend))).toEqual([60, 160, 210, 260]);
    await expectSameEverywhere(id, one, two);
  });

  it("ein ohne Netz begonnener Abend geht später als neuer Abend hoch", async () => {
    const id = await round();
    two.session.offline = true;
    const neu = await two.startEvening("abend-2");
    await two.addGame(neu);
    expect(await two.sync.sync()).toEqual(SyncOutcome.Offline);

    two.session.offline = false;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    await one.synced();

    expect(server.eveningRow(id, neu)?.version).toBe(1);
    expect(await one.evening(neu)).toEqual(await two.evening(neu));
    await expectSameEverywhere(id, one, two);
  });

  it("geht die Antwort auf das Hochladen verloren, entsteht nichts doppelt", async () => {
    const id = await round();
    await one.addGame(abend);
    one.session.loseNextAnswer = true;

    expect(await one.sync.sync()).toEqual(SyncOutcome.Offline);
    expect(server.eveningRow(id, abend)?.version).toBe(2);
    expect(await one.sync.pendingCount()).toBe(1);

    one.session.calls.length = 0;
    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));
    expect(one.session.calls).toEqual(["head", "members", "playersSince", "eveningsSince"]);
    expect(server.eveningRow(id, abend)?.version).toBe(2);
    await expectSameEverywhere(id, one);
  });

  it("geht die Antwort auf einen neuen Abend verloren, wird er nicht noch einmal angelegt", async () => {
    const id = await round();
    const neu = await one.startEvening("abend-2");
    one.session.loseNextAnswer = true;
    expect(await one.sync.sync()).toEqual(SyncOutcome.Offline);

    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));

    expect(server.eveningRow(id, neu)?.version).toBe(1);
    await expectSameEverywhere(id, one);
  });
});

describe("jemand anders war schneller", () => {
  it("schreibt jemand genau vor dem Hochladen, wird neu gelesen, zusammengeführt und noch einmal hochgeladen", async () => {
    const id = await round();
    clock.advance(100);
    await one.addGame(abend);
    one.api.beforeSave = async () => {
      clock.advance(5);
      await two.addGame(abend, false);
      await two.synced();
    };

    const outcome = await one.sync.sync();

    expect(outcome).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));
    expect(server.eveningRow(id, abend)?.version).toBe(3);
    expect((await one.evening(abend))?.entries).toHaveLength(3);
    await two.synced();
    await expectSameEverywhere(id, one, two);
  });

  it("nach mehreren vergeblichen Versuchen wartet die Änderung bis zum nächsten Abgleich", async () => {
    const id = await round();
    await one.addGame(abend);
    let interruptions = 0;
    const interrupt = (): void => {
      one.api.beforeSave = async () => {
        clock.advance(5);
        await two.addGame(abend, false);
        await two.synced();
        if (++interruptions < 6) interrupt();
      };
    };
    interrupt();

    // Zwei Durchgänge mit je drei Versuchen, und jedes Mal war Handy zwei schneller.
    const first = await one.sync.sync();

    expect(first).toMatchObject({ kind: "Synced", pushed: 0, pending: 1 });
    expect(server.eveningRow(id, abend)?.version).toBe(7);
    expect(one.session.calls.filter((it) => it === "saveEvening")).toHaveLength(6);

    const second = await one.sync.sync();
    expect(second).toMatchObject({ kind: "Synced", pushed: 1, pending: 0 });
    // Das erste Spiel des Abends, sechs von Handy zwei und das eine von Handy eins.
    expect(server.eveningRow(id, abend)?.evening?.entries).toHaveLength(8);
    await two.synced();
    await expectSameEverywhere(id, one, two);
  });

  it("kennt der Server einen neuen Abend schon, wird mit ihm zusammengeführt", async () => {
    const id = await round();
    // Beide Handys haben denselben Abend (gleiche Kennung), ohne voneinander zu wissen.
    const neu = await one.startEvening("abend-2");
    await one.addGame(neu);
    await two.evenings.save({ ...((await one.evening(neu)) as Evening), entries: [] });
    clock.advance(30);
    await two.addGame(neu, false);
    one.api.beforeSave = async () => {
      await two.synced();
    };

    await one.synced();
    await two.synced();

    expect((await one.evening(neu))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });
});

describe("Abende löschen", () => {
  it("ein gelöschter Abend verschwindet auch auf dem anderen Handy", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    expect(await one.sync.pendingCount()).toBe(1);

    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect(server.eveningRow(id, abend)?.deleted).toBe(true);
    expect(await two.evening(abend)).toBeNull();
    expect((await two.store.eveningBases()).size).toBe(0);
    await expectSameEverywhere(id, one, two);
  });

  it("wird ein Abend gelöscht, während ein anderer darin einträgt, bleibt er erhalten", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    await one.synced();
    await two.addGame(abend);

    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(await one.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect(server.eveningRow(id, abend)?.deleted).toBe(false);
    expect((await one.evening(abend))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });

  it("hier gelöscht, aber auf dem Server inzwischen geändert: der Abend kommt zurück", async () => {
    const id = await round();
    await two.addGame(abend);
    await two.synced();
    await one.evenings.delete(abend);

    expect(await one.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect((await one.evening(abend))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });

  it("hier gelöscht und der Server schickt dieselbe Fassung noch einmal: er bleibt gelöscht", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    // Das Handy liest alles neu (wie nach einem zurückgesetzten Lesezeichen).
    await one.store.setLink({ ...(await one.link())!, cursor: 0 });

    const outcome = await one.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 1 });
    expect(server.eveningRow(id, abend)?.deleted).toBe(true);
    expect(await one.evening(abend)).toBeNull();
    await expectSameEverywhere(id, one);
  });

  it("löschen beide denselben Abend, ist das kein Konflikt", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    await two.evenings.delete(abend);

    await one.synced();
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));

    await expectSameEverywhere(id, one, two);
  });

  it("ein auf dem Server gelöschter Abend, den dieses Handy nie hatte, bleibt weg", async () => {
    const id = await round();
    const neu = await one.startEvening("abend-2");
    await one.synced();
    await one.evenings.delete(neu);
    await one.synced();

    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));

    expect(await two.evening(neu)).toBeNull();
    await expectSameEverywhere(id, one, two);
  });

  it("trägt jemand genau vor dem Löschen etwas ein, kommt der Abend mit dem neuen Spiel zurück", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    one.api.beforeSave = async () => {
      await two.addGame(abend);
      await two.synced();
    };

    const outcome = await one.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0 });
    expect(server.eveningRow(id, abend)?.deleted).toBe(false);
    expect((await one.evening(abend))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });

  it("auch beim erneuten Lesen gilt: eine Änderung von anderswo schlägt das Löschen hier", async () => {
    const id = await round();
    await one.evenings.delete(abend);
    // Der Server schickt die bekannte Fassung noch einmal; genau vor dem Löschen trägt Handy zwei etwas ein.
    await one.store.setLink({ ...(await one.link())!, cursor: 0 });
    one.api.beforeSave = async () => {
      await two.addGame(abend);
      await two.synced();
    };

    await one.synced();

    expect((await one.evening(abend))?.entries).toHaveLength(2);
    await expectSameEverywhere(id, one, two);
  });

  it("wird ein gelöschter Abend von zwei Handys zugleich zurückgeholt, kommen beide Änderungen an", async () => {
    const id = await round();
    await join(three);
    await one.evenings.delete(abend);
    await one.synced();
    clock.advance(100);
    await two.addGame(abend);
    clock.advance(10);
    await three.addGame(abend, false);
    two.api.beforeSave = async () => {
      await three.synced();
    };

    await two.synced();
    await three.synced();
    await one.synced();

    expect((await one.evening(abend))?.entries).toHaveLength(3);
    await expectSameEverywhere(id, one, two, three);
  });

  it("kennt der Server einen Abend entgegen der eigenen Notiz gar nicht, wird er neu angelegt", async () => {
    const id = await round();
    await two.addGame(abend);
    server.forgetEvening(id, abend);

    // Die erwartete Fassung wird abgelehnt, das Nachlesen findet nichts, also geht der Abend als neuer hoch.
    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 1, pending: 0 });
    expect(server.eveningRow(id, abend)).toEqual({ id: abend, version: 1, deleted: false, rev: server.rev(id), evening: await two.evening(abend) });
    await expectSameEverywhere(id, two);
  });
});

describe("Dokumente, die diese Version nicht lesen kann oder der Server nicht annimmt", () => {
  it("ein Abend, den diese Version nicht lesen kann, bleibt unberührt und wird nicht überschrieben", async () => {
    const id = await round();
    const before = (await two.evening(abend)) as Evening;
    server.makeUnreadable(id, abend);

    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0, unreadable: 1 }));
    expect(await two.evening(abend)).toEqual(before);

    await two.addGame(abend);
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 1, unreadable: 1 }));
    expect(server.eveningRow(id, abend)?.evening).toBeNull();

    // Eine neuere App schreibt wieder etwas Lesbares: Jetzt kommt auch das wartende Spiel an.
    await one.api.saveEvening(id, before, 2);
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(server.eveningRow(id, abend)?.evening?.entries).toHaveLength(2);
  });

  it("ein Abend, den der Server nicht annimmt, hält die anderen nicht auf", async () => {
    const id = await round();
    const huge = await two.startEvening("riesig");
    await two.editor.update(huge, (evening) => ({
      ...evening,
      entries: Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => game(`g${index}`, index)),
    }));
    await two.addGame(abend);
    const normal = await two.startEvening("abend-2");

    const first = await two.sync.sync();

    expect(first).toEqual(synced({ pulled: 0, pushed: 2, pending: 1, refused: 1 }));
    expect(server.eveningRow(id, huge)).toBeNull();
    expect(server.eveningRow(id, normal)?.evening).toEqual(await two.evening(normal));

    // Derselbe Stand wird nicht noch einmal versucht, bleibt aber gezählt; nach einer Änderung geht er hoch.
    two.session.calls.length = 0;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 1, refused: 1 }));
    expect(two.session.calls).not.toContain("saveEvening");
    await two.editor.update(huge, (it) => ({ ...it, entries: it.entries.slice(0, 3) }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(server.eveningRow(id, huge)?.evening?.entries).toHaveLength(3);
    await expectSameEverywhere(id, two);
  });

  it("was eine Runde abgelehnt hat, wird in einer neuen Runde wieder versucht", async () => {
    await round();
    const huge = await two.startEvening("riesig");
    await two.editor.update(huge, (it) => ({
      ...it,
      entries: Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => game(`g${index}`, index)),
    }));
    expect(await two.sync.sync()).toMatchObject({ kind: "Synced", refused: 1 });
    two.session.calls.length = 0;
    await two.sync.sync();
    expect(two.session.calls).not.toContain("saveEvening");

    // Handy zwei verlässt die Runde und legt mit seinen Daten eine eigene an: Dort wird alles neu versucht.
    await two.connection.leave();
    two.session.calls.length = 0;
    const outcome = await two.connection.create("Eigene Runde", two.userId, PLACEHOLDERS);

    expect(two.session.calls.filter((it) => it === "saveEvening")).toHaveLength(2);
    expect(outcome).toMatchObject({ kind: "Synced", refused: 1 });
    expect(server.eveningRow(await two.roundId(), abend)?.evening).toEqual(await two.evening(abend));
  });

  it("nimmt der Server das Ergebnis des Zusammenführens nicht an, bleibt es hier und wartet", async () => {
    const id = await round();
    await one.addGame(abend);
    await one.synced();
    await two.editor.update(abend, (evening) => ({
      ...evening,
      entries: [...evening.entries, ...Array.from({ length: InMemoryRoundServer.MAX_ENTRIES }, (_, index) => game(`g${index}`, index))],
    }));

    const outcome = await two.sync.sync();

    // Das Spiel von Handy eins ist hier angekommen, das Hochladen wurde abgelehnt.
    expect(outcome).toEqual(synced({ pulled: 1, pushed: 0, pending: 1, refused: 1 }));
    expect((await two.evening(abend))?.entries).toHaveLength(InMemoryRoundServer.MAX_ENTRIES + 2);
    expect(server.eveningRow(id, abend)?.evening?.entries).toHaveLength(2);
    two.session.calls.length = 0;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 1, refused: 1 }));
    expect(two.session.calls).not.toContain("saveEvening");
    // Löscht man den Abend hier, ist auch die Zählung weg (der Server hat ihn weiter in der alten Fassung).
    await two.evenings.delete(abend);
    expect(await two.sync.sync()).toMatchObject({ kind: "Synced", refused: 0 });
  });

  it("ein unlesbarer Abend bleibt gemerkt und kommt an, sobald er sich lesen lässt", async () => {
    const id = await round();
    const newer = await one.startEvening("abend-neu");
    await one.addGame(newer);
    await one.synced();
    const content = (await one.evening(newer)) as Evening;
    // Eine neuere App schreibt den Abend in einer Form, die Handy zwei nicht lesen kann.
    server.makeUnreadable(id, newer);

    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0, unreadable: 1 }));
    expect(await two.evening(newer)).toBeNull();
    expect((await two.link())?.unreadable).toEqual([newer]);

    // Der Abend liegt jetzt hinter dem Lesezeichen. Er bleibt trotzdem gemerkt und gezählt.
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0, unreadable: 1 }));
    await two.addGame(abend);
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0, unreadable: 1 }));
    expect(await two.sync.sync(true)).toEqual(SyncOutcome.Unchanged);

    // Nach dem Update der App lässt er sich lesen; auf dem Server hat sich dabei nichts geändert.
    server.makeReadable(id, newer, content);
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));
    expect(await two.evening(newer)).toEqual(content);
    expect((await two.link())?.unreadable).toEqual([]);
    await one.synced();
    await expectSameEverywhere(id, one, two);
  });

  it("ein unlesbarer Abend, den es nicht mehr gibt, ist vergessen", async () => {
    const id = await round();
    const newer = await one.startEvening("abend-neu");
    await one.synced();
    server.makeUnreadable(id, newer);
    expect(await two.sync.sync()).toMatchObject({ kind: "Synced", unreadable: 1 });

    server.forgetEvening(id, newer);

    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));
    expect((await two.link())?.unreadable).toEqual([]);
  });

  it("kommt ein Abend beim Lesen zweimal, zählt die neuere Fassung", async () => {
    const id = await round();
    const old = (await two.evening(abend)) as Evening;
    await one.addGame(abend);
    await one.synced();
    const row = server.eveningRow(id, abend) as RemoteEvening;
    const twice = delegate(two.session, {
      eveningsSince: async () => [{ ...row, version: 1, evening: old }, row, { ...row, version: 1, evening: old }],
    });
    const sync = new RoundSync(twice, two.store, two.players, two.evenings, two.settings, two.editor);

    expect(await sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect(await two.evening(abend)).toEqual(await one.evening(abend));
  });
});

describe("Spieler", () => {
  it("ein umbenannter Gast heißt danach überall so, ein neuer steht überall hinten", async () => {
    const id = await round();
    await one.players.save({ ...(await one.allPlayers())[1], name: "Osten" });
    await two.players.save(player("gast", "Wirt", 4));

    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));
    expect(await one.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect((await one.allPlayers()).map((it) => it.name)).toEqual(["Nord", "Osten", "Süd", "West", "Wirt"]);
    await expectSameEverywhere(id, one, two);
  });

  it("benennen beide denselben Gast um, gilt der Name, der zuerst auf dem Server war", async () => {
    const id = await round();
    await one.players.save({ ...(await one.allPlayers())[0], name: "Norden" });
    await two.players.save({ ...(await two.allPlayers())[0], name: "N." });

    await one.synced();
    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0 });
    expect((await two.allPlayers())[0].name).toBe("Norden");
    await expectSameEverywhere(id, one, two);
  });

  it("archivieren und Farbe wechseln kommen beim anderen Handy an", async () => {
    const id = await round();
    await one.players.save({ ...(await one.allPlayers())[3], archived: true, colorIndex: 6 });

    await one.synced();
    await two.synced();

    expect((await two.allPlayers())[3]).toEqual(player(P4, "West", 6, true));
    await expectSameEverywhere(id, one, two);
  });

  it("eine Farbnummer außerhalb von 0 bis 31 wird vor dem Hochladen umgestellt", async () => {
    const id = await round();
    await one.players.save(player("gast", "Wirt", 39));

    await one.synced();

    expect((await one.allPlayers()).at(-1)?.colorIndex).toBe(7);
    expect(server.players(id).at(-1)?.colorIndex).toBe(7);
    await expectSameEverywhere(id, one);
  });

  it("ein gelöschter Gast verschwindet auch auf dem anderen Handy", async () => {
    const id = await round();
    await two.players.save(player("gast", "Wirt", 4));
    await two.synced();
    await one.synced();

    await one.players.delete("gast");
    expect(await one.sync.pendingCount()).toBe(1);
    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect(await two.allPlayers()).toHaveLength(4);
    await expectSameEverywhere(id, one, two);
  });

  it("ein auf dem Server gelöschter Gast, den hier ein Abend braucht, bleibt und geht wieder hoch", async () => {
    const id = await round();
    const gast = "gast";
    await two.players.save(player(gast, "Wirt", 4));
    await two.synced();
    await one.synced();
    // Handy eins löscht den Gast; Handy zwei hat ihn inzwischen ohne Netz an den Tisch gesetzt.
    await one.players.delete(gast);
    await one.synced();
    await two.startEvening("abend-2", [P1, P2, P3, gast]);

    const outcome = await two.sync.sync();
    await one.synced();

    expect(outcome).toMatchObject({ kind: "Synced", pending: 0 });
    expect((await two.allPlayers()).at(-1)?.name).toBe("Wirt");
    expect((await one.allPlayers()).at(-1)?.name).toBe("Wirt");
    await expectSameEverywhere(id, one, two);
  });

  it("ein hier gelöschter Gast kommt zurück, wenn ihn ein Abend eines anderen Handys nennt", async () => {
    const id = await round();
    const gast = "gast";
    await two.players.save(player(gast, "Wirt", 4));
    await two.synced();
    await one.synced();
    await two.startEvening("abend-2", [P1, P2, P3, gast]);
    await two.synced();
    // Handy eins weiß von dem neuen Abend noch nichts und löscht den Gast.
    await one.players.delete(gast);

    const outcome = await one.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0 });
    expect((await one.allPlayers()).at(-1)?.name).toBe("Wirt");
    expect(server.players(id).at(-1)?.name).toBe("Wirt");
    await expectSameEverywhere(id, one, two);
  });

  it("ein neuer Gast mit vergebenem Namen entfällt mit Hinweis", async () => {
    const id = await round();
    await two.players.save(player("gast", "nord", 4));

    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", notices: [notice("gast", "nord", "NAME_TAKEN", null)] });
    expect(await two.allPlayers()).toHaveLength(4);
    await expectSameEverywhere(id, two);
  });

  it("ein neuer Gast mit vergebenem Namen, der schon am Tisch sitzt, bekommt eine Nummer", async () => {
    const id = await round();
    const gast = "gast";
    await two.players.save(player(gast, "Nord", 4));
    await two.players.save(player("gast-2", "Nord 2", 5));
    await two.startEvening("abend-2", [gast, P2, P3, P4]);

    const outcome = await two.sync.sync();
    await one.synced();

    expect(outcome).toMatchObject({ kind: "Synced", pending: 0, notices: [notice(gast, "Nord", "NAME_TAKEN", "Nord 3")] });
    expect(server.players(id).map((it) => it.name)).toEqual(["Nord", "Ost", "Süd", "West", "Nord 2", "Nord 3"]);
    expect(await one.allPlayers()).toEqual(server.players(id).map(toPlayer));
    // Auf dem Handy, das beide Gäste angelegt hat, stehen sie in der Reihenfolge, in der sie dort entstanden sind.
    expect(new Set((await two.allPlayers()).map((it) => JSON.stringify(it)))).toEqual(new Set((await one.allPlayers()).map((it) => JSON.stringify(it))));
    expect(await two.sync.pendingCount()).toBe(0);
    expect(await two.allEvenings()).toEqual(await one.allEvenings());
  });

  it("ein langer Name wird für die Nummer gekürzt", async () => {
    const id = await round();
    const long = "Der Nachbar vom Wirtshaus";
    expect(long).toHaveLength(25);
    const name = long.slice(0, -1);
    await one.players.save(player("gast-1", name, 4));
    await one.synced();
    await two.players.save(player("gast-2", name.toUpperCase(), 5));
    await two.startEvening("abend-2", ["gast-2", P2, P3, P4]);

    await two.synced();

    expect((await two.allPlayers()).find((it) => it.id === "gast-2")?.name).toBe("DER NACHBAR VOM WIRTSH 2");
    expect(server.players(id).at(-1)?.name).toBe("DER NACHBAR VOM WIRTSH 2");
  });

  it("einen festen Namen kann nur sein Besitzer vergeben haben: andere werden zurückgesetzt", async () => {
    const id = await round();
    await one.connection.claim(P1, "Eins");
    await two.synced();
    await two.players.save({ ...(await two.allPlayers())[0], name: "Witzbold", colorIndex: 5 });

    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", notices: [notice(P1, "Witzbold", "NAME_LOCKED", "Eins")] });
    expect((await two.allPlayers())[0]).toEqual(player(P1, "Eins", 0));
    expect(server.players(id)[0].nameLocked).toBe(true);
    await expectSameEverywhere(id, two);
  });

  it("Farbe und Archiv eines festen Spielers darf jeder ändern, den Namen nur der Ersteller der Runde", async () => {
    const id = await round();
    await two.connection.claim(P2, "Zwei");
    await one.synced();

    await two.players.save({ ...(await two.allPlayers())[1], colorIndex: 7 });
    await two.synced();
    await one.synced();
    await one.players.save({ ...(await one.allPlayers())[1], name: "Zwo" });
    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    await two.synced();

    expect((await two.allPlayers())[1]).toEqual(player(P2, "Zwo", 7));
    await expectSameEverywhere(id, one, two);
  });

  it("ein Spieler mit Besitzer lässt sich nicht löschen und kommt zurück", async () => {
    const id = await round();
    await two.connection.claim(null, "Zwei");
    const neu = (await two.link())?.playerId as string;
    expect(neu).toBeTruthy();
    await one.synced();
    await one.players.delete(neu);

    const outcome = await one.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", notices: [notice(neu, "Zwei", "PLAYER_OWNED", "Zwei")] });
    expect((await one.allPlayers()).at(-1)?.name).toBe("Zwei");
    await expectSameEverywhere(id, one, two);
  });

  it("ist die Runde voll, bleibt ein gebrauchter Gast hier und wird nicht bei jedem Abgleich neu versucht", async () => {
    const id = await round();
    for (let index = 0; index < InMemoryRoundServer.MAX_PLAYERS - 4; index++) await one.players.save(player(`voll-${index}`, `Gast ${index}`, index));
    await one.synced();
    await two.synced();
    const gast = "einer-zu-viel";
    await two.players.save(player(gast, "Nachzügler", 1));
    await two.startEvening("abend-2", [gast, P2, P3, P4]);

    const first = await two.sync.sync();
    expect(first).toMatchObject({ kind: "Synced", pending: 1, notices: [notice(gast, "Nachzügler", "LIMIT_REACHED", "Nachzügler")] });

    two.session.calls.length = 0;
    const second = await two.sync.sync();
    expect(second).toMatchObject({ kind: "Synced", notices: [] });
    expect(two.session.calls).not.toContain("savePlayer");

    // Sobald sich an dem Spieler etwas ändert, wird es wieder versucht.
    await two.players.save({ ...((await two.allPlayers()).at(-1) as ReturnType<typeof player>), colorIndex: 2 });
    const third = await two.sync.sync();
    expect(third.kind === "Synced" ? third.notices : null).toHaveLength(1);
    expect(server.players(id)).toHaveLength(InMemoryRoundServer.MAX_PLAYERS);
  });

  it("ein unbenutzter Gast zu viel entfällt mit Hinweis", async () => {
    await round();
    for (let index = 0; index < InMemoryRoundServer.MAX_PLAYERS - 4; index++) await one.players.save(player(`voll-${index}`, `Gast ${index}`, index));
    await one.synced();
    await two.synced();
    await two.players.save(player("einer-zu-viel", "Nachzügler", 1));

    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pending: 0, notices: [notice("einer-zu-viel", "Nachzügler", "LIMIT_REACHED", null)] });
  });

  it("das eigene Echo überschreibt keine neuere Änderung von hier", async () => {
    const id = await round();
    await one.players.save({ ...(await one.allPlayers())[0], name: "Norden" });
    await one.synced();
    await one.players.save({ ...(await one.allPlayers())[0], name: "Nordlicht" });
    // Das Handy liest alles neu und bekommt dabei auch den eigenen, älteren Namen wieder.
    await one.store.setLink({ ...(await one.link())!, cursor: 0 });

    await one.synced();

    expect(server.players(id)[0].name).toBe("Nordlicht");
    await expectSameEverywhere(id, one);
  });

  it("Namen und Farben aus alten Daten werden vor dem Hochladen in Form gebracht", async () => {
    const id = await round();
    await two.players.save(player("gast-1", "  Wirt   vom\tEck ", -1));
    await two.players.save(player("gast-2", "Ein viel zu langer Name für den Server", 4));

    await two.synced();
    await one.synced();

    expect((await one.allPlayers())[4]).toEqual(player("gast-1", "Wirt vom Eck", 31));
    expect((await one.allPlayers())[5]).toEqual(player("gast-2", "Ein viel zu langer Name", 4));
    await expectSameEverywhere(id, one, two);
  });

  it("ein Spieler, den der Server gar nicht annimmt, entfällt oder bleibt hier, ohne den Abgleich aufzuhalten", async () => {
    const id = await round();
    const long = "x".repeat(InMemoryRoundServer.MAX_ID_LENGTH + 1);
    const used = "y".repeat(InMemoryRoundServer.MAX_ID_LENGTH + 1);
    await two.players.save(player(long, "Unbenutzt", 4));
    await two.players.save(player(used, "Am Tisch", 5));
    await two.players.save(player("leer", "   ", 6));
    await two.addGame(abend);
    await two.editor.update(abend, (it) => ({ ...it, firstDealer: used }));

    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({
      kind: "Synced",
      notices: [
        notice(long, "Unbenutzt", "INVALID_DOC", null),
        notice(used, "Am Tisch", "INVALID_DOC", "Am Tisch"),
        notice("leer", "   ", "INVALID_NAME", null),
      ],
      // Das Spiel ist trotzdem oben; nur der eine Spieler wartet.
      pushed: 1,
      pending: 1,
    });
    expect(server.eveningRow(id, abend)?.evening).toEqual(await two.evening(abend));
  });
});

describe("Regelwerk für neue Abende", () => {
  it("ein geändertes Regelwerk für neue Abende gilt danach auf allen Handys", async () => {
    await round();
    await one.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect(await one.sync.pendingCount()).toBe(1);

    expect(await one.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));

    expect((await two.settings.settings()).defaultRules).toEqual(RuleSets.sauspiel());
    expect((await two.link())?.rulesBase).toEqual(RuleSets.sauspiel());
  });

  it("ändern beide das Regelwerk, gilt das, was zuerst auf dem Server war", async () => {
    await round();
    const mine = { ...RuleSets.standard(), name: "von eins" };
    await one.settings.update((it) => ({ ...it, defaultRules: mine }));
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));

    await one.synced();
    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0 });
    expect((await two.settings.settings()).defaultRules).toEqual(mine);
  });

  it("ein Regelwerk, das diese Version nicht lesen kann, wird weder übernommen noch überschrieben", async () => {
    const id = await round();
    server.makeRulesUnreadable(id);
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));

    const outcome = await two.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 0, pending: 1 });
    expect(two.session.calls).not.toContain("saveRules");
    expect((await two.settings.settings()).defaultRules).toEqual(RuleSets.sauspiel());
  });

  it("ein Regelwerk, das der Server nicht annimmt, wird nicht bei jedem Abgleich neu versucht", async () => {
    await round();
    const wordy = { ...RuleSets.standard(), name: "x".repeat(InMemoryRoundServer.MAX_RULES_NAME_LENGTH + 1) };
    await two.settings.update((it) => ({ ...it, defaultRules: wordy }));
    await two.addGame(abend);

    const first = await two.sync.sync();
    two.session.calls.length = 0;
    const second = await two.sync.sync();

    expect(first).toMatchObject({ kind: "Synced", pushed: 1, pending: 1 });
    expect(second).toMatchObject({ kind: "Synced", pending: 1 });
    expect(two.session.calls).not.toContain("saveRules");
    // Ein anderes Regelwerk geht wieder hoch.
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 1, pending: 0 }));
  });
});

describe("Das bin ich", () => {
  it("ein vorhandener Spieler bekommt den eigenen, festen Namen", async () => {
    const id = await round();

    const outcome = await two.connection.claim(P3, "  Zwei ");
    await one.synced();

    expect(outcome.kind).toBe("Synced");
    expect((await two.link())?.playerId).toBe(P3);
    expect((await one.allPlayers())[2]).toEqual(player(P3, "Zwei", 2));
    const row = server.players(id)[2];
    expect(row.owner).toBe(two.userId);
    expect(row.nameLocked).toBe(true);
    expect((await one.link())?.members).toEqual([
      { userId: one.userId, playerId: null },
      { userId: two.userId, playerId: P3 },
    ]);
    expect((await one.store.playerBases()).get(P3)?.nameLocked).toBe(true);
    await expectSameEverywhere(id, one, two);
  });

  it("wer neu ist, wird als Spieler angelegt und steht bei allen in der Liste", async () => {
    const id = await round();

    await two.connection.claim(null, "Zwei");
    await one.synced();

    const me = (await two.link())?.playerId;
    expect(me).toBeTruthy();
    // Die erste freie Farbe nach den vier vorhandenen Spielern.
    expect((await one.allPlayers()).at(-1)).toEqual(player(me as string, "Zwei", 4));
    expect(await one.allPlayers()).toHaveLength(5);
    await expectSameEverywhere(id, one, two);
  });

  it("nur einmal, kein fremder Spieler und kein vergebener Name", async () => {
    await round();
    await one.connection.claim(P1, "Eins");

    expect(await errorOf(() => one.connection.claim(P2, "Noch einer"))).toBe("ALREADY_CLAIMED");
    await two.synced();
    expect(await errorOf(() => two.connection.claim(P1, "Zwei"))).toBe("PLAYER_TAKEN");
    expect(await errorOf(() => two.connection.claim(P2, "eins"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.connection.claim(null, "Süd"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.connection.claim(P2, "   "))).toBe("INVALID_NAME");
    expect((await two.link())?.playerId).toBeNull();
  });

  it("ohne Runde gibt es kein Das-bin-ich", async () => {
    expect(await one.connection.claim(null, "Eins")).toEqual(SyncOutcome.NotLinked);
  });
});

describe("Trennen", () => {
  it("gehört das Konto nicht mehr zur Runde, löst sich die Verbindung und die Daten bleiben", async () => {
    const id = await round();
    server.removeMember(id, two.userId);

    expect(await two.sync.sync()).toEqual(SyncOutcome.Detached);

    expect(await two.link()).toBeNull();
    expect((await two.store.eveningBases()).size).toBe(0);
    expect((await two.store.playerBases()).size).toBe(0);
    expect(await two.allEvenings()).toHaveLength(1);
    expect(await two.allPlayers()).toHaveLength(4);
    expect(await two.sync.sync()).toEqual(SyncOutcome.NotLinked);
    expect(await two.sync.pendingCount()).toBe(0);
  });

  it("eine einzelne seltsame Antwort trennt die Runde nicht vom Handy", async () => {
    await round();
    const headless = delegate(two.session, { head: async () => null });
    const sync = new RoundSync(headless, two.store, two.players, two.evenings, two.settings, two.editor);

    expect(await sync.sync()).toEqual(SyncOutcome.failed("SERVER"));

    expect(await two.link()).not.toBeNull();
  });

  it("Runde verlassen: das Konto ist draußen, die Daten bleiben als Kopie", async () => {
    const id = await round();
    await two.connection.claim(P2, "Zwei");

    await two.connection.leave();
    await one.synced();

    expect(await two.link()).toBeNull();
    expect(await two.allEvenings()).toHaveLength(1);
    expect((await one.link())?.members).toEqual([{ userId: one.userId, playerId: null }]);
    // Der Spieler bleibt in der Runde; wer wiederkommt, ist wieder er.
    expect(server.players(id)[1].name).toBe("Zwei");
    await two.connection.join(await one.api.createInvite(id), two.userId);
    expect((await two.link())?.playerId).toBe(P2);
  });

  it("verlässt der Letzte die Runde, gibt es sie nicht mehr", async () => {
    await one.firstStart();
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
    const id = await one.roundId();

    await one.connection.leave();

    expect(await one.link()).toBeNull();
    expect(await one.api.myRounds()).toEqual([]);
    expect(await one.api.head(id)).toBeNull();
    await one.connection.leave();
  });

  it("ohne Netz lässt sich die Runde nicht verlassen, aber die Verbindung auf dem Handy lösen", async () => {
    await round();
    two.session.offline = true;

    expect(await errorOf(() => two.connection.leave())).toBe("OFFLINE");
    expect(await two.link()).not.toBeNull();

    await two.connection.disconnect();
    expect(await two.link()).toBeNull();
    expect((await two.store.playerBases()).size).toBe(0);
    two.session.offline = false;
    expect(await one.api.members(await one.roundId())).toHaveLength(2);
  });
});

describe("Fehler", () => {
  it("ist die Anmeldung abgelaufen, bleibt alles, wie es ist", async () => {
    const id = await round();
    await two.addGame(abend);
    two.session.signedOut = true;

    expect(await two.sync.sync()).toEqual(SyncOutcome.SignedOut);
    expect(await two.link()).not.toBeNull();
    expect(await two.sync.pendingCount()).toBe(1);

    two.session.signedOut = false;
    await two.synced();
    await expectSameEverywhere(id, two);
  });

  it("ein Fehler des Servers wird gemeldet", async () => {
    await round();
    const broken = delegate(two.session, {
      head: async () => {
        throw new RoundException("SERVER", "503");
      },
    });
    const sync = new RoundSync(broken, two.store, two.players, two.evenings, two.settings, two.editor);

    expect(await sync.sync()).toEqual(SyncOutcome.failed("SERVER"));
  });

  it("ein Fehler, der nicht vom Server kommt, wird nicht verschluckt", async () => {
    await round();
    const broken = delegate(two.session, {
      head: async () => {
        throw new TypeError("Programmfehler");
      },
    });
    const sync = new RoundSync(broken, two.store, two.players, two.evenings, two.settings, two.editor);

    await expect(sync.sync()).rejects.toThrow("Programmfehler");
    // Der nächste Abgleich läuft trotzdem wieder an.
    expect(await new RoundSync(two.api, two.store, two.players, two.evenings, two.settings, two.editor).sync()).toMatchObject({ kind: "Synced" });
  });

  it("ohne Runde gibt es nichts abzugleichen", async () => {
    await one.firstStart();

    expect(await one.sync.sync()).toEqual(SyncOutcome.NotLinked);
    expect(await one.sync.pendingCount()).toBe(0);
    expect(one.session.calls).toEqual([]);
  });

  it("bricht es zwischen Abend und Lesezeichen ab, führt der nächste Abgleich zum selben Ergebnis", async () => {
    const id = await round();
    clock.advance(100);
    await two.addGame(abend, false);
    await two.synced();
    clock.advance(10);
    await one.addGame(abend);
    // Der Abend ist schon zusammengeführt gespeichert, die Basis noch nicht – wie nach einem Absturz genau dazwischen.
    const base = (await one.store.eveningBases()).get(abend)?.evening as Evening;
    const merged = EveningMerge.merge3(base, (await one.evening(abend)) as Evening, (await two.evening(abend)) as Evening);
    await one.evenings.save(merged);

    await one.synced();
    await two.synced();

    expect(server.eveningRow(id, abend)?.evening?.entries).toHaveLength(3);
    await expectSameEverywhere(id, one, two);
  });
});

describe("ein Server, der zurückfällt", () => {
  it("fällt der Server auf einen alten Stand zurück, geht nichts verloren", async () => {
    const id = await round();
    const old = (await one.evening(abend)) as Evening;
    await one.addGame(abend);
    await one.synced();
    await one.addGame(abend);
    await one.synced();
    expect(server.eveningRow(id, abend)?.version).toBe(3);

    server.rollBack(id, abend, 1, old, 3);
    const outcome = await one.sync.sync();

    expect(outcome).toMatchObject({ kind: "Synced", pushed: 1 });
    expect((await one.evening(abend))?.entries).toHaveLength(3);
    expect(server.eveningRow(id, abend)?.evening).toEqual(await one.evening(abend));
    expect(server.eveningRow(id, abend)?.version).toBe(2);
  });
});

describe("Wartendes zählen", () => {
  it("gezählt wird, was noch nicht auf dem Server ist", async () => {
    await round();
    expect(await two.sync.pendingCount()).toBe(0);

    await two.addGame(abend);
    await two.startEvening("abend-2");
    expect(await two.sync.pendingCount()).toBe(2);
    await two.players.save({ ...(await two.allPlayers())[0], name: "Norden" });
    await two.players.save(player("gast", "Wirt", 4));
    await two.players.delete(P4);
    expect(await two.sync.pendingCount()).toBe(5);
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    expect(await two.sync.pendingCount()).toBe(6);
    await two.evenings.delete(abend);
    expect(await two.sync.pendingCount()).toBe(6);
  });

  it("drei Handys kommen auch bei verschränkten Eingaben zum selben Stand", async () => {
    const id = await round();
    await join(three);
    const games: GameEntry[] = [];
    const play = async (device: Device, won: boolean): Promise<void> => {
      clock.advance(40);
      const entryId = await device.addGame(abend, won);
      games.push((await device.evening(abend))?.entries.find((it) => it.id === entryId) as GameEntry);
    };
    await play(one, true);
    await play(two, false);
    await one.synced();
    await play(three, true);
    await three.synced();
    await play(two, true);
    await two.synced();
    await play(one, false);
    await one.synced();
    await three.synced();
    await two.synced();

    const expected = (await one.evening(abend)) as Evening;
    expect(expected.entries.slice(1).map((it) => it.id)).toEqual(games.map((it) => it.id));
    await expectSameEverywhere(id, one, two, three);
  });
});

describe("nur in der Web-App: Ergänzungen zum Port", () => {
  it("hochgeladen wird, was nach dem Zusammenführen wirklich auf dem Gerät steht", async () => {
    const id = await round();
    clock.advance(100);
    await one.addGame(abend);
    await one.synced();
    clock.advance(10);
    await two.addGame(abend, false);
    // Eine Ablage, die beim Speichern etwas angleicht (hier: Orte ohne Leerraum am Rand), wie es Room mit Zeitpunkten tut.
    const save = two.evenings.save.bind(two.evenings);
    two.evenings.save = (evening) => save({ ...evening, location: evening.location.trim() });
    await one.editor.update(abend, (it) => ({ ...it, location: " Wirtshaus " }));
    await one.synced();

    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));

    // Auf dem Server steht die Fassung der Ablage, und das Gerät hält den Abend nicht für geändert.
    expect(server.eveningRow(id, abend)?.evening?.location).toBe("Wirtshaus");
    expect(server.eveningRow(id, abend)?.evening).toEqual(await two.evening(abend));
    two.session.calls.length = 0;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0 }));
    expect(two.session.calls).toEqual(["head"]);
  });

  it("ein Abend, den die Ablage nicht speichern kann, hält die anderen nicht auf", async () => {
    // Wie `RoundSyncRoomTest` der Android-App: Dort lehnt Room einen Abend ab, hier eine Ablage, die ihn nicht schreiben kann.
    const id = await round();
    const save = two.evenings.save.bind(two.evenings);
    two.evenings.save = async (evening) => {
      if (evening.id === "fremd") throw new EveningNotStorableError("Der Abend fremd lässt sich nicht speichern.");
      await save(evening);
    };
    await one.startEvening("fremd");
    await one.startEvening("gut");
    await one.synced();
    two.session.calls.length = 0;

    const outcome = await two.sync.sync();

    expect(outcome).toEqual(synced({ pulled: 1, pushed: 0, pending: 0, unreadable: 1 }));
    expect(await two.evening("fremd")).toBeNull();
    expect(await two.evening("gut")).not.toBeNull();
    expect(two.session.calls).toEqual(["head", "members", "playersSince", "eveningsSince"]);
    // Auf dem Server bleibt der fremde Abend unberührt. Das Gerät merkt ihn sich und liest ihn bei jedem Abgleich
    // einzeln nach (vielleicht lässt er sich später speichern), schreibt aber nichts.
    expect((await two.link())?.unreadable).toEqual(["fremd"]);
    two.session.calls.length = 0;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 0, pushed: 0, pending: 0, unreadable: 1 }));
    expect(two.session.calls).toEqual(["head", "evening"]);
    expect(server.eveningRow(id, "fremd")?.version).toBe(1);
    expect(await two.evening("fremd")).toBeNull();
    expect((await two.link())?.cursor).toBe(server.rev(id));
    // Das schnelle Nachfragen kostet weiter nur eine Anfrage.
    two.session.calls.length = 0;
    expect(await two.sync.sync(true)).toEqual(SyncOutcome.Unchanged);
    expect(two.session.calls).toEqual(["head"]);

    // Lässt er sich später speichern, kommt er an, ohne dass sich auf dem Server etwas geändert hat.
    two.evenings.save = save;
    expect(await two.sync.sync()).toEqual(synced({ pulled: 1, pushed: 0, pending: 0 }));
    expect(await two.evening("fremd")).toEqual(server.eveningRow(id, "fremd")?.evening);
    expect((await two.link())?.unreadable).toEqual([]);
  });

  it("es läuft immer nur ein Abgleich gleichzeitig, in der Reihenfolge der Aufrufe", async () => {
    await round();
    await two.addGame(abend);
    const running = [two.sync.sync(), two.sync.sync(), two.sync.sync(true)];

    expect(await Promise.all(running)).toEqual([
      synced({ pulled: 0, pushed: 1, pending: 0 }),
      synced({ pulled: 0, pushed: 0, pending: 0 }),
      SyncOutcome.Unchanged,
    ]);
    // Wären die Abgleiche ineinander gelaufen, stünde zwischen „head“ und „saveEvening“ ein weiteres „head“.
    expect(two.session.calls).toEqual(["head", "saveEvening", "head", "head"]);
  });

  it("schreibt jemand an einem anderen Abend, während dieses Handy hochlädt, springt das Lesezeichen nicht darüber hinweg", async () => {
    const id = await round();
    const other = await two.startEvening("abend-2");
    await two.synced();
    await one.synced();
    await one.addGame(abend);
    one.session.calls.length = 0;
    // Kein Konflikt am selben Abend: Das Hochladen gelingt, aber die Nummer des Servers schließt nicht an.
    one.api.beforeSave = async () => {
      await two.addGame(other);
      await two.synced();
    };

    const outcome = await one.sync.sync();

    expect(outcome).toEqual(synced({ pulled: 1, pushed: 1, pending: 0 }));
    expect((await one.evening(other))?.entries).toHaveLength(1);
    expect(one.session.calls).toEqual(["head", "saveEvening", "head", "members", "playersSince", "eveningsSince"]);
    await two.synced();
    await expectSameEverywhere(id, one, two);
  });

  it("auch ein Spieler, den eine volle Runde abgelehnt hat, geht in einer neuen Runde hoch", async () => {
    await round();
    for (let index = 0; index < InMemoryRoundServer.MAX_PLAYERS - 4; index++) await one.players.save(player(`voll-${index}`, `Gast ${index}`, index));
    await one.synced();
    await two.synced();
    const gast = "einer-zu-viel";
    await two.players.save(player(gast, "Nachzügler", 1));
    await two.startEvening("abend-2", [gast, P2, P3, P4]);
    expect(await two.sync.sync()).toMatchObject({ kind: "Synced", pending: 1 });

    // Handy zwei verlässt die volle Runde, räumt die vielen Gäste weg und legt mit dem Rest eine eigene Runde an.
    await two.connection.leave();
    for (const guest of await two.allPlayers()) if (guest.id.startsWith("voll-")) await two.players.delete(guest.id);
    const outcome = await two.connection.create("Neue Runde", two.userId, PLACEHOLDERS);

    expect(outcome).toMatchObject({ kind: "Synced", pending: 0, notices: [] });
    expect(server.players(await two.roundId()).map((it) => it.name)).toContain("Nachzügler");
  });
});
