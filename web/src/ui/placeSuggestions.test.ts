/* Steuerung der Ortsvorschläge (docs/ARCHITEKTUR.md, 4.3), mit nachgestellter Uhr und nachgestellter Suche. */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PlaceSuggestion } from "../data/place/photon";
import { MIN_QUERY_LENGTH, NO_SUGGESTIONS, PlaceSuggestions, revealScroll, SEARCH_DELAY_MILLIS, type SuggestionTimers } from "./placeSuggestions";

/** Eine Suche, die erst antwortet, wenn der Test es sagt. */
interface PendingSearch {
  readonly query: string;
  readonly signal: AbortSignal;
  resolve(suggestions: readonly PlaceSuggestion[]): void;
  reject(error: unknown): void;
}

function fakeSearch() {
  const calls: PendingSearch[] = [];
  const search = (query: string, signal: AbortSignal) =>
    new Promise<readonly PlaceSuggestion[]>((resolve, reject) => {
      calls.push({ query, signal, resolve, reject });
    });
  return { calls, search };
}

function place(title: string, details = ""): PlaceSuggestion {
  return { title, details, text: details === "" ? title : `${title}, ${details}` };
}

const KLINIKUM = place("Klinikum Bamberg", "Buger Straße 80, 96049 Bamberg");
const POST = place("Gasthaus Post", "Bamberg");

/** Lässt die Zeit vergehen und dabei auch alle fälligen Antworten ankommen. */
async function wait(millis: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(millis);
}

describe("Ortsvorschläge: wann gesucht wird", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("sucht nie von selbst, nur nach einer Eingabe", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    await wait(10_000);
    expect(calls).toEqual([]);
    expect(places.getState()).toBe(NO_SUGGESTIONS);
  });

  it("sucht 400 ms nach der letzten Eingabe nach dem getrimmten Text", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("  Kli");
    await wait(SEARCH_DELAY_MILLIS - 1);
    expect(calls).toEqual([]);
    await wait(1);
    expect(calls.map((it) => it.query)).toEqual(["Kli"]);
  });

  it("wartet nach jeder Eingabe von Neuem und sucht nur nach der letzten", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Kli");
    await wait(300);
    places.typed("Klin");
    await wait(300);
    places.typed("Klinikum ");
    await wait(SEARCH_DELAY_MILLIS - 1);
    expect(calls).toEqual([]);
    await wait(1);
    expect(calls.map((it) => it.query)).toEqual(["Klinikum"]);
  });

  it("unter drei Zeichen (getrimmt wie in Kotlin) wird nicht gesucht", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    expect(MIN_QUERY_LENGTH).toBe(3);
    for (const text of ["", "K", "Kl", "  Kl  ", "\u001cKl", "Kl "]) {
      places.typed(text);
      await wait(1_000);
    }
    expect(calls).toEqual([]);
    // U+FEFF ist in Kotlin kein Leerraum und zählt deshalb mit.
    places.typed("﻿Kl");
    await wait(SEARCH_DELAY_MILLIS);
    expect(calls.map((it) => it.query)).toEqual(["﻿Kl"]);
  });
});

