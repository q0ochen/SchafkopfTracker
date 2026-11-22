/*
 * Gleicht dieses Gerät mit der gemeinsamen Runde ab (docs/ARCHITEKTUR.md, Abschnitt 8.6): holt, was andere geändert
 * haben, führt es mit dem Stand von hier zusammen und lädt hoch, was hier neu ist. Das Gerät bleibt dabei jederzeit
 * vollständig benutzbar; scheitert etwas, wartet die Änderung einfach bis zum nächsten Versuch.
 *
 * Port von `domain/round/RoundSync.kt`, Schritt für Schritt in derselben Reihenfolge (Spieler holen, Spieler
 * hochladen, Regelwerk, Abende, Spieler löschen). Es läuft höchstens ein Abgleich gleichzeitig.
 */
import { involves } from "../evening/Evening";
import { floorMod, kotlinTrim, kotlinTrimEnd } from "../kotlin";
import type { Evening, EveningId, Player, PlayerId, RuleSet } from "../model";
import { MAX_NAME_LENGTH } from "../players";
import { sameEvening, sameEveningOrNull, samePlayer, sameRules, sameRulesOrNull } from "./equality";
import { EveningMerge } from "./EveningMerge";
import {
  remotePlayer,
  RoundException,
  sameContent,
  toPlayer,
  type EveningBase,
  type RemoteEvening,
  type RemotePlayer,
  type RoundError,
  type RoundHead,
  type RoundId,
  type RoundLink,
  type SavedEvening,
} from "./RoundModels";
import {
  EveningNotStorableError,
  type EveningEditor,
  type EveningRepository,
  type PlayerRepository,
  type RoundApi,
  type RoundStore,
  type SettingsRepository,
} from "./RoundPorts";

/**
 * Der Server hat eine Änderung an einem Spieler abgelehnt; die Oberfläche sagt es dem Nutzer.
 *
 * @property name Der Name, den dieses Gerät hochladen wollte.
 * @property reason Der Grund, zum Beispiel `NAME_TAKEN` oder `NAME_LOCKED`.
 * @property nowName Wie der Spieler jetzt auf diesem Gerät heißt; `null`, wenn es ihn hier nicht mehr gibt.
 */
export interface SyncNotice {
  readonly player: PlayerId;
  readonly name: string;
  readonly reason: RoundError;
  readonly nowName: string | null;
}

/**
 * Der Abgleich ist durchgelaufen.
 *
 * @property pulled So viele Abende, Spieler und Regelwerke haben sich auf diesem Gerät durch den Server geändert.
 * @property pushed So viele Änderungen hat dieses Gerät hochgeladen.
 * @property pending So viele Änderungen warten danach noch (zum Beispiel, weil gerade jemand anders schrieb).
 * @property unreadable So viele Abende des Servers kann diese Version der App nicht lesen; sie bleiben unberührt
 *   und werden bei jedem Abgleich neu gelesen (`RoundLink.unreadable`).
 * @property refused So viele Abende hat der Server in dieser Sitzung nicht angenommen (zum Beispiel, weil einer
 *   zu groß ist); sie bleiben auf dem Gerät und warten, bis sie sich ändern.
 */
export interface Synced {
  readonly kind: "Synced";
  readonly pulled: number;
  readonly pushed: number;
  readonly pending: number;
  readonly unreadable: number;
  readonly refused: number;
  readonly notices: readonly SyncNotice[];
}

/**
 * Wie ein Abgleich ausgegangen ist (Kotlin: die versiegelte Schnittstelle `SyncOutcome`; `kind` trägt den Namen der
 * Kotlin-Klasse):
 * - `Synced`: durchgelaufen,
 * - `Unchanged`: der Server hat nichts Neues; ob hier etwas wartet, wurde nicht geprüft (nur bei `quick`),
 * - `NotLinked`: das Gerät ist mit keiner Runde verbunden,
 * - `Detached`: die Runde gibt es nicht mehr, oder das Konto gehört nicht mehr dazu; die Verbindung ist gelöst, die
 *   Daten bleiben,
 * - `SignedOut`: die Anmeldung ist abgelaufen; nach erneutem Anmelden geht es weiter,
 * - `Offline`: keine Verbindung zum Server,
 * - `Failed`: der Server antwortet mit einem Fehler (zum Beispiel, weil das Projekt pausiert ist).
 */
