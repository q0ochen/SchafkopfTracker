/*
 * Zustand der Web-App: Spieler, Abende und Einstellungen im Arbeitsspeicher, gespiegelt in den Speicher des
 * Browsers. Jede Änderung wirkt sofort auf dem Bildschirm und wird dann der Reihe nach gespeichert. Schlägt das
 * Speichern fehl, merkt sich der Zustand den Fehler, damit die Oberfläche ihn anzeigen kann.
 */
import { compareInstants, instantFromEpochMillis } from "../core/instant";
import type { AppSettings, Evening, EveningId, InstantText, Player, PlayerId } from "../core/model";
import { RuleSets } from "../core/rules/RuleSet";
import type { AppRepository } from "../data/repository";
import { cleanName, defaultPlayers } from "./players";

export interface AppState {
  /** Alle Spieler in Anlagereihenfolge, auch archivierte. */
  readonly players: readonly Player[];
  /** Alle Abende, der neueste zuerst (wie in der Android-App). */
  readonly evenings: readonly Evening[];
  readonly settings: AppSettings;
  /** Letzter Speicherfehler; `null`, solange alles gespeichert ist. */
  readonly saveError: string | null;
  /** Wie viele gespeicherte Abende sich beim Start nicht lesen ließen. */
  readonly unreadableEvenings: number;
}

/** Uhr und Kennungen, austauschbar für Tests. */
export interface Clock {
  now(): InstantText;
  newId(): string;
}

export const systemClock: Clock = {
  now: () => instantFromEpochMillis(Date.now()),
  newId: () => crypto.randomUUID(),
};

/** Einstellungen beim ersten Start: Regeln der Runde, Bildschirm anlassen, dunkel (wie in der Android-App). */
export function defaultSettings(): AppSettings {
  return { defaultRules: RuleSets.standard(), keepScreenOn: true, themeMode: "DARK" };
}

