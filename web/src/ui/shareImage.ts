/*
 * „Als Bild teilen“ (docs/ARCHITEKTUR.md, 4.4): der obere Teil von „Abend teilen“ bzw. der Statistik als Bild, 1080
 * Pixel breit. Hier steht nur reine Logik, ohne Canvas:
 * - der Inhalt des Bildes (`ShareImage`), gebaut aus denselben Texten wie der Bildschirm;
 * - die Anordnung (`layoutShareImage`): Zeichenbefehle mit festen Koordinaten und Farbrollen. Texte misst eine
 *   übergebene Funktion, damit lange Namen und Orte nie überstehen (Auslassungspunkte oder Umbruch);
 * - das Weitergeben (`shareImageFile`) und die Meldungen danach.
 * Gezeichnet wird in `shareImageCanvas.ts`, in den Farben des eingestellten Erscheinungsbilds.
 */
import type { EveningState } from "../core/evening/EveningCalculator";
import type { PlayerId } from "../core/model";
import type { Statistics } from "../core/stats/StatisticEngine";
import type { Platform, ShareOutcome } from "../platform/platform";
import { spreadLabels, type ChartSeries } from "./charts/BalanceChart";
import { closingSubtitle, closingTitle, transferText, type Closing } from "./closing";
import { amountTone, formatAmount, formatPercent } from "./format";
import { accountRows } from "./statistics";

// ---------------------------------------------------------------- Inhalt

/** Dateinamen der beiden Bilder. */
export const EVENING_IMAGE_NAME = "schafkopf-abend.png";
export const STATISTICS_IMAGE_NAME = "schafkopf-statistik.png";

/** Farbe als Rolle; welche Farbe das ist, entscheidet das Erscheinungsbild beim Zeichnen (dunkel oder hell). */
export type ColorRole = "text" | "muted" | "grid" | "zero" | "boundary" | "gain" | "loss" | "error" | `player-${number}`;

/** Die Farbe eines Spielers (wie `seriesClass`: acht Farben, danach wieder von vorn). */
export function playerColor(colorIndex: number): ColorRole {
  return `player-${((colorIndex % 8) + 8) % 8}`;
}

/** Eine rechtsbündige Spalte einer Zeile, zum Beispiel ein Betrag. */
export interface ShareCell {
  readonly text: string;
  readonly color: ColorRole;
  readonly bold: boolean;
}

/** Eine Zeile einer Liste: links Text, rechts Spalten. */
export interface ShareRow {
  /** Steht vor allem anderen, zum Beispiel „1. “; sonst leer. */
  readonly prefix: string;
  /** Farbe des Spielers als Punkt vor dem Text; `null`: ohne Punkt. */
  readonly colorIndex: number | null;
  readonly label: string;
  /** Der Name eines Spielers: fett und in einer Zeile (zu lang: mit „…“). Sonst normal, bei Bedarf in zwei Zeilen. */
  readonly isName: boolean;
  readonly cells: readonly ShareCell[];
}

export interface ShareChart {
  readonly series: readonly ChartSeries[];
  readonly asMoney: boolean;
  /** Punkte, an denen ein neuer Abend beginnt (feine gestrichelte Linien). */
  readonly boundaries: readonly number[];
}

export type ShareBlock =
  | {
      readonly kind: "table";
      readonly heading: string;
      /** Spaltenköpfe, der erste über dem Text; `null`: ohne Kopfzeile. */
      readonly header: readonly string[] | null;
      readonly rows: readonly ShareRow[];
      /** Steht da, wenn es keine Zeile gibt. */
      readonly empty: string;
    }
  | {
      readonly kind: "chart";
      readonly heading: string;
      /** `null`: noch kein Verlauf (weniger als zwei Punkte). */
      readonly chart: ShareChart | null;
      readonly empty: string;
    }
  | { readonly kind: "note"; readonly text: string; readonly color: ColorRole };

/** Was auf dem Bild steht, von oben nach unten. */
export interface ShareImage {
  readonly title: string;
  readonly subtitle: string;
  readonly blocks: readonly ShareBlock[];
}

const HISTORY_HEADING = "Vermögensverlauf";
const HISTORY_EMPTY = "Der Verlauf erscheint nach dem ersten Spiel.";

