/*
 * Die Anbindung der Web-App an den Server der gemeinsamen Runde gegen ein **echtes** Supabase: den lokalen Stack der
 * Supabase CLI, den der Workflow „Supabase“ auf GitHub startet. Geprüft wird, was sich mit einem nachgestellten Server
 * nicht prüfen lässt: wie die Datenschnittstelle (PostgREST) und der Anmelde-Server wirklich antworten und ob
 * supabase-js und unsere Anbindung das richtig lesen. Entspricht `SupabaseLiveTest.kt` der Android-App.
 *
 * Läuft nur, wenn diese Umgebungsvariablen gesetzt sind (sonst wird alles übersprungen, `npm test` bleibt, wie es ist):
 * - `SKT_LIVE_URL`: Adresse des Stacks, zum Beispiel `http://127.0.0.1:54321`
 * - `SKT_LIVE_KEY`: der öffentliche Schlüssel (anon bzw. Publishable Key)
 * - `SKT_LIVE_ADMIN_KEY`: der Verwaltungsschlüssel des **lokalen** Stacks, nur um Testkonten anzulegen
 *
 * Aufruf: `npm run test:live`. Testkonten melden sich mit E-Mail und Passwort an (dafür schaltet der Workflow diese
 * Anmeldung im lokalen Stack ein); die Anmeldung mit Google selbst lässt sich so nicht prüfen. Insgesamt melden sich
 * fünf Konten an (der Anmelde-Server erlaubt 30 Anmeldungen in fünf Minuten).
 */
import type { SupabaseClient } from "@supabase/supabase-js";
import { beforeAll, describe, expect, it } from "vitest";
import { append, remove } from "../../core/evening/Evening";
import { instantFromEpochMillis } from "../../core/instant";
import type { EntryId, Evening, EveningId, GameEntry, Player, PlayerId } from "../../core/model";
import { sameEvening, sameEveningOrNull, sameRulesOrNull } from "../../core/round/equality";
import {
  InMemoryEveningEditor,
  InMemoryEveningRepository,
  InMemoryPlayerRepository,
  InMemorySettingsRepository,
} from "../../core/round/InMemoryRepositories";
import { InMemoryRoundStore } from "../../core/round/InMemoryRound";
import { InviteLinks } from "../../core/round/InviteLinks";
import { RoundConnection } from "../../core/round/RoundConnection";
import { RoundException, type RoundError, type RoundId } from "../../core/round/RoundModels";
import type { IdGenerator, RoundApi } from "../../core/round/RoundPorts";
import { RoundSync, SyncOutcome, type Synced } from "../../core/round/RoundSync";
import { RuleSets } from "../../core/rules/RuleSet";
import type { RoundAuth } from "../backend";
import { readRoundConfig } from "../config";
import { MemoryStorage } from "../testing/memoryStorage";
import { AUTH_STORAGE_KEY, connectSupabase } from "./index";

const ADDRESS = (process.env.SKT_LIVE_URL ?? "").trim().replace(/\/+$/, "");
const KEY = (process.env.SKT_LIVE_KEY ?? "").trim();
const ADMIN_KEY = (process.env.SKT_LIVE_ADMIN_KEY ?? "").trim();
const LIVE = ADDRESS !== "" && KEY !== "" && ADMIN_KEY !== "";

/** Über `npm run test:live` gestartet: Dann müssen die Angaben da sein, sonst liefe die Probe stillschweigend nicht. */
const REQUIRED = process.env.npm_lifecycle_event === "test:live";

/** Die Rückkehr-Adresse der Anmeldung in dieser Probe (steht in der Liste des lokalen Stacks). */
const START_URL = "https://q0ochen.github.io/SchafkopfTracker/";

// --- Testkonten ---

/** Die Leitung eines Kontos zum Server: zählt, wie oft die Sitzung erneuert wird, und kann ein Zugriffstoken verderben. */
class Wire {
  refreshes = 0;

  /** Die nächste Anfrage an die Datenschnittstelle geht mit einem unbrauchbaren Zugriffstoken hinaus. */
  spoilNext = false;

  readonly fetch: typeof fetch = async (input, init) => {
    const address = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(address);
    if (url.pathname === "/auth/v1/token" && url.searchParams.get("grant_type") === "refresh_token") this.refreshes++;
    if (this.spoilNext && url.pathname.startsWith("/rest/v1/")) {
      this.spoilNext = false;
      const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
      headers.set("Authorization", "Bearer kein.gueltiges.token");
      return fetch(input, { ...init, headers });
    }
    return fetch(input, init);
  };
}

