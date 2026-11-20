/*
 * Die Daten eines Geräts ganz im Speicher: Spieler, Abende, Einstellungen und der Bearbeiter der Abende. Sie dienen
 * den Tests der gemeinsamen Runde (mehrere nachgestellte Handys) und entsprechen `domain/ports/InMemoryRepositories.kt`
 * und `domain/evening/EveningEditor.kt` des Kotlin-Kerns. Die Web-App selbst benutzt stattdessen ihren Zustand
 * (`app/store.ts`, angebunden in `round/storeAdapters.ts`).
 */
import { compareInstants } from "../instant";
import type { AppSettings, EntryId, Evening, EveningId, InstantText, Player, PlayerId } from "../model";
import { RuleSets } from "../rules/RuleSet";
import { sameEveningOrNull } from "./equality";
import type { EveningEditor, EveningRepository, IdGenerator, PlayerRepository, SettingsRepository } from "./RoundPorts";

export class InMemoryPlayerRepository implements PlayerRepository {
  private list: readonly Player[];

  constructor(initial: readonly Player[] = []) {
    this.list = [...initial];
  }

  async players(): Promise<readonly Player[]> {
    return this.list;
  }

  async save(player: Player): Promise<void> {
    this.list = this.list.some((it) => it.id === player.id)
      ? this.list.map((it) => (it.id === player.id ? player : it))
      : [...this.list, player];
  }

  async delete(id: PlayerId): Promise<void> {
    this.list = this.list.filter((it) => it.id !== id);
  }
}

export class InMemoryEveningRepository implements EveningRepository {
  private list: readonly Evening[];

  constructor(initial: readonly Evening[] = []) {
    this.list = [...initial];
  }

  /** Alle Abende, der neueste zuerst; bei gleichem Beginn in der Reihenfolge des Anlegens. */
  async evenings(): Promise<readonly Evening[]> {
    return [...this.list].sort((a, b) => compareInstants(b.startedAt, a.startedAt));
  }

  async evening(id: EveningId): Promise<Evening | null> {
    return this.list.find((it) => it.id === id) ?? null;
  }

  async save(evening: Evening): Promise<void> {
    this.list = this.list.some((it) => it.id === evening.id)
      ? this.list.map((it) => (it.id === evening.id ? evening : it))
      : [...this.list, evening];
  }

  async delete(id: EveningId): Promise<void> {
    this.list = this.list.filter((it) => it.id !== id);
  }
}

export class InMemorySettingsRepository implements SettingsRepository {
  private current: AppSettings;

  constructor(initial: AppSettings = { defaultRules: RuleSets.standard(), keepScreenOn: true, themeMode: "DARK" }) {
    this.current = initial;
  }

  async settings(): Promise<AppSettings> {
    return this.current;
  }

  async update(transform: (settings: AppSettings) => AppSettings): Promise<void> {
    this.current = transform(this.current);
  }
}

/** Die Uhr eines Geräts. */
export interface InstantClock {
  now(): InstantText;
}

/**
 * Ändert einen Abend und speichert ihn sofort (Kotlin: `EveningEditor`). Alle Änderungen laufen nacheinander, damit
 * keine Eingabe eine andere überschreibt.
 */
export class InMemoryEveningEditor implements EveningEditor {
  private tail: Promise<void> = Promise.resolve();

  constructor(
    private readonly evenings: EveningRepository,
    private readonly clock: InstantClock,
    private readonly idGenerator: IdGenerator,
  ) {}

  /** Neue Kennung für einen Eintrag. */
  newEntryId(): EntryId {
    return this.idGenerator.newId();
  }

  /** Die aktuelle Zeit laut injizierter Uhr. */
  now(): InstantText {
    return this.clock.now();
  }

  private locked<T>(block: () => Promise<T>): Promise<T> {
    const run = this.tail.then(block);
    this.tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  /**
   * Liest den aktuellen Stand des Abends, wendet `transform` an und speichert das Ergebnis.
   *
   * @returns der gespeicherte Abend oder `null`, wenn es ihn nicht (mehr) gibt.
   */
  update(id: EveningId, transform: (evening: Evening) => Evening): Promise<Evening | null> {
    return this.locked(async () => {
      const current = await this.evenings.evening(id);
      if (current === null) return null;
      const changed = transform(current);
      if (!sameEveningOrNull(changed, current)) await this.evenings.save(changed);
      return changed;
    });
  }

  replace(id: EveningId, transform: (current: Evening | null) => Evening | null): Promise<Evening | null> {
    return this.locked(async () => {
      const current = await this.evenings.evening(id);
      const changed = transform(current);
      if (changed === null) {
        if (current !== null) await this.evenings.delete(id);
      } else if (!sameEveningOrNull(changed, current)) {
        await this.evenings.save(changed);
      }
      return changed;
    });
  }
}
