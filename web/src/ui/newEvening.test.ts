import { describe, expect, it } from "vitest";
import { cleanName, defaultPlayers } from "../app/players";
import type { Evening, Player, RuleSet } from "../core/model";
import {
  adoptLastSeating,
  createEvening,
  EMPTY_SELECTION,
  moveSeat,
  newEveningView,
  seatNewPlayer,
  setFirstDealer,
  setLocation,
  toggleSeat,
} from "./newEvening";

const players: Player[] = defaultPlayers();
const extra: Player = { id: "p5", name: "Gast", colorIndex: 4, archived: false };
const rules = { name: "Test" } as unknown as RuleSet;

function evening(seating: string[]): Evening {
  return {
    id: "e1",
    startedAt: "2026-11-14T19:00:00Z",
    finishedAt: null,
    location: "",
    seating,
    firstDealer: seating[0],
    rules,
    entries: [],
  };
}

describe("Neuer Abend: Vorschlag", () => {
  it("schlägt die feste Runde vor, wenn es genau vier aktive Spieler gibt", () => {
    const view = newEveningView(players, [], EMPTY_SELECTION);
    expect(view.seating).toEqual(["spieler-1", "spieler-2", "spieler-3", "spieler-4"]);
    expect(view.firstDealer).toBe("spieler-1");
    expect(view.canStart).toBe(true);
    expect(view.canUseLast).toBe(false);
  });

  it("schlägt die Sitzordnung des letzten Abends vor", () => {
    const last = evening(["spieler-3", "p5", "spieler-1", "spieler-2"]);
    const view = newEveningView([...players, extra], [last], EMPTY_SELECTION);
    expect(view.seating).toEqual(["spieler-3", "p5", "spieler-1", "spieler-2"]);
    expect(view.canUseLast).toBe(true);
  });

  it("schlägt nichts vor, wenn es weder einen letzten Abend noch genau vier Spieler gibt", () => {
    const view = newEveningView([...players, extra], [], EMPTY_SELECTION);
    expect(view.seating).toEqual([]);
    expect(view.missing).toBe(4);
    expect(view.canStart).toBe(false);
  });

  it("lässt archivierte Spieler weg", () => {
    const archived = players.map((it) => (it.id === "spieler-2" ? { ...it, archived: true } : it));
    const last = evening(["spieler-1", "spieler-2", "spieler-3", "spieler-4"]);
    const view = newEveningView(archived, [last], EMPTY_SELECTION);
    expect(view.players.map((it) => it.id)).not.toContain("spieler-2");
    expect(view.lastSeating).toEqual(["spieler-1", "spieler-3", "spieler-4"]);
    expect(view.seating).toEqual([]);
  });
});

