/*
 * Die gemeinsame Runde in der Oberfläche (docs/ARCHITEKTUR.md, Abschnitt 8.9), gegen die gebaute App mit einem
 * nachgestellten Supabase-Projekt: anmelden, Runde anlegen, „Das bin ich“, einladen, beitreten, Statuszeile.
 */
import { expect, test } from "@playwright/test";
import { FAKE_SUPABASE_URL, ROUND_APP_URL } from "./roundConfig";
import type { Evening, Player } from "../src/core/model";
import { InviteLinks } from "../src/core/round/InviteLinks";
import { RuleSets } from "../src/core/rules/RuleSet";
import { writeBackup } from "../src/data/format";
import {
  anotherDevice,
  claim,
  createRound,
  FakeSupabase,
  foundRound,
  inviteLink,
  KONTO_1,
  KONTO_2,
  nameRound,
  openRound,
  openRunningEvening,
  renamePlayer,
  roundRow,
  routeSupabase,
  signIn,
  SYNC,
} from "./roundSupport";
import { enterGame, expectAccessible, openApp, startEvening, waitUntilSaved } from "./support";

test.use({ baseURL: ROUND_APP_URL });

let fake: FakeSupabase;

test.beforeEach(async ({ context }) => {
  fake = new FakeSupabase();
  await routeSupabase(context, fake);
});

