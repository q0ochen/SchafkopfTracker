/* Oberflächen-Tests der Web-App: ein ganzer Abend am Tisch, Sicherung, Neuladen, Barrierefreiheit. */
import { readFile } from "node:fs/promises";
import type { Page } from "@playwright/test";
import {
  answerPlaces,
  blockPlaceSearch,
  cancelShareMenu,
  enterGame,
  expect,
  expectAccessible,
  expectCorner,
  keepShareMenuOpen,
  nameDefaultPlayers,
  openApp,
  PHOTON_REQUESTS,
  photonAnswer,
  shareCount,
  sharedFiles,
  standing,
  startEvening,
  stubShareMenu,
  test,
  waitUntilSaved,
  withoutShareMenu,
} from "./support";

test("Startseite: feste Runde vorbelegt, alles erreichbar und barrierefrei", async ({ page }) => {
  await openApp(page);
  await expect(page.getByRole("heading", { level: 1, name: "Schafkopf Tracker" })).toBeVisible();
  await expect(page.getByText("Noch kein Abend eingetragen.")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "Spieler" }).click();
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveCount(4);
  await expectAccessible(page);
});

test("ein Abend am Tisch: eintragen, zurücknehmen, ändern, Bock, Abend teilen", async ({ page }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await expect(page.getByText("Spiel 1 · Geber: Anna")).toBeVisible();
  await expectAccessible(page);

  // Rufspiel: Anna mit Cilli gewinnen 10 Cent.
  await enterGame(page, { players: ["Anna", "Cilli"], won: true });
  await expect(standing(page, "Anna")).toContainText("+0,10");
  await expect(standing(page, "Bert")).toContainText("−0,10");
  await expect(page.getByText("1 · Rufspiel · Anna mit Cilli")).toBeVisible();
  await expect(page.getByText("Spiel 2 · Geber: Bert")).toBeVisible();

  // Wenz von Bert mit 2 Laufenden, verloren: (50 + 20) × 3 = 210 Cent für Bert.
  await enterGame(page, {
    type: "Wenz",
    players: ["Bert"],
    won: false,
    extra: async (form) => {
      await form.getByRole("group", { name: "Laufende" }).getByRole("button", { name: "2 Laufende" }).click();
      await expect(form.locator(".value-preview")).toContainText("0,70");
    },
  });
  await expect(standing(page, "Bert")).toContainText("−2,20");
  await expect(standing(page, "Anna")).toContainText("+0,80");

  // Zweimal zurücknehmen und beides wiederherstellen (in der richtigen Reihenfolge).
  await page.getByRole("button", { name: "Letzten Eintrag zurücknehmen" }).click();
  await expect(standing(page, "Bert")).toContainText("−0,10");
  await page.getByRole("button", { name: "Letzten Eintrag zurücknehmen" }).click();
  await expect(page.getByText("2 Einträge entfernt")).toBeVisible();
  await expect(page.getByText("Noch nichts eingetragen.")).toBeVisible();
  await page.getByRole("button", { name: "Wiederherstellen" }).click();
  await expect(standing(page, "Bert")).toContainText("−0,10");
  await page.getByRole("button", { name: "Wiederherstellen" }).click();
  await expect(standing(page, "Bert")).toContainText("−2,20");
  await expect(page.getByRole("button", { name: "Wiederherstellen" })).toBeHidden();
  await expect(page.getByRole("list", { name: "Einträge" }).getByRole("listitem").first()).toContainText("1 · Rufspiel");

  // Das erste Spiel war doch verloren.
  await page.getByRole("button", { name: /^1 · Rufspiel · Anna mit Cilli/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Spiel ändern" })).toBeVisible();
  await page.getByRole("button", { name: /^Verloren/ }).click();
  await page.getByRole("button", { name: "Änderung speichern" }).click();
  await expect(standing(page, "Anna")).toContainText("+0,60");
  await expect(standing(page, "Bert")).toContainText("−2,00");

  // 6 Nixer: Bockrunde, das nächste Spiel zählt doppelt.
  await page.getByRole("button", { name: "6 Nixer – Bock" }).click();
  const dialog = page.getByRole("dialog", { name: "Bockrunde auslösen" });
  await dialog.getByLabel("Done").check();
  await dialog.getByRole("button", { name: "Bockrunde starten" }).click();
  await expect(page.getByText("Bock: noch 4 Spiele")).toBeVisible();
  await enterGame(page, {
    players: ["Done", "Bert"],
    won: true,
    extra: async (form) => {
      await expect(form.getByRole("button", { name: /Bock \(zählt doppelt\)/ })).toHaveAttribute("aria-pressed", "true");
      await expect(form.locator(".value-preview")).toContainText("0,20");
    },
  });
  await expect(page.getByText("Bockrunde: 6 Nixer bei Done")).toBeVisible();
  // Done: +0,10 (geändertes Rufspiel) + 0,70 (Wenz) + 0,20 (Rufspiel im Bock) = +1,00.
  await expect(standing(page, "Done")).toContainText("+1,00");

  // Abend beenden führt zu „Abend teilen“: Endstand, Vermögensverlauf (wie in der Statistik) und „Wer zahlt wem“.
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend beenden" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend teilen" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2 })).toHaveText(["Endstand", "Vermögensverlauf", "Wer zahlt wem"]);
  await expect(page.getByRole("group", { name: /Vermögensverlauf/ })).toBeVisible();
  await expect(page.getByRole("list", { name: "Legende" }).getByRole("listitem")).toHaveText(["Anna", "Bert", "Cilli", "Done"]);
  await expect(page.getByRole("list", { name: "Wer zahlt wem" }).getByRole("listitem").first()).toContainText("Bert zahlt");
  // Knöpfe: „Als Bild teilen“ zuerst (Hauptaktion), dann „Als Text teilen“, dann die Statistik.
  await expect(page.locator("main .button")).toHaveText(["Als Bild teilen", "Als Text teilen", "Statistik dieses Abends"]);
  await expect(page.getByRole("button", { name: "Als Bild teilen" })).toHaveClass(/primary/);
  expect((await page.getByRole("button", { name: "Als Bild teilen" }).boundingBox())?.height).toBeGreaterThanOrEqual(56);
  await expectAccessible(page);

  // Statistik dieses Abends.
  await page.getByRole("button", { name: "Statistik dieses Abends" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Vermögensverlauf" })).toBeVisible();
  await expect(page.getByRole("group", { name: /Vermögensverlauf/ })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Spielarten: selbst gespielt" })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Ø je Spiel" })).toBeVisible();
  await expect(page.getByRole("rowheader", { name: /Wenz · 1 Spiel/ })).toBeVisible();
  await expectAccessible(page);

  // Nach dem Neuladen ist alles noch da.
  await waitUntilSaved(page);
  await page.reload();
  await openApp(page);
  await expect(page.getByText("3 Spiele")).toBeVisible();
});

