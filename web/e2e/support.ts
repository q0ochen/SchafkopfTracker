/*
 * Hilfen für die Oberflächen-Tests: Namen vergeben, Abend starten, Spiele eintragen, Barrierefreiheit prüfen. Alle
 * Tests nehmen `test` und `expect` von hier: So erreicht kein Test den echten Dienst für Ortsvorschläge.
 */
import AxeBuilder from "@axe-core/playwright";
import { test as base, expect, type BrowserContext, type Page, type Route } from "@playwright/test";

/** Alle Anfragen an Photon, den Dienst für Ortsvorschläge (docs/ARCHITEKTUR.md, 4.3). */
export const PHOTON_REQUESTS = "https://photon.komoot.io/**";

/**
 * Bricht jede Anfrage an Photon ab, wie ohne Netz. Gilt für jeden Browser, den `test` öffnet; ein weiterer Browser
 * (`browser.newContext`) braucht es eigens. Einzelne Tests beantworten die Anfragen mit {@link answerPlaces}.
 */
export async function blockPlaceSearch(context: BrowserContext): Promise<void> {
  await context.route(PHOTON_REQUESTS, (route) => route.abort("internetdisconnected"));
}

/** Wie `test` von Playwright, nur dass Anfragen an Photon nie hinausgehen ({@link blockPlaceSearch}). */
export const test = base.extend({
  context: async ({ context }, use) => {
    await blockPlaceSearch(context);
    await use(context);
  },
});

export { expect };

/** Eine Antwort von Photon (GeoJSON) mit genau diesen `properties` je Treffer. */
export function photonAnswer(...properties: ReadonlyArray<Record<string, string>>): string {
  return JSON.stringify({ type: "FeatureCollection", features: properties.map((it) => ({ type: "Feature", properties: it })) });
}

/**
 * Beantwortet die Anfragen dieser Seite an Photon mit `body` (wie der echte Dienst mit CORS-Kopfzeile) und schreibt
 * Adresse und Kopfzeilen jeder Anfrage in die zurückgegebene Liste.
 */
export async function answerPlaces(page: Page, body: string): Promise<Array<{ url: string; headers: Record<string, string> }>> {
  const requested: Array<{ url: string; headers: Record<string, string> }> = [];
  await page.route(PHOTON_REQUESTS, async (route: Route) => {
    requested.push({ url: route.request().url(), headers: route.request().headers() });
    await route.fulfill({
      status: 200,
      headers: { "access-control-allow-origin": "*", "content-type": "application/json;charset=utf-8" },
      body,
    });
  });
  return requested;
}

export const NAMES = ["Anna", "Bert", "Cilli", "Done"] as const;

/** Wartet, bis alle Änderungen im Speicher des Browsers liegen (vor dem Neuladen oder Verlassen der Seite). */
export async function waitUntilSaved(page: Page): Promise<void> {
  await expect(page.locator("html")).not.toHaveAttribute("data-saving", "true");
}

/** Eine Seite der App öffnen (lädt neu) und warten, bis sie da ist. Vorher wird fertig gespeichert. */
export async function openApp(page: Page, path = ""): Promise<void> {
  if (page.url().startsWith("http")) await waitUntilSaved(page);
  await page.goto(path === "" ? "./" : `./#${path}`);
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
}

/** Gibt den vier vorbelegten Spielern Namen. */
export async function nameDefaultPlayers(page: Page): Promise<void> {
  await openApp(page, "/spieler");
  for (let i = 0; i < NAMES.length; i++) {
    await page.getByRole("button", { name: `Spieler ${i + 1} bearbeiten` }).click();
    const dialog = page.getByRole("dialog", { name: "Spieler bearbeiten" });
    await dialog.getByLabel("Name").fill(NAMES[i]);
    await dialog.getByRole("button", { name: "Speichern" }).click();
    await expect(dialog).toBeHidden();
  }
}

