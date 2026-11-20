/*
 * Lässt mehrere nachgestellte Handys zufällig, aber bei jedem Lauf gleich, eintragen, ändern, löschen und abgleichen –
 * mit Funklöchern und verlorenen Antworten. Am Ende müssen alle Handys und der Server dasselbe haben, und nichts, was
 * niemand gelöscht hat, darf fehlen. Port von `RoundSimulationTest.kt`; der Zufall kommt aus einem eigenen kleinen
 * Generator mit festem Startwert (die Zahlenfolge ist eine andere als in Kotlin, die Prüfungen sind dieselben).
 */
import { describe, expect, it } from "vitest";
import { involves, remove, replace } from "../evening/Evening";
import type { EntryId, EveningId, GameEntry } from "../model";
import { nextColorIndex } from "../players";
import { RuleSets } from "../rules/RuleSet";
import { InMemoryRoundServer } from "./InMemoryRound";
import { toPlayer } from "./RoundModels";
import { Device, PLACEHOLDERS, player, TestClock, TestRandom } from "./testing/fixtures";

const STEPS = 160;
const MAX_ROUNDS = 12;

/** Was in allen Läufen zusammen vorgekommen ist. */
class Seen {
  /** Abgleiche, die zugleich etwas geholt und etwas hochgeladen haben. */
  merges = 0;

  /** Abende, die neu gelesen werden mussten, weil jemand anders schneller war. */
  rereads = 0;
  offline = 0;
  lostAnswers = 0;
  notices = 0;

  /** Gelöschte Einträge, die niemand geändert hatte und die am Ende wirklich fehlen. */
  deletedForGood = 0;
}

function sameNameIgnoringCase(a: string, b: string): boolean {
  return a.toLowerCase() === b.toLowerCase();
}

class Simulation {
  private readonly random: TestRandom;
  private readonly clock = new TestClock();
  private readonly server = new InMemoryRoundServer(() => this.clock.millis);
  private readonly devices: Device[];

  /** Alle je angelegten Einträge mit ihrem Abend. */
  private readonly created = new Map<EntryId, EveningId>();

  /** Einträge, die irgendwer gelöscht hat: Sie dürfen am Ende fehlen (oder nach einem Konflikt noch da sein). */
  private readonly deletedEntries = new Set<EntryId>();

  /** Einträge, die irgendwer geändert hat: Eine Änderung gewinnt gegen das Löschen auf einem anderen Handy. */
  private readonly editedEntries = new Set<EntryId>();
  private readonly deletedEvenings = new Set<EveningId>();
  private counter = 0;

  constructor(
    private readonly seed: number,
    devices: number,
    private readonly atTheTable: boolean,
    private readonly seen: Seen,
    private readonly lossy = true,
  ) {
    this.random = new TestRandom(seed);
    this.devices = Array.from({ length: devices }, (_, index) => new Device(this.server, `konto-${index + 1}`, this.clock));
  }

  async run(): Promise<void> {
    await this.setup();
    for (let step = 0; step < STEPS; step++) {
      this.clock.advance(this.random.nextInt(1, 30));
      await this.step(this.random.pick(this.devices));
    }
    await this.settle();
    await this.check();
    for (const device of this.devices) this.seen.rereads += device.session.calls.filter((it) => it === "evening").length;
  }

  private fail(message: string): never {
    throw new Error(`Lauf ${this.seed}: ${message}`);
  }

  private async sync(device: Device, quick = false): Promise<void> {
    const lost = device.session.loseNextAnswer;
    const outcome = await device.sync.sync(quick);
    switch (outcome.kind) {
      case "Synced":
        if (outcome.pulled > 0 && outcome.pushed > 0) this.seen.merges++;
        this.seen.notices += outcome.notices.length;
        break;
      case "Offline":
        if (lost && !device.session.loseNextAnswer) this.seen.lostAnswers++;
        else this.seen.offline++;
        break;
      case "Unchanged":
        break;
      default:
        this.fail(`${device.userId}: ${JSON.stringify(outcome)}`);
    }
  }

