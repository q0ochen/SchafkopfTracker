/*
 * Ortsvorschläge aus Photon (docs/ARCHITEKTUR.md, 4.3): dieselben Fälle wie in Kotlin (`PhotonPlacesTest`,
 * `PhotonPlaceSearchTest`); den genauen Gleichlauf mit Kotlin prüft der Golden-Abschnitt `places`.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MAX_SUGGESTIONS,
  PHOTON_ORIGIN,
  PlaceSearchError,
  placeSearchUrl,
  placeSuggestions,
  SEARCH_TIMEOUT_MILLIS,
  searchPlaces,
  UnusablePlaceAnswerError,
} from "./photon";

function answer(...properties: string[]): string {
  return `{"type":"FeatureCollection","features":[${properties.map((it) => `{"type":"Feature","properties":${it}}`).join(",")}]}`;
}

describe("Vorschläge aus einer Antwort", () => {
  it("Lokal mit Adresse ergibt Name, Straße mit Hausnummer und Postleitzahl mit Ort", () => {
    const json = answer(
      `{"osm_key":"amenity","osm_value":"hospital","name":"Klinikum Bamberg","street":"Buger Straße",
        "housenumber":"80","postcode":"96049","city":"Bamberg","district":"Bug","state":"Bayern",
        "country":"Deutschland","countrycode":"DE","type":"house"}`,
    );
    expect(placeSuggestions(json)).toEqual([
      { title: "Klinikum Bamberg", details: "Buger Straße 80, 96049 Bamberg", text: "Klinikum Bamberg, Buger Straße 80, 96049 Bamberg" },
    ]);
  });

  it("ohne Namen wird die Straße zum Titel und steht nicht doppelt da", () => {
    const json = answer(`{"street":"Buger Straße","housenumber":"80","postcode":"96049","city":"Bamberg","countrycode":"DE"}`);
    expect(placeSuggestions(json)).toEqual([{ title: "Buger Straße 80", details: "96049 Bamberg", text: "Buger Straße 80, 96049 Bamberg" }]);
  });

  it("eine Hausnummer ohne Straße zählt nicht, ein Ort ohne Stadt nimmt locality oder district", () => {
    const json = answer(`{"name":"Keller","housenumber":"3","locality":"Wunderburg","postcode":"96050"}`, `{"name":"Bierkeller","district":"Gaustadt"}`);
    expect(placeSuggestions(json)).toEqual([
      { title: "Keller", details: "96050 Wunderburg", text: "Keller, 96050 Wunderburg" },
      { title: "Bierkeller", details: "Gaustadt", text: "Bierkeller, Gaustadt" },
    ]);
  });

  it("im Ausland steht das Land dahinter, in Deutschland nicht", () => {
    const json = answer(
      `{"name":"Gasthof Post","street":"Hauptplatz","housenumber":"1","postcode":"5020","city":"Salzburg","country":"Österreich","countrycode":"AT"}`,
      `{"name":"Post","city":"Bamberg","country":"Deutschland","countrycode":"de"}`,
      `{"name":"Ohne Code","city":"Irgendwo","country":"Nirgendland"}`,
    );
    expect(placeSuggestions(json)).toEqual([
      { title: "Gasthof Post", details: "Hauptplatz 1, 5020 Salzburg, Österreich", text: "Gasthof Post, Hauptplatz 1, 5020 Salzburg, Österreich" },
      { title: "Post", details: "Bamberg", text: "Post, Bamberg" },
      { title: "Ohne Code", details: "Irgendwo", text: "Ohne Code, Irgendwo" },
    ]);
  });

  it("ein Ort als Treffer wird zum Titel, Bundesland und Land sind die letzten Titel", () => {
    const json = answer(
      `{"name":"Bamberg","postcode":"96047","state":"Bayern","countrycode":"DE","type":"city"}`,
      `{"city":"Bamberg","state":"Bayern"}`,
      `{"state":"Bayern","country":"Deutschland","countrycode":"DE"}`,
      `{"country":"Österreich","countrycode":"AT"}`,
    );
    expect(placeSuggestions(json)).toEqual([
      { title: "Bamberg", details: "96047", text: "Bamberg, 96047" },
      // Die Ortszeile wäre nur „Bamberg“ wie der Titel: Sie entfällt.
      { title: "Bamberg", details: "", text: "Bamberg" },
      { title: "Bayern", details: "", text: "Bayern" },
      // Das Land ist hier Titel und Zusatz zugleich: nur einmal.
      { title: "Österreich", details: "", text: "Österreich" },
    ]);
  });

  it("Leerraum wird wie in Kotlin getrimmt, Werte ohne Text zählen als leer", () => {
    const json = answer(
      `{"name":"  Schlenkerla \\t","street":" Dominikanerstraße ","housenumber":6,"postcode":96049,"city":" Bamberg ","countrycode":null}`,
      `{"name":"   ","street":["x"],"city":{"a":1},"state":true}`,
      // U+FEFF ist in Kotlin kein Leerraum, U+001C schon (in JavaScript umgekehrt).
      `{"name":"\\ufeffSpezial","city":"\\u001cBamberg"}`,
    );
    expect(placeSuggestions(json)).toEqual([
      { title: "Schlenkerla", details: "Dominikanerstraße, Bamberg", text: "Schlenkerla, Dominikanerstraße, Bamberg" },
      { title: "﻿Spezial", details: "Bamberg", text: "﻿Spezial, Bamberg" },
    ]);
  });

  it("doppelte Vorschläge erscheinen einmal, höchstens fünf, Einträge ohne Objekt entfallen", () => {
    const same = `{"name":"Wirtshaus","city":"Bamberg"}`;
    const kellers = [1, 2, 3, 4, 5, 6].map((it) => `{"properties":{"name":"Keller ${it}"}}`).join(",");
    const json = `{"features":[1,null,"x",{"properties":"y"},{"type":"Feature"},{"properties":${same}},{"properties":${same}},${kellers}]}`;
    const result = placeSuggestions(json);
    expect(result).toHaveLength(MAX_SUGGESTIONS);
    expect(result.map((it) => it.text)).toEqual(["Wirtshaus, Bamberg", "Keller 1", "Keller 2", "Keller 3", "Keller 4"]);
  });

  it("ein zu langer Vorschlag wird wie jeder Ort auf 100 Zeichen gekürzt", () => {
    const name = `Gasthaus ${"z".repeat(95)}`;
    const [suggestion] = placeSuggestions(answer(`{"name":"${name}","city":"Bamberg"}`));
    expect(suggestion).toEqual({ title: name, details: "Bamberg", text: name.slice(0, 100) });
  });

  it("leere Antworten ergeben keine Vorschläge", () => {
    expect(placeSuggestions(`{"features":[]}`)).toEqual([]);
    expect(placeSuggestions(answer("{}"))).toEqual([]);
  });

  it("unbrauchbare Antworten sind ein Fehler", () => {
    for (const json of ["", "kein json", "[]", "null", "5", '"x"', "{}", `{"features":{}}`, `{"features":null}`, "<html>Wartung</html>"]) {
      expect(() => placeSuggestions(json), json).toThrow(UnusablePlaceAnswerError);
    }
  });
});

/** Eine Antwort für das nachgestellte `fetch`. */
function response(status: number, body: string): Response {
  return new Response(body, { status, headers: { "content-type": "application/json;charset=utf-8" } });
}

