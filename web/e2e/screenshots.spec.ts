/*
 * Bildschirmfotos zum Ansehen (nicht Teil der normalen Testsuite): `SCREENSHOT_DIR=/pfad npx playwright test
 * screenshots --project handy`. Ohne SCREENSHOT_DIR werden die Tests übersprungen.
 */
import { mkdir } from "node:fs/promises";
import { expect, test, type Page } from "@playwright/test";
import { ROUND_APP_URL } from "./roundConfig";
import {
  anotherDevice,
  claim,
  createRound,
  FakeSupabase,
  KONTO_1,
  KONTO_2,
  nameRound,
  openRound,
  openRunningEvening,
  roundRow,
  routeSupabase,
  signIn,
  SYNC,
} from "./roundSupport";
import { enterGame, nameDefaultPlayers, openApp, startEvening } from "./support";

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

test("Gemeinsame Runde ohne Server", async ({ page }) => {
  await openApp(page);
  await shot(page, "20-start-ohne-runde");
  await page.getByRole("button", { name: /^Gemeinsame Runde/ }).click();
  await expect(page.getByText("Der Server ist noch nicht eingerichtet.")).toBeVisible();
  await shot(page, "21-runde-ohne-server");
});

test.describe("mit Server", () => {
  test.use({ baseURL: ROUND_APP_URL });
  test.setTimeout(90_000);

  test("Bildschirme der gemeinsamen Runde", async ({ page, context, browser }) => {
    const fake = new FakeSupabase();
    await routeSupabase(context, fake);

    // Handy eins: Spieler, ein Abend, dann die Runde.
    await nameRound(page);
    await startEvening(page);
    await enterGame(page, { players: ["Nord", "Süd"], won: true });
    await openRound(page);
    await shot(page, "22-runde-nicht-angemeldet", true);
    await signIn(page, fake, KONTO_1);
    await shot(page, "23-runde-angemeldet", true);
    await createRound(page, "Stammtisch");
    const card = page.getByRole("region", { name: "Wer bist du?" });
    await card.getByRole("button", { name: "Nord", exact: true }).click();
    await shot(page, "24-runde-wer-bist-du", true);
    await card.getByRole("button", { name: "Das bin ich" }).click();
    await shot(page, "25-runde-name-festlegen");
    await page.getByRole("dialog").getByRole("button", { name: "Abbrechen" }).click();
    await claim(page, "Nord", "Nord");
    await page.getByRole("button", { name: "Einladen" }).click();
    const text = await page.getByRole("region", { name: "Einladung weitergeben" }).locator(".invite-text").innerText();
    const link = /https?:\/\/\S+/.exec(text)?.[0] ?? "";
    await shot(page, "26-runde-einladen", true);
    await page.getByRole("region", { name: "Einladung weitergeben" }).getByRole("button", { name: "Fertig" }).click();

    // Handy zwei: Einladungsseite, anmelden, Einladung, Rückfrage (es hat einen eigenen Abend), beitreten.
    const two = await anotherDevice(browser, fake);
    await startEvening(two);
    await two.goto(link);
    await expect(two.getByRole("heading", { level: 2, name: "Einladung in eine Schafkopfrunde" })).toBeVisible();
    await shot(two, "27-einladungsseite");
    await two.getByRole("button", { name: "Hier beitreten" }).click();
    await shot(two, "28-runde-eingeladen-nicht-angemeldet", true);
    await signIn(two, fake, KONTO_2);
    const invitation = two.getByRole("region", { name: "Einladung in die Runde Stammtisch" });
    await expect(invitation.getByText("1 Mitglied")).toBeVisible();
    await shot(two, "29-runde-einladung", true);
    await invitation.getByRole("button", { name: "Beitreten" }).click();
    await expect(two.getByRole("dialog", { name: "Daten dieses Geräts ersetzen?" })).toBeVisible();
    await shot(two, "30-runde-daten-ersetzen");
    await two.getByRole("dialog").getByRole("button", { name: "Ersetzen" }).click();
    await claim(two, "Ost", "Ost");
    await shot(two, "31-runde-verbunden", true);

    // Der Abend auf beiden Handys: Geber-Hinweis, Wolke, doppelt eingetragenes Spiel.
    await openRunningEvening(two);
    await shot(two, "32-abend-du-gibst");
    await enterGame(two, { players: ["Ost", "West"], won: false });
    await openRunningEvening(page);
    await expect(page.getByRole("list", { name: "Einträge" }).getByRole("listitem")).toHaveCount(2, SYNC);
    await enterGame(page, { players: ["Ost", "West"], won: false });
    await expect(page.getByText("Doppelt eingetragen?")).toBeVisible();
    await shot(page, "33-abend-doppelt-eingetragen");

    // Spieler mit festem Namen auf dem Handy, das nicht der Ersteller ist.
    await openApp(two, "/spieler");
    await two.getByRole("button", { name: "Nord bearbeiten" }).click();
    await shot(two, "34-spieler-fester-name");
    await two.getByRole("dialog").getByRole("button", { name: "Abbrechen" }).click();

    // Ohne Netz: Statuszeile auf dem Start-Bildschirm, im Abend und in der Runde; Rückfrage vor dem Verlassen.
    fake.offline = true;
    await openRunningEvening(two);
    await enterGame(two, { type: "Wenz", players: ["West"], won: true });
    await expect(two.getByRole("button", { name: /Gemeinsame Runde: Kein Netz/ })).toBeVisible(SYNC);
    await shot(two, "35-abend-kein-netz");
    await openApp(two);
    await expect(roundRow(two)).toContainText("Kein Netz", SYNC);
    await shot(two, "36-start-kein-netz");
    await roundRow(two).click();
    await two.getByRole("button", { name: "Runde verlassen" }).click();
    await expect(two.getByRole("dialog", { name: "Runde verlassen?" })).toBeVisible();
    await shot(two, "37-runde-verlassen");
    await two.getByRole("dialog").getByRole("button", { name: "Abbrechen" }).click();
    fake.offline = false;

    // Ein Hinweis aus dem Abgleich und eine Fehlermeldung.
    const [round] = await fake.session(KONTO_1.userId).myRounds();
    await fake.session(KONTO_1.userId).savePlayer(round.id, { id: "vom-dritten-handy", name: "Wirt", colorIndex: 5, archived: false });
    fake.offline = true;
    await openApp(page, "/spieler");
    await page.getByLabel("Neuer Spieler").fill("Wirt");
    await page.getByRole("button", { name: "Hinzufügen" }).click();
    fake.offline = false;
    await openRound(page);
    await expect(page.getByRole("heading", { level: 2, name: "Hinweise" })).toBeVisible(SYNC);
    await shot(page, "38-runde-hinweise", true);
    await openApp(page, "/einstellungen");
    await shot(page, "39-einstellungen-mit-runde", true);
    await openApp(page);
    await expect(roundRow(page)).toContainText("Abgeglichen", SYNC);
    await shot(page, "40-start-mit-runde");

    // Anmeldung abgelaufen.
    fake.revokeSessions();
    await roundRow(page).click();
    await expect(page.getByRole("status").filter({ hasText: "Anmeldung abgelaufen" })).toBeVisible(SYNC);
    await shot(page, "41-runde-anmeldung-abgelaufen", true);

    // Angemeldet ohne Runde, mit einer Runde des Kontos und einer Fehlermeldung.
    const three = await anotherDevice(browser, fake);
    await openRound(three);
    await signIn(three, fake, KONTO_2);
    await expect(three.getByRole("list", { name: "Deine Runden" })).toBeVisible();
    await three.getByRole("form", { name: "Einladung einlösen" }).getByLabel("Link oder Code").fill("kein Link");
    await three.getByRole("form", { name: "Einladung einlösen" }).getByRole("button", { name: "Beitreten" }).click();
    await expect(three.getByRole("alert")).toBeVisible();
    await shot(three, "42-runde-deine-runden-und-fehler", true);
    await two.context().close();
    await three.context().close();
  });
});
