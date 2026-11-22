/*
 * Die Runden-Auswahl auf dem Start-Bildschirm (docs/ARCHITEKTUR.md 8.9): welche Runden zur Wahl stehen, wie sie
 * heißen, wann die Auswahl dasteht, und der Wechsel mit Abgleich und Rückfrage. Dazu die Prüfung des Namens einer neuen
 * Runde, die „Gemeinsame Runden“ vor dem Anlegen macht. Mit nachgestellten Handys an einem Server im Speicher.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { RoundException, roundLink, roundSummary, switchFrom, type RoundSummary } from "../core/round/RoundModels";
import { knownRounds, roundNameProblem, suggestedRoundName } from "./actions";
import { NOT_CONFIGURED, type RoundState } from "./controller";
import { roundChoices, RoundPicker, showsPicker } from "./picker";
import { account, Phone } from "./testing/phone";

function summary(id: string, name: string, fields: Partial<RoundSummary> = {}): RoundSummary {
  return roundSummary({ id, name, rev: 3, memberCount: 2, myPlayerId: null, isCreator: false, ...fields });
}

const LINK = roundLink({ roundId: "a", roundName: "Stammtisch", userId: "konto-1", members: [{ userId: "konto-1", playerId: null }] });

describe("Einträge der Auswahl", () => {
  it("die Runden des Kontos in ihrer Reihenfolge; heißen zwei gleich, stehen ihre Mitglieder dahinter", () => {
    const rounds = [
      summary("b", "Klub", { memberNames: ["Cilli"] }),
      summary("a", "Stammtisch", { memberNames: ["Anna", "Bert"] }),
      summary("c", "stammtisch ", { memberNames: [], memberCount: 3 }),
    ];

    expect(roundChoices(LINK, rounds).map((it) => [it.round.id, it.label])).toEqual([
      ["b", "Klub"],
      ["a", "Stammtisch (Anna, Bert)"],
      ["c", "stammtisch  (3 Mitglieder)"],
    ]);
  });

  it("fehlt die verbundene Runde in der Liste, steht sie vorn", () => {
    const choices = roundChoices(LINK, [summary("b", "Klub"), summary("c", "Freitag")]);

    expect(choices.map((it) => it.label)).toEqual(["Stammtisch", "Klub", "Freitag"]);
    expect(choices[0].round).toMatchObject({ id: "a", name: "Stammtisch", memberCount: 1, memberNames: [] });
    // Auch dann werden gleichnamige erkannt; ohne bekannte Namen zählt die Zahl der Mitglieder.
    expect(roundChoices(LINK, [summary("b", "STAMMTISCH", { memberNames: ["Sepp"] })]).map((it) => it.label)).toEqual([
      "Stammtisch (1 Mitglied)",
      "STAMMTISCH (Sepp)",
    ]);
  });
});

describe("Wann die Auswahl dasteht", () => {
  const linked: RoundState = { ...NOT_CONFIGURED, configured: true, loaded: true, account: account(1), link: LINK };

  it("verbunden, angemeldet, gelesen und mindestens eine weitere Runde", () => {
    expect(showsPicker({ ...linked, rounds: [summary("a", "Stammtisch"), summary("b", "Klub")] })).toBe(true);
    // Scheitert ein späteres Lesen, bleibt die zuletzt gelesene Liste und mit ihr die Auswahl.
    expect(showsPicker({ ...linked, rounds: [summary("a", "Stammtisch"), summary("b", "Klub")], roundsFailed: true })).toBe(true);
    // Fehlt die verbundene Runde in der Liste, genügt eine andere.
    expect(showsPicker({ ...linked, rounds: [summary("b", "Klub")] })).toBe(true);
  });

  it("sonst nicht: ohne Runde, ohne gelesene Liste, nur mit der verbundenen, mit abgelaufener Anmeldung", () => {
    const rounds = [summary("a", "Stammtisch"), summary("b", "Klub")];
    expect(showsPicker({ ...linked, link: null, rounds })).toBe(false);
    expect(showsPicker({ ...linked, rounds: null })).toBe(false);
    expect(showsPicker({ ...linked, rounds: null, roundsFailed: true })).toBe(false);
    expect(showsPicker({ ...linked, rounds: [summary("a", "Stammtisch")] })).toBe(false);
    expect(showsPicker({ ...linked, rounds, problem: "SIGNED_OUT" })).toBe(false);
    expect(showsPicker({ ...linked, rounds, account: null })).toBe(false);
    expect(showsPicker({ ...linked, rounds, account: account(2) })).toBe(false);
  });
});

describe("Name einer neuen Runde", () => {
  /** Ohne verbundene Runde, mit diesen Runden des Kontos. */
  function unlinked(rounds: readonly RoundSummary[] | null): { link: null; rounds: readonly RoundSummary[] | null } {
    return { link: null, rounds };
  }

  it("1 bis 40 Zeichen nach dem Bereinigen, und keine Runde des Kontos heißt schon so", () => {
    const rounds = [summary("a", "Stammtisch"), summary("b", "Klub", { isCreator: true })];
    expect(roundNameProblem("Freitag", unlinked(rounds))).toBeNull();
    expect(roundNameProblem("  stammTISCH \t", unlinked(rounds))).toBe("NAME_TAKEN");
    expect(roundNameProblem("klub", unlinked(rounds))).toBe("NAME_TAKEN");
    expect(roundNameProblem(" \t ", unlinked(rounds))).toBe("INVALID_NAME");
    expect(roundNameProblem("x".repeat(41), unlinked([]))).toBe("INVALID_NAME");
    expect(roundNameProblem("x".repeat(40), unlinked([]))).toBeNull();
    // Ohne gelesene Liste prüft erst das Anlegen selbst.
    expect(roundNameProblem("Stammtisch", unlinked(null))).toBeNull();
  });

  it("die eigene, noch leere Runde eines Versuchs, dessen Antwort verloren ging, zählt nicht: Das Anlegen nimmt sie", () => {
    const fresh = summary("a", "Stammtisch", { rev: 1, memberCount: 1, isCreator: true });
    expect(roundNameProblem("stammtisch", unlinked([fresh]))).toBeNull();
    expect(suggestedRoundName(unlinked([{ ...fresh, name: "Schafkopfrunde" }]))).toBe("Schafkopfrunde");
    // Sobald etwas in ihr liegt, jemand dazukam oder sie von jemand anderem stammt, ist der Name vergeben.
    expect(roundNameProblem("stammtisch", unlinked([{ ...fresh, rev: 2 }]))).toBe("NAME_TAKEN");
    expect(roundNameProblem("stammtisch", unlinked([{ ...fresh, memberCount: 2 }]))).toBe("NAME_TAKEN");
    expect(roundNameProblem("stammtisch", unlinked([{ ...fresh, isCreator: false }]))).toBe("NAME_TAKEN");
  });

  it("die verbundene Runde blockiert ihren Namen immer, auch noch leer und auch, wenn die Liste sie (noch) nicht kennt", () => {
    const fresh = summary("a", "Stammtisch", { rev: 1, memberCount: 1, isCreator: true });
    expect(roundNameProblem("stammtisch", { link: LINK, rounds: [fresh] })).toBe("NAME_TAKEN");
    expect(roundNameProblem("stammtisch", { link: LINK, rounds: null })).toBe("NAME_TAKEN");
    expect(roundNameProblem("Klub", { link: LINK, rounds: null })).toBeNull();
    expect(suggestedRoundName({ link: { ...LINK, roundName: "Schafkopfrunde" }, rounds: [] })).toBe("Schafkopfrunde 2");
    expect(knownRounds({ link: LINK, rounds: null }).map((it) => it.id)).toEqual(["a"]);
    expect(knownRounds({ link: null, rounds: null })).toEqual([]);
  });
});

