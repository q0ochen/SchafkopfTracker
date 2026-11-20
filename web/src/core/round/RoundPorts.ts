/*
 * Ports der gemeinsamen Runde: der Server aus Sicht des angemeldeten Kontos und was sich ein Gerät zum Abgleich
 * merkt (Port von `domain/round/RoundPorts.kt`), dazu die Daten des Geräts, wie sie der Abgleich braucht (in Kotlin
 * `domain/ports/Repositories.kt` und `EveningEditor`). Umgesetzt werden sie in `web/src/round` (Supabase, IndexedDB,
 * Zustand der App); Tests benutzen die Umsetzungen im Speicher aus `InMemoryRound.ts` und `InMemoryRepositories.ts`.
 *
 * Wo Kotlin `suspend` und `Flow.first()` benutzt, stehen hier Promises.
 */
import type { AppSettings, Evening, EveningId, Player, PlayerId, RuleSet } from "../model";
import type {
  EveningBase,
  InviteInfo,
  RemoteEvening,
  RemotePlayer,
  RoundHead,
  RoundId,
  RoundLink,
  RoundMember,
  RoundSummary,
  SavedEvening,
} from "./RoundModels";

/**
 * Der Server der gemeinsamen Runde, aus Sicht des angemeldeten Kontos (docs/ARCHITEKTUR.md, Abschnitt 8.3).
 * Jeder Aufruf kann mit einer `RoundException` scheitern.
 */
export interface RoundApi {
  /** Der Kopf der Runde; `null`, wenn es sie nicht mehr gibt oder das Konto kein Mitglied (mehr) ist. */
  head(round: RoundId): Promise<RoundHead | null>;

  /** Die Mitglieder der Runde, das älteste zuerst. */
  members(round: RoundId): Promise<RoundMember[]>;

  /** Alle Spieler, die nach der Änderung `rev` geschrieben wurden, in der Reihenfolge der Änderungen. */
  playersSince(round: RoundId, rev: number): Promise<RemotePlayer[]>;

  /** Alle Abende, die nach der Änderung `rev` geschrieben wurden, in der Reihenfolge der Änderungen. */
  eveningsSince(round: RoundId, rev: number): Promise<RemoteEvening[]>;

  /** Ein einzelner Abend; `null`, wenn der Server ihn nicht kennt. */
  evening(round: RoundId, id: EveningId): Promise<RemoteEvening | null>;

  /**
   * Speichert einen Abend, wenn der Server noch die Fassung `expectedVersion` hat (0: Der Abend ist neu).
   *
   * @returns die neue Fassung oder `null`, wenn inzwischen jemand anders gespeichert hat.
   */
  saveEvening(round: RoundId, evening: Evening, expectedVersion: number, deleted?: boolean): Promise<SavedEvening | null>;

  /** Legt einen Gast an oder ändert einen Spieler; `deleted` löscht einen Gast. Liefert das neue `rev`. */
  savePlayer(round: RoundId, player: Player, deleted?: boolean): Promise<number>;

  /** Setzt das Regelwerk für neue Abende. Liefert das neue `rev`. */
  saveRules(round: RoundId, rules: RuleSet): Promise<number>;

  /** Die Runden des angemeldeten Kontos. */
  myRounds(): Promise<RoundSummary[]>;

  createRound(name: string): Promise<RoundId>;

  /** Ein neuer Einladungscode für die Runde. */
  createInvite(round: RoundId): Promise<string>;

  inviteInfo(token: string): Promise<InviteInfo>;

  /** Tritt der Runde hinter dem Einladungscode bei. */
  joinRound(token: string): Promise<RoundId>;

  leaveRound(round: RoundId): Promise<void>;

  /** „Das bin ich“: übernimmt den Spieler `player` oder legt ihn an und legt den eigenen Namen fest. */
  claimPlayer(round: RoundId, player: PlayerId, name: string, colorIndex: number): Promise<void>;
}

/** Die Namen aller Aufrufe von {@link RoundApi} (zum Weiterreichen und Zählen in Tests). */
export const ROUND_API_METHODS = [
  "head",
  "members",
  "playersSince",
  "eveningsSince",
  "evening",
  "saveEvening",
  "savePlayer",
  "saveRules",
  "myRounds",
  "createRound",
  "createInvite",
  "inviteInfo",
  "joinRound",
  "leaveRound",
  "claimPlayer",
] as const satisfies ReadonlyArray<keyof RoundApi>;

