/*
 * Zustand der Web-App: Spieler, Abende und Einstellungen im Arbeitsspeicher, gespiegelt in den Speicher des
 * Browsers. Jede Änderung wirkt sofort auf dem Bildschirm und wird dann der Reihe nach gespeichert. Schlägt das
 * Speichern fehl, merkt sich der Zustand den Fehler, damit die Oberfläche ihn anzeigen kann.
 */
import { compareInstants, instantFromEpochMillis } from "../core/instant";
import type { AppSettings, Evening, EveningId, InstantText, Player, PlayerId } from "../core/model";
import { sameEvening } from "../core/round/equality";
import { RuleSets } from "../core/rules/RuleSet";
import type { AppRepository } from "../data/repository";
import { cleanName, defaultPlayers, eveningInvolves, nextColorIndex } from "./players";

export interface AppState {
  /** Alle Spieler in Anlagereihenfolge, auch archivierte. */
  readonly players: readonly Player[];
  /** Alle Abende, der neueste zuerst (wie in der Android-App). */
  readonly evenings: readonly Evening[];
  readonly settings: AppSettings;
  /** Letzter Speicherfehler; `null`, solange alles gespeichert ist. */
  readonly saveError: string | null;
  /** Die Kennungen der gespeicherten Abende, die sich beim Laden nicht lesen ließen. Sie bleiben unangetastet liegen. */
  readonly unreadableEvenings: readonly EveningId[];
  /**
   * Spielerliste („players“) oder Einstellungen („settings“) ließen sich beim Laden nicht lesen. Dann gelten keine
   * Spieler bzw. die Standardwerte – das ist nicht der echte Stand. Der Abgleich der gemeinsamen Runde ruht deshalb,
   * bis die App neu geladen ist und beides wieder lesen kann.
   */
  readonly unreadableDocuments: readonly string[];
}

/** Was beim Laden anders sein darf als beim allerersten Start. */
export interface LoadOptions {
  /**
   * `false`: keine vorbelegten Spieler anlegen, auch wenn es noch keinen Spieler gibt. So startet ein Gerät, das mit
   * einer gemeinsamen Runde verbunden ist: Deren Spieler kommen vom Server, und Platzhalter gingen sonst als neue
   * Spieler in die Runde hoch.
   */
  readonly seedPlayers?: boolean;
}

/**
 * Der Speicher des Browsers lässt sich gerade nicht verlässlich benutzen: Speichern ist gescheitert, oder Spielerliste
 * bzw. Einstellungen ließen sich nicht lesen. Die gemeinsame Runde gleicht dann nicht ab (Statuszeile „Speichern auf
 * diesem Gerät gescheitert – bitte die App neu laden“).
 */
export class DeviceStorageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DeviceStorageError";
  }
}

/** Eine Änderung ließ sich nicht im Speicher des Browsers ablegen (zum Beispiel, weil er voll ist). */
export class StorageFailedError extends DeviceStorageError {
  constructor() {
    super("Speichern auf dem Gerät fehlgeschlagen");
    this.name = "StorageFailedError";
  }
}

/** Spielerliste oder Einstellungen ließen sich beim Laden nicht lesen; was im Arbeitsspeicher steht, ist nicht der echte Stand. */
export class UnreadableDataError extends DeviceStorageError {
  constructor() {
    super("Spielerliste oder Einstellungen auf dem Gerät nicht lesbar");
    this.name = "UnreadableDataError";
  }
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

/** Was ein Speicherauftrag schreibt (zum Merken, was noch nicht auf dem Gerät liegt). */
interface WriteTarget {
  readonly evenings?: readonly EveningId[];
  readonly players?: boolean;
  readonly settings?: boolean;
}

export class AppStore {
  private state: AppState;
  private readonly listeners = new Set<() => void>();
  private queue: Promise<void> = Promise.resolve();
  private pendingWrites = 0;
  private savingListener: ((saving: boolean) => void) | null = null;

  /** Was sich zuletzt nicht speichern ließ: Dort ist der Stand im Arbeitsspeicher neuer als der auf dem Gerät. */
  private readonly unsavedEvenings = new Set<EveningId>();
  private unsavedPlayers = false;
  private unsavedSettings = false;

