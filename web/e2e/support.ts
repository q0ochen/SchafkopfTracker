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

/** Prüft die Seite mit axe (WCAG 2.1 AA). */
export async function expectAccessible(page: Page): Promise<void> {
  // @axe-core/playwright bringt eigene (ältere) Typen für die Seite mit; zur Laufzeit ist es dieselbe Seite.
  const axePage = page as unknown as ConstructorParameters<typeof AxeBuilder>[0]["page"];
  const results = await new AxeBuilder({ page: axePage }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(results.violations.map((it) => `${it.id}: ${it.nodes.map((node) => node.target.join(" ")).join(", ")}`)).toEqual([]);
}
