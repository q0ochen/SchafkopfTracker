/*
 * Ein nachgestelltes Supabase-Projekt für Tests: Es beantwortet die Anfragen von supabase-js (Anmeldung und
 * Datenschnittstelle) so, wie docs/ARCHITEKTUR.md, Abschnitt 8.2 und 8.3 sie festlegt, und hält die Daten im Server
 * im Speicher (`InMemoryRoundServer`). Die Unit-Tests hängen es als `fetch` an supabase-js, die Oberflächen-Tests
 * fangen damit die Anfragen des Browsers ab. In der gebauten App kommt es nicht vor.
 *
 * Es ist streng, wo der Vertrag streng ist: Spalten in `select`, Namen der Parameter und Filter müssen stimmen, sonst
 * antwortet es wie PostgREST mit einem Fehler. Dokumente kommen wie aus `jsonb` zurück: mit den Schlüsseln in der
 * Reihenfolge von PostgreSQL, nicht in der, in der sie geschickt wurden. Maßgeblich bleibt der echte Server in
 * `supabase/` mit seinen Tests; ein Dokument, das diese App nicht lesen kann, nimmt die Nachbildung (anders als der
 * echte Server) nur über {@link InMemoryRoundServer.makeUnreadable} an.
 */
import type { Evening, RuleSet } from "../../core/model";
import { InMemoryRoundServer, type InMemoryRoundSession } from "../../core/round/InMemoryRound";
import { RoundException, type RemoteEvening, type RemotePlayer } from "../../core/round/RoundModels";
import { decodeEvening, decodeRules, encodeEvening, encodeRules } from "../../data/format";

/** Eine Anfrage, wie sie beim Server ankommt. */
export interface FakeRequest {
  readonly method: string;
  readonly url: URL;
  /** Kopfzeilen mit kleingeschriebenen Namen. */
  readonly headers: Readonly<Record<string, string>>;
  /** Der Inhalt als JSON-Wert; `undefined` ohne Inhalt. */
  readonly body: unknown;
}

/** Eine Antwort des Servers; `body` ist ein JSON-Wert, `undefined` heißt „kein Inhalt“. */
export interface FakeResponse {
  readonly status: number;
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: unknown;
}

/** Statt einer Antwort: Die Verbindung kommt nicht zustande (oder bricht ab, bevor die Antwort da ist). */
export const NETWORK_FAILURE = "network-failure" as const;

export interface FakeAccount {
  readonly userId: string;
  readonly email: string;
}

const COLUMNS = {
  rounds: ["id", "name", "rev", "rules", "created_by"],
  round_members: ["user_id", "player_id", "joined_at"],
  round_players: ["id", "name", "color_index", "archived", "position", "owner", "name_locked", "deleted", "rev"],
  round_evenings: ["id", "doc", "version", "deleted", "rev"],
} as const;

type Table = keyof typeof COLUMNS;

/** Die Parameter der Funktionen aus Abschnitt 8.3, in der Reihenfolge ihrer Vereinbarung. */
const FUNCTIONS: Readonly<Record<string, readonly string[]>> = {
  create_round: ["p_name"],
  my_rounds: [],
  create_invite: ["p_round"],
  invite_info: ["p_token"],
  join_round: ["p_token"],
  leave_round: ["p_round"],
  claim_player: ["p_round", "p_player", "p_name", "p_color_index"],
  save_player: ["p_round", "p_id", "p_name", "p_color_index", "p_archived", "p_deleted"],
  save_rules: ["p_round", "p_rules"],
  save_evening: ["p_round", "p_id", "p_doc", "p_expected_version", "p_deleted"],
};

/**
 * Der echte Server begrenzt ein Abend-Dokument auf 300 000 Bytes und das Regelwerk auf 20 000. Die Nachbildung zählt
 * dafür die Zeichen des JSON-Texts; das genügt, um die Ablehnung zu zeigen.
 */