test("anmelden, Runde anlegen, „Das bin ich“ und einladen", async ({ page }) => {
  // Das Gerät hat schon einen Abend mit den vorbelegten Spielern; einer davon hat einen Namen.
  await renamePlayer(page, "Spieler 2", "Ost");
  await startEvening(page);
  await openApp(page);
  await expect(roundRow(page)).toContainText("Gemeinsame Runde");
  await expect(roundRow(page)).toContainText("Alle tragen vom eigenen Handy ein");
  await roundRow(page).click();

  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
  await expect(page.getByText("Alle in der Runde tragen vom eigenen Handy ein und sehen denselben Stand.")).toBeVisible();
  await expect(
    page.getByText(
      "Namen, Abende und Beträge der Runde liegen dafür auf einem Server in Frankfurt (Supabase). Zum Anmelden brauchst du ein Google-Konto.",
    ),
  ).toBeVisible();
  await expectAccessible(page);

  await signIn(page, fake, KONTO_1);
  // Der Code aus der Rückkehr steht nicht mehr in der Adresse.
  expect(new URL(page.url()).search).toBe("");
  expect(new URL(page.url()).hash).toBe("#/runde");
  await expect(page.getByRole("form", { name: "Neue Runde anlegen" }).getByLabel("Name der Runde")).toHaveValue("Schafkopfrunde");
  await expect(page.getByText("Alle Abende und Spieler dieses Geräts kommen mit in die Runde.")).toBeVisible();
  await expect(page.getByRole("form", { name: "Einladung einlösen" }).getByLabel("Link oder Code")).toBeVisible();
  await expectAccessible(page);

  await createRound(page, "Stammtisch");
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Mitglieder" })).toContainText("Noch ohne Namen (du)");
  await expectAccessible(page);

  // Zur Wahl stehen die Spieler ohne Besitzer und „Ich bin neu“. Ein echter Name wird vorbelegt, ein vorbelegter
  // („Spieler 1“) ist noch keiner: Dann bleibt das Feld leer.
  const card = page.getByRole("region", { name: "Wer bist du?" });
  await expect(card.getByRole("button")).toHaveText(["Spieler 1", "Ost", "Spieler 3", "Spieler 4", "Ich bin neu", "Das bin ich"]);
  await expect(card.getByRole("button", { name: "Das bin ich" })).toBeDisabled();
  await card.getByRole("button", { name: "Ost", exact: true }).click();
  await expect(card.getByLabel("Dein Name")).toHaveValue("Ost");
  await card.getByRole("button", { name: "Spieler 1", exact: true }).click();
  await expect(card.getByRole("button", { name: "Spieler 1", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(card.getByLabel("Dein Name")).toHaveValue("");
  await expect(card.getByText("Dein Name lässt sich danach nicht mehr ändern.")).toBeVisible();
  await claim(page, "Spieler 1", "Nord");
  await expect(card).toBeHidden();

  const link = await inviteLink(page);
  expect(link).toMatch(/^http:\/\/127\.0\.0\.1:4174\/SchafkopfTracker\/#\/einladung\/[0-9a-f]{32}$/);
  await expect(page.getByText("Der Link gilt 14 Tage.")).toBeVisible();

  // Auf dem Server steht, was die Oberfläche zeigt.
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  expect(round).toMatchObject({ name: "Stammtisch", memberCount: 1, isCreator: true, myPlayerId: "spieler-1" });
  expect(fake.server.players(round.id).map((it) => [it.name, it.nameLocked])).toEqual([
    ["Nord", true],
    ["Ost", false],
    ["Spieler 3", false],
    ["Spieler 4", false],
  ]);
  expect(fake.server.evenings(round.id)).toHaveLength(1);

  // Start-Bildschirm und Einstellungen zeigen die Runde und ihren Stand.
  await page.getByRole("button", { name: "Zurück" }).click();
  await expect(roundRow(page)).toContainText("Runde: Stammtisch");
  await expect(roundRow(page)).toContainText("Abgeglichen");
});

test("zwei Handys: einladen, beitreten, beide tragen ein und sehen dasselbe", async ({ page, browser }) => {
  await foundRound(page, fake);
  const link = await inviteLink(page);
  const token = InviteLinks.tokenFrom(link) ?? "";

  // Das zweite Handy öffnet den Einladungslink.
  const two = await anotherDevice(browser, fake);
  await two.goto(link);
  await expect(two.getByRole("heading", { level: 2, name: "Einladung in eine Schafkopfrunde" })).toBeVisible();
  await expect(two.getByRole("link", { name: "In der Android-App öffnen" })).toHaveAttribute("href", `schafkopftracker://einladung/${token}`);
  await expectAccessible(two);
  await two.getByRole("button", { name: "Hier beitreten" }).click();

  // Der Code übersteht die Anmeldung.
  await expect(two.getByText("Du bist eingeladen. Melde dich an, um beizutreten.")).toBeVisible();
  await signIn(two, fake, KONTO_2);
  const invitation = two.getByRole("region", { name: "Einladung in die Runde Stammtisch" });
  await expect(invitation.getByText("1 Mitglied")).toBeVisible();
  await expect(invitation.getByRole("button", { name: "Einladung verwerfen" })).toBeVisible();
  await expectAccessible(two);
  await invitation.getByRole("button", { name: "Beitreten" }).click();

  // Ohne eigene Daten gibt es keine Rückfrage; das Handy übernimmt Spieler und Abend der Runde.
  await expect(two.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  const members = two.getByRole("list", { name: "Mitglieder" });
  await expect(members.getByRole("listitem")).toHaveText(["Nord", "Noch ohne Namen (du)"]);
  // „Nord“ gehört schon jemandem und steht nicht mehr zur Wahl.
  const card = two.getByRole("region", { name: "Wer bist du?" });
  await expect(card.getByRole("button")).toHaveText(["Ost", "Süd", "West", "Ich bin neu", "Das bin ich"]);
  await claim(two, "Ost", "Ost");
  await expect(members.getByRole("listitem")).toHaveText(["Nord", "Ost (du)"]);
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByRole("listitem")).toHaveText(["Nord (du)", "Ost"], SYNC);

  // Beide öffnen den Abend. Nach dem ersten Spiel gibt Ost: Das zweite Handy trägt ein.
  await openRunningEvening(two);
  await openRunningEvening(page);
  await expect(two.getByRole("list", { name: "Einträge" }).getByRole("listitem")).toHaveCount(1);
  await expect(two.getByText("Du gibst – trag dieses Spiel ein.")).toBeVisible();
  await expect(page.getByText("Ost gibt und trägt ein.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Gemeinsame Runde: Abgeglichen" })).toBeVisible();
  await expectAccessible(page);

  await enterGame(two, { players: ["Ost", "West"], won: false });
  await expect(page.getByRole("list", { name: "Einträge" }).getByRole("listitem")).toHaveCount(2, SYNC);
  await expect(page.getByText("Süd gibt und trägt ein.")).toBeVisible();

  // Eintragen kann trotzdem jeder.
  await enterGame(page, { type: "Wenz", players: ["Nord"], won: true });
  await expect(two.getByRole("list", { name: "Einträge" }).getByRole("listitem")).toHaveCount(3, SYNC);
  await expect(two.getByRole("button", { name: "Gemeinsame Runde: Abgeglichen" })).toBeVisible(SYNC);

  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id)[0].entries.length, SYNC).toBe(3);
  await two.context().close();
});

test("eine Einladung einlösen: Rückfrage, bevor die Daten dieses Geräts ersetzt werden", async ({ page }) => {
  // Die Runde gibt es schon (ein anderes Konto hat sie angelegt).
  const owner = fake.session(KONTO_1.userId);
  const round = await owner.createRound("Stammtisch");
  await owner.savePlayer(round, { id: "wirt", name: "Wirt", colorIndex: 0, archived: false });
  const link = InviteLinks.webLink(ROUND_APP_URL, await owner.createInvite(round));

  // Dieses Gerät hat einen eigenen Abend.
  await startEvening(page);
  await openRound(page);
  await signIn(page, fake, KONTO_2);
  const redeem = page.getByRole("form", { name: "Einladung einlösen" });
  await redeem.getByLabel("Link oder Code").fill(`Komm in unsere Schafkopfrunde Stammtisch: ${link}`);
  await redeem.getByRole("button", { name: "Beitreten" }).click();

  const dialog = page.getByRole("dialog", { name: "Daten dieses Geräts ersetzen?" });
  await expect(
    dialog.getByText("Auf diesem Gerät liegt 1 Abend. Er wird durch die Daten der Runde ersetzt. Sichere ihn vorher, wenn du ihn behalten willst."),
  ).toBeVisible();
  await expectAccessible(page);

  // „Zur Sicherung“ führt in die Einstellungen; nichts ist ersetzt.
  await dialog.getByRole("button", { name: "Zur Sicherung" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Einstellungen" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sicherung speichern" })).toBeVisible();
  await page.getByRole("button", { name: "Zurück" }).click();

  // „Abbrechen“ lässt alles, wie es ist.
  await redeem.getByLabel("Link oder Code").fill(link);
  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await dialog.getByRole("button", { name: "Abbrechen" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(`Angemeldet als ${KONTO_2.email}`)).toBeVisible();

  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await dialog.getByRole("button", { name: "Ersetzen" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Wer bist du?" }).getByRole("button")).toHaveText(["Wirt", "Ich bin neu", "Das bin ich"]);

  // Der eigene Abend ist weg, die Spieler sind die der Runde.
  await openApp(page);
  await expect(page.getByText("Noch kein Abend eingetragen. Mit „Neuer Abend“ geht es los.")).toBeVisible();
  await openApp(page, "/spieler");
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveText(["Wirt"]);
});

test("eine Runde des Kontos auf einem neuen Gerät verwenden", async ({ page }) => {
  const owner = fake.session(KONTO_1.userId);
  const round = await owner.createRound("Stammtisch");
  await owner.claimPlayer(round, "wirt", "Wirt", 2);

  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const rounds = page.getByRole("list", { name: "Deine Runden" });
  await expect(rounds.getByRole("listitem")).toContainText(["Stammtisch"]);
  await expect(rounds.getByText("1 Mitglied")).toBeVisible();
  await expectAccessible(page);
  await rounds.getByRole("button", { name: "Stammtisch auf diesem Gerät verwenden" }).click();

  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByRole("listitem")).toHaveText(["Wirt (du)"]);
  // Der eigene Spieler steht schon fest: keine Frage „Wer bist du?“.
  await expect(page.getByRole("region", { name: "Wer bist du?" })).toBeHidden();
});

test("ohne Netz wartet die Änderung, und die Statuszeile sagt es", async ({ page }) => {
  await foundRound(page, fake);
  fake.offline = true;
  await openRunningEvening(page);
  await enterGame(page, { players: ["Ost", "West"], won: true });

  await expect(page.getByRole("button", { name: "Gemeinsame Runde: Kein Netz – 1 Änderung wartet" })).toBeVisible(SYNC);
  await expectAccessible(page);
  await openApp(page);
  await expect(roundRow(page)).toContainText("Runde: Stammtisch");
  await expect(roundRow(page)).toContainText("Kein Netz – 1 Änderung wartet", SYNC);
  await roundRow(page).click();
  await expect(page.getByRole("status").filter({ hasText: "Kein Netz – 1 Änderung wartet" })).toBeVisible(SYNC);
  await expectAccessible(page);

  // Mit Netz geht das Spiel hoch.
  fake.offline = false;
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  expect(fake.server.evenings(round.id)[0].entries).toHaveLength(2);

  // Ein Server, der nur Fehler liefert (zum Beispiel pausiert).
  fake.failWithStatus = 540;
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Server nicht erreichbar" })).toBeVisible(SYNC);
  fake.failWithStatus = null;
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
});

test("ist die Anmeldung abgelaufen, bleibt nur „Mit Google anmelden“; danach geht es weiter", async ({ page }) => {
  await foundRound(page, fake);
  fake.revokeSessions();

  await expect(page.getByRole("status").filter({ hasText: "Anmeldung abgelaufen – bitte neu anmelden" })).toBeVisible(SYNC);
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Einladen" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Runde verlassen" })).toBeHidden();
  // Mitglieder und „Abmelden“ bleiben.
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByRole("listitem")).toHaveText(["Nord (du)"]);
  await expect(page.getByRole("button", { name: "Abmelden" })).toBeVisible();
  await expectAccessible(page);
  // Eintragen geht weiter; die Änderung wartet.
  await openRunningEvening(page);
  await expect(page.getByRole("button", { name: "Gemeinsame Runde: Anmeldung abgelaufen – bitte neu anmelden" })).toBeVisible();
  await enterGame(page, { players: ["Ost", "West"], won: true });

  await page.getByRole("button", { name: "Gemeinsame Runde: Anmeldung abgelaufen – bitte neu anmelden" }).click();
  fake.nextSignIn = KONTO_1;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeVisible(SYNC);
  // Was während der abgelaufenen Anmeldung eingetragen wurde, geht jetzt hoch.
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id)[0].entries.length, SYNC).toBe(2);
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
});

