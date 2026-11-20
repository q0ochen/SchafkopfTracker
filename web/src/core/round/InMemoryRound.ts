/*
 * Die gemeinsame Runde ganz im Speicher: ein Gerätezustand und ein nachgestellter Server. Beides dient Tests (auch
 * den Oberflächen-Tests, die damit die Antworten von Supabase nachstellen). Port von `domain/round/InMemoryRound.kt`.
 * Der Server verhält sich nach dem Vertrag aus docs/ARCHITEKTUR.md, Abschnitt 8.2 und 8.3; maßgeblich bleibt der
 * echte Server in `supabase/` mit seinen Tests.
 */
import { floorMod, kotlinTrim } from "../kotlin";
import type { Evening, EveningId, Player, PlayerId, RuleSet } from "../model";
import { cleanName } from "../players";
import {
  RoundException,
  type EveningBase,
  type InviteInfo,
  type RemoteEvening,
  type RemotePlayer,
  type RoundHead,
  type RoundId,
  type RoundLink,
  type RoundMember,
  type RoundSummary,
  type SavedEvening,
} from "./RoundModels";
import type { RoundApi, RoundStore } from "./RoundPorts";

/** Was sich ein Gerät zum Abgleich merkt, nur im Speicher. */
export class InMemoryRoundStore implements RoundStore {
  private current: RoundLink | null;
  private readonly evenings = new Map<EveningId, EveningBase>();
  private readonly players = new Map<PlayerId, RemotePlayer>();
  private readonly listeners = new Set<() => void>();

  constructor(initial: RoundLink | null = null) {
    this.current = initial;
  }

  /** Meldet, wenn sich die verbundene Runde geändert hat oder alle Basen vergessen wurden (Kotlin: `link` als Flow). */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify(): void {
    for (const listener of [...this.listeners]) listener();
  }

  async link(): Promise<RoundLink | null> {
    return this.current;
  }

  async setLink(link: RoundLink | null): Promise<void> {
    this.current = link;
    this.notify();
  }

  async eveningBases(): Promise<ReadonlyMap<EveningId, EveningBase>> {
    return new Map(this.evenings);
  }

  async setEveningBase(id: EveningId, base: EveningBase | null): Promise<void> {
    if (base === null) this.evenings.delete(id);
    else this.evenings.set(id, base);
  }

  async playerBases(): Promise<ReadonlyMap<PlayerId, RemotePlayer>> {
    return new Map(this.players);
  }

  async setPlayerBase(id: PlayerId, base: RemotePlayer | null): Promise<void> {
    if (base === null) this.players.delete(id);
    else this.players.set(id, base);
  }

  async clearBases(): Promise<void> {
    this.evenings.clear();
    this.players.clear();
    this.notify();
  }
}

const MAX_ROUNDS = 5;
const MAX_MEMBERS = 12;
const MAX_PLAYERS = 40;
const MAX_EVENINGS = 3000;
const MAX_INVITES = 20;
const MAX_ROUND_NAME_LENGTH = 40;
const MAX_ID_LENGTH = 64;

/**
 * Der echte Server begrenzt die Größe eines Abends auf 300 000 Bytes und die des Regelwerks auf 20 000. Hier stehen
 * dafür einfache Zahlen, an denen sich die Ablehnung zeigen lässt.
 */
const MAX_ENTRIES = 1000;
const MAX_RULES_NAME_LENGTH = 10_000;

/** Der Server speichert Farbnummern von 0 bis 31. */
const COLOR_COUNT = 32;

/** Eine Einladung gilt 14 Tage. */
const INVITE_LIFETIME_MILLIS = 14 * 24 * 60 * 60 * 1000;

class Round {
  rev = 0;
  rules: RuleSet | null = null;
  rulesUnreadable = false;

  /** Konto → sein Spieler, in der Reihenfolge des Beitritts. */
  readonly members = new Map<string, PlayerId | null>();
  readonly players = new Map<PlayerId, RemotePlayer>();
  readonly evenings = new Map<EveningId, RemoteEvening>();

  /** Einladungscode → Ablauf (Millisekunden seit 1970). */
  readonly invites = new Map<string, number>();

  constructor(
    readonly id: RoundId,
    readonly name: string,
    readonly createdBy: string | null,
  ) {}

  nextRev(): number {
    return ++this.rev;
  }

  activePlayers(): number {
    let count = 0;
    for (const player of this.players.values()) if (!player.deleted) count++;
    return count;
  }