describe("Wechseln über die Auswahl", () => {
  let server: InMemoryRoundServer;
  const phones: Phone[] = [];

  async function pass(millis = 0): Promise<void> {
    await vi.advanceTimersByTimeAsync(millis);
  }

  async function phone(name: string, number: number): Promise<Phone> {
    const created = await Phone.create(server, name, account(number));
    phones.push(created);
    created.controller.start(true);
    await pass();
    return created;
  }

  /** Handy eins mit dem „Stammtisch“ (ein Abend), Konto 1 gehört außerdem dem „Klub“ von Konto 2 an. */
  async function twoRounds(): Promise<{ one: Phone; two: Phone }> {
    const one = await phone("eins", 1);
    await one.startEvening("abend-1");
    await one.addGame("abend-1");
    await one.controller.create("Stammtisch");
    const two = await phone("zwei", 2);
    await two.startEvening("abend-klub");
    await two.controller.create("Klub");
    await server.sessionFor("konto-1").joinRound(await two.mine.createInvite(two.state.link?.roundId ?? ""));
    await pass();
    return { one, two };
  }

  /** Die Auswahl, wie der Start-Bildschirm sie zeigt, nachdem alles Angestoßene fertig ist. */
  async function pickerOf(of: Phone): Promise<RoundPicker> {
    const picker = new RoundPicker(of.controller);
    await pass();
    return picker;
  }

  function klubOf(of: Phone): RoundSummary {
    const round = of.state.rounds?.find((it) => it.name === "Klub");
    if (round === undefined) throw new Error("kein Klub in der Liste");
    return round;
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

  it("erscheint der Start-Bildschirm, werden die Runden gelesen; dann steht die Auswahl da", async () => {
    const { one } = await twoRounds();
    expect(one.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);
    expect(showsPicker(one.state)).toBe(false);

    const picker = await pickerOf(one);

    expect(one.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch", "Klub"]);
    expect(showsPicker(one.state)).toBe(true);
    picker.dispose();
  });

  it("eine andere Runde gewählt: erst abgleichen (gesperrt), dann die Rückfrage; nach „Wechseln“ die andere Runde", async () => {
    const { one, two } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);
    await one.addGame("abend-1");

    const choosing = picker.choose(klubOf(one).id);
    // Solange abgeglichen wird, steht über allem „Einen Moment …“.
    expect(picker.getUi().busy).toBe(true);
    expect(one.state.switching).toBe(true);
    await choosing;

    expect(picker.getUi()).toEqual({
      busy: false,
      error: null,
      confirm: { target: { kind: "use", round: klubOf(one) }, from: switchFrom(stammtisch, 0), fromName: "Stammtisch" },
    });
    expect(one.state.switching).toBe(false);
    expect(server.evenings(stammtisch)[0].entries).toHaveLength(2);
    expect(one.state.link?.roundId).toBe(stammtisch);

    const confirming = picker.confirm();
    expect(picker.getUi().confirm).toBeNull();
    expect(picker.getUi().busy).toBe(true);
    expect(one.state.switching).toBe(true);
    await confirming;
    await pass();

    expect(picker.getUi().busy).toBe(false);
    expect(one.state.switching).toBe(false);
    expect(picker.getUi().error).toBeNull();
    expect(one.state.link?.roundName).toBe("Klub");
    expect(one.store.getState().evenings).toEqual(two.store.getState().evenings);
    // Zurück über die Auswahl: Alles ist wieder da.
    await picker.choose(stammtisch);
    await picker.confirm();
    await pass();
    expect(one.state.link?.roundId).toBe(stammtisch);
    expect(one.store.getState().evenings).toEqual(server.evenings(stammtisch));
    picker.dispose();
  });

  it("„Abbrechen“ lässt alles, wie es ist; die verbundene Runde noch einmal gewählt tut nichts", async () => {
    const { one } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);

    await picker.choose(klubOf(one).id);
    picker.cancel();
    expect(picker.getUi().confirm).toBeNull();
    expect(one.state.link?.roundId).toBe(stammtisch);

    one.mine.calls.length = 0;
    await picker.choose(stammtisch);
    await picker.choose("gibt-es-nicht");
    expect(picker.getUi().confirm).toBeNull();
    expect(one.mine.calls).toEqual([]);
    picker.dispose();
  });

  it("ohne Netz kommt statt der Rückfrage der Fehlertext; nichts wechselt", async () => {
    const { one } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);
    one.mine.offline = true;

    await picker.choose(klubOf(one).id);

    expect(picker.getUi()).toEqual({ busy: false, error: { kind: "Server", error: "OFFLINE", roundName: false }, confirm: null });
    expect(one.state.link?.roundId).toBe(stammtisch);
    picker.clearError();
    expect(picker.getUi().error).toBeNull();
    picker.dispose();
  });

  it("scheitert erst der Wechsel selbst (die Zielrunde gibt es für das Konto nicht mehr), steht der Fehler da", async () => {
    const { one, two } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);
    await picker.choose(klubOf(one).id);
    server.removeMember(two.state.link?.roundId ?? "", "konto-1");

    await picker.confirm();

    expect(picker.getUi().error).toEqual({ kind: "Server", error: "NOT_MEMBER", roundName: false });
    expect(one.state.link?.roundId).toBe(stammtisch);
    // Die Liste ist neu gelesen: Der Klub steht nicht mehr darin, und mit ihm verschwindet die Auswahl.
    expect(one.state.rounds?.map((it) => it.name)).toEqual(["Stammtisch"]);
    expect(showsPicker(one.state)).toBe(false);
    picker.dispose();
  });

  it("gibt es die verbundene Runde nicht mehr, kommt die Meldung nach `Detached` statt der Rückfrage", async () => {
    const { one } = await twoRounds();
    const picker = await pickerOf(one);
    server.removeMember(one.state.link?.roundId ?? "", "konto-1");

    await picker.choose(klubOf(one).id);

    expect(picker.getUi().confirm).toBeNull();
    expect(picker.getUi().error).toBeNull();
    expect(one.state.message).toBe("DETACHED");
    expect(one.state.link).toBeNull();
    picker.dispose();
  });

  it("wird das Gerät abgemeldet, während die Rückfrage offen ist, wird beim Bestätigen nicht gewechselt", async () => {
    const { one } = await twoRounds();
    const picker = await pickerOf(one);
    await picker.choose(klubOf(one).id);
    expect(picker.getUi().confirm).not.toBeNull();
    const local = one.store.getState().evenings;

    await one.controller.signOut();
    await pass();

    // Die Rückfrage bleibt, bis man sie beantwortet; „Wechseln“ scheitert dann.
    expect(picker.getUi().confirm).not.toBeNull();
    await picker.confirm();
    expect(picker.getUi().error).toEqual({ kind: "Server", error: "SIGNED_OUT", roundName: false });
    expect(one.state.link).toBeNull();
    expect(one.store.getState().evenings).toEqual(local);
    picker.dispose();
  });

  it("hängt das Gerät beim Bestätigen nicht mehr an der Runde der Rückfrage, wird nicht gewechselt", async () => {
    const { one } = await twoRounds();
    const picker = await pickerOf(one);
    await picker.choose(klubOf(one).id);
    const local = one.store.getState().evenings;

    // Inzwischen hat das Konto die Runde verlassen (etwa in einem anderen Fenster); die Daten bleiben als Kopie.
    await one.controller.leave();
    await picker.confirm();

    expect(picker.getUi().error).toEqual({ kind: "Server", error: "CHANGED_MEANWHILE", roundName: false });
    expect(one.state.link).toBeNull();
    expect(one.store.getState().evenings).toEqual(local);
    picker.dispose();
  });

  it("wartet beim Bestätigen mehr, als die Rückfrage nannte, wird nicht gewechselt", async () => {
    const { one } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);
    await picker.choose(klubOf(one).id);
    expect(picker.getUi().confirm?.from).toEqual(switchFrom(stammtisch, 0));

    // Inzwischen kommt ein Spiel dazu, dessen Abend der Server nicht annimmt.
    one.mine.saveEvening = async () => {
      throw new RoundException("TOO_LARGE");
    };
    await one.addGame("abend-1");
    await picker.confirm();

    expect(picker.getUi().error).toEqual({ kind: "Server", error: "CHANGED_MEANWHILE", roundName: false });
    expect(one.state.link?.roundId).toBe(stammtisch);
    expect(one.store.getState().evenings[0].entries).toHaveLength(2);
    picker.dispose();
  });

  it("wird der Start-Bildschirm mitten im Wechsel verlassen, läuft der Wechsel trotzdem zu Ende", async () => {
    const { one, two } = await twoRounds();
    const picker = await pickerOf(one);
    await picker.choose(klubOf(one).id);

    const confirming = picker.confirm();
    picker.dispose();
    await confirming;
    await pass();

    expect(one.state.link?.roundName).toBe("Klub");
    expect(one.store.getState().evenings).toEqual(two.store.getState().evenings);
    expect(one.state.switching).toBe(false);
  });

  it("antwortet der Server mit einem Fehler, steht der allgemeine Fehler da", async () => {
    const { one } = await twoRounds();
    const picker = await pickerOf(one);
    one.mine.head = async () => {
      throw new RoundException("SERVER", "HTTP 503");
    };

    await picker.choose(klubOf(one).id);

    expect(picker.getUi().error).toEqual({ kind: "Server", error: "SERVER", roundName: false });
    expect(picker.getUi().confirm).toBeNull();
    picker.dispose();
  });

  it("lässt sich auf dem Gerät nicht speichern, was der Abgleich holt, sagt der Fehler es wie die Statuszeile", async () => {
    const { one } = await twoRounds();
    const stammtisch = one.state.link?.roundId ?? "";
    const picker = await pickerOf(one);
    // Ein anderes Gerät desselben Kontos hat inzwischen einen Abend eingetragen; der Speicher dieses Geräts ist voll.
    const original = one.store.evening("abend-1");
    if (original === undefined) throw new Error("abend-1 fehlt");
    await server.sessionFor("konto-1").saveEvening(stammtisch, { ...original, id: "abend-2" }, 0);
    one.repository.failWrites = true;

    await picker.choose(klubOf(one).id);

    expect(picker.getUi().error).toEqual({ kind: "Storage" });
    expect(picker.getUi().confirm).toBeNull();
    expect(one.state.link?.roundId).toBe(stammtisch);
    one.repository.failWrites = false;
    picker.dispose();
  });
});
