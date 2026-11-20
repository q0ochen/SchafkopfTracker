/* Wann abgeglichen wird (docs/ARCHITEKTUR.md, Abschnitt 8.6): mit gestellter Uhr und einem nachgestellten Abgleich. */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SyncOutcome } from "../core/round/RoundSync";
import { DEFAULT_TIMING, SyncScheduler, type SchedulerHost } from "./scheduler";

/** Ein nachgestellter Abgleich: merkt sich jeden Lauf und antwortet, was der Test vorgibt. */
class FakeHost implements SchedulerHost {
  linked = true;
  waiting = 0;
  /**
   * Die Antworten der nächsten Läufe; ist die Liste leer, gilt {@link fallback} oder, wie beim echten Abgleich,
   * „nichts zu tun“ (vollständig) bzw. „nichts Neues“ (kurz).
   */
  readonly outcomes: SyncOutcome[] = [];
  fallback: SyncOutcome | null = null;
  /** Jeder Lauf: `true` für vollständig. */
  readonly runs: boolean[] = [];
  /** So lange dauert ein Lauf (Millisekunden der gestellten Uhr). */
  duration = 0;
  active = 0;
  mostActive = 0;

  canSync(): boolean {
    return this.linked;
  }

  pending(): number {
    return this.waiting;
  }

  async run(full: boolean): Promise<SyncOutcome> {
    this.runs.push(full);
    this.active++;
    this.mostActive = Math.max(this.mostActive, this.active);
    if (this.duration > 0) await new Promise((resolve) => setTimeout(resolve, this.duration));
    this.active--;
    return this.outcomes.shift() ?? this.fallback ?? (full ? SyncOutcome.synced({ pulled: 0, pushed: 0, pending: 0 }) : SyncOutcome.Unchanged);
  }
}

const synced = SyncOutcome.synced;
let host: FakeHost;
let scheduler: SyncScheduler;

/** Lässt die gestellte Uhr laufen und alles Angestoßene fertig werden. */
async function pass(millis: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(millis);
}

beforeEach(() => {
  vi.useFakeTimers();
  host = new FakeHost();
  scheduler = new SyncScheduler(host);
});

afterEach(() => {
  scheduler.stop();
  vi.useRealTimers();
});