  /** Namen sind je Runde eindeutig, ohne auf Groß- und Kleinschreibung zu achten. */
  requireFreeName(name: string, except: PlayerId): void {
    for (const player of this.players.values()) {
      if (!player.deleted && player.id !== except && player.name.toLowerCase() === name.toLowerCase()) {
        throw new RoundException("NAME_TAKEN");
      }
    }
  }

  /** Der bereinigte Name, wenn er gültig und noch frei ist. */
  freeName(name: string, except: PlayerId): string {
    const clean = cleanName(name);
    if (clean === null) throw new RoundException("INVALID_NAME");
    this.requireFreeName(clean, except);
    return clean;
  }

  /** Die Position für einen neuen Spieler: die größte der Runde plus eins (der erste bekommt 1). */
  nextPosition(): number {
    let max = 0;
    for (const player of this.players.values()) max = Math.max(max, player.position);
    return max + 1;
  }
}

/**
 * Ein Server für gemeinsame Runden im Speicher. Jedes Konto bekommt mit {@link sessionFor} seinen eigenen Zugang; alle
 * Zugänge sehen dieselben Runden.
 */
export class InMemoryRoundServer {
  static readonly MAX_ROUNDS = MAX_ROUNDS;
  static readonly MAX_MEMBERS = MAX_MEMBERS;
  static readonly MAX_PLAYERS = MAX_PLAYERS;
  static readonly MAX_EVENINGS = MAX_EVENINGS;
  static readonly MAX_INVITES = MAX_INVITES;
  static readonly MAX_ROUND_NAME_LENGTH = MAX_ROUND_NAME_LENGTH;
  static readonly MAX_ID_LENGTH = MAX_ID_LENGTH;
  static readonly MAX_ENTRIES = MAX_ENTRIES;
  static readonly MAX_RULES_NAME_LENGTH = MAX_RULES_NAME_LENGTH;
  static readonly COLOR_COUNT = COLOR_COUNT;
  static readonly INVITE_LIFETIME_MILLIS = INVITE_LIFETIME_MILLIS;

  private readonly rounds = new Map<RoundId, Round>();
  private created = 0;

  /**
   * @param now Die Uhr des Servers (Millisekunden seit 1970); Tests stellen sie selbst.
   * @param newRoundId Die Kennung einer neuen Runde; ohne Angabe „runde-1“, „runde-2“ …
   */
  constructor(
    private readonly now: () => number = () => Date.now(),
    private readonly newRoundId: ((number: number) => RoundId) | null = null,
  ) {}

  /** Der Zugang eines Kontos. */
  sessionFor(userId: string): InMemoryRoundSession {
    return new InMemoryRoundSession(this.internals(), userId);
  }

  // --- Für Tests: nachsehen und stören ---

  /** Die aktuelle Änderungsnummer der Runde; 0, wenn es sie nicht gibt. */
  rev(round: RoundId): number {
    return this.rounds.get(round)?.rev ?? 0;
  }

  /** Der Abend, wie er auf dem Server liegt. */
  eveningRow(round: RoundId, id: EveningId): RemoteEvening | null {
    return this.rounds.get(round)?.evenings.get(id) ?? null;
  }

  /** Alle Abende der Runde, die nicht gelöscht sind. */
  evenings(round: RoundId): Evening[] {
    const result: Evening[] = [];
    for (const row of this.rounds.get(round)?.evenings.values() ?? []) {
      if (!row.deleted && row.evening !== null) result.push(row.evening);
    }
    return result;
  }

  /** Alle Spieler der Runde, die nicht gelöscht sind, in der Reihenfolge des Anlegens. */
  players(round: RoundId): RemotePlayer[] {
    return [...(this.rounds.get(round)?.players.values() ?? [])].filter((it) => !it.deleted).sort((a, b) => a.position - b.position);
  }

  /** Ersetzt das Dokument eines Abends durch eines, das sich nicht lesen lässt (wie von einer neueren App). */
  makeUnreadable(round: RoundId, id: EveningId): void {
    const state = this.requireRound(round);
    const row = state.evenings.get(id);
    if (row === undefined) throw new Error(`Abend ${id} fehlt`);
    state.evenings.set(id, { ...row, version: row.version + 1, rev: state.nextRev(), evening: null });
  }

