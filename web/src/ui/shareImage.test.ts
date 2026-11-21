import { describe, expect, it, vi } from "vitest";
import { EveningCalculator } from "../core/evening/EveningCalculator";
import type { Evening } from "../core/model";
import { StatisticEngine } from "../core/stats/StatisticEngine";
import type { ShareOutcome } from "../platform/platform";
import { closingOf } from "./closing";
import {
  chartPaths,
  eveningShareImage,
  fitText,
  FONTS,
  gameTicks,
  IMAGE_DOWNLOADED,
  IMAGE_FAILED,
  IMAGE_WIDTH,
  imageShareMessage,
  layoutShareImage,
  legendLines,
  MAX_IMAGE_HEIGHT,
  mayStartSharing,
  ROW_HEIGHT,
  shareImageFile,
  statisticsShareImage,
  valueAxis,
  wrapText,
  type DrawOp,
  type MeasureText,
  type ShareImage,
  type ShareImageLayout,
} from "./shareImage";
import { balanceSeriesOf, statisticsSummary, statisticsTitle } from "./statistics";
import { game, testEvening } from "./testing";

const NBSP = "\u00a0";
const MINUS = "\u2212";
const names = new Map([
  ["spieler-1", "Anna"],
  ["spieler-2", "Bert"],
  ["spieler-3", "Cilli"],
  ["spieler-4", "Done"],
]);
const colors = new Map([
  ["spieler-1", 0],
  ["spieler-2", 1],
  ["spieler-3", 2],
  ["spieler-4", 3],
]);

/** Näherung für die Breite eines Textes: jedes Zeichen eine halbe Schriftgröße breit (ganze Pixel, ohne Rundung). */
const measure: MeasureText = (text, font) => (Array.from(text).length * font.size) / 2;

/** Rand links und rechts: Nichts darf darüber hinaus. */
const PAD_X = 56;

type TextOp = Extract<DrawOp, { kind: "text" }>;

function texts(layout: ShareImageLayout): TextOp[] {
  return layout.ops.filter((op): op is TextOp => op.kind === "text");
}

/** Linke und rechte Kante eines Textes, wie er gezeichnet wird. */
function extent(op: TextOp): [number, number] {
  const width = measure(op.text, op.font);
  if (op.align === "left") return [op.x, op.x + width];
  if (op.align === "right") return [op.x - width, op.x];
  return [op.x - width / 2, op.x + width / 2];
}

/** Alles liegt im Bild: Texte zwischen den Rändern, Linien und Punkte innerhalb des Bildes. */
function expectInside(layout: ShareImageLayout): void {
  expect(layout.width).toBe(IMAGE_WIDTH);
  for (const op of layout.ops) {
    if (op.kind === "text") {
      const [left, right] = extent(op);
      expect(left, op.text).toBeGreaterThanOrEqual(PAD_X - 0.5);
      expect(right, op.text).toBeLessThanOrEqual(IMAGE_WIDTH - PAD_X + 0.5);
      expect(op.y - op.font.size / 2, op.text).toBeGreaterThanOrEqual(0);
      expect(op.y + op.font.size / 2, op.text).toBeLessThanOrEqual(layout.height);
    } else if (op.kind === "line") {
      for (const x of [op.x1, op.x2]) expect(x).toBeGreaterThanOrEqual(0);
      for (const x of [op.x1, op.x2]) expect(x).toBeLessThanOrEqual(IMAGE_WIDTH);
      for (const y of [op.y1, op.y2]) expect(y).toBeLessThanOrEqual(layout.height);
    } else if (op.kind === "path") {
      for (const [x, y] of op.points) {
        expect(x).toBeGreaterThanOrEqual(PAD_X);
        expect(x).toBeLessThanOrEqual(IMAGE_WIDTH - PAD_X);
        expect(y).toBeLessThanOrEqual(layout.height);
      }
    }
  }
}