test("Über Kreuz: ein Tipp wählt das Paar mit dem Gegenüber", async ({ page }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await enterGame(page, { type: "Über Kreuz", players: ["Bert"], won: true });
  await expect(page.getByText("1 · Über Kreuz · Bert und Done")).toBeVisible();
  await expect(standing(page, "Done")).toContainText("+0,10");
  await expect(standing(page, "Anna")).toContainText("−0,10");
});

test("Sicherung speichern und auf einem anderen Gerät einlesen", async ({ page, browser }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await enterGame(page, { type: "Farbsolo", players: ["Cilli"], won: true });
  await openApp(page, "/einstellungen");
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Sicherung speichern" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^schafkopf-sicherung-\d{4}-\d{2}-\d{2}\.json$/);
  const text = await readFile(await download.path(), "utf8");
  expect(JSON.parse(text)).toMatchObject({ format: "schafkopf-tracker-backup" });

  // Neues Gerät: leerer Speicher.
  const other = await browser.newPage();
  await blockPlaceSearch(other.context());
  await openApp(other, "/einstellungen");
  await other.locator('input[type="file"]').setInputFiles({ name: "sicherung.json", mimeType: "application/json", buffer: Buffer.from(text) });
  const dialog = other.getByRole("dialog", { name: "Sicherung einlesen?" });
  await expect(dialog).toContainText("1 neuer Abend kommt dazu.");
  await expect(dialog).toContainText("Spieler 1 → Anna");
  await dialog.getByRole("button", { name: "Einlesen" }).click();
  await expect(other.getByText("Sicherung eingelesen.")).toBeVisible();
  await openApp(other);
  await expect(other.getByRole("list", { name: "Alle Abende" }).getByText("1. Cilli")).toBeVisible();
  await other.close();
});

