/* Oberflächen-Tests der Web-App: ein ganzer Abend am Tisch, Sicherung, Neuladen, Barrierefreiheit. */
import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { enterGame, expectAccessible, nameDefaultPlayers, openApp, standing, startEvening, waitUntilSaved } from "./support";

test("Startseite: feste Runde vorbelegt, alles erreichbar und barrierefrei", async ({ page }) => {
  await openApp(page);
  await expect(page.getByRole("heading", { level: 1, name: "Schafkopf Tracker" })).toBeVisible();
  await expect(page.getByText("Noch kein Abend eingetragen.")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "Spieler" }).click();
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveCount(4);
  await expectAccessible(page);
});

test("ein Abend am Tisch: eintragen, zurücknehmen, ändern, Bock, Abschluss", async ({ page }) => {
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

  // Abend beenden: Abschluss mit „Wer zahlt wem“.
  await page.getByRole("button", { name: "Mehr" }).click();
  await page.getByRole("button", { name: "Abend beenden" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Abschluss" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Wer zahlt wem" }).getByRole("listitem").first()).toContainText("Bert zahlt");
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

  // Im Menü zwischen „Abschluss“ und „Regeln dieses Abends“.
  await page.getByRole("button", { name: "Mehr" }).click();
  await expect(page.locator(".more-menu-list .menu-item")).toHaveText([
    "Statistik dieses Abends",
    "Abschluss",
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

  // Ein Tag nach heute lässt sich nicht wählen.
  const date = page.getByLabel("Datum");
  const today = await date.getAttribute("max");
  expect(today).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(date).toHaveValue(today ?? "");
  await date.fill("2999-01-01");
  await expect(date).toHaveValue(today ?? "");
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