const MAX_EVENING_BYTES = 300_000;
const MAX_RULES_BYTES = 20_000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Ein JSON-Wert, wie PostgreSQL ihn aus `jsonb` wieder ausgibt: die Schlüssel jedes Objekts nach Länge und dann nach
 * Zeichen geordnet. So zeigt sich in Tests, ob die App Dokumente unabhängig von der Reihenfolge der Schlüssel liest.
 */
export function likeJsonb(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(likeJsonb);
  if (!isRecord(value)) return value;
  const keys = Object.keys(value).sort((a, b) => a.length - b.length || (a < b ? -1 : a > b ? 1 : 0));
  // Eine Map statt eines Objekts wäre nötig, wenn Schlüssel wie Zahlen aussehen (die sortiert JavaScript nach vorn);
  // für die Tests genügt das Objekt.
  return Object.fromEntries(keys.map((key) => [key, likeJsonb(value[key])]));
}

function eveningDocument(evening: Evening): unknown {
  return likeJsonb(JSON.parse(encodeEvening(evening)));
}

function rulesDocument(rules: RuleSet): unknown {
  return likeJsonb(JSON.parse(encodeRules(rules)));
}

/** Eine Antwort der Datenschnittstelle auf einen Fehler. */
function restError(status: number, code: string, message: string): FakeResponse {
  return { status, body: { code, message, details: null, hint: null } };
}

function raised(code: string): FakeResponse {
  return restError(400, "P0001", code);
}

/** Der Wert eines Filters wie `eq.abc`; `null`, wenn der Filter fehlt, ein Fehler, wenn er anders gemeint ist. */
function filter(url: URL, column: string, operator: string): string | null {
  const value = url.searchParams.get(column);
  if (value === null) return null;
  if (!value.startsWith(`${operator}.`)) throw new FilterError(`${column}=${value}`);
  return value.slice(operator.length + 1);
}

class FilterError extends Error {}

/** Ob irgendwo in dem JSON-Wert ein Text (oder Schlüssel) mit dem Nullzeichen steht. */
function containsNul(value: unknown): boolean {
  if (typeof value === "string") return value.includes("\u0000");
  if (Array.isArray(value)) return value.some(containsNul);
  if (isRecord(value)) return Object.entries(value).some(([key, inner]) => key.includes("\u0000") || containsNul(inner));
  return false;
}

export class FakeSupabase {
  readonly server: InMemoryRoundServer;

  /** Alle Anfragen in der Reihenfolge ihres Eingangs (außer Vorabfragen des Browsers). */
  readonly requests: FakeRequest[] = [];

  /** Kein Netz: Jede Anfrage scheitert, bevor sie den Server erreicht. */
  offline = false;

  /** Der Server antwortet auf alles mit diesem Status (zum Beispiel 540 für ein pausiertes Projekt). */
  failWithStatus: number | null = null;

  /** Wer sich als Nächstes „bei Google“ anmeldet. */
  nextSignIn: FakeAccount = { userId: "konto-1", email: "konto-1@example.org" };

  /** Die nächste Anmeldung bricht der Nutzer bei Google ab. */
  cancelNextSignIn = false;

  /** Für einzelne Tests: beantwortet eine Anfrage anders als vorgesehen; `null` lässt sie normal laufen. */
  override: ((request: FakeRequest) => FakeResponse | null) | null = null;

  private readonly sessions = new Map<string, InMemoryRoundSession>();
  private readonly accounts = new Map<string, FakeAccount>();
  private readonly codes = new Map<string, FakeAccount>();
  private readonly accessTokens = new Map<string, string>();
  private readonly refreshTokens = new Map<string, string>();
  private readonly joined = new Map<string, number>();
  private counter = 0;

  /** @param now Die Uhr des Servers (Millisekunden seit 1970), zum Beispiel für den Ablauf von Einladungen. */
  constructor(now: () => number = () => Date.now()) {
    this.server = new InMemoryRoundServer(now);
  }

  /** Der Zugang eines Kontos zum Server im Speicher (zum Stören und Nachsehen in Tests). */
  session(userId: string): InMemoryRoundSession {
    let session = this.sessions.get(userId);
    if (session === undefined) {
      session = this.server.sessionFor(userId);
      this.sessions.set(userId, session);
    }
    return session;
  }