/**
 * Vier Spiele: Anna und Cilli gewinnen ein Rufspiel, Bert verliert ein Farbsolo, Done gewinnt einen Wenz, Anna und
 * Done gewinnen ein Rufspiel. Endstand: Done +2,00, Anna +0,20, Cilli 0,00, Bert −2,20.
 */
function playedEvening(overrides: Partial<Evening> = {}): Evening {
  return testEvening(
    [
      game("g1"),
      game("g2", { type: "FARBSOLO", suit: "HERZ", declarer: "spieler-2", partner: null, declarerWon: false }),
      game("g3", { type: "WENZ", declarer: "spieler-4", partner: null }),
      game("g4", { declarer: "spieler-1", partner: "spieler-4" }),
    ],
    { location: "Gasthaus Post", ...overrides },
  );
}

function eveningImage(evening: Evening, nameMap: ReadonlyMap<string, string> = names): ShareImage {
  const state = EveningCalculator.compute(evening);
  return eveningShareImage({
    state,
    closing: closingOf(state, nameMap),
    series: balanceSeriesOf(StatisticEngine.forEvening(evening), nameMap, colors),
    names: nameMap,
    colors,
  });
}

describe("Inhalt des Bildes", () => {
  it("„Abend teilen“: Datum mit Ort, Spiele, Endstand, Verlauf, wer wem zahlt – wie auf dem Bildschirm", () => {
    const image = eveningImage(playedEvening());
    expect(image.title).toBe("Sa., 14. Nov. 2026 · Gasthaus Post");
    expect(image.subtitle).toBe(`4${NBSP}Spiele · Der Abend läuft noch.`);
    expect(image.blocks.map((it) => (it.kind === "note" ? it.text : it.heading))).toEqual(["Endstand", "Vermögensverlauf", "Wer zahlt wem"]);

    const [standings, history, transfers] = image.blocks;
    if (standings.kind !== "table" || history.kind !== "chart" || transfers.kind !== "table") throw new Error("Aufbau");
    expect(standings.rows.map((row) => [row.prefix, row.label, row.colorIndex, row.cells.map((it) => [it.text, it.color])])).toEqual([
      ["1. ", "Done", 3, [[`+2,00${NBSP}€`, "gain"]]],
      ["2. ", "Anna", 0, [[`+0,20${NBSP}€`, "gain"]]],
      ["3. ", "Cilli", 2, [[`0,00${NBSP}€`, "text"]]],
      ["4. ", "Bert", 1, [[`${MINUS}2,20${NBSP}€`, "loss"]]],
    ]);
    expect(standings.rows.every((row) => row.isName)).toBe(true);

    // Der Verlauf wie in der Statistik dieses Abends: Start und nach jedem Spiel, Spieler in Sitzreihenfolge.
    expect(history.chart?.series.map((it) => [it.name, it.colorIndex, it.values])).toEqual([
      ["Anna", 0, [0, 10, 60, 10, 20]],
      ["Bert", 1, [0, -10, -160, -210, -220]],
      ["Cilli", 2, [0, 10, 60, 10, 0]],
      ["Done", 3, [0, -10, 40, 190, 200]],
    ]);
    expect(history.chart?.boundaries).toEqual([]);

    expect(transfers.rows.map((row) => [row.label, row.isName, row.colorIndex, row.cells[0].text, row.cells[0].bold])).toEqual([
      ["Bert zahlt Done", false, null, `2,00${NBSP}€`, true],
      ["Bert zahlt Anna", false, null, `0,20${NBSP}€`, true],
    ]);
    expect(transfers.empty).toBe("Niemand muss etwas zahlen.");
  });

  it("beendeter Abend ohne Spiele: kein Verlauf, niemand zahlt, ohne Ort nur das Datum", () => {
    const image = eveningImage(testEvening([], { finishedAt: "2026-11-14T23:00:00Z" }));
    expect(image.title).toBe("Sa., 14. Nov. 2026");
    expect(image.subtitle).toBe(`0${NBSP}Spiele`);
    const history = image.blocks[1];
    expect(history.kind === "chart" && history.chart).toBeNull();
    const layout = layoutShareImage(image, measure);
    const words = texts(layout).map((it) => it.text);
    expect(words).toContain("Der Verlauf erscheint nach dem ersten Spiel.");
    expect(words).toContain("Niemand muss etwas zahlen.");
    expect(layout.ops.some((op) => op.kind === "path")).toBe(false);
    expectInside(layout);
  });

  it("gehen die Stände nicht auf, steht der Hinweis darunter in der Farbe für Fehler", () => {
    const evening = testEvening([], { location: "" });
    const state = EveningCalculator.compute(evening);
    const closing = { ...closingOf(state, names), balanced: false };
    const image = eveningShareImage({ state, closing, series: [], names, colors });
    expect(image.blocks.at(-1)).toEqual({
      kind: "note",
      text: "Die Stände gehen nicht auf (eine freie Buchung ist fehlerhaft); ein Rest bleibt offen.",
      color: "error",
    });
    const note = texts(layoutShareImage(image, measure)).filter((it) => it.color === "error");
    expect(note.map((it) => it.text).join(" ")).toBe("Die Stände gehen nicht auf (eine freie Buchung ist fehlerhaft); ein Rest bleibt offen.");
  });

  it("Statistik: Auswahl, Zusammenfassung, Kontostand mit Kopfzeile und Verlauf; Linien nur zwischen Abenden", () => {
    const first = playedEvening({ finishedAt: "2026-11-14T23:00:00Z" });
    const second = testEvening([game("h1"), game("h2", { declarerWon: false })], { id: "abend-2", startedAt: "2026-11-21T12:00:00Z" });
    const stats = StatisticEngine.forEvenings([first, second]);
    const image = statisticsShareImage({
      stats,
      title: statisticsTitle(null),
      summary: statisticsSummary(stats, false, true),
      series: balanceSeriesOf(stats, names, colors),
      single: false,
      asMoney: true,
      names,
      colors,
    });
    expect(image.title).toBe("Alle Abende (ewige Tabelle)");
    // Umsatz: alle Gewinne zusammen, 3,40 € am ersten Abend und 0,40 € am zweiten.
    expect(image.subtitle).toBe(`2${NBSP}Abende · 6${NBSP}Spiele · Umsatz 3,80${NBSP}€`);
    const [account, history] = image.blocks;
    if (account.kind !== "table" || history.kind !== "chart") throw new Error("Aufbau");
    expect(account.heading).toBe("Kontostand");
    expect(account.header).toEqual(["Spieler", "Saldo", "Spiele", "gewonnen"]);
    expect(account.rows.map((row) => [row.prefix, row.label, ...row.cells.map((it) => it.text)])).toEqual([
      ["1. ", "Done", `+2,00${NBSP}€`, "6", `67${NBSP}%`],
      ["2. ", "Anna", `+0,20${NBSP}€`, "6", `67${NBSP}%`],
      ["3. ", "Cilli", `0,00${NBSP}€`, "6", `50${NBSP}%`],
      ["4. ", "Bert", `${MINUS}2,20${NBSP}€`, "6", `17${NBSP}%`],
    ]);
    // Der zweite Abend beginnt nach Spiel 4.
    expect(history.chart?.boundaries).toEqual([0, 4]);

    const layout = layoutShareImage(image, measure);
    const dashed = layout.ops.filter((op) => op.kind === "line" && op.dash.length > 0);
    expect(dashed).toHaveLength(1);
    expect(texts(layout).map((it) => it.text)).toEqual(expect.arrayContaining(["Spieler", "Saldo", "Spiele", "gewonnen"]));
    expectInside(layout);

    // Ein einzelner Abend: „Abend vom …“ und keine Linien zwischen Abenden.
    const single = StatisticEngine.forEvening(first);
    const one = statisticsShareImage({
      stats: single,
      title: statisticsTitle(first),
      summary: statisticsSummary(single, true, true),
      series: balanceSeriesOf(single, names, colors),
      single: true,
      asMoney: true,
      names,
      colors,
    });
    expect(one.title).toBe("Abend vom Sa., 14. Nov. 2026 · Gasthaus Post");
    expect(one.subtitle).toBe(`4${NBSP}Spiele · Umsatz 3,40${NBSP}€`);
    expect(one.blocks[1].kind === "chart" && one.blocks[1].chart?.boundaries).toEqual([]);
  });
});

