/*
 * Der Server der gemeinsamen Runde, dessen Anbindung erst bei Bedarf geladen wird: Abgleich und Verbinden bekommen
 * diesen Stellvertreter, und die Bibliothek für den Server kommt erst mit dem ersten Aufruf ins Spiel.
 */
import { RoundException } from "../core/round/RoundModels";
import { ROUND_API_METHODS, type RoundApi } from "../core/round/RoundPorts";

/**
 * @param load Liefert die geladene Anbindung. Scheitert das Laden (zum Beispiel ohne Netz, bevor die App vollständig
 *   auf dem Gerät liegt), gilt der Aufruf als „keine Verbindung“.
 */
export function deferredApi(load: () => Promise<RoundApi>): RoundApi {
  const api: Record<string, (...args: never[]) => Promise<unknown>> = {};
  for (const method of ROUND_API_METHODS) {
    api[method] = async (...args: never[]) => {
      let inner: RoundApi;
      try {
        inner = await load();
      } catch (error) {
        if (error instanceof RoundException) throw error;
        throw new RoundException("OFFLINE", "Anbindung nicht geladen", error);
      }
      return (inner[method] as (...parameters: never[]) => Promise<unknown>)(...args);
    };
  }
  return api as unknown as RoundApi;
}