  /**
   * Macht das Dokument eines Abends wieder lesbar, ohne dass sich auf dem Server etwas ändert – wie nach einem Update
   * der App auf dem Gerät, das ihn bisher nicht lesen konnte.
   */
  makeReadable(round: RoundId, id: EveningId, evening: Evening): void {
    const state = this.requireRound(round);
    const row = state.evenings.get(id);
    if (row === undefined) throw new Error(`Abend ${id} fehlt`);
    state.evenings.set(id, { ...row, evening });
  }

  /** Vergisst einen Abend ganz, als hätte es ihn auf dem Server nie gegeben. */
  forgetEvening(round: RoundId, id: EveningId): void {
    this.requireRound(round).evenings.delete(id);
  }

  /** Hinterlegt ein Regelwerk, das sich nicht lesen lässt. */
  makeRulesUnreadable(round: RoundId): void {
    const state = this.requireRound(round);
    state.rules = null;
    state.rulesUnreadable = true;
    state.nextRev();
  }

  /** Entfernt ein Konto aus der Runde, wie es `leave_round` täte. */
  removeMember(round: RoundId, userId: string): void {
    const state = this.rounds.get(round);
    if (state === undefined) return;
    state.members.delete(userId);
    state.nextRev();
    if (state.members.size === 0) this.rounds.delete(round);
  }

  /** Setzt die Runde auf einen früheren Stand zurück, wie nach dem Einspielen einer alten Sicherung des Servers. */
  rollBack(round: RoundId, id: EveningId, version: number, evening: Evening, rev: number): void {
    const state = this.requireRound(round);
    state.rev = rev;
    state.evenings.set(id, { id, version, deleted: false, rev, evening });
  }

  private requireRound(round: RoundId): Round {
    const state = this.rounds.get(round);
    if (state === undefined) throw new Error(`Runde ${round} fehlt`);
    return state;
  }

  private internals(): ServerInternals {
    return {
      rounds: this.rounds,
      now: this.now,
      nextNumber: () => ++this.created,
      roundId: (number) => (this.newRoundId === null ? `runde-${number}` : this.newRoundId(number)),
    };
  }
}

/** Was ein Zugang vom Server braucht. */
interface ServerInternals {
  readonly rounds: Map<RoundId, Round>;
  readonly now: () => number;
  readonly nextNumber: () => number;
  readonly roundId: (number: number) => RoundId;
}

/** Der Zugang eines Kontos zum Server (Kotlin: `InMemoryRoundServer.Session`). Lässt sich für Tests vom Netz nehmen. */
export class InMemoryRoundSession implements RoundApi {
  /** Kein Netz: Jeder Aufruf scheitert mit `OFFLINE`. */
  offline = false;

  /** Die Anmeldung ist abgelaufen: Jeder Aufruf scheitert mit `SIGNED_OUT`. */
  signedOut = false;

  /** Die nächste Änderung kommt auf dem Server an, aber ihre Antwort geht verloren. */
  loseNextAnswer = false;

  /** Die Namen aller Aufrufe in ihrer Reihenfolge (zum Zählen der Anfragen). */
  readonly calls: string[] = [];

  constructor(
    private readonly server: ServerInternals,
    readonly userId: string,
  ) {}

  /**
   * Führt einen Aufruf aus: Die Wirkung auf dem Server geschieht sofort und in einem Stück, das Ergebnis kommt als
   * Promise (ein Fehler als abgelehntes Promise).
   */
  private async call<T>(name: string, write: boolean, block: () => T): Promise<T> {
    this.calls.push(name);
    if (this.offline) throw new RoundException("OFFLINE");
    if (this.signedOut) throw new RoundException("SIGNED_OUT");
    const result = block();
    if (write && this.loseNextAnswer) {
      this.loseNextAnswer = false;
      throw new RoundException("OFFLINE", "Antwort verloren");
    }
    return result;
  }

  /** Die Runde, wenn das Konto ihr angehört; sonst der Fehler `not_member`. */
  private member(round: RoundId): Round {
    const state = this.visible(round);
    if (state === null) throw new RoundException("NOT_MEMBER");
    return state;
  }

  /** Die Runde zum Lesen: Wer nicht dazugehört, sieht einfach nichts. */
  private visible(round: RoundId): Round | null {
    const state = this.server.rounds.get(round);
    return state !== undefined && state.members.has(this.userId) ? state : null;
  }

  head(round: RoundId): Promise<RoundHead | null> {
    return this.call("head", false, () => {
      const state = this.visible(round);
      if (state === null) return null;
      return {
        id: state.id,
        name: state.name,
        rev: state.rev,
        rules: state.rules,
        createdBy: state.createdBy,
        rulesUnreadable: state.rulesUnreadable,
      };
    });
  }

