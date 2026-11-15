import { describe, expect, it } from "vitest";
import { compareInstants, instantFromEpochMillis, instantParts, instantToEpochMillis } from "./instant";

describe("Zeitpunkte wie Kotlins Instant", () => {
  it("zerlegt die Schreibweise von Instant.toString()", () => {
    expect(instantParts("1970-01-01T00:00:00Z")).toEqual({ epochSecond: 0n, nano: 0 });
    expect(instantParts("2026-11-14T19:00:00Z")).toEqual({ epochSecond: 1794682800n, nano: 0 });
    expect(instantParts("2026-11-14T19:00:00.5Z")).toEqual({ epochSecond: 1794682800n, nano: 500_000_000 });
    expect(instantParts("1969-12-31T23:59:59.999999999Z")).toEqual({ epochSecond: -1n, nano: 999_999_999 });
    expect(instantParts("2000-02-29T12:00:00Z").epochSecond).toBe(951825600n);
    // Der späteste Zeitpunkt der Android-App (Long.MAX_VALUE Millisekunden) passt nicht genau in eine JavaScript-Zahl.
    expect(instantParts("+292278994-08-17T07:12:55.807Z")).toEqual({ epochSecond: 9223372036854775n, nano: 807_000_000 });
    expect(() => instantParts("2026-11-14 19:00:00Z")).toThrow();
    expect(() => instantParts("2026-11-14T19:00:00+01:00")).toThrow();
  });

  it("vergleicht nach der Zeit, nicht nach dem Text", () => {
    expect("2026-11-14T19:00:00.123Z" < "2026-11-14T19:00:00Z").toBe(true);
    expect(compareInstants("2026-11-14T19:00:00.123Z", "2026-11-14T19:00:00Z")).toBeGreaterThan(0);
    expect(compareInstants("2026-11-14T19:00:00Z", "2026-11-14T19:00:00.000000001Z")).toBeLessThan(0);
    expect(compareInstants("2026-11-14T19:00:00.5Z", "2026-11-14T19:00:00.500Z")).toBe(0);
    expect(compareInstants("1969-12-31T23:59:59Z", "1970-01-01T00:00:00Z")).toBeLessThan(0);
  });

  it("schreibt Zeitpunkte wie Instant.toString() und liest sie zurück", () => {
    expect(instantFromEpochMillis(Date.UTC(2026, 10, 14, 19, 0, 0))).toBe("2026-11-14T19:00:00Z");
    expect(instantFromEpochMillis(Date.UTC(2026, 10, 14, 19, 0, 0, 120))).toBe("2026-11-14T19:00:00.120Z");
    expect(instantToEpochMillis("2026-11-14T19:00:00.120Z")).toBe(Date.UTC(2026, 10, 14, 19, 0, 0, 120));
    expect(() => instantFromEpochMillis(Number.NaN)).toThrow();
  });
});