/** Der Verlauf fürs Bild; `null`, solange es weniger als zwei Punkte gibt (wie im Diagramm auf dem Bildschirm). */
function chartOf(series: readonly ChartSeries[], asMoney: boolean, boundaries: readonly number[]): ShareChart | null {
  const points = series.reduce((most, it) => Math.max(most, it.values.length), 0);
  return points >= 2 ? { series, asMoney, boundaries } : null;
}

/** Ein Betrag mit Vorzeichen, in der Farbe für Gewinn oder Verlust (wie `Amount`). */
function amountCell(cents: number, asMoney: boolean): ShareCell {
  const tone = amountTone(cents);
  return { text: formatAmount(cents, asMoney, true), color: tone === "neutral" ? "text" : tone, bold: false };
}

/**
 * „Abend teilen“ als Bild: Datum und Ort, Zahl der Spiele, Endstand, Vermögensverlauf und „Wer zahlt wem“ – genau der
 * obere Teil des Bildschirms.
 */
export function eveningShareImage(input: {
  readonly state: EveningState;
  readonly closing: Closing;
  readonly series: readonly ChartSeries[];
  readonly names: ReadonlyMap<PlayerId, string>;
  readonly colors: ReadonlyMap<PlayerId, number>;
}): ShareImage {
  const { state, closing, names, colors } = input;
  const asMoney = state.evening.rules.displayAsMoney;
  const blocks: ShareBlock[] = [
    {
      kind: "table",
      heading: "Endstand",
      header: null,
      rows: closing.standings.map((standing) => ({
        prefix: `${standing.rank}. `,
        colorIndex: colors.get(standing.player) ?? 0,
        label: standing.name,
        isName: true,
        cells: [amountCell(standing.balance, asMoney)],
      })),
      empty: "",
    },
    { kind: "chart", heading: HISTORY_HEADING, chart: chartOf(input.series, asMoney, []), empty: HISTORY_EMPTY },
    {
      kind: "table",
      heading: "Wer zahlt wem",
      header: null,
      rows: closing.transfers.map((transfer) => ({
        prefix: "",
        colorIndex: null,
        label: transferText(transfer, names),
        isName: false,
        cells: [{ text: formatAmount(transfer.amount, asMoney), color: "text", bold: true }],
      })),
      empty: "Niemand muss etwas zahlen.",
    },
  ];
  if (!closing.balanced) {
    blocks.push({
      kind: "note",
      text: "Die Stände gehen nicht auf (eine freie Buchung ist fehlerhaft); ein Rest bleibt offen.",
      color: "error",
    });
  }
  return { title: closingTitle(state.evening), subtitle: closingSubtitle(state), blocks };
}

/**
 * Die Statistik als Bild: die Auswahl (`title`), die Zusammenfassung, der Kontostand und der Vermögensverlauf – bei
 * allen Abenden mit den feinen Linien, wo ein Abend beginnt.
 */
export function statisticsShareImage(input: {
  readonly stats: Statistics;
  readonly title: string;
  readonly summary: string;
  readonly series: readonly ChartSeries[];
  /** `true`: ein einzelner Abend (ohne Linien zwischen Abenden). */
  readonly single: boolean;
  readonly asMoney: boolean;
  readonly names: ReadonlyMap<PlayerId, string>;
  readonly colors: ReadonlyMap<PlayerId, number>;
}): ShareImage {
  const { stats, asMoney, names, colors } = input;
  return {
    title: input.title,
    subtitle: input.summary,
    blocks: [
      {
        kind: "table",
        heading: "Kontostand",
        header: ["Spieler", "Saldo", "Spiele", "gewonnen"],
        rows: accountRows(stats).map((row) => ({
          prefix: `${row.rank}. `,
          colorIndex: colors.get(row.player) ?? 0,
          label: names.get(row.player) ?? "?",
          isName: true,
          cells: [
            amountCell(row.balance, asMoney),
            { text: String(row.games), color: "text", bold: false },
            { text: formatPercent(row.wonRate), color: "text", bold: false },
          ],
        })),
        empty: "",
      },
      {
        kind: "chart",
        heading: HISTORY_HEADING,
        chart: chartOf(input.series, asMoney, input.single ? [] : stats.finance.eveningStarts),
        empty: HISTORY_EMPTY,
      },
    ],
  };
}

// ---------------------------------------------------------------- Weitergeben

export const IMAGE_DOWNLOADED = "Das Bild ist heruntergeladen.";
export const IMAGE_FAILED = "Das Bild ließ sich nicht erstellen.";