  members(round: RoundId): Promise<RoundMember[]> {
    return this.call("members", false, () =>
      [...(this.visible(round)?.members ?? [])].map(([userId, playerId]) => ({ userId, playerId })),
    );
  }

  playersSince(round: RoundId, rev: number): Promise<RemotePlayer[]> {
    return this.call("playersSince", false, () =>
      [...(this.visible(round)?.players.values() ?? [])].filter((it) => it.rev > rev).sort((a, b) => a.rev - b.rev),
    );
  }

  eveningsSince(round: RoundId, rev: number): Promise<RemoteEvening[]> {
    return this.call("eveningsSince", false, () =>
      [...(this.visible(round)?.evenings.values() ?? [])].filter((it) => it.rev > rev).sort((a, b) => a.rev - b.rev),
    );
  }

  evening(round: RoundId, id: EveningId): Promise<RemoteEvening | null> {
    return this.call("evening", false, () => this.visible(round)?.evenings.get(id) ?? null);
  }

  saveEvening(round: RoundId, evening: Evening, expectedVersion: number, deleted = false): Promise<SavedEvening | null> {
    return this.call("saveEvening", true, () => {
      const state = this.member(round);
      const current = state.evenings.get(evening.id);
      if (evening.id.length < 1 || evening.id.length > MAX_ID_LENGTH) throw new RoundException("INVALID_DOC");
      if (evening.entries.length > MAX_ENTRIES) throw new RoundException("TOO_LARGE");
      if (current === undefined && expectedVersion !== 0) return null;
      if (current !== undefined && current.version !== expectedVersion) return null;
      if (current === undefined && state.evenings.size >= MAX_EVENINGS) throw new RoundException("LIMIT_REACHED");
      const row: RemoteEvening = { id: evening.id, version: expectedVersion + 1, deleted, rev: state.nextRev(), evening };
      state.evenings.set(evening.id, row);
      return { version: row.version, rev: row.rev };
    });
  }

  savePlayer(round: RoundId, player: Player, deleted = false): Promise<number> {
    return this.call("savePlayer", true, () => {
      const state = this.member(round);
      if (player.id.length < 1 || player.id.length > MAX_ID_LENGTH) throw new RoundException("INVALID_DOC");
      const current = state.players.get(player.id);
      if (deleted) {
        if (current !== undefined && !current.deleted) {
          if (current.owner !== null) throw new RoundException("PLAYER_OWNED");
          state.players.set(player.id, { ...current, deleted: true, rev: state.nextRev() });
        }
        return state.rev;
      }
      const name = cleanName(player.name);
      if (name === null) throw new RoundException("INVALID_NAME");
      const colorIndex = floorMod(player.colorIndex, COLOR_COUNT);
      if (current !== undefined && !current.deleted) {
        if (name !== current.name) {
          if (current.nameLocked && state.createdBy !== this.userId) throw new RoundException("NAME_LOCKED");
          state.requireFreeName(name, player.id);
        }
        state.players.set(player.id, { ...current, name, colorIndex, archived: player.archived, rev: state.nextRev() });
      } else {
        if (state.activePlayers() >= MAX_PLAYERS) throw new RoundException("LIMIT_REACHED");
        state.requireFreeName(name, player.id);
        state.players.set(player.id, {
          id: player.id,
          name,
          colorIndex,
          archived: player.archived,
          position: current?.position ?? state.nextPosition(),
          owner: null,
          nameLocked: false,
          deleted: false,
          rev: state.nextRev(),
        });
      }
      return state.rev;
    });
  }

  saveRules(round: RoundId, rules: RuleSet): Promise<number> {
    return this.call("saveRules", true, () => {
      const state = this.member(round);
      if (rules.name.length > MAX_RULES_NAME_LENGTH) throw new RoundException("TOO_LARGE");
      state.rules = rules;
      state.rulesUnreadable = false;
      return state.nextRev();
    });
  }

  myRounds(): Promise<RoundSummary[]> {
    return this.call("myRounds", false, () =>
      [...this.server.rounds.values()]
        .filter((it) => it.members.has(this.userId))
        .map((it) => ({
          id: it.id,
          name: it.name,
          rev: it.rev,
          memberCount: it.members.size,
          myPlayerId: it.members.get(this.userId) ?? null,
          isCreator: it.createdBy === this.userId,
        })),
    );
  }

