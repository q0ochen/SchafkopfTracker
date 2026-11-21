/*
 * Die Anbindung an Supabase gegen ein nachgestelltes Projekt (kein Netz): Geprüft werden genau die Anfragen aus
 * docs/ARCHITEKTUR.md, Abschnitt 8.3 (Tabellen, Spalten, Filter, Namen der Funktionen und ihrer Parameter), das Lesen
 * der Antworten und die Abbildung der Fehler – mit dem echten supabase-js, an dem nur `fetch` ausgetauscht ist.
 */
import { describe, expect, it } from "vitest";
import type { Evening, ManualEntry } from "../../core/model";
import { sameEvening, sameRules } from "../../core/round/equality";
import { InMemoryRoundServer } from "../../core/round/InMemoryRound";
import { RoundException, type RoundError, type RoundId } from "../../core/round/RoundModels";
import type { RoundApi } from "../../core/round/RoundPorts";
import { SyncOutcome } from "../../core/round/RoundSync";
import { after, Device, errorOf, evening, game, P1, P2, P3, P4, PLACEHOLDERS, player, TestClock } from "../../core/round/testing/fixtures";
import { RuleSets } from "../../core/rules/RuleSet";
import { FakeSupabase, fakeFetch, NETWORK_FAILURE, type FakeAccount, type FakeRequest } from "../testing/fakeSupabase";
import { MemoryStorage } from "../testing/memoryStorage";
import { AUTH_STORAGE_KEY, createSupabaseBackend } from "./index";

const SUPABASE = "https://projekt.supabase.co";
const KEY = "sb_publishable_nur_fuer_tests";
const START_URL = "https://example.org/SchafkopfTracker/";
const ONE: FakeAccount = { userId: "konto-1", email: "konto-1@example.org" };
const TWO: FakeAccount = { userId: "konto-2", email: "konto-2@example.org" };

/** Ein Gerät mit eigener Anmeldung am nachgestellten Projekt. */
function client(fake: FakeSupabase, account: FakeAccount | null = ONE, timeoutMillis?: number) {
  const storage = new MemoryStorage();
  if (account !== null) storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(fake.issueSession(account)));
  const opened: string[] = [];
  const backend = createSupabaseBackend(
    { url: SUPABASE, publishableKey: KEY },
    { redirectTo: START_URL, openUrl: (url) => opened.push(url), storage, fetch: fakeFetch(fake), autoRefresh: false, timeoutMillis },
  );
  return { api: backend.api, auth: backend.auth, storage, opened };
}

/** Die letzte Anfrage an die Datenschnittstelle. */
function last(fake: FakeSupabase): FakeRequest {
  const request = fake.requests.filter((it) => it.url.pathname.startsWith("/rest/v1/")).at(-1);
  if (request === undefined) throw new Error("keine Anfrage");
  return request;
}

/** Pfad und Abfrage einer Anfrage, lesbar und in fester Reihenfolge der Angaben. */
function query(request: FakeRequest): Record<string, string> {
  return Object.fromEntries([...request.url.searchParams].sort(([a], [b]) => (a < b ? -1 : 1)));
}

async function roundOf(fake: FakeSupabase, api: RoundApi): Promise<RoundId> {
  const round = await api.createRound("Stammtisch");
  fake.requests.length = 0;
  return round;
}

const manual: ManualEntry = {
  kind: "manual",
  id: "frei",
  recordedAt: after(200),
  amounts: new Map([
    [P4, -30],
    [P1, 30],
  ]),
  note: "Runde Schnaps",
};

