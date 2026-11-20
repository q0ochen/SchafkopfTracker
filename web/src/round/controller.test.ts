/*
 * Die gemeinsame Runde in der Web-App von der Anmeldung bis zur Statuszeile: nachgestellte Handys mit dem echten
 * Zustand der App, dem echten Abgleich und dem Planer, an einem Server im Speicher. Die Uhr ist gestellt.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundException } from "../core/round/RoundModels";
import { needsSignIn, NOT_CONFIGURED } from "./controller";
import { account, Phone, WEB_APP_URL } from "./testing/phone";
import { roundStatus } from "./texts";

let server: InMemoryRoundServer;
const phones: Phone[] = [];

/** Lässt die gestellte Uhr laufen und alles Angestoßene fertig werden. */
async function pass(millis = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(millis);
}

async function phone(name: string, signedIn: number | null = null): Promise<Phone> {
  const created = await Phone.create(server, name, signedIn === null ? null : account(signedIn));
  phones.push(created);
  return created;
}

/** Ein Handy, auf dem Konto 1 angemeldet ist und eine Runde mit einem Abend und einem Spiel angelegt hat. */
async function founder(): Promise<Phone> {
  const one = await phone("eins", 1);
  await one.startEvening("abend-1");
  await one.addGame("abend-1");
  one.controller.start(true);
  await pass();
  expect((await one.controller.create("Stammtisch")).kind).toBe("Synced");
  await pass();
  return one;
}

/** Ein neuer Einladungscode für die Runde von `one`. */
async function inviteToken(one: Phone): Promise<string> {
  const token = InviteLinks.tokenFrom(await one.controller.inviteLink());
  if (token === null) throw new Error("kein Code im Einladungslink");
  return token;
}

/** Ein weiteres Handy, das der Runde von `one` beitritt. */
async function guest(one: Phone, number = 2): Promise<Phone> {
  const two = await phone(`handy-${number}`, number);
  two.controller.start(true);
  await pass();
  expect((await two.controller.join(await inviteToken(one))).kind).toBe("Synced");
  await pass();
  return two;
}

function status(of: Phone): string {
  const state = of.state;
  return roundStatus({ ...state, needsSignIn: needsSignIn(state) }).text;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-12-12T19:00:00Z"));
  vi.spyOn(console, "warn").mockImplementation(() => undefined);
  server = new InMemoryRoundServer(() => Date.now());
  phones.length = 0;
});

afterEach(() => {
  for (const it of phones) it.controller.stop();
  vi.useRealTimers();
});

describe("Start", () => {
  it("ohne Runde und ohne Anmeldung wird nichts gefragt", async () => {
    const one = await phone("eins");
    expect(one.state).toEqual({ ...NOT_CONFIGURED, configured: true, loaded: false });

    one.controller.start(true);
    await pass(20_000);

    expect(one.state).toEqual({ ...NOT_CONFIGURED, configured: true });
    expect(needsSignIn(one.state)).toBe(false);
    expect(one.backendLoads).toBe(1);
  });

  it("angemeldet, aber ohne Runde: Der Server wird in Ruhe gelassen", async () => {
    const one = await phone("eins", 1);
    one.controller.start(true);
    await pass(20_000);

    expect(one.state.account).toEqual(account(1));
    expect(one.state.link).toBeNull();
    expect(one.mine.calls).toEqual([]);
  });

  it("lässt sich die Anbindung nicht laden, wird es mit wachsendem Abstand wieder versucht", async () => {
    const one = await phone("eins", 1);
    one.backendFails = true;
    one.controller.start(true);
    await pass();
    expect(one.state.loaded).toBe(false);
    expect(one.state.problem).toBe("OFFLINE");
    const before = one.backendLoads;

    await pass(4000);
    expect(one.backendLoads).toBeGreaterThan(before);
    one.backendFails = false;
    await pass(8000);

    expect(one.state.loaded).toBe(true);
    expect(one.state.account).toEqual(account(1));
    expect(one.state.problem).toBeNull();
  });
});