/** Startet einen Abend mit der vorgeschlagenen Runde. */
export async function startEvening(page: Page): Promise<void> {
  await openApp(page);
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  await page.getByRole("button", { name: "Abend starten" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
}

/** Trägt ein Spiel ein: Spielart, wer spielt (in Tipp-Reihenfolge), Ergebnis. */
export async function enterGame(
  page: Page,
  options: { type?: string; players: string[]; won: boolean; extra?: (page: Page) => Promise<void> },
): Promise<void> {
  await page.getByRole("button", { name: "Spiel eintragen" }).click();
  await expect(page.getByRole("heading", { level: 1, name: /Spiel \d+ eintragen/ })).toBeVisible();
  if (options.type) await page.getByRole("group", { name: "Spielart" }).getByRole("button", { name: options.type }).click();
  const who = page.getByRole("group", { name: "Wer spielt?" });
  for (const name of options.players) await who.getByRole("button", { name: new RegExp(`^${name}`) }).click();
  if (options.extra) await options.extra(page);
  await page.getByRole("button", { name: options.won ? /^Gewonnen/ : /^Verloren/ }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
}

/** Stand eines Spielers in der Kopfzeile des Abends (Beschriftung für Screenreader). */
export function standing(page: Page, name: string) {
  return page.getByRole("region", { name: "Stand" }).getByRole("listitem").filter({ hasText: name });
}

/** Eine Datei, die die App über das nachgestellte Teilen-Menü weitergegeben hat ({@link stubShareMenu}). */
export interface SharedFile {
  readonly name: string;
  readonly type: string;
  /** Breite und Höhe des Bildes, im Browser entschlüsselt. */
  readonly width: number;
  readonly height: number;
  /** Farbe des Pixels oben links (RGBA). */
  readonly corner: readonly number[];
  /** Das Ereignis, in dem `navigator.share` aufgerufen wurde: „click“ heißt im selben Takt wie das Antippen. */
  readonly event: string | null;
  /** `navigator.userActivation.isActive` beim Aufruf (nur Chromium kennt es). */
  readonly active: boolean | null;
  /** Die Datei als `data:`-Adresse (zum Speichern für Bildschirmfotos). */
  readonly dataUrl: string;
}

/**
 * Stellt das Teilen-Menü des Browsers nach, wie am iPhone: `navigator.canShare` erlaubt Dateien, `navigator.share`
 * schreibt jeden Aufruf mit und meldet Erfolg. Mit {@link cancelShareMenu} verhält es sich wie „Abbrechen“, mit
 * {@link keepShareMenuOpen} wie ein Teilen-Menü, das offen bleibt. Muss vor dem ersten Öffnen der Seite stehen.
 */
export async function stubShareMenu(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const calls: Array<{ files: File[]; event: string | null; active: boolean | null }> = [];
    const state = window as unknown as { __shareCalls: typeof calls; __shareCancel: boolean; __shareOpen: boolean };
    state.__shareCalls = calls;
    state.__shareCancel = false;
    state.__shareOpen = false;
    Object.defineProperty(Navigator.prototype, "canShare", {
      configurable: true,
      writable: true,
      value: (data?: ShareData) => Array.isArray(data?.files) && data.files.length > 0,
    });
    Object.defineProperty(Navigator.prototype, "share", {
      configurable: true,
      writable: true,
      value: (data?: ShareData) => {
        const activation = (navigator as unknown as { userActivation?: { isActive: boolean } }).userActivation;
        calls.push({ files: [...(data?.files ?? [])], event: window.event?.type ?? null, active: activation?.isActive ?? null });
        if (state.__shareOpen) return new Promise<void>(() => undefined);
        return state.__shareCancel ? Promise.reject(new DOMException("Abgebrochen", "AbortError")) : Promise.resolve();
      },
    });
  });
}

/** Das nachgestellte Teilen-Menü bricht ab jetzt jedes Teilen ab, wie „Abbrechen“ am iPhone. */
export async function cancelShareMenu(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { __shareCancel: boolean }).__shareCancel = true;
  });
}

/** Das nachgestellte Teilen-Menü bleibt ab jetzt offen: `navigator.share` antwortet nicht. */
export async function keepShareMenuOpen(page: Page): Promise<void> {
  await page.evaluate(() => {
    (window as unknown as { __shareOpen: boolean }).__shareOpen = true;
  });
}

/** Ohne Teilen-Menü für Dateien (wie Firefox am Desktop): Die App lädt das Bild herunter. */
export async function withoutShareMenu(page: Page): Promise<void> {
  await page.addInitScript(() => {
    for (const name of ["share", "canShare"]) {
      Object.defineProperty(Navigator.prototype, name, { configurable: true, writable: true, value: undefined });
    }
  });
}

/** Alle Dateien, die bisher über das nachgestellte Teilen-Menü gingen, in der Reihenfolge der Aufrufe. */
export async function sharedFiles(page: Page): Promise<SharedFile[]> {
  return page.evaluate(async () => {
    const calls = (window as unknown as { __shareCalls: Array<{ files: File[]; event: string | null; active: boolean | null }> }).__shareCalls;
    const result = [];
    for (const call of calls) {
      for (const file of call.files) {
        const bitmap = await createImageBitmap(file);
        const canvas = document.createElement("canvas");
        canvas.width = 1;
        canvas.height = 1;
        const context = canvas.getContext("2d");
        context?.drawImage(bitmap, 0, 0, 1, 1, 0, 0, 1, 1);
        const corner = Array.from(context?.getImageData(0, 0, 1, 1).data ?? []);
        const dataUrl = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result));
          reader.onerror = () => reject(reader.error);
          reader.readAsDataURL(file);
        });
        result.push({ name: file.name, type: file.type, width: bitmap.width, height: bitmap.height, corner, event: call.event, active: call.active, dataUrl });
      }
    }
    return result;
  });
}

/** Prüft die Farbe des Pixels oben links (RGBA) mit etwas Spielraum für das Farbmanagement des Browsers. */
export function expectCorner(file: SharedFile | undefined, rgba: readonly [number, number, number, number]): void {
  expect(file?.corner.map((value, index) => Math.abs(value - rgba[index]) <= 2)).toEqual([true, true, true, true]);
}

/** Wie oft `navigator.share` bisher aufgerufen wurde. */
export async function shareCount(page: Page): Promise<number> {
  return page.evaluate(() => (window as unknown as { __shareCalls: unknown[] }).__shareCalls.length);
}

/** Prüft die Seite mit axe (WCAG 2.1 AA). */
export async function expectAccessible(page: Page): Promise<void> {
  // @axe-core/playwright bringt eigene (ältere) Typen für die Seite mit; zur Laufzeit ist es dieselbe Seite.
  const axePage = page as unknown as ConstructorParameters<typeof AxeBuilder>[0]["page"];
  const results = await new AxeBuilder({ page: axePage }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(results.violations.map((it) => `${it.id}: ${it.nodes.map((node) => node.target.join(" ")).join(", ")}`)).toEqual([]);
}
