/*
 * Die Logik des Bildschirms „Gemeinsame Runde“ (in der Android-App `RoundViewModel`): Runden und Einladung nachsehen,
 * Rückfragen, Fehler, gesperrte Knöpfe. Mit nachgestellten Handys an einem Server im Speicher.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { InviteLinks } from "../core/round/InviteLinks";
import { RoundException, switchFrom, type RoundSummary } from "../core/round/RoundModels";
import { needsSignIn } from "./controller";
import type { RoundScreenModel } from "./screenModel";
import { account, Phone, WEB_APP_URL } from "./testing/phone";
import { roundErrorText, roundStatus } from "./texts";

let server: InMemoryRoundServer;
const phones: Phone[] = [];

async function pass(millis = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(millis);
}

async function phone(name: string, signedIn: number | null = null): Promise<Phone> {
  const created = await Phone.create(server, name, signedIn === null ? null : account(signedIn));
  phones.push(created);
  created.controller.start(true);
  await pass();
  return created;
}

/** Handy eins mit einer Runde „Stammtisch“ und einem Abend. */
async function founder(): Promise<Phone> {
  const one = await phone("eins", 1);
  await one.startEvening("abend-1");
  await one.addGame("abend-1");
  await one.controller.create("Stammtisch");
  await pass();
  return one;
}

async function inviteToken(one: Phone): Promise<string> {
  const token = InviteLinks.tokenFrom(await one.controller.inviteLink());
  if (token === null) throw new Error("kein Code im Einladungslink");
  return token;
}

/**
 * Eine zweite Runde „Klub“, die Konto 2 auf seinem Handy mit einem eigenen Abend anlegt; Konto 1 tritt ihr über den
 * Server bei (wie von einem anderen Gerät aus).
 */
async function club(): Promise<Phone> {
  const two = await phone("zwei", 2);
  await two.startEvening("abend-klub");
  await two.addGame("abend-klub");
  await two.controller.create("Klub");
  await pass();
  await server.sessionFor("konto-1").joinRound(await two.mine.createInvite(two.state.link?.roundId ?? ""));
  return two;
}

/** Die Runde `name` aus der Liste der Runden eines Handys. */
function roundNamed(of: Phone, name: string): RoundSummary {
  const round = of.state.rounds?.find((it) => it.name === name);
  if (round === undefined) throw new Error(`keine Runde ${name} in der Liste`);
  return round;
}

/** Die Statuszeile eines Handys. */
function statusOf(of: Phone): string {
  return roundStatus({ ...of.state, needsSignIn: needsSignIn(of.state) }).text;
}