  /** Eine frische Sitzung für das Konto, als hätte es sich gerade angemeldet (zum Vorbelegen des Speichers in Tests). */
  issueSession(account: FakeAccount): Record<string, unknown> {
    this.accounts.set(account.userId, account);
    const number = ++this.counter;
    const accessToken = `zugang.${account.userId}.${number}`;
    const refreshToken = `erneuern.${account.userId}.${number}`;
    this.accessTokens.set(accessToken, account.userId);
    this.refreshTokens.set(refreshToken, account.userId);
    const seconds = Math.floor(Date.now() / 1000);
    return {
      access_token: accessToken,
      refresh_token: refreshToken,
      token_type: "bearer",
      expires_in: 3600,
      expires_at: seconds + 3600,
      user: {
        id: account.userId,
        aud: "authenticated",
        role: "authenticated",
        email: account.email,
        app_metadata: { provider: "google" },
        user_metadata: {},
        created_at: new Date(0).toISOString(),
      },
    };
  }

  /** Alle Zugriffstoken gelten als abgelaufen; die Sitzungen lassen sich aber erneuern. */
  expireAccessTokens(): void {
    this.accessTokens.clear();
  }

  /** Der Server kennt keine Sitzung mehr: Auch das Erneuern scheitert. */
  revokeSessions(): void {
    this.accessTokens.clear();
    this.refreshTokens.clear();
  }

  /** Alle Anfragen mit dieser Methode an diesen Pfad. */
  calls(method: string, path: string): FakeRequest[] {
    return this.requests.filter((request) => request.method === method && request.url.pathname === path);
  }

  /** Die Pfade aller Anfragen an die Datenschnittstelle, ohne den Vorsatz `/rest/v1/`. */
  restCalls(): string[] {
    return this.requests
      .filter((request) => request.url.pathname.startsWith("/rest/v1/"))
      .map((request) => `${request.method} ${request.url.pathname.slice("/rest/v1/".length)}`);
  }

  async handle(request: FakeRequest): Promise<FakeResponse | typeof NETWORK_FAILURE> {
    if (this.offline) return NETWORK_FAILURE;
    this.requests.push(request);
    if (this.failWithStatus !== null) return { status: this.failWithStatus, body: { message: "Projekt nicht erreichbar" } };
    const overridden = this.override?.(request) ?? null;
    if (overridden !== null) return overridden;
    const path = request.url.pathname;
    if (path.startsWith("/auth/v1/")) return this.auth(request, path.slice("/auth/v1/".length));
    if (path.startsWith("/rest/v1/rpc/")) return this.rpc(request, path.slice("/rest/v1/rpc/".length));
    if (path.startsWith("/rest/v1/")) return this.select(request, path.slice("/rest/v1/".length));
    return { status: 404, body: { message: `Im Test nicht vorgesehen: ${request.method} ${path}` } };
  }

  // --- Anmeldung (GoTrue) ---

