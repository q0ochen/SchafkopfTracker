/*
 * Die Anmeldung über supabase-js gegen ein nachgestelltes Projekt: nur Google, mit PKCE, Rückkehr auf die
 * Start-Adresse der Web-App (docs/ARCHITEKTUR.md, Abschnitt 8.1).
 */
import { describe, expect, it } from "vitest";
import { errorOf } from "../../core/round/testing/fixtures";
import type { Account } from "../backend";
import { FakeSupabase, fakeFetch, NETWORK_FAILURE, type FakeAccount } from "../testing/fakeSupabase";
import { MemoryStorage } from "../testing/memoryStorage";
import { AUTH_STORAGE_KEY, createSupabaseBackend } from "./index";

const SUPABASE = "https://projekt.supabase.co";
const KEY = "sb_publishable_nur_fuer_tests";
const START_URL = "https://example.org/SchafkopfTracker/";
const ONE: FakeAccount = { userId: "konto-1", email: "konto-1@example.org" };

function setUp(options: { stored?: Record<string, unknown>; waitMillis?: number } = {}) {
  const fake = new FakeSupabase();
  const storage = new MemoryStorage();
  if (options.stored !== undefined) storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(options.stored));
  const opened: string[] = [];
  const create = () =>
    createSupabaseBackend(
      { url: SUPABASE, publishableKey: KEY },
      {
        redirectTo: START_URL,
        openUrl: (url) => opened.push(url),
        storage,
        fetch: fakeFetch(fake),
        autoRefresh: false,
        waitMillis: options.waitMillis,
      },
    );
  return { fake, storage, opened, create, ...create() };
}

/** Der Code aus der Rückkehr-Adresse, wie ihn der Browser nach der Anmeldung mitbringt. */
async function codeFor(fake: FakeSupabase, authorizeUrl: string): Promise<string> {
  const answer = await fake.handle({ method: "GET", url: new URL(authorizeUrl), headers: {}, body: undefined });
  if (answer === NETWORK_FAILURE) throw new Error("kein Netz");
  expect(answer.status).toBe(302);
  const back = new URL(answer.headers?.location ?? "");
  expect(`${back.origin}${back.pathname}`).toBe(START_URL);
  return back.searchParams.get("code") ?? "";
}

