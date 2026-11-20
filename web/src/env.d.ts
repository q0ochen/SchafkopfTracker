/// <reference types="vite/client" />

/* Umgebungswerte, die Vite beim Bauen einsetzt (siehe vite.config.ts). */
interface ImportMetaEnv {
  /** Version der Web-App aus package.json, im Build auf GitHub mit dem Commit. */
  readonly VITE_APP_VERSION?: string;
  /** Adresse der veröffentlichten Web-App (bestimmt den Basis-Pfad). */
  readonly VITE_WEB_APP_URL?: string;
  /** Gemeinsame Runde: Adresse des Supabase-Projekts; leer, wenn die App ohne Server gebaut wurde. */
  readonly VITE_SUPABASE_URL?: string;
  /** Gemeinsame Runde: öffentlicher Schlüssel (Publishable Key) des Supabase-Projekts. */
  readonly VITE_SUPABASE_PUBLISHABLE_KEY?: string;
  /** `true`, wenn die App mit Adresse und Schlüssel des Servers gebaut wurde (setzt vite.config.ts). */
  readonly VITE_ROUND_ENABLED?: boolean;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