  private auth(request: FakeRequest, path: string): FakeResponse {
    const { method, url } = request;
    if (method === "GET" && path === "authorize") {
      // Die Anmeldung bei Google überspringen: gleich mit einem Code (oder der Absage) zur App zurück.
      const back = new URL(url.searchParams.get("redirect_to") ?? "http://localhost/");
      if (this.cancelNextSignIn) {
        this.cancelNextSignIn = false;
        back.searchParams.set("error", "access_denied");
        back.searchParams.set("error_description", "Abgebrochen");
      } else {
        const code = `code-${++this.counter}`;
        this.codes.set(code, this.nextSignIn);
        back.searchParams.set("code", code);
      }
      return { status: 302, headers: { location: back.toString() } };
    }
    if (method === "POST" && path === "token") {
      const body = isRecord(request.body) ? request.body : {};
      const grant = url.searchParams.get("grant_type");
      if (grant === "pkce") {
        const account = typeof body.auth_code === "string" ? this.codes.get(body.auth_code) : undefined;
        if (account === undefined || typeof body.code_verifier !== "string" || body.code_verifier === "") {
          return { status: 400, body: { code: 400, error_code: "flow_state_not_found", msg: "Ungültiger Code" } };
        }
        this.codes.delete(body.auth_code as string);
        return { status: 200, body: this.issueSession(account) };
      }
      if (grant === "refresh_token") {
        const token = typeof body.refresh_token === "string" ? body.refresh_token : "";
        const userId = this.refreshTokens.get(token);
        const account = userId === undefined ? undefined : this.accounts.get(userId);
        if (account === undefined) {
          return { status: 400, body: { code: 400, error_code: "refresh_token_not_found", msg: "Invalid Refresh Token: Refresh Token Not Found" } };
        }
        this.refreshTokens.delete(token);
        return { status: 200, body: this.issueSession(account) };
      }
    }
    if (method === "POST" && path === "logout") {
      // Wie der echte Anmelde-Server: Die Sitzung ist beendet, ihr Refresh-Token gilt nicht mehr. (Die Nachbildung
      // führt je Konto nur eine Sitzung.)
      const token = this.bearer(request);
      const userId = token === null ? undefined : this.accessTokens.get(token);
      if (token !== null) this.accessTokens.delete(token);
      if (userId !== undefined) {
        for (const [refreshToken, owner] of [...this.refreshTokens]) if (owner === userId) this.refreshTokens.delete(refreshToken);
      }
      return { status: 204 };
    }
    return { status: 404, body: { message: `Im Test nicht vorgesehen: ${method} /auth/v1/${path}` } };
  }

  // --- Datenschnittstelle (PostgREST) ---

  private bearer(request: FakeRequest): string | null {
    const header = request.headers.authorization ?? "";
    return header.startsWith("Bearer ") ? header.slice("Bearer ".length) : null;
  }

  /** Das Konto hinter dem Token; sonst die Antwort, mit der der Server ablehnt. */
  private caller(request: FakeRequest, what: string): InMemoryRoundSession | FakeResponse {
    if ((request.headers.apikey ?? "") === "") return { status: 401, body: { message: "No API key found in request" } };
    const token = this.bearer(request);
    // Ohne Anmeldung schickt supabase-js den öffentlichen Schlüssel: Dann fragt die Rolle „anon“, die nichts darf.
    if (token === null || token === request.headers.apikey) return restError(401, "42501", `permission denied for ${what}`);
    const userId = this.accessTokens.get(token);
    if (userId === undefined) return restError(401, "PGRST301", "JWT expired");
    return this.session(userId);
  }

  private async select(request: FakeRequest, name: string): Promise<FakeResponse | typeof NETWORK_FAILURE> {
    if (request.method !== "GET") return restError(405, "PGRST105", "Schreiben geht nur über die Funktionen");
    if (!(name in COLUMNS)) return restError(404, "PGRST205", `Could not find the table 'public.${name}' in the schema cache`);
    const table = name as Table;
    const caller = this.caller(request, `table ${table}`);
    if ("status" in caller) return caller;
    const { url } = request;
    const columns = (url.searchParams.get("select") ?? "*").split(",");
    const unknown = columns.find((column) => !(COLUMNS[table] as readonly string[]).includes(column));
    if (unknown !== undefined) return restError(400, "42703", `column ${table}.${unknown} does not exist`);
    try {
      const all = await this.rowsOf(caller, table, url);
      const limit = url.searchParams.get("limit");
      const limited = limit === null ? all : all.slice(0, Number(limit));
      return { status: 200, body: limited.map((row) => Object.fromEntries(columns.map((column) => [column, row[column]]))) };
    } catch (error) {
      return this.failure(error);
    }
  }