/** Der Bildschirm eines Handys, nachdem alles Angestoßene fertig ist. */
async function screenOf(of: Phone): Promise<RoundScreenModel> {
  const screen = of.screen();
  await pass();
  return screen;
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

describe("Angemeldet, ohne Runde", () => {
  it("zeigt die Runden des Kontos", async () => {
    await founder();
    const other = await phone("neues-handy", 1);

    const screen = await screenOf(other);

    expect(other.state.rounds).toEqual([
      { id: expect.any(String), name: "Stammtisch", rev: expect.any(Number), memberCount: 1, myPlayerId: null, isCreator: true, memberNames: [] },
    ]);
    expect(screen.getUi().invite).toBeNull();
  });

  it("ließen sich die Runden nicht laden, sagt der Bildschirm es; „Erneut versuchen“ holt sie", async () => {
    await founder();
    const other = await Phone.create(server, "neues-handy", account(1));
    phones.push(other);
    other.session("konto-1").offline = true;
    other.controller.start(true);
    await pass();

    const screen = await screenOf(other);

    // Sonst sähe es auf einem neuen Handy aus, als gäbe es keine Runde.
    expect(other.state.roundsFailed).toBe(true);
    expect(other.state.rounds).toBeNull();
    other.session("konto-1").offline = false;
    const retrying = screen.retry();
    expect(screen.getUi().busy).toBe(true);
    await retrying;
    expect(screen.getUi().busy).toBe(false);
    expect(other.state.roundsFailed).toBe(false);
    expect(other.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);

    // Scheitert es später noch einmal, bleibt die bekannte Liste stehen.
    other.session("konto-1").offline = true;
    await screen.retry();
    expect(other.state.roundsFailed).toBe(true);
    expect(other.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);
  });

  it("ohne Anmeldung wird nichts nachgesehen", async () => {
    const one = await phone("eins");
    await screenOf(one);
    expect(one.state.rounds).toBeNull();
    expect(one.state.roundsFailed).toBe(false);
    expect(one.backendLoads).toBe(1);
  });

  it("zeigt, was die wartende Einladung verrät", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.controller.rememberInvite(await inviteToken(one));

    const screen = await screenOf(two);

    expect(screen.getUi().invite).toEqual({ roundId: one.state.link?.roundId, roundName: "Stammtisch", memberCount: 1, alreadyMember: false });
    expect(two.state.rounds).toEqual([]);
  });

  it("eine abgelaufene Einladung wird gemeldet und vergessen", async () => {
    const two = await phone("zwei", 2);
    two.controller.rememberInvite("0123456789abcdef0123456789abcdef");

    const screen = await screenOf(two);

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "INVALID_INVITE", roundName: false });
    expect(two.state.pendingInvite).toBeNull();
    expect(two.invite).toBeNull();
    screen.clearError();
    expect(screen.getUi().error).toBeNull();
  });

  it("ohne Netz bleibt die Einladung stehen", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.session("konto-2").offline = true;
    two.controller.rememberInvite(await inviteToken(one));

    const screen = await screenOf(two);

    expect(screen.getUi().invite).toBeNull();
    expect(screen.getUi().error).toBeNull();
    expect(two.state.pendingInvite).not.toBeNull();
  });

  it("„Einladung verwerfen“ vergisst den Code", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);

    screen.discardInvite();
    await pass();

    expect(two.state.pendingInvite).toBeNull();
    expect(screen.getUi().invite).toBeNull();
  });

  it("kommt eine andere Einladung, gilt bis zum Nachsehen nichts mehr von der vorigen", async () => {
    const one = await founder();
    const other = await phone("drei", 3);
    await other.controller.create("Klub");
    await pass();
    const two = await phone("zwei", 2);
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);
    expect(screen.getUi().invite?.roundName).toBe("Stammtisch");

    two.controller.rememberInvite(await inviteToken(other));
    expect(screen.getUi().invite).toBeNull();
    await pass();

    expect(screen.getUi().invite?.roundName).toBe("Klub");
  });

  it("nach dem Anmelden werden Runden und Einladung von selbst nachgesehen", async () => {
    const one = await founder();
    const two = await phone("zwei");
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);
    expect(screen.getUi().invite).toBeNull();

    two.auth.set(account(2));
    await pass();

    expect(screen.getUi().invite?.roundName).toBe("Stammtisch");
    expect(two.state.rounds).toEqual([]);
  });

  it("„Verlassen“ bei einer Runde des Kontos: erst die Rückfrage, dann ist sie aus der Liste; auf dem Gerät ändert sich nichts", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    const two = await phone("zwei", 2);
    await two.screen().redeem(await inviteToken(one));
    await pass();
    await two.controller.signOut();
    two.auth.set(account(2));
    await pass();
    await two.startEvening("eigener-abend");
    const screen = await screenOf(two);
    const [round] = two.state.rounds ?? [];
    expect(round).toMatchObject({ id, name: "Stammtisch", memberCount: 2 });

    // „Abbrechen“ lässt alles, wie es ist.
    screen.askLeave(round);
    expect(screen.getUi().leaving).toEqual(round);
    screen.cancelLeave();
    expect(screen.getUi().leaving).toBeNull();
    expect(await two.controller.myRounds()).toHaveLength(1);

    screen.askLeave(round);
    await screen.confirmLeave();
    expect(screen.getUi().leaving).toBeNull();
    expect(screen.getUi().error).toBeNull();
    expect(two.state.rounds).toEqual([]);
    expect((await one.session("konto-1").members(id)).map((it) => it.userId)).toEqual(["konto-1"]);
    expect(two.store.getState().evenings.map((it) => it.id)).toContain("eigener-abend");
    expect(two.state.link).toBeNull();
  });

  it("verlässt das letzte Mitglied eine Runde, ist sie auf dem Server gelöscht; ohne Netz bleibt sie in der Liste", async () => {
    const one = await founder();
    const id = one.state.link?.roundId ?? "";
    const other = await phone("neues-handy", 1);
    const screen = await screenOf(other);
    const [round] = other.state.rounds ?? [];
    expect(round.memberCount).toBe(1);

    other.mine.offline = true;
    screen.askLeave(round);
    await screen.confirmLeave();
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: false });
    expect(other.state.rounds).toEqual([round]);

    other.mine.offline = false;
    screen.askLeave(round);
    await screen.confirmLeave();
    expect(other.state.rounds).toEqual([]);
    expect(await one.session("konto-1").head(id)).toBeNull();
  });

  it("ein geschlossener Bildschirm beobachtet nichts mehr", async () => {
    const two = await phone("zwei");
    const screen = await screenOf(two);
    let calls = 0;
    screen.subscribe(() => calls++);
    screen.dispose();

    two.auth.set(account(2));
    await pass();

    expect(calls).toBe(0);
    expect(two.session("konto-2").calls).toEqual([]);
  });
});

