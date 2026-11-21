/*
 * Hilfen für die Oberflächen-Tests der gemeinsamen Runde: Jeder Test bekommt ein eigenes nachgestelltes
 * Supabase-Projekt (`FakeSupabase` über dem Server im Speicher). Die Anfragen des Browsers an die Adresse des
 * Projekts werden abgefangen und von dort beantwortet – auch die Anmeldung „bei Google“, die sofort mit einem Code
 * zur App zurückkehrt.
 */
import type { Browser, BrowserContext, BrowserContextOptions, Page } from "@playwright/test";
import type { Evening } from "../src/core/model";
import type { RoundId } from "../src/core/round/RoundModels";
import { RuleSets } from "../src/core/rules/RuleSet";
import { FakeSupabase, NETWORK_FAILURE, type FakeAccount } from "../src/round/testing/fakeSupabase";
import { FAKE_SUPABASE_URL, ROUND_APP_URL } from "./roundConfig";
import { blockPlaceSearch, enterGame, expect, openApp, startEvening } from "./support";

export const KONTO_1: FakeAccount = { userId: "konto-1", email: "konto-1@example.org" };
export const KONTO_2: FakeAccount = { userId: "konto-2", email: "konto-2@example.org" };
export const KONTO_3: FakeAccount = { userId: "konto-3", email: "konto-3@example.org" };

/** Lässt alle Anfragen dieses Browsers an das nachgestellte Projekt von `fake` beantworten. */
export async function routeSupabase(context: BrowserContext, fake: FakeSupabase): Promise<void> {
  await context.route(`${FAKE_SUPABASE_URL}/**`, async (route) => {
    const request = route.request();
    const headers = request.headers();
    const cors = {
      "access-control-allow-origin": headers.origin ?? "*",
      "access-control-allow-headers": headers["access-control-request-headers"] ?? "authorization, apikey, content-type, x-client-info",
      "access-control-allow-methods": "GET, POST, PATCH, DELETE, OPTIONS",
      "access-control-expose-headers": "content-range",
      vary: "origin",
    };
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: cors });
      return;
    }
    const text = request.postData();
    let body: unknown;
    try {
      body = text === null || text === "" ? undefined : (JSON.parse(text) as unknown);
    } catch {
      body = text;
    }
    const answer = await fake.handle({ method: request.method(), url: new URL(request.url()), headers, body });
    if (answer === NETWORK_FAILURE) {
      await route.abort("internetdisconnected");
      return;
    }
    const location = answer.headers?.location;
    if (answer.status === 302 && location !== undefined) {
      // Die Weiterleitung zurück zur App als kleine Seite: So verhält sich jede Browser-Engine gleich.
      await route.fulfill({
        status: 200,
        contentType: "text/html; charset=utf-8",
        body: `<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Anmeldung</title></head><body><script>location.replace(${JSON.stringify(location)});</script></body></html>`,
      });
      return;
    }
    const empty = answer.body === undefined || answer.status === 204;
    await route.fulfill({
      status: answer.status,
      headers: { ...cors, ...(empty ? {} : { "content-type": "application/json; charset=utf-8" }) },
      body: empty ? "" : JSON.stringify(answer.body),
    });
  });
}

/** Die Namen der Runde in den Tests. */
export const NAMES = ["Nord", "Ost", "Süd", "West"] as const;

/** So lange darf es dauern, bis ein Abgleich im Hintergrund auf dem Bildschirm ankommt (die App fragt alle vier Sekunden). */
export const SYNC = { timeout: 15_000 };

/**
 * Ein weiteres Handy: eigener Browser mit eigenem Speicher, am selben nachgestellten Projekt.
 *
 * @param options Für Bildschirmfotos zum Beispiel die Größe und Kennung eines iPhones.
 */
export async function anotherDevice(browser: Browser, fake: FakeSupabase, options: BrowserContextOptions = {}): Promise<Page> {
  const context = await browser.newContext({ ...options, baseURL: ROUND_APP_URL, serviceWorkers: "block" });
  await blockPlaceSearch(context);
  await routeSupabase(context, fake);
  return context.newPage();
}

/** Gibt den vier vorbelegten Spielern die Namen der Runde. */
export async function nameRound(page: Page): Promise<void> {
  await openApp(page, "/spieler");
  for (let index = 0; index < NAMES.length; index++) {
    await page.getByRole("button", { name: `Spieler ${index + 1} bearbeiten` }).click();
    const dialog = page.getByRole("dialog", { name: "Spieler bearbeiten" });
    await dialog.getByLabel("Name").fill(NAMES[index]);
    await dialog.getByRole("button", { name: "Speichern" }).click();
    await expect(dialog).toBeHidden();
  }
}