/** Ein `fetch`, das mitschreibt und nie antwortet, bis es abgebrochen wird. */
function hangingFetch(calls: Array<{ url: string; init: RequestInit | undefined }>): typeof fetch {
  return (input, init) => {
    calls.push({ url: String(input), init });
    return new Promise<Response>((_resolve, reject) => {
      const signal = init?.signal;
      signal?.addEventListener("abort", () => reject(signal.reason));
    });
  };
}

describe("Suche bei Photon", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("fragt genau die vereinbarte Adresse ab, ohne eigene Kopfzeilen und ohne Kennung der App", async () => {
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    const fake: typeof fetch = async (input, init) => {
      calls.push({ url: String(input), init });
      return response(200, `{"features":[{"properties":{"name":"Klinikum Bamberg","city":"Bamberg"}}]}`);
    };
    const result = await searchPlaces("Klinikum Bamberg & Co", { fetch: fake });
    expect(result.map((it) => it.text)).toEqual(["Klinikum Bamberg, Bamberg"]);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe("https://photon.komoot.io/api/?q=Klinikum%20Bamberg%20%26%20Co&lang=de&limit=8");
    const init = calls[0].init ?? {};
    expect(init.method).toBe("GET");
    expect(init.headers).toBeUndefined();
    expect(init.body).toBeUndefined();
    expect(init.credentials).toBe("omit");
    expect(init.referrerPolicy).toBe("no-referrer");
  });

  it("der öffentliche Dienst ist photon.komoot.io mit zehn Sekunden Frist", () => {
    expect(PHOTON_ORIGIN).toBe("https://photon.komoot.io");
    expect(SEARCH_TIMEOUT_MILLIS).toBe(10_000);
  });

  it("kodiert den Text für die Adresse, auch Umlaute und einzelne Ersatzzeichen", () => {
    expect(placeSearchUrl("Zum Löwen/Bräu?")).toBe("https://photon.komoot.io/api/?q=Zum%20L%C3%B6wen%2FBr%C3%A4u%3F&lang=de&limit=8");
    expect(placeSearchUrl("Bier 🍺")).toBe("https://photon.komoot.io/api/?q=Bier%20%F0%9F%8D%BA&lang=de&limit=8");
    expect(placeSearchUrl("Bier \ud83c")).toBe("https://photon.komoot.io/api/?q=Bier%20%EF%BF%BD&lang=de&limit=8");
    expect(placeSearchUrl("\udf7aBier")).toBe("https://photon.komoot.io/api/?q=%EF%BF%BDBier&lang=de&limit=8");
  });

  it("ein anderer Status als 200 ist ein Fehler", async () => {
    const fake: typeof fetch = async () => response(503, `{"features":[]}`);
    await expect(searchPlaces("Post", { fetch: fake })).rejects.toBeInstanceOf(PlaceSearchError);
  });

  it("eine unbrauchbare Antwort ist ein Fehler", async () => {
    const fake: typeof fetch = async () => new Response("<html>Wartung</html>", { status: 200 });
    await expect(searchPlaces("Post", { fetch: fake })).rejects.toBeInstanceOf(UnusablePlaceAnswerError);
  });

  it("ohne Netz ist es ein Fehler", async () => {
    const fake: typeof fetch = async () => {
      throw new TypeError("Failed to fetch");
    };
    await expect(searchPlaces("Post", { fetch: fake })).rejects.toBeInstanceOf(TypeError);
  });

  it("nach zehn Sekunden ist Schluss", async () => {
    vi.useFakeTimers();
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    const search = searchPlaces("Post", { fetch: hangingFetch(calls) });
    const outcome = expect(search).rejects.toBeInstanceOf(PlaceSearchError);
    await vi.advanceTimersByTimeAsync(SEARCH_TIMEOUT_MILLIS - 1);
    expect(calls[0].init?.signal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await outcome;
    expect(calls[0].init?.signal?.aborted).toBe(true);
  });

  it("die Frist gilt auch für das Lesen der Antwort", async () => {
    vi.useFakeTimers();
    const fake: typeof fetch = async (_input, init) =>
      ({
        status: 200,
        text: () =>
          new Promise<string>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => reject(init.signal?.reason));
          }),
      }) as Response;
    const outcome = expect(searchPlaces("Post", { fetch: fake })).rejects.toBeInstanceOf(PlaceSearchError);
    await vi.advanceTimersByTimeAsync(SEARCH_TIMEOUT_MILLIS);
    await outcome;
  });

  it("ein Abbruch von außen beendet die Anfrage sofort", async () => {
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    const abort = new AbortController();
    const search = searchPlaces("Post", { fetch: hangingFetch(calls), signal: abort.signal });
    abort.abort();
    await expect(search).rejects.toMatchObject({ name: "AbortError" });
    expect(calls[0].init?.signal?.aborted).toBe(true);
  });

  it("schon abgebrochen fragt sie gar nicht erst", async () => {
    const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
    const abort = new AbortController();
    abort.abort(new Error("weitergetippt"));
    await expect(searchPlaces("Post", { fetch: hangingFetch(calls), signal: abort.signal })).rejects.toThrow("weitergetippt");
    expect(calls).toEqual([]);
  });

  it("eine Antwort, die erst nach dem Abbruch gelesen war, zählt nicht", async () => {
    const abort = new AbortController();
    const fake: typeof fetch = async () =>
      ({
        status: 200,
        text: async () => {
          abort.abort(new Error("weitergetippt"));
          return `{"features":[{"properties":{"name":"Post"}}]}`;
        },
      }) as Response;
    await expect(searchPlaces("Post", { fetch: fake, signal: abort.signal })).rejects.toThrow("weitergetippt");
  });
});