export type SyncOutcome =
  | Synced
  | { readonly kind: "Unchanged" }
  | { readonly kind: "NotLinked" }
  | { readonly kind: "Detached" }
  | { readonly kind: "SignedOut" }
  | { readonly kind: "Offline" }
  | { readonly kind: "Failed"; readonly error: RoundError };

/** Die Ausgänge eines Abgleichs zum Erzeugen und Vergleichen. */
export const SyncOutcome = {
  /** Ein durchgelaufener Abgleich; was nicht angegeben ist, hat den Standardwert der Kotlin-Klasse. */
  synced(fields: Pick<Synced, "pulled" | "pushed" | "pending"> & Partial<Omit<Synced, "kind">>): Synced {
    return { kind: "Synced", unreadable: 0, refused: 0, notices: [], ...fields };
  },
  Unchanged: { kind: "Unchanged" } as SyncOutcome,
  NotLinked: { kind: "NotLinked" } as SyncOutcome,
  Detached: { kind: "Detached" } as SyncOutcome,
  SignedOut: { kind: "SignedOut" } as SyncOutcome,
  Offline: { kind: "Offline" } as SyncOutcome,
  failed(error: RoundError): SyncOutcome {
    return { kind: "Failed", error };
  },
};

/** Höchstens so oft läuft ein Abgleich hintereinander durch, bevor er auf den nächsten Anstoß wartet. */
const MAX_PASSES = 2;

/** Höchstens so oft wird ein Abend je Durchgang hochgeladen, wenn andere gleichzeitig schreiben. */
const MAX_TRIES = 3;

/** Der Server speichert Farbnummern von 0 bis 31 (ein Vielfaches der Farben der Oberfläche). */
const SERVER_COLOR_COUNT = 32;

/** Ablehnungen, die nur den einen Spieler betreffen; alles andere bricht den Abgleich ab. */
const PLAYER_REJECTIONS: ReadonlySet<RoundError> = new Set<RoundError>([
  "NAME_LOCKED",
  "NAME_TAKEN",
  "INVALID_NAME",
  "PLAYER_OWNED",
  "LIMIT_REACHED",
  "INVALID_DOC",
]);

/** Ablehnungen, die nur den einen Abend oder das Regelwerk betreffen. */
const DOCUMENT_REJECTIONS: ReadonlySet<RoundError> = new Set<RoundError>(["TOO_LARGE", "INVALID_DOC", "LIMIT_REACHED"]);

/** Wie Javas „\s+“: Folgen von ASCII-Leerraum. */
const WHITESPACE = /[ \t\n\v\f\r]+/g;

/** Ob `error` eine Ablehnung aus `rejections` ist; alles andere reicht der Aufrufer weiter. */
function rejection(error: unknown, rejections: ReadonlySet<RoundError>): RoundError | null {
  return error instanceof RoundException && rejections.has(error.error) ? error.error : null;
}

/** Ein Name, den noch niemand trägt: der gewünschte mit einer angehängten Nummer. */
function freeName(name: string, taken: readonly string[]): string {
  const used = new Set(taken.map((it) => it.toLowerCase()));
  for (let number = 2; ; number++) {
    const suffix = ` ${number}`;
    const candidate = kotlinTrimEnd(name.slice(0, MAX_NAME_LENGTH - suffix.length)) + suffix;
    if (!used.has(candidate.toLowerCase())) return candidate;
  }
}

/** Ob zwei Listen dieselben Kennungen enthalten, ohne auf die Reihenfolge zu achten (wie Kotlins `Set ==`). */
function sameIds(a: readonly string[], b: readonly string[]): boolean {
  const left = new Set(a);
  const right = new Set(b);
  return left.size === right.size && [...left].every((id) => right.has(id));
}

function sameLink(a: RoundLink, b: RoundLink): boolean {
  return (
    a.roundId === b.roundId &&
    a.roundName === b.roundName &&
    a.userId === b.userId &&
    a.playerId === b.playerId &&
    a.isCreator === b.isCreator &&
    a.cursor === b.cursor &&
    sameRulesOrNull(a.rulesBase, b.rulesBase) &&
    a.members.length === b.members.length &&
    a.members.every((member, index) => member.userId === b.members[index].userId && member.playerId === b.members[index].playerId) &&
    sameIds(a.unreadable, b.unreadable)
  );
}

/**
 * Läuft um jeden Abgleich und jeden Auftrag von {@link RoundSync.exclusive}, innerhalb der eigenen Sperre. Die
 * Web-App sperrt damit über alle Fenster hinweg und liest vorher den Gerätezustand frisch (docs/ARCHITEKTUR.md,
 * Abschnitt 8.8); in Kotlin gibt es das nicht, ohne Angabe läuft der Auftrag einfach.
 */