test("wer sich mit einem anderen Konto anmeldet, wird gleich wieder abgemeldet; die Runde bleibt verbunden, nichts geht verloren", async ({
  page,
}) => {
  await foundRound(page, fake);
  fake.revokeSessions();
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeVisible(SYNC);
  // Inzwischen wird weiter eingetragen; das Spiel wartet.
  await openRunningEvening(page);
  await enterGame(page, { players: ["Ost", "West"], won: true });
  await openRound(page);

  fake.nextSignIn = KONTO_2;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();

  const message = page.getByRole("status").filter({
    hasText: "Diese Runde gehört zu einem anderen Google-Konto. Melde dich mit dem Konto an, mit dem du der Runde beigetreten bist.",
  });
  await expect(message).toBeVisible();
  // Die Runde bleibt verbunden; angemeldet ist niemand, und unter dem fremden Konto lief nichts.
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "Anmeldung abgelaufen – bitte neu anmelden" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeEnabled();
  await expect(page.getByText(`Angemeldet als ${KONTO_2.email}`)).toBeHidden();
  expect(fake.session(KONTO_2.userId).calls).toEqual([]);
  await expectAccessible(page);
  // Die Meldung bleibt stehen, bis man sie wegtippt.
  await message.getByRole("button", { name: "OK" }).click();
  await expect(message).toBeHidden();

  // Mit dem richtigen Konto geht das wartende Spiel hoch.
  fake.nextSignIn = KONTO_1;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeVisible(SYNC);
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id)[0].entries.length, SYNC).toBe(2);
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
  expect(fake.session(KONTO_2.userId).calls).toEqual([]);
});