describe("Achsen des Diagramms", () => {
  it("die senkrechte Achse schließt die Null ein und reicht über alle Werte", () => {
    expect(valueAxis([0, 60, -160, 10])).toEqual({ min: -200, max: 100, ticks: [-200, -150, -100, -50, 0, 50, 100] });
    const gains = valueAxis([0, 30, 120]);
    expect(gains.min).toBe(0);
    expect(gains.max).toBeGreaterThanOrEqual(120);
    const losses = valueAxis([0, -5, -45]);
    expect(losses.max).toBe(0);
    expect(losses.min).toBeLessThanOrEqual(-45);
  });

  it("wählt runde Schritte in ganzen Cent", () => {
    for (const values of [[0, 1], [0, 3], [0, -7], [0, 1234, -987], [0, 99999], [-2500, 4000]]) {
      const axis = valueAxis(values);
      const steps = axis.ticks.slice(1).map((tick, index) => tick - axis.ticks[index]);
      expect(new Set(steps).size, String(values)).toBe(1);
      expect(String(steps[0]), String(values)).toMatch(/^(1|2|25|5)0*$/);
      expect(axis.ticks).toContain(0);
      expect(axis.ticks.length).toBeGreaterThanOrEqual(2);
      expect(axis.ticks.length).toBeLessThanOrEqual(8);
      expect(axis.min).toBeLessThanOrEqual(Math.min(...values));
      expect(axis.max).toBeGreaterThanOrEqual(Math.max(...values));
    }
  });

  it("sind alle Werte null, reicht die Achse von −1 bis +1 um die Null", () => {
    expect(valueAxis([0, 0, 0])).toEqual({ min: -100, max: 100, ticks: [-100, -50, 0, 50, 100] });
  });

  it("die waagerechte Achse zeigt einige Spielnummern ab 0 mit genug Abstand", () => {
    expect(gameTicks(4, 600)).toEqual([0, 1, 2, 3, 4]);
    expect(gameTicks(13, 600)).toEqual([0, 2, 4, 6, 8, 10, 12]);
    expect(gameTicks(20, 600)).toEqual([0, 5, 10, 15, 20]);
    expect(gameTicks(1, 600)).toEqual([0, 1]);
    expect(gameTicks(250, 700)).toEqual([0, 50, 100, 150, 200, 250]);
    for (let last = 1; last <= 400; last++) {
      const ticks = gameTicks(last, 640);
      const step = ticks[1] - ticks[0];
      expect(ticks[0]).toBe(0);
      expect((step / last) * 640, String(last)).toBeGreaterThanOrEqual(80);
      expect(ticks.at(-1)).toBeLessThanOrEqual(last);
      expect(last - (ticks.at(-1) ?? 0)).toBeLessThan(step);
    }
  });
});