describe("Neuer Abend: Auswahl", () => {
  it("wählt ab, wählt höchstens vier und übernimmt dabei den Vorschlag", () => {
    const all = [...players, extra];
    let selection = EMPTY_SELECTION;
    let view = newEveningView(players, [], selection);
    selection = toggleSeat(view, selection, "spieler-2");
    view = newEveningView(all, [], selection);
    expect(view.seating).toEqual(["spieler-1", "spieler-3", "spieler-4"]);
    selection = toggleSeat(view, selection, "p5");
    view = newEveningView(all, [], selection);
    expect(view.seating).toEqual(["spieler-1", "spieler-3", "spieler-4", "p5"]);
    selection = toggleSeat(view, selection, "spieler-2");
    view = newEveningView(all, [], selection);
    expect(view.seating).toHaveLength(4);
    expect(view.seating).not.toContain("spieler-2");
  });

  it("setzt einen neuen Spieler an den Tisch, wenn Platz ist", () => {
    const all = [...players, extra];
    let selection = toggleSeat(newEveningView(players, [], EMPTY_SELECTION), EMPTY_SELECTION, "spieler-4");
    selection = seatNewPlayer(newEveningView(all, [], selection), selection, "p5");
    expect(newEveningView(all, [], selection).seating).toEqual(["spieler-1", "spieler-2", "spieler-3", "p5"]);
    // Voll besetzt: kein Platz mehr.
    const full = seatNewPlayer(newEveningView(all, [], selection), selection, "spieler-4");
    expect(newEveningView(all, [], full).seating).toEqual(["spieler-1", "spieler-2", "spieler-3", "p5"]);
  });

  it("verschiebt Plätze und hält die Grenzen ein", () => {
    let selection = EMPTY_SELECTION;
    let view = newEveningView(players, [], selection);
    selection = moveSeat(view, selection, "spieler-2", -1);
    view = newEveningView(players, [], selection);
    expect(view.seating).toEqual(["spieler-2", "spieler-1", "spieler-3", "spieler-4"]);
    selection = moveSeat(view, selection, "spieler-2", -1);
    expect(newEveningView(players, [], selection).seating).toEqual(["spieler-2", "spieler-1", "spieler-3", "spieler-4"]);
    selection = moveSeat(view, selection, "spieler-4", 1);
    expect(newEveningView(players, [], selection).seating).toEqual(["spieler-2", "spieler-1", "spieler-3", "spieler-4"]);
  });

  it("merkt sich den ersten Geber nur, solange er am Tisch sitzt", () => {
    let selection = setFirstDealer(EMPTY_SELECTION, "spieler-3");
    let view = newEveningView(players, [], selection);
    expect(view.firstDealer).toBe("spieler-3");
    selection = toggleSeat(view, selection, "spieler-3");
    view = newEveningView(players, [], selection);
    expect(view.firstDealer).toBe("spieler-1");
    expect(view.canStart).toBe(false);
  });

  it("übernimmt die letzte Sitzordnung samt erstem Geber", () => {
    const last = evening(["spieler-4", "spieler-3", "spieler-2", "spieler-1"]);
    const view = newEveningView(players, [last], toggleSeat(newEveningView(players, [last], EMPTY_SELECTION), EMPTY_SELECTION, "spieler-1"));
    const selection = adoptLastSeating(view, EMPTY_SELECTION);
    const after = newEveningView(players, [last], selection);
    expect(after.seating).toEqual(["spieler-4", "spieler-3", "spieler-2", "spieler-1"]);
    expect(after.firstDealer).toBe("spieler-4");
  });

  it("begrenzt den Ort auf 40 Zeichen und schneidet beim Anlegen Leerraum ab", () => {
    const selection = setLocation(EMPTY_SELECTION, `  ${"x".repeat(50)}`);
    expect(selection.location).toHaveLength(40);
    const view = newEveningView(players, [], setLocation(EMPTY_SELECTION, "  Gasthaus Post  "));
    const created = createEvening(view, rules, "neu", "2026-11-15T19:00:00Z");
    expect(created).toEqual({
      id: "neu",
      startedAt: "2026-11-15T19:00:00Z",
      finishedAt: null,
      location: "Gasthaus Post",
      seating: ["spieler-1", "spieler-2", "spieler-3", "spieler-4"],
      firstDealer: "spieler-1",
      rules,
      entries: [],
    });
  });

  it("legt keinen Abend an, solange jemand fehlt", () => {
    const view = newEveningView([...players, extra], [], EMPTY_SELECTION);
    expect(createEvening(view, rules, "neu", "2026-11-15T19:00:00Z")).toBeNull();
  });
});

describe("Spielernamen (wie Player.cleanName)", () => {
  it("bereinigt Leerraum und begrenzt auf 24 Zeichen", () => {
    expect(cleanName("  Anna  ")).toBe("Anna");
    expect(cleanName("Anna \t  Maria")).toBe("Anna Maria");
    expect(cleanName("")).toBeNull();
    expect(cleanName("   ")).toBeNull();
    expect(cleanName("x".repeat(24))).toBe("x".repeat(24));
    expect(cleanName("x".repeat(25))).toBeNull();
  });

  it("legt die feste Runde mit den Kennungen der Android-App an", () => {
    expect(defaultPlayers()).toEqual([
      { id: "spieler-1", name: "Spieler 1", colorIndex: 0, archived: false },
      { id: "spieler-2", name: "Spieler 2", colorIndex: 1, archived: false },
      { id: "spieler-3", name: "Spieler 3", colorIndex: 2, archived: false },
      { id: "spieler-4", name: "Spieler 4", colorIndex: 3, archived: false },
    ]);
  });
});
