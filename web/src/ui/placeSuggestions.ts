/*
 * Steuerung der Ortsvorschläge beim Feld „Ort (freiwillig)“ (docs/ARCHITEKTUR.md, Abschnitt 4.3); „Neuer Abend“ und
 * „Abend bearbeiten“ teilen sich das. Verhaltensgleich zur Android-App (`app/ui/place/PlaceSuggestions.kt`), hier ohne
 * Oberfläche: Suche und Uhr sind austauschbar, damit die Tests die Zeit selbst vorstellen können.
 *
 * - Gesucht wird nur nach einer Eingabe des Nutzers ({@link PlaceSuggestions.typed}), nie für einen vorhandenen Ort.
 * - Hat der getrimmte Text (Kotlins `trim`) mindestens drei Zeichen, wird 400 ms nach der letzten Eingabe nach ihm
 *   gesucht. Darunter: keine Suche, Vorschläge und Hinweis verschwinden sofort.
 * - Jede neue Eingabe verwirft die geplante oder laufende Suche; es zählt nur die Antwort auf die letzte Eingabe. Bis sie
 *   da ist, bleiben die bisherigen Vorschläge stehen (die Liste flackert nicht bei jedem Buchstaben); der Hinweis auf
 *   fehlendes Internet verschwindet sofort.
 * - Scheitert die Suche, gibt es keine Liste, sondern den Hinweis „Ortsvorschläge gibt es nur mit Internet.“ bis zur
 *   nächsten Eingabe.
 * - Nach dem Antippen eines Vorschlags ({@link PlaceSuggestions.close}) ist die Liste zu, und gesucht wird erst wieder,
 *   wenn der Nutzer tippt.
 */
import { kotlinTrim } from "../core/kotlin";
import type { PlaceSuggestion } from "../data/place/photon";

/**
 * Was unter dem Feld „Ort“ steht.
 *
 * @property suggestions Die Vorschläge zur letzten Eingabe; leer heißt: keine Liste.
 * @property offline Die letzte Suche ist gescheitert: statt der Liste der Hinweis „Ortsvorschläge gibt es nur mit
 *   Internet.“.
 */
export interface PlaceSuggestionsState {
  readonly suggestions: readonly PlaceSuggestion[];
  readonly offline: boolean;
}

/** Weder Liste noch Hinweis. */
export const NO_SUGGESTIONS: PlaceSuggestionsState = { suggestions: [], offline: false };

/** Sucht nach dem schon getrimmten Text; `signal` meldet, dass die Antwort nicht mehr gebraucht wird. */
export type PlaceSearchFunction = (query: string, signal: AbortSignal) => Promise<readonly PlaceSuggestion[]>;

/** Die Uhr für das Warten nach der letzten Eingabe (in Tests austauschbar). */
export interface SuggestionTimers {
  setTimeout(callback: () => void, millis: number): unknown;
  clearTimeout(handle: unknown): void;
}

const browserTimers: SuggestionTimers = {
  setTimeout: (callback, millis) => setTimeout(callback, millis),
  clearTimeout: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/**
 * Wie weit die Seite nach unten rollen soll, damit die Liste unter dem Feld zu sehen ist (in Pixeln; 0: gar nicht).
 * Höchstens so weit, dass das Feld selbst nicht oben aus dem sichtbaren Bereich rollt: Am Handy steht beim Tippen die
 * Tastatur über der Seite, und dann soll eher ein Teil der Liste verdeckt bleiben als das Feld, in das man tippt.
 *
 * @param field Oberkante des Felds, @param list Unterkante der Liste und @param visible der sichtbare Bereich, alles
 *   in denselben Koordinaten (zum Beispiel `getBoundingClientRect` und `visualViewport`).
 * @param gap So viel Platz bleibt über dem Feld.
 */
export function revealScroll(field: { readonly top: number }, list: { readonly bottom: number }, visible: { readonly top: number; readonly bottom: number }, gap = 8): number {
  const needed = list.bottom - visible.bottom;
  const allowed = field.top - visible.top - gap;
  return Math.max(0, Math.min(needed, allowed));
}

/** So viele Zeichen (getrimmt) braucht eine Suche mindestens. */
export const MIN_QUERY_LENGTH = 3;

/** So lange nach der letzten Eingabe wird gesucht (Millisekunden). */
export const SEARCH_DELAY_MILLIS = 400;

export class PlaceSuggestions {
  private state: PlaceSuggestionsState = NO_SUGGESTIONS;
  private readonly listeners = new Set<() => void>();
  /** Zählt jede Eingabe und jedes Schließen: Eine Antwort zählt nur, wenn seit ihrer Suche nichts davon geschah. */
  private generation = 0;
  private timer: unknown = null;
  private running: AbortController | null = null;
  private disposed = false;

  constructor(
    private readonly search: PlaceSearchFunction,
    private readonly timers: SuggestionTimers = browserTimers,
    private readonly delayMillis: number = SEARCH_DELAY_MILLIS,
  ) {}

  getState(): PlaceSuggestionsState {
    return this.state;
  }

  /** Meldet jede Änderung des Zustands; gibt eine Funktion zum Abmelden zurück. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Der Nutzer hat den Ort zu `text` geändert. */
  typed(text: string): void {
    if (this.disposed) return;
    this.cancel();
    const query = kotlinTrim(text);
    if (query.length < MIN_QUERY_LENGTH) {
      this.set(NO_SUGGESTIONS);
      return;
    }
    if (this.state.offline) this.set({ ...this.state, offline: false });
    const generation = this.generation;
    this.timer = this.timers.setTimeout(() => {
      this.timer = null;
      this.run(query, generation);
    }, this.delayMillis);
  }

  /** Schließt die Liste (nach dem Antippen eines Vorschlags); gesucht wird erst wieder nach der nächsten Eingabe. */
  close(): void {
    if (this.disposed) return;
    this.cancel();
    this.set(NO_SUGGESTIONS);
  }

  /** Beendet alles (der Bildschirm ist zu): keine Suche und keine Meldung mehr. */
  dispose(): void {
    this.cancel();
    this.disposed = true;
    this.listeners.clear();
  }

  private run(query: string, generation: number): void {
    const controller = new AbortController();
    this.running = controller;
    let answer: Promise<readonly PlaceSuggestion[]>;
    try {
      answer = this.search(query, controller.signal);
    } catch (error) {
      answer = Promise.reject(error);
    }
    answer.then(
      (suggestions) => this.finish(generation, controller, { suggestions: [...suggestions], offline: false }),
      () => this.finish(generation, controller, { suggestions: [], offline: true }),
    );
  }

  private finish(generation: number, controller: AbortController, next: PlaceSuggestionsState): void {
    // Eine Antwort auf eine ältere Eingabe (oder nach dem Schließen) zählt nicht, auch wenn die Suche den Abbruch
    // übergangen hat.
    if (this.disposed || generation !== this.generation) return;
    if (this.running === controller) this.running = null;
    this.set(next);
  }

  /** Verwirft die geplante und die laufende Suche. */
  private cancel(): void {
    this.generation++;
    if (this.timer !== null) {
      this.timers.clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.running !== null) {
      this.running.abort();
      this.running = null;
    }
  }

  private set(next: PlaceSuggestionsState): void {
    if (next.offline === this.state.offline && next.suggestions.length === 0 && this.state.suggestions.length === 0) return;
    this.state = next;
    for (const listener of [...this.listeners]) listener();
  }
}