/**
 * Ein Handy mit benannten Spielern, einem laufenden Abend mit einem Spiel und einer Runde „Stammtisch“, in der das
 * Konto der Spieler „Nord“ ist. Am Ende steht der Bildschirm „Gemeinsame Runden“.
 */
export async function foundRound(page: Page, fake: FakeSupabase, account: FakeAccount = KONTO_1): Promise<void> {
  await nameRound(page);
  await startEvening(page);
  await enterGame(page, { players: ["Nord", "Süd"], won: true });
  await openRound(page);
  await signIn(page, fake, account);
  await createRound(page, "Stammtisch");
  await claim(page, "Nord", "Nord");
}

/** Öffnet den laufenden Abend vom Start-Bildschirm aus. */
export async function openRunningEvening(page: Page): Promise<void> {
  await openApp(page);
  await page.getByRole("button", { name: "Weiterspielen" }).click();
  await expect(page.getByRole("button", { name: "Spiel eintragen" })).toBeVisible();
}

/** Gibt einem Spieler auf dem Bildschirm „Spieler“ einen anderen Namen. */
export async function renamePlayer(page: Page, from: string, to: string): Promise<void> {
  await openApp(page, "/spieler");
  await page.getByRole("button", { name: `${from} bearbeiten` }).click();
  const dialog = page.getByRole("dialog", { name: "Spieler bearbeiten" });
  await dialog.getByLabel("Name").fill(to);
  await dialog.getByRole("button", { name: "Speichern" }).click();
  await expect(dialog).toBeHidden();
}

/** Öffnet „Gemeinsame Runden“. */
export async function openRound(page: Page): Promise<void> {
  await openApp(page, "/runde");
  await expect(page.getByRole("heading", { level: 1, name: "Gemeinsame Runden", exact: true })).toBeVisible();
}

/** Meldet das Konto an: „Mit Google anmelden“ antippen; die nachgestellte Anmeldung kehrt sofort zurück. */
export async function signIn(page: Page, fake: FakeSupabase, account: FakeAccount): Promise<void> {
  fake.nextSignIn = account;
  await page.getByRole("button", { name: "Mit Google anmelden" }).click();
  await expect(page.getByText(`Angemeldet als ${account.email}`)).toBeVisible();
}

/** Legt eine Runde an und wartet, bis sie verbunden und abgeglichen ist. */
export async function createRound(page: Page, name: string): Promise<void> {
  const form = page.getByRole("form", { name: "Neue Runde anlegen" });
  await form.getByLabel("Name der Runde").fill(name);
  await form.getByRole("button", { name: "Runde anlegen" }).click();
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  // Erst wenn der erste Abgleich durch ist, stehen die Mitglieder da und die Knöpfe sind wieder frei.
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByRole("listitem")).not.toHaveCount(0);
  await expect(page.getByText("Einen Moment …")).toBeHidden();
  await expect(page.getByRole("status").filter({ hasText: "Abgeglichen" })).toBeVisible();
}

/** „Das bin ich“ mit einem vorhandenen Spieler oder („Ich bin neu“) als neuer Spieler. */
export async function claim(page: Page, choice: string, name: string): Promise<void> {
  const card = page.getByRole("region", { name: "Wer bist du?" });
  await card.getByRole("button", { name: choice, exact: true }).click();
  await card.getByLabel("Dein Name").fill(name);
  await card.getByRole("button", { name: "Das bin ich" }).click();
  const dialog = page.getByRole("dialog", { name: `Als ${name} festlegen?` });
  await expect(dialog.getByText("Der Name lässt sich danach nicht mehr ändern.")).toBeVisible();
  await dialog.getByRole("button", { name: "Festlegen" }).click();
  await expect(page.getByRole("list", { name: "Mitglieder" }).getByText(`${name} (du)`)).toBeVisible();
}

/** Erzeugt einen Einladungslink über „Einladen“ und liest ihn von der Seite. */
export async function inviteLink(page: Page): Promise<string> {
  await page.getByRole("button", { name: "Einladen" }).click();
  const card = page.getByRole("region", { name: "Einladung weitergeben" });
  const text = await card.locator(".invite-text").innerText();
  const link = /https?:\/\/\S+/.exec(text)?.[0];
  if (link === undefined) throw new Error(`kein Link in „${text}“`);
  await card.getByRole("button", { name: "Fertig" }).click();
  return link;
}

