/*
 * Ortsvorschläge aus OpenStreetMap über Photon von komoot (docs/ARCHITEKTUR.md, Abschnitt 4.3): kostenlos, ohne
 * Schlüssel und ohne Konto. Gesendet wird nur der eingetippte Text, ohne eigene Kopfzeilen (keine Kennung von App,
 * Gerät oder Nutzer).
 *
 * `placeSuggestions` ist ein Port von Kotlins `PhotonPlaces.suggestions` (`data/place/PhotonPlaces.kt`); dass beide aus
 * demselben Antworttext dasselbe machen, prüft der Golden-Abschnitt `places`. Gelesen wird die Antwort deshalb wie in
 * der Android-App mit dem Nachbau von kotlinx.serialization (`parseToJsonElement`), nicht mit `JSON.parse`, und
 * getrimmt wird wie in Kotlin (`kotlinTrim`).
 */
import { EveningEdit } from "../../core/evening/EveningEdit";
import { kotlinTrim } from "../../core/kotlin";
import { JsonFormatError } from "../format/errors";
import { type JsonElement, type JsonObjectElement, parseToJsonElement, textOf } from "../format/jsonTree";
import { javaEqualsIgnoreCase } from "../format/kotlinText";
import { PHOTON_ORIGIN } from "./photonService";

/**
 * Ein Ortsvorschlag beim Feld „Ort“ (Kotlin: `PlaceSuggestion` in `domain.ports`).
 *
 * @property title Erste Zeile des Vorschlags, zum Beispiel der Name des Lokals.
 * @property details Zweite Zeile: Straße, Postleitzahl und Ort, im Ausland das Land; kann leer sein.
 * @property text Was beim Antippen ins Feld kommt: Titel und Zusatz in einer Zeile, gesäubert wie jeder Ort.
 */
export interface PlaceSuggestion {
  readonly title: string;
  readonly details: string;
  readonly text: string;
}

export { PHOTON_ORIGIN };

/** Höchstens so viele Vorschläge zeigt die App (Kotlin: `PhotonPlaces.MAX_SUGGESTIONS`). */
export const MAX_SUGGESTIONS = 5;

/** So viele Treffer fragt die App an; nach dem Entfernen doppelter bleiben höchstens fünf. */
export const REQUESTED_RESULTS = 8;

/** Höchstens so lange dauert eine Suche (Abschnitt 4.3: 10 Sekunden), Lesen der Antwort eingeschlossen. */
export const SEARCH_TIMEOUT_MILLIS = 10_000;

/** Die Antwort von Photon war nicht zu gebrauchen: kein JSON-Objekt oder `features` keine Liste. */
export class UnusablePlaceAnswerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnusablePlaceAnswerError";
  }
}

/** Die Suche ist gescheitert: ein anderer Status als 200 oder die Frist von zehn Sekunden ist um. */
export class PlaceSearchError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PlaceSearchError";
  }
}

/**
 * Die Vorschläge aus dem Text einer Antwort, in der Reihenfolge der Antwort, ohne doppelte (gleicher `text`) und
 * höchstens {@link MAX_SUGGESTIONS}. Ein Eintrag von `features`, der kein Objekt ist oder dessen `properties` kein Objekt
 * ist, entfällt; unbekannte Felder zählen nicht.
 *
 * @throws UnusablePlaceAnswerError wenn der Text kein JSON-Objekt ist oder `features` keine Liste.
 */
export function placeSuggestions(json: string): PlaceSuggestion[] {
  let root: JsonElement;
  try {
    root = parseToJsonElement(json);
  } catch (error) {
    if (error instanceof JsonFormatError) throw new UnusablePlaceAnswerError("Antwort ist kein JSON");
    throw error;
  }
  if (root.kind !== "object") throw new UnusablePlaceAnswerError("Antwort ist kein JSON-Objekt");
  const features = root.entries.get("features");
  if (features === undefined || features.kind !== "array") throw new UnusablePlaceAnswerError("features ist keine Liste");
  const seen = new Set<string>();
  const result: PlaceSuggestion[] = [];
  for (const feature of features.items) {
    if (result.length === MAX_SUGGESTIONS) break;
    if (feature.kind !== "object") continue;
    const properties = feature.entries.get("properties");
    if (properties === undefined || properties.kind !== "object") continue;
    const suggestion = suggestionOf(properties);
    if (suggestion === null || seen.has(suggestion.text)) continue;
    seen.add(suggestion.text);
    result.push(suggestion);
  }
  return result;
}

/** Das erste nicht leere Element; leer, wenn alle leer sind (Kotlin: `firstOrNull { it.isNotEmpty() }.orEmpty()`). */
function firstNotEmpty(values: readonly string[]): string {
  return values.find((value) => value !== "") ?? "";
}

