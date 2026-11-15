import { describe, expect, it } from "vitest";
import { amountTone, formatAmount, formatDate, formatPercent, MINUS, plural } from "./format";

const NBSP = " ";

describe("formatAmount (wie Money.kt der Android-App)", () => {
  it("schreibt Euro mit Komma, zwei Nachkommastellen und geschütztem Leerzeichen", () => {
    expect(formatAmount(0, true)).toBe(`0,00${NBSP}€`);
    expect(formatAmount(5, true)).toBe(`0,05${NBSP}€`);
    expect(formatAmount(120, true)).toBe(`1,20${NBSP}€`);
    expect(formatAmount(123456, true)).toBe(`1234,56${NBSP}€`);
  });

  it("setzt das echte Minuszeichen und auf Wunsch ein Plus", () => {
    expect(formatAmount(-120, true)).toBe(`${MINUS}1,20${NBSP}€`);
    expect(formatAmount(120, true, true)).toBe(`+1,20${NBSP}€`);
    expect(formatAmount(0, true, true)).toBe(`0,00${NBSP}€`);
    expect(formatAmount(-5, true, true)).toBe(`${MINUS}0,05${NBSP}€`);
  });

  it("schreibt Punkte als ganze Zahl", () => {
    expect(formatAmount(120, false)).toBe("120");
    expect(formatAmount(-120, false)).toBe(`${MINUS}120`);
    expect(formatAmount(120, false, true)).toBe("+120");
  });

  it("kommt mit den Grenzen des Zahlenbereichs zurecht", () => {
    expect(formatAmount(-2147483648, true)).toBe(`${MINUS}21474836,48${NBSP}€`);
    expect(formatAmount(2147483647, false, true)).toBe("+2147483647");
  });
});

describe("weitere Formate", () => {
  it("ordnet Gewinn, Verlust und Null zu", () => {
    expect(amountTone(1)).toBe("gain");
    expect(amountTone(-1)).toBe("loss");
    expect(amountTone(0)).toBe("neutral");
  });

  it("wählt Einzahl oder Mehrzahl", () => {
    expect(plural(1, "Spiel", "Spiele")).toBe(`1${NBSP}Spiel`);
    expect(plural(0, "Spiel", "Spiele")).toBe(`0${NBSP}Spiele`);
    expect(plural(12, "Spiel", "Spiele")).toBe(`12${NBSP}Spiele`);
  });

  it("schreibt Anteile als ganze Prozent", () => {
    expect(formatPercent(null)).toBe("–");
    expect(formatPercent(2 / 3)).toBe(`67${NBSP}%`);
    expect(formatPercent(1)).toBe(`100${NBSP}%`);
  });

  it("schreibt das Datum eines Abends auf Deutsch", () => {
    // Mittags in UTC, damit das Datum in jeder Zeitzone der Testrechner gleich bleibt.
    expect(formatDate("2026-11-14T12:00:00Z")).toBe("Sa., 14. Nov. 2026");
  });
});
