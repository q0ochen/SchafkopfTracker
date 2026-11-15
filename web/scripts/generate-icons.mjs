// Erzeugt die Grafiken der Web-App aus den selbst gezeichneten Vektorgrafiken der Android-App (eine einzige Quelle):
//
// - app/src/main/res/drawable/ic_launcher_foreground.xml + Hintergrundfarbe (values/colors.xml) → App-Symbol
// - app/src/main/res/drawable/ic_matchbox.xml → Streichholzschachtel für „gelegt“ (einfarbig, färbbar)
//
// Daraus entstehen SVGs und (mit Chromium über Playwright) die PNG-Symbole für Manifest, iPhone-Home-Bildschirm
// und Favicon. Die Ausgaben sind erzeugt und nicht eingecheckt (siehe web/.gitignore); nur das Symbol für die
// F-Droid-Texte (fastlane/…/icon.png) wird mit `--fastlane` neu geschrieben und eingecheckt.
//
//   node scripts/generate-icons.mjs            # nur, wenn sich eine Quelle geändert hat
//   node scripts/generate-icons.mjs --force    # alles neu erzeugen
//   node scripts/generate-icons.mjs --fastlane # zusätzlich fastlane/metadata/android/en-US/images/icon.png
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const webRoot = path.resolve(here, "..");
const repoRoot = path.resolve(webRoot, "..");
const resDir = path.join(repoRoot, "app/src/main/res");
const publicDir = path.join(webRoot, "public");
const iconDir = path.join(publicDir, "icons");
const fastlaneIcon = path.join(repoRoot, "fastlane/metadata/android/en-US/images/icon.png");
const force = process.argv.includes("--force");
const withFastlane = process.argv.includes("--fastlane");

const FOREGROUND = path.join(resDir, "drawable/ic_launcher_foreground.xml");
const MATCHBOX = path.join(resDir, "drawable/ic_matchbox.xml");
const COLORS = path.join(resDir, "values/colors.xml");

/**
 * Ausschnitte aus dem adaptiven App-Symbol (108 × 108, Motiv im Kreis Ø 66 um die Mitte):
 * - SQUARE_CROP für quadratische Symbole ohne Maske (iPhone-Home-Bildschirm, Favicon, „any“): ungefähr der
 *   Bereich, den auch Android-Launcher zeigen.
 * - MASKABLE_CROP für „maskable“-Symbole: Das Motiv muss im inneren Kreis mit 80 % Durchmesser liegen.
 */
export const SQUARE_CROP = [14, 14, 80, 80];
export const MASKABLE_CROP = [12, 12, 84, 84];

// ---------------------------------------------------------------------------------------------------------------
// Android-Vektorgrafik (VectorDrawable) → SVG
// ---------------------------------------------------------------------------------------------------------------

/** Liest die Attribute `android:name="wert"` eines Elements. */
function attributes(text) {
  const result = {};
  for (const match of text.matchAll(/android:(\w+)\s*=\s*"([^"]*)"/g)) result[match[1]] = match[2];
  return result;
}

/** Android-Farbe (#RGB, #ARGB, #RRGGBB, #AARRGGBB) als SVG-Farbe und Deckkraft. */
export function androidColor(value) {
  if (!value) return { color: "none", opacity: 1 };
  const hex = value.replace("#", "");
  let a = 255;
  let rgb;
  if (hex.length === 3) rgb = hex.split("").map((c) => c + c).join("");
  else if (hex.length === 4) {
    a = parseInt(hex[0] + hex[0], 16);
    rgb = hex.slice(1).split("").map((c) => c + c).join("");
  } else if (hex.length === 6) rgb = hex;
  else if (hex.length === 8) {
    a = parseInt(hex.slice(0, 2), 16);
    rgb = hex.slice(2);
  } else throw new Error(`Unbekannte Farbe: ${value}`);
  if (a === 0) return { color: "none", opacity: 1 };
  return { color: `#${rgb.toUpperCase()}`, opacity: Math.round((a / 255) * 1000) / 1000 };
}

