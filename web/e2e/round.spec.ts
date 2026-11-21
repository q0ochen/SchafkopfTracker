/*
 * Die gemeinsame Runde in der Oberfläche (docs/ARCHITEKTUR.md, Abschnitt 8.9), gegen die gebaute App mit einem
 * nachgestellten Supabase-Projekt: anmelden, Runde anlegen, „Das bin ich“, einladen, beitreten, Statuszeile, zwischen
 * den Runden des Kontos wechseln.
 */
import { FAKE_SUPABASE_URL, ROUND_APP_URL } from "./roundConfig";
import type { Evening, Player } from "../src/core/model";
import { InviteLinks } from "../src/core/round/InviteLinks";
import { RuleSets } from "../src/core/rules/RuleSet";
import { writeBackup } from "../src/data/format";
import {
  ANDROID_CHROME,
  anotherDevice,
  claim,
  copiedTexts,
  createRound,
  FakeSupabase,
  foundRound,
  fromHomeScreen,
  holdHead,
  inviteLink,
  IPHONE_SAFARI,
  KONTO_1,
  KONTO_2,
  KONTO_3,
  nameRound,
  openRound,
  openRunningEvening,
  otherRound,
  recordClipboard,
  renamePlayer,
  roundPicker,
  roundRow,
  routeSupabase,
  signIn,
  switchProgress,
  SYNC,
} from "./roundSupport";
import { answerPlaces, enterGame, expect, expectAccessible, openApp, photonAnswer, startEvening, test, waitUntilSaved } from "./support";

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
  await expect(roundRow(page)).toContainText("Gemeinsame Runden");
  await expect(roundRow(page)).toContainText("Alle tragen vom eigenen Handy ein");
  // Ohne Runde gibt es nichts zu wählen.
  await expect(roundPicker(page)).toBeHidden();
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

  // Start-Bildschirm und Einstellungen zeigen die Runde und ihren Stand. Mit nur einer Runde gibt es keine Auswahl.
  await page.getByRole("button", { name: "Zurück" }).click();
  await expect(roundRow(page)).toContainText("Runde: Stammtisch");
  await expect(roundRow(page)).toContainText("Abgeglichen");
  await expect(roundPicker(page)).toBeHidden();
  // Jeder Abend sagt, zu welcher Runde er gehört (docs/ARCHITEKTUR.md 8.9).
  const evenings = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(evenings).toHaveCount(1);
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Stammtisch");
  await expectAccessible(page);
});

test("zwei Handys: einladen, beitreten, beide tragen ein und sehen dasselbe", async ({ page, browser }) => {
  await foundRound(page, fake);
  const link = await inviteLink(page);
  const token = InviteLinks.tokenFrom(link) ?? "";

  // Das zweite Handy öffnet den Einladungslink (in einem Browser am Computer: kein Knopf für die Android-App).
  const two = await anotherDevice(browser, fake);
  await two.goto(link);
  await expect(two.getByRole("heading", { level: 2, name: "Einladung in eine Schafkopfrunde" })).toBeVisible();
  await expect(two.getByText(token, { exact: true })).toBeVisible();
  await expect(two.getByRole("link", { name: "In der Android-App öffnen" })).toBeHidden();
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
  // Das Konto gehört noch einer zweiten Runde an: Mit gültiger Anmeldung steht sie unter „Deine anderen Runden“.
  await otherRound(fake, "Klub");
  await foundRound(page, fake);
  await expect(page.getByRole("list", { name: "Deine anderen Runden" }).getByRole("listitem")).toContainText(["Klub"]);
  fake.revokeSessions();

  await expect(page.getByRole("status").filter({ hasText: "Anmeldung abgelaufen – bitte neu anmelden" })).toBeVisible(SYNC);
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Jetzt abgleichen" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Einladen" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Runde verlassen" })).toBeHidden();
  // Auch alles, was zu einer anderen Runde führt, braucht die Anmeldung.
  await expect(page.getByRole("form", { name: "Neue Runde anlegen" })).toBeHidden();
  await expect(page.getByRole("form", { name: "Einladung einlösen" })).toBeHidden();
  await expect(page.getByRole("heading", { level: 2, name: "Deine anderen Runden" })).toBeHidden();
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
  const round = (await fake.session(KONTO_1.userId).myRounds()).find((it) => it.name === "Stammtisch");
  await expect.poll(() => fake.server.evenings(round?.id ?? "")[0].entries.length, SYNC).toBe(2);
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
  await expect(page.getByRole("list", { name: "Deine anderen Runden" }).getByRole("listitem")).toContainText(["Klub"]);
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

test("„Abend bearbeiten“ sagt mit Runde, dass die Änderungen für alle gelten; sie kommen beim Server an", async ({ page }) => {
  await foundRound(page, fake);
  await openRunningEvening(page);
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend bearbeiten" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend bearbeiten" })).toBeVisible();
  await expect(page.getByText("Die Änderungen gelten für alle in der gemeinsamen Runde.")).toBeVisible();
  await expectAccessible(page);

  await page.getByLabel("Ort (freiwillig)").fill("Beim Huber");
  await page.getByRole("button", { name: "Süd einen Platz nach hinten" }).click();
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  const [round] = await fake.session(KONTO_1.userId).myRounds();
  await expect.poll(() => fake.server.evenings(round.id)[0]?.location, SYNC).toBe("Beim Huber");
  expect(fake.server.evenings(round.id)[0]?.seating).toEqual(["spieler-1", "spieler-2", "spieler-4", "spieler-3"]);
});

