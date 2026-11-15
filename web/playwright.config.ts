import { defineConfig, devices } from "@playwright/test";

/**
 * Oberflächen-Tests gegen die gebaute App (`vite preview`), in iPhone-Größe und am Desktop. Gebaut wird in einen
 * eigenen Ordner, damit `dist/` (das, was veröffentlicht wird) unberührt bleibt.
 */
export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  timeout: 30_000,
  use: {
    baseURL: "http://127.0.0.1:4173/SchafkopfTracker/",
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
  webServer: {
    command: "npx vite build --outDir dist-e2e --logLevel warn && npx vite preview --outDir dist-e2e --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173/SchafkopfTracker/",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