  /** Genau bevor `device` das nächste Mal einen Abend hochlädt, trägt ein anderes Handy etwas ein und gleicht ab. */
  private interleave(device: Device): void {
    const other = this.random.pick(this.devices.filter((it) => it !== device));
    device.api.beforeSave = async () => {
      this.clock.advance(1);
      const dice = this.random.nextInt(4);
      if (dice === 0) await this.editEntry(other);
      else if (dice === 1) await this.deleteEntry(other);
      else await this.addGame(other);
      // Das andere Handy gleicht hier ohne eigene Unterbrechung ab: Sonst riefe es am Ende das wartende Handy.
      other.api.beforeSave = null;
      await this.sync(other);
    };
  }

  private async setup(): Promise<void> {
    const first = this.devices[0];
    await first.firstStart();
    await first.startEvening("abend-0");
    await first.connection.create("Stammtisch", first.userId, PLACEHOLDERS);
    const round = await first.roundId();
    for (const device of this.devices.slice(1)) await device.connection.join(await first.api.createInvite(round), device.userId);
    // Jedes Konto sagt „Das bin ich“ und legt damit seinen Namen fest.
    for (const [index, device] of this.devices.entries()) {
      await device.synced();
      await device.connection.claim(PLACEHOLDERS[index].id, `Konto ${index + 1}`);
    }
    for (const device of this.devices) await device.synced();
  }

  private async step(device: Device): Promise<void> {
    const dice = this.random.nextInt(100);
    if (this.atTheTable) {
      if (dice < 45) await this.addGame(device);
      else if (dice < 55) await this.editEntry(device);
      else if (dice < 62) await this.deleteEntry(device);
      else if (dice < 64) await this.finishEvening(device);
      else if (dice < 68) this.interleave(device);
      else if (dice < 90) await this.sync(device, this.random.nextInt(4) === 0);
      else if (dice < 96) device.session.offline = !device.session.offline;
      else device.session.loseNextAnswer = this.lossy;
    } else {
      if (dice < 22) await this.addGame(device);
      else if (dice < 28) await this.startEvening(device);
      else if (dice < 34) await this.editEntry(device);
      else if (dice < 39) await this.deleteEntry(device);
      else if (dice < 42) await this.finishEvening(device);
      else if (dice < 45) await this.deleteEvening(device);
      else if (dice < 51) await this.addGuest(device);
      else if (dice < 56) await this.changePlayer(device);
      else if (dice < 60) await this.deletePlayer(device);
      else if (dice < 63) await this.changeRules(device);
      else if (dice < 66) this.interleave(device);
      else if (dice < 89) await this.sync(device, this.random.nextInt(4) === 0);
      else if (dice < 95) device.session.offline = !device.session.offline;
      else device.session.loseNextAnswer = true;
    }
  }

  private async addGame(device: Device): Promise<void> {
    const evening = this.random.pickOrNull(await device.allEvenings());
    if (evening === null) return;
    const won = this.random.nextBoolean();
    const laufende = this.random.nextInt(0, 4);
    this.created.set(await device.addGame(evening.id, won, laufende), evening.id);
  }

  private async startEvening(device: Device): Promise<void> {
    const active = (await device.allPlayers()).filter((it) => !it.archived);
    const seats = this.random
      .shuffled(active)
      .slice(0, 4)
      .map((it) => it.id);
    if (seats.length === 4) await device.startEvening(`abend-${++this.counter}`, seats);
  }

  private async editEntry(device: Device): Promise<void> {
    const evening = this.random.pickOrNull(await device.allEvenings());
    if (evening === null) return;
    const entry = this.random.pickOrNull(evening.entries.filter((it): it is GameEntry => it.kind === "game"));
    if (entry === null) return;
    const laufende = this.random.nextInt(4, 9);
    const declarerWon = this.random.nextBoolean();
    await device.editor.update(evening.id, (it) => replace(it, { ...entry, laufende, declarerWon }));
    this.editedEntries.add(entry.id);
  }

