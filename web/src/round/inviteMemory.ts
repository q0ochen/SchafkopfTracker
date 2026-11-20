/*
 * Was sich die Web-App zu Einladungen im Speicher des Browsers (`localStorage`) merkt:
 * - den Einladungscode, mit dem jemand kommt: Wer über einen Einladungslink kommt, muss sich erst anmelden; dafür wird
 *   die Seite verlassen. Der Code wartet solange.
 * - die zuletzt auf diesem Gerät erzeugte Einladung (docs/ARCHITEKTUR.md, Abschnitt 8.4): Sie wird wieder angeboten,
 *   solange sie jünger als 13 Tage ist (gültig sind 14), damit wiederholtes „Einladen“ nicht an die Grenze von 20
 *   gültigen Einladungen je Runde stößt.
 */
import { InviteLinks } from "../core/round/InviteLinks";
import type { RoundId } from "../core/round/RoundModels";

type BrowserStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export interface InviteMemory {
  /** Der gemerkte Einladungscode; `null`, wenn keiner wartet. */
  get(): string | null;

  /** Merkt sich den Code; `null` vergisst ihn. */
  set(token: string | null): void;
}

/** Unter diesem Schlüssel liegt der Code im Speicher des Browsers. */
export const INVITE_STORAGE_KEY = "schafkopf-tracker-einladung";

/** Ablage im Speicher des Browsers (`localStorage`). Ist er gesperrt, gilt der Code nur, solange die Seite offen ist. */
export function storedInviteMemory(storage: BrowserStorage): InviteMemory {
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

// --- Zuletzt erzeugte Einladung ---

/**
 * Die zuletzt auf diesem Gerät erzeugte Einladung.
 *
 * @property createdAt Wann sie erzeugt wurde, in Millisekunden seit 1970.
 */
export interface IssuedInvite {
  readonly roundId: RoundId;
  readonly token: string;
  readonly createdAt: number;
}

export interface IssuedInviteMemory {
  /** Die gemerkte Einladung; `null`, wenn keine (lesbare) da ist. */
  get(): IssuedInvite | null;

  /** Merkt sich die Einladung; `null` vergisst sie (beim Verlassen und Abmelden). */
  set(invite: IssuedInvite | null): void;
}

/** Unter diesem Schlüssel liegt die zuletzt erzeugte Einladung im Speicher des Browsers. */
export const ISSUED_INVITE_KEY = "schafkopf-tracker-einladung-erzeugt";

/** So lange wird eine erzeugte Einladung wieder angeboten: 13 Tage (gültig ist sie 14). */
export const INVITE_REUSE_MILLIS = 13 * 24 * 60 * 60 * 1000;

/**
 * Der Code der gemerkten Einladung, wenn sie zu dieser Runde gehört und jünger als 13 Tage ist; sonst `null`. Stammt
 * sie scheinbar aus der Zukunft (die Uhr des Geräts wurde zurückgestellt), lässt sich ihr Alter nicht sagen: dann nicht.
 */
export function reusableInvite(invite: IssuedInvite | null, roundId: RoundId, now: number): string | null {
  if (invite === null || invite.roundId !== roundId) return null;
  const age = now - invite.createdAt;
  return age >= 0 && age < INVITE_REUSE_MILLIS ? invite.token : null;
}

function issuedInviteOf(text: string): IssuedInvite | null {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const { roundId, token, createdAt } = value as Record<string, unknown>;
  if (typeof roundId !== "string" || typeof token !== "string" || typeof createdAt !== "number" || !Number.isFinite(createdAt)) return null;
  // Nur ein echter Code zählt (genau 32 Zeichen 0–9 und a–f).
  return InviteLinks.tokenFrom(token) === token ? { roundId, token, createdAt } : null;
}

/** Ablage im Speicher des Browsers (`localStorage`). Ist er gesperrt, gilt die Einladung nur, solange die Seite offen ist. */
export function storedIssuedInvites(storage: BrowserStorage): IssuedInviteMemory {
  let fallback: IssuedInvite | null = null;
  return {
    get() {
      try {
        const stored = storage.getItem(ISSUED_INVITE_KEY);
        return stored === null ? fallback : issuedInviteOf(stored);
      } catch {
        return fallback;
      }
    },
    set(invite) {
      fallback = invite;
      try {
        if (invite === null) storage.removeItem(ISSUED_INVITE_KEY);
        else storage.setItem(ISSUED_INVITE_KEY, JSON.stringify(invite));
      } catch {
        // Gesperrter Speicher: Die Einladung bleibt nur im Arbeitsspeicher.
      }
    },
  };
}
