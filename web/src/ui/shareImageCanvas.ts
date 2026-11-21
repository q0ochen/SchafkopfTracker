/*
 * Zeichnet das Bild zum Teilen (docs/ARCHITEKTUR.md, 4.4) in ein Canvas, in den Farben und der Schrift der Seite, und
 * macht daraus eine PNG-Datei. Alles geschieht ohne Warten (`toDataURL` statt `toBlob`): So kann das Teilen-Menü
 * noch im selben Takt wie das Antippen aufgehen, wie es Safari auf dem iPhone verlangt. Die Anordnung kommt aus
 * `shareImage.ts`.
 */
import { layoutShareImage, type ColorRole, type DrawOp, type FontSpec, type ShareImage } from "./shareImage";

/** Farben und Schrift des eingestellten Erscheinungsbilds. */
export interface ImageTheme {
  readonly background: string;
  readonly text: string;
  readonly muted: string;
  readonly grid: string;
  readonly zero: string;
  readonly boundary: string;
  readonly gain: string;
  readonly loss: string;
  readonly error: string;
  /** Die acht Spielerfarben. */
  readonly players: readonly string[];
  readonly fontFamily: string;
}

/** Die dunklen Farben aus `styles.css`, falls eine Farbe einmal nicht zu lesen ist. */
const DARK: Omit<ImageTheme, "fontFamily"> = {
  background: "#000000",
  text: "#f1f5ef",
  muted: "#d0d8ce",
  grid: "#3a423b",
  zero: "#9aa39a",
  boundary: "#9aa39a",
  gain: "#7fdc9a",
  loss: "#ffb4ab",
  error: "#ffb4ab",
  players: ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#0ca30c", "#9085e9", "#e66767"],
};

/** Liest die Farben (CSS-Variablen, je nach dunkel oder hell) und die Schriftfamilie der Seite. */
export function readImageTheme(doc: Document = document): ImageTheme {
  const view = doc.defaultView ?? window;
  const root = view.getComputedStyle(doc.documentElement);
  const color = (name: string, fallback: string) => root.getPropertyValue(name).trim() || fallback;
  return {
    background: color("--bg", DARK.background),
    text: color("--on-bg", DARK.text),
    muted: color("--on-surface-variant", DARK.muted),
    grid: color("--outline-variant", DARK.grid),
    zero: color("--outline", DARK.zero),
    boundary: color("--outline", DARK.boundary),
    gain: color("--gain", DARK.gain),
    loss: color("--loss", DARK.loss),
    error: color("--error", DARK.error),
    players: DARK.players.map((fallback, index) => color(`--player-${index}`, fallback)),
    fontFamily: view.getComputedStyle(doc.body).fontFamily || "sans-serif",
  };
}

function colorOf(role: ColorRole, theme: ImageTheme): string {
  if (role.startsWith("player-")) return theme.players[Number(role.slice("player-".length)) % theme.players.length];
  return theme[role as Exclude<ColorRole, `player-${number}`>];
}

/** Zeichnet das Bild; das Canvas bekommt dabei seine Größe (1080 Pixel breit, so hoch wie nötig). */
export function drawShareImage(canvas: HTMLCanvasElement, image: ShareImage, theme: ImageTheme): void {
  // Ohne Durchsichtigkeit: Das Bild hat immer einen deckenden Hintergrund, auch in Apps mit anderem Hintergrund.
  const context = canvas.getContext("2d", { alpha: false });
  if (!context) throw new Error("Das Canvas lässt sich nicht zeichnen.");
  const ctx = context;
  const useFont = (font: FontSpec) => {
    ctx.font = `${font.weight} ${font.size}px ${theme.fontFamily}`;
    // Versteht der Browser die Schriftfamilie nicht, bliebe die alte Schrift stehen: dann die Standardschrift.
    if (!ctx.font.includes(`${font.size}px`)) ctx.font = `${font.weight} ${font.size}px sans-serif`;
  };
  const layout = layoutShareImage(image, (text, font) => {
    useFont(font);
    return ctx.measureText(text).width;
  });
  canvas.width = layout.width;
  canvas.height = layout.height;
  ctx.fillStyle = theme.background;
  ctx.fillRect(0, 0, layout.width, layout.height);
  for (const op of layout.ops) drawOp(ctx, op, theme, useFont);
}

function drawOp(ctx: CanvasRenderingContext2D, op: DrawOp, theme: ImageTheme, useFont: (font: FontSpec) => void): void {
  switch (op.kind) {
    case "text":
      useFont(op.font);
      ctx.fillStyle = colorOf(op.color, theme);
      ctx.textAlign = op.align;
      ctx.textBaseline = "middle";
      ctx.fillText(op.text, op.x, op.y);
      return;
    case "line":
      ctx.strokeStyle = colorOf(op.color, theme);
      ctx.lineWidth = op.width;
      ctx.lineCap = op.round ? "round" : "butt";
      ctx.setLineDash([...op.dash]);
      ctx.beginPath();
      ctx.moveTo(op.x1, op.y1);
      ctx.lineTo(op.x2, op.y2);
      ctx.stroke();
      ctx.setLineDash([]);
      return;
    case "path": {
      ctx.strokeStyle = colorOf(op.color, theme);
      ctx.lineWidth = op.width;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.beginPath();
      op.points.forEach(([x, y], index) => (index === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)));
      ctx.stroke();
      return;
    }
    case "dot":
      if (op.ring > 0) {
        ctx.fillStyle = theme.background;
        ctx.beginPath();
        ctx.arc(op.x, op.y, op.radius + op.ring, 0, 2 * Math.PI);
        ctx.fill();
      }
      ctx.fillStyle = colorOf(op.color, theme);
      ctx.beginPath();
      ctx.arc(op.x, op.y, op.radius, 0, 2 * Math.PI);
      ctx.fill();
      return;
  }
}

/** Das Canvas als PNG-Datei, ohne Warten (`toDataURL`). Wirft, wenn der Browser kein PNG liefert. */
export function pngFile(canvas: HTMLCanvasElement, name: string): File {
  const url = canvas.toDataURL("image/png");
  const prefix = "data:image/png;base64,";
  // Zu große Canvas liefern in Safari nur „data:,“.
  if (!url.startsWith(prefix)) throw new Error("Kein PNG");
  const binary = atob(url.slice(prefix.length));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return new File([bytes], name, { type: "image/png" });
}

/** Zeichnet das Bild in den Farben der Seite und gibt es als PNG-Datei zurück. Wirft, wenn das nicht geht. */
export function createShareImageFile(image: ShareImage, name: string, doc: Document = document): File {
  const canvas = doc.createElement("canvas");
  drawShareImage(canvas, image, readImageTheme(doc));
  return pngFile(canvas, name);
}