/** Ein Vorschlag aus den `properties` eines Eintrags, oder `null`, wenn nichts Brauchbares darin steht. */
function suggestionOf(properties: JsonObjectElement): PlaceSuggestion | null {
  // Der getrimmte Text unter dem Schlüssel; leer, wenn er fehlt oder kein Text in Anführungszeichen ist.
  const value = (key: string): string => kotlinTrim(textOf(properties, key) ?? "");

  const streetName = value("street");
  const number = value("housenumber");
  const street = streetName === "" || number === "" ? streetName : `${streetName} ${number}`;
  const town = firstNotEmpty([value("city"), value("locality"), value("district")]);
  const townLine = [value("postcode"), town].filter((it) => it !== "").join(" ");
  const countryCode = value("countrycode");
  const country = countryCode !== "" && !javaEqualsIgnoreCase(countryCode, "DE") ? value("country") : "";

  const title = firstNotEmpty([value("name"), street, town, value("state"), value("country")]);
  if (title === "") return null;
  const details = [street, townLine, country].filter((it) => it !== "" && it !== title).join(", ");
  const text = EveningEdit.cleanLocation(details === "" ? title : `${title}, ${details}`);
  if (text === "") return null;
  return { title, details, text };
}

/** Optionen für {@link searchPlaces}; alles außer `signal` ist für Tests da. */
export interface PlaceSearchOptions {
  /** Bricht die Suche ab (zum Beispiel, weil der Nutzer weitergetippt hat). */
  readonly signal?: AbortSignal;
  /** Statt des `fetch` des Browsers. */
  readonly fetch?: typeof fetch;
  /** Statt {@link SEARCH_TIMEOUT_MILLIS}. */
  readonly timeoutMillis?: number;
}

/** Ersetzt einzelne Hälften von Ersatzzeichenpaaren durch U+FFFD (wie `toWellFormed`); sonst wirft `encodeURIComponent`. */
function wellFormed(text: string): string {
  let result = "";
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index);
    const next = text.charCodeAt(index + 1);
    if (code >= 0xd800 && code <= 0xdbff && next >= 0xdc00 && next <= 0xdfff) {
      result += text.substring(index, index + 2);
      index++;
    } else {
      result += code >= 0xd800 && code <= 0xdfff ? "\uFFFD" : text[index];
    }
  }
  return result;
}

/** Die Adresse einer Suche: `GET https://photon.komoot.io/api/?q=‹Text›&lang=de&limit=8` mit URL-kodiertem Text. */
export function placeSearchUrl(query: string): string {
  return `${PHOTON_ORIGIN}/api/?q=${encodeURIComponent(wellFormed(query))}&lang=de&limit=${REQUESTED_RESULTS}`;
}

/**
 * Sucht Orte per Name (`query` schon getrimmt) bei Photon. Die Anfrage trägt keine eigenen Kopfzeilen, keine
 * Zugangsdaten und keinen Verweis auf die App (`referrerPolicy: "no-referrer"`). Nach {@link SEARCH_TIMEOUT_MILLIS}
 * (Lesen der Antwort eingeschlossen) ist Schluss.
 *
 * @throws PlaceSearchError bei einem anderen Status als 200 oder nach Ablauf der Frist.
 * @throws UnusablePlaceAnswerError bei einer unbrauchbaren Antwort.
 * @throws Error ohne Netz (vom Browser) und bei einem Abbruch über `signal` (dessen Grund, sonst ein `AbortError`).
 */
export async function searchPlaces(query: string, options: PlaceSearchOptions = {}): Promise<PlaceSuggestion[]> {
  const { signal } = options;
  const fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new PlaceSearchError("Photon hat nicht innerhalb der Frist geantwortet")),
    options.timeoutMillis ?? SEARCH_TIMEOUT_MILLIS,
  );
  const forwardAbort = () => controller.abort(signal?.reason);
  if (signal?.aborted) forwardAbort();
  else signal?.addEventListener("abort", forwardAbort, { once: true });
  try {
    if (controller.signal.aborted) throw controller.signal.reason;
    const response = await fetchImpl(placeSearchUrl(query), {
      method: "GET",
      signal: controller.signal,
      credentials: "omit",
      referrerPolicy: "no-referrer",
    });
    if (response.status !== 200) throw new PlaceSearchError(`Photon antwortet mit ${response.status}`);
    const text = await response.text();
    // Eine Antwort, die erst nach dem Abbruch fertig gelesen war, zählt nicht mehr.
    if (controller.signal.aborted) throw controller.signal.reason;
    return placeSuggestions(text);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", forwardAbort);
  }
}