test("Ortsvorschläge gehen auch in der App mit Server: Die Content-Security-Policy erlaubt Photon und den Server", async ({ page }) => {
  const requests = await answerPlaces(
    page,
    photonAnswer({ name: "Schlenkerla", street: "Dominikanerstraße", housenumber: "6", postcode: "96049", city: "Bamberg", countrycode: "DE" }),
  );
  await openApp(page);
  await expect(page.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute(
    "content",
    /connect-src 'self' https:\/\/photon\.komoot\.io https:\/\/runde\.invalid;/,
  );
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  await page.getByLabel("Ort (freiwillig)").fill("Schlenkerla");
  const list = page.getByRole("list", { name: "Ortsvorschläge" });
  await expect(list.getByRole("button", { name: "Schlenkerla, Dominikanerstraße 6, 96049 Bamberg" })).toBeVisible();
  expect(requests.map((it) => it.url)).toEqual(["https://photon.komoot.io/api/?q=Schlenkerla&lang=de&limit=8"]);
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
  // Das Konto ist das einzige Mitglied: Die Runde wird gelöscht.
  await expect(
    leave.getByText(
      "Du bist das letzte Mitglied: Die Runde wird mit allen Abenden auf dem Server gelöscht. Auf diesem Gerät bleiben sie als Kopie.",
    ),
  ).toBeVisible();
  await expect(leave.getByText("Dein Name bleibt in der Runde.", { exact: false })).toBeHidden();
  await expect(leave.getByText("1 Änderung ist noch nicht hochgeladen und kommt nicht mehr in der Runde an.")).toBeVisible();
  await expectAccessible(page);
  await leave.getByRole("button", { name: "Abbrechen" }).click();
  await expect(leave).toBeHidden();

  // Mit verbundener Runde trennt „Abmelden“ das Gerät von der Runde.
  await page.getByRole("button", { name: "Abmelden" }).click();
  const linkedSignOut = page.getByRole("dialog", { name: "Abmelden?" });
  await expect(linkedSignOut.getByText("Dieses Gerät wird von der Runde getrennt. Die Daten bleiben als Kopie hier.")).toBeVisible();
  await expect(linkedSignOut.getByText("1 Änderung ist noch nicht hochgeladen und kommt nicht mehr in der Runde an.")).toBeVisible();
  await linkedSignOut.getByRole("button", { name: "Abbrechen" }).click();
  await expect(linkedSignOut).toBeHidden();

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

  // Ohne verbundene Runde wird nur abgemeldet.
  await page.getByRole("button", { name: "Abmelden" }).click();
  const signOut = page.getByRole("dialog", { name: "Abmelden?" });
  await expect(signOut.getByText("Dieses Gerät wird abgemeldet.", { exact: true })).toBeVisible();
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

  // Mit einer zweiten Runde: die Runden-Auswahl (gelesen wird mit Netz) und ohne Netz ein Fehler darunter; dann
  // „Deine anderen Runden“.
  await otherRound(fake, "Klub");
  fake.offline = false;
  await openApp(page);
  await expect(roundPicker(page).locator("option")).toHaveText(["Stammtisch", "Klub"]);
  fake.offline = true;
  await roundPicker(page).selectOption({ label: "Klub" });
  await expect(page.getByRole("alert")).toHaveText(/Keine Verbindung/);
  await expectAccessible(page);
  fake.offline = false;
  await roundRow(page).click();
  await expect(page.getByRole("list", { name: "Deine anderen Runden" }).getByRole("listitem")).toContainText(["Klub"]);
  await expectAccessible(page);
});

// --- Einladungsseite (Links aus WhatsApp oder iMessage öffnen sich im Browser, nicht in der App vom Home-Bildschirm) ---

const CODE = "0123456789abcdef0123456789abcdef";
const IPHONE_HINT =
  /^Benutzt du den Schafkopf Tracker vom Home-Bildschirm\? Dann kopiere den Code, öffne die App über ihr Symbol und füge ihn unter „Gemeinsame Runden“ → „Einladung einlösen“ ein\.$/;

test.describe("Einladungsseite am iPhone im Browser", () => {
  test.use({ userAgent: IPHONE_SAFARI });

  test("der Code zum Kopieren, der Hinweis auf die App vom Home-Bildschirm; „Hier beitreten“ geht trotzdem", async ({ page }) => {
    await recordClipboard(page);
    await page.goto(`./#/einladung/${CODE}`);

    await expect(page.getByRole("heading", { level: 2, name: "Einladung in eine Schafkopfrunde" })).toBeVisible();
    await expect(page.getByText(CODE, { exact: true })).toBeVisible();
    await expect(page.getByText(IPHONE_HINT)).toBeVisible();
    await expect(page.getByRole("link", { name: "In der Android-App öffnen" })).toBeHidden();
    await expectAccessible(page);

    await page.getByRole("button", { name: "Code kopieren" }).click();
    await expect(page.getByRole("button", { name: "Code kopiert" })).toBeVisible();
    expect(await copiedTexts(page)).toEqual([CODE]);

    // Wer doch hier im Browser beitreten will, kann das; der Code ist gemerkt.
    await page.getByRole("button", { name: "Hier beitreten" }).click();
    await expect(page.getByText("Du bist eingeladen. Melde dich an, um beizutreten.")).toBeVisible();
  });

  test("als App vom Home-Bildschirm: ohne den Hinweis", async ({ page }) => {
    await fromHomeScreen(page);
    await page.goto(`./#/einladung/${CODE}`);

    await expect(page.getByRole("button", { name: "Hier beitreten" })).toBeVisible();
    await expect(page.getByText(CODE, { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Code kopieren" })).toBeVisible();
    await expect(page.getByText(IPHONE_HINT)).toBeHidden();
    await expect(page.getByRole("link", { name: "In der Android-App öffnen" })).toBeHidden();
  });
});

test.describe("Einladungsseite auf Android", () => {
  test.use({ userAgent: ANDROID_CHROME });

  test("„In der Android-App öffnen“ führt in die App; ohne Hinweis fürs iPhone", async ({ page }) => {
    await page.goto(`./#/einladung/${CODE}`);

    await expect(page.getByRole("link", { name: "In der Android-App öffnen" })).toHaveAttribute("href", `schafkopftracker://einladung/${CODE}`);
    await expect(page.getByRole("button", { name: "Hier beitreten" })).toBeVisible();
    await expect(page.getByText(IPHONE_HINT)).toBeHidden();
    await expectAccessible(page);
  });
});

test("Einladungsseite auf einem Gerät, das schon mit einer Runde verbunden ist: beitreten geht trotzdem, der Code wird gemerkt", async ({
  page,
}) => {
  await foundRound(page, fake);
  await page.goto(`./#/einladung/${CODE}`);

  await expect(page.getByText(CODE, { exact: true })).toBeVisible();
  await expect(
    page.getByText("Dieses Gerät ist schon mit der Runde Stammtisch verbunden. Du kannst trotzdem beitreten und dann zwischen den Runden wechseln."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Zur gemeinsamen Runde" })).toBeHidden();
  await expectAccessible(page);
  await page.getByRole("button", { name: "Hier beitreten" }).click();

  // Der Code ist gemerkt; die Runde sieht ihn nach (abgelaufen: Fehlertext, und er ist wieder vergessen).
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveText(/Die Einladung ist abgelaufen oder ungültig\./);
  expect(await page.evaluate(() => localStorage.getItem("schafkopf-tracker-einladung"))).toBeNull();
});

// --- Runden des Kontos verlassen ---

test("eine Runde aus „Deine Runden“ verlassen: mit Rückfrage, beim letzten Mitglied mit Hinweis; ohne Runde nur abmelden", async ({ page }) => {
  const owner = fake.session(KONTO_1.userId);
  const shared = await owner.createRound("Stammtisch");
  await fake.session(KONTO_2.userId).joinRound(await owner.createInvite(shared));
  const alone = await owner.createRound("Dienstagsrunde");
  await startEvening(page);

  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const rounds = page.getByRole("list", { name: "Deine Runden" });
  await expect(rounds.getByRole("listitem")).toHaveCount(2);

  // Eine Runde mit weiteren Mitgliedern: Sie bleibt bestehen.
  await rounds.getByRole("button", { name: "Stammtisch verlassen" }).click();
  const leaveShared = page.getByRole("dialog", { name: "Stammtisch verlassen?" });
  await expect(leaveShared.getByText("Du gehörst danach nicht mehr zu dieser Runde. Auf diesem Gerät ändert sich nichts.")).toBeVisible();
  await expect(leaveShared.getByText(/letzte Mitglied/)).toBeHidden();
  await expectAccessible(page);
  await leaveShared.getByRole("button", { name: "Abbrechen" }).click();
  await expect(leaveShared).toBeHidden();
  await expect(rounds.getByRole("listitem")).toHaveCount(2);

  // Das letzte Mitglied: Die Runde wird gelöscht.
  await rounds.getByRole("button", { name: "Dienstagsrunde verlassen" }).click();
  const leaveAlone = page.getByRole("dialog", { name: "Dienstagsrunde verlassen?" });
  await expect(leaveAlone.getByText("Du bist das letzte Mitglied: Die Runde wird mit allen Abenden auf dem Server gelöscht.")).toBeVisible();
  await leaveAlone.getByRole("button", { name: "Verlassen" }).click();
  await expect(rounds.getByRole("listitem")).toHaveCount(1);
  await expect(rounds.getByRole("listitem")).toContainText("Stammtisch");
  expect(await fake.session(KONTO_1.userId).head(alone)).toBeNull();

  await rounds.getByRole("button", { name: "Stammtisch verlassen" }).click();
  await leaveShared.getByRole("button", { name: "Verlassen" }).click();
  await expect(page.getByRole("list", { name: "Deine Runden" })).toBeHidden();
  expect((await fake.session(KONTO_2.userId).members(shared)).map((it) => it.userId)).toEqual([KONTO_2.userId]);

  // Auf dem Gerät hat sich nichts geändert; ohne Runde wird nur abgemeldet.
  await page.getByRole("button", { name: "Abmelden" }).click();
  const signOut = page.getByRole("dialog", { name: "Abmelden?" });
  await expect(signOut.getByText("Dieses Gerät wird abgemeldet.", { exact: true })).toBeVisible();
  await signOut.getByRole("button", { name: "Abmelden" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Gemeinsam eintragen" })).toBeVisible();
  await openApp(page);
  await expect(page.getByText("Abend läuft")).toBeVisible();
});

// --- Zwischen Runden wechseln (docs/ARCHITEKTUR.md 8.7 und 8.9, seit dem 21.11.2026) ---

test("die Runde über die Auswahl auf dem Start-Bildschirm wechseln: Rückfrage, andere Abende mit ihrer Marke, zurück", async ({ page }) => {
  await foundRound(page, fake);
  await otherRound(fake, "Klub", { location: "Klubheim" });
  const [stammtisch, klub] = await fake.session(KONTO_1.userId).myRounds();

  await openApp(page);
  const picker = roundPicker(page);
  await expect(picker.locator("option")).toHaveText(["Stammtisch", "Klub"]);
  await expect(picker).toHaveValue(stammtisch.id);
  // Die Auswahl zeigt den Namen; die Zeile darüber nur noch Überschrift und Statuszeile.
  await expect(roundRow(page)).toContainText("Abgeglichen", SYNC);
  await expect(roundRow(page)).not.toContainText("Runde: Stammtisch");
  const evenings = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(evenings).toHaveCount(1);
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Stammtisch");
  await expectAccessible(page);

  await picker.selectOption({ label: "Klub" });
  const dialog = page.getByRole("dialog", { name: "Zur Runde Klub wechseln?" });
  await expect(dialog.getByText("Auf diesem Gerät stehen dann die Abende und Spieler von Klub.")).toBeVisible();
  await expect(dialog.getByText("Die Abende von Stammtisch bleiben dort; du kannst jederzeit zurückwechseln.")).toBeVisible();
  await expect(dialog.getByText(/noch nicht hochgeladen/)).toBeHidden();
  // Bis zum Wechsel zeigt die Auswahl die verbundene Runde.
  await expect(picker).toHaveValue(stammtisch.id);
  await expectAccessible(page);

  // „Abbrechen“ lässt alles, wie es ist.
  await dialog.getByRole("button", { name: "Abbrechen" }).click();
  await expect(dialog).toBeHidden();
  await expect(picker).toHaveValue(stammtisch.id);
  await expect(page.getByText("Abend läuft")).toBeVisible();

  await picker.selectOption({ label: "Klub" });
  await dialog.getByRole("button", { name: "Wechseln" }).click();
  await expect(picker).toHaveValue(klub.id, SYNC);
  await expect(page.getByText("Einen Moment …")).toBeHidden();
  await expect(evenings).toHaveCount(1);
  await expect(evenings.first()).toContainText("Klubheim");
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Klub");
  await expect(page.getByText("Abend läuft")).toBeHidden();
  await expect(roundRow(page)).toContainText("Abgeglichen", SYNC);
  await expectAccessible(page);
  // Die Abende des Stammtischs bleiben auf dem Server.
  expect(fake.server.evenings(stammtisch.id)).toHaveLength(1);

  // Zurück: Alles ist wieder da, auch der laufende Abend und die Namen.
  await picker.selectOption({ label: "Stammtisch" });
  await page.getByRole("dialog", { name: "Zur Runde Stammtisch wechseln?" }).getByRole("button", { name: "Wechseln" }).click();
  await expect(picker).toHaveValue(stammtisch.id, SYNC);
  await expect(page.getByText("Abend läuft")).toBeVisible();
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Stammtisch");
  await openApp(page, "/spieler");
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveText(["Nord", "Ost", "Süd", "West"]);
});

test("ohne Netz lässt sich nicht wechseln: Fehlertext unter der Auswahl, alles bleibt, wie es ist", async ({ page }) => {
  await foundRound(page, fake);
  await otherRound(fake, "Klub");
  const [stammtisch] = await fake.session(KONTO_1.userId).myRounds();
  await openApp(page);
  const picker = roundPicker(page);
  await expect(picker.locator("option")).toHaveText(["Stammtisch", "Klub"]);
  fake.offline = true;

  await picker.selectOption({ label: "Klub" });

  const alert = page.getByRole("alert");
  await expect(alert).toHaveText(/Keine Verbindung\. Bitte später noch einmal versuchen\./);
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(picker).toHaveValue(stammtisch.id);
  await expect(picker).toBeEnabled();
  await expect(roundRow(page)).toContainText("Kein Netz");
  const evenings = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Stammtisch");
  await expect(page.getByText("Abend läuft")).toBeVisible();
  await expectAccessible(page);
  // Der Fehler bleibt, bis man ihn wegtippt.
  await alert.getByRole("button", { name: "OK" }).click();
  await expect(alert).toBeHidden();
  expect(fake.calls("POST", "/rest/v1/rpc/join_round")).toEqual([]);
});

test("während gewechselt wird, steht „Einen Moment …“ über allem; nichts lässt sich bedienen, auch nicht mit Escape", async ({
  page,
  context,
}) => {
  await foundRound(page, fake);
  const klub = await otherRound(fake, "Klub", { location: "Klubheim" });
  const release = await holdHead(context, klub);
  await openApp(page);
  await roundPicker(page).selectOption({ label: "Klub" });
  await page.getByRole("dialog", { name: "Zur Runde Klub wechseln?" }).getByRole("button", { name: "Wechseln" }).click();

  const progress = switchProgress(page);
  await expect(progress).toBeVisible();
  await expect(progress.getByRole("status")).toHaveText("Einen Moment …");
  await expectAccessible(page);
  // Alles dahinter ist gesperrt: Ein Tippen auf „Neuer Abend“ kommt nicht an, Escape schließt nichts.
  const newEvening = page.getByText("Neuer Abend", { exact: true });
  await expect(newEvening.click({ trial: true, timeout: 1_000 })).rejects.toThrow();
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  await expect(progress).toBeVisible();
  expect(await page.locator("dialog.switch-progress").evaluate((dialog) => dialog.matches(":modal"))).toBe(true);

  release();
  await expect(progress).toBeHidden(SYNC);
  await expect(roundPicker(page)).toHaveValue(klub, SYNC);
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem").first()).toContainText("Klubheim");
  await newEvening.click({ trial: true });
});

test("„Einen Moment …“ bleibt auch nach „Zurück“ im Browser stehen, bis der Wechsel fertig ist", async ({ page, context }) => {
  await foundRound(page, fake);
  const klub = await otherRound(fake, "Klub", { location: "Klubheim" });
  await openApp(page);
  await roundRow(page).click();
  const release = await holdHead(context, klub);
  await page.getByRole("list", { name: "Deine anderen Runden" }).getByRole("button", { name: "Zu Klub wechseln" }).click();
  await page.getByRole("dialog", { name: "Zur Runde Klub wechseln?" }).getByRole("button", { name: "Wechseln" }).click();
  const progress = switchProgress(page);
  await expect(progress).toBeVisible();
  await expectAccessible(page);

  // Die Zurück-Taste des Browsers lässt sich nicht sperren; dann steht „Einen Moment …“ über dem Start-Bildschirm.
  await page.goBack();
  await expect(page).not.toHaveURL(/runde/);
  await expect(progress).toBeVisible();

  release();
  await expect(progress).toBeHidden(SYNC);
  await expect(roundPicker(page)).toHaveValue(klub, SYNC);
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem").first()).toContainText("Klubheim");
});

test("hat sich das Gerät seit der Rückfrage geändert (in einem zweiten Fenster), wird nicht gewechselt", async ({ page, context }) => {
  await foundRound(page, fake);
  await otherRound(fake, "Klub");
  await openApp(page);
  await roundPicker(page).selectOption({ label: "Klub" });
  const dialog = page.getByRole("dialog", { name: "Zur Runde Klub wechseln?" });
  await expect(dialog).toBeVisible();

  // Im zweiten Fenster derselben App verlässt das Konto inzwischen den Stammtisch; die Daten bleiben als Kopie.
  const second = await context.newPage();
  await second.goto("./#/runde");
  await second.getByRole("button", { name: "Runde verlassen" }).click();
  await second.getByRole("dialog", { name: "Runde verlassen?" }).getByRole("button", { name: "Runde verlassen" }).click();
  await expect(second.getByText(`Angemeldet als ${KONTO_1.email}`)).toBeVisible();
  await second.close();

  await dialog.getByRole("button", { name: "Wechseln" }).click();

  const alert = page.getByRole("alert");
  await expect(alert).toHaveText(/Inzwischen hat sich auf diesem Gerät etwas geändert\. Bitte noch einmal versuchen\./);
  await expect(dialog).toBeHidden();
  await expect(switchProgress(page)).toBeHidden();
  await expect(page.getByText("Abend läuft")).toBeVisible();
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem")).toHaveCount(1);
  await expectAccessible(page);
  await alert.getByRole("button", { name: "OK" }).click();
  await expect(alert).toBeHidden();
  expect(fake.calls("POST", "/rest/v1/rpc/join_round")).toEqual([]);
});

test("mit verbundener Runde eine neue anlegen: Name geprüft, Rückfrage, sie beginnt leer, die bisherige behält ihre Abende", async ({
  page,
}) => {
  await foundRound(page, fake);
  const form = page.getByRole("form", { name: "Neue Runde anlegen" });
  await expect(form.getByLabel("Name der Runde")).toHaveValue("Schafkopfrunde");
  await expect(form.getByText("Die neue Runde beginnt leer. Die Abende von Stammtisch bleiben dort.")).toBeVisible();
  await expect(page.getByRole("form", { name: "Einladung einlösen" }).getByLabel("Link oder Code")).toBeVisible();

  // Einen Namen, den das Konto schon hat, gibt es kein zweites Mal (bereinigt, ohne Groß und Klein).
  await form.getByLabel("Name der Runde").fill(" stammTISCH ");
  await form.getByRole("button", { name: "Runde anlegen" }).click();
  await expect(page.getByRole("alert")).toHaveText(/Du hast schon eine Runde mit diesem Namen\./);
  await expect(page.getByRole("dialog")).toBeHidden();
  await expectAccessible(page);
  await page.getByRole("alert").getByRole("button", { name: "OK" }).click();

  await form.getByLabel("Name der Runde").fill("Dienstagsrunde");
  await form.getByRole("button", { name: "Runde anlegen" }).click();
  const dialog = page.getByRole("dialog", { name: "Neue Runde Dienstagsrunde anlegen?" });
  await expect(dialog.getByText("Auf diesem Gerät beginnt dann die neue, leere Runde.")).toBeVisible();
  await expect(dialog.getByText("Die Abende von Stammtisch bleiben dort; du kannst jederzeit zurückwechseln.")).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "Anlegen" }).click();

  await expect(page.getByRole("heading", { level: 2, name: "Dienstagsrunde" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible(SYNC);
  // Die neue Runde ist leer: Zur Wahl steht nur „Ich bin neu“.
  await expect(page.getByRole("region", { name: "Wer bist du?" }).getByRole("button")).toHaveText(["Ich bin neu", "Das bin ich"]);
  const others = page.getByRole("list", { name: "Deine anderen Runden" });
  await expect(others.getByRole("listitem")).toHaveCount(1);
  await expect(others.getByRole("listitem")).toContainText("Stammtisch");
  await expect(others.getByText("1 Mitglied: Nord")).toBeVisible();
  await expect(others.getByRole("button", { name: "Stammtisch verlassen" })).toBeVisible();
  await expect(form.getByText("Die neue Runde beginnt leer. Die Abende von Dienstagsrunde bleiben dort.")).toBeVisible();
  await expectAccessible(page);

  // Auf dem Server: Der Stammtisch hat seinen Abend und seine Spieler, die neue Runde nichts.
  const [stammtisch, dienstag] = await fake.session(KONTO_1.userId).myRounds();
  expect([stammtisch.name, dienstag.name]).toEqual(["Stammtisch", "Dienstagsrunde"]);
  expect(fake.server.evenings(stammtisch.id)).toHaveLength(1);
  expect(fake.server.players(stammtisch.id).map((it) => it.name)).toEqual(["Nord", "Ost", "Süd", "West"]);
  expect(fake.server.evenings(dienstag.id)).toEqual([]);
  expect(fake.server.players(dienstag.id)).toEqual([]);

  // Der Start-Bildschirm: keine Abende; die Auswahl zeigt die neue Runde.
  await openApp(page);
  await expect(page.getByText("Noch kein Abend eingetragen. Mit „Neuer Abend“ geht es los.")).toBeVisible();
  await expect(roundPicker(page).locator("option")).toHaveText(["Stammtisch", "Dienstagsrunde"]);
  await expect(roundPicker(page)).toHaveValue(dienstag.id);

  // Zurück über „Deine anderen Runden“.
  await roundRow(page).click();
  await others.getByRole("button", { name: "Zu Stammtisch wechseln" }).click();
  await page.getByRole("dialog", { name: "Zur Runde Stammtisch wechseln?" }).getByRole("button", { name: "Wechseln" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByRole("listitem")).toHaveText(["Nord (du)"], SYNC);
  await expect(others.getByRole("listitem")).toContainText(["Dienstagsrunde"]);
});

test("einer weiteren Runde beitreten, während das Gerät verbunden ist: Einladungsseite, Karte ganz oben, Rückfrage", async ({ page }) => {
  await foundRound(page, fake);
  const klub = await otherRound(fake, "Klub", { location: "Klubheim", member: null });
  const link = InviteLinks.webLink(ROUND_APP_URL, await fake.session(KONTO_2.userId).createInvite(klub));

  await page.goto(link);
  await expect(
    page.getByText("Dieses Gerät ist schon mit der Runde Stammtisch verbunden. Du kannst trotzdem beitreten und dann zwischen den Runden wechseln."),
  ).toBeVisible();
  await page.getByRole("button", { name: "Hier beitreten" }).click();

  // Die Einladung steht ganz oben, über der verbundenen Runde.
  const invitation = page.getByRole("region", { name: "Einladung in die Runde Klub" });
  await expect(invitation.getByText("1 Mitglied")).toBeVisible();
  const card = await invitation.boundingBox();
  const heading = await page.getByRole("heading", { level: 2, name: "Stammtisch" }).boundingBox();
  expect((card?.y ?? 0) < (heading?.y ?? 0)).toBe(true);
  await expect(invitation.getByRole("button", { name: "Einladung verwerfen" })).toBeVisible();
  await expectAccessible(page);
  await invitation.getByRole("button", { name: "Beitreten" }).click();

  const dialog = page.getByRole("dialog", { name: "Der Runde Klub beitreten?" });
  await expect(dialog.getByText("Auf diesem Gerät stehen dann die Abende und Spieler von Klub.")).toBeVisible();
  await expect(dialog.getByText("Die Abende von Stammtisch bleiben dort; du kannst jederzeit zurückwechseln.")).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "Beitreten" }).click();

  await expect(page.getByRole("heading", { level: 2, name: "Klub" })).toBeVisible();
  await expect(invitation).toBeHidden();
  await expect(page.getByRole("region", { name: "Wer bist du?" }).getByRole("button")).toHaveText(["Bert", "Cilli", "Sepp", "Ich bin neu", "Das bin ich"]);
  await expect(page.getByRole("list", { name: "Deine anderen Runden" }).getByRole("listitem")).toContainText(["Stammtisch"]);
  expect(await page.evaluate(() => localStorage.getItem("schafkopf-tracker-einladung"))).toBeNull();
  await expectAccessible(page);

  await openApp(page);
  const evenings = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(evenings).toHaveCount(1);
  await expect(evenings.first()).toContainText("Klubheim");
  await expect(evenings.first().locator(".round-label")).toHaveText("Runde: Klub");
  const [stammtisch] = await fake.session(KONTO_1.userId).myRounds();
  expect(fake.server.evenings(stammtisch.id)).toHaveLength(1);
});

test("eine Einladung in die verbundene Runde: „Du bist schon in dieser Runde.“, und die Einladung ist vergessen", async ({ page }) => {
  await foundRound(page, fake);
  const link = await inviteLink(page);
  await page.goto(link);
  await page.getByRole("button", { name: "Hier beitreten" }).click();
  const invitation = page.getByRole("region", { name: "Einladung in die Runde Stammtisch" });
  await invitation.getByRole("button", { name: "Beitreten" }).click();

  const message = page.getByRole("status").filter({ hasText: "Du bist schon in dieser Runde." });
  await expect(message).toBeVisible();
  await expect(invitation).toBeHidden();
  await expect(page.getByRole("dialog")).toBeHidden();
  expect(await page.evaluate(() => localStorage.getItem("schafkopf-tracker-einladung"))).toBeNull();
  await expectAccessible(page);
  // Die Meldung bleibt stehen, bis man sie wegtippt.
  await message.getByRole("button", { name: "OK" }).click();
  await expect(message).toBeHidden();

  // Ebenso mit dem eingefügten Link.
  const redeem = page.getByRole("form", { name: "Einladung einlösen" });
  await redeem.getByLabel("Link oder Code").fill(`Komm in unsere Schafkopfrunde Stammtisch: ${link}`);
  await redeem.getByRole("button", { name: "Beitreten" }).click();
  await expect(message).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Stammtisch" })).toBeVisible();
  expect(fake.calls("POST", "/rest/v1/rpc/join_round")).toEqual([]);
});

test("Namen von Runden: Die Vorgabe ist frei, einen vergebenen Namen gibt es kein zweites Mal", async ({ page }) => {
  // Das Konto hat schon eine „Schafkopfrunde“, in der schon etwas liegt.
  const owner = fake.session(KONTO_1.userId);
  const round = await owner.createRound("Schafkopfrunde");
  await owner.savePlayer(round, { id: "wirt", name: "Wirt", colorIndex: 0, archived: false });

  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const rounds = page.getByRole("list", { name: "Deine Runden" });
  await expect(rounds.getByText("1 Mitglied", { exact: true })).toBeVisible();
  const form = page.getByRole("form", { name: "Neue Runde anlegen" });
  await expect(form.getByLabel("Name der Runde")).toHaveValue("Schafkopfrunde 2");

  await form.getByLabel("Name der Runde").fill("SCHAFKOPFRUNDE");
  await form.getByRole("button", { name: "Runde anlegen" }).click();

  await expect(page.getByRole("alert")).toHaveText(/Du hast schon eine Runde mit diesem Namen\./);
  await expect(page.getByRole("heading", { level: 2, name: "Deine Runden" })).toBeVisible();
  await expectAccessible(page);
  expect(fake.calls("POST", "/rest/v1/rpc/create_round")).toEqual([]);
  expect(await owner.myRounds()).toHaveLength(1);
});

test("was man in „Name der Runde“ selbst getippt hat, bleibt, auch wenn die Liste der Runden erst danach kommt", async ({ page, context }) => {
  const owner = fake.session(KONTO_1.userId);
  const round = await owner.createRound("Schafkopfrunde");
  await owner.savePlayer(round, { id: "wirt", name: "Wirt", colorIndex: 0, archived: false });
  let release = (): void => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await context.route(`${FAKE_SUPABASE_URL}/rest/v1/rpc/my_rounds`, async (route) => {
    if (route.request().method() === "POST") await held;
    await route.fallback();
  });

  await openRound(page);
  await signIn(page, fake, KONTO_1);
  const field = page.getByRole("form", { name: "Neue Runde anlegen" }).getByLabel("Name der Runde");
  // Solange die Liste fehlt, ist „Schafkopfrunde“ die Vorgabe.
  await expect(field).toHaveValue("Schafkopfrunde");
  await field.fill("Freitagsrunde");
  release();

  await expect(page.getByRole("list", { name: "Deine Runden" }).getByRole("listitem")).toHaveCount(1);
  await expect(field).toHaveValue("Freitagsrunde");
});

test("gleichnamige Runden tragen in der Auswahl und in „Deine anderen Runden“ die Namen ihrer Mitglieder", async ({ page }) => {
  await foundRound(page, fake);
  await otherRound(fake, "Stammtisch", { ownerName: "Wirt" });
  // Eine dritte, in der noch niemand einen Namen hat: Dann nennt die Auswahl die Zahl der Mitglieder.
  await otherRound(fake, "STAMMTISCH", { owner: KONTO_3, ownerName: null });

  await openApp(page);
  const picker = roundPicker(page);
  await expect(picker.locator("option")).toHaveText(["Stammtisch (Nord)", "Stammtisch (Wirt)", "STAMMTISCH (2 Mitglieder)"]);
  await expectAccessible(page);

  await roundRow(page).click();
  const others = page.getByRole("list", { name: "Deine anderen Runden" });
  await expect(others.getByRole("listitem")).toHaveCount(2);
  await expect(others.getByText("2 Mitglieder: Wirt")).toBeVisible();
  await expect(others.getByText("2 Mitglieder", { exact: true })).toBeVisible();
  await expect(others.getByRole("button", { name: "Zu Stammtisch (Wirt) wechseln" })).toBeVisible();
  await expect(others.getByRole("button", { name: "STAMMTISCH (2 Mitglieder) verlassen" })).toBeVisible();
  await expectAccessible(page);

  // Gewählt wird über den Eintrag mit den Mitgliedern; die Rückfrage nennt die Runde beim Namen.
  await openApp(page);
  await picker.selectOption({ label: "Stammtisch (Wirt)" });
  await page.getByRole("dialog", { name: "Zur Runde Stammtisch wechseln?" }).getByRole("button", { name: "Wechseln" }).click();
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem").first().locator(".round-label")).toHaveText(
    "Runde: Stammtisch",
    SYNC,
  );
  const mine = await fake.session(KONTO_1.userId).myRounds();
  await expect(picker).toHaveValue(mine[1].id);
});