  createRound(name: string): Promise<RoundId> {
    return this.call("createRound", true, () => {
      const clean = kotlinTrim(name);
      if (clean.length < 1 || clean.length > MAX_ROUND_NAME_LENGTH) throw new RoundException("INVALID_NAME");
      this.requireRoomForRound();
      const round = new Round(this.server.roundId(this.server.nextNumber()), clean, this.userId);
      // Das Anlegen mit dem ersten Mitglied ist die erste Änderung der Runde.
      round.rev = 1;
      round.members.set(this.userId, null);
      this.server.rounds.set(round.id, round);
      return round.id;
    });
  }

  createInvite(round: RoundId): Promise<string> {
    return this.call("createInvite", true, () => {
      const state = this.member(round);
      const now = this.server.now();
      for (const [token, expires] of [...state.invites]) if (expires <= now) state.invites.delete(token);
      if (state.invites.size >= MAX_INVITES) throw new RoundException("LIMIT_REACHED");
      const token = this.server.nextNumber().toString(16).padStart(32, "0");
      state.invites.set(token, now + INVITE_LIFETIME_MILLIS);
      return token;
    });
  }

  inviteInfo(token: string): Promise<InviteInfo> {
    return this.call("inviteInfo", false, () => {
      const state = this.invited(token);
      return { roundId: state.id, roundName: state.name, memberCount: state.members.size, alreadyMember: state.members.has(this.userId) };
    });
  }

  joinRound(token: string): Promise<RoundId> {
    return this.call("joinRound", true, () => {
      const state = this.invited(token);
      if (!state.members.has(this.userId)) {
        if (state.members.size >= MAX_MEMBERS) throw new RoundException("LIMIT_REACHED");
        this.requireRoomForRound();
        // Wer schon einmal dabei war, bekommt seinen Spieler zurück.
        const own = [...state.players.values()].find((it) => it.owner === this.userId && !it.deleted);
        state.members.set(this.userId, own?.id ?? null);
        state.nextRev();
      }
      return state.id;
    });
  }

  leaveRound(round: RoundId): Promise<void> {
    return this.call("leaveRound", true, () => {
      const state = this.member(round);
      state.members.delete(this.userId);
      state.nextRev();
      if (state.members.size === 0) this.server.rounds.delete(round);
    });
  }

  claimPlayer(round: RoundId, player: PlayerId, name: string, colorIndex: number): Promise<void> {
    return this.call("claimPlayer", true, () => {
      const state = this.member(round);
      const mine = state.members.get(this.userId) ?? null;
      // Derselbe Spieler noch einmal ist erlaubt (zum Beispiel nach einer verlorenen Antwort).
      if (mine !== null && mine !== player) throw new RoundException("ALREADY_CLAIMED");
      const row = state.players.get(player);
      const current = row !== undefined && !row.deleted ? row : null;
      if (current !== null && current.owner !== null && current.owner !== this.userId) throw new RoundException("PLAYER_TAKEN");
      const color = floorMod(colorIndex, COLOR_COUNT);
      if (current !== null && current.owner === this.userId) {
        // Gehörte der Spieler dem Konto schon, bleibt sein Name.
        state.players.set(player, { ...current, nameLocked: true, colorIndex: color, rev: state.nextRev() });
      } else if (current !== null) {
        const clean = state.freeName(name, player);
        state.players.set(player, { ...current, name: clean, owner: this.userId, nameLocked: true, colorIndex: color, rev: state.nextRev() });
      } else {
        if (state.activePlayers() >= MAX_PLAYERS) throw new RoundException("LIMIT_REACHED");
        state.players.set(player, {
          id: player,
          name: state.freeName(name, player),
          colorIndex: color,
          archived: false,
          position: row?.position ?? state.nextPosition(),
          owner: this.userId,
          nameLocked: true,
          deleted: false,
          rev: state.nextRev(),
        });
      }
      state.members.set(this.userId, player);
    });
  }

  private invited(token: string): Round {
    const now = this.server.now();
    for (const round of this.server.rounds.values()) {
      const expires = round.invites.get(token);
      if (expires !== undefined && expires > now) return round;
    }
    throw new RoundException("INVALID_INVITE");
  }

  private requireRoomForRound(): void {
    let count = 0;
    for (const round of this.server.rounds.values()) if (round.members.has(this.userId)) count++;
    if (count >= MAX_ROUNDS) throw new RoundException("LIMIT_REACHED");
  }
}
