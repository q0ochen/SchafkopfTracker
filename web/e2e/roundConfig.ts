/*
 * Feste Angaben für die Oberflächen-Tests der gemeinsamen Runde. Die App wird dafür ein zweites Mal gebaut, mit der
 * Adresse eines Servers, den es nicht gibt (die Endung „.invalid“ ist dafür reserviert): Alle Anfragen dorthin
 * beantworten die Tests selbst (siehe `roundSupport.ts`), nichts verlässt den Rechner.
 */

/** Die Adresse des nachgestellten Supabase-Projekts. */
export const FAKE_SUPABASE_URL = "https://runde.invalid";

/** Der öffentliche Schlüssel des nachgestellten Projekts (kein echter Schlüssel). */
export const FAKE_SUPABASE_KEY = "sb_publishable_nur_fuer_tests";

/** Die App ohne gemeinsame Runde, wie sie ohne Angaben zum Server gebaut wird. */
export const APP_URL = "http://127.0.0.1:4173/SchafkopfTracker/";

/** Die App mit gemeinsamer Runde. */
export const ROUND_APP_URL = "http://127.0.0.1:4174/SchafkopfTracker/";
