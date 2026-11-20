import { defineConfig, devices } from "@playwright/test";
import { APP_URL, FAKE_SUPABASE_KEY, FAKE_SUPABASE_URL, ROUND_APP_URL } from "./e2e/roundConfig";

/**
 * Oberflächen-Tests gegen die gebaute App (`vite preview`), in iPhone-Größe und am Desktop. Gebaut wird in eigene
 * Ordner, damit `dist/` (das, was veröffentlicht wird) unberührt bleibt – und zwar zweimal:
 * - Port 4173: ohne gemeinsame Runde, so wie die App ohne Angaben zum Server entsteht (auch wenn die Umgebung welche
 *   mitbringt);
 * - Port 4174: mit gemeinsamer Runde, gebaut mit der Adresse eines Servers, den es nicht gibt. Dessen Anfragen fangen
 *   die Tests ab und beantworten sie mit einem Server im Speicher (`e2e/roundSupport.ts`).
 */
export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  timeout: 30_000,
  use: {
    baseURL: APP_URL,
    locale: "de-DE",
    timezoneId: "Europe/Berlin",
    // Der Service Worker würde zwischen den Tests Dateien zwischenspeichern; nur der Offline-Test schaltet ihn ein.
    serviceWorkers: "block",
    trace: "retain-on-failure",
  },
  projects: [
    // iPhone-Größe (Zielgerät der Web-App), gerendert mit Chromium.
    { name: "handy", use: { ...devices["iPhone 13"], browserName: "chromium" } },
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 860 } } },
    // Mit der Safari-Engine (WebKit), so nah am iPhone wie ohne Gerät möglich. Läuft auf GitHub (E2E_WEBKIT=1);
    // dort wird WebKit eigens installiert.
    ...(process.env.E2E_WEBKIT === "1" ? [{ name: "iphone-webkit", use: { ...devices["iPhone 13"] } }] : []),
  ],
  webServer: [
    {
      command: "npx vite build --outDir dist-e2e --logLevel warn && npx vite preview --outDir dist-e2e --host 127.0.0.1 --port 4173 --strictPort",
      url: APP_URL,
      env: { VITE_SUPABASE_URL: "", VITE_SUPABASE_PUBLISHABLE_KEY: "" },
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command:
        "npx vite build --outDir dist-e2e-runde --logLevel warn && npx vite preview --outDir dist-e2e-runde --host 127.0.0.1 --port 4174 --strictPort",
      url: ROUND_APP_URL,
      env: { VITE_SUPABASE_URL: FAKE_SUPABASE_URL, VITE_SUPABASE_PUBLISHABLE_KEY: FAKE_SUPABASE_KEY },
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
