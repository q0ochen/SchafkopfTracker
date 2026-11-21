/*
 * „Gemeinsame Runden“ in einer App, die ohne Server gebaut wurde (docs/ARCHITEKTUR.md, Abschnitt 8.9): nur der Hinweis.
 * Eigene Datei, damit der Build ohne Server den ganzen übrigen Bildschirm weglassen kann.
 */
import { ROUNDS_TITLE } from "../../round/texts";
import { TopBar } from "../components";
import { useServices } from "../context";

export function RoundDisabledScreen() {
  const { router } = useServices();
  return (
    <div class="screen">
      <TopBar title={ROUNDS_TITLE} onBack={() => router.back()} />
      <main class="content round">
        <p class="round-lead">Der Server ist noch nicht eingerichtet.</p>
        <p>Sobald er eingerichtet ist, können alle vom eigenen Handy eintragen.</p>
      </main>
    </div>
  );
}
