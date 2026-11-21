/*
 * Der nachgestellte Server hält sich an den Vertrag aus docs/ARCHITEKTUR.md, Abschnitt 8.2 und 8.3. Gegen ihn laufen
 * die Tests des Abgleichs; der echte Server hat seine eigenen Tests in `supabase/tests`.
 * Port von `InMemoryRoundServerTest.kt`.
 */
import { beforeEach, describe, expect, it } from "vitest";
import type { Player } from "../model";
import { RuleSets } from "../rules/RuleSet";
import { InMemoryRoundServer, InMemoryRoundStore, type InMemoryRoundSession } from "./InMemoryRound";
import { InviteLinks } from "./InviteLinks";
import {
  remotePlayer,
  ROUND_ERRORS,
  RoundError,
  RoundException,
  roundLink,
  sameContent,
  toPlayer,
  type EveningBase,
  type RoundId,
} from "./RoundModels";
import { errorOf, evening, game, player, TestClock } from "./testing/fixtures";

let clock: TestClock;
let server: InMemoryRoundServer;
let one: InMemoryRoundSession;
let two: InMemoryRoundSession;
const guest: Player = player("gast", "Wirt", 3);

beforeEach(() => {
  clock = new TestClock();
  server = new InMemoryRoundServer(() => clock.millis);
  one = server.sessionFor("konto-1");
  two = server.sessionFor("konto-2");
});

async function roundOfTwo(): Promise<RoundId> {
  const round = await one.createRound("Stammtisch");
  await two.joinRound(await one.createInvite(round));
  return round;
}