describe("Runde anlegen", () => {
  it("legt die Runde an; währenddessen sind die Knöpfe gesperrt", async () => {
    const one = await phone("eins", 1);
    const screen = await screenOf(one);
    const seen: boolean[] = [];
    screen.subscribe(() => seen.push(screen.getUi().busy));

    const running = screen.create("Stammtisch");
    expect(screen.getUi().busy).toBe(true);
    // Ein zweites Tippen währenddessen tut nichts.
    await screen.create("Noch eine");
    await running;

    expect(screen.getUi().busy).toBe(false);
    expect(seen).toContain(true);
    expect(one.state.link?.roundName).toBe("Stammtisch");
    expect(await one.controller.myRounds()).toHaveLength(1);
    // Nach dem Anlegen ist die Liste der Runden neu gelesen (für „Deine anderen Runden“ und die Runden-Auswahl).
    expect(one.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);
  });

  it("ein unbrauchbarer Name der Runde: Fehler mit dem Hinweis auf 1 bis 40 Zeichen", async () => {
    const one = await phone("eins", 1);
    const screen = await screenOf(one);

    await screen.create("x".repeat(41));

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "INVALID_NAME", roundName: true });
    expect(roundErrorText("INVALID_NAME", true)).toBe("Bitte einen Namen mit 1 bis 40 Zeichen eingeben.");
    expect(one.state.link).toBeNull();
  });

  it("ohne Netz steht die Runde nicht; der Fehler sagt es", async () => {
    const one = await phone("eins", 1);
    one.mine.offline = true;
    const screen = await screenOf(one);

    await screen.create("Stammtisch");

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: true });
    expect(one.state.link).toBeNull();
  });

  it("scheitert nach dem Anlegen nur der Abgleich, ist die Runde trotzdem angelegt; die Statuszeile sagt es", async () => {
    const one = await phone("eins", 1);
    await one.startEvening("abend-1");
    const screen = await screenOf(one);
    const head = one.mine.head.bind(one.mine);
    one.mine.head = async () => {
      throw new RoundException("OFFLINE");
    };

    await screen.create("Stammtisch");

    expect(screen.getUi().error).toBeNull();
    expect(one.state.link?.roundName).toBe("Stammtisch");
    expect(statusOf(one)).toBe("Kein Netz – 6 Änderungen warten");
    // Die App holt den Abgleich von selbst nach.
    one.mine.head = head;
    await pass(4000);
    expect(statusOf(one)).toBe("Abgeglichen");
    expect(server.evenings(one.state.link?.roundId ?? "")).toHaveLength(1);
  });
});

