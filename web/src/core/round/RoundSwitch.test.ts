/*
 * Zwischen Runden wechseln, einer weiteren beitreten und eine neue anlegen, während das Gerät schon verbunden ist
 * (docs/ARCHITEKTUR.md, 8.7, seit dem 21.11.2026), dazu die eindeutigen Namen. Dabei darf nie verloren gehen, was ein
 * Gerät schon eingetragen hat: Vor dem Wechsel wird abgeglichen, und die Abende der bisherigen Runde bleiben auf dem
 * Server. Port von `RoundSwitchTest.kt`.
 */
import { beforeEach, describe, expect, it } from "vitest";
import type { Evening } from "../model";
import { InMemoryRoundServer } from "./InMemoryRound";
import { switchFrom, toPlayer, type RoundId, type RoundSummary } from "./RoundModels";
import { RoundNames } from "./RoundNames";
import { Device, errorOf, game, P1, PLACEHOLDERS, SEATING, TestClock } from "./testing/fixtures";

let clock: TestClock;
let server: InMemoryRoundServer;
/** Handy eins (Konto 1): verbunden mit dem „Stammtisch“. */
let one: Device;
/** Handy zwei (Konto 2): legt den „Klub“ an. */
let two: Device;
/** Handy drei (Konto 3): spielt im „Stammtisch“ mit. */
let three: Device;

const abendA = "abend-a";
const abendB = "abend-b";

beforeEach(() => {
  clock = new TestClock();
  server = new InMemoryRoundServer(() => clock.millis);
  one = new Device(server, "konto-1", clock);
  two = new Device(server, "konto-2", clock);
  three = new Device(server, "konto-3", clock);
});

/**
 * Zwei Runden. Den „Stammtisch“ legt Handy eins mit vier Spielern und einem Abend an. Den „Klub“ legt Handy zwei mit
 * einem eigenen Abend an; Konto 1 tritt ihm über den Server bei (wie von einem anderen Gerät aus), Handy eins bleibt
 * dabei mit dem „Stammtisch“ verbunden.
 */
async function twoRounds(): Promise<[RoundId, RoundId]> {
  await one.firstStart();
  const names = ["Nord", "Ost", "Süd", "West"];
  for (let index = 0; index < names.length; index++) await one.players.save({ ...PLACEHOLDERS[index], name: names[index] });
  await one.startEvening(abendA);
  clock.advance(60);
  await one.addGame(abendA);
  await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
  const stammtisch = await one.roundId();

  await two.firstStart();
  await two.startEvening(abendB);
  clock.advance(60);
  await two.addGame(abendB, false);
  await two.connection.create("Klub", two.userId, PLACEHOLDERS);
  const klub = await two.roundId();
  await one.api.joinRound(await two.api.createInvite(klub));
  one.session.calls.length = 0;
  return [stammtisch, klub];
}

async function summary(device: Device, round: RoundId): Promise<RoundSummary> {
  const found = (await device.api.myRounds()).find((it) => it.id === round);
  if (found === undefined) throw new Error(`${device.userId} gehört nicht zur Runde ${round}`);
  return found;
}