describe("Runden und Mitglieder", () => {
  it("eine neue Runde hat ihren Ersteller als einziges Mitglied", async () => {
    const round = await one.createRound("  Stammtisch  ");

    // Das Anlegen mit dem ersten Mitglied ist die erste Änderung der Runde.
    expect(await one.head(round)).toEqual({ id: round, name: "Stammtisch", rev: 1, rules: null, createdBy: "konto-1", rulesUnreadable: false });
    expect(await one.members(round)).toEqual([{ userId: "konto-1", playerId: null }]);
    expect(await one.myRounds()).toEqual([{ id: round, name: "Stammtisch", rev: 1, memberCount: 1, myPlayerId: null, isCreator: true, memberNames: [] }]);
  });

  it("der Name einer Runde hat 1 bis 40 Zeichen", async () => {
    expect(await errorOf(() => one.createRound("   "))).toBe("INVALID_NAME");
    expect(await errorOf(() => one.createRound("x".repeat(41)))).toBe("INVALID_NAME");
    expect(await errorOf(() => one.createRound("x".repeat(40)))).toBeNull();
  });

  it("ein Konto hat keine zwei Runden desselben Namens", async () => {
    const round = await one.createRound("Stammtisch");

    expect(await errorOf(() => one.createRound(" stammTISCH\t"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.createRound("Stammtisch"))).toBeNull();
    // Durch Beitreten kann es trotzdem zwei geben; der Name bleibt dann vergeben, bis keine mehr so heißt.
    const [other] = await two.myRounds();
    await one.joinRound(await two.createInvite(other.id));
    expect((await one.myRounds()).map((it) => it.name)).toEqual(["Stammtisch", "Stammtisch"]);
    await one.leaveRound(round);
    expect(await errorOf(() => one.createRound("Stammtisch"))).toBe("NAME_TAKEN");
    await one.leaveRound(other.id);
    expect(await errorOf(() => one.createRound("Stammtisch"))).toBeNull();
    // Geprüft wird vor der Obergrenze.
    for (let index = 0; index < InMemoryRoundServer.MAX_ROUNDS - 1; index++) await one.createRound(`Runde ${index}`);
    expect(await errorOf(() => one.createRound("runde 0"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => one.createRound("Runde 9"))).toBe("LIMIT_REACHED");
  });

  it("der Name wird bereinigt wie auf dem Server: Leer- und Steuerzeichen innen werden ein Leerzeichen", async () => {
    const round = await one.createRound("  Stamm \t\n tisch ");

    expect((await one.head(round))?.name).toBe("Stamm tisch");
    expect(await errorOf(() => one.createRound("STAMM TISCH"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => one.createRound("\u0000\u001F "))).toBe("INVALID_NAME");
    // Gezählt werden Zeichen: 40 außerhalb der Grundebene sind erlaubt.
    expect(await errorOf(() => one.createRound("🃏".repeat(40)))).toBeNull();
    expect(await errorOf(() => one.createRound("🂡".repeat(41)))).toBe("INVALID_NAME");
  });

  it("die Übersicht nennt die Namen der Mitglieder, ohne Gäste und ohne Mitglieder ohne Spieler", async () => {
    const round = await roundOfTwo();
    expect((await one.myRounds())[0].memberNames).toEqual([]);

    await one.claimPlayer(round, "p1", "Zora", 1);
    await one.savePlayer(round, guest);
    await two.claimPlayer(round, "p2", "anton", 2);

    expect((await one.myRounds())[0].memberNames).toEqual(["anton", "Zora"]);
    expect((await two.myRounds())[0].memberNames).toEqual(["anton", "Zora"]);
    await two.leaveRound(round);
    expect((await one.myRounds())[0].memberNames).toEqual(["Zora"]);
  });

  it("ein Konto kann höchstens fünf Runden angehören", async () => {
    for (let index = 0; index < InMemoryRoundServer.MAX_ROUNDS; index++) await one.createRound(`Runde ${index}`);
    const other = await two.createRound("Noch eine");

    expect(await errorOf(() => one.createRound("Zu viel"))).toBe("LIMIT_REACHED");
    const invite = await two.createInvite(other);
    expect(await errorOf(() => one.joinRound(invite))).toBe("LIMIT_REACHED");
  });

  it("wer nicht dazugehört, sieht nichts und darf nichts", async () => {
    const round = await one.createRound("Stammtisch");
    await one.savePlayer(round, guest);
    await one.saveEvening(round, evening(), 0);

    expect(await two.head(round)).toBeNull();
    expect(await two.members(round)).toEqual([]);
    expect(await two.playersSince(round, 0)).toEqual([]);
    expect(await two.eveningsSince(round, 0)).toEqual([]);
    expect(await two.evening(round, "abend")).toBeNull();
    expect(await two.myRounds()).toEqual([]);
    expect(await errorOf(() => two.saveEvening(round, evening(), 1))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.savePlayer(round, guest))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.saveRules(round, RuleSets.standard()))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.createInvite(round))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.claimPlayer(round, guest.id, "Zwei", 0))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.leaveRound(round))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.leaveRound("gibt-es-nicht"))).toBe("NOT_MEMBER");
  });
});