describe("Anmelden", () => {
  it("die Rückkehr mit Code meldet an", async () => {
    const one = await phone("eins");
    one.auth.nextAccount = account(1);
    one.controller.start(true, { kind: "code", code: "gut" });
    await pass();

    expect(one.state.account).toEqual(account(1));
    expect(one.state.loaded).toBe(true);
    expect(one.state.message).toBeNull();
  });

  it("ein Code, den der Server nicht annimmt: Meldung, niemand angemeldet", async () => {
    const one = await phone("eins");
    one.controller.start(true, { kind: "code", code: "falsch" });
    await pass();

    expect(one.state.account).toBeNull();
    expect(one.state.message).toBe("SIGN_IN_FAILED");
    one.controller.clearMessage();
    expect(one.state.message).toBeNull();
  });

  it.each([
    ["access_denied", "SIGN_IN_CANCELLED"],
    ["server_error", "SIGN_IN_FAILED"],
  ] as const)("die Rückkehr mit dem Fehler %s meldet %s", async (error, message) => {
    const one = await phone("eins");
    one.controller.start(true, { kind: "failure", error });
    await pass();
    expect(one.state.message).toBe(message);
    expect(one.state.account).toBeNull();
  });

  it("„Mit Google anmelden“ beginnt die Anmeldung", async () => {
    const one = await phone("eins");
    one.controller.start(true);
    await pass();
    await one.controller.signIn();
    expect(one.auth.signInsStarted).toBe(1);
  });

  it("meldet sich nach der Rückkehr ein anderes Konto an, bleibt die Runde verbunden und das Konto wird wieder abgemeldet", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    // Die Anmeldung ist abgelaufen; inzwischen wurde ein Spiel eingetragen, das noch wartet.
    one.mine.signedOut = true;
    await one.addGame("abend-1");
    await pass(500);
    expect(needsSignIn(one.state)).toBe(true);
    expect(one.state.pending).toBe(1);
    one.session("konto-1").signedOut = false;
    one.session("konto-1").calls.length = 0;
    one.auth.nextAccount = account(2);

    await one.reload({ kind: "code", code: "gut" });
    await pass(10_000);

    // Nichts lief unter dem fremden Konto, und nichts ging verloren.
    expect(one.state.link?.roundId).toBe(id);
    expect(one.state.message).toBe("OTHER_ACCOUNT");
    expect(one.state.account).toBeNull();
    expect(needsSignIn(one.state)).toBe(true);
    expect(one.auth.signOuts).toBe(1);
    expect(one.session("konto-2").calls).toEqual([]);
    expect(one.session("konto-1").calls).toEqual([]);
    expect(one.state.pending).toBe(1);
    expect(status(one)).toBe("Anmeldung abgelaufen – bitte neu anmelden");

    // Mit dem richtigen Konto geht das wartende Spiel hoch, und die Meldung ist erledigt.
    one.auth.nextAccount = account(1);
    await one.reload({ kind: "code", code: "gut" });
    await pass();
    expect(one.state.message).toBeNull();
    expect(one.state.pending).toBe(0);
    expect(status(one)).toBe("Abgeglichen");
    expect(server.evenings(id)[0].entries).toHaveLength(2);
  });

  it("meldet sich in einem anderen Fenster ein anderes Konto an, wird es hier gleich wieder abgemeldet; abgeglichen wird nie unter seinem Namen", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    one.mine.offline = true;
    await one.addGame("abend-1");
    await pass(500);
    one.mine.offline = false;

    one.auth.set(account(2));
    // Was in diesem Augenblick noch an den Server geht, scheitert, statt unter fremdem Namen zu laufen.
    await expect(one.controller.inviteLink()).rejects.toMatchObject({ error: "SIGNED_OUT" });
    await pass(10_000);

    expect(one.state.link?.roundId).toBe(id);
    expect(one.state.message).toBe("OTHER_ACCOUNT");
    expect(one.state.account).toBeNull();
    expect(one.auth.signOuts).toBe(1);
    expect(one.session("konto-2").calls).toEqual([]);
    expect(await one.controller.pendingCount()).toBe(1);

    // Mit dem richtigen Konto geht es weiter.
    one.auth.set(account(1));
    await pass();
    expect(status(one)).toBe("Abgeglichen");
    expect(server.evenings(id)[0].entries).toHaveLength(2);
  });

  it("hat ein anderes Fenster die Runde schon gelöst, bleibt das dort neu angemeldete Konto angemeldet", async () => {
    const one = await Phone.create(server, "eins", account(1), { cachedStore: true });
    phones.push(one);
    one.controller.start(true);
    await pass();
    expect((await one.controller.create("Stammtisch")).kind).toBe("Synced");
    await pass();
    // Ein anderes Fenster derselben App hat abgemeldet und die Runde gelöst; dieses Fenster hat noch den alten Stand.
    await one.roundStore.setLink(null);
    await one.roundStore.clearBases();
    expect(one.state.link).not.toBeNull();

    // Dort meldet sich jemand mit einem anderen Konto an.
    one.auth.set(account(2));
    await pass();

    expect(one.auth.signOuts).toBe(0);
    expect(one.state.account).toEqual(account(2));
    expect(one.state.link).toBeNull();
    expect(one.state.message).toBeNull();
  });

  it("während die Anmeldung eingelöst wird, ist das zu sehen", async () => {
    const one = await phone("eins");
    let release = (): void => undefined;
    one.auth.exchange = new Promise((resolve) => {
      release = resolve;
    });
    one.auth.nextAccount = account(1);

    one.controller.start(true, { kind: "code", code: "gut" });
    await pass();
    expect(one.state.signingIn).toBe(true);
    expect(one.state.loaded).toBe(false);

    release();
    await pass();
    expect(one.state.signingIn).toBe(false);
    expect(one.state.loaded).toBe(true);
    expect(one.state.account).toEqual(account(1));
  });

  it("hängt das Einlösen der Anmeldung, gilt es nach 20 Sekunden als „keine Verbindung“; die Knöpfe sind wieder frei", async () => {
    const one = await phone("eins");
    let answer = (): void => undefined;
    one.auth.exchange = new Promise((resolve) => {
      answer = resolve;
    });
    one.auth.nextAccount = account(1);

    one.controller.start(true, { kind: "code", code: "gut" });
    await pass(19_999);
    expect(one.state.signingIn).toBe(true);
    await pass(1);

    expect(one.state.signingIn).toBe(false);
    expect(one.state.loaded).toBe(true);
    expect(one.state.account).toBeNull();
    expect(one.state.message).toBe("SIGN_IN_FAILED");

    // Kommt die Antwort doch noch, ist die Anmeldung geglückt, und die Meldung erledigt sich.
    answer();
    await pass();
    expect(one.state.account).toEqual(account(1));
    expect(one.state.message).toBeNull();
  });

  it("eine Meldung zur Anmeldung bleibt stehen, solange sich niemand neu anmeldet", async () => {
    const one = await phone("eins", 1);
    one.controller.start(true, { kind: "failure", error: "access_denied" });
    await pass(10_000);
    // Angemeldet war das Konto schon vorher: Die Meldung über den Abbruch bleibt.
    expect(one.state.account).toEqual(account(1));
    expect(one.state.message).toBe("SIGN_IN_CANCELLED");
  });

  it("kommt dieselbe Rückkehr ein zweites Mal, gibt es keine Fehlermeldung", async () => {
    const one = await phone("eins");
    one.auth.nextAccount = account(1);
    one.controller.start(true, { kind: "code", code: "gut" });
    await pass();
    expect(one.state.account).toEqual(account(1));

    // Zur Rückkehr gibt es keine begonnene Anmeldung mehr; angemeldet ist längst jemand.
    one.auth.noPendingSignIn = true;
    await one.reload({ kind: "code", code: "gut" });
    await pass();

    expect(one.state.message).toBeNull();
    expect(one.state.account).toEqual(account(1));
    expect(one.state.signingIn).toBe(false);

    // Ohne Anmeldung ist eine solche Rückkehr aber ein Fehler.
    const two = await phone("zwei");
    two.auth.noPendingSignIn = true;
    two.controller.start(true, { kind: "code", code: "gut" });
    await pass();
    expect(two.state.message).toBe("SIGN_IN_FAILED");
    expect(two.state.account).toBeNull();
  });
});

