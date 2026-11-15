import { describe, expect, it } from "vitest";
import { niceTicks, spreadLabels } from "./BalanceChart";

describe("Teilstriche der y-Achse", () => {
  it("wählt runde Schritte, die den ganzen Bereich abdecken", () => {
    expect(niceTicks(-160, 60)).toEqual([-200, -100, 0, 100]);
    expect(niceTicks(0, 1000)).toEqual([0, 250, 500, 750, 1000]);
    const ticks = niceTicks(-1234, 5678);
    expect(ticks[0]).toBeLessThanOrEqual(-1234);
    expect(ticks[ticks.length - 1]).toBeGreaterThanOrEqual(5678);
    expect(ticks).toContain(0);
  });

  it("kommt mit lauter gleichen Werten zurecht", () => {
    const ticks = niceTicks(0, 0);
    expect(ticks.length).toBeGreaterThanOrEqual(2);
    expect(ticks[0]).toBeLessThan(0);
    expect(ticks[ticks.length - 1]).toBeGreaterThan(0);
  });
});

describe("Beschriftungen am Linienende", () => {
  it("schiebt zu nahe Beschriftungen auseinander und bleibt im Rahmen", () => {
    const placed = spreadLabels([100, 102, 104, 200], 16, 10, 250);
    const sorted = [...placed].sort((a, b) => a - b);
    for (let i = 1; i < sorted.length; i++) expect(sorted[i] - sorted[i - 1]).toBeGreaterThanOrEqual(16 - 1e-9);
    expect(placed[3]).toBe(200);
    expect(Math.min(...placed)).toBeGreaterThanOrEqual(10);
    expect(Math.max(...placed)).toBeLessThanOrEqual(250);
  });

  it("verschiebt unten anstoßende Beschriftungen nach oben", () => {
    const placed = spreadLabels([248, 249, 250], 16, 10, 250);
    expect(Math.max(...placed)).toBeLessThanOrEqual(250);
    expect([...placed].sort((a, b) => a - b)).toEqual([218, 234, 250]);
  });
});