describe("Texte, die nicht überstehen", () => {
  const font = FONTS.endLabel; // 34 px: 17 px je Zeichen in der Näherung

  it("kürzt zu lange Namen mit „…“, ohne Zeichen zu zerteilen", () => {
    expect(fitText("Anna", 200, font, measure)).toBe("Anna");
    expect(fitText("Maximilian-Alexander", 200, font, measure)).toBe("Maximilian…");
    // Kein Leerzeichen vor „…“.
    expect(fitText("Anna Bertha", 6 * 17, font, measure)).toBe("Anna…");
    expect(fitText("👍👍👍👍👍👍👍👍", 100, font, measure)).toBe("👍👍👍👍…");
    expect(fitText("Anna", 1, font, measure)).toBe("…");
  });

  it("bricht lange Titel an Leerzeichen um; „4 Spiele“ bleibt zusammen", () => {
    const title = "Sa., 14. Nov. 2026 · Klinikum Bamberg, Buger Straße 80, 96049 Bamberg";
    const lines = wrapText(title, 968, FONTS.title, measure);
    expect(lines).toEqual(["Sa., 14. Nov. 2026 · Klinikum", "Bamberg, Buger Straße 80, 96049", "Bamberg"]);
    for (const line of lines) expect(measure(line, FONTS.title)).toBeLessThanOrEqual(968);
    expect(wrapText(`Spiel 1 · 4${NBSP}Spiele`, 12 * 20, FONTS.subtitle, measure)).toEqual(["Spiel 1 ·", `4${NBSP}Spiele`]);
  });

  it("trennt ein zu langes Wort und endet nach der letzten erlaubten Zeile mit „…“", () => {
    const width = 10 * 20;
    expect(wrapText("Donaudampfschifffahrtsgesellschaft", width, FONTS.note, measure)).toEqual(["Donaudampf", "schifffahr", "tsgesellsc", "haft"]);
    expect(wrapText("Donaudampfschifffahrtsgesellschaft", width, FONTS.note, measure, 2)).toEqual(["Donaudampf", "schifffah…"]);
    expect(wrapText("", width, FONTS.note, measure)).toEqual([]);
  });

  it("die Legende bricht in Zeilen um und kürzt nur, was allein nicht in eine Zeile passt", () => {
    const four = legendLines(
      ["Anna", "Bert", "Cilli", "Done"].map((name, colorIndex) => ({ name, colorIndex })),
      968,
      measure,
    );
    expect(four.map((line) => line.map((it) => it.name))).toEqual([["Anna", "Bert", "Cilli", "Done"]]);

    const six = Array.from({ length: 6 }, (_, index) => ({ name: `Spielerin Nummer ${index + 1}`, colorIndex: index }));
    const lines = legendLines(six, 968, measure);
    expect(lines.map((line) => line.length)).toEqual([2, 2, 2]);
    expect(lines.flat().map((it) => it.name)).toEqual(six.map((it) => it.name));
    for (const line of lines) {
      expect(line[0].x).toBe(0);
      const lastItem = line[line.length - 1];
      expect(lastItem.x + lastItem.width).toBeLessThanOrEqual(968);
    }

    const [[huge]] = legendLines([{ name: "X".repeat(200), colorIndex: 0 }], 968, measure);
    expect(huge.name.endsWith("…")).toBe(true);
    expect(huge.width).toBeLessThanOrEqual(968);
  });
});