describe("Ortsvorschläge: Antworten", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("zeigt die Vorschläge der Antwort; eine leere Antwort zeigt keine Liste und keinen Hinweis", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    const changes = vi.fn();
    places.subscribe(changes);
    places.typed("Klinikum Bamberg");
    await wait(SEARCH_DELAY_MILLIS);
    calls[0].resolve([KLINIKUM]);
    await wait(0);
    expect(places.getState()).toEqual({ suggestions: [KLINIKUM], offline: false });
    expect(changes).toHaveBeenCalledTimes(1);

    places.typed("Klinikum Bambergx");
    await wait(SEARCH_DELAY_MILLIS);
    calls[1].resolve([]);
    await wait(0);
    expect(places.getState()).toEqual({ suggestions: [], offline: false });
  });

  it("eine neue Eingabe bricht die laufende Suche ab; nur die Antwort auf die letzte Eingabe zählt", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Post");
    await wait(SEARCH_DELAY_MILLIS);
    expect(calls).toHaveLength(1);
    places.typed("Klinikum");
    expect(calls[0].signal.aborted).toBe(true);
    await wait(SEARCH_DELAY_MILLIS);
    expect(calls.map((it) => it.query)).toEqual(["Post", "Klinikum"]);
    // Die alte Suche übergeht den Abbruch und antwortet nach der neuen: Ihre Antwort zählt trotzdem nicht.
    calls[1].resolve([KLINIKUM]);
    await wait(0);
    calls[0].resolve([POST]);
    await wait(0);
    expect(places.getState().suggestions).toEqual([KLINIKUM]);
    // Ebenso ein später Fehler der alten Suche.
    calls[0].reject(new Error("abgebrochen"));
    await wait(0);
    expect(places.getState()).toEqual({ suggestions: [KLINIKUM], offline: false });
  });

  it("eine Eingabe vor dem Ende der Wartezeit verwirft die geplante Suche", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Post");
    await wait(200);
    places.typed("Po");
    await wait(10_000);
    expect(calls).toEqual([]);
  });

  it("bis die neue Antwort da ist, bleiben die bisherigen Vorschläge stehen", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Gasthaus");
    await wait(SEARCH_DELAY_MILLIS);
    calls[0].resolve([POST]);
    await wait(0);
    places.typed("Gasthaus P");
    await wait(SEARCH_DELAY_MILLIS);
    expect(places.getState().suggestions).toEqual([POST]);
    calls[1].resolve([POST, KLINIKUM]);
    await wait(0);
    expect(places.getState().suggestions).toEqual([POST, KLINIKUM]);
  });

  it("unter drei Zeichen verschwinden Vorschläge sofort, und die laufende Suche wird abgebrochen", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Post");
    await wait(SEARCH_DELAY_MILLIS);
    calls[0].resolve([POST]);
    await wait(0);
    places.typed("Postx");
    await wait(SEARCH_DELAY_MILLIS);
    places.typed("Po");
    expect(places.getState()).toBe(NO_SUGGESTIONS);
    expect(calls[1].signal.aborted).toBe(true);
    calls[1].resolve([KLINIKUM]);
    await wait(0);
    expect(places.getState()).toBe(NO_SUGGESTIONS);
  });
});

describe("Ortsvorschläge: ohne Internet", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("nach einem Fehler: keine Liste, sondern der Hinweis bis zur nächsten Eingabe", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Post");
    await wait(SEARCH_DELAY_MILLIS);
    calls[0].resolve([POST]);
    await wait(0);
    places.typed("Posthorn");
    await wait(SEARCH_DELAY_MILLIS);
    calls[1].reject(new TypeError("Failed to fetch"));
    await wait(0);
    expect(places.getState()).toEqual({ suggestions: [], offline: true });

    // Die nächste Eingabe nimmt den Hinweis sofort weg; die Antwort entscheidet neu.
    places.typed("Posthorn B");
    expect(places.getState()).toEqual({ suggestions: [], offline: false });
    await wait(SEARCH_DELAY_MILLIS);
    calls[2].reject(new Error("Zeitüberschreitung"));
    await wait(0);
    expect(places.getState().offline).toBe(true);

    // Unter drei Zeichen verschwindet der Hinweis ebenfalls.
    places.typed("Po");
    expect(places.getState()).toBe(NO_SUGGESTIONS);
  });

  it("auch eine Suche, die sofort wirft, gilt als gescheitert", async () => {
    const places = new PlaceSuggestions(() => {
      throw new Error("kaputt");
    });
    places.typed("Post");
    await wait(SEARCH_DELAY_MILLIS);
    expect(places.getState()).toEqual({ suggestions: [], offline: true });
  });
});