describe("Abstände", () => {
  it("die vorgegebenen Abstände sind die des Vertrags", () => {
    expect(DEFAULT_TIMING).toEqual({ poll: 4000, debounce: 500, backoff: [4000, 8000, 16_000, 32_000, 60_000] });
  });

  it("gleicht beim Öffnen sofort und vollständig ab, danach alle vier Sekunden nur noch kurz", async () => {
    host.outcomes.push(synced({ pulled: 2, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(0);
    expect(host.runs).toEqual([true]);

    await pass(3999);
    expect(host.runs).toEqual([true]);
    await pass(1);
    expect(host.runs).toEqual([true, false]);
    await pass(8000);
    expect(host.runs).toEqual([true, false, false, false]);
  });

  it("eine halbe Sekunde nach einer Änderung wird vollständig abgeglichen; mehrere kurz hintereinander zählen als eine", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(1000);
    expect(host.runs).toEqual([true]);

    scheduler.localChange();
    await pass(300);
    scheduler.localChange();
    await pass(499);
    expect(host.runs).toEqual([true]);
    host.outcomes.push(synced({ pulled: 0, pushed: 1, pending: 0 }));
    await pass(1);
    expect(host.runs).toEqual([true, true]);
    // Danach wieder nur kurz, vier Sekunden nach dem letzten Abgleich.
    await pass(4000);
    expect(host.runs).toEqual([true, true, false]);
  });

  it("nach Fehlern wächst der Abstand: 4, 8, 16, 32, dann 60 Sekunden", async () => {
    host.fallback = SyncOutcome.Offline;
    scheduler.start(true);
    await pass(0);
    const waits: number[] = [];
    for (let attempt = 0; attempt < 7; attempt++) {
      const before = host.runs.length;
      let waited = 0;
      while (host.runs.length === before) {
        await pass(1000);
        waited += 1000;
      }
      waits.push(waited);
    }
    expect(waits).toEqual([4000, 8000, 16_000, 32_000, 60_000, 60_000, 60_000]);

    // Klappt es wieder, gilt wieder der normale Abstand.
    host.fallback = null;
    await pass(60_000);
    const before = host.runs.length;
    await pass(4000);
    expect(host.runs.length).toBe(before + 1);
  });

  it.each([SyncOutcome.SignedOut, SyncOutcome.failed("SERVER")])("auch %o zählt als Fehler", async (outcome) => {
    host.fallback = outcome;
    scheduler.start(true);
    await pass(0);
    await pass(4000);
    expect(host.runs).toHaveLength(2);
    await pass(4000);
    expect(host.runs).toHaveLength(2);
    await pass(4000);
    expect(host.runs).toHaveLength(3);
  });

  it("ein Anstoß wartet nicht auf den Abstand, auch nicht nach Fehlern", async () => {
    host.fallback = SyncOutcome.Offline;
    scheduler.start(true);
    await pass(0);
    await pass(4000);
    await pass(8000);
    expect(host.runs).toHaveLength(3);

    scheduler.wake();
    await pass(0);
    expect(host.runs).toHaveLength(4);
    scheduler.localChange();
    await pass(500);
    expect(host.runs).toHaveLength(5);
  });
});

describe("vollständig oder kurz", () => {
  it("solange noch etwas wartet und sich etwas bewegt, bleibt es beim vollständigen Abgleich", async () => {
    scheduler.start(true);
    await pass(0);
    scheduler.localChange();
    host.outcomes.push(synced({ pulled: 0, pushed: 1, pending: 1 }), synced({ pulled: 0, pushed: 1, pending: 0 }));
    await pass(500);
    await pass(4000);
    await pass(4000);
    expect(host.runs).toEqual([true, true, true, false]);
  });

  it("wartet etwas, das sich nicht bewegt (der Server nimmt es nicht an), wird nur noch kurz nachgesehen", async () => {
    scheduler.start(true);
    await pass(0);
    scheduler.localChange();
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 1, refused: 1 }));
    await pass(500);
    await pass(8000);
    expect(host.runs).toEqual([true, true, false, false]);
  });

  it("holt ein Abgleich etwas und bleibt danach etwas liegen, folgt ein vollständiger – auch ohne Änderung von hier", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(0);
    // Kurz nachgesehen, der Server hatte Neues, und das Zusammengeführte ließ sich noch nicht hochladen.
    host.outcomes.push(synced({ pulled: 1, pushed: 0, pending: 1 }), synced({ pulled: 0, pushed: 1, pending: 0 }));
    await pass(4000);
    await pass(4000);
    await pass(4000);
    expect(host.runs).toEqual([true, false, true, false]);
  });

  it("eine Änderung während eines Abgleichs gilt danach nicht als erledigt", async () => {
    host.duration = 1000;
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.localChange();
    host.outcomes.push(synced({ pulled: 0, pushed: 1, pending: 0 }));
    // Nach der halben Sekunde läuft der erste Abgleich noch: Der nächste folgt gleich danach und ist vollständig.
    await pass(2000);
    expect(host.runs).toEqual([true, true]);
    expect(host.mostActive).toBe(1);
  });

  it("„Jetzt abgleichen“ gleicht sofort und vollständig ab", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.syncNow();
    await pass(0);
    expect(host.runs).toEqual([true, true]);
  });

  it("ein Abgleich außerhalb der Schleife (Verbinden) zählt mit", async () => {
    host.linked = false;
    scheduler.start(true);
    await pass(0);
    expect(host.runs).toEqual([]);

    const seen = scheduler.seen();
    scheduler.applied(synced({ pulled: 3, pushed: 0, pending: 0 }), seen);
    host.linked = true;
    scheduler.wake();
    await pass(0);
    expect(host.runs).toEqual([false]);
    expect(scheduler.isDirty()).toBe(false);
  });
});

describe("nie zwei gleichzeitig", () => {
  it("Anstöße während eines Abgleichs führen zu genau einem weiteren danach", async () => {
    host.duration = 3000;
    scheduler.start(true);
    await pass(100);
    scheduler.wake();
    scheduler.syncNow();
    scheduler.wake();
    await pass(10_000);
    expect(host.mostActive).toBe(1);
    // Der erste, der angestoßene und dann der nächste nach vier Sekunden.
    expect(host.runs).toEqual([true, true, false]);
  });

  it("dauert ein Abgleich länger als der Abstand, startet trotzdem kein zweiter", async () => {
    host.duration = 9000;
    scheduler.start(true);
    await pass(30_000);
    expect(host.mostActive).toBe(1);
    // Vier Sekunden Pause nach jedem Ende: 9 + 4 + 9 + 4 + 9 …
    expect(host.runs).toHaveLength(3);
  });
});