  private constructor(
    private readonly repository: AppRepository,
    readonly clock: Clock,
    initial: AppState,
  ) {
    this.state = initial;
  }

  /**
   * Lädt alles aus dem Speicher. Gibt es noch keinen Spieler, wird die feste Vierer-Runde angelegt – außer das Gerät
   * ist mit einer gemeinsamen Runde verbunden (siehe {@link LoadOptions}).
   */
  static async load(repository: AppRepository, clock: Clock = systemClock, options: LoadOptions = {}): Promise<AppStore> {
    const data = await repository.load();
    const players = data.players ?? [];
    const seed = players.length === 0 && options.seedPlayers !== false;
    const store = new AppStore(repository, clock, {
      players: seed ? defaultPlayers() : players,
      evenings: sortEvenings(data.evenings),
      settings: data.settings ?? defaultSettings(),
      saveError: null,
      unreadableEvenings: data.unreadableEvenings,
      unreadableDocuments: data.unreadableDocuments,
    });
    if (seed) await store.persist(() => repository.savePlayers(store.state.players), { players: true });
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
  private persist(task: () => Promise<void>, target: WriteTarget): Promise<void> {
    return this.persisted(task, target).then(() => undefined);
  }

  /** Wie {@link persist}; das Ergebnis sagt, ob das Speichern gelungen ist. */
  private persisted(task: () => Promise<void>, target: WriteTarget): Promise<boolean> {
    this.pendingWrites++;
    if (this.pendingWrites === 1) this.savingListener?.(true);
    const run = this.queue
      .then(task)
      .then(
        () => {
          this.markUnsaved(target, false);
          if (this.state.saveError !== null) this.setState({ saveError: null });
          return true;
        },
        (error: unknown) => {
          console.error("Schafkopf Tracker: Speichern fehlgeschlagen", error);
          this.markUnsaved(target, true);
          this.setState({ saveError: error instanceof Error ? error.message : String(error) });
          return false;
        },
      )
      .finally(() => {
        this.pendingWrites--;
        if (this.pendingWrites === 0) this.savingListener?.(false);
      });
    this.queue = run.then(() => undefined);
    return run;
  }

  /** Die Aufträge laufen der Reihe nach: Es gilt jeweils, wie der letzte Auftrag für dasselbe Ziel ausgegangen ist. */
  private markUnsaved(target: WriteTarget, unsaved: boolean): void {
    for (const id of target.evenings ?? []) {
      if (unsaved) this.unsavedEvenings.add(id);
      else this.unsavedEvenings.delete(id);
    }
    if (target.players) this.unsavedPlayers = unsaved;
    if (target.settings) this.unsavedSettings = unsaved;
  }

  /** Wie {@link persist}, scheitert aber mit {@link StorageFailedError}, wenn sich nicht speichern ließ. */
  private async persistOrFail(task: () => Promise<void>, target: WriteTarget): Promise<void> {
    if (!(await this.persisted(task, target))) throw new StorageFailedError();
  }

  /** Wartet, bis alle Änderungen gespeichert sind (für Tests und vor dem Exportieren). */
  flush(): Promise<void> {
    return this.queue;
  }

  /** Ob alles, was im Arbeitsspeicher steht, auch im Speicher des Browsers liegt (nichts wartet, nichts ist gescheitert). */
  private isSaved(): boolean {
    return this.pendingWrites === 0 && this.unsavedEvenings.size === 0 && !this.unsavedPlayers && !this.unsavedSettings;
  }

  /**
   * Wartet auf alle Speicheraufträge und holt nach, was sich zuletzt nicht speichern ließ. Scheitert mit
   * {@link StorageFailedError}, wenn es wieder nicht geht. Der Abgleich der gemeinsamen Runde ruft das, bevor er
   * den Zustand liest: Er darf sich nichts als „auf dem Server bekannt“ merken, was nicht wirklich auf dem Gerät liegt.
   */
  async settle(): Promise<void> {
    await this.flush();
    if (this.unsavedEvenings.size === 0 && !this.unsavedPlayers && !this.unsavedSettings) return;
    const evenings = [...this.unsavedEvenings];
    const players = this.unsavedPlayers;
    const settings = this.unsavedSettings;
    await this.persistOrFail(
      async () => {
        for (const id of evenings) {
          const evening = this.evening(id);
          if (evening === undefined) await this.repository.deleteEvening(id);
          else await this.repository.saveEvening(evening);
        }
        if (players) await this.repository.savePlayers(this.state.players);
        if (settings) await this.repository.saveSettings(this.state.settings);
      },
      { evenings, players, settings },
    );
  }

  /**
   * Wartet, bis alle Änderungen im Speicher des Browsers liegen – auch die, die währenddessen dazukommen –, und führt
   * `block` dann sofort aus, ohne dass etwas dazwischenkommt: Was `block` im Zustand liest, steht genau so auf dem
   * Gerät. Scheitert mit {@link StorageFailedError}, wenn sich etwas nicht speichern lässt. Der Abgleich der
   * gemeinsamen Runde liest und ersetzt so: Er darf sich nie etwas als „vom Server bekannt“ merken, das nicht wirklich
   * auf dem Gerät liegt (docs/ARCHITEKTUR.md, Abschnitt 8.8).
   */
  async whenSaved<T>(block: () => T | Promise<T>): Promise<T> {
    for (;;) {
      await this.settle();
      if (this.isSaved()) return block();
    }
  }

  /**
   * Holt den Stand vom Gerät, wenn ein anderes Fenster der App inzwischen gespeichert hat. Eigene Änderungen werden
   * vorher gespeichert, damit das Neulesen sie nicht verwirft; lässt sich das nicht, scheitert es mit
   * {@link StorageFailedError}. Hat kein anderes Fenster gespeichert, bleibt alles, wie es ist.
   *
   * @returns ob neu gelesen wurde.
   */
  async catchUp(): Promise<boolean> {
    await this.flush();
    if (await this.repository.isCurrent()) return false;
    await this.settle();
    await this.reload();
    return true;
  }

  // ---------------------------------------------------------------- Abende

  evening(id: EveningId): Evening | undefined {
    return this.state.evenings.find((it) => it.id === id);
  }

  /** Neuer oder geänderter Abend. */
  saveEvening(evening: Evening): Promise<void> {
    const others = this.state.evenings.filter((it) => it.id !== evening.id);
    this.setState({ evenings: sortEvenings([...others, evening]) });
    return this.persist(() => this.repository.saveEvening(evening), { evenings: [evening.id] });
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
    return this.persist(() => this.repository.deleteEvening(id), { evenings: [id] });
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
    const player: Player = { id: this.clock.newId(), name, colorIndex: nextColorIndex(this.state.players), archived: false };
    const players = [...this.state.players, player];
    this.setState({ players });
    void this.persist(() => this.repository.savePlayers(players), { players: true });
    return player;
  }

  /** Ändert Name oder Archiv-Status; `false`, wenn der Name leer oder zu lang ist. */
  updatePlayer(id: PlayerId, nameInput: string, archived: boolean): boolean {
    const name = cleanName(nameInput);
    if (name === null) return false;
    const players = this.state.players.map((it) => (it.id === id ? { ...it, name, archived } : it));
    this.setState({ players });
    void this.persist(() => this.repository.savePlayers(players), { players: true });
    return true;
  }

  /**
   * Löscht einen Spieler endgültig, aber nur, wenn er in keinem Abend vorkommt; sonst `false` (dann bleibt nur das
   * Archivieren). Gibt es ihn nicht, ebenfalls `false`.
   */
  deletePlayer(id: PlayerId): boolean {
    if (!this.state.players.some((it) => it.id === id)) return false;
    if (this.state.evenings.some((evening) => eveningInvolves(evening, id))) return false;
    const players = this.state.players.filter((it) => it.id !== id);
    this.setState({ players });
    void this.persist(() => this.repository.savePlayers(players), { players: true });
    return true;
  }

  // ---------------------------------------------------------------- Einstellungen

  updateSettings(transform: (settings: AppSettings) => AppSettings): Promise<void> {
    const settings = transform(this.state.settings);
    this.setState({ settings });
    return this.persist(() => this.repository.saveSettings(settings), { settings: true });
  }

  // ---------------------------------------------------------------- Abgleich der gemeinsamen Runde
  //
  // Der Abgleich schreibt über diese Aufrufe. Anders als die Aufrufe der Oberfläche scheitern sie, wenn sich die
  // Änderung nicht speichern ließ: Der Abgleich merkt sich danach, was er vom Server kennt, und das darf nie neuer
  // sein als das, was wirklich auf dem Gerät liegt.

  /**
   * Liest den aktuellen Stand des Abends (oder `null`), wendet `transform` an und übernimmt das Ergebnis; `null` löscht
   * den Abend. Lesen und Übernehmen geschehen in einem Zug: Wie bei {@link updateEvening} kommt keine Eingabe am
   * Tisch dazwischen, und keine geht verloren.
   *
   * @returns das Ergebnis von `transform`, sobald es gespeichert ist.
   */
  replaceEvening(id: EveningId, transform: (current: Evening | null) => Evening | null): Promise<Evening | null> {
    const current = this.evening(id) ?? null;
    const changed = transform(current);
    if (changed === null) {
      if (current === null) return Promise.resolve(null);
      return this.removeEvening(id).then(() => null);
    }
    if (current !== null && sameEvening(changed, current)) return Promise.resolve(changed);
    return this.putEvening(changed).then(() => changed);
  }

  /** Neuer oder geänderter Abend. */
  putEvening(evening: Evening): Promise<void> {
    const others = this.state.evenings.filter((it) => it.id !== evening.id);
    this.setState({ evenings: sortEvenings([...others, evening]) });
    return this.persistOrFail(() => this.repository.saveEvening(evening), { evenings: [evening.id] });
  }

  removeEvening(id: EveningId): Promise<void> {
    if (!this.state.evenings.some((it) => it.id === id)) return Promise.resolve();
    this.setState({ evenings: this.state.evenings.filter((it) => it.id !== id) });
    return this.persistOrFail(() => this.repository.deleteEvening(id), { evenings: [id] });
  }

  /** Legt einen Spieler an (hinten) oder ersetzt den Spieler mit derselben Kennung an seiner Stelle; der Name wird nicht geprüft. */
  putPlayer(player: Player): Promise<void> {
    const players = this.state.players.some((it) => it.id === player.id)
      ? this.state.players.map((it) => (it.id === player.id ? player : it))
      : [...this.state.players, player];
    this.setState({ players });
    return this.persistOrFail(() => this.repository.savePlayers(players), { players: true });
  }

  /** Löscht den Spieler mit dieser Kennung; gibt es ihn nicht, passiert nichts. Ob ihn noch ein Abend braucht, prüft der Aufrufer. */
  removePlayer(id: PlayerId): Promise<void> {
    if (!this.state.players.some((it) => it.id === id)) return Promise.resolve();
    const players = this.state.players.filter((it) => it.id !== id);
    this.setState({ players });
    return this.persistOrFail(() => this.repository.savePlayers(players), { players: true });
  }

  putSettings(transform: (settings: AppSettings) => AppSettings): Promise<void> {
    const settings = transform(this.state.settings);
    this.setState({ settings });
    return this.persistOrFail(() => this.repository.saveSettings(settings), { settings: true });
  }

  /**
   * Liest Spieler, Abende und Einstellungen neu aus dem Speicher, nachdem ein anderes Fenster der App sie geändert hat.
   * Eigene Speicheraufträge werden vorher abgewartet.
   */
  async reload(): Promise<void> {
    await this.flush();
    const data = await this.repository.load();
    // Ab jetzt gilt wieder, was auf dem Gerät liegt.
    this.unsavedEvenings.clear();
    this.unsavedPlayers = false;
    this.unsavedSettings = false;
    this.setState({
      players: data.players ?? [],
      evenings: sortEvenings(data.evenings),
      settings: data.settings ?? this.state.settings,
      unreadableEvenings: data.unreadableEvenings,
      unreadableDocuments: data.unreadableDocuments,
    });
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
    return this.persist(() => this.repository.saveImport(merged, evenings), { players: true, evenings: evenings.map((it) => it.id) });
  }
}
