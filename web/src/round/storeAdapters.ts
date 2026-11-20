/*
 * Verbindet den Abgleich der gemeinsamen Runde mit dem Zustand der Web-App: Spieler, Abende und Einstellungen liest
 * und schreibt er über `AppStore`. Das Zusammenführen eines Abends läuft dabei in einem Zug mit dem Zustand
 * (`AppStore.replaceEvening`), genau wie jede Eingabe am Tisch (`AppStore.updateEvening`): So überschreibt keines
 * das andere.
 *
 * Weil der Browser anders speichert als Room, gilt zusätzlich (docs/ARCHITEKTUR.md, Abschnitt 8.8):
 * - Der Abgleich arbeitet nur mit Gespeichertem. Jedes Lesen und jedes Ersetzen wartet, bis alles im Speicher des
 *   Browsers liegt (`AppStore.whenSaved`); lässt sich etwas nicht speichern, scheitert es mit `StorageFailedError`.
 *   Der Abgleich bricht dann ab, bevor er hochlädt oder sich eine Basis merkt: Eine Basis ist so nie neuer als das
 *   Gerät. Alle Schreibaufrufe scheitern ebenso, wenn sich die Änderung nicht speichern ließ.
 * - Was sich auf dem Gerät nicht lesen lässt, gilt nie als gelöscht oder leer: Unlesbare Abende überschreibt der
 *   Abgleich nicht (sie gelten ihm als „lässt sich nicht speichern“), und ihre Basen blendet {@link ShieldedRoundStore}
 *   aus, ohne sie anzufassen. Ließen sich Spielerliste oder Einstellungen nicht lesen, liest der Abgleich gar nichts
 *   ({@link UnreadableDataError}); er ruht, bis die App neu geladen ist.
 */
import { UnreadableDataError, type AppState, type AppStore } from "../app/store";
import type { Evening, EveningId, PlayerId } from "../core/model";
import type { EveningBase, RemotePlayer, RoundLink } from "../core/round/RoundModels";
import {
  EveningNotStorableError,
  type EveningEditor,
  type EveningRepository,
  type PlayerRepository,
  type SettingsRepository,
} from "../core/round/RoundPorts";
import type { ObservableRoundStore } from "./controller";

export class StoreAdapters {
  /** Größer als 0, solange der Abgleich selbst gerade in den Zustand schreibt. */
  private applying = 0;
  private readonly listeners = new Set<() => void>();

  readonly players: PlayerRepository;
  readonly evenings: EveningRepository;
  readonly settings: SettingsRepository;
  readonly editor: EveningEditor;

  /**
   * @param encodeEvening Schreibt einen Abend ins Dateiformat des Speichers; wirft, wenn er nicht hineinpasst. Damit
   *   wird jeder Abend geprüft, bevor er in den Zustand kommt.
   */
  constructor(
    private readonly store: AppStore,
    encodeEvening: (evening: Evening) => string,
  ) {
    let previous = store.getState();
    store.subscribe(() => {
      const state = store.getState();
      const changed =
        state.players !== previous.players ||
        state.evenings !== previous.evenings ||
        state.settings.defaultRules !== previous.settings.defaultRules;
      previous = state;
      if (changed && this.applying === 0) for (const listener of [...this.listeners]) listener();
    });

    // Der Zustand ändert sich sofort beim Aufruf (und meldet es sofort); gespeichert wird danach der Reihe nach.
    const apply = <T>(write: () => Promise<T>): Promise<T> => {
      this.applying++;
      try {
        return write();
      } finally {
        this.applying--;
      }
    };

    /** Lässt sich der Abend wegen seines Inhalts nicht speichern, bleibt der Zustand, wie er ist. */
    const storable = (evening: Evening): Evening => {
      try {
        encodeEvening(evening);
      } catch (error) {
        throw new EveningNotStorableError(`Der Abend ${evening.id} lässt sich nicht speichern.`, { cause: error });
      }
      return evening;
    };

    /** Ein unlesbarer Abend auf dem Gerät wird nie überschrieben; für den Abgleich lässt er sich nicht speichern. */
    const writable = (state: AppState, id: EveningId): void => {
      if (state.unreadableEvenings.includes(id)) {
        throw new EveningNotStorableError(`Der Abend ${id} lässt sich auf diesem Gerät nicht lesen und wird nicht überschrieben.`);
      }
    };

    /** Liest aus dem Zustand, sobald alles gespeichert ist – und gar nicht, wenn Spieler oder Einstellungen unlesbar waren. */
    const saved = <T>(read: (state: AppState) => T): Promise<T> => store.whenSaved(() => read(this.usable()));

    this.players = {
      players: () => saved((state) => state.players),
      save: (player) => apply(() => store.putPlayer(player)),
      delete: (id) => apply(() => store.removePlayer(id)),
    };
    this.evenings = {
      evenings: () => saved((state) => state.evenings),
      evening: (id) => saved((state) => state.evenings.find((it) => it.id === id) ?? null),
      save: async (evening) => {
        storable(evening);
        writable(store.getState(), evening.id);
        await apply(() => store.putEvening(evening));
      },
      delete: (id) => apply(() => store.removeEvening(id)),
    };
    this.settings = {
      settings: () => saved((state) => state.settings),
      update: (transform) => apply(() => store.putSettings(transform)),
    };
    this.editor = {
      // Lesen und Übernehmen in einem Zug, und erst, wenn alles gespeichert ist: Gleicht der Abgleich den Abend nicht
      // an, weil er schon stimmt, muss er auch so auf dem Gerät liegen.
      replace: (id, transform) =>
        store.whenSaved(() => {
          writable(this.usable(), id);
          return apply(() =>
            store.replaceEvening(id, (current) => {
              const changed = transform(current);
              return changed === null ? null : storable(changed);
            }),
          );
        }),
    };
  }