describe("Vordergrund und Hintergrund", () => {
  it("im Hintergrund ruht der Abgleich und läuft beim Wiederkommen sofort", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(0);
    scheduler.setVisible(false);
    await pass(60_000);
    expect(host.runs).toEqual([true]);

    scheduler.setVisible(true);
    await pass(0);
    expect(host.runs).toEqual([true, false]);
    await pass(4000);
    expect(host.runs).toEqual([true, false, false]);
  });

  it("startet im Hintergrund nicht, bis die App zu sehen ist", async () => {
    scheduler.start(false);
    scheduler.wake();
    scheduler.localChange();
    await pass(10_000);
    expect(host.runs).toEqual([]);
    scheduler.setVisible(true);
    await pass(0);
    expect(host.runs).toEqual([true]);
  });

  it("wartet beim Verlassen noch etwas, wird einmal sofort abgeglichen und dann geruht", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    // Gerade erst eingetragen: Die halbe Sekunde ist noch nicht um.
    scheduler.localChange();
    host.outcomes.push(synced({ pulled: 0, pushed: 1, pending: 0 }));
    scheduler.setVisible(false);
    await pass(0);
    expect(host.runs).toEqual([true, true]);
    await pass(60_000);
    expect(host.runs).toEqual([true, true]);
  });

  it("wartet beim Verlassen nichts, passiert nichts", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.setVisible(false);
    await pass(60_000);
    expect(host.runs).toEqual([true]);
  });

  it("läuft beim Verlassen gerade ein Abgleich und wartet noch etwas, folgt genau einer", async () => {
    host.duration = 1000;
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.localChange();
    scheduler.setVisible(false);
    await pass(60_000);
    expect(host.runs).toEqual([true, true]);
    expect(host.mostActive).toBe(1);
  });

  it("meldet der Browser dasselbe mehrfach, zählt nur der Wechsel", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.setVisible(true);
    scheduler.setVisible(true);
    await pass(0);
    expect(host.runs).toEqual([true]);

    scheduler.localChange();
    scheduler.setVisible(false);
    scheduler.setVisible(false);
    await pass(0);
    expect(host.runs).toEqual([true, true]);
    scheduler.setVisible(true);
    scheduler.setVisible(true);
    await pass(0);
    expect(host.runs).toEqual([true, true, false]);
  });

  it("ein Anstoß im Hintergrund wird beim Wiederkommen nachgeholt", async () => {
    host.outcomes.push(synced({ pulled: 0, pushed: 0, pending: 0 }));
    scheduler.start(true);
    await pass(100);
    scheduler.setVisible(false);
    scheduler.wake();
    await pass(10_000);
    expect(host.runs).toEqual([true]);
    scheduler.setVisible(true);
    await pass(0);
    expect(host.runs).toEqual([true, false]);
  });
});

describe("ohne Runde oder Anmeldung", () => {
  it("gibt es nichts abzugleichen, läuft nichts; die Schleife bleibt aber wach", async () => {
    host.linked = false;
    scheduler.start(true);
    await pass(20_000);
    expect(host.runs).toEqual([]);

    host.linked = true;
    await pass(4000);
    expect(host.runs).toEqual([true]);
  });

  it("nach dem Anhalten läuft nichts mehr", async () => {
    scheduler.start(true);
    await pass(0);
    scheduler.stop();
    scheduler.wake();
    scheduler.localChange();
    await pass(60_000);
    expect(host.runs).toEqual([true]);
  });

  it("wirft der Abgleich doch einmal, bleibt die Schleife nicht stehen", async () => {
    const run = host.run.bind(host);
    let broken = true;
    host.run = async (full) => {
      if (broken) {
        host.runs.push(full);
        throw new Error("Datenbank");
      }
      return run(full);
    };
    scheduler.start(true);
    await pass(0);
    broken = false;
    await pass(4000);
    expect(host.runs).toEqual([true, true]);
  });
});