describe("Einladungen", () => {
  it("eine Einladung ist ein Code aus 32 Zeichen und verrät vor dem Beitritt nur Name und Größe", async () => {
    const round = await one.createRound("Stammtisch");
    const token = await one.createInvite(round);

    expect(InviteLinks.tokenFrom(InviteLinks.webLink("https://example.org/app/", token))).toBe(token);
    expect(await two.inviteInfo(token)).toEqual({ roundId: round, roundName: "Stammtisch", memberCount: 1, alreadyMember: false });
    expect(await two.joinRound(token)).toBe(round);
    expect(await two.inviteInfo(token)).toEqual({ roundId: round, roundName: "Stammtisch", memberCount: 2, alreadyMember: true });
    // Noch einmal beitreten ändert nichts.
    const rev = server.rev(round);
    expect(await two.joinRound(token)).toBe(round);
    expect(server.rev(round)).toBe(rev);
    expect(await one.members(round)).toHaveLength(2);
  });

  it("eine Einladung gilt 14 Tage, ein falscher Code nie", async () => {
    const round = await one.createRound("Stammtisch");
    const token = await one.createInvite(round);

    expect(await errorOf(() => two.inviteInfo("0".repeat(32)))).toBe("INVALID_INVITE");
    expect(await errorOf(() => two.joinRound("kein-code"))).toBe("INVALID_INVITE");
    clock.millis += InMemoryRoundServer.INVITE_LIFETIME_MILLIS - 1000;
    expect(await errorOf(() => two.inviteInfo(token))).toBeNull();
    clock.advance(1);
    expect(await errorOf(() => two.inviteInfo(token))).toBe("INVALID_INVITE");
    expect(await errorOf(() => two.joinRound(token))).toBe("INVALID_INVITE");
  });

  it("je Runde gibt es höchstens 20 gültige Einladungen, abgelaufene zählen nicht", async () => {
    const round = await one.createRound("Stammtisch");
    const tokens: string[] = [];
    for (let index = 0; index < InMemoryRoundServer.MAX_INVITES; index++) tokens.push(await one.createInvite(round));

    expect(new Set(tokens).size).toBe(InMemoryRoundServer.MAX_INVITES);
    expect(await errorOf(() => one.createInvite(round))).toBe("LIMIT_REACHED");
    clock.advance(15 * 24 * 60 * 60);
    expect(await errorOf(() => one.createInvite(round))).toBeNull();
  });

  it("eine Runde hat höchstens zwölf Mitglieder", async () => {
    const round = await one.createRound("Stammtisch");
    const token = await one.createInvite(round);
    for (let index = 0; index < InMemoryRoundServer.MAX_MEMBERS - 1; index++) await server.sessionFor(`mitglied-${index}`).joinRound(token);

    expect(await errorOf(() => two.joinRound(token))).toBe("LIMIT_REACHED");
  });
});

describe("Abende", () => {
  it("ein Abend wird nur mit der erwarteten Fassung gespeichert", async () => {
    const round = await roundOfTwo();
    const first = evening([game("g1", 60)]);
    const second = evening([game("g1", 60), game("g2", 120)]);
    const before = server.rev(round);

    // Neu nur mit der Fassung 0.
    expect(await one.saveEvening(round, first, 1)).toBeNull();
    expect(await one.saveEvening(round, first, 0)).toEqual({ version: 1, rev: before + 1 });
    expect(await two.saveEvening(round, second, 0)).toBeNull();
    expect(await two.saveEvening(round, second, 2)).toBeNull();
    // Eine abgelehnte Fassung zählt nicht als Änderung der Runde.
    expect(server.rev(round)).toBe(before + 1);
    expect(await two.saveEvening(round, second, 1)).toEqual({ version: 2, rev: before + 2 });

    expect(await one.evening(round, first.id)).toEqual({ id: first.id, version: 2, deleted: false, rev: before + 2, evening: second });
    expect(server.evenings(round)).toEqual([second]);
  });

  it("ein gelöschter Abend bleibt als Zeile und lässt sich wieder anlegen", async () => {
    const round = await roundOfTwo();
    const doc = evening([game("g1", 60)]);
    await one.saveEvening(round, doc, 0);

    expect((await one.saveEvening(round, doc, 1, true))?.version).toBe(2);
    const rows = await two.eveningsSince(round, 0);
    expect(rows).toHaveLength(1);
    expect(rows[0].deleted).toBe(true);
    expect(server.evenings(round)).toEqual([]);
    expect((await two.saveEvening(round, doc, 2))?.version).toBe(3);
    expect((await one.evening(round, doc.id))?.deleted).toBe(false);
  });

  it("gelesen wird alles nach einer Änderungsnummer, in der Reihenfolge der Änderungen", async () => {
    const round = await roundOfTwo();
    const start = server.rev(round);
    await one.saveEvening(round, evening([], "a"), 0);
    await one.saveEvening(round, evening([], "b"), 0);
    await one.saveEvening(round, evening([game("g1", 60)], "a"), 1);

    expect((await two.eveningsSince(round, start)).map((it) => it.id)).toEqual(["b", "a"]);
    expect((await two.eveningsSince(round, start + 2)).map((it) => it.id)).toEqual(["a"]);
    expect(await two.eveningsSince(round, start + 3)).toEqual([]);
    expect((await two.head(round))?.rev).toBe(start + 3);
  });
});