describe("Anfragen wie im Vertrag (Abschnitt 8.3)", () => {
  it("jede Anfrage trägt den öffentlichen Schlüssel und das Zugriffstoken des Kontos", async () => {
    const fake = new FakeSupabase();
    const { api, storage } = client(fake);

    await api.myRounds();

    const token = (JSON.parse(storage.getItem(AUTH_STORAGE_KEY) ?? "{}") as { access_token: string }).access_token;
    expect(last(fake).headers.apikey).toBe(KEY);
    expect(last(fake).headers.authorization).toBe(`Bearer ${token}`);
    expect(last(fake).url.origin).toBe(SUPABASE);
  });

  it("der Kopf der Runde: rounds mit id, name, rev, rules, created_by", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);

    const head = await api.head(round);

    expect(fake.restCalls()).toEqual(["GET rounds"]);
    expect(query(last(fake))).toEqual({ id: `eq.${round}`, select: "id,name,rev,rules,created_by" });
    expect(head).toEqual({ id: round, name: "Stammtisch", rev: 1, rules: null, createdBy: ONE.userId, rulesUnreadable: false });
  });

  it("eine Runde, die es nicht gibt oder zu der das Konto nicht gehört, hat keinen Kopf", async () => {
    const fake = new FakeSupabase();
    const round = await roundOf(fake, client(fake).api);

    expect(await client(fake, TWO).api.head(round)).toBeNull();
    expect(await client(fake).api.head("gibt-es-nicht")).toBeNull();
  });

  it("die Mitglieder: round_members nach Beitritt geordnet", async () => {
    const fake = new FakeSupabase();
    const one = client(fake).api;
    const round = await roundOf(fake, one);
    await client(fake, TWO).api.joinRound(await one.createInvite(round));
    await one.claimPlayer(round, "ich", "Eins", 3);

    const members = await one.members(round);

    expect(query(last(fake))).toEqual({ order: "joined_at.asc", round_id: `eq.${round}`, select: "user_id,player_id,joined_at" });
    expect(members).toEqual([
      { userId: ONE.userId, playerId: "ich" },
      { userId: TWO.userId, playerId: null },
    ]);
  });

  it("die Spieler seit einer Änderung: round_players mit rev größer als n, nach rev geordnet", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const first = await api.savePlayer(round, player("gast-1", "Wirt", 4));
    await api.savePlayer(round, player("gast-2", "Nachbar", 5, true));
    await api.claimPlayer(round, "ich", "Eins", 3);

    const players = await api.playersSince(round, first);

    expect(query(last(fake))).toEqual({
      order: "rev.asc",
      rev: `gt.${first}`,
      round_id: `eq.${round}`,
      select: "id,name,color_index,archived,position,owner,name_locked,deleted,rev",
    });
    expect(players).toEqual([
      { id: "gast-2", name: "Nachbar", colorIndex: 5, archived: true, position: 2, owner: null, nameLocked: false, deleted: false, rev: first + 1 },
      { id: "ich", name: "Eins", colorIndex: 3, archived: false, position: 3, owner: ONE.userId, nameLocked: true, deleted: false, rev: first + 2 },
    ]);
  });

  it("die Abende seit einer Änderung: round_evenings seitenweise zu 100, jeweils nach dem größten gelesenen rev weiter", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    for (let index = 0; index < 250; index++) await fake.session(ONE.userId).saveEvening(round, evening([], `abend-${index}`), 0);
    const start = 1;

    const rows = await api.eveningsSince(round, start);

    expect(rows).toHaveLength(250);
    expect(rows.map((it) => it.rev)).toEqual(Array.from({ length: 250 }, (_, index) => start + 1 + index));
    expect(fake.restCalls()).toEqual(["GET round_evenings", "GET round_evenings", "GET round_evenings"]);
    expect(fake.calls("GET", "/rest/v1/round_evenings").map(query)).toEqual(
      [start, start + 100, start + 200].map((rev) => ({
        limit: "100",
        order: "rev.asc",
        rev: `gt.${rev}`,
        round_id: `eq.${round}`,
        select: "id,doc,version,deleted,rev",
      })),
    );
  });

  it("genau 100 Abende: Die zweite Seite ist leer, dann ist Schluss", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    for (let index = 0; index < 100; index++) await fake.session(ONE.userId).saveEvening(round, evening([], `abend-${index}`), 0);

    expect(await api.eveningsSince(round, 0)).toHaveLength(100);
    expect(fake.restCalls()).toEqual(["GET round_evenings", "GET round_evenings"]);
  });

  it("ein einzelner Abend: round_evenings mit id", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const saved = await api.saveEvening(round, evening([game("g1", 60)]), 0);

    const row = await api.evening(round, "abend");

    expect(query(last(fake))).toEqual({ id: "eq.abend", round_id: `eq.${round}`, select: "id,doc,version,deleted,rev" });
    expect(row).toMatchObject({ id: "abend", version: 1, deleted: false, rev: saved?.rev });
    expect(await api.evening(round, "unbekannt")).toBeNull();
  });

  it("die Funktionen des Servers mit genau ihren Parametern", async () => {
    const fake = new FakeSupabase();
    const one = client(fake).api;
    const two = client(fake, TWO).api;
    const bodyOf = (name: string): unknown => fake.calls("POST", `/rest/v1/rpc/${name}`).at(-1)?.body;

    const round = await one.createRound("  Stammtisch ");
    expect(bodyOf("create_round")).toEqual({ p_name: "  Stammtisch " });

    const token = await one.createInvite(round);
    expect(bodyOf("create_invite")).toEqual({ p_round: round });
    expect(token).toMatch(/^[0-9a-f]{32}$/);

    expect(await two.inviteInfo(token)).toEqual({ roundId: round, roundName: "Stammtisch", memberCount: 1, alreadyMember: false });
    expect(bodyOf("invite_info")).toEqual({ p_token: token });

    expect(await two.joinRound(token)).toBe(round);
    expect(bodyOf("join_round")).toEqual({ p_token: token });

    await two.claimPlayer(round, "spieler-2", " Zwei ", 33);
    expect(bodyOf("claim_player")).toEqual({ p_round: round, p_player: "spieler-2", p_name: " Zwei ", p_color_index: 33 });

    const rev = await one.savePlayer(round, player("gast", "Wirt", 4, true));
    expect(bodyOf("save_player")).toEqual({ p_round: round, p_id: "gast", p_name: "Wirt", p_color_index: 4, p_archived: true, p_deleted: false });
    expect(rev).toBe(fake.server.rev(round));
    expect(await one.savePlayer(round, player("gast", "Wirt", 4, true), true)).toBe(rev + 1);
    expect(bodyOf("save_player")).toMatchObject({ p_id: "gast", p_deleted: true });

    expect(await one.saveRules(round, RuleSets.sauspiel())).toBe(rev + 2);
    expect(bodyOf("save_rules")).toEqual({ p_round: round, p_rules: JSON.parse(JSON.stringify((bodyOf("save_rules") as { p_rules: unknown }).p_rules)) });
    expect((bodyOf("save_rules") as { p_rules: { name: string; payoutRule: string } }).p_rules).toMatchObject({
      name: "Sauspiel 10-20-50 mit Bockrunde",
      payoutRule: "schafkopf",
    });

    // member_names (seit dem 21.11.2026): die Namen der Spieler, die Mitglieder übernommen haben, hier nur „Zwei“.
    expect(await one.myRounds()).toEqual([
      { id: round, name: "Stammtisch", rev: rev + 2, memberCount: 2, myPlayerId: null, isCreator: true, memberNames: ["Zwei"] },
    ]);
    expect(bodyOf("my_rounds")).toEqual({});
    expect((await two.myRounds())[0]).toMatchObject({ myPlayerId: "spieler-2", isCreator: false, memberNames: ["Zwei"] });

    await two.leaveRound(round);
    expect(bodyOf("leave_round")).toEqual({ p_round: round });
    expect(await two.myRounds()).toEqual([]);
  });

  it("die Runden des Kontos: member_names fehlt bei einem älteren Server oder ist null, dann ist die Liste leer", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    fake.override = (request) =>
      request.url.pathname === "/rest/v1/rpc/my_rounds"
        ? {
            status: 200,
            body: [
              { id: "erste", name: "Stammtisch", rev: 9, member_count: 3, my_player_id: "spieler-2", is_creator: false, member_names: ["Anna", "Zwei"] },
              // Wie von einem Server vor dem 21.11.2026: ohne die Spalte.
              { id: "andere", name: "Zweite", rev: 1, member_count: 1, my_player_id: null, is_creator: true },
              { id: "dritte", name: "Dritte", rev: 2, member_count: 1, my_player_id: null, is_creator: true, member_names: null },
            ],
          }
        : null;

    expect(await api.myRounds()).toEqual([
      { id: "erste", name: "Stammtisch", rev: 9, memberCount: 3, myPlayerId: "spieler-2", isCreator: false, memberNames: ["Anna", "Zwei"] },
      { id: "andere", name: "Zweite", rev: 1, memberCount: 1, myPlayerId: null, isCreator: true, memberNames: [] },
      { id: "dritte", name: "Dritte", rev: 2, memberCount: 1, myPlayerId: null, isCreator: true, memberNames: [] },
    ]);

    // Etwas anderes als eine Liste von Texten ist eine Antwort in unerwarteter Form.
    for (const names of ["Anna", [17], [null], { 0: "Anna" }]) {
      fake.override = (request) =>
        request.url.pathname === "/rest/v1/rpc/my_rounds"
          ? { status: 200, body: [{ id: "erste", name: "Stammtisch", rev: 9, member_count: 3, my_player_id: null, is_creator: false, member_names: names }] }
          : null;
      expect(await errorOf(() => api.myRounds()), JSON.stringify(names)).toBe("SERVER");
    }
  });

  it("eine Runde mit dem Namen einer Runde des Kontos lehnt der Server ab (bereinigt, ohne Groß/Klein), vor der Obergrenze", async () => {
    const fake = new FakeSupabase();
    const one = client(fake).api;
    const two = client(fake, TWO).api;
    await one.createRound("Stammtisch");

    expect(await errorOf(() => one.createRound("  stammTISCH "))).toBe("NAME_TAKEN");
    expect(await errorOf(() => one.createRound(" STAMMTISCH\n"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => two.createRound("Stammtisch"))).toBeNull();
    for (let index = 2; index <= InMemoryRoundServer.MAX_ROUNDS; index++) await one.createRound(`Runde ${index}`);
    expect(await errorOf(() => one.createRound("runde 2"))).toBe("NAME_TAKEN");
    expect(await errorOf(() => one.createRound("Runde 6"))).toBe("LIMIT_REACHED");
  });

  it("ein Abend geht als Dokument im Dateiformat der Sicherung hoch und kommt gleich zurück", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const doc: Evening = {
      ...evening([{ ...game("g1", 60), klopfer: [P3, P2], kontra: "KONTRA", kontraBy: P4, declarerEyes: 61 }, manual]),
      finishedAt: after(7200, 250),
      location: "Wirtshaus",
      rules: RuleSets.sauspiel(),
    };

    expect(await api.saveEvening(round, doc, 0)).toEqual({ version: 1, rev: 2 });

    const body = fake.calls("POST", "/rest/v1/rpc/save_evening")[0].body as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(["p_deleted", "p_doc", "p_expected_version", "p_id", "p_round"]);
    expect(body).toMatchObject({ p_round: round, p_id: "abend", p_expected_version: 0, p_deleted: false });
    // Das Dokument ist das Abend-Objekt des Dateiformats: mit Schema-Version, Kennung und Zeitpunkten als Text.
    expect(body.p_doc).toMatchObject({
      schemaVersion: 1,
      id: "abend",
      startedAt: "2026-12-12T18:00:00Z",
      finishedAt: "2026-12-12T20:00:00.250Z",
      location: "Wirtshaus",
      seating: [P1, P2, P3, P4],
      firstDealer: P1,
    });
    expect((body.p_doc as { entries: unknown[] }).entries[1]).toEqual({
      type: "manual",
      id: "frei",
      recordedAt: "2026-12-12T18:03:20Z",
      amounts: { [P4]: -30, [P1]: 30 },
      note: "Runde Schnaps",
    });

    // Der Server gibt das Dokument mit anderer Reihenfolge der Schlüssel zurück; inhaltlich ist es derselbe Abend.
    const row = await api.evening(round, "abend");
    expect(row?.evening).not.toBeNull();
    expect(sameEvening(row?.evening as Evening, doc)).toBe(true);
    expect([...((row?.evening?.entries[1] as ManualEntry).amounts.keys() ?? [])]).toEqual([P1, P4]);

    // Löschen schickt dasselbe Dokument mit dem Vermerk.
    expect(await api.saveEvening(round, doc, 1, true)).toEqual({ version: 2, rev: 3 });
    expect(fake.calls("POST", "/rest/v1/rpc/save_evening")[1].body).toMatchObject({ p_expected_version: 1, p_deleted: true });
    expect((await api.evening(round, "abend"))?.deleted).toBe(true);
  });

  it("passt die erwartete Fassung nicht, kommt kein Fehler, sondern nichts", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);

    expect(await api.saveEvening(round, evening(), 1)).toBeNull();
    expect(await api.saveEvening(round, evening(), 0)).toEqual({ version: 1, rev: 2 });
    expect(await api.saveEvening(round, evening([game("g1", 60)]), 0)).toBeNull();
    expect(fake.server.rev(round)).toBe(2);
  });

  it("das Regelwerk der Runde kommt als Regelwerk zurück", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const rules = { ...RuleSets.standard(), name: "Unsere Regeln", kontraEnabled: false };
    await api.saveRules(round, rules);

    const head = await api.head(round);

    expect(head?.rulesUnreadable).toBe(false);
    expect(head?.rules !== null && head?.rules !== undefined && sameRules(head.rules, rules)).toBe(true);
  });
});

