/* Zugriff der Bildschirme auf Zustand, Navigation und Gerätefunktionen. */
import { createContext } from "preact";
import { useContext, useEffect, useMemo, useState } from "preact/hooks";
import type { BackupPort } from "../app/backup";
import type { Router } from "../app/router";
import type { AppState, AppStore } from "../app/store";
import type { Platform } from "../platform/platform";
import { NOT_CONFIGURED, type RoundController, type RoundState } from "../round/controller";

export interface Services {
  readonly store: AppStore;
  readonly router: Router;
  readonly platform: Platform;
  readonly backup: BackupPort;
  /** Die gemeinsame Runde; `null`, wenn die App ohne Server gebaut wurde. */
  readonly round: RoundController | null;
  /** Version der Web-App (aus package.json, im Build mit Commit). */
  readonly version: string;
}

export const ServicesContext = createContext<Services | null>(null);

export function useServices(): Services {
  const services = useContext(ServicesContext);
  if (!services) throw new Error("ServicesContext fehlt");
  return services;
}

/** Der aktuelle Zustand; die Komponente wird bei jeder Änderung neu gezeichnet. */
export function useAppState(): AppState {
  const { store } = useServices();
  const [state, setState] = useState(store.getState());
  useEffect(() => {
    setState(store.getState());
    return store.subscribe(() => setState(store.getState()));
  }, [store]);
  return state;
}

/** Namen aller Spieler (auch archivierter) nach Kennung. */
export function useNames(): ReadonlyMap<string, string> {
  const { players } = useAppState();
  return useMemo(() => new Map(players.map((it) => [it.id, it.name])), [players]);
}

/** Farbnummer aller Spieler nach Kennung. */
export function useColors(): ReadonlyMap<string, number> {
  const { players } = useAppState();
  return useMemo(() => new Map(players.map((it) => [it.id, it.colorIndex])), [players]);
}

/** Der Stand der gemeinsamen Runde; die Komponente wird bei jeder Änderung neu gezeichnet. */
export function useRoundState(): RoundState {
  const { round } = useServices();
  const [state, setState] = useState(round?.getState() ?? NOT_CONFIGURED);
  useEffect(() => {
    if (round === null) return;
    setState(round.getState());
    return round.subscribe(() => setState(round.getState()));
  }, [round]);
  return state;
}
