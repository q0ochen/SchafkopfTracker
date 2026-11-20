/*
 * Die Logik des Bildschirms „Gemeinsame Runde“ (in der Android-App `RoundViewModel`): Runden und Einladung nachsehen,
 * Rückfragen, Fehler, gesperrte Knöpfe. Mit nachgestellten Handys an einem Server im Speicher.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { InviteLinks } from "../core/round/InviteLinks";
import type { RoundScreenModel } from "./screenModel";
import { account, Phone, WEB_APP_URL } from "./testing/phone";
import { roundErrorText } from "./texts";

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

    expect(screen.getUi().rounds).toEqual([
      { id: expect.any(String), name: "Stammtisch", rev: expect.any(Number), memberCount: 1, myPlayerId: null, isCreator: true },
    ]);
    expect(screen.getUi().invite).toBeNull();
  });

  it("ohne Anmeldung wird nichts nachgesehen", async () => {
    const one = await phone("eins");
    const screen = await screenOf(one);
    expect(screen.getUi().rounds).toBeNull();
    expect(one.backendLoads).toBe(1);
  });

  it("zeigt, was die wartende Einladung verrät", async () => {
    const one = await founder();
    const two = await phone("zwei", 2);
    two.controller.rememberInvite(await inviteToken(one));

    const screen = await screenOf(two);

    expect(screen.getUi().invite).toEqual({ roundId: one.state.link?.roundId, roundName: "Stammtisch", memberCount: 1, alreadyMember: false });
    expect(screen.getUi().rounds).toEqual([]);
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

  it("nach dem Anmelden werden Runden und Einladung von selbst nachgesehen", async () => {
    const one = await founder();
    const two = await phone("zwei");
    two.controller.rememberInvite(await inviteToken(one));
    const screen = await screenOf(two);
    expect(screen.getUi().invite).toBeNull();

    two.auth.set(account(2));
    await pass();

    expect(screen.getUi().invite?.roundName).toBe("Stammtisch");
    expect(screen.getUi().rounds).toEqual([]);
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
    // Mit Runde gibt es keine Liste der Runden mehr.
    expect(screen.getUi().rounds).toBeNull();
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
    const [round] = screen.getUi().rounds ?? [];

    await screen.use(round);
    await pass();

    expect(other.state.link?.roundId).toBe(one.state.link?.roundId);
    expect(other.store.getState().evenings).toEqual(one.store.getState().evenings);
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
    expect(screen.getUi().rounds).toBeNull();
  });

  it("„Mit Google anmelden“ beginnt die Anmeldung", async () => {
    const one = await phone("eins");
    const screen = await screenOf(one);
    await screen.signIn();
    expect(one.auth.signInsStarted).toBe(1);
    expect(screen.getUi().error).toBeNull();
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
