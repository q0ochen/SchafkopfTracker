/*
 * Der Server der gemeinsamen Runde über supabase-js (docs/ARCHITEKTUR.md, Abschnitt 8.3): gelesen wird direkt aus
 * den Tabellen (der Server zeigt jedem Konto nur seine Runden), geschrieben nur über die Funktionen des Servers.
 *
 * Jeder Aufruf scheitert höchstens mit einer `RoundException`:
 * - ohne Netz, bei einer Zeitüberschreitung oder wenn sich die Anmeldung gerade nicht erneuern lässt: `OFFLINE`,
 * - ohne gültige Anmeldung (keine Sitzung, Antwort 401, ein Fehler der Gruppe PGRST30x): `SIGNED_OUT`; nach einer
 *   Antwort 401 wird vorher genau einmal mit einer erneuerten Sitzung wiederholt. Kennt der Server die Sitzung
 *   sicher nicht mehr, wird sie auch auf dem Gerät vergessen,
 * - mit einem Fehlercode der Funktionen (`code` P0001, der Code steht in `message`): der passende Fehler,
 * - sonst `SERVER`.
 *
 * Ein Abend-Dokument ist das Abend-Objekt des Dateiformats (wie in der Sicherung), das Regelwerk dessen Darstellung
 * des Regelwerks. Was sich nicht lesen lässt (auch: eine neuere `schemaVersion`), ergibt `evening = null` bzw.
 * `rulesUnreadable = true`, nie eine Ausnahme.
 */
import { isAuthApiError, isAuthRetryableFetchError, type AuthError, type SupabaseClient } from "@supabase/supabase-js";
import type { Evening, EveningId, Player, PlayerId, RuleSet } from "../../core/model";
import {
  RoundError,
  RoundException,
  type InviteInfo,
  type RemoteEvening,
  type RemotePlayer,
  type RoundHead,
  type RoundId,
  type RoundMember,
  type RoundSummary,
  type SavedEvening,
} from "../../core/round/RoundModels";
import type { RoundApi } from "../../core/round/RoundPorts";
import { decodeEvening, decodeRulesOrNull, encodeEvening, encodeRules } from "../../data/format";
import { TimeoutError, within } from "./within";

/** So viele Abende kommen höchstens je Anfrage. */
const PAGE_SIZE = 100;

/** Mehr Seiten gibt es nie: Eine Runde hat höchstens 3000 Abende. Die Grenze schützt vor einem Server, der den Filter nicht beachtet. */
const MAX_PAGES = 40;

/** So lange darf eine Anfrage dauern. Der Abgleich läuft im Hintergrund und soll nie lange am Netz hängen. */
const TIMEOUT_MILLIS = 20_000;

/** So lange wird höchstens auf die Sitzung gewartet (sie liegt auf dem Gerät; nur das Erneuern braucht das Netz). */
const SESSION_WAIT_MILLIS = 8000;

/** SQLSTATE der Fehler, die die Funktionen des Servers selbst auslösen. */
const RAISED_EXCEPTION = "P0001";

/** Mit diesen Antworten auf das Erneuern ist die Sitzung endgültig vorbei (wie in der Android-App). */
const GONE_CODES: ReadonlySet<string> = new Set([
  "invalid_grant",
  "refresh_token_not_found",
  "refresh_token_already_used",
  "session_not_found",
  "session_expired",
  "user_not_found",
  "user_banned",
  "bad_jwt",
]);

/** Mit diesen Antworten hat der Server das Erneuern abgelehnt: Neu anmelden hilft. */
const REJECTED_STATUS: ReadonlySet<number> = new Set([400, 401, 403, 404]);

/** Was supabase-js auf eine Anfrage antwortet. */
interface Answer {
  readonly data: unknown;
  readonly error: { readonly message?: unknown; readonly code?: unknown } | null;
  readonly status: number;
}

/** Schickt eine Anfrage ab; `signal` bricht sie nach der erlaubten Zeit ab. */
type Send = (signal: AbortSignal) => PromiseLike<Answer>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function unreadable(): RoundException {
  return new RoundException("SERVER", "Antwort nicht lesbar");
}

function rows(data: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(data) || !data.every(isRecord)) throw unreadable();
  return data as Array<Record<string, unknown>>;
}

function text(value: unknown): string {
  if (typeof value !== "string") throw unreadable();
  return value;
}

function textOrNull(value: unknown): string | null {
  return value === null || value === undefined ? null : text(value);
}

function whole(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw unreadable();
  return value;
}

function flag(value: unknown): boolean {
  if (typeof value !== "boolean") throw unreadable();
  return value;
}

function codeOf(answer: Answer): string {
  return typeof answer.error?.code === "string" ? answer.error.code : "";
}

