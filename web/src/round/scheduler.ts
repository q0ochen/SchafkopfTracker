/*
 * Wann die Web-App mit der gemeinsamen Runde abgleicht (docs/ARCHITEKTUR.md, Abschnitt 8.6), wie die Schleife im
 * `RoundCoordinator` der Android-App: beim Öffnen, eine halbe Sekunde nach jeder Änderung auf dem Gerät (dann
 * vollständig), sonst alle vier Sekunden (nur nachsehen, solange hier nichts wartet); nach Fehlern mit wachsendem
 * Abstand. Es läuft nie mehr als ein Abgleich, und nur solange die App zu sehen ist.
 *
 * Geht die App in den Hintergrund und wartet noch etwas, wird einmal sofort abgeglichen, soweit der Browser das noch
 * zulässt (die Android-App übergibt dafür an den WorkManager). Danach ruht der Abgleich, bis die App wieder zu sehen ist.
 */
import type { SyncOutcome } from "../core/round/RoundSync";

/** Die Abstände des Abgleichs in Millisekunden. */
export interface SchedulerTiming {
  readonly poll: number;
  readonly debounce: number;
  readonly backoff: readonly number[];
}

export const DEFAULT_TIMING: SchedulerTiming = { poll: 4000, debounce: 500, backoff: [4000, 8000, 16_000, 32_000, 60_000] };

/** Was der Planer von der Runde braucht. */
export interface SchedulerHost {
  /** Ob es gerade etwas abzugleichen gibt (verbunden und angemeldet). */
  canSync(): boolean;

  /** So viele Änderungen warten nach dem letzten Abgleich noch. */
  pending(): number;

  /**
   * Ein Abgleich. Fehler fängt der Aufrufer selbst ab und meldet sie als Ergebnis.
   *
   * @param full `false`: nur nachsehen, ob der Server etwas Neues hat.
   */
  run(full: boolean): Promise<SyncOutcome>;
}

/** Ob ein Abgleich die Wartezeit zurücksetzt (alles außer „kein Netz“, „abgemeldet“ und Fehlern des Servers). */
function succeeded(outcome: SyncOutcome): boolean {
  return outcome.kind !== "Offline" && outcome.kind !== "SignedOut" && outcome.kind !== "Failed";
}

export class SyncScheduler {
  private started = false;
  private visible = false;
  private running = false;
  /** Während eines Abgleichs oder im Hintergrund kam ein Anstoß: gleich danach bzw. beim Wiederkommen noch einmal. */
  private nudged = false;
  /** Die App ging während eines Abgleichs in den Hintergrund, und es wartet noch etwas: danach einmal noch. */
  private flush = false;
  private failures = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  /** Zählt die Änderungen auf dem Gerät. Steht {@link cleanAt} auf demselben Wert, wartet nichts Ungeprüftes. */
  private changeCount = 0;
  private cleanAt = -1;
  /** „Jetzt abgleichen“ wurde getippt: Der nächste Abgleich, der beginnt, ist vollständig. */
  private forceFull = false;

  constructor(
    private readonly host: SchedulerHost,
    private readonly timing: SchedulerTiming = DEFAULT_TIMING,
  ) {}

  /** Beginnt zu arbeiten. `visible`: ob die App gerade zu sehen ist. */
  start(visible: boolean): void {
    if (this.started) return;
    this.started = true;
    this.visible = false;
    this.setVisible(visible);
  }

  /** Hört auf (für Tests und wenn die Datenbank nicht mehr benutzbar ist). */
  stop(): void {
    this.started = false;
    this.clearTimer();
    this.clearDebounce();
  }