/** Die Meldung nach dem Teilen; geteilt oder im Teilen-Menü abgebrochen: keine. */
export function imageShareMessage(outcome: ShareOutcome): string | null {
  switch (outcome) {
    case "downloaded":
      return IMAGE_DOWNLOADED;
    case "failed":
      return IMAGE_FAILED;
    default:
      return null;
  }
}

/** So lange gilt ein Teilen als unterwegs, wenn das Teilen-Menü nicht antwortet. */
export const SHARE_GUARD_MS = 3000;

/**
 * Ob ein Antippen ein neues Teilen starten darf: nicht, solange das vorige (begonnen um `startedAt`, `null`: keines)
 * noch unterwegs ist. Ein zweites Teilen-Menü schlüge fehl, und das Bild würde stattdessen heruntergeladen. Antwortet
 * das Teilen-Menü nie, geht es nach `SHARE_GUARD_MS` trotzdem wieder.
 */
export function mayStartSharing(startedAt: number | null, now: number): boolean {
  return startedAt === null || now - startedAt >= SHARE_GUARD_MS || now < startedAt;
}

/**
 * Erstellt das Bild und gibt es weiter (Teilen-Menü, sonst Download); liefert die Meldung dazu.
 *
 * Safari auf dem iPhone öffnet das Teilen-Menü (`navigator.share`) nur unmittelbar nach dem Antippen. Deshalb muss
 * `create` das Bild ohne Warten erstellen, und `saveFile` wird noch im selben Takt aufgerufen; das Teilen-Menü geht
 * dort vor dem ersten `await` auf. Erst auf das Ergebnis des Teilens wird gewartet.
 */
export function shareImageFile(platform: Pick<Platform, "saveFile">, create: () => File): Promise<string | null> {
  let file: File;
  try {
    file = create();
  } catch {
    return Promise.resolve(IMAGE_FAILED);
  }
  return platform.saveFile(file).then(imageShareMessage, () => IMAGE_FAILED);
}

// ---------------------------------------------------------------- Anordnung

/** Schrift: Größe in Pixeln und Stärke. Die Schriftfamilie ist die der Seite. */
export interface FontSpec {
  readonly size: number;
  readonly weight: 400 | 600 | 700;
}

/** Breite eines Textes in Pixeln in dieser Schrift. */
export type MeasureText = (text: string, font: FontSpec) => number;

/** Ein Zeichenbefehl. Bei Text ist `y` die Mitte der Zeile. */
export type DrawOp =
  | {
      readonly kind: "text";
      readonly text: string;
      readonly x: number;
      readonly y: number;
      readonly font: FontSpec;
      readonly color: ColorRole;
      readonly align: "left" | "right" | "center";
    }
  | {
      readonly kind: "line";
      readonly x1: number;
      readonly y1: number;
      readonly x2: number;
      readonly y2: number;
      readonly width: number;
      readonly color: ColorRole;
      /** Strichmuster (leer: durchgezogen). */
      readonly dash: readonly number[];
      /** Runde Enden (stehen um die halbe Breite über). */
      readonly round: boolean;
    }
  | {
      readonly kind: "path";
      readonly points: ReadonlyArray<readonly [number, number]>;
      readonly width: number;
      readonly color: ColorRole;
    }
  | {
      readonly kind: "dot";
      readonly x: number;
      readonly y: number;
      readonly radius: number;
      readonly color: ColorRole;
      /** Ring in der Hintergrundfarbe um den Punkt, damit er sich von Linien abhebt (0: keiner). */
      readonly ring: number;
    };

export interface ShareImageLayout {
  readonly width: number;
  readonly height: number;
  readonly ops: readonly DrawOp[];
}

/** Breite des Bildes in Pixeln. */
export const IMAGE_WIDTH = 1080;
/** Höher wird ein Bild nie (Grenze für Canvas in Safari); unten wird dann abgeschnitten. */
export const MAX_IMAGE_HEIGHT = 8000;

/*
 * Maße in Pixeln des Bildes: etwa das Dreifache des Bildschirms (360 Pixel breit), damit das Bild auf dem Handy so
 * groß wirkt wie die App.
 */
const PAD_X = 56;
const PAD_Y = 56;
const CONTENT_RIGHT = IMAGE_WIDTH - PAD_X;
const CONTENT_WIDTH = CONTENT_RIGHT - PAD_X;