/** Die Sitzung wurde abgelehnt: Antwort 401 oder ein Fehler der Datenschnittstelle rund um das Token (PGRST300 bis 303). */
function isUnauthorized(answer: Answer): boolean {
  return answer.error !== null && (answer.status === 401 || /^PGRST30\d$/.test(codeOf(answer)));
}

/** Kurzer technischer Zusatz für die Fehlersuche: Status und Code, nie Inhalt. */
function describe(answer: Answer): string {
  const code = codeOf(answer);
  return code === "" || code === RAISED_EXCEPTION ? `HTTP ${answer.status}` : `HTTP ${answer.status} ${code}`;
}

/** SQLSTATE-Klasse 22: Die Datenbank lehnt einen Wert ab („data exception“). */
const DATA_EXCEPTION_CLASS = "22";

/** Die Datenschnittstelle konnte den gesendeten Inhalt nicht lesen. */
const INVALID_BODY = "PGRST102";

function toException(answer: Answer): RoundException {
  // supabase-js meldet einen Netzfehler oder eine abgebrochene Anfrage als Fehler mit dem Status 0.
  if (answer.status === 0) return new RoundException("OFFLINE");
  const code = codeOf(answer);
  if (code === RAISED_EXCEPTION && typeof answer.error?.message === "string") {
    const known = RoundError.fromCode(answer.error.message.trim());
    if (known !== null) return new RoundException(known);
  }
  // Die Datenbank kann mit dem Inhalt nichts anfangen (zum Beispiel ein Nullzeichen in einem Text): Das trifft nur
  // dieses eine Stück und wird durch Wiederholen nicht besser (wie in der Android-App, `SupabaseRoundApi.call`).
  if (answer.status === 400 && (code.startsWith(DATA_EXCEPTION_CLASS) || code === INVALID_BODY)) {
    return new RoundException("INVALID_DOC", `HTTP 400 ${code}`);
  }
  return new RoundException("SERVER", describe(answer));
}

/** Ein Signal, das nach `millis` abbricht, und eine Funktion zum Aufräumen. */
function timeout(millis: number): { signal: AbortSignal; done: () => void } {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), millis);
  return { signal: controller.signal, done: () => clearTimeout(timer) };
}

/**
 * Wartet höchstens `millis` auf einen Aufruf der Anmeldung; dauert es länger, gilt das als „kein Netz“. (supabase-js
 * versucht das Erneuern einer Sitzung ohne Verbindung bis zu einer halben Minute lang immer wieder; so lange soll
 * kein Abgleich hängen.)
 */
async function awaited<T>(work: PromiseLike<T>, millis: number): Promise<T> {
  try {
    return await within(work, millis);
  } catch (error) {
    if (error instanceof TimeoutError) throw new RoundException("OFFLINE", "Anmeldung");
    throw error;
  }
}

export class SupabaseRoundApi implements RoundApi {
  private readonly sessionWaitMillis: number;

  constructor(
    private readonly client: SupabaseClient,
    private readonly timeoutMillis: number = TIMEOUT_MILLIS,
  ) {
    this.sessionWaitMillis = Math.min(timeoutMillis, SESSION_WAIT_MILLIS);
  }

  // --- Lesen ---

  async head(round: RoundId): Promise<RoundHead | null> {
    const data = await this.call((signal) =>
      this.client.from("rounds").select("id,name,rev,rules,created_by").eq("id", round).abortSignal(signal).retry(false),
    );
    const row = rows(data)[0];
    if (row === undefined) return null;
    const document = row.rules ?? null;
    const rules = document === null ? null : rulesOrNull(document);
    return {
      id: text(row.id),
      name: text(row.name),
      rev: whole(row.rev),
      rules,
      createdBy: textOrNull(row.created_by),
      rulesUnreadable: document !== null && rules === null,
    };
  }

  async members(round: RoundId): Promise<RoundMember[]> {
    const data = await this.call((signal) =>
      this.client
        .from("round_members")
        .select("user_id,player_id,joined_at")
        .eq("round_id", round)
        .order("joined_at", { ascending: true })
        .abortSignal(signal)
        .retry(false),
    );
    return rows(data).map((row) => ({ userId: text(row.user_id), playerId: textOrNull(row.player_id) }));
  }

  async playersSince(round: RoundId, rev: number): Promise<RemotePlayer[]> {
    const data = await this.call((signal) =>
      this.client
        .from("round_players")
        .select("id,name,color_index,archived,position,owner,name_locked,deleted,rev")
        .eq("round_id", round)
        .gt("rev", rev)
        .order("rev", { ascending: true })
        .abortSignal(signal)
        .retry(false),
    );
    return rows(data).map((row) => ({
      id: text(row.id),
      name: text(row.name),
      colorIndex: whole(row.color_index),
      archived: flag(row.archived),
      position: whole(row.position),
      owner: textOrNull(row.owner),
      nameLocked: flag(row.name_locked),
      deleted: flag(row.deleted),
      rev: whole(row.rev),
    }));
  }