  /** Die App ist zu sehen bzw. nicht mehr zu sehen. */
  setVisible(visible: boolean): void {
    if (!this.started) {
      this.visible = visible;
      return;
    }
    // Der Browser meldet dasselbe oft mehrfach (Sichtbarkeit und Verlassen der Seite): Nur ein Wechsel zählt.
    if (visible === this.visible) return;
    if (visible) {
      this.visible = true;
      this.flush = false;
      this.wake();
      return;
    }
    this.visible = false;
    this.clearTimer();
    const waiting = this.debounceTimer !== null || this.isDirty() || this.host.pending() > 0;
    this.clearDebounce();
    // Letzte Gelegenheit, bevor der Browser die Seite anhält.
    if (waiting && this.host.canSync()) {
      if (this.running) this.flush = true;
      else void this.cycle();
    }
  }

  /** Eine Änderung auf dem Gerät: nach einer kurzen Pause vollständig abgleichen; mehrere kurz hintereinander zählen als eine. */
  localChange(): void {
    this.changeCount++;
    if (!this.started) return;
    this.clearDebounce();
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = null;
      this.wake();
    }, this.timing.debounce);
  }

  /** „Jetzt abgleichen“: sofort und vollständig. */
  syncNow(): void {
    this.forceFull = true;
    this.wake();
  }

  /** Ein Anstoß: so bald wie möglich abgleichen (wieder Netz, neue Anmeldung, andere Runde). */
  wake(): void {
    if (!this.started) return;
    if (this.running || !this.visible) {
      this.nudged = true;
      return;
    }
    this.clearTimer();
    void this.cycle();
  }

  /** Ob auf dem Gerät etwas geändert wurde, das noch kein vollständiger Abgleich gesehen hat. */
  isDirty(): boolean {
    return this.changeCount !== this.cleanAt;
  }

  /** Der Zählerstand der Änderungen (vor einem Abgleich außerhalb der Schleife merken, danach an {@link applied} geben). */
  seen(): number {
    return this.changeCount;
  }

  /**
   * Das Ergebnis eines Abgleichs, auch eines, der außerhalb der Schleife lief (Verbinden, „Das bin ich“).
   *
   * @param seen Der Zählerstand der Änderungen vor dem Abgleich.
   */
  applied(outcome: SyncOutcome, seen: number): void {
    if (outcome.kind !== "Synced") return;
    // Sauber, wenn nichts mehr wartet – oder wenn sich nichts bewegt hat: Dann hilft erst eine Änderung.
    if (outcome.pending === 0 || (outcome.pulled === 0 && outcome.pushed === 0)) this.cleanAt = seen;
    // Es wartet noch etwas, und es hat sich etwas bewegt: beim nächsten Mal wieder vollständig. (In der Android-App
    // ergibt sich das von selbst, weil dort auch die Änderungen des Abgleichs als Änderungen zählen.)
    else this.cleanAt = -1;
  }

  private async cycle(): Promise<void> {
    this.running = true;
    this.nudged = false;
    try {
      if (!this.host.canSync()) {
        this.failures = 0;
      } else {
        const seen = this.changeCount;
        const full = this.forceFull || this.isDirty();
        this.forceFull = false;
        let outcome: SyncOutcome;
        try {
          outcome = await this.host.run(full);
        } catch {
          // Der Aufrufer fängt Fehler selbst ab; hier nur zur Sicherheit, damit die Schleife nie stehen bleibt.
          outcome = { kind: "Failed", error: "SERVER" };
        }
        this.applied(outcome, seen);
        this.failures = succeeded(outcome) ? 0 : this.failures + 1;
      }
    } finally {
      this.running = false;
    }
    if (!this.started) return;
    if (!this.visible) {
      if (this.flush) {
        this.flush = false;
        void this.cycle();
      }
      return;
    }
    if (this.nudged) {
      void this.cycle();
      return;
    }
    const wait = this.failures === 0 ? this.timing.poll : this.timing.backoff[Math.min(this.failures, this.timing.backoff.length) - 1];
    this.timer = setTimeout(() => {
      this.timer = null;
      // Die Wartezeit ist abgelaufen, aber die App ist inzwischen nicht mehr zu sehen: weiter ruhen.
      if (this.started && this.visible && !this.running) void this.cycle();
    }, wait);
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  private clearDebounce(): void {
    if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
    this.debounceTimer = null;
  }
}