test("eine abgebrochene Anmeldung wird gemeldet; eine geglückte räumt die Meldung weg", async ({ page }) => {
  await openRound(page);
  fake.cancelNextSignIn = true;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();

  const message = page.getByRole("status").filter({ hasText: "Die Anmeldung wurde abgebrochen." });
  await expect(message).toBeVisible();
  expect(new URL(page.url()).search).toBe("");
  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
  await message.getByRole("button", { name: "OK" }).click();
  await expect(message).toBeHidden();

  fake.cancelNextSignIn = true;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();
  await expect(message).toBeVisible();
  await signIn(page, fake, KONTO_1);
  await expect(message).toBeHidden();
});

test("kommt die Rückkehr aus der Anmeldung ein zweites Mal, ist das kein Fehler, solange jemand angemeldet ist", async ({ page }) => {
  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const code = (fake.calls("POST", "/auth/v1/token")[0].body as { auth_code: string }).auth_code;
  const failed = page.getByRole("status").filter({ hasText: "Die Anmeldung hat nicht geklappt. Bitte noch einmal versuchen." });

  // Dieselbe Adresse noch einmal, zum Beispiel aus dem Verlauf.
  await page.goto(`./?code=${code}`);
  await expect(page.getByText(`Angemeldet als ${KONTO_1.email}`)).toBeVisible();
  await expect(page.getByRole("form", { name: "Neue Runde anlegen" })).toBeVisible();
  await expect(failed).toBeHidden();
  expect(new URL(page.url()).search).toBe("");

  // Ohne Anmeldung ist dieselbe Rückkehr aber ein Fehler.
  await page.getByRole("button", { name: "Abmelden" }).click();
  await page.getByRole("dialog", { name: "Abmelden?" }).getByRole("button", { name: "Abmelden" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
  await page.goto(`./?code=${code}`);
  await expect(failed).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
});

test("während die Anmeldung nach der Rückkehr eingelöst wird, ruhen die Knöpfe", async ({ page, context }) => {
  await foundRound(page, fake);
  fake.revokeSessions();
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeVisible(SYNC);
  // Das Einlösen des Codes dauert (langsames Netz).
  let release = (): void => undefined;
  const slow = new Promise<void>((resolve) => {
    release = resolve;
  });
  await context.route(`${FAKE_SUPABASE_URL}/auth/v1/token**`, async (route) => {
    const request = route.request();
    if (request.method() === "POST" && new URL(request.url()).searchParams.get("grant_type") === "pkce") await slow;
    await route.fallback();
  });

  fake.nextSignIn = KONTO_1;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();

  await expect(page.getByText("Einen Moment …")).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  for (const name of ["Jetzt abgleichen", "Einladen", "Runde verlassen", "Abmelden"]) {
    await expect(page.getByRole("button", { name, exact: true })).toBeDisabled();
  }
  await expectAccessible(page);
  release();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeEnabled(SYNC);
  await expect(page.getByText("Einen Moment …")).toBeHidden();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
});

test("ließen sich „Deine Runden“ nicht laden, steht das da; „Erneut versuchen“ holt sie", async ({ page }) => {
  await fake.session(KONTO_1.userId).createRound("Stammtisch");
  fake.override = (request) => (request.url.pathname === "/rest/v1/rpc/my_rounds" ? { status: 503, body: { message: "Gerade nicht" } } : null);
  await openRound(page);
  await signIn(page, fake, KONTO_1);

  await expect(page.getByRole("heading", { level: 2, name: "Deine Runden" })).toBeVisible();
  await expect(page.getByText("Deine Runden ließen sich nicht laden.")).toBeVisible();
  await expectAccessible(page);

  fake.override = null;
  await page.getByRole("button", { name: "Erneut versuchen" }).click();
  const rounds = page.getByRole("list", { name: "Deine Runden" });
  await expect(rounds.getByRole("listitem")).toContainText(["Stammtisch"]);
  await expect(page.getByText("Deine Runden ließen sich nicht laden.")).toBeHidden();
  await expect(page.getByRole("button", { name: "Erneut versuchen" })).toBeHidden();
});

test("„Abend löschen?“ sagt mit Runde, dass der Abend für alle gelöscht wird", async ({ page, browser }) => {
  // Ohne Runde fehlt der Satz.
  const alone = await anotherDevice(browser, fake);
  await startEvening(alone);
  await alone.getByRole("button", { name: "Mehr" }).click();
  await alone.getByRole("button", { name: "Abend löschen" }).click();
  await expect(alone.getByRole("dialog", { name: "Abend löschen?" })).toContainText("Das lässt sich nicht rückgängig machen.");
  await expect(alone.getByText("Der Abend wird für alle in der Runde gelöscht.")).toBeHidden();
  await alone.context().close();

  await foundRound(page, fake);
  await openRunningEvening(page);
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend löschen" }).click();
  const dialog = page.getByRole("dialog", { name: "Abend löschen?" });
  await expect(dialog.getByText("Alle Einträge dieses Abends werden gelöscht. Das lässt sich nicht rückgängig machen.")).toBeVisible();
  await expect(dialog.getByText("Der Abend wird für alle in der Runde gelöscht.")).toBeVisible();
  await expectAccessible(page);

  await dialog.getByRole("button", { name: "Abend löschen" }).click();
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id).length, SYNC).toBe(0);
});