  /** Der Zustand, wenn er sich für den Abgleich lesen lässt; sonst {@link UnreadableDataError}. */
  private usable(): AppState {
    const state = this.store.getState();
    if (state.unreadableDocuments.length > 0) throw new UnreadableDataError();
    return state;
  }

  /**
   * Ob der Abgleich ruhen muss: Spielerliste oder Einstellungen ließen sich beim Laden nicht lesen. Was im Zustand
   * steht (keine Spieler, Standardwerte), ist dann nicht der echte Stand und sähe sonst aus wie hier gelöscht.
   */
  blocked(): boolean {
    return this.store.getState().unreadableDocuments.length > 0;
  }

  /**
   * Die Ablage der Runde, wie der Abgleich sie sehen soll: ohne die Basen der Abende, die sich auf diesem Gerät nicht
   * lesen lassen (siehe {@link ShieldedRoundStore}).
   */
  shield(inner: ObservableRoundStore): ObservableRoundStore {
    return new ShieldedRoundStore(inner, () => this.store.getState().unreadableEvenings);
  }

  /**
   * Meldet jede Änderung an Spielern, Abenden und dem Regelwerk für neue Abende, die nicht der Abgleich selbst
   * gemacht hat: Eingaben am Tisch, eine eingelesene Sicherung, geänderte Regeln.
   */
  onLocalChange(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
}

/**
 * Blendet die Basen der Abende aus, die sich auf diesem Gerät nicht lesen lassen. Ohne das sähe ein solcher Abend für
 * den Abgleich aus wie „hier gelöscht“ (Basis da, Abend weg), und er löschte ihn in der Runde. Die Basen bleiben
 * unberührt liegen und gelten wieder, sobald sich der Abend lesen lässt (zum Beispiel nach einem Update der App).
 */
export class ShieldedRoundStore implements ObservableRoundStore {
  constructor(
    private readonly inner: ObservableRoundStore,
    private readonly hidden: () => readonly EveningId[],
  ) {}

  subscribe(listener: () => void): () => void {
    return this.inner.subscribe(listener);
  }

  invalidate(notify = true): void {
    this.inner.invalidate?.(notify);
  }

  link(): Promise<RoundLink | null> {
    return this.inner.link();
  }

  setLink(link: RoundLink | null): Promise<void> {
    return this.inner.setLink(link);
  }

  async eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    const all = await this.inner.eveningBases();
    const hidden = this.hidden();
    if (hidden.length === 0) return all;
    return new Map([...all].filter(([id]) => !hidden.includes(id)));
  }

  async setEveningBase(id: EveningId, base: EveningBase | null): Promise<void> {
    // Der Abgleich fasst einen unlesbaren Abend nicht an; käme es doch dazu, bliebe die Basis, wie sie ist.
    if (this.hidden().includes(id)) return;
    await this.inner.setEveningBase(id, base);
  }

  playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    return this.inner.playerBases();
  }

  setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    return this.inner.setPlayerBase(id, base);
  }

  clearBases(): Promise<void> {
    return this.inner.clearBases();
  }
}
