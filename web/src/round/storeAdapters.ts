/*
 * Verbindet den Abgleich der gemeinsamen Runde mit dem Zustand der Web-App: Spieler, Abende und Einstellungen liest
 * und schreibt er über `AppStore`. Das Zusammenführen eines Abends läuft dabei in einem Zug mit dem Zustand
 * (`AppStore.replaceEvening`), genau wie jede Eingabe am Tisch (`AppStore.updateEvening`): So überschreibt keines
 * das andere.
 *
 * Alle Schreibaufrufe scheitern, wenn sich die Änderung nicht im Speicher des Browsers ablegen ließ. Der Abgleich
 * bricht dann ab und merkt sich nichts, was neuer wäre als der Stand auf dem Gerät. Aus demselben Grund holt jedes
 * Lesen der Listen zuerst nach, was sich vorher nicht speichern ließ (`AppStore.settle`).
 */
import type { AppStore } from "../app/store";
import type { Evening } from "../core/model";
import {
  EveningNotStorableError,
  type EveningEditor,
  type EveningRepository,
  type PlayerRepository,
  type SettingsRepository,
} from "../core/round/RoundPorts";

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
  constructor(store: AppStore, encodeEvening: (evening: Evening) => string) {
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

    this.players = {
      players: async () => {
        await store.settle();
        return store.getState().players;
      },
      save: (player) => apply(() => store.putPlayer(player)),
      delete: (id) => apply(() => store.removePlayer(id)),
    };
    this.evenings = {
      evenings: async () => {
        await store.settle();
        return store.getState().evenings;
      },
      evening: async (id) => store.evening(id) ?? null,
      save: async (evening) => {
        storable(evening);
        await apply(() => store.putEvening(evening));
      },
      delete: (id) => apply(() => store.removeEvening(id)),
    };
    this.settings = {
      settings: async () => store.getState().settings,
      update: (transform) => apply(() => store.putSettings(transform)),
    };
    this.editor = {
      replace: async (id, transform) =>
        apply(() =>
          store.replaceEvening(id, (current) => {
            const changed = transform(current);
            return changed === null ? null : storable(changed);
          }),
        ),
    };
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