describe("Ortsvorschläge: Antippen und Schließen", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("nach dem Antippen ist die Liste zu, und gesucht wird erst nach der nächsten Eingabe", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Klinikum");
    await wait(SEARCH_DELAY_MILLIS);
    calls[0].resolve([KLINIKUM]);
    await wait(0);
    // Noch eine Suche läuft, als der Nutzer einen Vorschlag antippt.
    places.typed("Klinikum B");
    await wait(SEARCH_DELAY_MILLIS);
    places.close();
    expect(places.getState()).toBe(NO_SUGGESTIONS);
    expect(calls[1].signal.aborted).toBe(true);
    calls[1].resolve([KLINIKUM, POST]);
    await wait(10_000);
    expect(places.getState()).toBe(NO_SUGGESTIONS);
    expect(calls).toHaveLength(2);

    places.typed("Klinikum Bamberg, Buger Straße 80, 96049 Bamberg x");
    await wait(SEARCH_DELAY_MILLIS);
    expect(calls).toHaveLength(3);
  });

  it("Schließen verwirft auch eine geplante Suche", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    places.typed("Klinikum");
    await wait(100);
    places.close();
    await wait(10_000);
    expect(calls).toEqual([]);
  });

  it("nach dem Ende des Bildschirms: keine Suche und keine Meldung mehr", async () => {
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    const changes = vi.fn();
    places.subscribe(changes);
    places.typed("Klinikum");
    await wait(SEARCH_DELAY_MILLIS);
    places.dispose();
    expect(calls[0].signal.aborted).toBe(true);
    calls[0].resolve([KLINIKUM]);
    places.typed("Post");
    await wait(10_000);
    expect(calls).toHaveLength(1);
    expect(changes).not.toHaveBeenCalled();
    expect(places.getState()).toBe(NO_SUGGESTIONS);
  });

  it("meldet nur echte Änderungen", async () => {
    const { search } = fakeSearch();
    const places = new PlaceSuggestions(search);
    const changes = vi.fn();
    const unsubscribe = places.subscribe(changes);
    places.typed("K");
    places.typed("");
    places.close();
    places.typed("Klinikum");
    expect(changes).not.toHaveBeenCalled();
    unsubscribe();
  });

  it("nimmt eine eigene Uhr", () => {
    const scheduled: Array<{ callback: () => void; millis: number }> = [];
    const cleared: unknown[] = [];
    const timers: SuggestionTimers = {
      setTimeout: (callback, millis) => scheduled.push({ callback, millis }),
      clearTimeout: (handle) => cleared.push(handle),
    };
    const { calls, search } = fakeSearch();
    const places = new PlaceSuggestions(search, timers, 250);
    places.typed("Post");
    places.typed("Posthorn");
    expect(scheduled.map((it) => it.millis)).toEqual([250, 250]);
    expect(cleared).toEqual([1]);
    scheduled[1].callback();
    expect(calls.map((it) => it.query)).toEqual(["Posthorn"]);
  });
});

describe("Ortsvorschläge: ins Bild rollen", () => {
  const visible = { top: 0, bottom: 700 };

  it("rollt nicht, wenn die Liste schon zu sehen ist", () => {
    expect(revealScroll({ top: 300 }, { bottom: 650 }, visible)).toBe(0);
    expect(revealScroll({ top: 300 }, { bottom: 700 }, visible)).toBe(0);
  });

  it("rollt so weit, dass die ganze Liste zu sehen ist", () => {
    expect(revealScroll({ top: 400 }, { bottom: 900 }, visible)).toBe(200);
  });

  it("rollt das Feld nie oben hinaus, auch wenn die Liste dann nicht ganz zu sehen ist", () => {
    // Mit Tastatur ist nur wenig zu sehen: Das Feld bleibt mit 8 px Abstand oben stehen.
    expect(revealScroll({ top: 250 }, { bottom: 900 }, { top: 0, bottom: 400 })).toBe(242);
    expect(revealScroll({ top: 250 }, { bottom: 900 }, { top: 0, bottom: 400 }, 0)).toBe(250);
    // Der sichtbare Bereich kann gegen die Seite verschoben sein (visualViewport.offsetTop).
    expect(revealScroll({ top: 350 }, { bottom: 900 }, { top: 100, bottom: 500 })).toBe(242);
    // Steht das Feld schon ganz oben (oder darüber), wird nicht gerollt.
    expect(revealScroll({ top: 5 }, { bottom: 900 }, visible)).toBe(0);
  });
});