test("eine kaputte Datei wird freundlich abgelehnt", async ({ page }) => {
  await openApp(page, "/einstellungen");
  await page.locator('input[type="file"]').setInputFiles({ name: "kaputt.json", mimeType: "application/json", buffer: Buffer.from("{kaputt") });
  const dialog = page.getByRole("dialog", { name: "Sicherung nicht lesbar" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "OK" }).click();
  await expect(dialog).toBeHidden();
});

test("Regeln dieses Abends ändern rechnet neu", async ({ page }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await enterGame(page, { players: ["Anna", "Cilli"], won: true });
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Regeln dieses Abends" }).click();
  await page.getByRole("button", { name: "Sauspiel: 10-20-50" }).click();
  await page.getByRole("button", { name: "Zurück", exact: true }).click();
  await expect(standing(page, "Anna")).toContainText("+0,20");
});

test("Abend bearbeiten: Datum, Ort, Sitzordnung und erster Geber; Spiele und Beträge bleiben", async ({ page }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await enterGame(page, { players: ["Anna", "Cilli"], won: true });
  await expect(page.getByText("Spiel 2 · Geber: Bert")).toBeVisible();

  // Im Menü zwischen „Abend teilen“ und „Regeln dieses Abends“.
  await page.getByRole("button", { name: "Mehr" }).click();
  await expect(page.locator(".more-menu-list .menu-item")).toHaveText([
    "Statistik dieses Abends",
    "Abend teilen",
    "Abend bearbeiten",
    "Regeln dieses Abends",
    "Abend beenden",
    "Abend löschen",
  ]);
  await page.getByRole("button", { name: "Abend bearbeiten" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend bearbeiten" })).toBeVisible();
  await expect(page.getByText("Eingetragene Spiele und Beträge bleiben unverändert. Wer gibt, ergibt sich aus Sitzordnung und erstem Geber.")).toBeVisible();
  await expect(page.getByText("Die Änderungen gelten für alle in der gemeinsamen Runde.")).toBeHidden();

  // Ohne Änderung ist „Speichern“ gesperrt; Leerraum im Ort ist keine Änderung.
  const save = page.getByRole("button", { name: "Speichern" });
  await expect(save).toBeDisabled();
  const location = page.getByLabel("Ort (freiwillig)");
  await location.fill("   ");
  await expect(save).toBeDisabled();
  await expectAccessible(page);

  // Jeder Tag lässt sich wählen, auch einer nach heute (bis zum 21.11.2026 gesperrt); zurück auf heute ist es unverändert.
  const date = page.getByLabel("Datum");
  await expect(date).not.toHaveAttribute("max");
  const today = await date.inputValue();
  expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await date.fill("2999-01-01");
  await expect(date).toHaveValue("2999-01-01");
  await expect(save).toBeEnabled();
  await date.fill(today);
  await expect(save).toBeDisabled();

  await date.fill("2025-11-15");
  await location.fill("  Beim Huber ");
  await page.getByRole("button", { name: "Bert einen Platz nach vorn" }).click();
  await page.getByRole("radio", { name: /^Cilli/ }).click();
  await expect(page.getByRole("radio", { name: /^Cilli/ })).toHaveAttribute("aria-checked", "true");
  await expect(page.getByRole("list", { name: "Sitzordnung" }).getByRole("listitem")).toHaveText([/^1Bert/, /^2Anna/, /^3Cilli/, /^4Done/]);
  await expect(save).toBeEnabled();
  await expectAccessible(page);
  // Doppelt angetippt wird nur einmal gespeichert und nur ein Schritt zurückgegangen (sonst stünde die Startseite da).
  // Beide Klicks kommen im selben Takt, also sicher, bevor der Bildschirm wechselt.
  await save.evaluate((button: HTMLButtonElement) => {
    button.click();
    button.click();
  });

  // Zurück im Abend: das neue Datum im Titel, die neue Sitzordnung, der Geber neu berechnet, die Beträge wie vorher.
  await expect(page.getByRole("heading", { level: 1, name: /^Abend vom .*15\. Nov\. 2025$/ })).toBeVisible();
  await expect(page.getByRole("region", { name: "Stand" }).getByRole("listitem")).toHaveCount(4);
  await expect(page.getByRole("region", { name: "Stand" }).getByRole("listitem").first()).toContainText("Bert");
  await expect(page.getByText("Spiel 2 · Geber: Done")).toBeVisible();
  await expect(standing(page, "Anna")).toContainText("+0,10");
  await expect(standing(page, "Cilli")).toContainText("+0,10");
  await expect(standing(page, "Bert")).toContainText("−0,10");
  await expect(page.getByText("1 · Rufspiel · Anna mit Cilli")).toBeVisible();

  // Zurück ohne Speichern verwirft, ohne zu fragen.
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend bearbeiten" }).click();
  await expect(page.getByLabel("Datum")).toHaveValue("2025-11-15");
  await expect(page.getByLabel("Ort (freiwillig)")).toHaveValue("Beim Huber");
  await expect(page.getByRole("radio", { name: /^Cilli/ })).toHaveAttribute("aria-checked", "true");
  await expect(page.getByRole("button", { name: "Speichern" })).toBeDisabled();
  await page.getByLabel("Ort (freiwillig)").fill("Daheim");
  await page.getByRole("button", { name: "Zurück", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: /^Abend vom / })).toBeVisible();

  // Die Startseite zeigt den neuen Ort, auch nach dem Neuladen.
  await openApp(page);
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByText(/15\. Nov\. 2025 · Beim Huber/)).toBeVisible();
  await waitUntilSaved(page);
  await page.reload();
  await expect(page.getByRole("list", { name: "Alle Abende" }).getByText(/15\. Nov\. 2025 · Beim Huber/)).toBeVisible();
});

