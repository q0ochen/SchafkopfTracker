/* Hilfen für die Oberflächen-Tests: Namen vergeben, Abend starten, Spiele eintragen, Barrierefreiheit prüfen. */
import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

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