export const FONTS = {
  title: { size: 52, weight: 700 },
  subtitle: { size: 40, weight: 400 },
  heading: { size: 40, weight: 600 },
  row: { size: 44, weight: 400 },
  rowBold: { size: 44, weight: 700 },
  header: { size: 34, weight: 600 },
  note: { size: 40, weight: 400 },
  tick: { size: 32, weight: 400 },
  endLabel: { size: 34, weight: 600 },
  legend: { size: 40, weight: 400 },
} as const satisfies Record<string, FontSpec>;

const TITLE_LINE = 66;
const SUBTITLE_LINE = 54;
const HEADING_LINE = 54;
const SECTION_GAP = 44;
const NOTE_LINE = 54;
const SEPARATOR = 2;

const HEADER_ROW = 64;
/** Höhe einer Zeile mit einer Textzeile; je weitere Textzeile `ROW_LINE` mehr. */
export const ROW_HEIGHT = 104;
const ROW_LINE = 56;
const COLUMN_GAP = 40;
const DOT_RADIUS = 13;
const DOT_GAP = 16;

const PLOT_TOP_GAP = 32;
const PLOT_HEIGHT = 560;
const Y_TICK_TARGET = 5;
const TICK_GAP = 20;
const X_TICK_ROW = 84;
const X_TICK_SPACING = 80;
const LINE_WIDTH = 6;
const GRID_WIDTH = 2;
const ZERO_WIDTH = 4;
const BOUNDARY_DASH = [14, 12] as const;
/** Rechts neben dem Diagramm: Abstand bis zum Punkt vor dem Namen, Punkt, Abstand zum Namen. */
const LEADER = 30;
const END_DOT = 10;
const END_RING = 4;
const END_NAME_GAP = 12;
const LEADER_WIDTH = 3;
const END_LABEL_MAX = 210;
const END_LABEL_GAP = 42;
const X_CAPTION = "Spiele";

const LEGEND_GAP = 16;
const LEGEND_LINE = 64;
const SWATCH_LENGTH = 44;
const SWATCH_WIDTH = 10;
const SWATCH_GAP = 16;
const LEGEND_ITEM_GAP = 48;

const ELLIPSIS = "…";

const segmenter = typeof Intl !== "undefined" && "Segmenter" in Intl ? new Intl.Segmenter("de", { granularity: "grapheme" }) : null;

/** Die Schriftzeichen eines Textes (zusammengesetzte Emoji bleiben ganz). */
function graphemes(text: string): string[] {
  return segmenter ? Array.from(segmenter.segment(text), (it) => it.segment) : Array.from(text);
}

/** Der Text, wenn er passt; sonst so weit gekürzt, dass er mit „…“ passt. */
export function fitText(text: string, maxWidth: number, font: FontSpec, measure: MeasureText): string {
  if (measure(text, font) <= maxWidth) return text;
  const parts = graphemes(text);
  const cut = (count: number) => `${parts.slice(0, count).join("").trimEnd()}${ELLIPSIS}`;
  // Größte Zahl von Zeichen, die mit „…“ noch passt (die Breite wächst mit jedem Zeichen).
  let low = 0;
  let high = parts.length - 1;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (measure(cut(middle), font) <= maxWidth) low = middle;
    else high = middle - 1;
  }
  return cut(low);
}

/**
 * Bricht einen Text an Leerzeichen in Zeilen um (geschützte Leerzeichen wie in „12 Spiele“ halten zusammen). Ein
 * Wort, das allein nicht in eine Zeile passt, wird zwischen Schriftzeichen getrennt. Mehr als `maxLines` Zeilen gibt
 * es nie: Die letzte endet dann mit „…“.
 */
export function wrapText(text: string, maxWidth: number, font: FontSpec, measure: MeasureText, maxLines = Infinity): string[] {
  const lines: string[] = [];
  let current = "";
  for (const word of text.split(" ").filter((it) => it !== "")) {
    const candidate = current === "" ? word : `${current} ${word}`;
    if (measure(candidate, font) <= maxWidth) {
      current = candidate;
      continue;
    }
    if (current !== "") lines.push(current);
    current = word;
    while (measure(current, font) > maxWidth) {
      const parts = graphemes(current);
      let count = 1;
      while (count < parts.length && measure(parts.slice(0, count + 1).join(""), font) <= maxWidth) count++;
      lines.push(parts.slice(0, count).join(""));
      current = parts.slice(count).join("");
    }
  }
  if (current !== "") lines.push(current);
  if (lines.length <= maxLines) return lines;
  const kept = lines.slice(0, Math.max(0, maxLines - 1));
  kept.push(fitText(lines.slice(kept.length).join(" "), maxWidth, font, measure));
  return kept;
}

