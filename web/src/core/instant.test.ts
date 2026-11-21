import { describe, expect, it } from "vitest";
import {
  civilFromDays,
  compareInstants,
  daysFromCivil,
  durationBetween,
  formatInstant,
  INSTANT_MAX_EPOCH_SECOND,
  INSTANT_MIN_EPOCH_SECOND,
  instantFromEpochMillis,
  instantParts,
  instantToEpochMillis,
  plusDuration,
  type InstantParts,
} from "./instant";

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

/** Zufallszahlen mit festem Startwert (xorshift32), damit jeder Lauf dieselben Fälle prüft. */
function seeded(seed: number): () => number {
  let state = seed >>> 0 || 1;
  return () => {
    state ^= state << 13;
    state >>>= 0;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state / 2 ** 32;
  };
}

describe("Kalender (Hinnant) für beliebige Jahre", () => {
  it("rechnet Tage seit 1970 in ein Datum um und zurück", () => {
    expect(civilFromDays(0n)).toEqual({ year: 1970n, month: 1, day: 1 });
    expect(civilFromDays(-1n)).toEqual({ year: 1969n, month: 12, day: 31 });
    expect(civilFromDays(11016n)).toEqual({ year: 2000n, month: 2, day: 29 });
    expect(civilFromDays(11017n)).toEqual({ year: 2000n, month: 3, day: 1 });
    expect(civilFromDays(-719528n)).toEqual({ year: 0n, month: 1, day: 1 });
    expect(civilFromDays(-719529n)).toEqual({ year: -1n, month: 12, day: 31 });
    // Instant.MIN und Instant.MAX.
    expect(civilFromDays(INSTANT_MIN_EPOCH_SECOND / 86400n)).toEqual({ year: -1_000_000_000n, month: 1, day: 1 });
    expect(civilFromDays(INSTANT_MAX_EPOCH_SECOND / 86400n)).toEqual({ year: 1_000_000_000n, month: 12, day: 31 });
    expect(daysFromCivil(2026n, 11, 21)).toBe(20778n);
    expect(daysFromCivil(2026n, 11, 14) * 86400n + 19n * 3600n).toBe(1794682800n);
    expect(daysFromCivil(-1_000_000_000n, 1, 1) * 86400n).toBe(INSTANT_MIN_EPOCH_SECOND);
  });

  it("findet für jeden Tag das Datum, aus dem daysFromCivil wieder diesen Tag macht", () => {
    // Lückenlos über einen ganzen 400-Jahres-Zyklus um das Jahr 0 und einige Jahre um 1970, dazu Zufallstage im
    // ganzen Bereich von Instant.
    const days: bigint[] = [];
    for (let day = -800_000n; day <= -650_000n; day += 1n) days.push(day);
    for (let day = -2_000n; day <= 2_000n; day += 1n) days.push(day);
    const random = seeded(20261121);
    const span = Number(INSTANT_MAX_EPOCH_SECOND / 86400n - INSTANT_MIN_EPOCH_SECOND / 86400n);
    for (let i = 0; i < 2_000; i++) days.push(INSTANT_MIN_EPOCH_SECOND / 86400n + BigInt(Math.floor(random() * span)));
    const failures: string[] = [];
    for (const day of days) {
      const civil = civilFromDays(day);
      const valid = civil.month >= 1 && civil.month <= 12 && civil.day >= 1 && civil.day <= 31;
      if (!valid || daysFromCivil(civil.year, civil.month, civil.day) !== day) failures.push(`${day}: ${JSON.stringify(civil, (_, v: unknown) => (typeof v === "bigint" ? String(v) : v))}`);
      if (failures.length >= 10) break;
    }
    expect(failures).toEqual([]);
  });
});

describe("Zeitpunkte schreiben wie Instant.toString()", () => {
  const parts = (epochSecond: bigint, nano = 0): InstantParts => ({ epochSecond, nano });

  it("schreibt das Jahr mit mindestens vier Stellen, „+“ über 9999 und „-“ vor 0", () => {
    expect(formatInstant(parts(1794682800n))).toBe("2026-11-14T19:00:00Z");
    expect(formatInstant(instantParts("+10000-01-02T23:00:00Z"))).toBe("+10000-01-02T23:00:00Z");
    expect(formatInstant(parts(253402300800n))).toBe("+10000-01-01T00:00:00Z");
    expect(formatInstant(parts(253402300799n))).toBe("9999-12-31T23:59:59Z");
    expect(formatInstant(parts(-62167219200n))).toBe("0000-01-01T00:00:00Z");
    expect(formatInstant(parts(-62167219201n))).toBe("-0001-12-31T23:59:59Z");
    expect(formatInstant(instantParts("-0001-12-30T12:00:00.250Z"))).toBe("-0001-12-30T12:00:00.250Z");
    expect(formatInstant(parts(-377736739200n))).toBe("-10000-01-01T00:00:00Z");
    expect(formatInstant(parts(INSTANT_MIN_EPOCH_SECOND))).toBe("-1000000000-01-01T00:00:00Z");
    expect(formatInstant(parts(INSTANT_MAX_EPOCH_SECOND, 999_999_999))).toBe("+1000000000-12-31T23:59:59.999999999Z");
    expect(formatInstant(parts(0n))).toBe("1970-01-01T00:00:00Z");
    expect(formatInstant(parts(60n))).toBe("1970-01-01T00:01:00Z");
  });

  it("schreibt einen Bruchteil nur, wenn er nicht null ist, in Dreiergruppen", () => {
    expect(formatInstant(parts(0n, 500_000_000))).toBe("1970-01-01T00:00:00.500Z");
    expect(formatInstant(parts(0n, 120_000_000))).toBe("1970-01-01T00:00:00.120Z");
    expect(formatInstant(parts(0n, 123_400_000))).toBe("1970-01-01T00:00:00.123400Z");
    expect(formatInstant(parts(0n, 1000))).toBe("1970-01-01T00:00:00.000001Z");
    expect(formatInstant(parts(0n, 10))).toBe("1970-01-01T00:00:00.000000010Z");
    expect(formatInstant(parts(-1n, 999_999_999))).toBe("1969-12-31T23:59:59.999999999Z");
    expect(() => formatInstant(parts(0n, 1_000_000_000))).toThrow();
    expect(() => formatInstant(parts(0n, -1))).toThrow();
    expect(() => formatInstant(parts(0n, 0.5))).toThrow();
  });

  it("liest zurück, was es schreibt, im ganzen Bereich von Instant", () => {
    const random = seeded(42);
    const span = Number(INSTANT_MAX_EPOCH_SECOND - INSTANT_MIN_EPOCH_SECOND);
    for (let i = 0; i < 2_000; i++) {
      const value = parts(INSTANT_MIN_EPOCH_SECOND + BigInt(Math.floor(random() * span)), Math.floor(random() * 1_000_000_000));
      const text = formatInstant(value);
      expect(instantParts(text), text).toEqual(value);
    }
  });
});

