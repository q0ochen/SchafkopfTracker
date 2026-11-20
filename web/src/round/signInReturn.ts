/*
 * Die Rückkehr aus der Anmeldung bei Google (docs/ARCHITEKTUR.md, Abschnitt 8.1): Der Server schickt den Browser auf
 * die Start-Adresse der Web-App zurück und hängt einen einmaligen Code an (`?code=…`) oder, wenn es nicht geklappt
 * hat, einen Fehler (`?error=…`; manche Fehler stehen stattdessen hinter dem „#“). Die App löst den Code ein und
 * nimmt die Angaben sofort aus der Adresse, damit sie weder im Verlauf noch in einem Lesezeichen landen.
 */

/** Was die Rückkehr-Adresse sagt (in der Android-App `AuthCallback`). */
export type SignInReturn =
  | { readonly kind: "code"; readonly code: string }
  /** `error` ist der Fehlercode des Servers, zum Beispiel `access_denied`, wenn die Anmeldung im Browser abgelehnt wurde. */
  | { readonly kind: "failure"; readonly error: string };

/**
 * Liest die Rückkehr aus der Anmeldung aus einer Adresse; `null`, wenn die Adresse keine ist.
 *
 * @param search Der Teil der Adresse ab „?“ (`location.search`).
 * @param hash Der Teil der Adresse ab „#“ (`location.hash`). Seiten der App beginnen mit „#/“ und sind nie eine Rückkehr.
 */
export function parseSignInReturn(search: string, hash: string): SignInReturn | null {
  const query = new URLSearchParams(search);
  const fragment = hash.startsWith("#/") ? new URLSearchParams() : new URLSearchParams(hash.replace(/^#/, ""));
  const error = query.get("error") ?? query.get("error_code") ?? fragment.get("error") ?? fragment.get("error_code");
  if (error !== null && error !== "") return { kind: "failure", error };
  const code = query.get("code");
  if (code !== null && code !== "") return { kind: "code", code };
  return null;
}
