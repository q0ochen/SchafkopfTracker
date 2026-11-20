/*
 * Merkt sich einen Einladungscode über die Anmeldung hinweg: Wer über einen Einladungslink kommt, muss sich erst
 * anmelden; dafür wird die Seite verlassen. Der Code liegt solange im Speicher des Browsers.
 */
import { InviteLinks } from "../core/round/InviteLinks";

export interface InviteMemory {
  /** Der gemerkte Einladungscode; `null`, wenn keiner wartet. */
  get(): string | null;

  /** Merkt sich den Code; `null` vergisst ihn. */
  set(token: string | null): void;
}

/** Unter diesem Schlüssel liegt der Code im Speicher des Browsers. */
export const INVITE_STORAGE_KEY = "schafkopf-tracker-einladung";

/** Ablage im Speicher des Browsers (`localStorage`). Ist er gesperrt, gilt der Code nur, solange die Seite offen ist. */
export function storedInviteMemory(storage: Pick<Storage, "getItem" | "setItem" | "removeItem">): InviteMemory {
  let fallback: string | null = null;
  return {
    get() {
      try {
        // Nur ein echter Code zählt; alles andere (von Hand verändert) gilt als nicht vorhanden.
        const stored = storage.getItem(INVITE_STORAGE_KEY);
        return stored === null ? fallback : InviteLinks.tokenFrom(stored);
      } catch {
        return fallback;
      }
    },
    set(token) {
      fallback = token;
      try {
        if (token === null) storage.removeItem(INVITE_STORAGE_KEY);
        else storage.setItem(INVITE_STORAGE_KEY, token);
      } catch {
        // Gesperrter Speicher: Der Code bleibt nur im Arbeitsspeicher.
      }
    },
  };
}
