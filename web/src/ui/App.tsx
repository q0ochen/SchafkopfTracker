/* Wurzel der Oberfläche: wählt den Bildschirm zur Adresse, setzt das Erscheinungsbild und zeigt Hinweise. */
import { useEffect, useState } from "preact/hooks";
import type { Route } from "../app/router";
import type { ThemeMode } from "../core/model";
import { ServicesContext, useAppState, useServices, type Services } from "./context";
import { BoardScreen } from "./screens/BoardScreen";
import { ClosingScreen } from "./screens/ClosingScreen";
import { GameFormScreen } from "./screens/GameFormScreen";
import { InviteScreen } from "./screens/InviteScreen";
import { NewEveningScreen } from "./screens/NewEveningScreen";
import { PlayersScreen } from "./screens/PlayersScreen";
import { RoundDisabledScreen } from "./screens/RoundDisabledScreen";
import { RoundScreen } from "./screens/RoundScreen";
import { RulesScreen } from "./screens/RulesScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { StartScreen } from "./screens/StartScreen";
import { StatisticsScreen } from "./screens/StatisticsScreen";

function Screen({ route }: { route: Route }) {
  switch (route.name) {
    case "start":
      return <StartScreen />;
    case "newEvening":
      return <NewEveningScreen />;
    case "board":
      return <BoardScreen eveningId={route.eveningId} />;
    case "gameForm":
      return <GameFormScreen eveningId={route.eveningId} entryId={route.entryId} />;
    case "rules":
      return <RulesScreen eveningId={route.eveningId} />;
    case "statistics":
      return <StatisticsScreen eveningId={route.eveningId} />;
    case "closing":
      return <ClosingScreen eveningId={route.eveningId} />;
    case "players":
      return <PlayersScreen />;
    case "settings":
      return <SettingsScreen />;
    case "round":
      // Ohne Server lässt der Build den Bildschirm samt seiner Logik weg; übrig bleibt der Hinweis.
      return import.meta.env.VITE_ROUND_ENABLED ? <RoundScreen /> : <RoundDisabledScreen />;
    case "invite":
      return <InviteScreen token={route.token} />;
  }
}

/** Überträgt das gewählte Erscheinungsbild auf die Seite (und die Farbe der Statusleiste). */
function useTheme(mode: ThemeMode): void {
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = mode === "LIGHT" ? "light" : mode === "SYSTEM" ? "system" : "dark";
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
    const light = mode === "LIGHT" || (mode === "SYSTEM" && window.matchMedia?.("(prefers-color-scheme: light)").matches);
    if (meta) meta.content = light ? "#f7faf5" : "#000000";
  }, [mode]);
}

function SaveErrorBanner() {
  const { saveError } = useAppState();
  if (saveError === null) return null;
  return (
    <div class="banner error" role="alert">
      Speichern fehlgeschlagen: Die letzte Änderung ist nur vorläufig da. Bitte Speicherplatz prüfen und die App nicht im
      privaten Modus benutzen.
    </div>
  );
}

function UpdateBanner({ onReload }: { onReload: () => void }) {
  return (
    <div class="update-banner" role="status">
      <span>Eine neue Version ist da.</span>
      <button type="button" class="button" onClick={onReload}>
        Neu laden
      </button>
    </div>
  );
}

function Shell({ updateReady, onReload }: { updateReady: boolean; onReload: () => void }) {
  const { router } = useServices();
  const { settings } = useAppState();
  const [route, setRoute] = useState<Route>(router.current());
  useTheme(settings.themeMode);
  useEffect(() => router.subscribe(setRoute), [router]);
  // Beim Wechsel des Bildschirms nach oben (außer der Abend springt selbst ans Ende).
  useEffect(() => {
    if (route.name !== "board") window.scrollTo({ top: 0 });
  }, [route]);
  return (
    <>
      {updateReady ? <UpdateBanner onReload={onReload} /> : null}
      <SaveErrorBanner />
      <Screen key={JSON.stringify(route)} route={route} />
    </>
  );
}

export function App({
  services,
  updateReady,
  onReload,
}: {
  services: Services;
  updateReady: boolean;
  onReload: () => void;
}) {
  return (
    <ServicesContext.Provider value={services}>
      <Shell updateReady={updateReady} onReload={onReload} />
    </ServicesContext.Provider>
  );
}
