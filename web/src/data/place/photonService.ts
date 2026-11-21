/*
 * Wo die Ortsvorschläge herkommen (docs/ARCHITEKTUR.md, Abschnitt 4.3). Eigene Datei ohne Abhängigkeiten, weil auch
 * vite.config.ts sie liest: Die Herkunft steht in jedem Build in `connect-src` der Content-Security-Policy.
 */

/** Die Herkunft des öffentlichen Photon-Servers von komoot. */
export const PHOTON_ORIGIN = "https://photon.komoot.io";