describe("Beitreten und Verwenden", () => {
  it("ein eingefügter Text ohne Code ist kein Einladungslink", async () => {
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);

    await screen.redeem("Komm doch am Freitag!");

    expect(screen.getUi().error).toEqual({ kind: "NoCode" });
    expect(two.session("konto-2").calls.filter((it) => it === "joinRound")).toEqual([]);
  });

  it("ohne eigene Daten tritt das Handy sofort bei, auch aus einer ganzen Nachricht heraus", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);
    const link = await one.controller.inviteLink();

    await screen.redeem(`Komm in unsere Schafkopfrunde Stammtisch: ${link}`);
    await pass();

    expect(screen.getUi().replace).toBeNull();
    expect(screen.getUi().error).toBeNull();
    expect(two.state.link?.roundName).toBe("Stammtisch");
    expect(two.store.getState().evenings).toEqual(one.store.getState().evenings);
  });

  it("mit eigenen Abenden kommt erst die Rückfrage mit ihrer Zahl", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    await two.startEvening("eigener-1");
    await two.startEvening("eigener-2");
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);

    await screen.joinInvited();

    expect(screen.getUi().replace).toEqual({ kind: "join", token: two.state.pendingInvite });
    expect(screen.getUi().eveningCount).toBe(2);
    expect(two.state.link).toBeNull();

    // „Abbrechen“ lässt alles, wie es ist.
    screen.cancelReplace();
    expect(screen.getUi().replace).toBeNull();
    expect(two.store.getState().evenings).toHaveLength(2);

    await screen.joinInvited();
    await screen.confirmReplace();
    await pass();
    expect(two.state.link?.roundName).toBe("Stammtisch");
    expect(two.store.getState().evenings.map((it) => it.id)).toEqual(["abend-1"]);
    expect(two.state.pendingInvite).toBeNull();
  });

  it("nur umbenannte Spieler, keine Abende: Rückfrage ohne Zahl", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.store.updatePlayer("spieler-1", "Gast", false);
    const screen = await screenOf(two);

    await screen.redeem(await inviteToken(one));

    expect(screen.getUi().replace).not.toBeNull();
    expect(screen.getUi().eveningCount).toBe(0);
  });

  it("eine Runde des Kontos auf einem neuen Handy verwenden", async () => {
    const one = await founder();
    const other = await phone("neues-handy", 1);
    const screen = await screenOf(other);
    const [round] = other.state.rounds ?? [];

    await screen.use(round);
    await pass();

    expect(other.state.link?.roundId).toBe(one.state.link?.roundId);
    expect(other.store.getState().evenings).toEqual(one.store.getState().evenings);
  });

  it("antwortet der Server beim Beitreten nicht, bleibt das Gerät, wie es ist", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    await two.startEvening("eigener-1");
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);
    await screen.joinInvited();
    expect(screen.getUi().replace).not.toBeNull();
    // Die Einladung lässt sich noch annehmen, dann reißt die Verbindung ab.
    const head = two.mine.head.bind(two.mine);
    two.mine.head = async () => {
      throw new RoundException("OFFLINE");
    };

    await screen.confirmReplace();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: false });
    expect(two.state.link).toBeNull();
    expect(two.store.getState().evenings.map((it) => it.id)).toEqual(["eigener-1"]);
    // Mit Netz klappt es, und erst dann werden die Daten des Geräts ersetzt.
    two.mine.head = head;
    await screen.joinInvited();
    await screen.confirmReplace();
    await pass();
    expect(screen.getUi().error).toBeNull();
    expect(two.state.link?.roundName).toBe("Stammtisch");
    expect(two.store.getState().evenings.map((it) => it.id)).toEqual(["abend-1"]);
  });

  it("ist das Gerät verbunden, während „Daten dieses Geräts ersetzen?“ offen ist, wird beim Bestätigen nicht gewechselt", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    await two.startEvening("eigener-1");
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);
    await screen.joinInvited();
    expect(screen.getUi().replace).not.toBeNull();

    // Zum Beispiel in einem anderen Fenster: eine eigene Runde, die die Abende dieses Geräts mitnimmt.
    await two.controller.create("Freitag");
    await screen.confirmReplace();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "CHANGED_MEANWHILE", roundName: false });
    expect(two.state.link?.roundName).toBe("Freitag");
    expect(two.store.getState().evenings.map((it) => it.id)).toEqual(["eigener-1"]);
    expect(roundErrorText("CHANGED_MEANWHILE")).toBe("Inzwischen hat sich auf diesem Gerät etwas geändert. Bitte noch einmal versuchen.");
  });

  it("eine ungültige Einladung beim Einlösen: Fehlertext, nichts ersetzt", async () => {
    const two = await phone("zwei", 2);
    await two.startEvening("eigener-1");
    const screen = await screenOf(two);

    await screen.redeem("0123456789abcdef0123456789abcdef");
    await screen.confirmReplace();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "INVALID_INVITE", roundName: false });
    expect(two.store.getState().evenings).toHaveLength(1);
    expect(two.state.link).toBeNull();
  });
});

