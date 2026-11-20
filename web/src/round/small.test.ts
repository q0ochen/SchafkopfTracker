/* Kleine Bausteine der gemeinsamen Runde: Konfiguration, Rückkehr aus der Anmeldung, gemerkte Einladung, Stellvertreter. */
import { describe, expect, it } from "vitest";
import { InMemoryRoundServer } from "../core/round/InMemoryRound";
import { RoundException } from "../core/round/RoundModels";
import { ROUND_API_METHODS } from "../core/round/RoundPorts";
import { errorOf } from "../core/round/testing/fixtures";
import { readRoundConfig } from "./config";
import { deferredApi } from "./deferredApi";
import { INVITE_STORAGE_KEY, storedInviteMemory } from "./inviteMemory";
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