describe("Anmelden", () => {
  it("öffnet die Anmeldung bei Google mit PKCE und der Start-Adresse als Rückkehr", async () => {
    const { auth, opened, storage, fake } = setUp();
    expect(await auth.load()).toBeNull();

    await auth.signIn();

    expect(opened).toHaveLength(1);
    const url = new URL(opened[0]);
    expect(`${url.origin}${url.pathname}`).toBe(`${SUPABASE}/auth/v1/authorize`);
    expect(url.searchParams.get("provider")).toBe("google");
    expect(url.searchParams.get("redirect_to")).toBe(START_URL);
    expect(url.searchParams.get("code_challenge_method")).toBe("s256");
    expect(url.searchParams.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    // Nur die üblichen Angaben des Kontos (openid, email, profile): keine weiteren Bereiche.
    expect(url.searchParams.get("scopes")).toBeNull();
    // Der Schlüssel zum Code bleibt auf dem Gerät und übersteht das Verlassen der Seite.
    expect(storage.getItem(`${AUTH_STORAGE_KEY}-code-verifier`)).toBeTruthy();
    // Bis hierher ging nichts ans Netz.
    expect(fake.requests).toEqual([]);
  });

  it("löst den Code aus der Rückkehr-Adresse gegen eine Sitzung ein, auch nach dem Neuladen der Seite", async () => {
    const before = setUp();
    await before.auth.signIn();
    const code = await codeFor(before.fake, before.opened[0]);
    // Die Seite wurde verlassen und neu geladen: eine neue Anbindung über demselben Speicher.
    const { auth } = before.create();
    const seen: Array<Account | null> = [];
    auth.subscribe((account) => seen.push(account));

    const account = await auth.completeSignIn(code);

    expect(account).toEqual(ONE);
    expect(auth.account()).toEqual(ONE);
    expect(seen).toEqual([ONE]);
    const token = before.fake.calls("POST", "/auth/v1/token")[0];
    expect(token.url.searchParams.get("grant_type")).toBe("pkce");
    expect(token.body).toMatchObject({ auth_code: code });
    expect((token.body as { code_verifier: string }).code_verifier.length).toBeGreaterThan(40);
    expect(before.storage.getItem(AUTH_STORAGE_KEY)).toContain("erneuern.konto-1");
    // Beim nächsten Start ist das Konto ohne Netz wieder da.
    before.fake.offline = true;
    expect(await before.create().auth.load()).toEqual(ONE);
  });

  it("ein Konto ohne E-Mail-Adresse ist trotzdem angemeldet", async () => {
    const { fake, auth, opened } = setUp();
    fake.nextSignIn = { userId: "konto-9", email: "" };
    await auth.signIn();

    expect(await auth.completeSignIn(await codeFor(fake, opened[0]))).toEqual({ userId: "konto-9", email: null });
  });

  it("ein falscher oder schon benutzter Code meldet niemanden an", async () => {
    const { fake, auth, opened } = setUp();
    await auth.signIn();
    const code = await codeFor(fake, opened[0]);

    expect(await errorOf(() => auth.completeSignIn("falsch"))).toBe("SIGNED_OUT");
    expect(auth.account()).toBeNull();
    // Nach einem gescheiterten Versuch beginnt die Anmeldung von vorn; der alte Code gilt nicht mehr.
    expect(await errorOf(() => auth.completeSignIn(code))).toBe("SIGNED_OUT");
    await auth.signIn();
    const fresh = await codeFor(fake, opened[1]);
    expect(await auth.completeSignIn(fresh)).toEqual(ONE);
    expect(await errorOf(() => auth.completeSignIn(fresh))).toBe("SIGNED_OUT");
    expect(auth.account()).toEqual(ONE);
  });

  it("ohne begonnene Anmeldung (kein Schlüssel zum Code) scheitert das Einlösen", async () => {
    const { auth } = setUp();

    expect(await errorOf(() => auth.completeSignIn("code-ohne-anfang"))).toBe("SIGNED_OUT");
  });

  it("ohne Netz scheitert das Einlösen mit OFFLINE", async () => {
    const { fake, auth, opened } = setUp();
    await auth.signIn();
    const code = await codeFor(fake, opened[0]);
    fake.offline = true;

    expect(await errorOf(() => auth.completeSignIn(code))).toBe("OFFLINE");
    expect(auth.account()).toBeNull();
  });
});

describe("gespeicherte Anmeldung", () => {
  it("ohne gespeicherte Sitzung ist niemand angemeldet", async () => {
    const { auth, fake } = setUp();

    expect(await auth.load()).toBeNull();
    expect(auth.account()).toBeNull();
    expect(fake.requests).toEqual([]);
  });

  it("eine gültige Sitzung meldet das Konto ohne Netz an", async () => {
    const fake = new FakeSupabase();
    const { auth } = setUp({ stored: fake.issueSession(ONE) });

    expect(await auth.load()).toEqual(ONE);
  });

  it("ist die Sitzung abgelaufen und das Netz weg, bleibt das zuletzt bekannte Konto angemeldet", async () => {
    const source = new FakeSupabase();
    const stored = { ...source.issueSession(ONE), expires_at: Math.floor(Date.now() / 1000) - 60 };
    // supabase-js versucht das Erneuern ohne Netz lange immer wieder; die App wartet darauf nur kurz.
    const { auth, fake, storage } = setUp({ stored, waitMillis: 100 });
    fake.offline = true;
    const started = Date.now();

    expect(await auth.load()).toEqual(ONE);
    expect(Date.now() - started).toBeLessThan(2000);
    expect(storage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
  });

  it("ist die Sitzung abgelaufen und kennt der Server sie nicht mehr, ist das Gerät abgemeldet", async () => {
    const source = new FakeSupabase();
    const stored = { ...source.issueSession(ONE), expires_at: Math.floor(Date.now() / 1000) - 60 };
    // Dieses Projekt kennt die Sitzung nicht (sie stammt aus einem anderen).
    const { auth, storage } = setUp({ stored });

    expect(await auth.load()).toBeNull();
    expect(storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("ein gestörter Speicher bringt nichts zum Absturz: Die Anmeldung hält dann bis zum Schließen der Seite", async () => {
    const { fake, auth, opened, storage, create } = setUp();
    storage.broken = true;

    expect(await auth.load()).toBeNull();
    await auth.signIn();
    expect(await auth.completeSignIn(await codeFor(fake, opened[0]))).toEqual(ONE);
    expect(auth.account()).toEqual(ONE);
    await auth.signOut();
    expect(auth.account()).toBeNull();
    // Im Speicher des Browsers stand nie etwas.
    storage.broken = false;
    expect(storage.values.size).toBe(0);
    expect(await create().auth.load()).toBeNull();
  });
});

describe("Abmelden", () => {
  it("meldet nur dieses Gerät ab und vergisst die Sitzung", async () => {
    const fake = new FakeSupabase();
    const context = setUp({ stored: fake.issueSession(ONE) });
    // Die Sitzung stammt aus einem anderen Projekt; für das Abmelden spielt das keine Rolle.
    await context.auth.load();
    const seen: Array<Account | null> = [];
    context.auth.subscribe((account) => seen.push(account));

    await context.auth.signOut();

    const logout = context.fake.calls("POST", "/auth/v1/logout");
    expect(logout).toHaveLength(1);
    expect(logout[0].url.searchParams.get("scope")).toBe("local");
    expect(context.storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    expect(context.auth.account()).toBeNull();
    expect(seen).toEqual([null]);
    expect(await context.create().auth.load()).toBeNull();
  });

  it("klappt auch ohne Netz", async () => {
    const fake = new FakeSupabase();
    const context = setUp({ stored: fake.issueSession(ONE) });
    await context.auth.load();
    context.fake.offline = true;

    await context.auth.signOut();

    expect(context.storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    expect(context.auth.account()).toBeNull();
  });

  it("klappt auch mit abgelaufener Sitzung ohne Netz", async () => {
    const fake = new FakeSupabase();
    const stored = { ...fake.issueSession(ONE), expires_at: Math.floor(Date.now() / 1000) - 60 };
    const context = setUp({ stored, waitMillis: 100 });
    context.fake.offline = true;
    await context.auth.load();
    expect(context.auth.account()).toEqual(ONE);
    const started = Date.now();

    await context.auth.signOut();

    expect(Date.now() - started).toBeLessThan(2000);
    expect(context.storage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    expect(context.auth.account()).toBeNull();
    // Auch nach dem Neuladen der Seite ist niemand mehr angemeldet.
    expect(await context.create().auth.load()).toBeNull();
  });
});
