/* Kleine Bausteine der gemeinsamen Runde: Konfiguration, Rückkehr aus der Anmeldung, gemerkte Einladung, Stellvertreter. */
import { describe, expect, it } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { RoundException } from "../core/round/RoundModels";
import { ROUND_API_METHODS } from "../core/round/RoundPorts";
import { errorOf } from "../core/round/testing/fixtures";
import { readRoundConfig } from "./config";
import { deferredApi } from "./deferredApi";
import { INVITE_REUSE_MILLIS, INVITE_STORAGE_KEY, ISSUED_INVITE_KEY, reusableInvite, storedInviteMemory, storedIssuedInvites } from "./inviteMemory";
import { parseSignInReturn } from "./signInReturn";
import { MemoryStorage } from "./testing/memoryStorage";

describe("Konfiguration", () => {
  it("ohne Adresse oder ohne Schlüssel gibt es keine gemeinsame Runde", () => {
    expect(readRoundConfig(undefined, undefined)).toBeNull();
    expect(readRoundConfig("", "")).toBeNull();
    expect(readRoundConfig("https://abc.supabase.co", undefined)).toBeNull();
    expect(readRoundConfig("https://abc.supabase.co", "   ")).toBeNull();
    expect(readRoundConfig(undefined, "sb_publishable_x")).toBeNull();
  });

  it("Adresse und Schlüssel werden bereinigt übernommen", () => {
    expect(readRoundConfig(" https://abc.supabase.co/ ", " sb_publishable_x ")).toEqual({
      url: "https://abc.supabase.co",
      publishableKey: "sb_publishable_x",
    });
    expect(readRoundConfig("https://abc.supabase.co///", "x")?.url).toBe("https://abc.supabase.co");
  });

  it("verlangt https; http nur für einen Server auf dem eigenen Rechner", () => {
    expect(readRoundConfig("http://abc.supabase.co", "x")).toBeNull();
    expect(readRoundConfig("ftp://abc.supabase.co", "x")).toBeNull();
    expect(readRoundConfig("abc.supabase.co", "x")).toBeNull();
    expect(readRoundConfig("keine adresse", "x")).toBeNull();
    expect(readRoundConfig("http://127.0.0.1:54321", "x")).toEqual({ url: "http://127.0.0.1:54321", publishableKey: "x" });
    expect(readRoundConfig("http://localhost:54321/", "x")).toEqual({ url: "http://localhost:54321", publishableKey: "x" });
  });
});

describe("Rückkehr aus der Anmeldung", () => {
  it("ein Code in der Adresse", () => {
    expect(parseSignInReturn("?code=abc-123", "")).toEqual({ kind: "code", code: "abc-123" });
    expect(parseSignInReturn("?code=abc&state=xyz", "#/runde")).toEqual({ kind: "code", code: "abc" });
  });

  it("ein Fehler in der Adresse, auch hinter dem „#“", () => {
    expect(parseSignInReturn("?error=access_denied&error_description=Abgebrochen", "")).toEqual({ kind: "failure", error: "access_denied" });
    expect(parseSignInReturn("", "#error=server_error&error_description=Kaputt")).toEqual({ kind: "failure", error: "server_error" });
    expect(parseSignInReturn("?error_code=otp_expired", "")).toEqual({ kind: "failure", error: "otp_expired" });
    // Ein Fehler geht vor, auch wenn ein Code dabeisteht.
    expect(parseSignInReturn("?code=abc&error=access_denied", "")).toEqual({ kind: "failure", error: "access_denied" });
  });

  it("eine gewöhnliche Adresse der App ist keine Rückkehr", () => {
    expect(parseSignInReturn("", "")).toBeNull();
    expect(parseSignInReturn("", "#/runde")).toBeNull();
    expect(parseSignInReturn("", "#/einladung/0123456789abcdef0123456789abcdef")).toBeNull();
    expect(parseSignInReturn("?code=", "")).toBeNull();
    expect(parseSignInReturn("?anderes=1", "#/abend/error=1")).toBeNull();
  });
});

describe("gemerkte Einladung", () => {
  const TOKEN = "0123456789abcdef0123456789abcdef";

  it("merkt sich den Code im Speicher des Browsers und vergisst ihn wieder", () => {
    const storage = new MemoryStorage();
    const memory = storedInviteMemory(storage);
    expect(memory.get()).toBeNull();

    memory.set(TOKEN);
    expect(storage.values.get(INVITE_STORAGE_KEY)).toBe(TOKEN);
    // Auch nach der Rückkehr aus der Anmeldung (die Seite wurde neu geladen).
    expect(storedInviteMemory(storage).get()).toBe(TOKEN);

    memory.set(null);
    expect(storage.values.has(INVITE_STORAGE_KEY)).toBe(false);
    expect(memory.get()).toBeNull();
  });

  it("nur ein echter Code zählt", () => {
    const storage = new MemoryStorage();
    storage.setItem(INVITE_STORAGE_KEY, "<script>alert(1)</script>");
    expect(storedInviteMemory(storage).get()).toBeNull();
  });

  it("ist der Speicher gesperrt, gilt der Code, solange die Seite offen ist", () => {
    const storage = new MemoryStorage();
    storage.broken = true;
    const memory = storedInviteMemory(storage);
    memory.set(TOKEN);
    expect(memory.get()).toBe(TOKEN);
    memory.set(null);
    expect(memory.get()).toBeNull();
  });
});