describe("Dokumente, die diese Version nicht lesen kann", () => {
  it("ein Abend mit neuerer Schema-Version ergibt keinen Abend, aber auch keinen Fehler", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    await api.saveEvening(round, evening([game("g1", 60)]), 0);
    await api.saveEvening(round, evening([], "abend-2"), 0);
    fake.server.makeUnreadable(round, "abend");

    const rows = await api.eveningsSince(round, 0);

    expect(rows.map((it) => [it.id, it.version, it.evening === null])).toEqual([
      ["abend-2", 1, false],
      ["abend", 2, true],
    ]);
    expect((await api.evening(round, "abend"))?.evening).toBeNull();
  });

  it("ein Regelwerk, das sich nicht lesen lässt, wird als unlesbar gemeldet", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    fake.server.makeRulesUnreadable(round);

    expect(await api.head(round)).toMatchObject({ rules: null, rulesUnreadable: true });
  });

  it("auch ganz andere Inhalte bringen das Lesen nicht zum Absturz", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const respond = (doc: unknown) => ({ status: 200, body: [{ id: "abend", doc, version: 3, deleted: false, rev: 9 }] });
    const original = fake.handle.bind(fake);
    const docs: unknown[] = [null, "Text", 17, [], {}, { id: "abend" }, { id: "anders", schemaVersion: 1 }, { schemaVersion: 1, id: "abend", entries: "kaputt" }];

    for (const doc of docs) {
      fake.handle = async (request) => (request.url.pathname.endsWith("/round_evenings") ? respond(doc) : original(request));
      expect(await api.evening(round, "abend"), JSON.stringify(doc)).toEqual({ id: "abend", version: 3, deleted: false, rev: 9, evening: null });
    }

    // Ein lesbares Dokument mit einer anderen Kennung als die Zeile gilt ebenfalls als unlesbar.
    const other = JSON.parse(JSON.stringify((await (async () => {
      fake.handle = original;
      await api.saveEvening(round, evening([], "anders"), 0);
      return fake.calls("POST", "/rest/v1/rpc/save_evening").at(-1)?.body as { p_doc: unknown };
    })()).p_doc)) as unknown;
    fake.handle = async (request) => (request.url.pathname.endsWith("/round_evenings") ? respond(other) : original(request));
    expect((await api.evening(round, "abend"))?.evening).toBeNull();
  });
});