/** Transformation einer Gruppe wie bei Android: erst skalieren, dann drehen (um den Drehpunkt), dann verschieben. */
function groupTransform(a) {
  const px = Number(a.pivotX ?? 0);
  const py = Number(a.pivotY ?? 0);
  const tx = Number(a.translateX ?? 0);
  const ty = Number(a.translateY ?? 0);
  const rotation = Number(a.rotation ?? 0);
  const sx = Number(a.scaleX ?? 1);
  const sy = Number(a.scaleY ?? 1);
  const parts = [`translate(${px + tx} ${py + ty})`];
  if (rotation !== 0) parts.push(`rotate(${rotation})`);
  if (sx !== 1 || sy !== 1) parts.push(`scale(${sx} ${sy})`);
  parts.push(`translate(${-px} ${-py})`);
  return parts.join(" ");
}

/**
 * Übersetzt eine VectorDrawable in SVG-Inhalt (ohne äußeres <svg>). Unterstützt <group> (Drehen, Skalieren,
 * Verschieben um einen Drehpunkt) und <path> mit Füllung, Füllregel, Kontur, Linienenden und Deckkraft.
 * @param {string} xml
 * @param {{ monochrome?: boolean }} [options] monochrome: jede sichtbare Farbe wird zu `currentColor`.
 * @returns {{ viewBox: number[]; body: string }}
 */
export function vectorToSvg(xml, options = {}) {
  const source = xml.replace(/<!--[\s\S]*?-->/g, "").replace(/<\?xml[^>]*\?>/, "");
  let viewBox = null;
  let body = "";
  let depth = 0;
  for (const match of source.matchAll(/<(\/?)(\w+)((?:[^>"]|"[^"]*")*?)(\/?)>/g)) {
    const [, closing, name, rawAttributes, selfClosing] = match;
    const a = attributes(rawAttributes);
    const indent = "  ".repeat(depth + 1);
    if (name === "vector") {
      if (closing) continue;
      viewBox = [0, 0, Number(a.viewportWidth), Number(a.viewportHeight)];
    } else if (name === "group") {
      if (closing) {
        depth--;
        body += `${"  ".repeat(depth + 1)}</g>\n`;
      } else {
        body += `${indent}<g transform="${groupTransform(a)}">\n`;
        if (!selfClosing) depth++;
        else body += `${indent}</g>\n`;
      }
    } else if (name === "path") {
      if (closing) continue;
      const fill = androidColor(a.fillColor);
      const stroke = androidColor(a.strokeColor);
      const paint = (c) => (options.monochrome && c.color !== "none" ? "currentColor" : c.color);
      const parts = [`d="${a.pathData}"`, `fill="${paint(fill)}"`];
      if (fill.color !== "none" && fill.opacity !== 1) parts.push(`fill-opacity="${fill.opacity}"`);
      if (a.fillAlpha) parts.push(`fill-opacity="${a.fillAlpha}"`);
      if (a.fillType === "evenOdd") parts.push(`fill-rule="evenodd"`);
      if (stroke.color !== "none") {
        parts.push(`stroke="${paint(stroke)}"`, `stroke-width="${a.strokeWidth ?? 0}"`);
        if (stroke.opacity !== 1) parts.push(`stroke-opacity="${stroke.opacity}"`);
        if (a.strokeAlpha) parts.push(`stroke-opacity="${a.strokeAlpha}"`);
        if (a.strokeLineCap) parts.push(`stroke-linecap="${a.strokeLineCap}"`);
        if (a.strokeLineJoin) parts.push(`stroke-linejoin="${a.strokeLineJoin}"`);
      }
      body += `${indent}<path ${parts.join(" ")}/>\n`;
    } else if (!closing) {
      throw new Error(`Element <${name}> wird nicht unterstützt.`);
    }
  }
  if (!viewBox) throw new Error("Keine <vector>-Angabe gefunden.");
  if (depth !== 0) throw new Error("Gruppen sind nicht sauber geschlossen.");
  return { viewBox, body };
}

/** Hintergrundfarbe des adaptiven Symbols aus values/colors.xml. */
export function launcherBackground(colorsXml) {
  const match = /<color name="ic_launcher_background">([^<]+)<\/color>/.exec(colorsXml);
  if (!match) throw new Error("Farbe ic_launcher_background fehlt in values/colors.xml.");
  return androidColor(match[1].trim()).color;
}

/**
 * Das App-Symbol als SVG: Hintergrund und Vordergrund des adaptiven Symbols, auf einen Ausschnitt zugeschnitten.
 * @param {"rounded" | "full"} shape „rounded“: abgerundete Ecken mit Transparenz, „full“: randlos.
 */
export function appIconSvg(foregroundXml, background, crop, shape) {
  const { body } = vectorToSvg(foregroundXml);
  const [x, y, w, h] = crop;
  const radius = shape === "rounded" ? Math.round(w * 0.2 * 100) / 100 : 0;
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${x} ${y} ${w} ${h}" width="${w}" height="${h}">`,
    `  <!-- Erzeugt von web/scripts/generate-icons.mjs aus app/src/main/res (selbst gezeichnet, CC0). -->`,
    `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${radius}" fill="${background}"/>`,
    body.trimEnd(),
    `</svg>`,
    "",
  ].join("\n");
}

/** Ein einfarbiges Symbol (z. B. die Streichholzschachtel) mit `currentColor`. */
export function monochromeSvg(xml) {
  const { viewBox, body } = vectorToSvg(xml, { monochrome: true });
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox.join(" ")}" width="${viewBox[2]}" height="${viewBox[3]}" fill="currentColor">`,
    `  <!-- Erzeugt von web/scripts/generate-icons.mjs aus app/src/main/res (selbst gezeichnet, CC0). -->`,
    body.trimEnd(),
    `</svg>`,
    "",
  ].join("\n");
}