/** Die senkrechte Achse: von `min` bis `max` in runden Schritten, die Null immer dabei. */
export interface ValueAxis {
  readonly min: number;
  readonly max: number;
  readonly ticks: readonly number[];
}

/** Ein runder Schritt in ganzen Cent (1, 2, 5, 10, 20, 25, 50, 100 …), mindestens `rough`. */
function niceStep(rough: number): number {
  if (!(rough > 1)) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  for (const factor of [1, 2, 2.5, 5, 10]) {
    const step = factor * magnitude;
    if (step >= rough && Number.isInteger(step)) return step;
  }
  return 10 * magnitude;
}

/**
 * Achse für Beträge in Cent: Sie reicht von unter dem kleinsten bis über den größten Wert, schließt die Null immer ein
 * und hat etwa `target` runde Abschnitte. Sind alle Werte null, reicht sie von −100 bis 100.
 */
export function valueAxis(values: Iterable<number>, target = Y_TICK_TARGET): ValueAxis {
  let low = 0;
  let high = 0;
  for (const value of values) {
    if (value < low) low = value;
    if (value > high) high = value;
  }
  if (low === high) {
    low = -100;
    high = 100;
  }
  const step = niceStep((high - low) / target);
  const min = Math.floor(low / step) * step;
  const max = Math.ceil(high / step) * step;
  const ticks: number[] = [];
  for (let tick = min; tick <= max; tick += step) ticks.push(tick);
  return { min, max, ticks };
}

/**
 * Spielnummern für die waagerechte Achse: ab 0 in runden Schritten (1, 2, 5, 10, 20, 25, 50 …), so dicht, wie es
 * `minSpacing` Pixel Abstand erlauben.
 */
export function gameTicks(last: number, plotWidth: number, minSpacing = X_TICK_SPACING): number[] {
  const count = Math.max(1, Math.floor(last));
  let step = count;
  search: for (let magnitude = 1; magnitude <= count; magnitude *= 10) {
    for (const factor of [1, 2, 2.5, 5]) {
      const candidate = factor * magnitude;
      if (Number.isInteger(candidate) && candidate <= count && (candidate / count) * plotWidth >= minSpacing) {
        step = candidate;
        break search;
      }
    }
  }
  const ticks: number[] = [];
  for (let tick = 0; tick <= count; tick += step) ticks.push(tick);
  return ticks;
}

/**
 * Die Linien des Diagramms als Punktfolgen in Pixeln, gerade von Punkt zu Punkt. Wo Linien auf einem Stück genau
 * gleich verlaufen (etwa die Partner eines Rufspiels), liegen sie dort nebeneinander statt übereinander, jede um
 * `width` versetzt (quer zum Stück); so bleibt jede Farbe zu sehen. Die Reihenfolge nebeneinander folgt der Liste.
 */
export function chartPaths(
  series: ReadonlyArray<readonly number[]>,
  x: (index: number) => number,
  y: (value: number) => number,
  width: number,
): Array<Array<readonly [number, number]>> {
  const paths = series.map(() => [] as Array<readonly [number, number]>);
  const segments = series.reduce((most, it) => Math.max(most, it.length), 0) - 1;
  const add = (path: Array<readonly [number, number]>, point: readonly [number, number]) => {
    const last = path[path.length - 1];
    if (!last || last[0] !== point[0] || last[1] !== point[1]) path.push(point);
  };
  for (let segment = 0; segment < segments; segment++) {
    // Linien mit genau demselben Stück (gleicher Wert davor und danach) bilden ein Bündel.
    const bundles = new Map<string, number[]>();
    series.forEach((values, index) => {
      if (segment + 1 >= values.length) return;
      const key = `${values[segment]}|${values[segment + 1]}`;
      const bundle = bundles.get(key);
      if (bundle) bundle.push(index);
      else bundles.set(key, [index]);
    });
    for (const bundle of bundles.values()) {
      bundle.forEach((index, rank) => {
        const values = series[index];
        const x0 = x(segment);
        const y0 = y(values[segment]);
        const x1 = x(segment + 1);
        const y1 = y(values[segment + 1]);
        const length = Math.hypot(x1 - x0, y1 - y0) || 1;
        const shift = (rank - (bundle.length - 1) / 2) * width;
        const dx = (-(y1 - y0) / length) * shift;
        const dy = ((x1 - x0) / length) * shift;
        add(paths[index], [x0 + dx, y0 + dy]);
        add(paths[index], [x1 + dx, y1 + dy]);
      });
    }
  }
  return paths;
}