describe("Fehler", () => {
  it("jeder Fehlercode der Funktionen wird zu seinem Fehler", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const original = fake.handle.bind(fake);
    const codes: Array<[string, RoundError]> = [
      ["not_member", "NOT_MEMBER"],
      ["invalid_name", "INVALID_NAME"],
      ["name_taken", "NAME_TAKEN"],
      ["name_locked", "NAME_LOCKED"],
      ["already_claimed", "ALREADY_CLAIMED"],
      ["player_taken", "PLAYER_TAKEN"],
      ["player_owned", "PLAYER_OWNED"],
      ["invalid_invite", "INVALID_INVITE"],
      ["limit_reached", "LIMIT_REACHED"],
      ["too_large", "TOO_LARGE"],
      ["invalid_doc", "INVALID_DOC"],
    ];

    for (const [code, expected] of codes) {
      fake.handle = async () => ({ status: 400, body: { code: "P0001", message: code, details: null, hint: null } });
      expect(await errorOf(() => api.savePlayer(round, player("gast", "Wirt", 4))), code).toBe(expected);
    }
    // Ein Code, den diese Version nicht kennt, und die Fehler der App selbst sind keine Codes des Servers.
    for (const code of ["neuer_code", "offline", "signed_out", "server", "NAME_TAKEN", ""]) {
      fake.handle = async () => ({ status: 400, body: { code: "P0001", message: code, details: null, hint: null } });
      expect(await errorOf(() => api.savePlayer(round, player("gast", "Wirt", 4))), code).toBe("SERVER");
    }
    fake.handle = original;
  });

  it("die Fehler des Servers im Speicher kommen über die Leitung genauso an", async () => {
    const fake = new FakeSupabase();
    const one = client(fake).api;
    const two = client(fake, TWO).api;
    const round = await roundOf(fake, one);

    expect(await errorOf(() => two.savePlayer(round, player("gast", "Wirt", 4)))).toBe("NOT_MEMBER");
    expect(await errorOf(() => two.inviteInfo("0".repeat(32)))).toBe("INVALID_INVITE");
    expect(await errorOf(() => one.createRound("   "))).toBe("INVALID_NAME");
    await one.savePlayer(round, player("gast", "Wirt", 4));
    expect(await errorOf(() => one.savePlayer(round, player("gast-2", "wirt", 5)))).toBe("NAME_TAKEN");
    const huge = evening(Array.from({ length: InMemoryRoundServer.MAX_ENTRIES + 1 }, (_, index) => game(`g${index}`, index)));
    expect(await errorOf(() => one.saveEvening(round, huge, 0))).toBe("TOO_LARGE");
    expect(await errorOf(() => one.saveEvening(round, evening([], "x".repeat(65)), 0))).toBe("INVALID_DOC");
  });

  it("ohne Netz: OFFLINE", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    fake.offline = true;

    expect(await errorOf(() => api.head(round))).toBe("OFFLINE");
    expect(await errorOf(() => api.saveEvening(round, evening(), 0))).toBe("OFFLINE");
    expect(await errorOf(() => api.myRounds())).toBe("OFFLINE");
    // Es wird nicht von selbst mehrfach versucht: Den Abstand bestimmt der Abgleich.
    expect(fake.requests).toEqual([]);
  });

  it("geht die Antwort verloren, ist die Änderung trotzdem angekommen: OFFLINE", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    fake.session(ONE.userId).loseNextAnswer = true;

    expect(await errorOf(() => api.saveEvening(round, evening(), 0))).toBe("OFFLINE");
    expect(fake.server.eveningRow(round, "abend")?.version).toBe(1);
  });

  it("dauert eine Anfrage zu lange, wird sie abgebrochen: OFFLINE", async () => {
    const fake = new FakeSupabase();
    const storage = new MemoryStorage();
    storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(fake.issueSession(ONE)));
    const answer = fakeFetch(fake);
    // Ein Netz, das nie antwortet – nur der Abbruch beendet die Anfrage.
    const hanging: typeof fetch = (input, init) =>
      new Promise((_resolve, reject) => {
        const signal = init?.signal ?? (input instanceof Request ? input.signal : undefined);
        signal?.addEventListener("abort", () => reject(new DOMException("Abgebrochen", "AbortError")));
      });
    let hang = false;
    const { SupabaseRoundApi } = await import("./supabaseRoundApi");
    const { createClient } = await import("@supabase/supabase-js");
    const supabase = createClient(SUPABASE, KEY, {
      auth: { storage, storageKey: AUTH_STORAGE_KEY, autoRefreshToken: false, detectSessionInUrl: false },
      global: { fetch: (input, init) => (hang ? hanging(input, init) : answer(input, init)) },
    });
    const api = new SupabaseRoundApi(supabase, 30);
    const round = await api.createRound("Stammtisch");

    hang = true;
    expect(await errorOf(() => api.head(round))).toBe("OFFLINE");
    expect(await errorOf(() => api.saveEvening(round, evening(), 0))).toBe("OFFLINE");
    hang = false;
    expect((await api.head(round))?.name).toBe("Stammtisch");
  });

  it("ohne Anmeldung: SIGNED_OUT, ohne dass der Server gefragt wird", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake, null);

    expect(await errorOf(() => api.myRounds())).toBe("SIGNED_OUT");
    expect(await errorOf(() => api.head("runde-1"))).toBe("SIGNED_OUT");
    expect(fake.requests).toEqual([]);
  });

  it("lehnt der Server das Token ab, wird die Sitzung einmal erneuert und die Anfrage wiederholt", async () => {
    const fake = new FakeSupabase();
    const { api, storage } = client(fake);
    const round = await roundOf(fake, api);
    const before = storage.getItem(AUTH_STORAGE_KEY);
    fake.expireAccessTokens();

    expect((await api.head(round))?.name).toBe("Stammtisch");

    expect(fake.requests.map((it) => `${it.method} ${it.url.pathname}`)).toEqual([
      "GET /rest/v1/rounds",
      "POST /auth/v1/token",
      "GET /rest/v1/rounds",
    ]);
    expect(fake.calls("POST", "/auth/v1/token")[0].url.searchParams.get("grant_type")).toBe("refresh_token");
    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBe(before);
    // Danach geht es ohne weiteres Erneuern weiter.
    fake.requests.length = 0;
    await api.myRounds();
    expect(fake.restCalls()).toEqual(["POST rpc/my_rounds"]);
  });

  it("kennt der Server die Sitzung nicht mehr, ist das Gerät abgemeldet: SIGNED_OUT", async () => {
    const fake = new FakeSupabase();
    const { api, auth, storage } = client(fake);
    const round = await roundOf(fake, api);
    await auth.load();
    fake.revokeSessions();

    expect(await errorOf(() => api.head(round))).toBe("SIGNED_OUT");

    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    expect(auth.account()).toBeNull();
    // Der nächste Versuch fragt den Server gar nicht mehr.
    fake.requests.length = 0;
    expect(await errorOf(() => api.head(round))).toBe("SIGNED_OUT");
    expect(fake.requests).toEqual([]);
  });

  it.each([
    [403, "SIGNED_OUT"],
    [429, "SERVER"],
    // Antworten wie „gerade nicht da“ versucht supabase-js lange immer wieder; die Anbindung wartet nur die erlaubte Zeit.
    [500, "OFFLINE"],
    [540, "OFFLINE"],
  ] as const)("lehnt der Server das Erneuern mit %i und unbekanntem Grund ab, bleibt die Sitzung auf dem Gerät: %s", async (status, expected) => {
    const fake = new FakeSupabase();
    const { api, auth, storage } = client(fake, ONE, 150);
    const round = await roundOf(fake, api);
    await auth.load();
    fake.expireAccessTokens();
    const original = fake.handle.bind(fake);
    fake.handle = async (request) =>
      request.url.pathname === "/auth/v1/token" ? { status, body: { code: status, error_code: "noch_unbekannt", msg: "Abgelehnt" } } : original(request);

    expect(await errorOf(() => api.head(round))).toBe(expected);

    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    expect(auth.account()).toEqual(ONE);
  });

  it("lässt sich die Sitzung nach einer Ablehnung gerade nicht erneuern (kein Netz), bleibt die Anmeldung: OFFLINE", async () => {
    const fake = new FakeSupabase();
    // supabase-js versucht das Erneuern ohne Netz lange immer wieder; die Anbindung wartet nur die erlaubte Zeit.
    const { api, storage } = client(fake, ONE, 150);
    const round = await roundOf(fake, api);
    fake.expireAccessTokens();
    const original = fake.handle.bind(fake);
    fake.handle = async (request) => (request.url.pathname.startsWith("/auth/") ? NETWORK_FAILURE : original(request));

    expect(await errorOf(() => api.head(round))).toBe("OFFLINE");

    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    fake.handle = original;
    expect((await api.head(round))?.name).toBe("Stammtisch");
  });

  it("lehnt der Server auch die erneuerte Sitzung ab: SIGNED_OUT", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const original = fake.handle.bind(fake);
    fake.handle = async (request) =>
      request.url.pathname.startsWith("/rest/") ? { status: 401, body: { code: "PGRST301", message: "JWT expired", details: null, hint: null } } : original(request);

    const error = await api.head(round).catch((it: unknown) => it);

    expect(error).toBeInstanceOf(RoundException);
    expect((error as RoundException).error).toBe("SIGNED_OUT");
    expect((error as RoundException).detail).toBe("HTTP 401 PGRST301");
  });

  it("ein pausiertes Projekt kostet auch bei abgelaufener Sitzung nicht die Anmeldung", async () => {
    const source = new FakeSupabase();
    const fake = new FakeSupabase();
    const storage = new MemoryStorage();
    // Die Sitzung ist schon abgelaufen: Ohne Erneuern geht nichts.
    storage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ ...source.issueSession(ONE), expires_at: Math.floor(Date.now() / 1000) - 60 }));
    const backend = createSupabaseBackend(
      { url: SUPABASE, publishableKey: KEY },
      { redirectTo: START_URL, openUrl: () => undefined, storage, fetch: fakeFetch(fake), autoRefresh: false, timeoutMillis: 150, waitMillis: 150 },
    );
    fake.failWithStatus = 540;

    expect(await backend.auth.load()).toEqual(ONE);
    expect(await errorOf(() => backend.api.myRounds())).toBe("OFFLINE");

    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    expect(backend.auth.account()).toEqual(ONE);
    expect(fake.calls("POST", "/auth/v1/token").length).toBeGreaterThan(0);
  });

  it("antwortet der Server mit einem Fehler (zum Beispiel pausiert): SERVER, ohne Inhalt der Antwort im Fehler", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);

    for (const status of [500, 503, 540]) {
      fake.failWithStatus = status;
      const error = await api.head(round).catch((it: unknown) => it);
      expect(error).toBeInstanceOf(RoundException);
      expect((error as RoundException).error).toBe("SERVER");
      expect((error as RoundException).message).toBe(`SERVER: HTTP ${status}`);
    }
    fake.failWithStatus = null;
    const original = fake.handle.bind(fake);
    fake.handle = async () => ({ status: 403, body: { code: "42501", message: "permission denied for table rounds", details: null, hint: null } });
    expect(((await api.head(round).catch((it: unknown) => it)) as RoundException).message).toBe("SERVER: HTTP 403 42501");
    fake.handle = original;
  });

  it("eine Antwort in unerwarteter Form: SERVER", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const original = fake.handle.bind(fake);
    const answers: unknown[] = [{ kein: "Feld" }, [{ id: 17 }], "Text", [null], [{ id: round, name: "Stammtisch", rev: "viele", rules: null, created_by: null }]];

    for (const body of answers) {
      fake.handle = async () => ({ status: 200, body });
      expect(await errorOf(() => api.head(round)), JSON.stringify(body)).toBe("SERVER");
    }
    fake.handle = async () => ({ status: 200, body: "kein-rev" });
    expect(await errorOf(() => api.savePlayer(round, player("gast", "Wirt", 4)))).toBe("SERVER");
    fake.handle = async () => ({ status: 200, body: 17 });
    expect(await errorOf(() => api.createRound("Stammtisch"))).toBe("SERVER");
    fake.handle = async () => ({ status: 200, body: [] });
    expect(await errorOf(() => api.inviteInfo("0".repeat(32)))).toBe("INVALID_INVITE");
    fake.handle = original;
  });

  it("ein Inhalt, mit dem die Datenbank nichts anfangen kann (Nullzeichen), trifft nur dieses eine Stück: INVALID_DOC", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const poisoned = evening([{ ...game("g1", 60), note: "vor\u0000nach" }]);

    const error = await api.saveEvening(round, poisoned, 0).catch((it: unknown) => it);
    expect((error as RoundException).error).toBe("INVALID_DOC");
    expect((error as RoundException).message).toBe("INVALID_DOC: HTTP 400 22P05");
    expect(await errorOf(() => api.savePlayer(round, player("gast", "Wi\u0000rt", 4)))).toBe("INVALID_DOC");
    expect(await errorOf(() => api.saveRules(round, { ...RuleSets.standard(), name: "Haus\u0000regeln" }))).toBe("INVALID_DOC");
    expect(fake.server.evenings(round)).toEqual([]);
  });

  it("ein Abend oder Regelwerk, das dem Server zu groß ist: TOO_LARGE", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);

    expect(await errorOf(() => api.saveEvening(round, evening([{ ...game("g1", 60), note: "x".repeat(300_001) }]), 0))).toBe("TOO_LARGE");
    expect(await errorOf(() => api.saveRules(round, { ...RuleSets.standard(), name: "x".repeat(20_001) }))).toBe("TOO_LARGE");
    expect(await api.saveEvening(round, evening([{ ...game("g1", 60), note: "x".repeat(290_000) }]), 0)).toEqual({ version: 1, rev: 2 });
  });

  it.each([
    [400, "22P05", "INVALID_DOC"],
    [400, "22021", "INVALID_DOC"],
    [400, "PGRST102", "INVALID_DOC"],
    // Nur die Antwort 400 zählt, und nur diese Codes: Alles andere bleibt ein Fehler des Servers.
    [500, "22P05", "SERVER"],
    [400, "23505", "SERVER"],
    [400, "PGRST100", "SERVER"],
    [400, "", "SERVER"],
  ] as const)("die Antwort %i mit dem Code „%s“ wird zu %s", async (status, code, expected) => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    fake.override = (request) =>
      request.url.pathname.startsWith("/rest/v1/") ? { status, body: { code, message: "Fehler der Datenbank", details: null, hint: null } } : null;

    expect(await errorOf(() => api.saveEvening(round, evening([]), 0))).toBe(expected);
  });

  it("ein Abend, der sich nicht ins Dateiformat schreiben lässt, gilt als vom Server nicht angenommen", async () => {
    const fake = new FakeSupabase();
    const { api } = client(fake);
    const round = await roundOf(fake, api);
    const broken = evening([{ ...game("g1", 60), laufende: 1.5 }]);

    expect(await errorOf(() => api.saveEvening(round, broken, 0))).toBe("INVALID_DOC");
    expect(fake.restCalls()).toEqual([]);
  });
});