describe("Spieler", () => {
  it("Gäste bekommen eine Position, Namen werden bereinigt und sind eindeutig", async () => {
    const round = await roundOfTwo();

    await one.savePlayer(round, { ...guest, name: "  Wirt  " });
    await two.savePlayer(round, player("gast-2", "Nachbar", 40));

    expect(server.players(round).map((it) => it.name)).toEqual(["Wirt", "Nachbar"]);
    expect(server.players(round).map((it) => it.position)).toEqual([1, 2]);
    expect(server.players(round)[1].colorIndex).toBe(8);
    expect(await errorOf(() => two.savePlayer(round, player("gast-3", "WIRT", 0)))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.savePlayer(round, player("gast-2", "wirt", 0)))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.savePlayer(round, player("gast-3", "  ", 0)))).toBe("INVALID_NAME");
    expect(await errorOf(() => two.savePlayer(round, player("gast-3", "x".repeat(25), 0)))).toBe("INVALID_NAME");
    // Der eigene Name darf bleiben, auch in anderer Schreibweise.
    expect(await errorOf(() => two.savePlayer(round, { ...guest, name: "WIRT", archived: true }))).toBeNull();
    expect((await one.playersSince(round, 0)).at(-1)).toEqual(
      remotePlayer({ id: guest.id, name: "WIRT", colorIndex: 3, archived: true, position: 1, rev: server.rev(round) }),
    );
  });

  it("ein gelöschter Gast gibt seinen Namen frei und kann wiederkommen", async () => {
    const round = await roundOfTwo();
    await one.savePlayer(round, guest);

    const rev = await one.savePlayer(round, guest, true);

    expect(rev).toBe(server.rev(round));
    const rows = await two.playersSince(round, 0);
    expect(rows).toHaveLength(1);
    expect(rows[0].deleted).toBe(true);
    expect(server.players(round)).toEqual([]);
    // Noch einmal löschen oder einen unbekannten Spieler löschen ändert nichts.
    expect(await two.savePlayer(round, guest, true)).toBe(rev);
    expect(await two.savePlayer(round, player("unbekannt", "Niemand", 0), true)).toBe(rev);
    expect(await errorOf(() => two.savePlayer(round, player("gast-2", "Wirt", 0)))).toBeNull();
    expect(await errorOf(() => one.savePlayer(round, guest))).toBe("NAME_TAKEN");
    expect(await errorOf(() => one.savePlayer(round, { ...guest, name: "Wirt von nebenan" }))).toBeNull();
    expect(server.players(round).map((it) => it.name)).toEqual(["Wirt von nebenan", "Wirt"]);
  });

  it("eine Runde hat höchstens 40 Spieler, gelöschte zählen nicht", async () => {
    const round = await roundOfTwo();
    for (let index = 0; index < InMemoryRoundServer.MAX_PLAYERS; index++) await one.savePlayer(round, player(`p${index}`, `Spieler ${index}`, index));

    expect(await errorOf(() => one.savePlayer(round, guest))).toBe("LIMIT_REACHED");
    expect(await errorOf(() => two.claimPlayer(round, "ich", "Zwei", 0))).toBe("LIMIT_REACHED");
    await one.savePlayer(round, player("p0", "Spieler 0", 0), true);
    expect(await errorOf(() => one.savePlayer(round, guest))).toBeNull();
  });
});