describe("Statuszeile beim Start", () => {
  it("was beim Start schon wartet, zeigt die Statuszeile gleich, nicht erst nach dem ersten Abgleich", async () => {
    const one = await founder();
    one.mine.offline = true;
    await one.addGame("abend-1");
    await pass(500);
    one.mine.offline = false;

    // Neu geladen mit langsamem Netz: Die Anbindung an den Server lässt auf sich warten.
    let release = (): void => undefined;
    one.backendGate = new Promise((resolve) => {
      release = resolve;
    });
    await one.reload();
    await pass();
    expect(one.state.loaded).toBe(false);
    expect(one.state.pending).toBe(1);
    expect(status(one)).toBe("1 Änderung wartet");

    // Die Anbindung ist da, aber die erste Antwort des Servers dauert: Auch dann steht das Wartende da.
    const head = one.mine.head.bind(one.mine);
    let answer = (): void => undefined;
    const slow = new Promise<void>((resolve) => {
      answer = resolve;
    });
    one.mine.head = async (round) => {
      await slow;
      return head(round);
    };
    release();
    await pass();
    expect(one.state.syncing).toBe(true);
    expect(one.state.pending).toBe(1);
    expect(status(one)).toBe("1 Änderung wartet");

    answer();
    await pass();
    expect(status(one)).toBe("Abgeglichen");
  });

  it("Abende, die diese Version nicht lesen kann, stehen nach dem Neuladen gleich in der Statuszeile", async () => {
    const one = await founder();
    const two = await guest(one);
    await one.startEvening("abend-2");
    await pass(500);
    server.makeUnreadable(one.state.link?.roundId ?? "", "abend-2");
    await pass(4000);
    expect(two.state.unreadable).toBe(1);

    two.backendGate = new Promise(() => undefined);
    await two.reload();
    await pass();

    expect(two.state.loaded).toBe(false);
    expect(two.state.unreadable).toBe(1);
    expect(status(two)).toBe("1 Abend kann diese Version nicht lesen – bitte die App aktualisieren");
  });
});