/** Ein Eintrag der Legende; `x` zählt ab dem linken Rand der Legende. */
export interface LegendItem {
  readonly x: number;
  readonly width: number;
  readonly name: string;
  readonly colorIndex: number;
}

/** Die Legende in Zeilen: Farbstrich und Name je Spieler, nebeneinander, bis die Zeile voll ist. */
export function legendLines(
  series: ReadonlyArray<{ readonly name: string; readonly colorIndex: number }>,
  maxWidth: number,
  measure: MeasureText,
): LegendItem[][] {
  const lines: LegendItem[][] = [];
  let current: LegendItem[] = [];
  let end = 0;
  for (const it of series) {
    const name = fitText(it.name, maxWidth - SWATCH_LENGTH - SWATCH_GAP, FONTS.legend, measure);
    const width = SWATCH_LENGTH + SWATCH_GAP + measure(name, FONTS.legend);
    if (current.length > 0 && end + LEGEND_ITEM_GAP + width > maxWidth) {
      lines.push(current);
      current = [];
    }
    const x = current.length === 0 ? 0 : end + LEGEND_ITEM_GAP;
    current.push({ x, width, name, colorIndex: it.colorIndex });
    end = x + width;
  }
  if (current.length > 0) lines.push(current);
  return lines;
}

/** Sammelt die Zeichenbefehle und führt die aktuelle Höhe mit. */
class Painter {
  readonly ops: DrawOp[] = [];
  y = PAD_Y;

  constructor(readonly measure: MeasureText) {}

  text(text: string, x: number, y: number, font: FontSpec, color: ColorRole, align: "left" | "right" | "center" = "left"): void {
    this.ops.push({ kind: "text", text, x, y, font, color, align });
  }

  line(x1: number, y1: number, x2: number, y2: number, width: number, color: ColorRole, dash: readonly number[] = [], round = false): void {
    this.ops.push({ kind: "line", x1, y1, x2, y2, width, color, dash, round });
  }

  dot(x: number, y: number, radius: number, color: ColorRole, ring: number): void {
    this.ops.push({ kind: "dot", x, y, radius, color, ring });
  }

  /** Text über die ganze Breite, umgebrochen; rückt `y` weiter. */
  paragraph(text: string, font: FontSpec, lineHeight: number, color: ColorRole, maxLines: number): void {
    for (const line of wrapText(text, CONTENT_WIDTH, font, this.measure, maxLines)) {
      this.text(line, PAD_X, this.y + lineHeight / 2, font, color);
      this.y += lineHeight;
    }
  }

  separator(): void {
    this.line(PAD_X, this.y - SEPARATOR / 2, CONTENT_RIGHT, this.y - SEPARATOR / 2, SEPARATOR, "grid");
  }
}