describe("Verbunden", () => {
  it("„Das bin ich“ legt den Namen fest; ein vergebener Name wird gemeldet", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);
    await screen.redeem(await inviteToken(one));
    await pass();

    await screen.claim("spieler-2", "Spieler 1");
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "NAME_TAKEN", roundName: false });
    expect(two.state.link?.playerId).toBeNull();

    await screen.claim("spieler-2", "Gast");
    expect(screen.getUi().error).toBeNull();
    expect(two.state.link?.playerId).toBe("spieler-2");
    expect(two.store.playerName("spieler-2")).toBe("Gast");
  });

  it("„Das bin ich“ gilt, sobald der Server es angenommen hat, auch wenn der Abgleich danach scheitert", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);
    await screen.redeem(await inviteToken(one));
    await pass();
    const head = two.mine.head.bind(two.mine);
    two.mine.head = async () => {
      throw new RoundException("OFFLINE");
    };

    await screen.claim("spieler-2", "Gast");

    expect(screen.getUi().error).toBeNull();
    expect(server.players(two.state.link?.roundId ?? "").find((it) => it.id === "spieler-2")).toMatchObject({ name: "Gast", owner: "konto-2" });
    expect(statusOf(two)).toBe("Kein Netz");
    two.mine.head = head;
    await pass(4000);
    expect(two.state.link?.playerId).toBe("spieler-2");
    expect(two.store.playerName("spieler-2")).toBe("Gast");
  });

  it("„Ich bin neu“ legt einen neuen Spieler an", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);
    await screen.redeem(await inviteToken(one));
    await pass();

    await screen.claim(null, "Neuling");
    await pass(4000);

    const mine = two.state.link?.playerId ?? "";
    expect(two.store.playerName(mine)).toBe("Neuling");
    expect(one.store.getState().players.map((it) => it.name)).toEqual(["Spieler 1", "Spieler 2", "Spieler 3", "Spieler 4", "Neuling"]);
  });

  it("„Einladen“ erzeugt einen Link auf die Einladungsseite", async () => {
    const one = await founder();
    const screen = await screenOf(one);

    await screen.invite();

    const link = screen.getUi().shareLink ?? "";
    expect(link.startsWith(`${WEB_APP_URL}#/einladung/`)).toBe(true);
    expect(InviteLinks.tokenFrom(link)).toHaveLength(32);
    screen.shared();
    expect(screen.getUi().shareLink).toBeNull();
  });

  it("vor dem Verlassen wird gefragt und gesagt, was noch wartet", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    const screen = await screenOf(two);
    await screen.redeem(await inviteToken(one));
    await pass();
    two.mine.offline = true;
    await two.addGame("abend-1");

    await screen.askFarewell("LEAVE");
    expect(screen.getUi().farewell).toBe("LEAVE");
    expect(screen.getUi().farewellPending).toBe(1);

    screen.cancelFarewell();
    expect(screen.getUi().farewell).toBeNull();
    expect(two.state.link).not.toBeNull();

    // Ohne Netz lässt sich die Runde nicht verlassen.
    await screen.askFarewell("LEAVE");
    await screen.confirmFarewell();
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: false });
    expect(two.state.link).not.toBeNull();

    two.mine.offline = false;
    await screen.askFarewell("LEAVE");
    await screen.confirmFarewell();
    await pass();
    expect(two.state.link).toBeNull();
    expect(two.state.account).toEqual(account(2));
    expect(two.store.getState().evenings).toHaveLength(1);
  });

  it("„Abmelden“ trennt das Gerät und meldet ab", async () => {
    const one = await founder();
    const screen = await screenOf(one);

    await screen.askFarewell("SIGN_OUT");
    expect(screen.getUi().farewellPending).toBe(0);
    await screen.confirmFarewell();
    await pass();

    expect(one.state.link).toBeNull();
    expect(one.state.account).toBeNull();
    expect(one.store.getState().evenings).toHaveLength(1);
    expect(one.state.rounds).toBeNull();
  });

  it("„Mit Google anmelden“ beginnt die Anmeldung", async () => {
    const one = await phone("eins");
    const screen = await screenOf(one);
    await screen.signIn();
    expect(one.auth.signInsStarted).toBe(1);
    expect(screen.getUi().error).toBeNull();
  });

  it("lassen sich Spielerliste oder Einstellungen auf dem Gerät nicht lesen, sagt der Fehler es, statt beizutreten", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.repository.players = "{kaputt";
    await two.restart();
    two.controller.start(true);
    await pass();
    const screen = await screenOf(two);

    await screen.redeem(await inviteToken(one));

    expect(screen.getUi().error).toEqual({ kind: "Storage" });
    expect(two.state.link).toBeNull();
    expect(two.session("konto-2").calls.filter((it) => it === "joinRound")).toEqual([]);
  });

  it("lässt sich die Anbindung nicht laden, sagt der Fehler „keine Verbindung“", async () => {
    const one = await Phone.create(server, "eins");
    phones.push(one);
    one.backendFails = true;
    one.controller.start(true);
    await pass();
    const screen = await screenOf(one);

    await screen.signIn();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: false });
    expect(roundErrorText("OFFLINE")).toBe("Keine Verbindung. Bitte später noch einmal versuchen.");
  });
});