describe("Runde anlegen und beitreten", () => {
  it("wer eine Runde anlegt, bringt seine Daten mit und ist danach abgeglichen", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";

    expect(one.state.link).toMatchObject({ roundName: "Stammtisch", userId: "konto-1", isCreator: true, playerId: null });
    expect(status(one)).toBe("Abgeglichen");
    expect(one.state.pending).toBe(0);
    expect(server.evenings(id)).toEqual(one.store.getState().evenings);
    expect(server.players(id).map((it) => it.name)).toEqual(["Spieler 1", "Spieler 2", "Spieler 3", "Spieler 4"]);
    // Der Stand der Spieler auf dem Server steht der Oberfläche zur Verfügung.
    expect([...one.state.players.keys()]).toEqual(["spieler-1", "spieler-2", "spieler-3", "spieler-4"]);
  });

  it("wer beitritt, übernimmt die Daten der Runde; die eigenen werden ersetzt", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    await two.startEvening("eigener-abend");
    two.controller.start(true);
    await pass();
    const token = await inviteToken(one);
    two.controller.rememberInvite(token);
    expect(two.invite).toBe(token);

    expect((await two.controller.join(token)).kind).toBe("Synced");
    await pass();

    expect(two.store.getState().evenings).toEqual(one.store.getState().evenings);
    expect(two.store.getState().players).toEqual(one.store.getState().players);
    expect(two.state.link).toMatchObject({ roundName: "Stammtisch", userId: "konto-2", isCreator: false });
    // Die gemerkte Einladung ist eingelöst.
    expect(two.invite).toBeNull();
    expect(two.state.pendingInvite).toBeNull();
    expect(status(two)).toBe("Abgeglichen");
  });

  it("der Einladungslink führt auf die Einladungsseite der Web-App", async () => {
    const one = await founder();
    const link = await one.controller.inviteLink();
    expect(link).toMatch(/^https:\/\/app\.example\/SchafkopfTracker\/#\/einladung\/[0-9a-f]{32}$/);
    expect(link.startsWith(`${WEB_APP_URL}#/einladung/`)).toBe(true);
  });

  it("eine Runde des Kontos lässt sich auf einem neuen Handy verwenden", async () => {
    const one = await founder();
    const other = await phone("neues-handy", 1);
    other.controller.start(true);
    await pass();
    const [round] = await other.controller.myRounds();

    expect((await other.controller.use(round)).kind).toBe("Synced");

    expect(other.store.getState().evenings).toEqual(one.store.getState().evenings);
    expect(other.state.link).toMatchObject({ roundId: one.state.link?.roundId, isCreator: true });
  });

  it("„Das bin ich“ legt den eigenen Namen fest, und alle lesen ihn", async () => {
    const one = await founder();
    const two = await guest(one);

    expect((await two.controller.claim("spieler-2", "Gast")).kind).toBe("Synced");
    await pass(4000);

    expect(two.state.link?.playerId).toBe("spieler-2");
    expect(two.state.players.get("spieler-2")).toMatchObject({ name: "Gast", owner: "konto-2", nameLocked: true });
    expect(one.store.playerName("spieler-2")).toBe("Gast");
    expect(one.state.players.get("spieler-2")?.nameLocked).toBe(true);
    expect(one.state.link?.members).toEqual([
      { userId: "konto-1", playerId: null },
      { userId: "konto-2", playerId: "spieler-2" },
    ]);
  });
});