  private async deleteEntry(device: Device): Promise<void> {
    const evening = this.random.pickOrNull(await device.allEvenings());
    if (evening === null) return;
    const entry = this.random.pickOrNull(evening.entries);
    if (entry === null) return;
    await device.editor.update(evening.id, (it) => remove(it, entry.id));
    this.deletedEntries.add(entry.id);
  }

  private async finishEvening(device: Device): Promise<void> {
    const evening = this.random.pickOrNull(await device.allEvenings());
    if (evening === null) return;
    const finish = this.random.nextBoolean();
    const location = `Wirtshaus ${this.random.nextInt(3)}`;
    await device.editor.update(evening.id, (it) =>
      finish ? { ...it, finishedAt: it.finishedAt === null ? this.clock.now() : null } : { ...it, location },
    );
  }

  private async deleteEvening(device: Device): Promise<void> {
    const evening = this.random.pickOrNull(await device.allEvenings());
    if (evening === null) return;
    await device.evenings.delete(evening.id);
    this.deletedEvenings.add(evening.id);
  }

  private async addGuest(device: Device): Promise<void> {
    const players = await device.allPlayers();
    // Wenige Namen, damit sich zwei Handys auch einmal denselben aussuchen.
    const name = `Gast ${this.random.nextInt(6)}`;
    if (players.some((it) => sameNameIgnoringCase(it.name, name))) return;
    await device.players.save(player(`gast-${++this.counter}`, name, nextColorIndex(players)));
  }

  private async changePlayer(device: Device): Promise<void> {
    const players = await device.allPlayers();
    const chosen = this.random.pickOrNull(players);
    if (chosen === null) return;
    const dice = this.random.nextInt(3);
    // Auch Namen, die fest oder schon vergeben sind: Der Server lehnt sie ab.
    const changed =
      dice === 0
        ? { ...chosen, name: `Name ${this.random.nextInt(8)}` }
        : dice === 1
          ? { ...chosen, colorIndex: this.random.nextInt(0, 40) }
          : { ...chosen, archived: !chosen.archived };
    if (!players.some((it) => it.id !== chosen.id && sameNameIgnoringCase(it.name, changed.name))) await device.players.save(changed);
  }

  private async deletePlayer(device: Device): Promise<void> {
    const evenings = await device.allEvenings();
    const unused = (await device.allPlayers()).filter((it) => !evenings.some((evening) => involves(evening, it.id)));
    const chosen = this.random.pickOrNull(unused);
    if (chosen !== null) await device.players.delete(chosen.id);
  }

  private async changeRules(device: Device): Promise<void> {
    const rules = this.random.nextBoolean() ? RuleSets.sauspiel() : { ...RuleSets.standard(), name: `Runde ${this.random.nextInt(3)}` };
    await device.settings.update((it) => ({ ...it, defaultRules: rules }));
  }

  /** Alle wieder am Netz: abgleichen, bis sich nichts mehr bewegt. */
  private async settle(): Promise<void> {
    for (const device of this.devices) {
      device.session.offline = false;
      device.session.loseNextAnswer = false;
      device.api.beforeSave = null;
    }
    for (let round = 0; round < MAX_ROUNDS; round++) {
      let moving = false;
      for (const device of this.devices) {
        const outcome = await device.synced();
        if (outcome.pulled > 0 || outcome.pushed > 0 || outcome.pending > 0) moving = true;
      }
      if (!moving) return;
    }
    this.fail("Der Abgleich kommt nicht zur Ruhe.");
  }

