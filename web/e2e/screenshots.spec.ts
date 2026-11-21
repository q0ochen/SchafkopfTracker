/*
 * Bildschirmfotos zum Ansehen (nicht Teil der normalen Testsuite): `SCREENSHOT_DIR=/pfad npx playwright test
 * screenshots --project handy`. Ohne SCREENSHOT_DIR werden die Tests übersprungen.
 */
import { mkdir } from "node:fs/promises";
import { devices, expect, test, type Page } from "@playwright/test";
import type { Evening, Player } from "../src/core/model";
import { RuleSets } from "../src/core/rules/RuleSet";
import { writeBackup } from "../src/data/format";
import { FAKE_SUPABASE_URL, ROUND_APP_URL } from "./roundConfig";
import {
  anotherDevice,
  claim,
  createRound,
  FakeSupabase,
  foundRound,
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

/** Ans Ende der Seite scrollen, damit über einer festen Leiste unten das Letzte zu sehen ist. */
async function scrollToEnd(page: Page): Promise<void> {
  await page.evaluate(() => window.scrollTo({ top: document.documentElement.scrollHeight }));
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
  await page.getByRole("button", { name: "Abend bearbeiten" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend bearbeiten" })).toBeVisible();
  // Der Abend war ans Ende gescrollt; der neue Bildschirm springt erst nach dem Zeichnen nach oben.
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  await shot(page, "06b-abend-bearbeiten");
  await page.getByLabel("Ort (freiwillig)").fill("Beim Huber");
  await page.getByRole("button", { name: "Bert einen Platz nach vorn" }).click();
  // Unten die Hinweise und „Speichern“ (ein Foto der ganzen Seite zeigte die feste Leiste mitten darüber).
  await scrollToEnd(page);
  await shot(page, "06c-abend-bearbeiten-geaendert");
  await page.getByRole("button", { name: "Speichern" }).click();
  await page.getByRole("button", { name: "Mehr" }).click();
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

    // Die Einladungsseite in Safari auf einem iPhone (der Link kam über WhatsApp) und auf dem verbundenen Handy selbst.
    const { defaultBrowserType: _engine, ...iphone } = devices["iPhone 13"];
    const safari = await anotherDevice(browser, fake, iphone);
    await safari.goto(link);
    await expect(safari.getByText(/^Benutzt du den Schafkopf Tracker vom Home-Bildschirm\?/)).toBeVisible();
    await shot(safari, "27b-einladungsseite-iphone-safari", true);
    await safari.context().close();
    await page.goto(link);
    await expect(page.getByText("Dieses Gerät ist schon mit der Runde Stammtisch verbunden.")).toBeVisible();
    await shot(page, "27c-einladungsseite-verbunden", true);

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
    await page.getByRole("button", { name: "Mehr" }).click();
    await page.getByRole("button", { name: "Abend bearbeiten" }).click();
    await expect(page.getByText("Die Änderungen gelten für alle in der gemeinsamen Runde.")).toBeVisible();
    await scrollToEnd(page);
    await shot(page, "33b-abend-bearbeiten-mit-runde");
    await page.getByRole("button", { name: "Zurück", exact: true }).click();

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
    // Eine Runde des Kontos verlassen, die nicht mit diesem Gerät verbunden ist.
    await three.getByRole("alert").getByRole("button", { name: "OK" }).click();
    await three.getByRole("button", { name: "Stammtisch verlassen" }).click();
    await expect(three.getByRole("dialog", { name: "Stammtisch verlassen?" })).toBeVisible();
    await shot(three, "42b-runde-verlassen-nicht-verbunden");
    await three.getByRole("dialog").getByRole("button", { name: "Abbrechen" }).click();
    await two.context().close();
    await three.context().close();
  });

  test("Meldungen und Rückfragen der gemeinsamen Runde", async ({ page, context, browser }) => {
    const fake = new FakeSupabase();
    await routeSupabase(context, fake);
    await foundRound(page, fake);

    // „Abend löschen?“ mit Runde.
    await openRunningEvening(page);
    await page.getByRole("button", { name: "Mehr" }).click();
    await page.getByRole("button", { name: "Abend löschen" }).click();
    await expect(page.getByRole("dialog", { name: "Abend löschen?" })).toBeVisible();
    await shot(page, "43-abend-loeschen-mit-runde");
    await page.getByRole("dialog").getByRole("button", { name: "Behalten" }).click();

    // Sicherung einlesen mit Runde: dieselben Spieler unter anderen Namen und ein neuer Abend.
    const players: Player[] = ["Anna", "Bert", "Cilli", "Done"].map((name, index) => ({ id: `spieler-${index + 1}`, name, colorIndex: index, archived: false }));
    const earlier: Evening = {
      id: "abend-von-frueher",
      startedAt: "2026-11-14T18:00:00Z",
      finishedAt: "2026-11-14T22:00:00Z",
      location: "Wirtshaus",
      seating: players.map((it) => it.id),
      firstDealer: "spieler-1",
      rules: RuleSets.standard(),
      entries: [{ kind: "thrown_in", id: "wurf-1", recordedAt: "2026-11-14T18:05:00Z" }],
    };
    const backup = writeBackup(players, [earlier], RuleSets.standard(), "0.1.0", "2026-11-15T10:00:00Z");
    await openApp(page, "/einstellungen");
    await page.locator('input[type="file"]').setInputFiles({ name: "sicherung.json", mimeType: "application/json", buffer: Buffer.from(backup) });
    await expect(page.getByRole("dialog", { name: "Sicherung einlesen?" })).toBeVisible();
    await shot(page, "44-sicherung-einlesen-mit-runde");
    await page.getByRole("dialog").getByRole("button", { name: "Abbrechen" }).click();

    // Die Anmeldung ist abgelaufen; die neue wird langsam eingelöst und gehört zu einem anderen Konto.
    fake.revokeSessions();
    await openRound(page);
    await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeVisible(SYNC);
    let release = (): void => undefined;
    const slow = new Promise<void>((resolve) => {
      release = resolve;
    });
    await context.route(`${FAKE_SUPABASE_URL}/auth/v1/token**`, async (route) => {
      if (route.request().method() === "POST" && new URL(route.request().url()).searchParams.get("grant_type") === "pkce") await slow;
      await route.fallback();
    });
    fake.nextSignIn = KONTO_2;
    await page.getByRole("button", { name: "Mit Google anmelden" }).click();
    await expect(page.getByText("Einen Moment …")).toBeVisible();
    await shot(page, "45-runde-anmeldung-wird-eingeloest", true);
    release();
    await expect(page.getByText("Diese Runde gehört zu einem anderen Google-Konto.", { exact: false })).toBeVisible();
    await shot(page, "46-runde-anderes-konto", true);

    // Ein neues Gerät, auf dem sich die Runden des Kontos nicht laden lassen.
    const other = await anotherDevice(browser, fake);
    fake.override = (request) => (request.url.pathname === "/rest/v1/rpc/my_rounds" ? { status: 503, body: { message: "Gerade nicht" } } : null);
    await openRound(other);
    await signIn(other, fake, KONTO_1);
    await expect(other.getByText("Deine Runden ließen sich nicht laden.")).toBeVisible();
    await shot(other, "47-runde-runden-nicht-geladen", true);
    fake.override = null;
    await other.context().close();
  });
});
