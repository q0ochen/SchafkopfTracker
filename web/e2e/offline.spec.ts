/* Die App startet auch ohne Netz (Service Worker), so wie im Wirtshaus mit schlechtem Empfang. */
import { expect, test } from "@playwright/test";

test.use({ serviceWorkers: "allow" });

// Playwright kann Service Worker und „ohne Netz“ nur in Chromium nachstellen; in WebKit bricht schon das Neuladen
// ohne Netz mit einem internen Fehler ab, bevor der Service Worker antworten kann. Geprüft wird es deshalb in Chromium.
test.skip(({ browserName }) => browserName !== "chromium", "Service Worker ohne Netz lässt sich nur in Chromium nachstellen");

test("startet nach dem ersten Besuch auch ohne Netz", async ({ page, context }) => {
  await page.goto("./");
  await expect(page.getByRole("heading", { level: 1, name: "Schafkopf Tracker" })).toBeVisible();
  // Warten, bis der Service Worker alles zwischengespeichert hat und die Seite steuert.
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true);

  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Schafkopf Tracker" })).toBeVisible();
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  await page.getByRole("button", { name: "Abend starten" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await context.setOffline(false);
});