describe("der Abgleich über supabase-js (mehrere Geräte an einem nachgestellten Projekt)", () => {
  const abend = "abend-1";

  function devices() {
    const clock = new TestClock();
    const fake = new FakeSupabase(() => clock.millis);
    const device = (account: FakeAccount): Device =>
      new Device(fake.server, account.userId, clock, { session: fake.session(account.userId), api: client(fake, account).api });
    return { clock, fake, one: device(ONE), two: device(TWO) };
  }

  async function expectSame(fake: FakeSupabase, round: RoundId, ...all: Device[]): Promise<void> {
    const onServer = [...fake.server.evenings(round)].sort((a, b) => (a.id < b.id ? -1 : 1));
    for (const device of all) {
      const local = await device.allEvenings();
      expect(local.map((it) => it.id), device.userId).toEqual(onServer.map((it) => it.id));
      local.forEach((it, index) => expect(sameEvening(it, onServer[index]), `${device.userId}: ${it.id}`).toBe(true));
      expect(await device.sync.pendingCount(), device.userId).toBe(0);
      expect((await device.link())?.cursor, device.userId).toBe(fake.server.rev(round));
    }
  }

  it("anlegen, beitreten, gleichzeitig eintragen: Am Ende haben alle dasselbe", async () => {
    const { clock, fake, one, two } = devices();
    await one.firstStart();
    await one.startEvening(abend);
    clock.advance(60);
    await one.addGame(abend);
    await one.editor.update(abend, (it) => ({ ...it, entries: [...it.entries, manual] }));

    expect(await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS)).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 6, pending: 0 }));
    const round = await one.roundId();
    // Vier Spieler und ein Abend; das Regelwerk der Runde hatte dieses Gerät schon genauso.
    expect(await two.connection.join(await one.api.createInvite(round), two.userId)).toEqual(SyncOutcome.synced({ pulled: 5, pushed: 0, pending: 0 }));
    // Der Abend kam mit umgestellten Schlüsseln zurück und gilt trotzdem nicht als geändert.
    expect(await two.sync.pendingCount()).toBe(0);
    await one.synced();

    clock.advance(100);
    const later = await one.addGame(abend, true, 3);
    clock.advance(-10);
    const earlier = await two.addGame(abend, false);
    one.session.calls.length = 0;
    expect(await one.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(one.session.calls).toEqual(["head", "saveEvening"]);
    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 1, pushed: 1, pending: 0 }));
    expect(await one.sync.sync(true)).toEqual(SyncOutcome.synced({ pulled: 1, pushed: 0, pending: 0 }));
    expect(await one.sync.sync(true)).toEqual(SyncOutcome.Unchanged);

    expect((await one.evening(abend))?.entries.slice(2).map((it) => it.id)).toEqual([earlier, later]);
    await expectSame(fake, round, one, two);
  });

  it("ohne Netz, mit verlorener Antwort und mit abgelaufenem Token geht nichts verloren und nichts doppelt hoch", async () => {
    const { clock, fake, one, two } = devices();
    await one.firstStart();
    await one.startEvening(abend);
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
    const round = await one.roundId();
    await two.connection.join(await one.api.createInvite(round), two.userId);

    two.session.offline = true;
    clock.advance(60);
    await two.addGame(abend);
    expect(await two.sync.sync()).toEqual(SyncOutcome.Offline);
    two.session.offline = false;
    two.session.loseNextAnswer = true;
    expect(await two.sync.sync()).toEqual(SyncOutcome.Offline);
    expect(fake.server.eveningRow(round, abend)?.version).toBe(2);
    fake.expireAccessTokens();
    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 0, pending: 0 }));
    expect(fake.server.eveningRow(round, abend)?.version).toBe(2);

    fake.revokeSessions();
    expect(await one.sync.sync()).toEqual(SyncOutcome.SignedOut);
    expect(await two.sync.sync(true)).toEqual(SyncOutcome.SignedOut);
    expect(await two.sync.pendingCount()).toBe(0);
  });

  it("ein Abend mit einem Nullzeichen bleibt hier und hält die anderen nicht auf", async () => {
    const { fake, one, two } = devices();
    await one.firstStart();
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
    const round = await one.roundId();
    await two.connection.join(await one.api.createInvite(round), two.userId);
    await two.startEvening("gift");
    await two.editor.update("gift", (it) => ({ ...it, location: "Wirts\u0000haus" }));
    await two.startEvening("gut");

    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 1, pending: 1, refused: 1 }));

    expect(fake.server.evenings(round).map((it) => it.id)).toEqual(["gut"]);
    expect(await two.evening("gift")).not.toBeNull();
    // Solange sich der Abend nicht ändert, wird er nicht noch einmal geschickt; er wartet und bleibt als abgelehnt gezählt.
    fake.requests.length = 0;
    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 0, pending: 1, refused: 1 }));
    expect(fake.restCalls().filter((it) => it.includes("save_evening"))).toEqual([]);
    // Ohne das Nullzeichen geht er hoch, und die Zählung ist weg.
    await two.editor.update("gift", (it) => ({ ...it, location: "Wirtshaus" }));
    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 1, pending: 0 }));
    expect(fake.server.evenings(round).map((it) => it.id).sort()).toEqual(["gift", "gut"]);
  });

  it("ein pausiertes Projekt hält den Abend nicht auf", async () => {
    const { fake, one } = devices();
    await one.firstStart();
    await one.startEvening(abend);
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
    const round = await one.roundId();

    fake.failWithStatus = 540;
    await one.addGame(abend);
    await one.addGame(abend, false);
    expect(await one.sync.sync()).toEqual(SyncOutcome.failed("SERVER"));
    expect(await one.sync.pendingCount()).toBe(1);
    fake.failWithStatus = null;
    expect(await one.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 0, pushed: 1, pending: 0 }));
    await expectSame(fake, round, one);
  });

  it("Spieler, Regelwerk, Das bin ich und Löschen laufen über die Leitung wie im Speicher", async () => {
    const { fake, one, two } = devices();
    await one.firstStart();
    await one.players.save({ ...PLACEHOLDERS[0], name: "Nord" });
    await one.startEvening(abend);
    await one.connection.create("Stammtisch", one.userId, PLACEHOLDERS);
    const round = await one.roundId();
    await two.connection.join(await one.api.createInvite(round), two.userId);

    expect((await two.connection.claim(P2, "Zwei")).kind).toBe("Synced");
    await two.players.save({ ...(await two.allPlayers())[0], name: "Norden" });
    await two.players.save(player("gast", "nord", 4));
    await two.settings.update((it) => ({ ...it, defaultRules: RuleSets.sauspiel() }));
    const outcome = await two.sync.sync();
    // Der umbenannte Spieler, der neue Gast (sein Name ist durch das Umbenennen frei geworden) und das Regelwerk.
    expect(outcome).toMatchObject({ kind: "Synced", pushed: 3, pending: 0, notices: [] });
    await one.synced();

    expect((await one.allPlayers()).map((it) => it.name)).toEqual(["Norden", "Zwei", "Spieler 3", "Spieler 4", "nord"]);
    expect((await one.link())?.members).toEqual([
      { userId: ONE.userId, playerId: null },
      { userId: TWO.userId, playerId: P2 },
    ]);
    expect(sameRules((await one.settings.settings()).defaultRules, RuleSets.sauspiel())).toBe(true);
    expect((await one.store.playerBases()).get(P2)).toMatchObject({ owner: TWO.userId, nameLocked: true });

    await one.evenings.delete(abend);
    await one.players.delete("gast");
    await one.synced();
    expect(await two.sync.sync()).toEqual(SyncOutcome.synced({ pulled: 2, pushed: 0, pending: 0 }));
    expect(await two.allEvenings()).toEqual([]);
    await expectSame(fake, round, one, two);

    // Wer nicht mehr dazugehört, wird getrennt und behält seine Daten.
    fake.server.removeMember(round, TWO.userId);
    expect(await two.sync.sync()).toEqual(SyncOutcome.Detached);
    expect(await two.link()).toBeNull();
    expect(await two.allPlayers()).toHaveLength(4);
  });
});
