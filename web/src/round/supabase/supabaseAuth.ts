/*
 * Die Anmeldung bei der gemeinsamen Runde über Supabase Auth (docs/ARCHITEKTUR.md, Abschnitt 8.1): nur Google, mit
 * PKCE, über den Browser selbst (die Seite wird verlassen und kehrt mit `?code=…` auf die Start-Adresse zurück).
 *
 * Abgemeldet ist nur, wer sich abmeldet, wessen Sitzung der Server ablehnt oder wer keine gespeicherte Sitzung hat.
 * Lässt sich die Sitzung nur gerade nicht erneuern (kein Netz), bleibt das zuletzt bekannte Konto angemeldet;
 * supabase-js erneuert sie später von selbst.
 *
 * Google fragt bei jeder Anmeldung, welches Konto (`prompt=select_account`): So lässt sich ein falsch gewähltes Konto
 * korrigieren, statt dass Google stillschweigend das zuletzt benutzte nimmt.
 */
import { isAuthPKCECodeVerifierMissingError, isAuthRetryableFetchError, type SupabaseClient, type User } from "@supabase/supabase-js";
import { RoundException } from "../../core/round/RoundModels";
import { NO_SIGN_IN_PENDING, type Account, type RoundAuth } from "../backend";
import { SIGN_IN_EXCHANGE_MILLIS, TimeoutError, within } from "../within";

/** Ablage der Sitzung (im Browser `localStorage`). */
export type SessionStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** So lange wartet die App beim Start und beim Abmelden höchstens auf die Anmeldung (Millisekunden). */
const DEFAULT_WAIT_MILLIS = 4000;

export interface SupabaseAuthOptions {
  /** Rückkehr-Adresse nach der Anmeldung: die Start-Adresse der Web-App. Sie muss beim Server genau so eingetragen sein. */
  readonly redirectTo: string;
  /** Öffnet die Anmeldeseite (im Browser: `location.assign`). */
  readonly openUrl: (url: string) => void;
  readonly storage: SessionStorage;
  readonly storageKey: string;
  /**
   * So lange wird beim Start und beim Abmelden höchstens auf supabase-js gewartet. Ohne Netz versucht die Bibliothek
   * eine abgelaufene Sitzung bis zu einer halben Minute lang zu erneuern; so lange soll niemand warten müssen.
   */
  readonly waitMillis?: number;
  /** So lange darf das Einlösen der Anmeldung nach der Rückkehr dauern; danach gilt es als „keine Verbindung“. */
  readonly exchangeMillis?: number;
}

/**
 * Eine Ablage, die nie scheitert: Lässt sich der Speicher des Browsers nicht lesen oder beschreiben (gesperrt oder
 * voll), bleibt die Anmeldung wenigstens bis zum Schließen der Seite im Arbeitsspeicher. supabase-js fängt Fehler
 * der Ablage nicht überall ab.
 */
export function resilientStorage(storage: SessionStorage): SessionStorage {
  const memory = new Map<string, string>();
  return {
    getItem(key) {
      const kept = memory.get(key);
      if (kept !== undefined) return kept;
      try {
        return storage.getItem(key);
      } catch {
        return null;
      }
    },
    setItem(key, value) {
      try {
        storage.setItem(key, value);
        memory.delete(key);
      } catch {
        memory.set(key, value);
      }
    },
    removeItem(key) {
      memory.delete(key);
      try {
        storage.removeItem(key);
      } catch {
        // Dann steht dort ohnehin nichts Lesbares.
      }
    },
  };
}

function accountOf(user: User): Account {
  return { userId: user.id, email: typeof user.email === "string" && user.email !== "" ? user.email : null };
}

export class SupabaseRoundAuth implements RoundAuth {
  private current: Account | null = null;
  private loaded: Promise<Account | null> | null = null;
  private readonly listeners = new Set<(account: Account | null) => void>();

  constructor(
    private readonly client: SupabaseClient,
    private readonly options: SupabaseAuthOptions,
  ) {
    client.auth.onAuthStateChange((event, session) => {
      // Keine weiteren Aufrufe von supabase-js im Rückruf (Empfehlung der Bibliothek): nur den Zustand setzen.
      if (session) this.apply(accountOf(session.user));
      else if (event === "SIGNED_OUT") this.apply(null);
      // Ohne Sitzung beim Start entscheidet `load`, ob wirklich niemand angemeldet ist.
    });
  }