export type SyncGuard = <T>(block: () => Promise<T>) => Promise<T>;

/** Lässt immer nur einen Auftrag gleichzeitig laufen (wie Kotlins `Mutex.withLock`), in der Reihenfolge der Aufrufe. */
class Mutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run = this.tail.then(block);
    this.tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }
}

/** Was sich der Abgleich über einzelne Durchläufe hinweg merkt, solange die App läuft. */
interface SessionMemory {
  /** Für diese Runde gilt das Gemerkte. */
  roundId: RoundId | null;

  /** Spieler, die der Server in dieser Sitzung abgelehnt hat: nicht bei jedem Abgleich dasselbe erneut versuchen. */
  readonly rejected: Map<PlayerId, Player>;

  /** Abende, die der Server in dieser Sitzung nicht angenommen hat, mit dem Stand, den er abgelehnt hat. */
  readonly refusedEvenings: Map<EveningId, Evening>;

  /** Das Regelwerk, das der Server in dieser Sitzung nicht angenommen hat. */
  refusedRules: RuleSet | null;
}

export class RoundSync {
  private readonly mutex = new Mutex();
  private readonly memory: SessionMemory = { roundId: null, rejected: new Map(), refusedEvenings: new Map(), refusedRules: null };

  /** @param guard Läuft um jeden Abgleich und jeden Auftrag von {@link exclusive} (siehe {@link SyncGuard}). */
  constructor(
    private readonly api: RoundApi,
    private readonly store: RoundStore,
    private readonly players: PlayerRepository,
    private readonly evenings: EveningRepository,
    private readonly settings: SettingsRepository,
    private readonly editor: EveningEditor,
    private readonly guard: SyncGuard = (block) => block(),
  ) {}

  /**
   * Ein Abgleich.
   *
   * @param quick Nur nachsehen, ob der Server etwas Neues hat (eine kleine Anfrage), und sonst sofort mit
   *   `Unchanged` aufhören. Für das regelmäßige Nachfragen, solange hier nichts geändert wurde.
   */
  sync(quick = false): Promise<SyncOutcome> {
    return this.locked(() => this.syncLocked(quick));
  }

  /**
   * Gleicht vollständig ab und führt danach `block` mit dem Ergebnis aus, ohne dass dazwischen ein anderer Abgleich
   * läuft (Kotlin: `internal`). Für den Wechsel in eine andere Runde (`RoundConnection`): Was bis eben eingetragen
   * wurde, ist dann oben, bevor das Gerät geleert wird.
   */
  syncThen<T>(block: (outcome: SyncOutcome) => Promise<T>): Promise<T> {
    return this.locked(async () => block(await this.syncLocked(false)));
  }

  /** Ein Abgleich; nur innerhalb der eigenen Sperre. */
  private async syncLocked(quick: boolean): Promise<SyncOutcome> {
    const link = await this.store.link();
    if (link === null) return SyncOutcome.NotLinked;
    // Eine andere Runde (zum Beispiel nach „Runde verlassen“ und „Neue Runde anlegen“): von vorn versuchen. Was
    // eine Runde abgelehnt hat, sagt nichts über eine andere.
    if (this.memory.roundId !== link.roundId) {
      this.memory.roundId = link.roundId;
      this.memory.rejected.clear();
      this.memory.refusedEvenings.clear();
      this.memory.refusedRules = null;
    }
    try {
      return await new Run(this.api, this.store, this.players, this.evenings, this.settings, this.editor, this.memory, link, () =>
        this.pendingCount(),
      ).execute(quick);
    } catch (error) {
      if (!(error instanceof RoundException)) throw error;
      if (error.error === "OFFLINE") return SyncOutcome.Offline;
      if (error.error === "SIGNED_OUT") return SyncOutcome.SignedOut;
      return SyncOutcome.failed(error.error);
    }
  }

