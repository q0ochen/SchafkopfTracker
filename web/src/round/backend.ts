/*
 * Was die Web-App vom Server der gemeinsamen Runde braucht: die Anmeldung und die Aufrufe. Die Umsetzung mit
 * supabase-js liegt in `round/supabase` und wird nur geladen, wenn die App mit einem Server gebaut wurde.
 */
import type { RoundApi } from "../core/round/RoundPorts";

/**
 * Das angemeldete Konto.
 *
 * @property userId Kennung des Kontos beim Server.
 * @property email Nur zum Anzeigen („Angemeldet als …“).
 */
export interface Account {
  readonly userId: string;
  readonly email: string | null;
}

/** Die Anmeldung dieses Geräts beim Server: nur Google, im Browser, mit PKCE. */
export interface RoundAuth {
  /** Liest die gespeicherte Anmeldung (einmal) und liefert das angemeldete Konto. */
  load(): Promise<Account | null>;

  /** Das angemeldete Konto; `null`, solange niemand angemeldet ist oder {@link load} noch nicht gelaufen ist. */
  account(): Account | null;

  subscribe(listener: (account: Account | null) => void): () => void;

  /** Beginnt die Anmeldung bei Google. Die Seite wird dafür verlassen und kehrt mit einem Code zurück. */
  signIn(): Promise<void>;

  /** Schließt die Anmeldung mit dem Code aus der Rückkehr-Adresse ab. */
  completeSignIn(code: string): Promise<Account>;

  /** Meldet dieses Gerät ab. Das klappt auch ohne Netz; der Server lässt die Sitzung dann ablaufen. */
  signOut(): Promise<void>;
}

export interface RoundBackend {
  readonly api: RoundApi;
  readonly auth: RoundAuth;
}