describe("Zeitspannen wie Javas Duration", () => {
  const at = (text: string): InstantParts => instantParts(text);

  it("misst die Spanne zwischen zwei Zeitpunkten mit Nanosekunden von 0 bis 999 999 999", () => {
    expect(durationBetween(at("2026-12-12T18:00:00Z"), at("2026-12-12T17:59:59.500Z"))).toEqual({ seconds: -1n, nano: 500_000_000 });
    expect(durationBetween(at("2026-12-12T18:00:00.123456789Z"), at("2026-12-11T19:00:00.123Z"))).toEqual({ seconds: -82801n, nano: 999_543_211 });
    expect(durationBetween(at("2026-12-12T18:00:00Z"), at("2026-12-12T18:01:00Z"))).toEqual({ seconds: 60n, nano: 0 });
    expect(durationBetween(at("2026-12-12T18:00:00Z"), at("2026-12-12T18:00:00Z"))).toEqual({ seconds: 0n, nano: 0 });
    expect(durationBetween(at("-1000000000-01-01T00:00:00Z"), at("+1000000000-12-31T23:59:59.999999999Z"))).toEqual({
      seconds: INSTANT_MAX_EPOCH_SECOND - INSTANT_MIN_EPOCH_SECOND,
      nano: 999_999_999,
    });
  });

  it("verschiebt einen Zeitpunkt wie Instant.plus(Duration)", () => {
    const plus = (text: string, from: string, to: string): string | null => {
      const moved = plusDuration(at(text), durationBetween(at(from), at(to)));
      return moved === null ? null : formatInstant(moved);
    };
    expect(plus("2026-12-12T23:45:10.500Z", "2026-12-12T18:00:00.123456789Z", "2026-12-11T19:00:00.123Z")).toBe("2026-12-12T00:45:10.499543211Z");
    expect(plus("2026-12-12T23:00:00.000001Z", "2026-12-12T18:00:00Z", "2026-12-13T18:00:00.000002Z")).toBe("2026-12-13T23:00:00.000003Z");
    expect(plus("9999-12-31T23:00:00Z", "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z")).toBe("+10000-01-02T23:00:00Z");
    // Am Ende des Bereichs: noch drin, knapp darüber.
    expect(plus("+1000000000-12-31T23:59:59.600Z", "2026-12-12T18:00:00Z", "2026-12-12T18:00:00.300Z")).toBe("+1000000000-12-31T23:59:59.900Z");
    expect(plus("+1000000000-12-31T23:59:59.600Z", "2026-12-12T18:00:00Z", "2026-12-12T18:00:00.500Z")).toBeNull();
    expect(plus("+1000000000-12-31T23:59:59.999999999Z", "2026-12-12T18:00:00Z", "2026-12-12T18:01:00Z")).toBeNull();
    // Am Anfang des Bereichs zieht Java zuerst die ganzen Sekunden ab: −0,5 s sind −1 s und +0,5 s.
    expect(plus("-1000000000-01-01T00:00:01.600Z", "2026-12-12T18:00:00Z", "2026-12-12T17:59:59.500Z")).toBe("-1000000000-01-01T00:00:01.100Z");
    expect(plus("-1000000000-01-01T00:00:00.600Z", "2026-12-12T18:00:00Z", "2026-12-12T17:59:59.500Z")).toBeNull();
    expect(plus("-1000000000-01-01T00:00:00Z", "2026-12-12T18:00:00Z", "2026-12-12T17:59:00Z")).toBeNull();
    // Ohne Spanne bleibt der Zeitpunkt.
    expect(plus("2026-12-12T23:45:10.500Z", "2026-12-12T18:00:00Z", "2026-12-12T18:00:00Z")).toBe("2026-12-12T23:45:10.500Z");
  });
});