  /**
   * Wie viele Änderungen dieses Geräts noch nicht auf dem Server sind: geänderte, neue und gelöschte Abende und
   * Spieler sowie ein geändertes Regelwerk für neue Abende. Ohne Runde 0.
   */
  async pendingCount(): Promise<number> {
    const link = await this.store.link();
    if (link === null) return 0;
    const localEvenings = await this.evenings.evenings();
    const eveningBases = await this.store.eveningBases();
    const localPlayers = await this.players.players();
    const playerBases = await this.store.playerBases();
    const eveningIds = new Set(localEvenings.map((it) => it.id));
    const playerIds = new Set(localPlayers.map((it) => it.id));
    let count = 0;
    for (const evening of localEvenings) {
      const base = eveningBases.get(evening.id);
      if (base === undefined || !sameEvening(base.evening, evening)) count++;
    }
    for (const id of eveningBases.keys()) if (!eveningIds.has(id)) count++;
    for (const player of localPlayers) {
      const base = playerBases.get(player.id);
      if (base === undefined || !sameContent(base, player)) count++;
    }
    for (const id of playerBases.keys()) if (!playerIds.has(id)) count++;
    if (!sameRulesOrNull((await this.settings.settings()).defaultRules, link.rulesBase)) count++;
    return count;
  }

  /** Führt `block` aus, während kein Abgleich läuft (für das Verbinden und Trennen, siehe `RoundConnection`). */
  exclusive<T>(block: () => Promise<T>): Promise<T> {
    return this.locked(block);
  }

  private locked<T>(block: () => Promise<T>): Promise<T> {
    return this.mutex.withLock(() => this.guard(block));
  }
}

/** Ein Durchlauf von `sync` mit seinem Zwischenstand. */
class Run {
  private pulled = 0;
  private pushed = 0;

  /** Abende des Servers, die sich in diesem Durchgang nicht lesen oder nicht speichern ließen. */
  private readonly unreadable = new Set<EveningId>();
  private readonly notices: SyncNotice[] = [];

  /** Die Nummer, die der Server der nächsten Änderung gibt, wenn außer diesem Gerät niemand schreibt. */
  private nextRev = 0;

  /** Zwischen den eigenen Änderungen hat jemand anders geschrieben: Es gibt noch etwas zu holen. */
  private gap = false;

  /** Es ist noch etwas hochzuladen, das erst in diesem Durchgang entstanden ist. */
  private again = false;

  private eveningBases = new Map<EveningId, EveningBase>();
  private playerBases = new Map<PlayerId, RemotePlayer>();

  /** Spieler, die der Server als gelöscht meldet. Ob sie hier wirklich gehen, entscheidet sich nach den Abenden. */
  private readonly removals = new Set<PlayerId>();

  constructor(
    private readonly api: RoundApi,
    private readonly store: RoundStore,
    private readonly players: PlayerRepository,
    private readonly evenings: EveningRepository,
    private readonly settings: SettingsRepository,
    private readonly editor: EveningEditor,
    private readonly memory: SessionMemory,
    private link: RoundLink,
    private readonly pendingCount: () => Promise<number>,
  ) {}

  async execute(quick: boolean): Promise<SyncOutcome> {
    for (let pass = 1; pass <= MAX_PASSES; pass++) {
      const head = await this.api.head(this.link.roundId);
      if (head === null) return this.detach();
      if (quick && pass === 1 && head.rev === this.link.cursor) return SyncOutcome.Unchanged;
      await this.pass(head);
      if (!this.gap && !this.again) break;
    }
    // Beide Zahlen gelten auch für Abende, die dieser Abgleich nicht angefasst hat: die gemerkten unlesbaren und die,
    // die der Server in dieser Sitzung abgelehnt hat und die seitdem unverändert auf dem Gerät liegen.
    return SyncOutcome.synced({
      pulled: this.pulled,
      pushed: this.pushed,
      pending: await this.pendingCount(),
      unreadable: this.link.unreadable.length,
      refused: this.memory.refusedEvenings.size,
      notices: this.notices,
    });
  }

  /** Der Kopf der Runde fehlt: Gibt es sie wirklich nicht mehr, wird die Verbindung gelöst. */
  private async detach(): Promise<SyncOutcome> {
    // Sicherheitshalber gegenprüfen, damit eine einzelne seltsame Antwort die Runde nicht vom Gerät trennt.
    if ((await this.api.myRounds()).some((it) => it.id === this.link.roundId)) return SyncOutcome.failed("SERVER");
    await this.store.setLink(null);
    await this.store.clearBases();
    return SyncOutcome.Detached;
  }

