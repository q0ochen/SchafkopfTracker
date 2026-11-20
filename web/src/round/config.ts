/*
 * Die öffentliche Konfiguration der gemeinsamen Runde: Adresse und Publishable Key des Supabase-Projekts
 * (docs/ARCHITEKTUR.md, Abschnitt 8.1). Beides ist kein Geheimnis: Es steht in jeder ausgelieferten App. Was ein Konto
 * lesen und schreiben darf, regelt allein der Server.
 *
 * Gesetzt werden die Werte beim Bauen (`VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`). Fehlen sie, gibt es in
 * dieser App keine gemeinsame Runde; alles andere funktioniert unverändert.
 */

export interface RoundConfig {
  /** Adresse des Projekts ohne Schrägstrich am Ende, zum Beispiel „https://abc.supabase.co“. */
  readonly url: string;
  readonly publishableKey: string;
}

/**
 * Die Konfiguration aus den Angaben, mit denen die App gebaut wurde; `null`, wenn sie fehlen oder unbrauchbar sind.
 * Verlangt wird eine `https`-Adresse; `http` nur für einen Server auf dem eigenen Rechner (beim Entwickeln).
 */
export function readRoundConfig(url: string | undefined, publishableKey: string | undefined): RoundConfig | null {
  const address = (url ?? "").trim().replace(/\/+$/, "");
  const key = (publishableKey ?? "").trim();
  if (address === "" || key === "") return null;
  let parsed: URL;
  try {
    parsed = new URL(address);
  } catch {
    return null;
  }
  const local = parsed.hostname === "localhost" || parsed.hostname === "127.0.0.1";
  if (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && local)) return null;
  return { url: address, publishableKey: key };
}