describe("Abgleich im Betrieb", () => {
  it("ein eingetragenes Spiel ist eine halbe Sekunde später auf dem Server und nach spätestens vier Sekunden beim anderen", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";
    // Erst sieht Handy eins, dass jemand beigetreten ist; danach ist Ruhe.
    await pass(4000);
    one.mine.calls.length = 0;

    const entry = await one.addGame("abend-1");
    await pass(499);
    expect(server.evenings(id)[0].entries).toHaveLength(1);
    await pass(1);
    expect(server.evenings(id)[0].entries.map((it) => it.id)).toContain(entry);
    expect(one.mine.calls).toEqual(["head", "saveEvening"]);
    expect(status(one)).toBe("Abgeglichen");

    await pass(4000);
    expect(two.entryIds("abend-1")).toContain(entry);
    expect(status(two)).toBe("Abgeglichen");
  });

  it("solange nichts zu tun ist, fragt die App alle vier Sekunden nur kurz nach", async () => {
    const one = await founder();
    await pass(4000);
    one.mine.calls.length = 0;
    await pass(12_000);
    expect(one.mine.calls).toEqual(["head", "head", "head"]);
    expect(one.state.syncing).toBe(false);
  });

  it("tragen zwei gleichzeitig ein, haben am Ende beide beide Spiele", async () => {
    const one = await founder();
    const two = await guest(one);

    const first = await one.addGame("abend-1");
    const second = await two.addGame("abend-1");
    await pass(10_000);

    expect(one.entryIds("abend-1")).toEqual(two.entryIds("abend-1"));
    expect(one.entryIds("abend-1")).toEqual(expect.arrayContaining([first, second]));
    expect(one.entryIds("abend-1")).toHaveLength(3);
    expect(status(one)).toBe("Abgeglichen");
    expect(status(two)).toBe("Abgeglichen");
  });

  it("ohne Netz wartet die Änderung, die Statuszeile sagt es, und mit Netz geht sie hoch", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    one.mine.offline = true;

    await one.addGame("abend-1");
    await pass(500);
    expect(one.state.problem).toBe("OFFLINE");
    expect(status(one)).toBe("Kein Netz – 1 Änderung wartet");
    await one.startEvening("abend-2");
    await pass(500);
    expect(status(one)).toBe("Kein Netz – 2 Änderungen warten");

    one.mine.offline = false;
    one.controller.wake();
    await pass();
    expect(status(one)).toBe("Abgeglichen");
    expect(server.evenings(id)).toHaveLength(2);
  });

  it("ohne Netz und ohne Änderung heißt es nur „Kein Netz“; die Abstände wachsen", async () => {
    const one = await founder();
    one.mine.offline = true;
    await pass(4000);
    expect(status(one)).toBe("Kein Netz");
    one.mine.calls.length = 0;
    // Nach dem ersten Fehler 4 Sekunden, dann 8, dann 16.
    await pass(4000 + 8000 + 16_000);
    expect(one.mine.calls).toEqual(["head", "head", "head"]);
  });

  it("ein Fehler des Servers steht in der Statuszeile", async () => {
    const one = await founder();
    const head = one.mine.head.bind(one.mine);
    one.mine.head = async () => {
      throw new RoundException("SERVER", "HTTP 540");
    };
    await pass(4000);
    expect(one.state.problem).toBe("SERVER");
    expect(status(one)).toBe("Server nicht erreichbar");
    await one.addGame("abend-1");
    await pass(500);
    expect(status(one)).toBe("Server nicht erreichbar – 1 Änderung wartet");

    one.mine.head = head;
    one.controller.syncNow();
    await pass();
    expect(status(one)).toBe("Abgeglichen");
  });

  it("ist die Anmeldung abgelaufen, sagt es die Statuszeile; nach neuer Anmeldung geht es weiter", async () => {
    const one = await founder();
    one.mine.signedOut = true;
    await one.addGame("abend-1");
    await pass(500);

    expect(one.state.problem).toBe("SIGNED_OUT");
    expect(needsSignIn(one.state)).toBe(true);
    expect(status(one)).toBe("Anmeldung abgelaufen – bitte neu anmelden");

    // Die Sitzung ist weg, dann meldet sich dasselbe Konto neu an.
    one.auth.set(null);
    await pass();
    expect(needsSignIn(one.state)).toBe(true);
    one.session("konto-1").calls.length = 0;
    await pass(60_000);
    expect(one.session("konto-1").calls).toEqual([]);

    one.session("konto-1").signedOut = false;
    one.auth.set(account(1));
    await pass();
    expect(needsSignIn(one.state)).toBe(false);
    expect(status(one)).toBe("Abgeglichen");
    expect(server.evenings(one.state.link?.roundId ?? "")[0].entries).toHaveLength(2);
  });

  it("gibt es die Runde für das Konto nicht mehr, wird das Handy getrennt und behält seine Daten", async () => {
    const one = await founder();
    const two = await guest(one);
    server.removeMember(one.state.link?.roundId ?? "", "konto-2");

    await pass(4000);

    expect(two.state.link).toBeNull();
    expect(two.state.message).toBe("DETACHED");
    expect(two.store.getState().evenings).toHaveLength(1);
    expect(two.state.pending).toBe(0);
    // Die Meldung bleibt stehen, bis man sie wegtippt.
    await pass(8000);
    expect(two.state.message).toBe("DETACHED");
    two.controller.clearMessage();
    expect(two.state.message).toBeNull();
  });

  it("Abende, die diese Version nicht lesen kann, und solche, die der Server nicht annimmt, stehen in der Statuszeile", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";
    await one.startEvening("abend-2");
    await pass(500);
    server.makeUnreadable(id, "abend-2");

    await pass(4000);
    expect(two.state.unreadable).toBe(1);
    expect(status(two)).toBe("1 Abend kann diese Version nicht lesen – bitte die App aktualisieren");

    // Ein Abend, der dem Server zu groß ist.
    await two.startEvening("riesig");
    await two.store.updateEvening("riesig", (evening) => ({
      ...evening,
      entries: Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => ({
        kind: "thrown_in" as const,
        id: `wurf-${index}`,
        recordedAt: evening.startedAt,
      })),
    }));
    await pass(500);
    expect(two.state.refused).toBe(1);
    expect(two.state.pending).toBe(1);
    // Beide Warnungen bleiben stehen, auch wenn spätere Abgleiche die Abende nicht mehr anfassen.
    expect(two.state.unreadable).toBe(1);
    await two.startEvening("abend-3");
    await pass(4500);
    expect(two.state.unreadable).toBe(1);
    expect(two.state.refused).toBe(1);
    expect(status(two)).toBe("1 Abend kann diese Version nicht lesen – bitte die App aktualisieren");
  });

  it("Hinweise aus dem Abgleich sammeln sich (höchstens 20) und lassen sich ausblenden", async () => {
    const one = await founder();
    const two = await guest(one);
    await two.controller.claim("spieler-2", "Gast");
    await pass(4000);

    // Handy eins ist der Ersteller und darf den festen Namen korrigieren, Handy zwei einen fremden festen Namen nicht.
    await one.controller.claim("spieler-1", "Wirt");
    await pass(4000);
    two.store.updatePlayer("spieler-1", "Falsch", false);
    await pass(500);

    expect(two.state.notices).toEqual([{ player: "spieler-1", name: "Falsch", reason: "NAME_LOCKED", nowName: "Wirt" }]);
    expect(two.store.playerName("spieler-1")).toBe("Wirt");

    for (let round = 0; round < 25; round++) {
      two.store.updatePlayer("spieler-1", `Falsch ${round}`, false);
      await pass(500);
    }
    expect(two.state.notices).toHaveLength(20);
    expect(two.state.notices.at(-1)?.name).toBe("Falsch 24");

    two.controller.clearNotices();
    expect(two.state.notices).toEqual([]);
  });

  it("im Hintergrund ruht der Abgleich; beim Wiederkommen wird sofort nachgesehen", async () => {
    const one = await founder();
    const two = await guest(one);
    two.controller.setVisible(false);
    await pass();
    two.mine.calls.length = 0;

    const entry = await one.addGame("abend-1");
    await pass(60_000);
    expect(two.mine.calls).toEqual([]);
    expect(two.entryIds("abend-1")).not.toContain(entry);

    two.controller.setVisible(true);
    await pass();
    expect(two.entryIds("abend-1")).toContain(entry);
  });

  it("wer die App gleich nach dem Eintragen verlässt, lädt das Spiel trotzdem noch hoch", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    const entry = await one.addGame("abend-1");
    one.controller.setVisible(false);
    await pass();
    expect(server.evenings(id)[0].entries.map((it) => it.id)).toContain(entry);
  });

  it("lässt sich auf dem Gerät nicht speichern, merkt sich der Abgleich nichts und holt es später nach", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";
    const entry = await one.addGame("abend-1");
    await pass(500);

    // Der Speicher des zweiten Handys ist voll: Was vom Server kommt, lässt sich nicht ablegen. Das sagt die
    // Statuszeile so (und nicht „Server nicht erreichbar“).
    two.repository.failWrites = true;
    await pass(4000);
    expect(two.state.problem).toBe("STORAGE");
    expect(status(two)).toBe("Speichern auf diesem Gerät gescheitert – bitte die App neu laden");
    expect((await two.roundStore.eveningBases()).get("abend-1")?.evening.entries).toHaveLength(1);
    expect((await two.roundStore.link())?.cursor).toBeLessThan(server.rev(id));

    two.repository.failWrites = false;
    await pass(4000);
    expect(two.state.problem).toBeNull();
    expect(two.entryIds("abend-1")).toContain(entry);
    // Nach einem Neustart steht auf dem Gerät dasselbe wie in der Runde, und nichts sieht nach einer Löschung aus.
    const reloaded = await two.repository.load();
    expect(reloaded.evenings).toEqual(server.evenings(id));
    expect(await two.controller.pendingCount()).toBe(0);
    expect(server.evenings(id)[0].entries).toHaveLength(2);
  });

  it("speichert der Browser eine Eingabe nicht, sagt es die Statuszeile; hochgeladen wird erst, was auf dem Gerät liegt", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    one.repository.failWrites = true;
    await one.addGame("abend-1");
    await pass(500);

    expect(status(one)).toBe("Speichern auf diesem Gerät gescheitert – bitte die App neu laden");
    expect(server.evenings(id)[0].entries).toHaveLength(1);
    // Ohne Netz sagt die Statuszeile das (der Abgleich kommt dann gar nicht bis zum Speicher).
    one.mine.offline = true;
    one.controller.syncNow();
    await pass();
    expect(status(one)).toMatch(/^Kein Netz/);

    one.mine.offline = false;
    one.repository.failWrites = false;
    one.controller.syncNow();
    await pass();
    expect(status(one)).toBe("Abgeglichen");
    expect(server.evenings(id)[0].entries).toHaveLength(2);
  });
});