describe("zuletzt erzeugte Einladung", () => {
  const TOKEN = "0123456789abcdef0123456789abcdef";
  const DAY = 24 * 3600 * 1000;
  const issued = { roundId: "runde-1", token: TOKEN, createdAt: 1_000_000 };

  it("wird für dieselbe Runde wiederverwendet, solange sie jünger als 13 Tage ist", () => {
    expect(INVITE_REUSE_MILLIS).toBe(13 * DAY);
    expect(reusableInvite(issued, "runde-1", issued.createdAt)).toBe(TOKEN);
    expect(reusableInvite(issued, "runde-1", issued.createdAt + 13 * DAY - 1)).toBe(TOKEN);
    expect(reusableInvite(issued, "runde-1", issued.createdAt + 13 * DAY)).toBeNull();
    expect(reusableInvite(issued, "runde-2", issued.createdAt)).toBeNull();
    expect(reusableInvite(null, "runde-1", issued.createdAt)).toBeNull();
    // Aus der Zukunft (die Uhr des Geräts wurde zurückgestellt): Wie alt sie ist, lässt sich nicht sagen.
    expect(reusableInvite(issued, "runde-1", issued.createdAt - 1)).toBeNull();
  });

  it("steht im Speicher des Browsers und wird beim Verlassen vergessen", () => {
    const storage = new MemoryStorage();
    const memory = storedIssuedInvites(storage);
    expect(memory.get()).toBeNull();

    memory.set(issued);
    expect(JSON.parse(storage.values.get(ISSUED_INVITE_KEY) ?? "null")).toEqual(issued);
    expect(storedIssuedInvites(storage).get()).toEqual(issued);

    memory.set(null);
    expect(storage.values.has(ISSUED_INVITE_KEY)).toBe(false);
    expect(memory.get()).toBeNull();
  });

  it("nur ein lesbarer Eintrag mit echtem Code zählt; ein gesperrter Speicher hält sie, solange die Seite offen ist", () => {
    const storage = new MemoryStorage();
    for (const broken of ["{kaputt", "[]", JSON.stringify({ ...issued, token: "<script>" }), JSON.stringify({ ...issued, createdAt: "gestern" })]) {
      storage.setItem(ISSUED_INVITE_KEY, broken);
      expect(storedIssuedInvites(storage).get(), broken).toBeNull();
    }

    const locked = new MemoryStorage();
    locked.broken = true;
    const memory = storedIssuedInvites(locked);
    memory.set(issued);
    expect(memory.get()).toEqual(issued);
  });
});

describe("Server, dessen Anbindung erst bei Bedarf geladen wird", () => {
  it("reicht jeden Aufruf weiter und lädt erst beim ersten", async () => {
    const server = new InMemoryRoundServer(() => 0);
    const session = server.sessionFor("konto-1");
    let loads = 0;
    const api = deferredApi(async () => {
      loads++;
      return session;
    });
    expect(Object.keys(api).sort()).toEqual([...ROUND_API_METHODS].sort());
    expect(loads).toBe(0);

    const round = await api.createRound("Stammtisch");
    expect((await api.head(round))?.name).toBe("Stammtisch");
    expect(await api.myRounds()).toHaveLength(1);
    expect(session.calls).toEqual(["createRound", "head", "myRounds"]);
    expect(loads).toBe(3);
  });

  it("lässt sich die Anbindung nicht laden, gilt das als „keine Verbindung“; Fehler des Servers bleiben, was sie sind", async () => {
    const failing = deferredApi(async () => {
      throw new Error("Datei nicht geladen");
    });
    expect(await errorOf(() => failing.myRounds())).toBe("OFFLINE");

    const signedOut = deferredApi(async () => {
      throw new RoundException("SIGNED_OUT");
    });
    expect(await errorOf(() => signedOut.myRounds())).toBe("SIGNED_OUT");

    const server = new InMemoryRoundServer(() => 0);
    const api = deferredApi(async () => server.sessionFor("konto-1"));
    expect(await errorOf(() => api.joinRound("0".repeat(32)))).toBe("INVALID_INVITE");
  });
});