/**
 * Die Zeile „Gemeinsame Runden“ (Start-Bildschirm und Einstellungen): Überschrift, „Runde: ‹Name›“ und die
 * Statuszeile. Antippen öffnet den Bildschirm; die Runden-Auswahl darunter gehört nicht dazu.
 */
export function roundRow(page: Page) {
  return page.getByRole("button", { name: /^Gemeinsame Runden/ });
}

/** Die Runden-Auswahl auf dem Start-Bildschirm (eine native Auswahlliste mit der Beschriftung „Runde“). */
export function roundPicker(page: Page) {
  return page.getByRole("combobox", { name: "Runde", exact: true });
}

/**
 * Eine weitere Runde, die ein anderes Konto (`owner`, ohne Angabe Konto 2) angelegt hat: vier Spieler, von denen der
 * erste dem Konto gehört („Das bin ich“ als `ownerName`, ohne Angabe „Anna“; `null`: noch niemand) und die anderen
 * Gäste sind (Bert, Cilli, Sepp – diese Namen sind deshalb für `ownerName` vergeben), und ein beendeter Abend am Ort
 * `location`. `member` (ohne Angabe Konto 1) ist ihr über den Server beigetreten, wie von einem anderen Gerät aus;
 * `null`: niemand.
 *
 * @returns Die Kennung der Runde.
 */
export async function otherRound(
  fake: FakeSupabase,
  name: string,
  options: { owner?: FakeAccount; ownerName?: string | null; member?: FakeAccount | null; location?: string } = {},
): Promise<RoundId> {
  const owner = fake.session((options.owner ?? KONTO_2).userId);
  const round = await owner.createRound(name);
  const ids = ["gast-1", "gast-2", "gast-3", "gast-4"];
  const ownerName = options.ownerName === undefined ? "Anna" : options.ownerName;
  if (ownerName !== null) await owner.claimPlayer(round, ids[0], ownerName, 0);
  else await owner.savePlayer(round, { id: ids[0], name: "Anna", colorIndex: 0, archived: false });
  const guests = ["Bert", "Cilli", "Sepp"];
  for (let index = 0; index < guests.length; index++) {
    await owner.savePlayer(round, { id: ids[index + 1], name: guests[index], colorIndex: index + 1, archived: false });
  }
  const evening: Evening = {
    id: "abend-vom-klub",
    startedAt: "2026-11-20T18:00:00Z",
    finishedAt: "2026-11-20T22:00:00Z",
    location: options.location ?? "",
    seating: ids,
    firstDealer: ids[0],
    rules: RuleSets.standard(),
    entries: [{ kind: "thrown_in", id: "wurf-1", recordedAt: "2026-11-20T18:05:00Z" }],
  };
  await owner.saveEvening(round, evening, 0);
  const member = options.member === undefined ? KONTO_1 : options.member;
  if (member !== null) await fake.session(member.userId).joinRound(await owner.createInvite(round));
  return round;
}

// --- Geräte nachstellen (die App erkennt sie an der Kennung des Browsers, siehe `src/platform/platform.ts`) ---

/** Safari auf dem iPhone (so meldet sich auch der Browser in WhatsApp oder iMessage). */
export const IPHONE_SAFARI =
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

/** Chrome auf einem Android-Handy. */
export const ANDROID_CHROME =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

/** Die Seite läuft als App vom Home-Bildschirm des iPhones (wie dort `navigator.standalone`). */
export async function fromHomeScreen(page: Page): Promise<void> {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "standalone", { configurable: true, get: () => true });
  });
}

/** Eine Zwischenablage, die mitschreibt, was kopiert wurde (in jedem Browser gleich, ohne Berechtigung). */
export async function recordClipboard(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const copied: string[] = [];
    Object.defineProperty(window, "kopiert", { configurable: true, value: copied });
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: (text: string) => {
          copied.push(text);
          return Promise.resolve();
        },
      },
    });
  });
}

/** Was seit dem Laden der Seite kopiert wurde (siehe {@link recordClipboard}). */
export function copiedTexts(page: Page): Promise<string[]> {
  return page.evaluate(() => [...((window as unknown as { kopiert?: string[] }).kopiert ?? [])]);
}

export { FakeSupabase };