describe("Unlesbare Daten auf dem Gerät", () => {
  it("ist die Spielerliste unlesbar, ruht der Abgleich: kein Aufruf beim Server, nichts gelöscht, Statuszeile „Speichern …“", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";
    await one.session("konto-1").savePlayer(id, { id: "gast", name: "Gast", colorIndex: 4, archived: false });
    await pass(4000);
    expect(two.store.playerName("gast")).toBe("Gast");
    const players = two.repository.players;
    two.repository.players = "{kaputt";

    await two.restart();
    two.mine.calls.length = 0;
    two.controller.start(true);
    await pass(60_000);

    expect(two.state.problem).toBe("STORAGE");
    expect(status(two)).toBe("Speichern auf diesem Gerät gescheitert – bitte die App neu laden");
    expect(two.mine.calls).toEqual([]);
    expect(server.players(id).filter((it) => it.deleted)).toEqual([]);
    expect(two.repository.players).toBe("{kaputt");
    // Verlassen und Abmelden gehen trotzdem; die Rückfrage nennt den zuletzt bekannten Stand.
    expect(await two.controller.pendingCount()).toBe(0);
    // Verbinden mit anderen Daten geht nicht.
    await expect(two.controller.hasOwnData()).rejects.toMatchObject({ name: "UnreadableDataError" });

    // Mit lesbarer Spielerliste (zum Beispiel nach einem Update) geht es weiter, und nichts ist verloren.
    two.repository.players = players;
    await two.restart();
    two.controller.start(true);
    await pass();
    expect(status(two)).toBe("Abgeglichen");
    expect(two.store.playerName("gast")).toBe("Gast");
    expect(server.players(id).filter((it) => it.deleted)).toEqual([]);
  });

  it("sind die Einstellungen unlesbar, ruht der Abgleich, und das Regelwerk der Runde bleibt", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    const rules = (await one.session("konto-1").head(id))?.rules;
    one.repository.settings = "{kaputt";

    await one.restart();
    one.mine.calls.length = 0;
    one.controller.start(true);
    await pass(10_000);

    expect(status(one)).toBe("Speichern auf diesem Gerät gescheitert – bitte die App neu laden");
    expect(one.mine.calls).toEqual([]);
    expect((await one.session("konto-1").head(id))?.rules).toEqual(rules);
  });

  it("ein unlesbarer Abend bleibt in der Runde", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    const text = one.repository.evenings.get("abend-1") ?? "";
    one.repository.evenings.set("abend-1", text.replace(/"schemaVersion":\s*\d+/, '"schemaVersion":99'));

    await one.restart();
    one.controller.start(true);
    await pass(10_000);

    expect(server.eveningRow(id, "abend-1")?.deleted).toBe(false);
    expect(one.state.pending).toBe(0);
    expect(status(one)).toBe("Abgeglichen");
  });
});