function layoutTable(painter: Painter, table: Extract<ShareBlock, { kind: "table" }>): void {
  const measure = painter.measure;
  if (table.rows.length === 0) {
    painter.paragraph(table.empty, FONTS.note, NOTE_LINE, "muted", 3);
    return;
  }
  // Rechtsbündige Spalten, so breit wie ihr breitester Text; der Text links bekommt den Rest.
  const header = table.header;
  const columns = Math.max(header ? header.length - 1 : 0, ...table.rows.map((row) => row.cells.length));
  const widths = Array.from({ length: columns }, (_, column) => {
    let width = header?.[column + 1] ? measure(header[column + 1], FONTS.header) : 0;
    for (const row of table.rows) {
      const cell = row.cells[column];
      if (cell) width = Math.max(width, measure(cell.text, cell.bold ? FONTS.rowBold : FONTS.row));
    }
    return width;
  });
  const rights: number[] = [];
  let edge = CONTENT_RIGHT;
  for (let column = columns - 1; column >= 0; column--) {
    rights[column] = edge;
    edge -= widths[column] + COLUMN_GAP;
  }
  const labelRight = edge;

  if (header) {
    const middle = painter.y + HEADER_ROW / 2;
    painter.text(fitText(header[0] ?? "", labelRight - PAD_X, FONTS.header, measure), PAD_X, middle, FONTS.header, "muted");
    for (let column = 0; column < columns; column++) {
      if (header[column + 1]) painter.text(header[column + 1], rights[column], middle, FONTS.header, "muted", "right");
    }
    painter.y += HEADER_ROW;
    painter.separator();
  }

  for (const row of table.rows) {
    const prefixWidth = row.prefix === "" ? 0 : measure(row.prefix, FONTS.row);
    const dotX = PAD_X + prefixWidth + DOT_RADIUS;
    const labelX = row.colorIndex === null ? PAD_X + prefixWidth : dotX + DOT_RADIUS + DOT_GAP;
    const font = row.isName ? FONTS.rowBold : FONTS.row;
    const available = labelRight - labelX;
    const lines = row.isName ? [fitText(row.label, available, font, measure)] : wrapText(row.label, available, font, measure, 2);
    const height = ROW_HEIGHT + (lines.length - 1) * ROW_LINE;
    const middle = painter.y + height / 2;
    const first = middle - ((lines.length - 1) * ROW_LINE) / 2;
    if (row.prefix !== "") painter.text(row.prefix, PAD_X, first, FONTS.row, "text");
    if (row.colorIndex !== null) painter.dot(dotX, first, DOT_RADIUS, playerColor(row.colorIndex), 0);
    lines.forEach((line, index) => painter.text(line, labelX, first + index * ROW_LINE, font, "text"));
    row.cells.forEach((cell, column) => {
      painter.text(cell.text, rights[column], middle, cell.bold ? FONTS.rowBold : FONTS.row, cell.color, "right");
    });
    painter.y += height;
    painter.separator();
  }
}