test("Sicherung einlesen mit Runde: Hinweis, dass es bei allen erscheint; die Namen bleiben die der Runde", async ({ page }) => {
  await foundRound(page, fake);
  // Eine Sicherung von früher: dieselben Spieler unter anderen Namen und ein Abend, den die Runde noch nicht kennt.
  const players: Player[] = ["Anna", "Bert", "Cilli", "Done"].map((name, index) => ({
    id: `spieler-${index + 1}`,
    name,
    colorIndex: index,
    archived: false,
  }));
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
  const text = writeBackup(players, [earlier], RuleSets.standard(), "0.1.0", "2026-11-15T10:00:00Z");

  await openApp(page, "/einstellungen");
  await page.locator('input[type="file"]').setInputFiles({ name: "sicherung.json", mimeType: "application/json", buffer: Buffer.from(text) });
  const dialog = page.getByRole("dialog", { name: "Sicherung einlesen?" });
  await expect(dialog.getByText("Dieses Gerät gehört zu einer gemeinsamen Runde. Was du einliest, erscheint bei allen.")).toBeVisible();
  await expect(dialog).toContainText("1 neuer Abend kommt dazu.");
  await expect(dialog.getByText(/Spieler wie in der Sicherung benennen/)).toBeHidden();
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "Einlesen" }).click();
  await expect(page.getByText("Sicherung eingelesen.")).toBeVisible();

  // Die Namen bleiben die der Runde, und der Abend geht in die Runde hoch.
  await openApp(page, "/spieler");
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveText(["Nord", "Ost", "Süd", "West"]);
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id).map((it) => it.id), SYNC).toContain("abend-von-frueher");
  expect(fake.server.players(round.id).map((it) => it.name)).toEqual(["Nord", "Ost", "Süd", "West"]);
});