interface TestAccount {
  readonly userId: string;
  readonly email: string;
  readonly api: RoundApi;
  readonly auth: RoundAuth;
  readonly client: SupabaseClient;
  readonly storage: MemoryStorage;
  readonly wire: Wire;
  /** Adressen, zu denen die Anmeldung den Browser schicken wollte. */
  readonly opened: string[];
}

/** Ein Client, wie ihn die App baut, mit eigenem Speicher im Arbeitsspeicher. */
function connect(storage: MemoryStorage, wire: Wire, opened: string[]) {
  const config = readRoundConfig(ADDRESS, KEY);
  if (config === null) throw new Error("SKT_LIVE_URL ist keine Adresse, mit der die App gebaut werden könnte (https, oder http auf dem eigenen Rechner).");
  return connectSupabase(config, {
    redirectTo: START_URL,
    openUrl: (url) => opened.push(url),
    storage,
    fetch: wire.fetch,
    autoRefresh: false,
  });
}

/** Legt ein neues Konto an (über die Verwaltung des lokalen Stacks) und meldet es mit dem Client der App an. */
async function newAccount(): Promise<TestAccount> {
  const email = `probe-web-${crypto.randomUUID()}@example.org`;
  const password = crypto.randomUUID();
  const created = await fetch(`${ADDRESS}/auth/v1/admin/users`, {
    method: "POST",
    headers: { apikey: ADMIN_KEY, Authorization: `Bearer ${ADMIN_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, email_confirm: true }),
  });
  if (!created.ok) throw new Error(`Testkonto anlegen: HTTP ${created.status}`);
  const user = (await created.json()) as { id?: unknown };
  if (typeof user.id !== "string") throw new Error("Testkonto anlegen: Antwort ohne Kennung");

  const storage = new MemoryStorage();
  const wire = new Wire();
  const opened: string[] = [];
  const { backend, client } = connect(storage, wire, opened);
  const signedIn = await client.auth.signInWithPassword({ email, password });
  if (signedIn.error) throw new Error(`Testkonto anmelden: ${signedIn.error.message}`);
  return { userId: user.id, email, api: backend.api, auth: backend.auth, client, storage, wire, opened };
}

/** Der Fehler, mit dem `block` scheitert; `null`, wenn er durchläuft. */
async function errorOf(block: () => Promise<unknown>): Promise<RoundError | null> {
  try {
    await block();
    return null;
  } catch (error) {
    if (error instanceof RoundException) return error.error;
    throw error;
  }
}

/** Verlässt die Runde, wenn das Konto ihr (noch) angehört: Jedes Konto darf nur fünf Runden haben. */
async function leaveQuietly(account: TestAccount, round: RoundId | null): Promise<void> {
  if (round === null) return;
  try {
    await account.api.leaveRound(round);
  } catch {
    // Schon draußen, oder die Runde gibt es nicht mehr.
  }
}

// --- Testdaten ---

const SEATING: readonly PlayerId[] = ["spieler-1", "spieler-2", "spieler-3", "spieler-4"];
const PLACEHOLDERS: readonly Player[] = SEATING.map((id, index) => ({ id, name: `Spieler ${index + 1}`, colorIndex: index, archived: false }));
const START_MILLIS = Date.parse("2026-12-12T18:00:00Z");

function game(id: string, seconds: number, note = ""): GameEntry {
  return {
    kind: "game",
    id,
    // Mit Bruchteilen von Sekunden: Die müssen den Weg über den Server überstehen.
    recordedAt: instantFromEpochMillis(START_MILLIS + seconds * 1000 + 250),
    type: "RUFSPIEL",
    suit: null,
    declarer: SEATING[0],
    partner: SEATING[1],
    declarerWon: true,
    level: "NORMAL",
    laufende: 3,
    announcement: "NONE",
    klopfer: [],
    kontra: "NONE",
    kontraBy: null,
    retourBy: null,
    bockOverride: "AUTO",
    declarerEyes: null,
    note,
  };
}

function evening(id: string, ...entries: GameEntry[]): Evening {
  return {
    id,
    startedAt: instantFromEpochMillis(START_MILLIS),
    finishedAt: null,
    location: "Wirtshaus „Zum Ober“",
    seating: [...SEATING],
    firstDealer: SEATING[0],
    rules: RuleSets.standard(),
    entries,
  };
}

async function savePlaceholders(api: RoundApi, round: RoundId): Promise<void> {
  for (const placeholder of PLACEHOLDERS) await api.savePlayer(round, placeholder);
}

function expectSameEvening(actual: Evening | null | undefined, expected: Evening, what = "Abend"): void {
  expect(sameEveningOrNull(actual ?? null, expected), `${what} ${expected.id}`).toBe(true);
}

/** Ein nachgestelltes Handy mit eigenen Daten im Arbeitsspeicher; der Server ist der echte. */
class LiveDevice {
  readonly players = new InMemoryPlayerRepository();
  readonly evenings = new InMemoryEveningRepository();
  readonly settings = new InMemorySettingsRepository();
  readonly store = new InMemoryRoundStore();
  readonly editor: InMemoryEveningEditor;
  readonly sync: RoundSync;
  readonly connection: RoundConnection;
  private counter = 0;

  constructor(readonly account: TestAccount) {
    const ids: IdGenerator = { newId: () => `${account.userId.slice(0, 8)}-${++this.counter}` };
    this.editor = new InMemoryEveningEditor(this.evenings, { now: () => instantFromEpochMillis(Date.now()) }, ids);
    this.sync = new RoundSync(account.api, this.store, this.players, this.evenings, this.settings, this.editor);
    this.connection = new RoundConnection(account.api, this.store, this.players, this.evenings, this.sync, ids);
  }

  async synced(): Promise<Synced> {
    const outcome = await this.sync.sync();
    if (outcome.kind !== "Synced") throw new Error(`Abgleich: ${JSON.stringify(outcome)}`);
    return outcome;
  }

  async addGame(eveningId: EveningId): Promise<EntryId> {
    const id = this.editor.newEntryId();
    await this.editor.update(eveningId, (it) => append(it, { ...game(id, 0), recordedAt: this.editor.now(), laufende: 0 }));
    return id;
  }

  async allPlayers(): Promise<Player[]> {
    return [...(await this.players.players())].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  async allEvenings(): Promise<Evening[]> {
    return [...(await this.evenings.evenings())].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }
}

function expectSynced(outcome: SyncOutcome, what: string): void {
  expect(outcome.kind, `${what}: ${JSON.stringify(outcome)}`).toBe("Synced");
}

// --- Die Probe ---

it.runIf(REQUIRED)("die Probe hat Adresse und Schlüssel des Servers bekommen", () => {
  expect(ADDRESS, "SKT_LIVE_URL").not.toBe("");
  expect(KEY, "SKT_LIVE_KEY").not.toBe("");
  expect(ADMIN_KEY, "SKT_LIVE_ADMIN_KEY").not.toBe("");
});

describe.skipIf(!LIVE)("die Anbindung gegen ein echtes Supabase", () => {
  let one: TestAccount;
  let two: TestAccount;
  let stranger: TestAccount;

  beforeAll(async () => {
    one = await newAccount();
    two = await newAccount();
    stranger = await newAccount();
  });

  // --- Anmeldung ---

  it("die Anmeldung: Konto, erneuertes Zugriffstoken, Abmelden", async () => {
    const account = await newAccount();

    // Nach der Anmeldung kennt die App das Konto, und die Sitzung liegt in ihrem Speicher.
    expect(await account.auth.load()).toEqual({ userId: account.userId, email: account.email });
    expect(account.auth.account()).toEqual({ userId: account.userId, email: account.email });
    expect(account.storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    expect(await account.api.myRounds()).toEqual([]);
    expect(account.wire.refreshes).toBe(0);

    // Lehnt der Server das Zugriffstoken ab (Antwort 401), wird die Sitzung genau einmal erneuert und die Anfrage wiederholt.
    account.wire.spoilNext = true;
    expect(await account.api.myRounds()).toEqual([]);
    expect(account.wire.refreshes).toBe(1);
    expect(account.auth.account()?.userId).toBe(account.userId);

    // Ein zweites Gerät mit derselben Sitzung (eine Kopie des Speichers), bevor sich das erste abmeldet.
    const copy = new MemoryStorage();
    copy.setItem(AUTH_STORAGE_KEY, account.storage.getItem(AUTH_STORAGE_KEY) ?? "");
    const copyWire = new Wire();
    const other = connect(copy, copyWire, []).backend;
    expect((await other.auth.load())?.userId).toBe(account.userId);
    expect(await other.api.myRounds()).toEqual([]);

    await account.auth.signOut();

    expect(account.auth.account()).toBeNull();
    expect(account.storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    // Ohne Anmeldung wird der Server gar nicht erst gefragt.
    expect(await errorOf(() => account.api.myRounds())).toBe("SIGNED_OUT");

    // Der Server kennt die Sitzung nicht mehr: Kommt das zweite Gerät mit seinem Zugriffstoken nicht mehr durch und
    // will erneuern, gilt es als endgültig abgemeldet (nicht als Störung) und vergisst die Sitzung.
    copyWire.spoilNext = true;
    expect(await errorOf(() => other.api.myRounds())).toBe("SIGNED_OUT");
    expect(copyWire.refreshes).toBe(1);
    expect(other.auth.account()).toBeNull();
    expect(copy.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("die Adresse der Anmeldung führt zu Google", async () => {
    const opened: string[] = [];
    const { backend } = connect(new MemoryStorage(), new Wire(), opened);

    await backend.auth.signIn();

    expect(opened).toHaveLength(1);
    const address = new URL(opened[0]);
    expect(`${address.origin}${address.pathname}`).toBe(`${ADDRESS}/auth/v1/authorize`);
    expect(address.searchParams.get("provider")).toBe("google");
    expect(address.searchParams.get("redirect_to")).toBe(START_URL);
    expect(address.searchParams.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(address.searchParams.get("code_challenge_method")?.toLowerCase()).toBe("s256");
    // Der Anmelde-Server nimmt die Angaben an und schickt den Browser weiter zu Google. Der Workflow hinterlegt dafür
    // im lokalen Stack erfundene Zugangsdaten; angemeldet wird hier niemand. Wie der Browser: ohne eigene Kopfzeilen.
    const response = await fetch(opened[0], { redirect: "manual" });
    expect(response.status, "Weiterleitung").toBeGreaterThanOrEqual(300);
    expect(response.status, "Weiterleitung").toBeLessThan(400);
    expect((response.headers.get("location") ?? "").startsWith("https://accounts.google.com/")).toBe(true);
  });

  // --- Runde, Einladung, Mitglieder, Spieler ---

  it("eine Runde anlegen, einladen, beitreten und den eigenen Namen festlegen", async () => {
    let round: RoundId | null = null;
    try {
      round = await one.api.createRound("  Stammtisch   am  Freitag ");
      const id = round;

      expect(id).toMatch(/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
      expect(await one.api.head(id)).toEqual({
        id,
        name: "Stammtisch am Freitag",
        rev: 1,
        rules: null,
        createdBy: one.userId,
        rulesUnreadable: false,
      });
      const mine = (await one.api.myRounds()).filter((it) => it.id === id);
      expect(mine).toEqual([{ id, name: "Stammtisch am Freitag", rev: 1, memberCount: 1, myPlayerId: null, isCreator: true, memberNames: [] }]);
      expect(await one.api.members(id)).toEqual([{ userId: one.userId, playerId: null }]);

      // Wer nicht dazugehört, sieht nichts und darf nichts.
      expect(await stranger.api.head(id)).toBeNull();
      expect(await stranger.api.members(id)).toEqual([]);
      expect(await stranger.api.playersSince(id, 0)).toEqual([]);
      expect(await stranger.api.eveningsSince(id, 0)).toEqual([]);
      expect(await stranger.api.evening(id, "abend-x")).toBeNull();
      expect(await errorOf(() => stranger.api.createInvite(id))).toBe("NOT_MEMBER");
      expect(await errorOf(() => stranger.api.savePlayer(id, { id: "x", name: "Fremder", colorIndex: 0, archived: false }))).toBe("NOT_MEMBER");
      expect(await errorOf(() => stranger.api.saveEvening(id, evening("abend-x"), 0))).toBe("NOT_MEMBER");
      expect(await errorOf(() => stranger.api.saveRules(id, RuleSets.standard()))).toBe("NOT_MEMBER");
      expect(await errorOf(() => stranger.api.claimPlayer(id, "x", "Fremder", 0))).toBe("NOT_MEMBER");
      expect(await errorOf(() => stranger.api.leaveRound(id))).toBe("NOT_MEMBER");

      // Einladen und beitreten.
      const token = await one.api.createInvite(id);
      expect(token).toMatch(/^[0-9a-f]{32}$/);
      expect(InviteLinks.tokenFrom(InviteLinks.webLink(START_URL, token))).toBe(token);
      expect(await two.api.inviteInfo(token)).toEqual({ roundId: id, roundName: "Stammtisch am Freitag", memberCount: 1, alreadyMember: false });
      expect((await one.api.inviteInfo(token)).alreadyMember).toBe(true);
      expect(await errorOf(() => two.api.inviteInfo("0".repeat(32)))).toBe("INVALID_INVITE");
      expect(await errorOf(() => two.api.joinRound("kein Code"))).toBe("INVALID_INVITE");

      expect(await two.api.joinRound(token)).toBe(id);
      // Ein zweites Mal beitreten ändert nichts.
      expect(await two.api.joinRound(token)).toBe(id);

      expect((await two.api.head(id))?.rev).toBe(2);
      expect(await one.api.members(id)).toEqual([
        { userId: one.userId, playerId: null },
        { userId: two.userId, playerId: null },
      ]);
      expect((await two.api.myRounds()).filter((it) => it.id === id)).toEqual([
        { id, name: "Stammtisch am Freitag", rev: 2, memberCount: 2, myPlayerId: null, isCreator: false, memberNames: [] },
      ]);

      // Spieler anlegen: Der Name wird bereinigt, die Reihenfolge gezählt.
      expect(await one.api.savePlayer(id, { id: SEATING[0], name: "  Nord  ", colorIndex: 0, archived: false })).toBe(3);
      expect(await one.api.savePlayer(id, { id: SEATING[1], name: "Spieler 2", colorIndex: 1, archived: false })).toBe(4);
      expect(await two.api.playersSince(id, 0)).toEqual([
        { id: SEATING[0], name: "Nord", colorIndex: 0, archived: false, position: 1, owner: null, nameLocked: false, deleted: false, rev: 3 },
        { id: SEATING[1], name: "Spieler 2", colorIndex: 1, archived: false, position: 2, owner: null, nameLocked: false, deleted: false, rev: 4 },
      ]);
      expect((await two.api.playersSince(id, 3)).map((it) => it.id)).toEqual([SEATING[1]]);
      const third = (name: string): Player => ({ id: SEATING[2], name, colorIndex: 2, archived: false });
      expect(await errorOf(() => two.api.savePlayer(id, third("nord")))).toBe("NAME_TAKEN");
      expect(await errorOf(() => two.api.savePlayer(id, third("   ")))).toBe("INVALID_NAME");
      expect(await errorOf(() => two.api.savePlayer(id, third("x".repeat(25))))).toBe("INVALID_NAME");
      // Abgelehntes zählt nicht.
      expect((await one.api.head(id))?.rev).toBe(4);

      // „Das bin ich“: Das zweite Konto übernimmt den zweiten Spieler und legt seinen Namen fest.
      await two.api.claimPlayer(id, SEATING[1], " Ost ", 5);

      expect(await one.api.members(id)).toEqual([
        { userId: one.userId, playerId: null },
        { userId: two.userId, playerId: SEATING[1] },
      ]);
      expect(await one.api.playersSince(id, 4)).toEqual([
        { id: SEATING[1], name: "Ost", colorIndex: 5, archived: false, position: 2, owner: two.userId, nameLocked: true, deleted: false, rev: 5 },
      ]);
      // member_names (seit dem 21.11.2026): die Namen der übernommenen Spieler, hier nur der des zweiten Kontos.
      expect((await two.api.myRounds()).filter((it) => it.id === id)).toEqual([
        { id, name: "Stammtisch am Freitag", rev: 5, memberCount: 2, myPlayerId: SEATING[1], isCreator: false, memberNames: ["Ost"] },
      ]);
      // Derselbe Aufruf noch einmal (zum Beispiel nach einer verlorenen Antwort): Der Name bleibt.
      await two.api.claimPlayer(id, SEATING[1], "Anders", 6);
      expect((await one.api.playersSince(id, 5)).map((it) => it.name)).toEqual(["Ost"]);
      expect(await errorOf(() => two.api.claimPlayer(id, SEATING[0], "Ost", 0))).toBe("ALREADY_CLAIMED");
      expect(await errorOf(() => one.api.claimPlayer(id, SEATING[1], "Nord", 0))).toBe("PLAYER_TAKEN");
      expect(await errorOf(() => one.api.claimPlayer(id, "neu", "OST", 0))).toBe("NAME_TAKEN");

      // Ein fester Name bleibt; nur wer die Runde angelegt hat, darf ihn korrigieren. Gelöscht werden nur Gäste.
      expect(await errorOf(() => two.api.savePlayer(id, { id: SEATING[1], name: "Osti", colorIndex: 6, archived: false }))).toBe("NAME_LOCKED");
      await one.api.savePlayer(id, { id: SEATING[1], name: "Osten", colorIndex: 6, archived: true });
      expect(await one.api.playersSince(id, 6)).toEqual([
        { id: SEATING[1], name: "Osten", colorIndex: 6, archived: true, position: 2, owner: two.userId, nameLocked: true, deleted: false, rev: 7 },
      ]);
      expect(await errorOf(() => one.api.savePlayer(id, { id: SEATING[1], name: "Osten", colorIndex: 6, archived: false }, true))).toBe("PLAYER_OWNED");
      expect(await one.api.savePlayer(id, { id: SEATING[0], name: "Nord", colorIndex: 0, archived: false }, true)).toBe(8);
      expect((await one.api.playersSince(id, 7)).map((it) => [it.id, it.deleted])).toEqual([[SEATING[0], true]]);
      // Nichts zu löschen: rev bleibt.
      expect(await one.api.savePlayer(id, { id: "gibt-es-nicht", name: "Niemand", colorIndex: 0, archived: false }, true)).toBe(8);

      // Verlassen und wiederkommen: Der eigene Spieler ist wieder verknüpft.
      await two.api.leaveRound(id);

      expect(await two.api.head(id)).toBeNull();
      expect((await two.api.myRounds()).filter((it) => it.id === id)).toEqual([]);
      expect(await one.api.members(id)).toEqual([{ userId: one.userId, playerId: null }]);
      expect(await errorOf(() => two.api.savePlayer(id, third("Süd")))).toBe("NOT_MEMBER");
      expect(await two.api.joinRound(token)).toBe(id);
      expect((await one.api.members(id)).at(-1)).toEqual({ userId: two.userId, playerId: SEATING[1] });

      // Geht das letzte Mitglied, gibt es die Runde nicht mehr.
      await two.api.leaveRound(id);
      await one.api.leaveRound(id);
      expect(await one.api.head(id)).toBeNull();
      expect((await one.api.myRounds()).filter((it) => it.id === id)).toEqual([]);
      expect(await errorOf(() => stranger.api.joinRound(token))).toBe("INVALID_INVITE");
    } finally {
      await leaveQuietly(two, round);
      await leaveQuietly(one, round);
    }
  });

  // --- Abende und Regelwerk ---

  it("Abende werden nur mit der erwarteten Fassung ersetzt", async () => {
    let round: RoundId | null = null;
    try {
      round = await one.api.createRound("Abende");
      const id = round;
      await savePlaceholders(one.api, id);
      const first = evening("abend-1", game("spiel-1", 60, "Schneider! „Herz“ sticht – 10 € 🂱"), game("spiel-2", 120));

      expect(await one.api.saveEvening(id, first, 0)).toEqual({ version: 1, rev: 6 });

      // Der Inhalt kommt unverändert zurück, auch Umlaute, Anführungszeichen und Bruchteile von Sekunden.
      const read = await one.api.evening(id, first.id);
      expect(read).toMatchObject({ id: first.id, version: 1, deleted: false, rev: 6 });
      expectSameEvening(read?.evening, first);
      expect(read?.evening?.location).toBe("Wirtshaus „Zum Ober“");
      expect(read?.evening?.entries.map((it) => (it.kind === "game" ? it.note : ""))).toEqual(["Schneider! „Herz“ sticht – 10 € 🂱", ""]);
      expect(await one.api.evening(id, "gibt-es-nicht")).toBeNull();

      // Die erwartete Fassung passt nicht: kein Fehler, sondern nichts, und nichts ändert sich.
      const changed = append(first, game("spiel-3", 180));
      expect(await one.api.saveEvening(id, changed, 0)).toBeNull();
      expect(await one.api.saveEvening(id, changed, 2)).toBeNull();
      expect((await one.api.head(id))?.rev).toBe(6);
      expectSameEvening((await one.api.evening(id, first.id))?.evening, first);

      expect(await one.api.saveEvening(id, changed, 1)).toEqual({ version: 2, rev: 7 });
      const since = await one.api.eveningsSince(id, 6);
      expect(since.map((it) => [it.id, it.version, it.deleted, it.rev])).toEqual([[first.id, 2, false, 7]]);
      expectSameEvening(since[0].evening, changed);
      expect(await one.api.eveningsSince(id, 7)).toEqual([]);

      // Löschen ist eine Markierung; die Zeile bleibt, damit alle Geräte davon erfahren.
      expect(await one.api.saveEvening(id, changed, 2, true)).toEqual({ version: 3, rev: 8 });
      expect(await one.api.evening(id, first.id)).toMatchObject({ deleted: true, version: 3 });

      // Was der Server nicht annimmt, trifft nur diesen einen Abend: ein Nullzeichen (die Datenbank kann es nicht
      // speichern) und ein Dokument über 300 000 Bytes.
      const withNul = evening("abend-nul", game("spiel-nul", 60, "a\u0000b"));
      expect(await errorOf(() => one.api.saveEvening(id, withNul, 0))).toBe("INVALID_DOC");
      expect(await errorOf(() => one.api.savePlayer(id, { id: "gast-nul", name: "Ga\u0000st", colorIndex: 4, archived: false }))).toBe("INVALID_DOC");
      const tooLarge = evening("abend-gross", game("spiel-gross", 60, "x".repeat(300_001)));
      expect(await errorOf(() => one.api.saveEvening(id, tooLarge, 0))).toBe("TOO_LARGE");
      expect((await one.api.head(id))?.rev).toBe(8);
      // Knapp darunter geht es.
      const large = evening("abend-fast-zu-gross", game("spiel-fast", 60, "x".repeat(290_000)));
      expect(await one.api.saveEvening(id, large, 0)).toEqual({ version: 1, rev: 9 });
      expectSameEvening((await one.api.evening(id, large.id))?.evening, large);
    } finally {
      await leaveQuietly(one, round);
    }
  });

  it("viele Abende werden seitenweise und vollständig gelesen", async () => {
    let round: RoundId | null = null;
    try {
      round = await one.api.createRound("Viele Abende");
      const id = round;
      const count = 205;
      for (let index = 0; index < count; index++) {
        const eveningId = `abend-${String(index).padStart(3, "0")}`;
        expect(await one.api.saveEvening(id, evening(eveningId, game(`spiel-${eveningId}`, 60)), 0), eveningId).not.toBeNull();
      }

      const all = await one.api.eveningsSince(id, 0);

      expect(all).toHaveLength(count);
      expect(new Set(all.map((it) => it.id)).size).toBe(count);
      expect(all.map((it) => it.rev)).toEqual(Array.from({ length: count }, (_, index) => index + 2));
      expect(all.every((it) => it.evening !== null && it.version === 1 && !it.deleted)).toBe(true);
      expect(await one.api.eveningsSince(id, count - 4)).toHaveLength(5);
    } finally {
      await leaveQuietly(one, round);
    }
  });

  it("das Regelwerk der Runde wird gespeichert und gelesen", async () => {
    let round: RoundId | null = null;
    try {
      round = await two.api.createRound("Regeln");
      const id = round;

      expect(await two.api.saveRules(id, RuleSets.sauspiel())).toBe(2);

      const head = await two.api.head(id);
      expect(sameRulesOrNull(head?.rules ?? null, RuleSets.sauspiel())).toBe(true);
      expect(head?.rulesUnreadable).toBe(false);
      expect(head?.rev).toBe(2);
      expect(await two.api.saveRules(id, RuleSets.standard())).toBe(3);
      expect(sameRulesOrNull((await two.api.head(id))?.rules ?? null, RuleSets.standard())).toBe(true);
      // Auch hier trifft ein Nullzeichen nur dieses eine Stück.
      expect(await errorOf(() => two.api.saveRules(id, { ...RuleSets.standard(), name: "Haus\u0000regeln" }))).toBe("INVALID_DOC");
      expect((await two.api.head(id))?.rev).toBe(3);
    } finally {
      await leaveQuietly(two, round);
    }
  });

  it("mehr als fünf Runden je Konto und doppelte Namen lehnt der Server ab", async () => {
    const account = await newAccount();
    const rounds: RoundId[] = [];
    try {
      expect(await errorOf(() => account.api.createRound("   "))).toBe("INVALID_NAME");
      expect(await errorOf(() => account.api.createRound("x".repeat(41)))).toBe("INVALID_NAME");
      for (let index = 0; index < 5; index++) rounds.push(await account.api.createRound(`Runde ${index + 1}`));

      // Seit dem 21.11.2026: ein Name je Konto, ohne Groß/Klein, geprüft vor der Obergrenze.
      expect(await errorOf(() => account.api.createRound("  RUNDE   1 "))).toBe("NAME_TAKEN");
      expect(await errorOf(() => account.api.createRound("Runde 6"))).toBe("LIMIT_REACHED");
      expect((await account.api.myRounds()).map((it) => it.name)).toEqual(["Runde 1", "Runde 2", "Runde 3", "Runde 4", "Runde 5"]);
    } finally {
      for (const round of rounds) await leaveQuietly(account, round);
    }
  });

  // --- Der ganze Abgleich mit zwei Geräten ---

  it("zwei Geräte gleichen sich über den echten Server ab", async () => {
    const first = new LiveDevice(one);
    const second = new LiveDevice(two);
    let round: RoundId | null = null;
    try {
      // Das erste Handy hat schon einen Abend und bringt ihn mit in die neue Runde.
      for (const placeholder of PLACEHOLDERS) await first.players.save(placeholder);
      const table = evening("abend-live", game("spiel-1", 60));
      await first.evenings.save(table);
      expectSynced(await first.connection.create("Runde am Tisch", one.userId, PLACEHOLDERS), "Runde anlegen");
      round = (await first.store.link())?.roundId ?? null;
      const id = round ?? "";
      expect(await first.sync.pendingCount()).toBe(0);
      expectSameEvening((await one.api.evening(id, table.id))?.evening, table);
      expect(await one.api.playersSince(id, 0)).toHaveLength(4);

      // Das zweite Handy hat eigene Daten; sie werden durch die der Runde ersetzt.
      for (const placeholder of PLACEHOLDERS) await second.players.save(placeholder);
      await second.evenings.save(evening("abend-eigener"));
      const token = await one.api.createInvite(id);
      expectSynced(await second.connection.join(token, two.userId), "Beitreten");

      const joined = await second.allEvenings();
      expect(joined.map((it) => it.id)).toEqual([table.id]);
      expectSameEvening(joined[0], table);
      expect(await second.allPlayers()).toEqual(await first.allPlayers());
      expect(await second.sync.pendingCount()).toBe(0);

      // „Das bin ich“ auf beiden Handys: Die festen Namen erscheinen überall.
      expectSynced(await first.connection.claim(SEATING[0], "Nord"), "Das bin ich (eins)");
      expectSynced(await second.connection.claim(SEATING[1], "Ost"), "Das bin ich (zwei)");
      await first.synced();
      expect((await first.allPlayers()).map((it) => it.name)).toEqual(["Nord", "Ost", "Spieler 3", "Spieler 4"]);
      expect(await second.allPlayers()).toEqual(await first.allPlayers());
      expect((await second.store.link())?.playerId).toBe(SEATING[1]);
      expect((await first.store.link())?.members).toEqual([
        { userId: one.userId, playerId: SEATING[0] },
        { userId: two.userId, playerId: SEATING[1] },
      ]);

      // Beide tragen gleichzeitig ein; nach dem Abgleich haben beide dasselbe, in derselben Reihenfolge.
      const fromFirst = await first.addGame(table.id);
      const fromSecond = await second.addGame(table.id);
      await first.synced();
      await second.synced();
      await first.synced();
      const merged = await first.evenings.evening(table.id);
      expect(new Set(merged?.entries.map((it) => it.id))).toEqual(new Set(["spiel-1", fromFirst, fromSecond]));
      const other = await second.evenings.evening(table.id);
      expect(merged !== null && other !== null && sameEvening(merged, other)).toBe(true);
      expect(other?.entries.map((it) => it.id)).toEqual(merged?.entries.map((it) => it.id));

      // Löschen und ein neuer Abend wandern auch hinüber.
      await second.editor.update(table.id, (it) => remove(it, fromFirst));
      await second.evenings.save(evening("abend-zwei", game("spiel-zwei", 60)));
      await second.synced();
      await first.synced();
      const here = await first.allEvenings();
      const there = await second.allEvenings();
      expect(here.map((it) => it.id)).toEqual(["abend-live", "abend-zwei"]);
      expect(there.map((it) => it.id)).toEqual(here.map((it) => it.id));
      here.forEach((it, index) => expectSameEvening(there[index], it, "auf beiden Handys"));
      expect((await first.evenings.evening(table.id))?.entries.some((it) => it.id === fromFirst)).toBe(false);

      // Ruhe: Ohne Änderung meldet der schnelle Abgleich „nichts Neues“, und nichts wartet.
      expect(await first.sync.sync(true)).toEqual(SyncOutcome.Unchanged);
      expect(await second.sync.sync(true)).toEqual(SyncOutcome.Unchanged);
      expect(await first.sync.pendingCount()).toBe(0);
      expect(await second.sync.pendingCount()).toBe(0);

      // Wer die Runde verlässt, behält seine Daten als Kopie; der andere erfährt davon.
      await second.connection.leave();
      expect(await second.store.link()).toBeNull();
      expect(await second.allEvenings()).toHaveLength(2);
      await first.synced();
      expect((await first.store.link())?.members).toEqual([{ userId: one.userId, playerId: SEATING[0] }]);
      expect(await second.sync.sync()).toEqual(SyncOutcome.NotLinked);
    } finally {
      await leaveQuietly(two, round);
      await leaveQuietly(one, round);
    }
  });
});