  private async pass(head: RoundHead): Promise<void> {
    const stored = this.link;
    // Ein Server, der hinter den bekannten Stand zurückfällt (neu aufgesetzt): alles noch einmal lesen.
    if (head.rev < this.link.cursor) this.link = { ...this.link, cursor: 0 };
    this.nextRev = head.rev + 1;
    this.gap = false;
    this.again = false;
    this.link = { ...this.link, roundName: head.name, isCreator: head.createdBy === this.link.userId };

    let remotePlayers: readonly RemotePlayer[] = [];
    let remoteEvenings: readonly RemoteEvening[] = [];
    if (head.rev > this.link.cursor) {
      const members = await this.api.members(this.link.roundId);
      remotePlayers = await this.api.playersSince(this.link.roundId, this.link.cursor);
      remoteEvenings = await this.api.eveningsSince(this.link.roundId, this.link.cursor);
      const userId = this.link.userId;
      this.link = { ...this.link, members, playerId: members.find((it) => it.userId === userId)?.playerId ?? null };
    }
    // Spieler vor den Abenden: Kein Abend soll auf dem Server oder hier jemanden nennen, den es dort nicht gibt.
    // Gelöscht wird erst danach, wenn feststeht, dass kein Abend den Spieler braucht.
    await this.applyPlayers(remotePlayers);
    await this.pushPlayers();
    await this.syncRules(head);
    await this.syncEvenings(remoteEvenings);
    await this.removePlayers();

    // Alles bis zum Stand des Kopfes ist verarbeitet, dazu die eigenen Änderungen, solange niemand dazwischen
    // geschrieben hat. Was darüber hinaus gelesen wurde, kommt beim nächsten Mal noch einmal und ändert nichts.
    this.link = { ...this.link, cursor: this.nextRev - 1 };
    if (!sameLink(this.link, stored)) await this.store.setLink(this.link);
  }

  /** Merkt sich eine hochgeladene Änderung und ob die Nummern des Servers noch lückenlos die eigenen sind. */
  private countPush(rev: number): void {
    this.pushed++;
    if (!this.gap && rev === this.nextRev) this.nextRev++;
    else this.gap = true;
  }

  // --- Spieler ---