test("Fehlertexte: kein Code, abgelaufene Einladung, zu langer Name der Runde", async ({ page }) => {
  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const redeem = page.getByRole("form", { name: "Einladung einlösen" });
  await expect(redeem.getByRole("button", { name: "Beitreten" })).toBeDisabled();

  await redeem.getByLabel("Link oder Code").fill("Komm doch am Freitag!");
  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await expect(page.getByRole("alert")).toHaveText(/Das ist kein gültiger Einladungslink\./);
  await expectAccessible(page);
  await page.getByRole("alert").getByRole("button", { name: "OK" }).click();
  await expect(page.getByRole("alert")).toBeHidden();

  await redeem.getByLabel("Link oder Code").fill("0123456789abcdef0123456789abcdef");
  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await expect(page.getByRole("alert")).toHaveText(/Die Einladung ist abgelaufen oder ungültig\./);

  const create = page.getByRole("form", { name: "Neue Runde anlegen" });
  await create.getByLabel("Name der Runde").fill("x".repeat(41));
  await create.getByRole("button", { name: "Runde anlegen" }).click();
  await expect(page.getByRole("alert")).toHaveText(/Bitte einen Namen mit 1 bis 40 Zeichen eingeben\./);
  await create.getByLabel("Name der Runde").fill("   ");
  await expect(create.getByRole("button", { name: "Runde anlegen" })).toBeDisabled();
});

test("eine gemerkte Einladung, die abgelaufen ist, wird gemeldet und vergessen", async ({ page }) => {
  await page.goto("./#/einladung/0123456789abcdef0123456789abcdef");
  await page.getByRole("button", { name: "Hier beitreten" }).click();
  await signIn(page, fake, KONTO_1);

  await expect(page.getByRole("alert")).toHaveText(/Die Einladung ist abgelaufen oder ungültig\./);
  await expect(page.getByRole("region", { name: /Einladung in die Runde/ })).toBeHidden();
  // Nach dem Neuladen kommt die Meldung nicht wieder.
  await page.reload();
  await expect(page.getByText(`Angemeldet als ${KONTO_1.email}`)).toBeVisible();
  await expect(page.getByRole("form", { name: "Neue Runde anlegen" })).toBeVisible();
  await expect(page.getByRole("alert")).toBeHidden();
});

test("die Einladungsseite ohne gültigen Code", async ({ page }) => {
  await page.goto("./#/einladung/kein-code");
  await expect(page.getByText("Das ist kein gültiger Einladungslink.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Hier beitreten" })).toBeHidden();
  await expectAccessible(page);
  // Auch ein verstümmelter Link sagt es, statt still auf den Start-Bildschirm zu führen.
  for (const broken of ["./#/einladung/", "./#/einladung/0123456789abcdef/kaputt"]) {
    await page.goto(broken);
    await expect(page.getByText("Das ist kein gültiger Einladungslink.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Hier beitreten" })).toBeHidden();
  }
  // Steht der Code irgendwo im Link, gilt er.
  await page.goto("./#/einladung/0123456789abcdef0123456789abcdef/");
  await expect(page.getByRole("button", { name: "Hier beitreten" })).toBeVisible();
});