describe("Einladen", () => {
  it("dieselbe Einladung wird wiederverwendet, solange sie jünger als 13 Tage ist", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    one.mine.calls.length = 0;

    const first = await one.controller.inviteLink();
    const second = await one.controller.inviteLink();
    expect(second).toBe(first);
    expect(one.mine.calls.filter((it) => it === "createInvite")).toHaveLength(1);
    expect(one.issued).toMatchObject({ roundId: id, token: InviteLinks.tokenFrom(first) });

    // Auch nach dem Neuladen der Seite.
    await one.reload();
    expect(await one.controller.inviteLink()).toBe(first);

    // Kurz vor 13 Tagen gilt sie noch, danach gibt es eine neue (gültig ist sie 14 Tage).
    vi.setSystemTime(Date.now() + 13 * 24 * 3600 * 1000 - 1000);
    expect(await one.controller.inviteLink()).toBe(first);
    vi.setSystemTime(Date.now() + 2000);
    const third = await one.controller.inviteLink();
    expect(third).not.toBe(first);
    expect(one.mine.calls.filter((it) => it === "createInvite")).toHaveLength(2);
  });

  it("wiederholtes Einladen stößt nicht an die Grenze von 20 gültigen Einladungen", async () => {
    const one = await founder();
    for (let index = 0; index < InMemoryRoundServer.MAX_INVITES + 5; index++) await one.controller.inviteLink();
    expect(one.mine.calls.filter((it) => it === "createInvite")).toHaveLength(1);
  });

  it("nach dem Verlassen oder Abmelden ist die gemerkte Einladung vergessen; eine andere Runde bekommt ihre eigene", async () => {
    const one = await founder();
    await one.controller.inviteLink();
    expect(one.issued).not.toBeNull();
    await one.controller.signOut();
    expect(one.issued).toBeNull();

    const two = await founder();
    const before = await two.controller.inviteLink();
    // Eine Einladung zu einer anderen Runde (zum Beispiel aus einem früheren Stand) gilt hier nicht.
    two.issued = { roundId: "andere-runde", token: InviteLinks.tokenFrom(before) ?? "", createdAt: Date.now() };
    const fresh = await two.controller.inviteLink();
    expect(fresh).not.toBe(before);
    await two.controller.leave();
    expect(two.issued).toBeNull();
  });
});