  private async rowsOf(caller: InMemoryRoundSession, table: Table, url: URL): Promise<Array<Record<string, unknown>>> {
    const order = url.searchParams.get("order");
    switch (table) {
      case "rounds": {
        const id = filter(url, "id", "eq");
        if (id === null) throw new FilterError("rounds ohne id");
        const head = await caller.head(id);
        if (head === null) return [];
        const rules = head.rulesUnreadable ? { name: "Regeln von morgen", payoutRule: "NOCH_UNBEKANNT" } : head.rules === null ? null : rulesDocument(head.rules);
        return [{ id: head.id, name: head.name, rev: head.rev, rules, created_by: head.createdBy }];
      }
      case "round_members": {
        const round = filter(url, "round_id", "eq");
        if (round === null || order !== "joined_at.asc") throw new FilterError("round_members");
        return (await caller.members(round)).map((member) => {
          const key = `${round}/${member.userId}`;
          if (!this.joined.has(key)) this.joined.set(key, this.joined.size);
          const joinedAt = new Date(Date.UTC(2026, 10, 19) + (this.joined.get(key) ?? 0) * 1000).toISOString();
          return { user_id: member.userId, player_id: member.playerId, joined_at: joinedAt };
        });
      }
      case "round_players": {
        const round = filter(url, "round_id", "eq");
        const after = filter(url, "rev", "gt");
        if (round === null || after === null || order !== "rev.asc") throw new FilterError("round_players");
        return (await caller.playersSince(round, Number(after))).map(playerRow);
      }
      case "round_evenings": {
        const round = filter(url, "round_id", "eq");
        if (round === null) throw new FilterError("round_evenings ohne round_id");
        const id = filter(url, "id", "eq");
        if (id !== null) {
          const row = await caller.evening(round, id);
          return row === null ? [] : [eveningRowOf(row)];
        }
        const after = filter(url, "rev", "gt");
        if (after === null || order !== "rev.asc") throw new FilterError("round_evenings");
        return (await caller.eveningsSince(round, Number(after))).map(eveningRowOf);
      }
    }
  }

  private async rpc(request: FakeRequest, name: string): Promise<FakeResponse | typeof NETWORK_FAILURE> {
    const parameters = FUNCTIONS[name];
    const args = isRecord(request.body) ? request.body : {};
    const given = Object.keys(args).sort().join(", ");
    if (request.method !== "POST" || parameters === undefined || [...parameters].sort().join(", ") !== given) {
      return restError(404, "PGRST202", `Could not find the function public.${name}(${given}) in the schema cache`);
    }
    const caller = this.caller(request, `function ${name}`);
    if ("status" in caller) return caller;
    // PostgreSQL kann das Nullzeichen in keinem Text speichern und lehnt die Anfrage ab, bevor die Funktion läuft.
    if (containsNul(args)) return restError(400, "22P05", "unsupported Unicode escape sequence");
    const text = (key: string): string => (typeof args[key] === "string" ? (args[key] as string) : "");
    const whole = (key: string): number => (typeof args[key] === "number" ? (args[key] as number) : Number.NaN);
    try {
      switch (name) {
        case "create_round":
          return { status: 200, body: await caller.createRound(text("p_name")) };
        case "my_rounds":
          return {
            status: 200,
            body: (await caller.myRounds()).map((round) => ({
              id: round.id,
              name: round.name,
              rev: round.rev,
              member_count: round.memberCount,
              my_player_id: round.myPlayerId,
              is_creator: round.isCreator,
            })),
          };
        case "create_invite":
          return { status: 200, body: await caller.createInvite(text("p_round")) };
        case "invite_info": {
          const info = await caller.inviteInfo(text("p_token"));
          return {
            status: 200,
            body: [{ round_id: info.roundId, round_name: info.roundName, member_count: info.memberCount, already_member: info.alreadyMember }],
          };
        }
        case "join_round":
          return { status: 200, body: await caller.joinRound(text("p_token")) };
        case "leave_round":
          await caller.leaveRound(text("p_round"));
          return { status: 204 };
        case "claim_player":
          await caller.claimPlayer(text("p_round"), text("p_player"), text("p_name"), whole("p_color_index"));
          return { status: 204 };
        case "save_player": {
          const player = { id: text("p_id"), name: text("p_name"), colorIndex: whole("p_color_index"), archived: args.p_archived === true };
          return { status: 200, body: await caller.savePlayer(text("p_round"), player, args.p_deleted === true) };
        }
        case "save_rules": {
          if (!isRecord(args.p_rules)) return raised("invalid_doc");
          if (JSON.stringify(args.p_rules).length > MAX_RULES_BYTES) return raised("too_large");
          return { status: 200, body: await caller.saveRules(text("p_round"), decodeRules(JSON.stringify(args.p_rules))) };
        }
        default: {
          const doc = args.p_doc;
          if (!isRecord(doc) || typeof doc.id !== "string" || doc.id !== args.p_id) return raised("invalid_doc");
          if (JSON.stringify(doc).length > MAX_EVENING_BYTES) return raised("too_large");
          let evening: Evening;
          try {
            evening = decodeEvening(JSON.stringify(doc));
          } catch {
            // Der echte Server nähme jedes JSON-Objekt mit passender Kennung; die Nachbildung kennt nur lesbare Abende.
            return raised("invalid_doc");
          }
          const saved = await caller.saveEvening(text("p_round"), evening, whole("p_expected_version"), args.p_deleted === true);
          return { status: 200, body: saved === null ? [] : [{ version: saved.version, rev: saved.rev }] };
        }
      }
    } catch (error) {
      return this.failure(error);
    }
  }

