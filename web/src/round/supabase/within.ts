/* Begrenztes Warten: Nichts, was mit dem Netz zu tun hat, soll die App lange aufhalten. */

/** Das Warten hat länger gedauert als erlaubt. */
export class TimeoutError extends Error {
  constructor() {
    super("Zeitüberschreitung");
    this.name = "TimeoutError";
  }
}

/**
 * Wartet höchstens `millis` auf `work` und scheitert sonst mit {@link TimeoutError}. Die Arbeit selbst läuft weiter;
 * ihr spätes Ergebnis (auch ein später Fehler) wird dann nicht mehr beachtet.
 */
export function within<T>(work: PromiseLike<T>, millis: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new TimeoutError()), millis);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error(String(error)));
      },
    );
  });
}
