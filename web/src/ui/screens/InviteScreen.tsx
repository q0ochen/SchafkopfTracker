/*
 * Die Einladungsseite der Web-App (`#/einladung/<Code>`, docs/ARCHITEKTUR.md, Abschnitt 8.9): Wer einen Einladungslink
 * antippt, landet hier und wählt, ob er in der Android-App oder gleich hier beitritt.
 */
import { InviteLinks } from "../../core/round/InviteLinks";
import { TopBar } from "../components";
import { useServices } from "../context";

export function InviteScreen({ token: text }: { token: string }) {
  const { round, router } = useServices();
  // Nur ein echter Code zählt (32 Zeichen, 0–9 und a–f); alles andere im Link wird nie weiterverwendet.
  const token = InviteLinks.tokenFrom(text);
  return (
    <div class="screen">
      <TopBar title="Gemeinsame Runde" onBack={() => router.back()} />
      <main class="content round">
        {token === null ? (
          <p class="round-lead">Das ist kein gültiger Einladungslink.</p>
        ) : (
          <>
            <h2 class="round-heading">Einladung in eine Schafkopfrunde</h2>
            <a class="button main full-width spaced" href={InviteLinks.appLink(token)}>
              In der Android-App öffnen
            </a>
            <button
              type="button"
              class="button primary main full-width spaced"
              onClick={() => {
                // Der Code wird gemerkt, auch über die Anmeldung hinweg; eingelöst wird er auf „Gemeinsame Runde“.
                round?.rememberInvite(token);
                router.open({ name: "round" }, { replace: true });
              }}
            >
              Hier beitreten
            </button>
          </>
        )}
      </main>
    </div>
  );
}
