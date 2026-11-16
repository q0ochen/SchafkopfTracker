/*
 * Bildschirmfotos zum Ansehen (nicht Teil der normalen Testsuite): `SCREENSHOT_DIR=/pfad npx playwright test
 * screenshots --project handy`. Ohne SCREENSHOT_DIR werden die Tests übersprungen.
 */
import { mkdir } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import { enterGame, nameDefaultPlayers, openApp } from "./support";

const directory = process.env.SCREENSHOT_DIR ?? "";
test.skip(directory === "", "nur mit SCREENSHOT_DIR");

async function shot(page: Page, name: string, fullPage = false): Promise<void> {
  await mkdir(directory, { recursive: true });
  await page.screenshot({ path: `${directory}/${test.info().project.name}-${name}.png`, fullPage });
}

test("Bildschirme eines Abends", async ({ page }) => {
  await nameDefaultPlayers(page);
  await openApp(page);
  await shot(page, "01-start-leer");
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  await shot(page, "02-neuer-abend", true);
  await page.getByRole("button", { name: "Abend starten" }).click();
  await enterGame(page, { players: ["Anna", "Cilli"], won: true });
  await enterGame(page, { type: "Wenz", players: ["Bert"], won: false });
  await page.getByRole("button", { name: "6 Nixer – Bock" }).click();
  await shot(page, "03-bock-dialog");
  await page.getByRole("dialog").getByRole("button", { name: "Bockrunde starten" }).click();
  await enterGame(page, {
    type: "Farbsolo",
    players: ["Done"],
    won: true,
    extra: async (form) => {
      await form.getByRole("group", { name: "Gelegt" }).getByRole("button", { name: /Anna/ }).click();
      await form.getByRole("group", { name: "Laufende" }).getByRole("button", { name: "3 Laufende" }).click();
      await shot(form, "04-spiel-eintragen", true);
    },
  });
  await enterGame(page, { type: "Über Kreuz", players: ["Bert"], won: false });
  await shot(page, "05-abend");
  await page.getByRole("button", { name: "Mehr" }).click();
  await shot(page, "06-menue");
  await page.getByRole("button", { name: "Abend beenden" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abschluss" })).toBeVisible();
  await shot(page, "07-abschluss", true);
  await page.getByRole("button", { name: "Statistik dieses Abends" }).click();
  await page.getByRole("group", { name: /Vermögensverlauf/ }).click({ position: { x: 200, y: 120 } });
  await shot(page, "08-statistik", true);
  await openApp(page);
  await shot(page, "09-start-mit-abend");
  await openApp(page, "/einstellungen");
  await shot(page, "10-einstellungen", true);
  await openApp(page, "/regeln");
  await shot(page, "11-regeln", true);
  await openApp(page, "/spieler");
  await page.getByLabel("Neuer Spieler").fill("Gast");
  await page.getByRole("button", { name: "Hinzufügen" }).click();
  await page.getByRole("button", { name: "Gast bearbeiten" }).click();
  await shot(page, "12-spieler-bearbeiten");
  await page.getByRole("dialog").getByRole("button", { name: "Spieler löschen" }).click();
  await shot(page, "13-spieler-loeschen");
  await page.getByRole("button", { name: "Behalten" }).click();
  await page.getByRole("button", { name: "Abbrechen" }).click();
  await page.getByRole("button", { name: "Anna bearbeiten" }).click();
  await shot(page, "14-spieler-mit-abend");
});