/** Was sich ein Gerät zum Abgleich merkt (docs/ARCHITEKTUR.md, Abschnitt 8.4). */
export interface RoundStore {
  /** Die verbundene Runde; `null`, wenn das Gerät für sich allein arbeitet. */
  link(): Promise<RoundLink | null>;

  setLink(link: RoundLink | null): Promise<void>;

  /** Je Abend die Fassung, die dieses Gerät zuletzt vom Server kennt. */
  eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>>;

  /** Setzt oder (mit `null`) entfernt die Basis eines Abends. */
  setEveningBase(id: EveningId, base: EveningBase | null): Promise<void>;

  /** Je Spieler der Stand, den dieses Gerät zuletzt vom Server kennt. */
  playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>>;

  /** Setzt oder (mit `null`) entfernt die Basis eines Spielers. */
  setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void>;

  /** Vergisst alle Basen (beim Verbinden mit einer Runde und beim Trennen). */
  clearBases(): Promise<void>;
}

/** Die Spieler dieses Geräts (Kotlin: `PlayerRepository`). */
export interface PlayerRepository {
  /** Alle Spieler, auch archivierte, in der Reihenfolge, in der sie angelegt wurden. */
  players(): Promise<readonly Player[]>;

  /** Legt einen Spieler an oder ersetzt den Spieler mit derselben Kennung. */
  save(player: Player): Promise<void>;

  /**
   * Löscht den Spieler mit dieser Kennung endgültig; gibt es ihn nicht, passiert nichts. Abende werden dabei nicht
   * angefasst: Nur Spieler löschen, die in keinem Abend vorkommen (siehe `involves`).
   */
  delete(id: PlayerId): Promise<void>;
}

/** Die Abende dieses Geräts (Kotlin: `EveningRepository`). */
export interface EveningRepository {
  /** Alle Abende, der neueste zuerst. */
  evenings(): Promise<readonly Evening[]>;

  /** Ein einzelner Abend; `null`, wenn es ihn nicht (mehr) gibt. */
  evening(id: EveningId): Promise<Evening | null>;

  /**
   * Speichert den Abend dauerhaft. Kehrt erst zurück, wenn die Daten sicher auf dem Gerät liegen.
   *
   * @throws EveningNotStorableError wenn sich der Abend wegen seines Inhalts nicht speichern lässt.
   */
  save(evening: Evening): Promise<void>;

  delete(id: EveningId): Promise<void>;
}

/**
 * Ein Abend lässt sich wegen seines Inhalts nicht speichern (beschädigte oder fremde Daten). Gespeichert wurde dann
 * nichts (Kotlin: `EveningNotStorableException`; dort wirft sie nur Room, zum Beispiel wenn ein Eintrag eine Kennung
 * trägt, die es in einem anderen Abend schon gibt). In der Web-App meldet sie die Anbindung an den Zustand, wenn sich
 * ein Abend nicht ins Dateiformat schreiben lässt.
 */
export class EveningNotStorableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "EveningNotStorableError";
  }
}

/** Die Einstellungen der App (Kotlin: `SettingsRepository`); der Abgleich braucht daraus das Regelwerk für neue Abende. */
export interface SettingsRepository {
  settings(): Promise<AppSettings>;

  update(transform: (settings: AppSettings) => AppSettings): Promise<void>;
}

/**
 * Ändert Abende so, dass keine Eingabe am Tisch verloren geht (Kotlin: `EveningEditor.replace`).
 */
export interface EveningEditor {
  /**
   * Liest den aktuellen Stand des Abends (oder `null`), wendet `transform` an und speichert das Ergebnis; `null`
   * löscht den Abend. Zwischen Lesen und Speichern kommt keine andere Änderung dieses Abends dazwischen. Der Abgleich
   * legt damit Abende an, ersetzt und löscht sie, ohne einer Eingabe am Tisch in die Quere zu kommen.
   *
   * @returns der Abend, wie er danach gespeichert ist, oder `null`, wenn es ihn danach nicht gibt.
   * @throws EveningNotStorableError wenn sich das Ergebnis wegen seines Inhalts nicht speichern lässt.
   */
  replace(id: EveningId, transform: (current: Evening | null) => Evening | null): Promise<Evening | null>;
}

/** Erzeugt neue, eindeutige Kennungen (Kotlin: `IdGenerator`). */
export interface IdGenerator {
  newId(): string;
}