test("Abend bearbeiten: einen Abend, den es nicht gibt, gibt es nicht", async ({ page }) => {
  await openApp(page, "/abend/gibt-es-nicht/bearbeiten");
  await expect(page.getByRole("heading", { level: 1, name: "Abend bearbeiten" })).toBeVisible();
  await expect(page.getByText("Diesen Abend gibt es nicht mehr.")).toBeVisible();
});

/** Was Photon für „Klinikum Bamberg“ liefert (gekürzt auf die Felder, die die App liest, und ein paar andere). */
const KLINIKUM = photonAnswer({
  osm_key: "amenity",
  osm_value: "hospital",
  name: "Klinikum Bamberg",
  street: "Buger Straße",
  housenumber: "80",
  postcode: "96049",
  city: "Bamberg",
  district: "Bug",
  state: "Bayern",
  country: "Deutschland",
  countrycode: "DE",
});

test("Neuer Abend: Ortsvorschläge aus OpenStreetMap, antippen übernimmt den Ort", async ({ page }) => {
  const requests = await answerPlaces(page, KLINIKUM);
  await openApp(page);
  // Die Content-Security-Policy lässt Photon in jedem Build zu.
  await expect(page.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute("content", /connect-src 'self' https:\/\/photon\.komoot\.io;/);
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  const location = page.getByLabel("Ort (freiwillig)");
  await expect(location).toHaveAttribute("maxlength", "100");

  await location.fill("Klinikum Bamberg");
  const list = page.getByRole("list", { name: "Ortsvorschläge" });
  const suggestion = list.getByRole("button", { name: "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg" });
  await expect(suggestion).toBeVisible();
  await expect(suggestion).toContainText("Klinikum Bamberg");
  await expect(suggestion).toContainText("Buger Straße 80, 96049 Bamberg");
  await expect(page.getByText("Vorschläge: © OpenStreetMap-Mitwirkende, über photon.komoot.io")).toBeVisible();
  // Gesendet wird nur der eingetippte Text, ohne Verweis auf die App.
  expect(requests.map((it) => it.url)).toEqual(["https://photon.komoot.io/api/?q=Klinikum%20Bamberg&lang=de&limit=8"]);
  expect(requests[0].headers.referer).toBeUndefined();
  expect((await suggestion.boundingBox())?.height).toBeGreaterThanOrEqual(56);
  await expectAccessible(page);

  await suggestion.click();
  await expect(location).toHaveValue("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
  await expect(list).toBeHidden();
  await expect(page.getByText("Vorschläge: © OpenStreetMap-Mitwirkende, über photon.komoot.io")).toBeHidden();
  // Für das Übernehmen wird nicht gesucht (die Wartezeit nach einer Eingabe ist 400 ms).
  await page.waitForTimeout(800);
  expect(requests).toHaveLength(1);

  await page.getByRole("button", { name: "Abend starten" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await openApp(page);
  await expect(page.getByRole("list", { name: "Alle Abende" })).toContainText("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
});

test("Neuer Abend: ohne Internet nur ein Hinweis, der Ort lässt sich trotzdem eintippen", async ({ page }) => {
  await page.route(PHOTON_REQUESTS, (route) => route.abort("internetdisconnected"));
  await openApp(page);
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  const location = page.getByLabel("Ort (freiwillig)");
  await location.fill("Gasthaus Post");
  await expect(page.getByText("Ortsvorschläge gibt es nur mit Internet.")).toBeVisible();
  await expect(page.getByRole("list", { name: "Ortsvorschläge" })).toHaveCount(0);
  await expectAccessible(page);
  // Unter drei Zeichen verschwindet der Hinweis; mit mehr kommt er nach der nächsten Suche wieder.
  await location.fill("Ga");
  await expect(page.getByText("Ortsvorschläge gibt es nur mit Internet.")).toBeHidden();
  await location.fill("Gasthaus Post");
  await expect(page.getByText("Ortsvorschläge gibt es nur mit Internet.")).toBeVisible();

  await page.getByRole("button", { name: "Abend starten" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await openApp(page);
  await expect(page.getByRole("list", { name: "Alle Abende" })).toContainText("Gasthaus Post");
});

/** Ein Tag im nächsten Jahr („JJJJ-06-15“) zu einem Ortsdatum „JJJJ-MM-TT“. */
function nextYearsDay(today: string): string {
  return `${Number(today.slice(0, 4)) + 1}-06-15`;
}

test("Neuer Abend an einem späteren Tag: geplant, nicht in „Abend läuft“", async ({ page }) => {
  await nameDefaultPlayers(page);
  await startEvening(page);

  await openApp(page);
  await page.getByRole("button", { name: "Neuer Abend" }).click();
  const date = page.getByLabel("Datum");
  const today = await date.inputValue();
  expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(page.getByRole("button", { name: "Abend starten" })).toBeVisible();
  const planned = page.getByText("Der Abend ist geplant. Bis zu diesem Tag steht er unter „Abende“ als „geplant“.");
  await expect(planned).toBeHidden();

  await date.fill(nextYearsDay(today));
  await expect(planned).toBeVisible();
  await expect(page.getByRole("button", { name: "Abend anlegen" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Abend starten" })).toHaveCount(0);
  await expectAccessible(page);
  // Fehlt noch jemand, bleibt es bei „Noch ‹n› auswählen“.
  await page.getByRole("group", { name: "Spieler auswählen" }).getByRole("button", { name: "Bert" }).click();
  await expect(page.getByRole("button", { name: "Noch 1 auswählen" })).toBeDisabled();
  await page.getByRole("group", { name: "Spieler auswählen" }).getByRole("button", { name: "Bert" }).click();
  // Zurück auf heute: wieder „Abend starten“.
  await date.fill(today);
  await expect(planned).toBeHidden();
  await expect(page.getByRole("button", { name: "Abend starten" })).toBeVisible();

  await date.fill(nextYearsDay(today));
  await page.getByLabel("Ort (freiwillig)").fill("Schützenhaus");
  await page.getByRole("button", { name: "Abend anlegen" }).click();
  // Danach öffnet sich der Abend wie sonst, mit dem gewählten Tag im Titel.
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1, name: /^Abend vom .*15\. Juni \d{4}$/ })).toBeVisible();

  // Startseite: „Abend läuft“ zeigt den Abend von heute, der geplante steht nur in der Liste („geplant“ statt „läuft“).
  await openApp(page);
  const runningCard = page.getByRole("region", { name: "Abend läuft" });
  await expect(runningCard).toBeVisible();
  await expect(runningCard).not.toContainText("Schützenhaus");
  const items = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(items).toHaveCount(2);
  await expect(items.first()).toContainText("Schützenhaus");
  await expect(items.first().locator(".badge")).toHaveText("geplant");
  await expect(items.nth(1).locator(".badge")).toHaveText("läuft");
  // Ohne gemeinsame Runde trägt kein Abend die Marke „Runde: …“ (docs/ARCHITEKTUR.md 8.9).
  await expect(page.locator(".round-label")).toHaveCount(0);
  await expectAccessible(page);

  // Ist der Abend von heute beendet, gibt es keine Karte „Abend läuft“ (der geplante zählt nicht).
  await runningCard.getByRole("button", { name: "Weiterspielen" }).click();
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend beenden" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend teilen" })).toBeVisible();
  await openApp(page);
  await expect(page.getByRole("heading", { name: "Abend läuft" })).toHaveCount(0);
  await expect(items.first().locator(".badge")).toHaveText("geplant");
  await expect(items.nth(1).locator(".badge")).toHaveCount(0);
});

test("Abend bearbeiten: ein Tag nach heute macht den Abend zum geplanten; Ortsvorschläge auch hier", async ({ page }) => {
  await answerPlaces(page, KLINIKUM);
  await startEvening(page);
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend bearbeiten" }).click();
  const date = page.getByLabel("Datum");
  await date.fill(nextYearsDay(await date.inputValue()));
  // Beim Öffnen wird nicht gesucht; erst nach einer Eingabe.
  const location = page.getByLabel("Ort (freiwillig)");
  await location.fill("Klinikum");
  await page.getByRole("list", { name: "Ortsvorschläge" }).getByRole("button", { name: /^Klinikum Bamberg/ }).click();
  await expect(location).toHaveValue("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("heading", { level: 1, name: /^Abend vom .*15\. Juni \d{4}$/ })).toBeVisible();

  await openApp(page);
  await expect(page.getByRole("heading", { name: "Abend läuft" })).toHaveCount(0);
  const item = page.getByRole("list", { name: "Alle Abende" }).getByRole("listitem");
  await expect(item).toContainText("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg");
  await expect(item.locator(".badge")).toHaveText("geplant");
});

test("Zurück im Abend nach „Weiterspielen“, auch doppelt angetippt", async ({ page }) => {
  await startEvening(page);
  await openApp(page);
  // Doppelt angetippt: Es entsteht nur ein Schritt, ein Zurück reicht.
  await page.getByRole("button", { name: "Weiterspielen" }).dblclick();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await page.getByRole("button", { name: "Zurück", exact: true }).click();
  await expect(page.getByRole("button", { name: "Weiterspielen" })).toBeVisible();
});

test("Zurück geht auch, wenn der Browser keinen Schritt zurück kann", async ({ page }) => {
  // So verhält sich iOS, wenn es die App vom Home-Bildschirm neu geladen und nur den letzten Bildschirm samt Zustand
  // behalten hat: `history.back()` bewegt nichts. Dann führt „Zurück“ zum übergeordneten Bildschirm.
  await page.addInitScript(() => {
    History.prototype.back = () => undefined;
  });
  await startEvening(page);
  await openApp(page);
  await page.getByRole("button", { name: "Weiterspielen" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
  await page.getByRole("button", { name: "Zurück", exact: true }).click();
  await expect(page.getByRole("button", { name: "Weiterspielen" })).toBeVisible();
});

/** Ein laufender Abend mit zwei Spielen; danach steht „Abend teilen“ offen (aus dem Menü des Abends). */
async function openShareScreen(page: Page): Promise<void> {
  await nameDefaultPlayers(page);
  await startEvening(page);
  await enterGame(page, { players: ["Anna", "Cilli"], won: true });
  await enterGame(page, { type: "Wenz", players: ["Bert"], won: false });
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend teilen" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abend teilen" })).toBeVisible();
}

test("Abend teilen: als Bild über das Teilen-Menü, im selben Takt wie das Antippen", async ({ page }) => {
  await stubShareMenu(page);
  await openShareScreen(page);
  await expect(page.getByText("2 Spiele · Der Abend läuft noch.")).toBeVisible();
  await page.getByRole("button", { name: "Als Bild teilen" }).click();
  await expect.poll(() => shareCount(page)).toBe(1);
  const [image] = await sharedFiles(page);
  // Genau eine PNG-Datei, 1080 Pixel breit, mit deckendem Hintergrund in der Farbe des dunklen Designs.
  expect(image).toMatchObject({ name: "schafkopf-abend.png", type: "image/png", width: 1080 });
  expectCorner(image, [0, 0, 0, 255]);
  expect(image.height).toBeGreaterThan(1500);
  expect(image.height).toBeLessThan(2600);
  // Safari öffnet das Teilen-Menü nur unmittelbar nach dem Antippen: Der Aufruf geschah noch im Klick selbst (ohne
  // `await` dazwischen), mit Nutzeraktion (`null`: Der Browser kennt `navigator.userActivation` nicht).
  expect(image.event).toBe("click");
  expect(image.active).not.toBe(false);
  // Geteilt: keine Meldung.
  await expect(page.getByRole("status")).toHaveCount(0);

  // Abbrechen im Teilen-Menü meldet nichts und lädt nichts herunter.
  await cancelShareMenu(page);
  let downloads = 0;
  page.on("download", () => downloads++);
  await page.getByRole("button", { name: "Als Bild teilen" }).click();
  await expect.poll(() => shareCount(page)).toBe(2);
  await page.waitForTimeout(300);
  expect(downloads).toBe(0);
  await expect(page.getByRole("status")).toHaveCount(0);

  // Der Text lässt sich weiter teilen.
  await page.getByRole("button", { name: "Als Text teilen" }).click();
  await expect(page.getByRole("status")).toHaveCount(0);
});

test("Abend teilen: doppelt angetippt, während das Teilen-Menü aufgeht, gibt es nur ein Teilen-Menü", async ({ page }) => {
  await stubShareMenu(page);
  await openShareScreen(page);
  await keepShareMenuOpen(page);
  let downloads = 0;
  page.on("download", () => downloads++);
  const button = page.getByRole("button", { name: "Als Bild teilen" });
  await button.click();
  await button.click();
  await expect.poll(() => shareCount(page)).toBe(1);
  await page.waitForTimeout(300);
  expect(await shareCount(page)).toBe(1);
  expect(downloads).toBe(0);
  await expect(page.getByRole("status")).toHaveCount(0);
});

test("Abend teilen: Kann der Browser keine Dateien teilen, wird das Bild heruntergeladen", async ({ page }) => {
  await withoutShareMenu(page);
  await openShareScreen(page);
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Als Bild teilen" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("schafkopf-abend.png");
  const png = await readFile(await download.path());
  // PNG-Signatur, dann im Kopf (IHDR) Breite und Höhe.
  expect([...png.subarray(0, 8)]).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  expect(png.readUInt32BE(16)).toBe(1080);
  expect(png.readUInt32BE(20)).toBeGreaterThan(1500);
  await expect(page.getByRole("status")).toHaveText("Das Bild ist heruntergeladen.");
  await expectAccessible(page);
});

test("Statistik: Auswahl oben und „Als Bild teilen“, im hellen Design auch das Bild hell", async ({ page }) => {
  await stubShareMenu(page);
  await openShareScreen(page);
  await page.getByRole("button", { name: "Statistik dieses Abends" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Statistik dieses Abends" })).toBeVisible();
  await expect(page.locator("main .card-title")).toHaveText(/^Abend vom .*\d{4}$/);
  await page.getByRole("button", { name: "Als Bild teilen" }).click();
  await expect.poll(() => shareCount(page)).toBe(1);
  const [one] = await sharedFiles(page);
  expect(one).toMatchObject({ name: "schafkopf-statistik.png", type: "image/png", width: 1080, event: "click" });
  expectCorner(one, [0, 0, 0, 255]);
  await expect(page.getByRole("status")).toHaveCount(0);

  // Alle Abende im hellen Design.
  await openApp(page, "/einstellungen");
  await page.getByRole("group", { name: "Erscheinungsbild" }).getByRole("button", { name: "hell" }).click();
  await openApp(page, "/statistik");
  await expect(page.locator("main .card-title")).toHaveText("Alle Abende (ewige Tabelle)");
  const before = await shareCount(page);
  await page.getByRole("button", { name: "Als Bild teilen" }).click();
  await expect.poll(() => shareCount(page)).toBe(before + 1);
  await expect(page.getByRole("status")).toHaveCount(0);
  await expectAccessible(page);
  const all = (await sharedFiles(page)).at(-1);
  // Hell: deckender Hintergrund #f7faf5 wie die Seite.
  expect(all).toMatchObject({ name: "schafkopf-statistik.png", type: "image/png", width: 1080, event: "click" });
  expectCorner(all, [247, 250, 245, 255]);
  expect(all?.height).toBeGreaterThan(1500);
});

test("Spieler löschen: ohne Abend mit Rückfrage, mit Abend nur archivieren", async ({ page }) => {
  await openApp(page, "/spieler");
  await page.getByLabel("Neuer Spieler").fill("Gast");
  await page.getByRole("button", { name: "Hinzufügen" }).click();
  await page.getByRole("button", { name: "Gast bearbeiten" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Spieler löschen" }).click();
  await expect(page.getByRole("dialog", { name: "Spieler löschen?" })).toContainText("„Gast“ wird endgültig gelöscht.");
  await expectAccessible(page);
  await page.getByRole("dialog", { name: "Spieler löschen?" }).getByRole("button", { name: "Löschen" }).click();
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveCount(4);

  // Wer schon mitgespielt hat, lässt sich nicht löschen.
  await startEvening(page);
  await openApp(page, "/spieler");
  await page.getByRole("button", { name: "Spieler 1 bearbeiten" }).click();
  await expect(page.getByRole("dialog")).toContainText("Spielt in 1 Abend mit und lässt sich deshalb nicht löschen.");
  await expect(page.getByRole("dialog").getByRole("button", { name: "Spieler löschen" })).toHaveCount(0);
});

test("helles Erscheinungsbild ist ebenfalls barrierefrei", async ({ page }) => {
  await openApp(page, "/einstellungen");
  await page.getByRole("group", { name: "Erscheinungsbild" }).getByRole("button", { name: "hell" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expectAccessible(page);
});