describe("Das bin ich", () => {
  it("Das bin ich macht den Spieler zum eigenen und den Namen fest", async () => {
    const round = await roundOfTwo();
    await one.savePlayer(round, guest);

    await two.claimPlayer(round, guest.id, " Zwei ", 9);

    const rows = server.players(round);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual(
      remotePlayer({ id: guest.id, name: "Zwei", colorIndex: 9, archived: false, position: 1, owner: "konto-2", nameLocked: true, rev: rows[0].rev }),
    );
    expect((await one.members(round)).at(-1)).toEqual({ userId: "konto-2", playerId: guest.id });
    expect((await two.myRounds())[0].myPlayerId).toBe(guest.id);
    // Derselbe Spieler noch einmal (etwa nach einer verlorenen Antwort) ist kein Fehler; der Name bleibt.
    expect(await errorOf(() => two.claimPlayer(round, guest.id, "Anders", 2))).toBeNull();
    expect(toPlayer(server.players(round)[0])).toEqual(player(guest.id, "Zwei", 2));
    expect(await errorOf(() => two.claimPlayer(round, "noch-einer", "Drei", 0))).toBe("ALREADY_CLAIMED");
    expect(await errorOf(() => one.claimPlayer(round, guest.id, "Eins", 0))).toBe("PLAYER_TAKEN");
    expect(await errorOf(() => one.savePlayer(round, guest, true))).toBe("PLAYER_OWNED");
  });

  it("den festen Namen ändert nur der Ersteller der Runde", async () => {
    const round = await roundOfTwo();
    const third = server.sessionFor("konto-3");
    await third.joinRound(await one.createInvite(round));
    await two.claimPlayer(round, guest.id, "Zwei", 3);
    const mine = player(guest.id, "Zwei", 3);

    expect(await errorOf(() => third.savePlayer(round, { ...mine, name: "Witzbold" }))).toBe("NAME_LOCKED");
    // Auch der Besitzer selbst nicht: Der Name ist nach „Das bin ich“ fest.
    expect(await errorOf(() => two.savePlayer(round, { ...mine, name: "Doch anders" }))).toBe("NAME_LOCKED");
    expect(await errorOf(() => third.savePlayer(round, { ...mine, colorIndex: 5, archived: true }))).toBeNull();
    expect(await errorOf(() => one.savePlayer(round, { ...mine, name: "Zwo" }))).toBeNull();
    expect(toPlayer(server.players(round)[0])).toEqual(player(guest.id, "Zwo", 3));
    expect(server.players(round)[0].nameLocked).toBe(true);
  });

  it("Das bin ich mit neuem Spieler, vergebenem Namen und nach dem Wiederkommen", async () => {
    const round = await roundOfTwo();
    await one.savePlayer(round, guest);
    expect(await errorOf(() => two.claimPlayer(round, "ich", "wirt", 0))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.claimPlayer(round, "ich", "", 0))).toBe("INVALID_NAME");

    await two.claimPlayer(round, "ich", "Zwei", 33);
    expect(server.players(round).at(-1)).toEqual(
      remotePlayer({ id: "ich", name: "Zwei", colorIndex: 1, archived: false, position: 2, owner: "konto-2", nameLocked: true, rev: server.rev(round) }),
    );

    // Wer geht und wiederkommt, ist wieder derselbe Spieler.
    await two.leaveRound(round);
    expect(await one.members(round)).toEqual([{ userId: "konto-1", playerId: null }]);
    expect(server.players(round).at(-1)?.owner).toBe("konto-2");
    await two.joinRound(await one.createInvite(round));
    expect((await one.members(round)).at(-1)?.playerId).toBe("ich");
  });

  it("ein gelöschter Gast kann von jemandem als eigener Spieler neu angelegt werden", async () => {
    const round = await roundOfTwo();
    await one.savePlayer(round, guest);
    await one.savePlayer(round, guest, true);

    await two.claimPlayer(round, guest.id, "Zwei", 6);

    const rows = server.players(round);
    expect(rows).toHaveLength(1);
    expect(rows[0].owner).toBe("konto-2");
    expect(rows[0].position).toBe(1);
    expect(rows[0].colorIndex).toBe(6);
  });

  it("Kennungen sind höchstens 64 Zeichen lang, Dokumente nicht beliebig groß", async () => {
    const round = await roundOfTwo();
    const long = "x".repeat(InMemoryRoundServer.MAX_ID_LENGTH + 1);

    expect(await errorOf(() => one.savePlayer(round, player(long, "Lang", 0)))).toBe("INVALID_DOC");
    expect(await errorOf(() => one.saveEvening(round, evening([], long), 0))).toBe("INVALID_DOC");
    expect(await errorOf(() => one.saveEvening(round, evening([], long.slice(0, -1)), 0))).toBeNull();
    const huge = evening(Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => game(`g${index}`, index)));
    expect(await errorOf(() => one.saveEvening(round, huge, 0))).toBe("TOO_LARGE");
    const wordy = { ...RuleSets.standard(), name: "x".repeat(InMemoryRoundServer.MAX_RULES_NAME_LENGTH + 1) };
    expect(await errorOf(() => one.saveRules(round, wordy))).toBe("TOO_LARGE");
  });
});