  private async setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    if (base === null) this.playerBases.delete(id);
    else this.playerBases.set(id, base);
    await this.store.setPlayerBase(id, base);
  }

  /** Was der Server an Spielern geändert hat, gilt. Neue Spieler in der Reihenfolge, in der sie angelegt wurden. */
  private async applyPlayers(rows: readonly RemotePlayer[]): Promise<void> {
    this.playerBases = new Map(await this.store.playerBases());
    this.removals.clear();
    if (rows.length === 0) return;
    const local = new Map((await this.players.players()).map((it) => [it.id, it]));
    const sorted = [...rows].sort((a, b) => a.position - b.position || a.rev - b.rev);
    for (const row of sorted) {
      const mine = local.get(row.id);
      if (row.deleted) {
        if (mine === undefined) await this.setPlayerBase(row.id, null);
        else this.removals.add(row.id);
        continue;
      }
      const base = this.playerBases.get(row.id);
      const theirs = toPlayer(row);
      // Steht auf dem Server noch, was dieses Gerät zuletzt kannte, bleibt eine Änderung von hier bestehen.
      const newOnServer = base === undefined || !sameContent(base, theirs);
      if (newOnServer && (mine === undefined || !samePlayer(mine, theirs))) {
        await this.players.save(theirs);
        this.pulled++;
      }
      await this.setPlayerBase(row.id, row);
    }
  }

  /** Was hier an Spielern neu oder geändert ist, hochladen. */
  private async pushPlayers(): Promise<void> {
    const local = await this.players.players();
    let eveningsNow: readonly Evening[] | null = null;
    const inUse = async (id: PlayerId): Promise<boolean> => {
      eveningsNow ??= await this.evenings.evenings();
      return eveningsNow.some((it) => involves(it, id));
    };
    for (const current of local) {
      if (this.removals.has(current.id)) continue;
      const base = this.playerBases.get(current.id);
      if (base !== undefined && sameContent(base, current)) continue;
      const player = await this.inServerForm(current);
      const rejected = this.memory.rejected.get(player.id);
      if (rejected !== undefined && samePlayer(rejected, player)) continue;
      try {
        const rev = await this.api.savePlayer(this.link.roundId, player);
        this.countPush(rev);
        const saved = base ?? remotePlayer({ id: player.id, name: player.name, colorIndex: player.colorIndex, archived: player.archived });
        await this.setPlayerBase(player.id, {
          ...saved,
          name: player.name,
          colorIndex: player.colorIndex,
          archived: player.archived,
          deleted: false,
          rev,
        });
      } catch (error) {
        const reason = rejection(error, PLAYER_REJECTIONS);
        if (reason === null) throw error;
        if (base !== undefined) {
          // Der Server bleibt bei seinem Stand: zurück auf das, was dort steht.
          await this.players.save(toPlayer(base));
          this.notices.push({ player: player.id, name: player.name, reason, nowName: base.name });
        } else if (!(await inUse(player.id))) {
          // Hier neu angelegt und noch nirgends gebraucht: wieder weg.
          await this.players.delete(player.id);
          this.notices.push({ player: player.id, name: player.name, reason, nowName: null });
        } else if (reason === "NAME_TAKEN") {
          // Wird in einem Abend gebraucht, aber der Name ist vergeben: unter anderem Namen hochladen.
          const renamed: Player = { ...player, name: freeName(player.name, local.map((it) => it.name)) };
          await this.players.save(renamed);
          this.notices.push({ player: player.id, name: player.name, reason, nowName: renamed.name });
          this.again = true;
        } else {
          this.memory.rejected.set(player.id, player);
          this.notices.push({ player: player.id, name: player.name, reason, nowName: player.name });
        }
      }
    }
  }

  /**
   * Löscht Spieler, die der Server als gelöscht meldet, und lädt hoch, was hier gelöscht wurde. Beides erst nach den
   * Abenden und nie, solange ein Abend auf diesem Gerät den Spieler nennt: Dann bleibt er (oder kommt zurück).
   */
  private async removePlayers(): Promise<void> {
    const local = new Set((await this.players.players()).map((it) => it.id));
    const deletedHere = [...this.playerBases.keys()].filter((id) => !local.has(id));
    if (this.removals.size === 0 && deletedHere.length === 0) return;
    const all = await this.evenings.evenings();
    for (const id of this.removals) {
      if (!local.has(id)) {
        // Hier schon weg.
      } else if (all.some((it) => involves(it, id))) {
        // Wird hier noch gebraucht: Der Spieler bleibt und geht im nächsten Durchgang als neu wieder hoch.
        this.again = true;
      } else {
        await this.players.delete(id);
        this.pulled++;
      }
      await this.setPlayerBase(id, null);
    }
    for (const id of deletedHere) {
      const base = this.playerBases.get(id);
      if (base === undefined) continue;
      if (all.some((it) => involves(it, id))) {
        // Inzwischen nennt ihn ein Abend, der von einem anderen Gerät kam: Der Spieler kommt zurück.
        await this.players.save(toPlayer(base));
        this.pulled++;
        continue;
      }
      try {
        this.countPush(await this.api.savePlayer(this.link.roundId, toPlayer(base), true));
        await this.setPlayerBase(id, null);
      } catch (error) {
        const reason = rejection(error, PLAYER_REJECTIONS);
        if (reason === null) throw error;
        // Der Spieler gehört einem Mitglied und lässt sich nicht löschen: Er kommt zurück.
        await this.players.save(toPlayer(base));
        this.notices.push({ player: id, name: base.name, reason, nowName: base.name });
      }
    }
  }

  /**
   * Der Server bereinigt Namen (Leerraum außen weg, innen einfach, höchstens 24 Zeichen) und kennt Farben von 0 bis
   * 31. Was davon abweicht (das kommt nur aus alten oder eingelesenen Daten), wird vorher hier umgestellt, damit auf
   * allen Geräten dasselbe steht.
   */
  private async inServerForm(player: Player): Promise<Player> {
    const name = kotlinTrimEnd(kotlinTrim(player.name).replace(WHITESPACE, " ").slice(0, MAX_NAME_LENGTH));
    const changed: Player = {
      ...player,
      name: name.length === 0 ? player.name : name,
      colorIndex: floorMod(player.colorIndex, SERVER_COLOR_COUNT),
    };
    if (!samePlayer(changed, player)) await this.players.save(changed);
    return changed;
  }

  // --- Regelwerk für neue Abende ---

  private async syncRules(head: RoundHead): Promise<void> {
    if (head.rulesUnreadable) return;
    const local = (await this.settings.settings()).defaultRules;
    const theirs = head.rules;
    if (theirs !== null && !sameRulesOrNull(theirs, this.link.rulesBase)) {
      // Der Server hat ein anderes als zuletzt bekannt: Es gilt, auch gegen eine Änderung von hier.
      if (!sameRules(local, theirs)) {
        await this.settings.update((it) => ({ ...it, defaultRules: theirs }));
        this.pulled++;
      }
      this.link = { ...this.link, rulesBase: theirs };
    } else if (theirs === null || !sameRulesOrNull(local, this.link.rulesBase)) {
      // Der Server hat noch keines, oder hier wurde es geändert.
      if (this.memory.refusedRules !== null && sameRules(local, this.memory.refusedRules)) return;
      try {
        this.countPush(await this.api.saveRules(this.link.roundId, local));
        this.link = { ...this.link, rulesBase: local };
      } catch (error) {
        if (rejection(error, DOCUMENT_REJECTIONS) === null) throw error;
        // Der Server nimmt dieses Regelwerk nicht an: nicht bei jedem Abgleich neu versuchen.
        this.memory.refusedRules = local;
      }
    }
  }

  // --- Abende ---

  private async syncEvenings(rows: readonly RemoteEvening[]): Promise<void> {
    this.eveningBases = new Map(await this.store.eveningBases());
    const local = await this.evenings.evenings();
    // Kommt ein Abend mehrfach (er wurde während des Lesens noch einmal geändert), zählt die neueste Fassung.
    const latest = new Map<EveningId, RemoteEvening>();
    for (const row of rows) {
      const known = latest.get(row.id);
      if (known === undefined || row.version >= known.version) latest.set(row.id, row);
    }
    // Was dieses Gerät früher nicht lesen konnte, liegt hinter dem Lesezeichen und käme nie wieder: einzeln neu
    // lesen. Gibt es den Abend nicht mehr, ist er damit vergessen.
    for (const id of this.link.unreadable) {
      if (latest.has(id)) continue;
      const row = await this.api.evening(this.link.roundId, id);
      if (row !== null) latest.set(id, row);
    }
    this.unreadable.clear();
    const ids = new Set<EveningId>(latest.keys());
    const localIds = new Set<EveningId>();
    for (const evening of local) {
      localIds.add(evening.id);
      const base = this.eveningBases.get(evening.id);
      const refused = this.memory.refusedEvenings.get(evening.id);
      const changed = base === undefined || !sameEvening(base.evening, evening);
      // Was der Server so schon einmal abgelehnt hat, wartet, bis es sich ändert.
      if (changed && (refused === undefined || !sameEvening(refused, evening))) ids.add(evening.id);
    }
    for (const id of [...this.memory.refusedEvenings.keys()]) {
      if (!localIds.has(id)) this.memory.refusedEvenings.delete(id);
    }
    for (const id of this.eveningBases.keys()) if (!localIds.has(id)) ids.add(id);
    for (const id of ids) await this.syncEvening(id, latest.get(id) ?? null);
    this.link = { ...this.link, unreadable: [...this.unreadable] };
  }

  private async setBase(id: EveningId, base: EveningBase | null): Promise<void> {
    if (base === null) this.eveningBases.delete(id);
    else this.eveningBases.set(id, base);
    await this.store.setEveningBase(id, base);
  }

  private async syncEvening(id: EveningId, pulledRow: RemoteEvening | null): Promise<void> {
    try {
      await this.trySyncEvening(id, pulledRow);
      this.memory.refusedEvenings.delete(id);
    } catch (error) {
      if (error instanceof EveningNotStorableError) {
        // Dieses Gerät kann die Fassung des Servers nicht speichern (beschädigte Daten): wie unlesbar behandeln.
        this.unreadable.add(id);
        return;
      }
      if (rejection(error, DOCUMENT_REJECTIONS) === null) throw error;
      // Der Server nimmt diesen einen Abend nicht an. Die anderen sollen deshalb nicht liegen bleiben. Gemerkt wird
      // der Stand, den er abgelehnt hat; gibt es den Abend hier nicht mehr, gibt es auch nichts zu merken.
      const current = await this.evenings.evening(id);
      if (current === null) this.memory.refusedEvenings.delete(id);
      else this.memory.refusedEvenings.set(id, current);
    }
  }

  private async trySyncEvening(id: EveningId, pulledRow: RemoteEvening | null): Promise<void> {
    let row = pulledRow;
    for (let tries = 0; tries < MAX_TRIES; tries++) {
      if (await this.attempt(id, row)) return;
      // Jemand anders war schneller: die Fassung des Servers neu lesen und noch einmal. Dann gibt es auch sonst
      // vielleicht Neues, das der nächste Durchgang holt.
      this.gap = true;
      row = await this.api.evening(this.link.roundId, id);
      if (row === null) await this.setBase(id, null);
    }
  }

  /** @returns `false`, wenn der Server inzwischen eine andere Fassung hat als angenommen. */
  private async attempt(id: EveningId, row: RemoteEvening | null): Promise<boolean> {
    // Fällt der Server hinter die bekannte Fassung zurück, wird ohne Basis zusammengeführt: So geht nichts verloren.
    const known = this.eveningBases.get(id);
    const base = known !== undefined && (row === null || row.version >= known.version) ? known : null;
    if (row === null) return this.pushLocal(id, base);
    if (row.deleted) return this.applyDeletion(id, row, base);
    const remote = row.evening;
    if (remote === null) {
      this.unreadable.add(id);
      return true;
    }
    return this.mergeWith(id, row, remote, base);
  }

  /** Der Server hat nichts Neueres als die Basis (oder kennt den Abend nicht): den Stand von hier hochladen. */
  private async pushLocal(id: EveningId, base: EveningBase | null): Promise<boolean> {
    const local = await this.evenings.evening(id);
    let saved: SavedEvening | null;
    if (base === null) {
      if (local === null) return true;
      saved = await this.api.saveEvening(this.link.roundId, local, 0);
    } else if (local === null) {
      saved = await this.api.saveEvening(this.link.roundId, base.evening, base.version, true);
    } else if (sameEvening(local, base.evening)) {
      return true;
    } else {
      saved = await this.api.saveEvening(this.link.roundId, local, base.version);
    }
    if (saved === null) return false;
    await this.setBase(id, local === null ? null : { version: saved.version, evening: local });
    this.countPush(saved.rev);
    return true;
  }

  /** Der Server meldet den Abend als gelöscht. */
  private async applyDeletion(id: EveningId, row: RemoteEvening, base: EveningBase | null): Promise<boolean> {
    let removed = false;
    const kept = await this.editor.replace(id, (current) => {
      removed = current !== null && base !== null && sameEvening(current, base.evening);
      return removed ? null : current;
    });
    if (kept === null) {
      if (removed) this.pulled++;
      await this.setBase(id, null);
      return true;
    }
    // Hier wurde der Abend geändert, während ihn ein anderes Gerät gelöscht hat: Die Änderung gewinnt.
    const saved = await this.api.saveEvening(this.link.roundId, kept, row.version, false);
    if (saved === null) return false;
    await this.setBase(id, { version: saved.version, evening: kept });
    this.countPush(saved.rev);
    return true;
  }

  /** Der Server hat eine lesbare Fassung: mit dem Stand von hier zusammenführen und das Ergebnis hochladen. */
  private async mergeWith(id: EveningId, row: RemoteEvening, remote: Evening, base: EveningBase | null): Promise<boolean> {
    let changedHere = false;
    const merged = await this.editor.replace(id, (current) => {
      let result: Evening | null;
      if (current !== null) result = EveningMerge.merge3(base?.evening ?? null, current, remote);
      // Hier unbekannt, oder hier gelöscht und auf dem Server seitdem geändert: (wieder) anlegen.
      else if (base === null || row.version !== base.version) result = remote;
      // Hier gelöscht und auf dem Server unverändert: bleibt gelöscht.
      else result = null;
      changedHere = !sameEveningOrNull(result, current);
      return result;
    });
    if (changedHere) this.pulled++;
    if (merged === null) {
      const saved = await this.api.saveEvening(this.link.roundId, remote, row.version, true);
      if (saved === null) return false;
      await this.setBase(id, null);
      this.countPush(saved.rev);
      return true;
    }
    // Erst der Abend, dann die Basis: Bricht es dazwischen ab, führt der nächste Abgleich noch einmal zusammen und
    // kommt zum selben Ergebnis. Andersherum sähe Neues vom Server aus, als wäre es hier gelöscht worden.
    await this.setBase(id, { version: row.version, evening: remote });
    // Hochgeladen wird, was jetzt wirklich auf dem Gerät steht. Speichert die Ablage etwas anders, als es kam (zum
    // Beispiel Zeitpunkte nur auf Millisekunden genau), gilt ihre Fassung – sonst hielte das Gerät den Abend beim
    // nächsten Mal für geändert.
    const stored = await this.evenings.evening(id);
    if (stored === null) return true;
    if (sameEvening(stored, remote)) return true;
    const saved = await this.api.saveEvening(this.link.roundId, stored, row.version);
    if (saved === null) return false;
    await this.setBase(id, { version: saved.version, evening: stored });
    this.countPush(saved.rev);
    return true;
  }
}