function onServer(round: RoundId): Evening[] {
  return [...server.evenings(round)].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

async function expectShows(device: Device, round: RoundId): Promise<void> {
  expect(await device.roundId()).toBe(round);
  expect(await device.allEvenings(), `Abende auf ${device.userId}`).toEqual(onServer(round));
  expect(await device.allPlayers(), `Spieler auf ${device.userId}`).toEqual(server.players(round).map(toPlayer));
  expect(await device.sync.pendingCount(), `Wartendes auf ${device.userId}`).toBe(0);
}

describe("Wechseln", () => {
  it("das Gerät hält danach die Daten der anderen Runde, die bisherige bleibt auf dem Server", async () => {
    const [stammtisch, klub] = await twoRounds();
    const before = onServer(stammtisch);

    const outcome = await one.connection.use(await summary(one, klub), one.userId);

    expect(outcome.kind).toBe("Synced");
    await expectShows(one, klub);
    expect((await one.allEvenings()).map((it) => it.id)).toEqual([abendB]);
    expect((await one.link())?.isCreator).toBe(false);
    expect(onServer(stammtisch)).toEqual(before);
    expect(server.players(stammtisch)).toHaveLength(4);

    // Und zurück: Alles ist wieder da.
    expect((await one.connection.use(await summary(one, stammtisch), one.userId)).kind).toBe("Synced");
    await expectShows(one, stammtisch);
    expect(await one.allEvenings()).toEqual(before);
    expect((await one.link())?.isCreator).toBe(true);
    expect((await one.allPlayers()).map((it) => it.name)).toEqual(["Nord", "Ost", "Süd", "West"]);
  });

  it("vor dem Wechseln geht hoch, was auf dem Gerät noch wartet", async () => {
    const [stammtisch, klub] = await twoRounds();
    clock.advance(60);
    await one.addGame(abendA, true, 3);
    expect(await one.sync.pendingCount()).toBe(1);
    const target = await summary(one, klub);
    one.session.calls.length = 0;

    await one.connection.use(target, one.userId);

    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
    await expectShows(one, klub);
  });

  it("ohne Netz bleibt beim Wechseln alles, wie es ist", async () => {
    const [stammtisch, klub] = await twoRounds();
    clock.advance(60);
    await one.addGame(abendA);
    const local = await one.allEvenings();
    const target = await summary(one, klub);
    one.session.offline = true;

    expect(await errorOf(() => one.connection.use(target, one.userId))).toBe("OFFLINE");

    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
    expect(await one.sync.pendingCount()).toBe(1);
    // Mit Netz klappt es, und die Änderung von vorhin kommt dabei im „Stammtisch“ an.
    one.session.offline = false;
    expect((await one.connection.use(target, one.userId)).kind).toBe("Synced");
    expect(onServer(stammtisch)).toEqual(local);
  });

  it("ist die Anmeldung abgelaufen oder antwortet der Server mit einem Fehler, wird nicht gewechselt", async () => {
    const [stammtisch, klub] = await twoRounds();
    const target = await summary(one, klub);
    const local = await one.allEvenings();

    one.session.signedOut = true;
    expect(await errorOf(() => one.connection.use(target, one.userId))).toBe("SIGNED_OUT");
    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
    one.session.signedOut = false;

    // Die Zielrunde gibt es nicht (mehr) für dieses Konto: Der Abgleich davor lief, gewechselt wird trotzdem nicht.
    server.removeMember(klub, one.userId);
    expect(await errorOf(() => one.connection.use(target, one.userId))).toBe("NOT_MEMBER");
    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
  });

  it("gibt es die bisherige Runde nicht mehr, wird nicht gewechselt und die Daten bleiben als Kopie", async () => {
    const [stammtisch, klub] = await twoRounds();
    const local = await one.allEvenings();
    const target = await summary(one, klub);
    server.removeMember(stammtisch, one.userId);

    expect(await one.connection.use(target, one.userId)).toEqual({ kind: "Detached" });

    expect(await one.link()).toBeNull();
    expect(await one.allEvenings()).toEqual(local);
    expect((await one.allPlayers()).map((it) => it.name)).toEqual(["Nord", "Ost", "Süd", "West"]);
    expect(one.session.calls).not.toContain("joinRound");
  });

  it("die verbundene Runde noch einmal wählen gleicht nur ab", async () => {
    const [stammtisch] = await twoRounds();
    const local = await one.allEvenings();

    expect((await one.connection.use(await summary(one, stammtisch), one.userId)).kind).toBe("Synced");

    await expectShows(one, stammtisch);
    expect(await one.allEvenings()).toEqual(local);
  });

  it("was der Server nicht annimmt, wartet auch nach dem Abgleich – und geht beim Wechseln verloren", async () => {
    const [stammtisch, klub] = await twoRounds();
    // Ein Abend, den der Server ablehnt (zu groß); die Oberfläche nennt ihn in ihrer Rückfrage.
    const huge: Evening = {
      id: "riesig",
      startedAt: clock.now(),
      finishedAt: null,
      location: "",
      seating: [...SEATING],
      firstDealer: P1,
      rules: (await one.allEvenings())[0].rules,
      entries: Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => game(`g${index}`, index)),
    };
    await one.evenings.save(huge);
    const outcome = await one.synced();
    expect(outcome.refused).toBe(1);
    expect(await one.sync.pendingCount()).toBe(1);
    const local = await one.allEvenings();
    const target = await summary(one, klub);

    // Ohne Zustimmung (die Rückfrage nannte 0) wird nicht gewechselt.
    expect(await errorOf(() => one.connection.use(target, one.userId))).toBe("CHANGED_MEANWHILE");
    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);

    await one.connection.use(target, one.userId, switchFrom(stammtisch, 1));

    await expectShows(one, klub);
    expect(onServer(stammtisch).map((it) => it.id)).toEqual([abendA]);
  });

  it("ein Spiel, das mitten im Wechsel eingetragen wird, nimmt der letzte Abgleich mit", async () => {
    const [stammtisch, klub] = await twoRounds();
    const target = await summary(one, klub);
    // Während die App nach dem Kopf der Zielrunde fragt, trägt jemand auf diesem Handy noch ein Spiel ein.
    one.api.afterHead = async (round) => {
      if (round !== klub) return;
      one.api.afterHead = null;
      clock.advance(60);
      await one.addGame(abendA, true, 4);
    };

    expect((await one.connection.use(target, one.userId, switchFrom(stammtisch))).kind).toBe("Synced");

    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
    await expectShows(one, klub);
  });

  it("kommt der letzte Abgleich nicht mehr durch, bleibt alles, wie es ist", async () => {
    const [stammtisch, klub] = await twoRounds();
    const target = await summary(one, klub);
    one.api.afterHead = async (round) => {
      if (round !== klub) return;
      one.api.afterHead = null;
      clock.advance(60);
      await one.addGame(abendA);
      one.session.offline = true;
    };

    expect(await errorOf(() => one.connection.use(target, one.userId, switchFrom(stammtisch)))).toBe("OFFLINE");

    expect(await one.roundId()).toBe(stammtisch);
    expect((await one.allEvenings())[0].entries).toHaveLength(2);
    expect(await one.sync.pendingCount()).toBe(1);
  });

  it("hängt das Gerät nicht mehr an der Runde der Rückfrage, wird nicht gewechselt", async () => {
    const [stammtisch, klub] = await twoRounds();
    const local = await one.allEvenings();

    // Die Rückfrage ging von einer anderen Runde aus.
    expect(await errorOf(async () => one.connection.use(await summary(one, klub), one.userId, switchFrom(klub)))).toBe("CHANGED_MEANWHILE");
    expect(await one.roundId()).toBe(stammtisch);
    // Inzwischen getrennt: Die Kopie wird nicht ohne die Rückfrage „Daten dieses Geräts ersetzen?“ geleert.
    await one.connection.disconnect();
    expect(await errorOf(async () => one.connection.use(await summary(one, klub), one.userId, switchFrom(stammtisch)))).toBe(
      "CHANGED_MEANWHILE",
    );
    expect(await errorOf(() => one.connection.create("Neu", one.userId, PLACEHOLDERS, switchFrom(stammtisch)))).toBe("CHANGED_MEANWHILE");
    expect(await one.link()).toBeNull();
    expect(await one.allEvenings()).toEqual(local);
  });

  it("wartet niemand mehr auf das Ergebnis, läuft der Wechsel trotzdem am Stück zu Ende", async () => {
    // Kotlin bricht hier den Aufrufer ab (der Zug läuft `NonCancellable` weiter). In JavaScript lässt sich ein Aufruf
    // nicht abbrechen; wer den Bildschirm verlässt, lässt das Versprechen nur liegen. Der Wechsel läuft dann zu Ende.
    const [, klub] = await twoRounds();
    const target = await summary(one, klub);
    let headOfTarget = (): void => undefined;
    const reachedTarget = new Promise<void>((resolve) => {
      headOfTarget = resolve;
    });
    one.api.afterHead = async (round) => {
      if (round === klub) headOfTarget();
    };

    const abandoned = one.connection.use(target, one.userId);
    await reachedTarget;
    await abandoned.catch(() => undefined);

    expect(await one.roundId()).toBe(klub);
    await expectShows(one, klub);
  });

  it("wer zurückwechselt, sieht, was die anderen inzwischen eingetragen haben", async () => {
    const [stammtisch, klub] = await twoRounds();
    await three.connection.join(await one.api.createInvite(stammtisch), three.userId);
    await one.connection.use(await summary(one, klub), one.userId);

    clock.advance(60);
    await three.addGame(abendA);
    await three.addGame(abendA, false);
    await three.synced();
    await one.synced();
    await expectShows(one, klub);

    await one.connection.use(await summary(one, stammtisch), one.userId);

    await expectShows(one, stammtisch);
    expect((await one.allEvenings())[0].entries).toHaveLength(3);
    expect(await one.allEvenings()).toEqual(await three.allEvenings());
  });
});