  /** Macht aus einem Fehler des Servers im Speicher die Antwort, die der echte Server gäbe. */
  private failure(error: unknown): FakeResponse | typeof NETWORK_FAILURE {
    if (error instanceof FilterError) return restError(400, "PGRST100", `Im Vertrag nicht vorgesehen: ${error.message}`);
    if (!(error instanceof RoundException)) throw error;
    // „Kein Netz“ und „Antwort verloren“ des Zugangs: Die Anfrage (oder ihre Antwort) kommt nicht an.
    if (error.error === "OFFLINE") return NETWORK_FAILURE;
    if (error.error === "SIGNED_OUT") return restError(401, "PGRST301", "JWT expired");
    if (error.error === "SERVER") return { status: 500, body: { message: "Fehler des Servers" } };
    return raised(error.error.toLowerCase());
  }
}

function playerRow(player: RemotePlayer): Record<string, unknown> {
  return {
    id: player.id,
    name: player.name,
    color_index: player.colorIndex,
    archived: player.archived,
    position: player.position,
    owner: player.owner,
    name_locked: player.nameLocked,
    deleted: player.deleted,
    rev: player.rev,
  };
}

function eveningRowOf(row: RemoteEvening): Record<string, unknown> {
  // Ein Dokument, das diese Version nicht lesen kann: wie von einer neueren App geschrieben.
  const doc = row.evening === null ? { id: row.id, schemaVersion: 99, neu: true } : eveningDocument(row.evening);
  return { id: row.id, doc, version: row.version, deleted: row.deleted, rev: row.rev };
}

/**
 * Hängt das nachgestellte Projekt als `fetch` an supabase-js: Jede Anfrage wird direkt beantwortet, ohne Netz.
 */
export function fakeFetch(fake: FakeSupabase): typeof fetch {
  return async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const text = await request.clone().text();
    let body: unknown;
    try {
      body = text === "" ? undefined : (JSON.parse(text) as unknown);
    } catch {
      body = text;
    }
    const headers: Record<string, string> = {};
    request.headers.forEach((value, key) => {
      headers[key.toLowerCase()] = value;
    });
    if (request.signal.aborted) throw new DOMException("Abgebrochen", "AbortError");
    const answer = await fake.handle({ method: request.method, url: new URL(request.url), headers, body });
    if (answer === NETWORK_FAILURE) throw new TypeError("Failed to fetch");
    const noContent = answer.body === undefined || answer.status === 204;
    return new Response(noContent ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { ...(noContent ? {} : { "Content-Type": "application/json" }), ...answer.headers },
    });
  };
}