describe("Mit verbundener Runde: wechseln, beitreten, anlegen", () => {
  it("„Wechseln“: erst vollständig abgleichen, dann die Rückfrage; „Abbrechen“ lässt alles, wie es ist", async () => {
    const one = await founder();
    const stammtisch = one.state.link?.roundId ?? "";
    const two = await club();
    const screen = await screenOf(one);
    const klub = roundNamed(one, "Klub");
    await one.addGame("abend-1");

    const asking = screen.use(klub);
    // Solange abgeglichen wird, steht über allem „Einen Moment …“.
    expect(screen.getUi().busy).toBe(true);
    expect(one.state.switching).toBe(true);
    await asking;

    expect(one.state.switching).toBe(false);
    expect(screen.getUi().switching).toEqual({ target: { kind: "use", round: klub }, from: switchFrom(stammtisch, 0), fromName: "Stammtisch" });
    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
    expect(one.state.link?.roundId).toBe(stammtisch);
    screen.cancelSwitch();
    expect(screen.getUi().switching).toBeNull();
    expect(one.state.link?.roundId).toBe(stammtisch);

    await screen.use(klub);
    const confirming = screen.confirmSwitch();
    expect(one.state.switching).toBe(true);
    await confirming;
    await pass();

    expect(one.state.switching).toBe(false);
    expect(screen.getUi().error).toBeNull();
    expect(one.state.link?.roundName).toBe("Klub");
    expect(one.store.getState().evenings).toEqual(two.store.getState().evenings);
    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
  });

  it("die Rückfrage nennt, was nach dem Abgleich noch wartet und beim Wechseln verloren ginge", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    one.mine.saveEvening = async () => {
      throw new RoundException("TOO_LARGE");
    };
    await one.addGame("abend-1");

    await screen.use(roundNamed(one, "Klub"));

    expect(screen.getUi().switching?.from).toEqual(switchFrom(one.state.link?.roundId ?? "", 1));
  });

  it("ohne Netz kommt statt der Rückfrage der Fehlertext, und es bleibt alles, wie es ist", async () => {
    const one = await founder();
    const stammtisch = one.state.link?.roundId ?? "";
    await club();
    const screen = await screenOf(one);
    one.mine.offline = true;

    await screen.use(roundNamed(one, "Klub"));

    expect(screen.getUi().switching).toBeNull();
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: false });
    expect(one.state.link?.roundId).toBe(stammtisch);
    expect(one.store.getState().evenings.map((it) => it.id)).toEqual(["abend-1"]);
  });

  it("gibt es die verbundene Runde nicht mehr, kommt statt der Rückfrage die Meldung nach `Detached`", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    server.removeMember(one.state.link?.roundId ?? "", "konto-1");

    await screen.use(roundNamed(one, "Klub"));

    expect(screen.getUi().switching).toBeNull();
    expect(screen.getUi().error).toBeNull();
    expect(one.state.message).toBe("DETACHED");
    expect(one.state.link).toBeNull();
    expect(one.store.getState().evenings.map((it) => it.id)).toEqual(["abend-1"]);
  });

  it("hat sich das Gerät seit der Rückfrage geändert, wird nicht gewechselt", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    await screen.use(roundNamed(one, "Klub"));
    expect(screen.getUi().switching).not.toBeNull();

    // Zwischen Rückfrage und Bestätigung wird das Gerät getrennt (etwa abgemeldet in einem anderen Fenster).
    await one.controller.signOut();
    await pass();

    // Die Rückfrage bleibt, bis man sie beantwortet; „Wechseln“ scheitert dann, und das Gerät bleibt, wie es ist.
    expect(screen.getUi().switching).not.toBeNull();
    await screen.confirmSwitch();
    await pass();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "SIGNED_OUT", roundName: false });
    expect(one.state.link).toBeNull();
    expect(one.store.getState().evenings.map((it) => it.id)).toEqual(["abend-1"]);
  });

  it("ist das Gerät seit der Rückfrage mit einer anderen Runde verbunden, wird nicht gewechselt", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    await screen.use(roundNamed(one, "Klub"));
    // Inzwischen ist das Gerät (etwa in einem anderen Fenster) in eine neue Runde gewechselt.
    await one.controller.create("Dienstag");

    await screen.confirmSwitch();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "CHANGED_MEANWHILE", roundName: false });
    expect(one.state.link?.roundName).toBe("Dienstag");
  });

  it("solange der Wechsel vorbereitet wird, ist alles gesperrt; ein zweiter Anstoß zählt nicht", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    one.mine.calls.length = 0;

    const asking = screen.use(roundNamed(one, "Klub"));
    expect(screen.getUi().busy).toBe(true);
    expect(one.state.switching).toBe(true);
    await screen.create("Noch eine");
    await screen.use(roundNamed(one, "Stammtisch"));
    await asking;

    expect(one.state.switching).toBe(false);
    expect(screen.getUi().switching?.target).toEqual({ kind: "use", round: roundNamed(one, "Klub") });
    expect(one.mine.calls).not.toContain("createRound");
  });

  it("wird der Bildschirm mitten im Wechsel geschlossen, läuft der Wechsel trotzdem zu Ende", async () => {
    const one = await founder();
    const two = await club();
    const screen = await screenOf(one);
    await screen.use(roundNamed(one, "Klub"));

    const confirming = screen.confirmSwitch();
    screen.dispose();
    await confirming;
    await pass();

    expect(one.state.link?.roundName).toBe("Klub");
    expect(one.store.getState().evenings).toEqual(two.store.getState().evenings);
    expect(one.state.switching).toBe(false);
  });

  it("eine Einladung in die verbundene Runde: „Du bist schon in dieser Runde.“, und die Einladung ist vergessen", async () => {
    const one = await founder();
    const token = await inviteToken(one);
    one.controller.rememberInvite(token);
    const screen = await screenOf(one);
    expect(screen.getUi().invite?.roundName).toBe("Stammtisch");

    await screen.joinInvited();

    expect(screen.getUi().message).toBe("ALREADY_IN_ROUND");
    expect(screen.getUi().switching).toBeNull();
    expect(one.state.pendingInvite).toBeNull();
    expect(one.invite).toBeNull();
    expect(one.mine.calls).not.toContain("joinRound");
    screen.clearMessage();
    expect(screen.getUi().message).toBeNull();

    // Dasselbe mit dem eingefügten Code.
    await screen.redeem(`Komm in unsere Schafkopfrunde Stammtisch: ${await one.controller.inviteLink()}`);
    expect(screen.getUi().message).toBe("ALREADY_IN_ROUND");
  });

  it("eine Einladung in eine andere Runde: Rückfrage „Der Runde ‹Ziel› beitreten?“ mit dem Namen aus der Einladung", async () => {
    const two = await phone("zwei", 2);
    await two.startEvening("abend-freitag");
    await two.controller.create("Freitag");
    await pass();
    const one = await founder();
    const stammtisch = one.state.link?.roundId ?? "";
    const token = await inviteToken(two);
    one.controller.rememberInvite(token);
    const screen = await screenOf(one);

    await screen.joinInvited();

    expect(screen.getUi().switching).toEqual({
      target: { kind: "join", token, roundName: "Freitag" },
      from: switchFrom(stammtisch, 0),
      fromName: "Stammtisch",
    });
    await screen.confirmSwitch();
    await pass();

    expect(one.state.link?.roundName).toBe("Freitag");
    expect(one.state.pendingInvite).toBeNull();
    expect(one.store.getState().evenings.map((it) => it.id)).toEqual(["abend-freitag"]);
    expect(server.evenings(stammtisch)).toHaveLength(1);
    // Die Liste ist nach dem Beitreten neu gelesen (der Server im Speicher ordnet nach dem Anlegen der Runden).
    expect(one.state.rounds?.map((it) => it.name).sort()).toEqual(["Freitag", "Stammtisch"]);
  });

  it("ist die Einladung beim Beitreten mit verbundener Runde inzwischen abgelaufen: Fehlertext, die Einladung ist vergessen", async () => {
    const two = await phone("zwei", 2);
    await two.controller.create("Freitag");
    await pass();
    const one = await founder();
    one.controller.rememberInvite(await inviteToken(two));
    const screen = await screenOf(one);
    expect(screen.getUi().invite?.roundName).toBe("Freitag");
    // Zwischen dem Öffnen und dem Antippen läuft sie ab.
    one.mine.inviteInfo = async () => {
      throw new RoundException("INVALID_INVITE");
    };

    await screen.joinInvited();

    expect(screen.getUi().error).toEqual({ kind: "Server", error: "INVALID_INVITE", roundName: false });
    expect(one.state.pendingInvite).toBeNull();
    expect(screen.getUi().switching).toBeNull();
    expect(one.state.link?.roundName).toBe("Stammtisch");
  });

  it("neue Runde anlegen: erst der Name, dann die Rückfrage; danach beginnt die neue Runde leer", async () => {
    const one = await founder();
    const stammtisch = one.state.link?.roundId ?? "";
    const screen = await screenOf(one);

    await screen.create("  Neue \t Runde ");

    expect(screen.getUi().switching).toEqual({
      target: { kind: "create", name: "Neue Runde" },
      from: switchFrom(stammtisch, 0),
      fromName: "Stammtisch",
    });
    await screen.confirmSwitch();
    await pass();

    expect(screen.getUi().error).toBeNull();
    expect(one.state.link).toMatchObject({ roundName: "Neue Runde", isCreator: true });
    expect(one.store.getState().evenings).toEqual([]);
    expect(server.evenings(stammtisch)).toHaveLength(1);
  });

  it("ein Name, den das Konto schon hat, wird vor der Rückfrage gemeldet; ebenso ein zu langer", async () => {
    const one = await founder();
    await club();
    const screen = await screenOf(one);
    one.mine.calls.length = 0;

    await screen.create(" stammTISCH ");
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "NAME_TAKEN", roundName: true });
    expect(roundErrorText("NAME_TAKEN", true)).toBe("Du hast schon eine Runde mit diesem Namen.");
    // Auch der Name einer beigetretenen Runde ist vergeben.
    await screen.create("klub");
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "NAME_TAKEN", roundName: true });
    await screen.create("x".repeat(41));
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "INVALID_NAME", roundName: true });

    expect(screen.getUi().switching).toBeNull();
    expect(one.state.link?.roundName).toBe("Stammtisch");
    expect(one.mine.calls).not.toContain("createRound");
  });

  it("die eigene leere Runde eines Versuchs, dessen Antwort verloren ging, gilt beim Prüfen des Namens nicht als vergeben", async () => {
    const one = await phone("eins", 1);
    await one.startEvening("abend-1");
    const screen = await screenOf(one);
    one.mine.loseNextAnswer = true;
    await screen.create("Stammtisch");
    expect(screen.getUi().error).toEqual({ kind: "Server", error: "OFFLINE", roundName: true });
    expect(one.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);

    await screen.create("Stammtisch");

    expect(screen.getUi().error).toBeNull();
    expect(one.state.link?.roundName).toBe("Stammtisch");
    expect(await one.controller.myRounds()).toHaveLength(1);
    expect(server.evenings(one.state.link?.roundId ?? "")).toHaveLength(1);
  });

  it("ohne verbundene Runde gibt es keine Rückfrage vor dem Wechseln: Verwenden fragt wie bisher nach dem Ersetzen", async () => {
    await founder();
    const other = await phone("neues-handy", 1);
    await other.startEvening("eigener");
    const screen = await screenOf(other);

    await screen.use(roundNamed(other, "Stammtisch"));

    expect(screen.getUi().switching).toBeNull();
    expect(screen.getUi().replace?.kind).toBe("use");
  });
});
