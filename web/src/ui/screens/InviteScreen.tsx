/*
 * Die Einladungsseite der Web-App (`#/einladung/<Code>`, docs/ARCHITEKTUR.md, Abschnitt 8.9): Wer einen
 * Einladungslink antippt, landet hier. Der Code steht zum Kopieren da (am iPhone öffnen sich Links in Safari, die App
 * vom Home-Bildschirm hat aber einen eigenen Speicher); auf Android geht es in die Android-App, sonst gleich hier weiter.
 * Auch ein Gerät, das schon mit einer Runde verbunden ist, kann hier beitreten (und danach zwischen den Runden
 * wechseln). Was die Seite zeigt, entscheidet `ui/invitePage.ts`.
 */
import { useState } from "preact/hooks";
import { InviteLinks } from "../../core/round/InviteLinks";
import { ROUNDS_TITLE } from "../../round/texts";
import { TopBar } from "../components";
import { useRoundState, useServices } from "../context";
import { alreadyLinkedText, invitePage, IPHONE_HINT } from "../invitePage";

function buttonClass(primary: boolean): string {
  return primary ? "button primary main full-width spaced" : "button full-width spaced";
}

export function InviteScreen({ token: text }: { token: string }) {
  const { round, router, platform } = useServices();
  const state = useRoundState();
  // Nur ein echter Code zählt (32 Zeichen, 0–9 und a–f); alles andere im Link wird nie weiterverwendet.
  const token = InviteLinks.tokenFrom(text);
  // `null`: noch nicht kopiert; danach, ob es geklappt hat.
  const [copied, setCopied] = useState<boolean | null>(null);
  const linkedRound = state.link?.roundName ?? null;
  const page = invitePage({ os: platform.os(), standalone: platform.standalone(), linkedRound });

  let body;
  if (token === null) {
    body = <p class="round-lead">Das ist kein gültiger Einladungslink.</p>;
  } else {
    body = (
      <>
        <h2 class="round-heading">Einladung in eine Schafkopfrunde</h2>
        <p class="invite-code" translate={false}>
          {token}
        </p>
        <div aria-live="polite">
          <button type="button" class={buttonClass(page.primary === "copy")} onClick={() => void platform.copyText(token).then(setCopied)}>
            {copied === true ? "Code kopiert" : "Code kopieren"}
          </button>
          {copied === false ? <p class="hint error-text">Das Kopieren hat nicht geklappt. Bitte den Code von Hand kopieren.</p> : null}
        </div>
        {page.iphoneHint ? <p class="invite-hint">{IPHONE_HINT}</p> : null}
        {page.androidApp ? (
          <a class={buttonClass(page.primary === "android")} href={InviteLinks.appLink(token)}>
            In der Android-App öffnen
          </a>
        ) : null}
        {page.alreadyLinked && linkedRound !== null ? <p class="invite-hint">{alreadyLinkedText(linkedRound)}</p> : null}
        <button
          type="button"
          class={buttonClass(page.primary === "join")}
          onClick={() => {
            // Der Code wird gemerkt, auch über die Anmeldung hinweg; eingelöst wird er auf „Gemeinsame Runden“.
            round?.rememberInvite(token);
            router.open({ name: "round" }, { replace: true });
          }}
        >
          Hier beitreten
        </button>
      </>
    );
  }

  return (
    <div class="screen">
      <TopBar title={ROUNDS_TITLE} onBack={() => router.back()} />
      <main class="content round">{body}</main>
    </div>
  );
}
