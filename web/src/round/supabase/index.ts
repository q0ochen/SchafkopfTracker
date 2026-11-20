/*
 * Die Anbindung der gemeinsamen Runde an Supabase mit supabase-js. Dieses Modul (und mit ihm die Bibliothek) wird nur
 * geladen, wenn die App mit einem Server gebaut wurde (`VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`) – und
 * dann erst bei Bedarf. Ohne Konfiguration bleibt die App genau so groß und so schnell wie ohne gemeinsame Runde.
 *
 * Benutzt werden nur Anmeldung und Datenschnittstelle. Es gibt kein Realtime: Ohne Kanal baut supabase-js keine
 * dauerhafte Verbindung auf.
 */
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import type { RoundBackend } from "../backend";
import type { RoundConfig } from "../config";
import { resilientStorage, SupabaseRoundAuth, type SessionStorage } from "./supabaseAuth";
import { SupabaseRoundApi } from "./supabaseRoundApi";

/** Unter diesem Schlüssel liegt die Sitzung im Speicher des Browsers. */
export const AUTH_STORAGE_KEY = "schafkopf-tracker-anmeldung";

export interface SupabaseBackendOptions {
  /** Rückkehr-Adresse nach der Anmeldung: die Start-Adresse der Web-App. */
  readonly redirectTo: string;
  /** Verlässt die Seite zur Anmeldung bei Google. */
  readonly openUrl: (url: string) => void;
  /** Ablage der Sitzung; im Browser `localStorage`. */
  readonly storage: SessionStorage;
  /** Für Tests: eigenes `fetch`. */
  readonly fetch?: typeof fetch;
  /** Für Tests: die Sitzung nicht von selbst im Hintergrund erneuern. */
  readonly autoRefresh?: boolean;
  /** Für Tests: So lange darf eine Anfrage dauern (Millisekunden). */
  readonly timeoutMillis?: number;
  /** Für Tests: So lange wird beim Start und beim Abmelden höchstens auf die Anmeldung gewartet (Millisekunden). */
  readonly waitMillis?: number;
}

/**
 * Antwortet die Anmeldung des Servers mit einem Fehler der Art „gerade nicht da“ (auch 540: das Projekt ist pausiert),
 * soll das die gespeicherte Sitzung nicht kosten. supabase-js behält sie nur bei den Antworten, die es als
 * vorübergehend kennt (500 bis 504, 520 bis 530); bei anderen verwirft es eine abgelaufene Sitzung. Deshalb werden
 * alle übrigen Antworten ab 500 der Anmeldung hier als 503 weitergereicht.
 */
function keepSessionWhenServerIsDown(send: typeof fetch): typeof fetch {
  return async (input, init) => {
    const response = await send(input, init);
    if (response.status < 500 || response.status === 503) return response;
    const address = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    let path: string;
    try {
      path = new URL(address).pathname;
    } catch {
      return response;
    }
    if (!path.startsWith("/auth/v1/")) return response;
    return new Response(response.body, { status: 503, statusText: "Service Unavailable", headers: response.headers });
  };
}

/** Die Anbindung und der Client von supabase-js dahinter (den Client brauchen nur Proben, die sich anders anmelden). */
export interface SupabaseConnection {
  readonly backend: RoundBackend;
  readonly client: SupabaseClient;
}

export function createSupabaseBackend(config: RoundConfig, options: SupabaseBackendOptions): RoundBackend {
  return connectSupabase(config, options).backend;
}

/** Baut den Client von supabase-js so, wie die App ihn benutzt, und darauf Anmeldung und Aufrufe. */
export function connectSupabase(config: RoundConfig, options: SupabaseBackendOptions): SupabaseConnection {
  const storage = resilientStorage(options.storage);
  const send = options.fetch ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const client = createClient(config.url, config.publishableKey, {
    auth: {
      flowType: "pkce",
      // Der Code aus der Rückkehr-Adresse wird gezielt eingelöst (siehe `signInReturn.ts`), nicht nebenbei beim Laden.
      detectSessionInUrl: false,
      persistSession: true,
      autoRefreshToken: options.autoRefresh ?? true,
      storage,
      storageKey: AUTH_STORAGE_KEY,
    },
    global: { fetch: keepSessionWhenServerIsDown(send) },
  });
  const backend: RoundBackend = {
    api: new SupabaseRoundApi(client, options.timeoutMillis),
    auth: new SupabaseRoundAuth(client, {
      redirectTo: options.redirectTo,
      openUrl: options.openUrl,
      storage,
      storageKey: AUTH_STORAGE_KEY,
      waitMillis: options.waitMillis,
    }),
  };
  return { backend, client };
}