/** Neueste Abende zuerst; bei gleichem Beginn nach Kennung (wie `NEWEST_FIRST` in der Android-App). */
export function sortEvenings(evenings: readonly Evening[]): Evening[] {
  return [...evenings].sort((a, b) => {
    const byStart = compareInstants(b.startedAt, a.startedAt);
    if (byStart !== 0) return byStart;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

export class AppStore {
  private state: AppState;
  private readonly listeners = new Set<() => void>();
  private queue: Promise<void> = Promise.resolve();
  private pendingWrites = 0;
  private savingListener: ((saving: boolean) => void) | null = null;

  private constructor(
    private readonly repository: AppRepository,
    readonly clock: Clock,
    initial: AppState,
  ) {
    this.state = initial;
  }

  /** Lädt alles aus dem Speicher. Gibt es noch keinen Spieler, wird die feste Vierer-Runde angelegt. */
  static async load(repository: AppRepository, clock: Clock = systemClock): Promise<AppStore> {
    const data = await repository.load();
    const players = data.players ?? [];
    const store = new AppStore(repository, clock, {
      players: players.length > 0 ? players : defaultPlayers(),
      evenings: sortEvenings(data.evenings),
      settings: data.settings ?? defaultSettings(),
      saveError: null,
      unreadableEvenings: data.unreadableEvenings.length,
    });
    if (players.length === 0) await store.persist(() => repository.savePlayers(store.state.players));
    return store;
  }

  getState(): AppState {
    return this.state;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private setState(changes: Partial<AppState>): void {
    this.state = { ...this.state, ...changes };
    for (const listener of this.listeners) listener();
  }

  /**
   * Meldet, ob gerade noch gespeichert wird (`true`) oder alles im Speicher des Browsers liegt (`false`). Die
   * Kompositionswurzel zeigt das als Attribut an der Seite; die Oberflächen-Tests warten darauf vor dem Neuladen.
   */
  setSavingListener(listener: ((saving: boolean) => void) | null): void {
    this.savingListener = listener;
    listener?.(this.pendingWrites > 0);
  }

  /** Hängt einen Speicherauftrag an die Warteschlange. Das Ergebnis ist erfüllt, sobald er erledigt ist. */
  private persist(task: () => Promise<void>): Promise<void> {
    this.pendingWrites++;
    if (this.pendingWrites === 1) this.savingListener?.(true);
    const run = this.queue
      .then(task)
      .then(
        () => {
          if (this.state.saveError !== null) this.setState({ saveError: null });
        },
        (error: unknown) => {
          console.error("Schafkopf Tracker: Speichern fehlgeschlagen", error);
          this.setState({ saveError: error instanceof Error ? error.message : String(error) });
        },
      )
      .finally(() => {
        this.pendingWrites--;
        if (this.pendingWrites === 0) this.savingListener?.(false);
      });
    this.queue = run;
    return run;
  }

  /** Wartet, bis alle Änderungen gespeichert sind (für Tests und vor dem Exportieren). */
  flush(): Promise<void> {
    return this.queue;
  }

  // ---------------------------------------------------------------- Abende

  evening(id: EveningId): Evening | undefined {
    return this.state.evenings.find((it) => it.id === id);
  }

  /** Neuer oder geänderter Abend. */
  saveEvening(evening: Evening): Promise<void> {
    const others = this.state.evenings.filter((it) => it.id !== evening.id);
    this.setState({ evenings: sortEvenings([...others, evening]) });
    return this.persist(() => this.repository.saveEvening(evening));
  }

  /** Ändert einen Abend; `null`, wenn es ihn nicht (mehr) gibt oder sich nichts ändert. */
  updateEvening(id: EveningId, transform: (evening: Evening) => Evening | null): Promise<Evening | null> {
    const before = this.evening(id);
    if (!before) return Promise.resolve(null);
    const after = transform(before);
    if (after === null || after === before) return Promise.resolve(null);
    return this.saveEvening(after).then(() => after);
  }

  deleteEvening(id: EveningId): Promise<void> {
    this.setState({ evenings: this.state.evenings.filter((it) => it.id !== id) });
    return this.persist(() => this.repository.deleteEvening(id));
  }

  // ---------------------------------------------------------------- Spieler

  /** Name eines Spielers; „?“, wenn er unbekannt ist. */
  playerName(id: PlayerId): string {
    return this.state.players.find((it) => it.id === id)?.name ?? "?";
  }

  /** Legt einen Spieler an; `null`, wenn der Name leer oder zu lang ist. */
  addPlayer(nameInput: string): Player | null {
    const name = cleanName(nameInput);
    if (name === null) return null;
    const player: Player = { id: this.clock.newId(), name, colorIndex: this.state.players.length, archived: false };
    const players = [...this.state.players, player];
    this.setState({ players });
    void this.persist(() => this.repository.savePlayers(players));
    return player;
  }

  /** Ändert Name oder Archiv-Status; `false`, wenn der Name leer oder zu lang ist. */
  updatePlayer(id: PlayerId, nameInput: string, archived: boolean): boolean {
    const name = cleanName(nameInput);
    if (name === null) return false;
    const players = this.state.players.map((it) => (it.id === id ? { ...it, name, archived } : it));
    this.setState({ players });
    void this.persist(() => this.repository.savePlayers(players));
    return true;
  }

  // ---------------------------------------------------------------- Einstellungen

  updateSettings(transform: (settings: AppSettings) => AppSettings): Promise<void> {
    const settings = transform(this.state.settings);
    this.setState({ settings });
    return this.persist(() => this.repository.saveSettings(settings));
  }

  // ---------------------------------------------------------------- Sicherung

  /**
   * Übernimmt eingelesene Spieler und Abende: Spieler und Abende mit derselben Kennung werden ersetzt, alle anderen
   * bleiben. Alles wird in einem Zug gespeichert.
   */
  importData(players: readonly Player[], evenings: readonly Evening[]): Promise<void> {
    const merged = [...this.state.players];
    for (const player of players) {
      const index = merged.findIndex((it) => it.id === player.id);
      if (index >= 0) merged[index] = player;
      else merged.push(player);
    }
    const importedIds = new Set(evenings.map((it) => it.id));
    const allEvenings = sortEvenings([...this.state.evenings.filter((it) => !importedIds.has(it.id)), ...evenings]);
    this.setState({ players: merged, evenings: allEvenings });
    return this.persist(() => this.repository.saveImport(merged, evenings));
  }
}
