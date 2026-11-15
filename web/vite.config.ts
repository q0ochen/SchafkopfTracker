/// <reference types="vitest/config" />
import { readFileSync } from "node:fs";
import { defineConfig, loadEnv } from "vite";
import { basePathFromWebAppUrl, DEFAULT_WEB_APP_URL } from "./scripts/base-path.mjs";
import { contentSecurityPolicyPlugin, serviceWorkerPlugin } from "./scripts/vite-plugins.mjs";

export default defineConfig(({ mode }) => {
  const env = { ...loadEnv(mode, process.cwd(), "VITE_"), ...process.env };
  const webAppUrl = env.VITE_WEB_APP_URL?.trim() || DEFAULT_WEB_APP_URL;
  const base = basePathFromWebAppUrl(webAppUrl);
  const { version } = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as { version: string };
  const commit = process.env.GITHUB_SHA ? ` (${process.env.GITHUB_SHA.slice(0, 7)})` : "";

  return {
    base,
    define: { "import.meta.env.VITE_APP_VERSION": JSON.stringify(`${version}${commit}`) },
    // JSX für Preact (Vite 8 übersetzt mit Oxc).
    oxc: { jsx: { runtime: "automatic", importSource: "preact" } },
    build: {
      target: ["es2022", "safari16.4"],
      sourcemap: true,
      assetsInlineLimit: 0,
    },
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
    },
    preview: { port: 4173, strictPort: true },
    plugins: [contentSecurityPolicyPlugin(), serviceWorkerPlugin({ entry: "src/sw/sw.ts" })],
    test: {
      include: ["src/**/*.test.ts", "scripts/**/*.test.ts"],
      environment: "node",
      restoreMocks: true,
    },
  };
});