  private async check(): Promise<void> {
    const round = await this.devices[0].roundId();
    const onServer = [...this.server.evenings(round)].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    const playersOnServer = this.server.players(round).map(toPlayer);
    const asSet = (players: readonly unknown[]): Set<string> => new Set(players.map((it) => JSON.stringify(it)));
    const rules = (await this.devices[0].api.head(round))?.rules ?? null;
    for (const device of this.devices) {
      const where = `Lauf ${this.seed}, ${device.userId}`;
      expect(await device.allEvenings(), `${where}: Abende`).toEqual(onServer);
      expect(asSet(await device.allPlayers()), `${where}: Spieler`).toEqual(asSet(playersOnServer));
      expect((await device.settings.settings()).defaultRules, `${where}: Regelwerk`).toEqual(rules);
      expect(await device.sync.pendingCount(), `${where}: Wartendes`).toBe(0);
      expect((await device.link())?.cursor, `${where}: Stand`).toBe(this.server.rev(round));
    }
    const present = new Set<EntryId>();
    for (const evening of onServer) {
      const ids = evening.entries.map((it) => it.id);
      expect(new Set(ids).size, `Lauf ${this.seed}: doppelte Einträge in ${evening.id}`).toBe(ids.length);
      for (const id of ids) present.add(id);
      // Kein Abend nennt jemanden, den es in der Runde nicht gibt.
      const missing = [...evening.seating, evening.firstDealer].filter((id) => !playersOnServer.some((it) => it.id === id));
      expect(missing, `Lauf ${this.seed}: ${evening.id} nennt unbekannte Spieler`).toEqual([]);
    }
    // Nichts geht verloren, was niemand gelöscht hat.
    const lost = [...this.created].filter(
      ([entry, evening]) => !this.deletedEntries.has(entry) && !this.deletedEvenings.has(evening) && !present.has(entry),
    );
    expect(lost, `Lauf ${this.seed}: verlorene Einträge`).toEqual([]);
    if (!this.lossy) {
      // Ohne Änderung von anderer Seite bleibt Gelöschtes gelöscht.
      const gone = [...this.deletedEntries].filter((it) => !this.editedEntries.has(it));
      expect(
        gone.filter((it) => present.has(it)),
        `Lauf ${this.seed}: wieder aufgetauchte Einträge`,
      ).toEqual([]);
      this.seen.deletedForGood += gone.length;
    }
    // Namen sind in der Runde eindeutig.
    const names = playersOnServer.map((it) => it.name.toLowerCase());
    expect(new Set(names).size, `Lauf ${this.seed}: doppelte Namen`).toBe(names.length);
  }
}

describe("Zufallsläufe mit mehreren Handys", () => {
  it("vier Handys am Tisch tragen durcheinander in denselben Abend ein", async () => {
    const seen = new Seen();
    for (let seed = 0; seed < 60; seed++) await new Simulation(seed, 4, true, seen).run();

    // Der Test taugt nur, wenn die schwierigen Fälle auch wirklich vorkommen.
    expect(seen.merges, "zusammengeführt").toBeGreaterThan(300);
    expect(seen.rereads, "jemand war schneller").toBeGreaterThan(30);
    expect(seen.offline, "ohne Netz").toBeGreaterThan(100);
    expect(seen.lostAnswers, "verlorene Antworten").toBeGreaterThan(20);
  });

  it("was am Tisch gelöscht und von niemandem geändert wurde, bleibt gelöscht", async () => {
    // Ohne verlorene Antworten: Nur dann weiß jedes Handy sicher, was schon auf dem Server war.
    const seen = new Seen();
    for (let seed = 0; seed < 60; seed++) await new Simulation(500 + seed, 4, true, seen, false).run();

    expect(seen.rereads, "jemand war schneller").toBeGreaterThan(30);
    expect(seen.deletedForGood, "gelöscht geblieben").toBeGreaterThan(300);
  });

  it("drei Handys ändern über Wochen Abende, Spieler und Regeln", async () => {
    const seen = new Seen();
    for (let seed = 0; seed < 60; seed++) await new Simulation(1000 + seed, 3, false, seen).run();

    expect(seen.merges, "zusammengeführt").toBeGreaterThan(100);
    expect(seen.notices, "abgelehnte Spieler").toBeGreaterThan(30);
    expect(seen.rereads, "jemand war schneller").toBeGreaterThan(10);
    expect(seen.offline, "ohne Netz").toBeGreaterThan(100);
  });
});