  load(): Promise<Account | null> {
    this.loaded ??= this.restore();
    return this.loaded;
  }

  private async restore(): Promise<Account | null> {
    // Was im Speicher steht, gilt, bis supabase-js es besser weiß.
    const stored = this.storedAccount();
    try {
      const { data, error } = await within(this.client.auth.getSession(), this.options.waitMillis ?? DEFAULT_WAIT_MILLIS);
      if (data.session) this.apply(accountOf(data.session.user));
      else if (error && isAuthRetryableFetchError(error)) this.apply(stored);
      else this.apply(null);
    } catch {
      // Es dauert zu lange (kein Netz), oder Speicher oder Bibliothek sind gestört: nicht abmelden, sondern beim
      // gespeicherten Stand bleiben. Lehnt der Server die Sitzung später ab, meldet supabase-js das von selbst.
      this.apply(stored);
    }
    return this.current;
  }

  /** Das Konto aus der gespeicherten Sitzung, ohne sie zu erneuern; `null`, wenn keine da ist. */
  private storedAccount(): Account | null {
    try {
      const text = this.options.storage.getItem(this.options.storageKey);
      if (text === null) return null;
      const user = (JSON.parse(text) as { user?: { id?: unknown; email?: unknown } } | null)?.user;
      if (typeof user?.id !== "string") return null;
      return { userId: user.id, email: typeof user.email === "string" && user.email !== "" ? user.email : null };
    } catch {
      return null;
    }
  }

  account(): Account | null {
    return this.current;
  }

  subscribe(listener: (account: Account | null) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  async signIn(): Promise<void> {
    const { data, error } = await this.client.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: this.options.redirectTo, skipBrowserRedirect: true, queryParams: { prompt: "select_account" } },
    });
    if (error || !data.url) throw new RoundException("SERVER", "Anmeldung");
    this.options.openUrl(data.url);
  }

  async completeSignIn(code: string): Promise<Account> {
    let result: Awaited<ReturnType<SupabaseClient["auth"]["exchangeCodeForSession"]>>;
    try {
      result = await within(this.client.auth.exchangeCodeForSession(code), this.options.exchangeMillis ?? SIGN_IN_EXCHANGE_MILLIS);
    } catch (error) {
      // Dauert es zu lange, gilt das wie „keine Verbindung“; kommt die Antwort später doch noch, meldet supabase-js an.
      if (error instanceof TimeoutError) throw new RoundException("OFFLINE", "Anmeldung", error);
      throw new RoundException("SIGNED_OUT", "Anmeldung", error);
    }
    const { data, error } = result;
    // Zu diesem Code gibt es auf dem Gerät keinen Schlüssel: keine begonnene Anmeldung (oder sie ist schon eingelöst).
    if (isAuthPKCECodeVerifierMissingError(error)) throw new RoundException("SIGNED_OUT", NO_SIGN_IN_PENDING);
    if (error || !data.session) {
      throw new RoundException(error && isAuthRetryableFetchError(error) ? "OFFLINE" : "SIGNED_OUT", "Anmeldung");
    }
    const account = accountOf(data.session.user);
    this.apply(account);
    return account;
  }

  async signOut(): Promise<void> {
    try {
      await within(this.client.auth.signOut({ scope: "local" }), this.options.waitMillis ?? DEFAULT_WAIT_MILLIS);
    } catch {
      // Abmelden klappt auf dem Gerät immer; der Server lässt die Sitzung dann ablaufen.
    }
    // Ohne Netz oder mit abgelaufener Sitzung lässt supabase-js die gespeicherte Sitzung unter Umständen stehen.
    try {
      this.options.storage.removeItem(this.options.storageKey);
      this.options.storage.removeItem(`${this.options.storageKey}-code-verifier`);
    } catch {
      // Dann eben nur für diese Sitzung der App.
    }
    this.apply(null);
  }

  private apply(next: Account | null): void {
    if (next?.userId === this.current?.userId && next?.email === this.current?.email) return;
    this.current = next;
    for (const listener of [...this.listeners]) listener(next);
  }
}