  async eveningsSince(round: RoundId, rev: number): Promise<RemoteEvening[]> {
    const all: RemoteEvening[] = [];
    let after = rev;
    // Seitenweise, solange volle Seiten kommen, jeweils nach dem größten gelesenen `rev` weiter.
    for (let page = 0; page < MAX_PAGES; page++) {
      const from = after;
      const data = await this.call((signal) =>
        this.client
          .from("round_evenings")
          .select("id,doc,version,deleted,rev")
          .eq("round_id", round)
          .gt("rev", from)
          .order("rev", { ascending: true })
          .limit(PAGE_SIZE)
          .abortSignal(signal)
          .retry(false),
      );
      const found = rows(data).map(eveningRow);
      all.push(...found);
      const last = found.at(-1)?.rev;
      if (found.length < PAGE_SIZE || last === undefined || last <= after) break;
      after = last;
    }
    return all;
  }

  async evening(round: RoundId, id: EveningId): Promise<RemoteEvening | null> {
    const data = await this.call((signal) =>
      this.client
        .from("round_evenings")
        .select("id,doc,version,deleted,rev")
        .eq("round_id", round)
        .eq("id", id)
        .abortSignal(signal)
        .retry(false),
    );
    const row = rows(data)[0];
    return row === undefined ? null : eveningRow(row);
  }

  // --- Schreiben über die Funktionen des Servers ---

  async saveEvening(round: RoundId, evening: Evening, expectedVersion: number, deleted = false): Promise<SavedEvening | null> {
    const data = await this.rpc("save_evening", {
      p_round: round,
      p_id: evening.id,
      p_doc: document(() => encodeEvening(evening)),
      p_expected_version: expectedVersion,
      p_deleted: deleted,
    });
    // Passt die erwartete Fassung nicht, kommt kein Fehler, sondern nichts.
    const row = rows(data)[0];
    return row === undefined ? null : { version: whole(row.version), rev: whole(row.rev) };
  }

  async savePlayer(round: RoundId, player: Player, deleted = false): Promise<number> {
    return whole(
      await this.rpc("save_player", {
        p_round: round,
        p_id: player.id,
        p_name: player.name,
        p_color_index: player.colorIndex,
        p_archived: player.archived,
        p_deleted: deleted,
      }),
    );
  }

  async saveRules(round: RoundId, rules: RuleSet): Promise<number> {
    return whole(await this.rpc("save_rules", { p_round: round, p_rules: document(() => encodeRules(rules)) }));
  }

  async myRounds(): Promise<RoundSummary[]> {
    return rows(await this.rpc("my_rounds", {})).map((row) => ({
      id: text(row.id),
      name: text(row.name),
      rev: whole(row.rev),
      memberCount: whole(row.member_count),
      myPlayerId: textOrNull(row.my_player_id),
      isCreator: flag(row.is_creator),
    }));
  }

  async createRound(name: string): Promise<RoundId> {
    return text(await this.rpc("create_round", { p_name: name }));
  }

  async createInvite(round: RoundId): Promise<string> {
    return text(await this.rpc("create_invite", { p_round: round }));
  }

  async inviteInfo(token: string): Promise<InviteInfo> {
    const row = rows(await this.rpc("invite_info", { p_token: token }))[0];
    if (row === undefined) throw new RoundException("INVALID_INVITE");
    return {
      roundId: text(row.round_id),
      roundName: text(row.round_name),
      memberCount: whole(row.member_count),
      alreadyMember: flag(row.already_member),
    };
  }

  async joinRound(token: string): Promise<RoundId> {
    return text(await this.rpc("join_round", { p_token: token }));
  }

  async leaveRound(round: RoundId): Promise<void> {
    await this.rpc("leave_round", { p_round: round });
  }

  async claimPlayer(round: RoundId, player: PlayerId, name: string, colorIndex: number): Promise<void> {
    await this.rpc("claim_player", { p_round: round, p_player: player, p_name: name, p_color_index: colorIndex });
  }

  // --- Gemeinsames ---

  private rpc(name: string, args: Record<string, unknown>): Promise<unknown> {
    return this.call((signal) => this.client.rpc(name, args).abortSignal(signal));
  }

