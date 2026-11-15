/// <reference types="vite/client" />

/* Umgebungswerte, die Vite beim Bauen einsetzt (siehe vite.config.ts). */
interface ImportMetaEnv {
  /** Version der Web-App aus package.json, im Build auf GitHub mit dem Commit. */
  readonly VITE_APP_VERSION?: string;
  /** Adresse der veröffentlichten Web-App (bestimmt den Basis-Pfad). */
  readonly VITE_WEB_APP_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