describe("Anordnung des Bildes", () => {
  it("1080 Pixel breit, alles im Bild, in der Reihenfolge des Bildschirms", () => {
    const layout = layoutShareImage(eveningImage(playedEvening()), measure);
    expectInside(layout);
    const words = texts(layout).map((it) => it.text);
    const order = ["Sa., 14. Nov. 2026 · Gasthaus Post", "Endstand", "Done", "Bert", "Vermögensverlauf", "Wer zahlt wem", "Bert zahlt Done"];
    const positions = order.map((word) => words.indexOf(word));
    expect(positions.every((it) => it >= 0)).toBe(true);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
    // Unten bleibt der Rand frei.
    const lowest = Math.max(...layout.ops.map((op) => (op.kind === "text" ? op.y + op.font.size / 2 : op.kind === "line" ? Math.max(op.y1, op.y2) : 0)));
    expect(layout.height - lowest).toBeGreaterThanOrEqual(40);
  });

  it("das Diagramm: dicke Linien in den Spielerfarben, eine deutliche Null, Beträge links, Spiele ab 0, Legende", () => {
    const layout = layoutShareImage(eveningImage(playedEvening()), measure);
    const paths = layout.ops.filter((op): op is Extract<DrawOp, { kind: "path" }> => op.kind === "path");
    expect(paths.map((it) => it.color)).toEqual(["player-0", "player-1", "player-2", "player-3"]);
    expect(paths.every((it) => it.width >= 4)).toBe(true);

    const lines = layout.ops.filter((op): op is Extract<DrawOp, { kind: "line" }> => op.kind === "line");
    const zero = lines.filter((it) => it.color === "zero");
    expect(zero).toHaveLength(1);
    const grid = lines.filter((it) => it.color === "grid" && it.y1 === it.y2 && it.x1 === zero[0].x1);
    expect(zero[0].width).toBeGreaterThan(Math.max(...grid.map((it) => it.width)));
    // Alle Linien beginnen links auf der Null (paarweise nebeneinander: Anna und Cilli gewinnen das erste Spiel).
    for (const path of paths) {
      expect(path.points[0][0]).toBeCloseTo(zero[0].x1, 0);
      expect(Math.abs(path.points[0][1] - zero[0].y1)).toBeLessThanOrEqual(path.width);
    }
    // Linien steigen nach oben: Done (+2,00) endet oben, Bert (−2,20) unten.
    expect(paths[3].points.at(-1)?.[1]).toBeLessThan(zero[0].y1);
    expect(paths[1].points.at(-1)?.[1]).toBeGreaterThan(zero[0].y1);

    const tickTexts = texts(layout).filter((it) => it.font === FONTS.tick).map((it) => it.text);
    expect(tickTexts).toEqual(expect.arrayContaining([`0,00${NBSP}€`, `2,00${NBSP}€`, `${MINUS}2,00${NBSP}€`, "0", "1", "4", "Spiele"]));

    // Legende: Farbstrich und Name je Spieler, Name in Textfarbe.
    const legend = texts(layout).filter((it) => it.font === FONTS.legend);
    expect(legend.map((it) => [it.text, it.color])).toEqual([
      ["Anna", "text"],
      ["Bert", "text"],
      ["Cilli", "text"],
      ["Done", "text"],
    ]);
    const swatches = lines.filter((it) => it.round && it.y1 === it.y2 && it.y1 > zero[0].y1 + 300);
    expect(swatches.map((it) => it.color)).toEqual(["player-0", "player-1", "player-2", "player-3"]);
    // Rechts je Spieler ein Punkt in seiner Farbe vor dem Namen; eine feine Linie in seiner Farbe führt vom Ende seiner
    // Linie dorthin. So gehört jeder Name zur richtigen Farbe, auch wo Linien gleich enden.
    const dots = layout.ops.filter((op): op is Extract<DrawOp, { kind: "dot" }> => op.kind === "dot" && op.ring > 0);
    const labels = texts(layout).filter((it) => it.font === FONTS.endLabel);
    expect(labels.map((it) => it.text)).toEqual(["Anna", "Bert", "Cilli", "Done"]);
    expect(dots.map((it) => it.color)).toEqual(["player-0", "player-1", "player-2", "player-3"]);
    dots.forEach((dot, index) => {
      expect(dot.y).toBe(labels[index].y);
      expect(dot.x).toBeLessThan(labels[index].x);
      const leader = lines.find((it) => it.color === dot.color && it.x2 === dot.x - dot.radius && it.y2 === dot.y);
      expect(leader?.x1).toBe(paths[index].points.at(-1)?.[0]);
      expect(leader?.y1).toBe(paths[index].points.at(-1)?.[1]);
    });
  });

  it("Linien, die genau gleich verlaufen, liegen nebeneinander statt übereinander", () => {
    const x = (index: number) => 100 + index * 100;
    const y = (value: number) => 500 - value;
    // Anna und Cilli gewinnen zusammen, dann trennen sich ihre Wege; Bert verliert allein.
    const [anna, cilli, bert] = chartPaths(
      [
        [0, 10, 60],
        [0, 10, 0],
        [0, -20, -20],
      ],
      x,
      y,
      6,
    );
    // Erstes Stück: Anna und Cilli je 3 Pixel quer zur Linie versetzt, zusammen genau eine Linienbreite auseinander.
    const offset = (a: readonly [number, number], b: readonly [number, number]) => Math.hypot(a[0] - b[0], a[1] - b[1]);
    expect(offset(anna[0], cilli[0])).toBeCloseTo(6);
    expect(offset(anna[1], cilli[1])).toBeCloseTo(6);
    expect((anna[0][1] + cilli[0][1]) / 2).toBeCloseTo(500);
    expect((anna[1][1] + cilli[1][1]) / 2).toBeCloseTo(490);
    // Danach wieder genau auf den Werten; Bert ist nie versetzt.
    expect(anna.at(-1)).toEqual([300, 440]);
    expect(cilli.at(-1)).toEqual([300, 500]);
    expect(bert).toEqual([
      [100, 500],
      [200, 520],
      [300, 520],
    ]);
    // Drei gleiche Linien: in der Mitte die zweite, die anderen eine Linienbreite daneben.
    const three = chartPaths([[5, 5], [5, 5], [5, 5]], x, y, 6);
    expect(three.map((path) => path.map(([, py]) => py))).toEqual([
      [489, 489],
      [495, 495],
      [501, 501],
    ]);
  });

  it("jede weitere Zeile macht das Bild genau eine Zeilenhöhe höher", () => {
    const image = eveningImage(playedEvening());
    const transfers = image.blocks[2];
    if (transfers.kind !== "table") throw new Error("Aufbau");
    const more: ShareImage = {
      ...image,
      blocks: [image.blocks[0], image.blocks[1], { ...transfers, rows: [...transfers.rows, transfers.rows[0]] }],
    };
    expect(layoutShareImage(more, measure).height - layoutShareImage(image, measure).height).toBe(ROW_HEIGHT);
  });

  it("lange Namen und Orte stehen nie über: Orte umbrochen, Namen gekürzt, „zahlt“-Zeilen zweizeilig", () => {
    // Namen haben höchstens 24 Zeichen.
    const long = new Map([
      ["spieler-1", "Annalena-Maximiliane Hub"],
      ["spieler-2", "Bartholomäus Brandstette"],
      ["spieler-3", "Cilli"],
      ["spieler-4", "Done"],
    ]);
    const short = eveningImage(playedEvening({ location: "Post" }), long);
    const wide = eveningImage(playedEvening({ location: "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg" }), long);
    const shortLayout = layoutShareImage(short, measure);
    const wideLayout = layoutShareImage(wide, measure);
    expectInside(shortLayout);
    expectInside(wideLayout);
    const titleLines = texts(wideLayout).filter((it) => it.font === FONTS.title);
    expect(titleLines.length).toBe(3);
    expect(titleLines.map((it) => it.text).join(" ")).toBe("Sa., 14. Nov. 2026 · Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
    expect(wideLayout.height - shortLayout.height).toBe(titleLines[2].y - titleLines[0].y);
    // „Bartholomäus Brandstette zahlt Annalena-Maximiliane Hub“ steht in zwei Zeilen, der Betrag auf halber Höhe.
    const all = texts(wideLayout);
    const first = all.findIndex((it) => it.text === "Bartholomäus Brandstette zahlt");
    expect(first).toBeGreaterThan(0);
    const [line1, line2, amount] = all.slice(first, first + 3);
    expect(line2.text).toBe("Annalena-Maximiliane Hub");
    expect(line2.x).toBe(line1.x);
    expect(line2.y).toBeGreaterThan(line1.y);
    expect(amount.text).toBe(`0,20${NBSP}€`);
    expect(amount.y).toBe((line1.y + line2.y) / 2);
  });

  it("viele Spieler über viele Abende: alles im Bild, Legende in mehreren Zeilen; höchstens 8000 Pixel hoch", () => {
    const players = Array.from({ length: 8 }, (_, index) => `spieler-${index + 1}`);
    const many = new Map(players.map((id, index) => [id, `Mitspielerin Nummer ${index + 1}`]));
    const evenings = Array.from({ length: 6 }, (_, k) =>
      testEvening(
        Array.from({ length: 30 }, (_, g) =>
          game(`g${k}-${g}`, { declarer: players[(k + g) % 4 + (k % 2) * 4], partner: players[(k + g + 2) % 4 + (k % 2) * 4], declarerWon: g % 3 !== 0 }),
        ),
        {
          id: `abend-${k}`,
          startedAt: `2026-0${k + 1}-10T18:00:00Z`,
          finishedAt: `2026-0${k + 1}-10T23:00:00Z`,
          seating: players.slice((k % 2) * 4, (k % 2) * 4 + 4),
          firstDealer: players[(k % 2) * 4],
        },
      ),
    );
    const stats = StatisticEngine.forEvenings(evenings);
    const nameColors = new Map(players.map((id, index) => [id, index]));
    const image = statisticsShareImage({
      stats,
      title: statisticsTitle(null),
      summary: statisticsSummary(stats, false, false),
      series: balanceSeriesOf(stats, many, nameColors),
      single: false,
      asMoney: false,
      names: many,
      colors: nameColors,
    });
    const layout = layoutShareImage(image, measure);
    expectInside(layout);
    expect(new Set(texts(layout).filter((it) => it.font === FONTS.legend).map((it) => it.y)).size).toBeGreaterThan(1);
    expect(layout.ops.filter((op) => op.kind === "line" && op.dash.length > 0)).toHaveLength(5);

    const tall: ShareImage = { title: "x", subtitle: "y", blocks: Array.from({ length: 30 }, () => image.blocks[0]) };
    expect(layoutShareImage(tall, measure).height).toBe(MAX_IMAGE_HEIGHT);
  });
});

describe("Als Bild teilen", () => {
  const file = new File([new Uint8Array([137, 80, 78, 71])], "schafkopf-abend.png", { type: "image/png" });

  it("gibt das Bild noch im selben Takt weiter, ohne vorher zu warten (Safari verlangt das fürs Teilen-Menü)", async () => {
    const saveFile = vi.fn(async (_: File): Promise<ShareOutcome> => "shared");
    const create = vi.fn(() => file);
    const result = shareImageFile({ saveFile }, create);
    // Ohne ein einziges `await` dazwischen: Bild erstellt und Teilen angestoßen.
    expect(create).toHaveBeenCalledTimes(1);
    expect(saveFile).toHaveBeenCalledWith(file);
    await expect(result).resolves.toBeNull();
  });

  it("meldet nur Download und Fehler; geteilt und abgebrochen bleibt still", async () => {
    const cases: Array<[ShareOutcome, string | null]> = [
      ["shared", null],
      ["cancelled", null],
      ["downloaded", "Das Bild ist heruntergeladen."],
      ["failed", "Das Bild ließ sich nicht erstellen."],
    ];
    for (const [outcome, message] of cases) {
      expect(imageShareMessage(outcome)).toBe(message);
      await expect(shareImageFile({ saveFile: async () => outcome }, () => file)).resolves.toBe(message);
    }
    expect(IMAGE_DOWNLOADED).toBe("Das Bild ist heruntergeladen.");
    await expect(shareImageFile({ saveFile: () => Promise.reject(new Error("kaputt")) }, () => file)).resolves.toBe(IMAGE_FAILED);
  });

  it("ein zweites Antippen, während das Teilen-Menü offen ist, startet nichts; nach drei Sekunden ohne Antwort schon", () => {
    expect(mayStartSharing(null, 1_000)).toBe(true);
    expect(mayStartSharing(1_000, 1_000)).toBe(false);
    expect(mayStartSharing(1_000, 3_999)).toBe(false);
    expect(mayStartSharing(1_000, 4_000)).toBe(true);
    // Uhr zurückgestellt: nicht hängen bleiben.
    expect(mayStartSharing(5_000, 1_000)).toBe(true);
  });

  it("lässt sich das Bild nicht erstellen, wird nichts weitergegeben", async () => {
    const saveFile = vi.fn(async (): Promise<ShareOutcome> => "shared");
    const result = shareImageFile({ saveFile }, () => {
      throw new Error("kein Canvas");
    });
    await expect(result).resolves.toBe("Das Bild ließ sich nicht erstellen.");
    expect(saveFile).not.toHaveBeenCalled();
  });
});