function layoutChart(painter: Painter, block: Extract<ShareBlock, { kind: "chart" }>): void {
  const chart = block.chart;
  if (chart === null || chart.series.length === 0) {
    painter.paragraph(block.empty, FONTS.note, NOTE_LINE, "muted", 3);
    return;
  }
  const measure = painter.measure;
  const series = chart.series;
  const last = Math.max(1, series.reduce((most, it) => Math.max(most, it.values.length), 0) - 1);
  const axis = valueAxis(
    (function* () {
      for (const it of series) yield* it.values;
    })(),
  );

  // Links die Beträge der Achse, rechts die Namen an den Linienenden; dazwischen das Diagramm. Rechts unten steht
  // „Spiele“; die Spalte dort ist so breit, dass auch die halbe letzte Spielnummer daneben Platz hat.
  const tickTexts = axis.ticks.map((tick) => formatAmount(tick, chart.asMoney));
  const tickWidth = Math.max(...tickTexts.map((it) => measure(it, FONTS.tick)));
  const names = series.map((it) => fitText(it.name, END_LABEL_MAX, FONTS.endLabel, measure));
  const nameOffset = LEADER + 2 * END_DOT + END_NAME_GAP;
  const captionWidth = measure(X_CAPTION, FONTS.tick) + measure(String(last), FONTS.tick) / 2 + TICK_GAP;
  const labelWidth = Math.max(captionWidth, ...names.map((it) => nameOffset + measure(it, FONTS.endLabel)));
  const plotLeft = PAD_X + tickWidth + TICK_GAP;
  const plotRight = CONTENT_RIGHT - labelWidth;
  const plotTop = painter.y + PLOT_TOP_GAP;
  const plotBottom = plotTop + PLOT_HEIGHT;
  const x = (index: number) => plotLeft + (index / last) * (plotRight - plotLeft);
  const y = (value: number) => plotTop + (1 - (value - axis.min) / (axis.max - axis.min)) * PLOT_HEIGHT;

  // Gitter und Beträge; die Null kommt nach den Linien der Abende und ist kräftiger als das Gitter.
  axis.ticks.forEach((tick, index) => {
    if (tick !== 0) painter.line(plotLeft, y(tick), plotRight, y(tick), GRID_WIDTH, "grid");
    painter.text(tickTexts[index], plotLeft - TICK_GAP, y(tick), FONTS.tick, "muted", "right");
  });
  for (const boundary of chart.boundaries) {
    if (boundary > 0 && boundary < last) painter.line(x(boundary), plotTop, x(boundary), plotBottom, GRID_WIDTH, "boundary", BOUNDARY_DASH);
  }
  painter.line(plotLeft, y(0), plotRight, y(0), ZERO_WIDTH, "zero");

  // Waagerechte Achse: einige Spielnummern ab 0, ganz rechts „Spiele“.
  const tickRow = plotBottom + X_TICK_ROW / 2;
  for (const tick of gameTicks(last, plotRight - plotLeft)) painter.text(String(tick), x(tick), tickRow, FONTS.tick, "muted", "center");
  painter.text(X_CAPTION, CONTENT_RIGHT, tickRow, FONTS.tick, "muted", "right");

  // Eine Linie je Spieler in seiner Farbe, gerade Stücke von Punkt zu Punkt wie auf dem Bildschirm; was genau
  // gleich verläuft, nebeneinander.
  const paths = chartPaths(
    series.map((it) => it.values),
    x,
    y,
    LINE_WIDTH,
  );
  series.forEach((it, index) => {
    painter.ops.push({ kind: "path", points: paths[index], width: LINE_WIDTH, color: playerColor(it.colorIndex) });
  });
  // Rechts daneben je Spieler ein Punkt in seiner Farbe und der Name (in Textfarbe), auseinandergeschoben, wo Linien
  // nah beieinander enden; eine feine Linie in seiner Farbe führt vom Ende seiner Linie dorthin.
  const ends = paths.map((path) => path[path.length - 1] ?? [plotRight, y(0)]);
  const labelYs = spreadLabels(
    ends.map((it) => it[1]),
    END_LABEL_GAP,
    plotTop,
    plotBottom,
  );
  const dotX = plotRight + LEADER + END_DOT;
  series.forEach((it, index) => {
    const [endX, endY] = ends[index];
    painter.line(endX, endY, dotX - END_DOT, labelYs[index], LEADER_WIDTH, playerColor(it.colorIndex), [], true);
  });
  series.forEach((it, index) => {
    painter.dot(dotX, labelYs[index], END_DOT, playerColor(it.colorIndex), END_RING);
    painter.text(names[index], plotRight + nameOffset, labelYs[index], FONTS.endLabel, "text");
  });
  painter.y = plotBottom + X_TICK_ROW;

  // Legende: Farbstrich und Name je Spieler.
  painter.y += LEGEND_GAP;
  for (const line of legendLines(series, CONTENT_WIDTH, measure)) {
    const middle = painter.y + LEGEND_LINE / 2;
    for (const item of line) {
      const start = PAD_X + item.x;
      const color = playerColor(item.colorIndex);
      painter.line(start + SWATCH_WIDTH / 2, middle, start + SWATCH_LENGTH - SWATCH_WIDTH / 2, middle, SWATCH_WIDTH, color, [], true);
      painter.text(item.name, start + SWATCH_LENGTH + SWATCH_GAP, middle, FONTS.legend, "text");
    }
    painter.y += LEGEND_LINE;
  }
}

/** Ordnet den Inhalt an: 1080 Pixel breit, so hoch wie nötig (höchstens `MAX_IMAGE_HEIGHT`). */
export function layoutShareImage(image: ShareImage, measure: MeasureText): ShareImageLayout {
  const painter = new Painter(measure);
  painter.paragraph(image.title, FONTS.title, TITLE_LINE, "text", 3);
  painter.y += 6;
  painter.paragraph(image.subtitle, FONTS.subtitle, SUBTITLE_LINE, "muted", 2);
  for (const block of image.blocks) {
    if (block.kind === "note") {
      painter.y += 16;
      painter.paragraph(block.text, FONTS.note, NOTE_LINE, block.color, 4);
      continue;
    }
    painter.y += SECTION_GAP;
    painter.paragraph(block.heading, FONTS.heading, HEADING_LINE, "muted", 1);
    painter.y += 8;
    if (block.kind === "table") layoutTable(painter, block);
    else layoutChart(painter, block);
  }
  const height = Math.min(MAX_IMAGE_HEIGHT, Math.ceil(painter.y + PAD_Y));
  return { width: IMAGE_WIDTH, height, ops: painter.ops };
}
