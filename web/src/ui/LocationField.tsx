/*
 * Das Feld „Ort (freiwillig)“ mit Ortsvorschlägen aus OpenStreetMap (docs/ARCHITEKTUR.md, Abschnitt 4.3), für „Neuer
 * Abend“ und „Abend bearbeiten“. Wann gesucht wird und was gilt, steuert `PlaceSuggestions` (placeSuggestions.ts); hier
 * steht nur, was davon zu sehen ist: die Liste zum Antippen mit dem Hinweis auf OpenStreetMap oder nach einer
 * gescheiterten Suche „Ortsvorschläge gibt es nur mit Internet.“. Der Ort lässt sich immer frei eintippen.
 */
import { useEffect, useRef, useState } from "preact/hooks";
import { MAX_LOCATION_LENGTH } from "../core/evening/EveningEdit";
import { type PlaceSuggestion, searchPlaces } from "../data/place/photon";
import { Icon } from "./components";
import { type PlaceSearchFunction, PlaceSuggestions, revealScroll } from "./placeSuggestions";

/** Die Suche bei Photon (photon.komoot.io). */
const photonSearch: PlaceSearchFunction = (query, signal) => searchPlaces(query, { signal });

/** Was ein Vorschlag vorliest: Titel und darunter die Adresse. */
function spokenName(suggestion: PlaceSuggestion): string {
  return suggestion.details === "" ? suggestion.title : `${suggestion.title}, ${suggestion.details}`;
}

/**
 * @param value Der Ort, wie er im Feld steht.
 * @param onChange Bekommt jede Eingabe und den Text eines angetippten Vorschlags; der Bildschirm kürzt ihn wie jeden Ort.
 */
export function LocationField({ id, value, onChange }: { id: string; value: string; onChange: (location: string) => void }) {
  const [places] = useState(() => new PlaceSuggestions(photonSearch));
  const [state, setState] = useState(places.getState());
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const unsubscribe = places.subscribe(() => setState(places.getState()));
    return () => {
      unsubscribe();
      places.dispose();
    };
  }, [places]);

  // Erscheint die Liste, rollt die Seite sie ins Bild (sonst läge sie oft unter der Leiste unten), aber nie das Feld
  // oben hinaus. Sichtbar ist, was der Browser zeigt (`visualViewport`, am Handy ohne die Tastatur), ohne die
  // Kopfzeile oben und die Leiste unten, soweit sie im Bild sind (beide bleiben beim Rollen stehen).
  useEffect(() => {
    const field = input.current;
    const block = list.current;
    if (state.suggestions.length === 0 || field === null || block === null) return;
    const viewport = window.visualViewport;
    let top = viewport?.offsetTop ?? 0;
    let bottom = top + (viewport?.height ?? window.innerHeight);
    const header = document.querySelector(".top-bar")?.getBoundingClientRect();
    if (header !== undefined && header.bottom > top && header.bottom < bottom) top = header.bottom;
    const bar = document.querySelector(".bottom-bar")?.getBoundingClientRect();
    if (bar !== undefined && bar.top > top && bar.top < bottom) bottom = bar.top;
    const delta = revealScroll(field.getBoundingClientRect(), block.getBoundingClientRect(), { top, bottom });
    if (delta > 0) window.scrollBy(0, delta);
  }, [state.suggestions]);

  const pick = (suggestion: PlaceSuggestion, byKeyboard: boolean) => {
    onChange(suggestion.text);
    places.close();
    // Mit der Tastatur gewählt: Der Fokus bleibt im Feld, statt mit der Liste zu verschwinden.
    if (byKeyboard) input.current?.focus();
  };

  const hintId = `${id}-ohne-internet`;
  return (
    <>
      <div class="text-field">
        <label for={id}>Ort (freiwillig)</label>
        <input
          ref={input}
          id={id}
          type="text"
          autoComplete="off"
          maxLength={MAX_LOCATION_LENGTH}
          value={value}
          aria-describedby={state.offline ? hintId : undefined}
          onInput={(event) => {
            const text = (event.currentTarget as HTMLInputElement).value;
            onChange(text);
            places.typed(text);
          }}
        />
        {state.suggestions.length > 0 ? (
          <div class="place-suggestions" ref={list}>
            <ul class="place-list" aria-label="Ortsvorschläge">
              {state.suggestions.map((suggestion) => (
                <li key={suggestion.text}>
                  <button
                    type="button"
                    class="place-option"
                    aria-label={spokenName(suggestion)}
                    // `detail` ist 0, wenn der Knopf mit der Tastatur ausgelöst wurde.
                    onClick={(event) => pick(suggestion, event.detail === 0)}
                  >
                    <Icon name="place" />
                    <span class="place-option-text">
                      <span class="place-title">{suggestion.title}</span>
                      {suggestion.details !== "" ? <span class="place-details">{suggestion.details}</span> : null}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <p class="place-attribution">Vorschläge: © OpenStreetMap-Mitwirkende, über photon.komoot.io</p>
          </div>
        ) : null}
      </div>
      {/* Immer da, damit Screenreader den Hinweis vorlesen, sobald er erscheint. */}
      <div aria-live="polite">
        {state.offline ? (
          <p id={hintId} class="hint">
            Ortsvorschläge gibt es nur mit Internet.
          </p>
        ) : null}
      </div>
    </>
  );
}