describe("Verlassen und Abmelden", () => {
  it("Runde verlassen: Das Konto ist draußen, die Daten bleiben als Kopie", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";

    await two.controller.leave();
    await pass();

    expect(two.state.link).toBeNull();
    expect(two.state.account).toEqual(account(2));
    expect(two.store.getState().evenings).toHaveLength(1);
    expect((await one.session("konto-1").members(id)).map((it) => it.userId)).toEqual(["konto-1"]);
    expect(two.state.message).toBeNull();
  });

  it("Abmelden trennt das Gerät von der Runde; in der Runde bleibt das Konto Mitglied", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";

    await two.controller.signOut();
    await pass();

    expect(two.state.link).toBeNull();
    expect(two.state.account).toBeNull();
    expect(needsSignIn(two.state)).toBe(false);
    expect(two.store.getState().evenings).toHaveLength(1);
    expect((await one.session("konto-1").members(id)).map((it) => it.userId)).toEqual(["konto-1", "konto-2"]);
  });

  it("ohne Netz lässt sich die Runde nicht verlassen; abmelden geht trotzdem", async () => {
    const one = await founder();
    one.mine.offline = true;
    await expect(one.controller.leave()).rejects.toMatchObject({ error: "OFFLINE" });
    expect(one.state.link).not.toBeNull();

    await one.controller.signOut();
    expect(one.state.link).toBeNull();
    expect(one.state.account).toBeNull();
  });

  it("wie viele Änderungen noch warten, lässt sich vor dem Verlassen erfragen", async () => {
    const one = await founder();
    one.mine.offline = true;
    await one.addGame("abend-1");
    expect(await one.controller.pendingCount()).toBe(1);
  });

  it("eine Runde des Kontos, die nicht verbunden ist, lässt sich verlassen; auf dem Gerät ändert sich nichts", async () => {
    const one = await founder();
    const two = await guest(one);
    const id = one.state.link?.roundId ?? "";
    await two.controller.signOut();
    two.auth.set(account(2));
    await pass();
    const evenings = two.store.getState().evenings;

    await two.controller.leaveRound(id);

    expect((await one.session("konto-1").members(id)).map((it) => it.userId)).toEqual(["konto-1"]);
    expect(await two.controller.myRounds()).toEqual([]);
    expect(two.store.getState().evenings).toBe(evenings);
    // Das letzte Mitglied löscht die Runde.
    await one.controller.signOut();
    one.auth.set(account(1));
    await pass();
    await one.controller.leaveRound(id);
    expect(await one.session("konto-1").head(id)).toBeNull();
  });
});

describe("mehrere Fenster", () => {
  it("hat ein anderes Fenster die Daten geändert, wird neu gelesen, während kein Abgleich läuft", async () => {
    const one = await founder();
    let reloads = 0;
    await one.controller.changedElsewhere(async () => {
      reloads++;
      await one.roundStore.setLink(null);
    });
    await pass();
    expect(reloads).toBe(1);
    expect(one.state.link).toBeNull();
  });
});