// ---------------------------------------------------------------------------------------------------------------
// Dateien schreiben
// ---------------------------------------------------------------------------------------------------------------

async function mtime(file) {
  try {
    return (await stat(file)).mtimeMs;
  } catch {
    return 0;
  }
}

/** Zeichnet ein SVG mit Chromium als PNG der angegebenen Größe (transparenter Hintergrund). */
async function renderPngs(jobs) {
  const { chromium } = await import("@playwright/test");
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ deviceScaleFactor: 1 });
    for (const job of jobs) {
      await page.setViewportSize({ width: job.size, height: job.size });
      const dataUrl = `data:image/svg+xml;base64,${Buffer.from(job.svg).toString("base64")}`;
      await page.setContent(
        `<html><body style="margin:0;background:transparent"><img src="${dataUrl}" width="${job.size}" height="${job.size}" style="display:block"></body></html>`,
      );
      await page.waitForFunction(() => document.images[0]?.complete);
      await mkdir(path.dirname(job.file), { recursive: true });
      await page.screenshot({ path: job.file, omitBackground: true, clip: { x: 0, y: 0, width: job.size, height: job.size } });
    }
  } finally {
    await browser.close();
  }
}

async function main() {
  const sources = [FOREGROUND, MATCHBOX, COLORS, fileURLToPath(import.meta.url)];
  const newestSource = Math.max(...(await Promise.all(sources.map(mtime))));
  const foreground = await readFile(FOREGROUND, "utf8");
  const background = launcherBackground(await readFile(COLORS, "utf8"));
  const matchbox = await readFile(MATCHBOX, "utf8");

  const vectors = [
    { file: path.join(iconDir, "app_icon.svg"), svg: appIconSvg(foreground, background, SQUARE_CROP, "rounded") },
    { file: path.join(iconDir, "matchbox.svg"), svg: monochromeSvg(matchbox) },
  ];
  const rounded = appIconSvg(foreground, background, SQUARE_CROP, "rounded");
  const full = appIconSvg(foreground, background, SQUARE_CROP, "full");
  const maskable = appIconSvg(foreground, background, MASKABLE_CROP, "full");
  const rasters = [
    { file: path.join(iconDir, "app-192.png"), svg: rounded, size: 192 },
    { file: path.join(iconDir, "app-512.png"), svg: rounded, size: 512 },
    { file: path.join(iconDir, "app-maskable-192.png"), svg: maskable, size: 192 },
    { file: path.join(iconDir, "app-maskable-512.png"), svg: maskable, size: 512 },
    { file: path.join(publicDir, "apple-touch-icon.png"), svg: full, size: 180 },
    { file: path.join(publicDir, "favicon.png"), svg: rounded, size: 32 },
  ];
  if (withFastlane) rasters.push({ file: fastlaneIcon, svg: rounded, size: 512, always: true });

  await mkdir(iconDir, { recursive: true });
  let written = 0;
  for (const vector of vectors) {
    if (force || (await mtime(vector.file)) < newestSource) {
      await writeFile(vector.file, vector.svg);
      written++;
    }
  }
  const due = [];
  for (const raster of rasters) {
    if (force || raster.always || (await mtime(raster.file)) < newestSource) due.push(raster);
  }
  if (due.length > 0) await renderPngs(due);
  console.warn(`Grafiken: ${written} SVG und ${due.length} PNG erzeugt (Quelle: app/src/main/res).`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