describe("Beitreten, während das Gerät verbunden ist", () => {
  it("einer weiteren Runde beitreten: erst abgleichen, dann wechseln", async () => {
    const [stammtisch] = await twoRounds();
    await three.firstStart();
    await three.startEvening("abend-c");
    await three.connection.create("Freitag", three.userId, PLACEHOLDERS);
    const freitag = await three.roundId();
    clock.advance(60);
    await one.addGame(abendA);

    const outcome = await one.connection.join(await three.api.createInvite(freitag), one.userId);

    expect(outcome.kind).toBe("Synced");
    await expectShows(one, freitag);
    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
    expect((await one.api.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Klub", "Freitag"]);
  });

  it("eine Einladung in die verbundene Runde gleicht nur ab", async () => {
    const [stammtisch] = await twoRounds();
    const local = await one.allEvenings();

    expect((await one.connection.join(await one.api.createInvite(stammtisch), one.userId)).kind).toBe("Synced");

    await expectShows(one, stammtisch);
    expect(await one.allEvenings()).toEqual(local);
    expect(one.session.calls).not.toContain("joinRound");
  });

  it("eine ungültige Einladung ändert nichts, auch nicht an der verbundenen Runde", async () => {
    const [stammtisch] = await twoRounds();
    const local = await one.allEvenings();

    expect(await errorOf(() => one.connection.join("0".repeat(32), one.userId))).toBe("INVALID_INVITE");

    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
  });
});

describe("Anlegen, während das Gerät verbunden ist", () => {
  it("neue Runde anlegen: Sie beginnt leer, die bisherige behält alles", async () => {
    const [stammtisch] = await twoRounds();
    clock.advance(60);
    await one.addGame(abendA);
    const before = await one.allEvenings();
    const players = server.players(stammtisch);

    const outcome = await one.connection.create("  Neue   Runde ", one.userId, PLACEHOLDERS);

    expect(outcome.kind).toBe("Synced");
    const neu = await one.roundId();
    expect((await one.link())?.roundName).toBe("Neue Runde");
    expect((await one.link())?.isCreator).toBe(true);
    expect(await one.allEvenings()).toEqual([]);
    expect(await one.allPlayers()).toEqual([]);
    expect(server.evenings(neu)).toEqual([]);
    expect(server.players(neu)).toEqual([]);
    expect(onServer(stammtisch)).toEqual(before);
    expect(server.players(stammtisch)).toEqual(players);
    expect((await one.api.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Klub", "Neue Runde"]);
  });

  it("eine neue Runde mit dem Namen einer Runde des Kontos gibt es nicht – das Gerät bleibt, wie es ist", async () => {
    const [stammtisch] = await twoRounds();
    const local = await one.allEvenings();

    expect(await errorOf(() => one.connection.create(" stammTISCH ", one.userId, PLACEHOLDERS))).toBe("NAME_TAKEN");
    // Auch der Name einer beigetretenen Runde ist vergeben.
    expect(await errorOf(() => one.connection.create("klub", one.userId, PLACEHOLDERS))).toBe("NAME_TAKEN");

    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
    expect(await one.api.myRounds()).toHaveLength(2);
    expect(one.session.calls).not.toContain("createRound");
  });

  it("ohne Netz lässt sich keine neue Runde anlegen – das Gerät bleibt verbunden", async () => {
    const [stammtisch] = await twoRounds();
    const local = await one.allEvenings();
    one.session.offline = true;

    expect(await errorOf(() => one.connection.create("Neu", one.userId, PLACEHOLDERS))).toBe("OFFLINE");

    expect(await one.roundId()).toBe(stammtisch);
    expect(await one.allEvenings()).toEqual(local);
  });

  it("die verbundene, noch leere Runde wird nie wiederverwendet", async () => {
    // Eine Runde, deren erster Abgleich nicht durchkam, hat noch rev = 1 und sähe aus wie eine verwaiste leere Runde.
    // Solange das Gerät an ihr hängt, ist ihr Name trotzdem vergeben.
    one.api.afterHead = async () => {
      one.api.afterHead = null;
      one.session.offline = true;
    };
    expect(await one.connection.create("Leer", one.userId, PLACEHOLDERS)).toEqual({ kind: "Offline" });
    one.session.offline = false;
    const leer = await one.roundId();
    expect(RoundNames.reusable(await summary(one, leer))).toBe(true);

    expect(await errorOf(() => one.connection.create("leer", one.userId, PLACEHOLDERS))).toBe("NAME_TAKEN");
    expect(await one.roundId()).toBe(leer);
  });

  it("ohne Runde scheitert das Anlegen mit einem vergebenen Namen, ohne etwas zu ändern", async () => {
    await twoRounds();
    // Ein zweites Handy desselben Kontos, noch ohne Runde, mit eigenen Daten.
    const other = new Device(server, one.userId, clock);
    await other.firstStart();
    await other.startEvening("eigener");

    expect(await errorOf(() => other.connection.create("Klub", other.userId, PLACEHOLDERS))).toBe("NAME_TAKEN");

    expect(await other.link()).toBeNull();
    expect((await other.allEvenings()).map((it) => it.id)).toEqual(["eigener"]);
    expect(await other.allPlayers()).toEqual(PLACEHOLDERS);
  });

  it("geht die Antwort auf das Anlegen verloren, während das Gerät verbunden ist, nimmt der zweite Versuch dieselbe Runde", async () => {
    const [stammtisch] = await twoRounds();
    // Auf dem Gerät wartet nichts: Der Abgleich davor schreibt nichts, die nächste Änderung ist das Anlegen. Dessen
    // Antwort geht verloren.
    one.session.loseNextAnswer = true;
    expect(await errorOf(() => one.connection.create("Dienstag", one.userId, PLACEHOLDERS))).toBe("OFFLINE");
    expect(await one.roundId()).toBe(stammtisch);
    expect((await one.api.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Klub", "Dienstag"]);

    expect((await one.connection.create("dienstag", one.userId, PLACEHOLDERS)).kind).toBe("Synced");

    expect((await one.api.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Klub", "Dienstag"]);
    expect((await one.link())?.roundName).toBe("Dienstag");
    expect(await one.allEvenings()).toEqual([]);
    expect(server.evenings(stammtisch)).toHaveLength(1);
  });
});

describe("Mitglieder in der Übersicht", () => {
  it("die Übersicht nennt die Namen der Mitglieder", async () => {
    const [stammtisch, klub] = await twoRounds();
    await one.connection.claim(P1, "Nord");
    await three.connection.join(await one.api.createInvite(stammtisch), three.userId);
    await three.connection.claim(null, "anton");
    await one.synced();

    expect((await summary(one, stammtisch)).memberNames).toEqual(["anton", "Nord"]);
    expect((await summary(one, klub)).memberNames).toEqual([]);
    expect((await summary(three, stammtisch)).memberNames).toEqual((await summary(one, stammtisch)).memberNames);

    // Wer die Runde verlässt, fehlt.
    await three.connection.leave();
    expect((await summary(one, stammtisch)).memberNames).toEqual(["Nord"]);
  });
});