test("Spieler: ein fester Name lässt sich nur vom Ersteller ändern, ein Spieler mit Besitzer nicht löschen", async ({ page, browser }) => {
  await foundRound(page, fake);
  const link = await inviteLink(page);
  const two = await anotherDevice(browser, fake);
  await two.goto(link);
  await two.getByRole("button", { name: "Hier beitreten" }).click();
  await signIn(two, fake, KONTO_2);
  await two.getByRole("region", { name: "Einladung in die Runde Stammtisch" }).getByRole("button", { name: "Beitreten" }).click();
  await claim(two, "Ich bin neu", "Gast");

  // Das zweite Handy ist nicht der Ersteller: „Nord“ hat den Namen selbst festgelegt.
  await openApp(two, "/spieler");
  await two.getByRole("button", { name: "Nord bearbeiten" }).click();
  const dialog = two.getByRole("dialog", { name: "Spieler bearbeiten" });
  await expect(dialog.getByLabel("Name")).toBeDisabled();
  await expect(dialog.getByText("Name selbst festgelegt")).toBeVisible();
  await expect(dialog.getByText("Dieser Spieler gehört zu einem Mitglied der Runde und lässt sich nicht löschen.")).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Spieler löschen" })).toBeHidden();
  await expectAccessible(two);
  await dialog.getByRole("button", { name: "Abbrechen" }).click();

  // Der eigene Spieler: Auch den eigenen festen Namen ändert hier niemand mehr; löschen lässt er sich nicht.
  await two.getByRole("button", { name: "Gast bearbeiten" }).click();
  await expect(dialog.getByLabel("Name")).toBeDisabled();
  await expect(dialog.getByText("Dieser Spieler gehört zu einem Mitglied der Runde und lässt sich nicht löschen.")).toBeVisible();
  await dialog.getByRole("button", { name: "Abbrechen" }).click();

  // Der Ersteller der Runde darf einen festen Namen korrigieren.
  await openApp(page, "/spieler");
  await expect(page.getByRole("button", { name: "Gast bearbeiten" })).toBeVisible(SYNC);
  await page.getByRole("button", { name: "Gast bearbeiten" }).click();
  const mine = page.getByRole("dialog", { name: "Spieler bearbeiten" });
  await expect(mine.getByLabel("Name")).toBeEnabled();
  await expect(mine.getByText("Name selbst festgelegt")).toBeHidden();
  await expect(mine.getByText("Dieser Spieler gehört zu einem Mitglied der Runde und lässt sich nicht löschen.")).toBeVisible();
  await mine.getByLabel("Name").fill("Gastwirt");
  await mine.getByRole("button", { name: "Speichern" }).click();
  await expect(two.getByRole("list", { name: "Alle Spieler" })).toContainText("Gastwirt", SYNC);
  await two.context().close();
});

test("dasselbe Spiel zweimal kurz hintereinander: „Doppelt eingetragen?“", async ({ page }) => {
  await foundRound(page, fake);
  await openRunningEvening(page);
  await enterGame(page, { players: ["Ost", "West"], won: true });
  await expect(page.getByText("Doppelt eingetragen?")).toBeHidden();

  await enterGame(page, { players: ["Ost", "West"], won: true });

  const entries = page.getByRole("list", { name: "Einträge" }).getByRole("listitem");
  await expect(entries).toHaveCount(3);
  await expect(entries.nth(2)).toContainText("Doppelt eingetragen?");
  await expect(entries.nth(1)).not.toContainText("Doppelt eingetragen?");
  await expectAccessible(page);
});

