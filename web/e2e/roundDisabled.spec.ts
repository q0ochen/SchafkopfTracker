/*
 * Die App ohne Server der gemeinsamen Runde (so entsteht sie ohne `VITE_SUPABASE_URL` und
 * `VITE_SUPABASE_PUBLISHABLE_KEY`): „Gemeinsame Runden“ zeigt nur den Hinweis, und außer den Ortsvorschlägen verlässt
 * nichts das Gerät.
 */
import type { Evening, Player } from "../src/core/model";
import { RuleSets } from "../src/core/rules/RuleSet";
import { encodeEvening, encodePlayers } from "../src/data/format";
import { expect, expectAccessible, openApp, test } from "./support";

test("ohne Server zeigt „Gemeinsame Runden“ nur den Hinweis, und die App spricht mit niemandem", async ({ page, baseURL }) => {
  const foreign: string[] = [];
  const origin = new URL(baseURL ?? "").origin;
  page.on("request", (request) => {
    const url = request.url();
    if (!url.startsWith(origin) && !url.startsWith("data:") && !url.startsWith("blob:")) foreign.push(url);
  });

  await openApp(page);
  const row = page.getByRole("button", { name: /^Gemeinsame Runden/ });
  await expect(row).toContainText("Alle tragen vom eigenen Handy ein");
  // Ohne Server gibt es keine Runden zur Auswahl.
  await expect(page.getByRole("combobox", { name: "Runde", exact: true })).toBeHidden();
  await row.click();

  await expect(page.getByRole("heading", { level: 1, name: "Gemeinsame Runden", exact: true })).toBeVisible();
  await expect(page.getByText("Der Server ist noch nicht eingerichtet.")).toBeVisible();
  await expect(page.getByText("Sobald er eingerichtet ist, können alle vom eigenen Handy eintragen.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Mit Google anmelden" })).toBeHidden();
  await expectAccessible(page);

  // Auch aus den Einstellungen führt die Zeile dorthin.
  await openApp(page, "/einstellungen");
  await page.getByRole("button", { name: /^Gemeinsame Runden/ }).click();
  await expect(page.getByText("Der Server ist noch nicht eingerichtet.")).toBeVisible();
  await page.getByRole("button", { name: "Zurück" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Einstellungen" })).toBeVisible();
  await expect(
    page.getByText(
      "Es gibt keinen eigenen Server. Nur für Ortsvorschläge geht der eingetippte Ort an photon.komoot.io (OpenStreetMap). Sonst verlässt nichts dieses Gerät, außer du gibst eine Sicherung selbst weiter.",
    ),
  ).toBeVisible();

  expect(foreign).toEqual([]);
  // Der Build enthält die Bibliothek für den Server gar nicht erst.
  const scripts = await page.evaluate(() => performance.getEntriesByType("resource").map((it) => it.name));
  expect(scripts.filter((name) => name.includes("supabase"))).toEqual([]);
});

test("ohne Server führt auch ein Einladungslink nur zum Hinweis", async ({ page }) => {
  await page.goto("./#/einladung/0123456789abcdef0123456789abcdef");
  await expect(page.getByRole("heading", { level: 2, name: "Einladung in eine Schafkopfrunde" })).toBeVisible();
  await expect(page.getByText("0123456789abcdef0123456789abcdef", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Code kopieren" })).toBeVisible();
  await page.getByRole("button", { name: "Hier beitreten" }).click();
  await expect(page.getByText("Der Server ist noch nicht eingerichtet.")).toBeVisible();
});

test("die Datenbank der vorigen Version wird übernommen: Spieler und Abende bleiben", async ({ page }) => {
  const players: Player[] = ["Nord", "Ost", "Süd", "West"].map((name, index) => ({
    id: `spieler-${index + 1}`,
    name,
    colorIndex: index,
    archived: false,
  }));
  const evening: Evening = {
    id: "abend-von-frueher",
    startedAt: "2026-11-14T18:00:00Z",
    finishedAt: "2026-11-14T22:00:00Z",
    location: "Wirtshaus",
    seating: players.map((it) => it.id),
    firstDealer: "spieler-1",
    rules: RuleSets.standard(),
    entries: [{ kind: "thrown_in", id: "wurf-1", recordedAt: "2026-11-14T18:05:00Z" }],
  };
  // Eine Seite derselben Herkunft, ohne die App zu starten: Dort entsteht die Datenbank, wie die App sie vor der
  // gemeinsamen Runde angelegt hat (Version 1, zwei Ablagen), mit Daten im Dateiformat der App.
  await page.goto("./manifest.webmanifest");
  await page.evaluate(
    ({ playersText, eveningId, eveningText }) =>
      new Promise<void>((resolve, reject) => {
        const request = indexedDB.open("schafkopf-tracker", 1);
        request.onupgradeneeded = () => {
          request.result.createObjectStore("evenings");
          request.result.createObjectStore("documents");
        };
        request.onerror = () => reject(new Error("Datenbank nicht angelegt"));
        request.onsuccess = () => {
          const db = request.result;
          const tx = db.transaction(["evenings", "documents"], "readwrite");
          tx.objectStore("documents").put(playersText, "players");
          tx.objectStore("evenings").put(eveningText, eveningId);
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
          tx.onerror = () => reject(new Error("Daten nicht geschrieben"));
        };
      }),
    { playersText: encodePlayers(players), eveningId: evening.id, eveningText: encodeEvening(evening) },
  );

  await openApp(page);

  const evenings = page.getByRole("list", { name: "Alle Abende" });
  await expect(evenings.getByRole("listitem")).toHaveCount(1);
  await expect(evenings).toContainText("Wirtshaus");
  await expect(evenings).toContainText("Nord");
  await openApp(page, "/spieler");
  await expect(page.getByRole("list", { name: "Alle Spieler" }).getByRole("listitem")).toHaveText(["Nord", "Ost", "Süd", "West"]);
  // Die Datenbank ist jetzt auf dem neuen Stand, mit den Ablagen der gemeinsamen Runde.
  const stores = await page.evaluate(
    () =>
      new Promise<{ version: number; names: string[] }>((resolve, reject) => {
        const request = indexedDB.open("schafkopf-tracker");
        request.onerror = () => reject(new Error("Datenbank nicht geöffnet"));
        request.onsuccess = () => {
          const db = request.result;
          const result = { version: db.version, names: [...db.objectStoreNames].sort() };
          db.close();
          resolve(result);
        };
      }),
  );
  expect(stores).toEqual({ version: 2, names: ["documents", "eveningBases", "evenings", "playerBases", "round"] });
});