  /** Führt den Aufruf im Namen des Kontos aus und liefert den Inhalt der Antwort. */
  private async call(send: Send): Promise<unknown> {
    await this.requireSession();
    let answer = await this.attempt(send);
    if (isUnauthorized(answer)) {
      // Die Uhr des Geräts kann falsch gehen: Dann hält es ein Token für gültig, das der Server schon ablehnt.
      await this.refreshSession();
      answer = await this.attempt(send);
      if (isUnauthorized(answer)) throw new RoundException("SIGNED_OUT", describe(answer));
    }
    if (answer.error === null) return answer.data;
    throw toException(answer);
  }

  private async attempt(send: Send): Promise<Answer> {
    const limit = timeout(this.timeoutMillis);
    try {
      return await send(limit.signal);
    } catch (error) {
      // supabase-js fängt Netzfehler selbst ab; was trotzdem hier ankommt, kam jedenfalls nicht vom Server.
      throw new RoundException("OFFLINE", error instanceof Error ? error.name : null, error);
    } finally {
      limit.done();
    }
  }

  /** Ohne Anmeldung gar nicht erst fragen: Der Server lehnte ohnehin ab. */
  private async requireSession(): Promise<void> {
    let result: Awaited<ReturnType<SupabaseClient["auth"]["getSession"]>>;
    try {
      result = await awaited(this.client.auth.getSession(), this.sessionWaitMillis);
    } catch (error) {
      if (error instanceof RoundException) throw error;
      throw new RoundException("SERVER", "Anmeldung nicht lesbar", error);
    }
    if (result.data.session) return;
    // Die Sitzung ist abgelaufen und ließ sich gerade nicht erneuern (kein Netz): Sie bleibt, später noch einmal.
    if (result.error && isAuthRetryableFetchError(result.error)) throw new RoundException("OFFLINE", "Anmeldung");
    throw new RoundException("SIGNED_OUT");
  }

  /** Nach einer Antwort 401: die Sitzung erneuern. Scheitert das, sagt der Fehler, ob neu anmelden hilft. */
  private async refreshSession(): Promise<void> {
    let result: Awaited<ReturnType<SupabaseClient["auth"]["refreshSession"]>>;
    try {
      result = await awaited(this.client.auth.refreshSession(), this.sessionWaitMillis);
    } catch (error) {
      if (error instanceof RoundException) throw error;
      throw new RoundException("SERVER", "Erneuern", error);
    }
    if (result.data.session) return;
    const error: AuthError | null = result.error;
    if (error && isAuthRetryableFetchError(error)) throw new RoundException("OFFLINE", "Anmeldung");
    if (error && isAuthApiError(error)) {
      if (typeof error.code === "string" && GONE_CODES.has(error.code)) {
        // Der Server kennt die Sitzung sicher nicht mehr: auch hier vergessen, damit nicht jeder Abgleich neu fragt.
        await this.forgetSession();
        throw new RoundException("SIGNED_OUT", "Sitzung beendet");
      }
      if (!REJECTED_STATUS.has(error.status)) throw new RoundException("SERVER", `Anmeldung: HTTP ${error.status}`);
    }
    throw new RoundException("SIGNED_OUT", "Erneuern");
  }

  private async forgetSession(): Promise<void> {
    try {
      await within(this.client.auth.signOut({ scope: "local" }), this.sessionWaitMillis);
    } catch {
      // Dann bleibt sie stehen, bis sie abläuft; der Abgleich meldet weiter „Anmeldung abgelaufen“.
    }
  }
}

/** Ein Dokument für den Server: der Text des Dateiformats als JSON-Objekt. */
function document(encode: () => string): unknown {
  try {
    return JSON.parse(encode()) as unknown;
  } catch (error) {
    // Ein Abend, der sich nicht schreiben lässt, gilt wie einer, den der Server nicht annimmt: Er bleibt hier liegen.
    throw new RoundException("INVALID_DOC", "nicht schreibbar", error);
  }
}

function eveningRow(row: Record<string, unknown>): RemoteEvening {
  const id = text(row.id);
  return { id, version: whole(row.version), deleted: flag(row.deleted), rev: whole(row.rev), evening: eveningOrNull(id, row.doc) };
}

/** Der Abend aus dem Dokument; `null`, wenn es sich nicht lesen lässt (zum Beispiel von einer neueren App). */
function eveningOrNull(id: EveningId, doc: unknown): Evening | null {
  if (!isRecord(doc)) return null;
  try {
    const evening = decodeEvening(JSON.stringify(doc));
    return evening.id === id ? evening : null;
  } catch {
    return null;
  }
}

function rulesOrNull(doc: unknown): RuleSet | null {
  if (!isRecord(doc)) return null;
  try {
    return decodeRulesOrNull(JSON.stringify(doc));
  } catch {
    return null;
  }
}