test("Hinweise aus dem Abgleich stehen auf dem Bildschirm und lassen sich ausblenden", async ({ page }) => {
  await foundRound(page, fake);
  // Ein neuer Spieler mit einem Namen, den es in der Runde schon gibt (ein anderes Handy war schneller): Der Server
  // lehnt ihn ab.
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await fake.session(KONTO_1.userId).savePlayer(round.id, { id: "vom-anderen-handy", name: "Wirt", colorIndex: 5, archived: false });
  fake.offline = true;
  await openApp(page, "/spieler");
  await page.getByLabel("Neuer Spieler").fill("Wirt");
  await page.getByRole("button", { name: "Hinzufügen" }).click();
  await waitUntilSaved(page);
  fake.offline = false;

  await openRound(page);
  await expect(page.getByRole("heading", { level: 2, name: "Hinweise" })).toBeVisible(SYNC);
  await expect(page.getByRole("list", { name: "Hinweise" }).getByRole("listitem")).toHaveText([
    "Den Namen Wirt gibt es in der Runde schon. Der neue Spieler wurde nicht angelegt.",
  ]);
  await expectAccessible(page);
  await page.getByRole("button", { name: "Hinweise ausblenden" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Hinweise" })).toBeHidden();
});

test("Runde verlassen und abmelden, mit Rückfrage und Hinweis auf Wartendes", async ({ page }) => {
  await foundRound(page, fake);
  fake.offline = true;
  await openRunningEvening(page);
  await enterGame(page, { players: ["Ost", "West"], won: true });
  await openRound(page);

  await page.getByRole("button", { name: "Runde verlassen" }).click();
  const leave = page.getByRole("dialog", { name: "Runde verlassen?" });
  await expect(leave.getByText("Die Daten bleiben als Kopie auf diesem Gerät. Dein Name bleibt in der Runde.")).toBeVisible();
  await expect(leave.getByText("1 Änderung ist noch nicht hochgeladen und kommt nicht mehr in der Runde an.")).toBeVisible();
  await expectAccessible(page);
  await leave.getByRole("button", { name: "Abbrechen" }).click();
  await expect(leave).toBeHidden();

  // Ohne Netz lässt sich die Runde nicht verlassen.
  await page.getByRole("button", { name: "Runde verlassen" }).click();
  await leave.getByRole("button", { name: "Runde verlassen" }).click();
  await expect(page.getByRole("alert")).toHaveText(/Keine Verbindung\. Bitte später noch einmal versuchen\./);
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();

  fake.offline = false;
  await page.getByRole("button", { name: "Jetzt abgleichen" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
  await page.getByRole("button", { name: "Runde verlassen" }).click();
  await expect(leave.getByText(/noch nicht hochgeladen/)).toBeHidden();
  await leave.getByRole("button", { name: "Runde verlassen" }).click();
  await expect(page.getByText(`Angemeldet als ${KONTO_1.email}`)).toBeVisible();
  expect(await fake.session(KONTO_1.userId).myRounds()).toEqual([]);

  await page.getByRole("button", { name: "Abmelden" }).click();
  const signOut = page.getByRole("dialog", { name: "Abmelden?" });
  await expect(signOut.getByText("Dieses Gerät wird von der Runde getrennt. Die Daten bleiben als Kopie hier.")).toBeVisible();
  await signOut.getByRole("button", { name: "Abmelden" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
  // Die Daten sind als Kopie noch da.
  await openApp(page);
  await expect(page.getByText("Abend läuft")).toBeVisible();
});

test("Runde und Anmeldung überstehen das Neuladen; ein zweites Fenster bekommt Änderungen mit", async ({ page, context }) => {
  await foundRound(page, fake);
  await openApp(page);
  await expect(roundRow(page)).toContainText("Runde: Stammtisch");
  await expect(roundRow(page)).toContainText("Abgeglichen", SYNC);

  // Ein zweites Fenster derselben App (derselbe Speicher).
  const second = await context.newPage();
  await second.goto("./");
  await expect(roundRow(second)).toContainText("Runde: Stammtisch");
  await expect(second.getByRole("list", { name: "Alle Abende" }).getByText("1 Spiel", { exact: true })).toBeVisible();

  await openRunningEvening(page);
  await enterGame(page, { players: ["Ost", "West"], won: true });
  await waitUntilSaved(page);
  await expect(second.getByRole("list", { name: "Alle Abende" }).getByText("2 Spiele", { exact: true })).toBeVisible(SYNC);
  await second.close();
});

test("auch im hellen Erscheinungsbild ist die gemeinsame Runde barrierefrei", async ({ page }) => {
  await openApp(page, "/einstellungen");
  await page.getByRole("group", { name: "Erscheinungsbild" }).getByRole("button", { name: "hell" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await nameRound(page);
  await startEvening(page);
  await enterGame(page, { players: ["Nord", "Süd"], won: true });
  await enterGame(page, { players: ["Nord", "Süd"], won: true });

  // Nicht angemeldet, dann angemeldet mit einer Fehlermeldung.
  await openRound(page);
  await expectAccessible(page);
  await signIn(page, fake, KONTO_1);
  const redeem = page.getByRole("form", { name: "Einladung einlösen" });
  await redeem.getByLabel("Link oder Code").fill("kein Link");
  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expectAccessible(page);

  // Verbunden mit der Karte „Wer bist du?“, danach mit der Einladung zum Weitergeben.
  await createRound(page, "Stammtisch");
  await page.getByRole("region", { name: "Wer bist du?" }).getByRole("button", { name: "Nord", exact: true }).click();
  await expectAccessible(page);
  await claim(page, "Nord", "Nord");
  await page.getByRole("button", { name: "Einladen" }).click();
  await expect(page.getByRole("region", { name: "Einladung weitergeben" })).toBeVisible();
  await expectAccessible(page);

  // Der Abend mit Wolke, Geber-Hinweis und „Doppelt eingetragen?“, ohne Netz (Störung in der Fehlerfarbe).
  fake.offline = true;
  await openRunningEvening(page);
  await expect(page.getByText("Doppelt eingetragen?")).toBeVisible();
  await enterGame(page, { type: "Wenz", players: ["Ost"], won: true });
  await expect(page.getByRole("button", { name: /Gemeinsame Runde: Kein Netz/ })).toBeVisible(SYNC);
  await expectAccessible(page);
  await openApp(page);
  await expect(roundRow(page)).toContainText("Kein Netz", SYNC);
  await expectAccessible(page);
});