describe("Regelwerk, Störungen, Hilfen für Tests", () => {
  it("das Regelwerk für neue Abende steht am Kopf der Runde", async () => {
    const round = await roundOfTwo();

    const rev = await two.saveRules(round, RuleSets.sauspiel());

    expect((await one.head(round))?.rules).toEqual(RuleSets.sauspiel());
    expect((await one.head(round))?.rev).toBe(rev);
    server.makeRulesUnreadable(round);
    expect((await one.head(round))?.rulesUnreadable).toBe(true);
    expect((await one.head(round))?.rules).toBeNull();
    await one.saveRules(round, RuleSets.standard());
    expect((await one.head(round))?.rulesUnreadable).toBe(false);
  });

  it("ohne Netz und ohne Anmeldung scheitert jeder Aufruf, eine verlorene Antwort nur das eine Mal", async () => {
    const round = await roundOfTwo();
    one.offline = true;
    expect(await errorOf(() => one.head(round))).toBe("OFFLINE");
    expect(await errorOf(() => one.savePlayer(round, guest))).toBe("OFFLINE");
    one.offline = false;
    one.signedOut = true;
    expect(await errorOf(() => one.myRounds())).toBe("SIGNED_OUT");
    one.signedOut = false;

    one.loseNextAnswer = true;
    // Lesen ist nicht betroffen; die nächste Änderung kommt an, nur ihre Antwort nicht.
    expect(await errorOf(() => one.head(round))).toBeNull();
    expect(await errorOf(() => one.savePlayer(round, guest))).toBe("OFFLINE");
    expect(server.players(round).map((it) => it.name)).toEqual(["Wirt"]);
    expect(await errorOf(() => one.savePlayer(round, { ...guest, colorIndex: 1 }))).toBeNull();
    expect(one.calls.slice(2)).toEqual(["head", "savePlayer", "myRounds", "head", "savePlayer", "savePlayer"]);
  });

  it("der Server lässt sich für Tests befragen und stören", async () => {
    const round = await roundOfTwo();
    const doc = evening([game("g1", 60)]);
    await one.saveEvening(round, doc, 0);
    const rev = server.rev(round);

    server.makeUnreadable(round, doc.id);
    expect(server.eveningRow(round, doc.id)).toEqual({ id: doc.id, version: 2, deleted: false, rev: rev + 1, evening: null });
    expect(server.evenings(round)).toEqual([]);

    // Wieder lesbar (wie nach einem Update der App): Version und Änderungsnummer bleiben.
    server.makeReadable(round, doc.id, doc);
    expect(server.eveningRow(round, doc.id)).toEqual({ id: doc.id, version: 2, deleted: false, rev: rev + 1, evening: doc });
    expect(server.rev(round)).toBe(rev + 1);
    expect(() => server.makeReadable(round, "gibt-es-nicht", doc)).toThrow();

    server.rollBack(round, doc.id, 1, doc, 1);
    expect(server.rev(round)).toBe(1);
    expect(server.evenings(round)).toEqual([doc]);

    server.removeMember(round, "konto-2");
    expect(await two.head(round)).toBeNull();
    server.removeMember(round, "konto-1");
    expect(server.rev(round)).toBe(0);
    server.forgetEvening(await one.createRound("Leer"), doc.id);
    expect(server.eveningRow(round, doc.id)).toBeNull();
    expect(server.players(round)).toEqual([]);
    server.removeMember(round, "konto-1");
    expect(await one.createRound("Neu")).not.toBe(round);
  });

  it("der Speicher eines Geräts merkt sich Runde und Basen", async () => {
    const store = new InMemoryRoundStore();
    const link = roundLink({ roundId: "runde", roundName: "Stammtisch", userId: "konto-1" });
    const base: EveningBase = { version: 3, evening: evening([game("g1", 60)]) };
    const row = remotePlayer({ id: guest.id, name: guest.name, colorIndex: guest.colorIndex, archived: guest.archived, owner: "konto-2", nameLocked: true });

    expect(await store.link()).toBeNull();
    await store.setLink(link);
    await store.setEveningBase(base.evening.id, base);
    await store.setPlayerBase(row.id, row);

    expect(await store.link()).toEqual(link);
    expect(await store.eveningBases()).toEqual(new Map([[base.evening.id, base]]));
    expect(await store.playerBases()).toEqual(new Map([[row.id, row]]));
    expect(sameContent(row, guest)).toBe(true);
    expect(sameContent(row, { ...guest, archived: true })).toBe(false);
    expect(sameContent(row, { ...guest, id: "anders" })).toBe(false);

    await store.setEveningBase(base.evening.id, null);
    await store.setPlayerBase(row.id, null);
    expect((await store.eveningBases()).size + (await store.playerBases()).size).toBe(0);
    await store.setEveningBase(base.evening.id, base);
    await store.setPlayerBase(row.id, row);
    await store.clearBases();
    expect((await store.eveningBases()).size + (await store.playerBases()).size).toBe(0);
    expect(await new InMemoryRoundStore(link).link()).toEqual(await store.link());
  });

  it("Fehlercodes des Servers werden erkannt, alles andere nicht", () => {
    expect(RoundError.fromCode("name_taken")).toBe("NAME_TAKEN");
    expect(RoundError.fromCode("not_member")).toBe("NOT_MEMBER");
    expect(RoundError.fromCode("invalid_doc")).toBe("INVALID_DOC");
    expect(ROUND_ERRORS.map((error) => error.toLowerCase()).filter((code) => RoundError.fromCode(code)?.toLowerCase() === code)).toEqual([
      "not_member",
      "invalid_name",
      "name_taken",
      "name_locked",
      "already_claimed",
      "player_taken",
      "player_owned",
      "invalid_invite",
      "limit_reached",
      "too_large",
      "invalid_doc",
    ]);
    // Die Fehler der App selbst sind keine Codes des Servers.
    expect(RoundError.fromCode("offline")).toBeNull();
    expect(RoundError.fromCode("signed_out")).toBeNull();
    expect(RoundError.fromCode("server")).toBeNull();
    expect(RoundError.fromCode("not_configured")).toBeNull();
    expect(RoundError.fromCode("changed_meanwhile")).toBeNull();
    expect(RoundError.fromCode("NAME_TAKEN")).toBeNull();
    expect(RoundError.fromCode("")).toBeNull();
    expect(new RoundException("NAME_TAKEN").message).toBe("NAME_TAKEN");
    expect(new RoundException("SERVER", "503").message).toBe("SERVER: 503");
    expect(new RoundException("SERVER", "503").detail).toBe("503");
  });
});
