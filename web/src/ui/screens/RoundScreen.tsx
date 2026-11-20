/*
 * Gemeinsame Runde (docs/ARCHITEKTUR.md, Abschnitt 8.9, Texte wörtlich): anmelden, Runde anlegen oder beitreten,
 * „Das bin ich“, Mitglieder, einladen, verlassen. Wie `ui/round/RoundScreen.kt` der Android-App; die Logik steckt in
 * `round/screenModel.ts` und `round/controller.ts`.
 */
import { useEffect, useId, useMemo, useState } from "preact/hooks";
import type { Player, PlayerId } from "../../core/model";
import type { RoundLink, RoundSummary } from "../../core/round/RoundModels";
import { needsSignIn, type RoundController, type RoundState } from "../../round/controller";
import { RoundScreenModel, type Farewell, type RoundUi, type RoundUiError } from "../../round/screenModel";
import {
  farewellText,
  inviteMessage,
  LAST_MEMBER_TEXT,
  LEAVE_ROUND_TEXT,
  memberCountText,
  messageText,
  NO_CODE_ERROR,
  noticeText,
  pendingWarning,
  replaceText,
  roundErrorText,
  STORAGE_FAILED_TEXT,
} from "../../round/texts";
import { ChoiceTile, Dialog, Hint, PlayerName, SectionHeading, TopBar } from "../components";
import { useAppState, useRoundState, useServices } from "../context";
import { StatusLine } from "../round";
import { RoundDisabledScreen } from "./RoundDisabledScreen";

/** Ein vorbelegter Name ist noch keiner: Dann bleibt das Feld „Dein Name“ leer. */
const PLACEHOLDER_NAME = /^Spieler \d+$/;

function errorText(error: RoundUiError): string {
  switch (error.kind) {
    case "NoCode":
      return NO_CODE_ERROR;
    case "Storage":
      return STORAGE_FAILED_TEXT;
    case "Server":
      return roundErrorText(error.error, error.roundName);
  }
}

/** Ob das Konto das einzige Mitglied der verbundenen Runde ist (soweit bekannt): Dann löscht „Runde verlassen“ sie. */
function onlyMember(link: RoundLink | null): boolean {
  return link !== null && link.members.length > 0 && link.members.every((member) => member.userId === link.userId);
}

/** Eine Meldung, die stehen bleibt, bis man sie mit „OK“ wegtippt; Screenreader lesen sie von selbst vor. */
function MessageCard({ text, onDismiss, isError }: { text: string; onDismiss: () => void; isError?: boolean }) {
  return (
    <div class={isError ? "message-card error" : "message-card"} role={isError ? "alert" : "status"}>
      <span>{text}</span>
      <button type="button" class="text-button" onClick={onDismiss}>
        OK
      </button>
    </div>
  );
}

function TextField({
  label,
  value,
  onInput,
  hint,
  capitalize,
  maxLength,
}: {
  label: string;
  value: string;
  onInput: (value: string) => void;
  hint?: string;
  capitalize?: "words" | "sentences" | "off";
  maxLength?: number;
}) {
  const id = useId();
  return (
    <div class="text-field">
      <label for={id}>{label}</label>
      <input
        id={id}
        type="text"
        autoComplete="off"
        autoCapitalize={capitalize ?? "off"}
        autoCorrect="off"
        spellcheck={false}
        enterKeyHint="done"
        maxLength={maxLength}
        value={value}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onInput={(event) => onInput((event.currentTarget as HTMLInputElement).value)}
      />
      {hint ? (
        <p id={`${id}-hint`} class="hint">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

// --- Nicht angemeldet ---

function SignedOut({ invited, busy, onSignIn }: { invited: boolean; busy: boolean; onSignIn: () => void }) {
  return (
    <>
      <h2 class="round-heading">Gemeinsam eintragen</h2>
      {invited ? <p class="round-invited">Du bist eingeladen. Melde dich an, um beizutreten.</p> : null}
      <p>Alle in der Runde tragen vom eigenen Handy ein und sehen denselben Stand.</p>
      <Hint>
        Namen, Abende und Beträge der Runde liegen dafür auf einem Server in Frankfurt (Supabase). Zum Anmelden brauchst du ein
        Google-Konto.
      </Hint>
      <button type="button" class="button primary main full-width spaced-top" disabled={busy} onClick={onSignIn}>
        Mit Google anmelden
      </button>
    </>
  );
}

// --- Angemeldet, ohne Runde ---

function SignedIn({ state, ui, model }: { state: RoundState; ui: RoundUi; model: RoundScreenModel }) {
  const [name, setName] = useState("Schafkopfrunde");
  const [code, setCode] = useState("");
  const email = state.account?.email ?? null;
  const rounds = ui.rounds ?? [];
  const inviteTitleId = useId();
  return (
    <>
      <Hint>{email === null ? "Angemeldet" : `Angemeldet als ${email}`}</Hint>

      {state.pendingInvite !== null ? (
        <section class="card highlight" aria-labelledby={inviteTitleId}>
          <h2 id={inviteTitleId} class="card-title">
            Einladung in die Runde {ui.invite?.roundName ?? "…"}
          </h2>
          {ui.invite ? <p class="card-line">{memberCountText(ui.invite.memberCount)}</p> : null}
          <button type="button" class="button primary main full-width spaced-top" disabled={ui.busy} onClick={() => void model.joinInvited()}>
            Beitreten
          </button>
          <button type="button" class="text-button full-width" disabled={ui.busy} onClick={() => model.discardInvite()}>
            Einladung verwerfen
          </button>
        </section>
      ) : null}

      {ui.roundsFailed && rounds.length === 0 ? (
        // Ohne diesen Hinweis sähe es auf einem neuen Handy aus, als gäbe es keine Runde.
        <>
          <SectionHeading>Deine Runden</SectionHeading>
          <p>Deine Runden ließen sich nicht laden.</p>
          <button type="button" class="button full-width" disabled={ui.busy} onClick={() => void model.retry()}>
            Erneut versuchen
          </button>
        </>
      ) : null}

      {rounds.length > 0 ? (
        <>
          <SectionHeading>Deine Runden</SectionHeading>
          <ul class="list" aria-label="Deine Runden">
            {rounds.map((round: RoundSummary) => (
              <li key={round.id} class="card">
                <span class="card-title">{round.name}</span>
                <span class="card-line hint">{memberCountText(round.memberCount)}</span>
                <button
                  type="button"
                  class="button full-width spaced-top"
                  disabled={ui.busy}
                  aria-label={`${round.name} auf diesem Gerät verwenden`}
                  onClick={() => void model.use(round)}
                >
                  Auf diesem Gerät verwenden
                </button>
                <button
                  type="button"
                  class="text-button danger-text full-width"
                  disabled={ui.busy}
                  aria-label={`${round.name} verlassen`}
                  onClick={() => model.askLeave(round)}
                >
                  Verlassen
                </button>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <form
        aria-label="Neue Runde anlegen"
        onSubmit={(event) => {
          event.preventDefault();
          if (name.trim() !== "") void model.create(name);
        }}
      >
        <SectionHeading>Neue Runde anlegen</SectionHeading>
        <TextField label="Name der Runde" value={name} onInput={setName} capitalize="sentences" maxLength={60} />
        <Hint>Alle Abende und Spieler dieses Geräts kommen mit in die Runde.</Hint>
        <button type="submit" class="button primary main full-width spaced-top" disabled={ui.busy || name.trim() === ""}>
          Runde anlegen
        </button>
      </form>

      <form
        aria-label="Einladung einlösen"
        onSubmit={(event) => {
          event.preventDefault();
          if (code.trim() !== "") void model.redeem(code);
        }}
      >
        <SectionHeading>Einladung einlösen</SectionHeading>
        <TextField label="Link oder Code" value={code} onInput={setCode} />
        <button type="submit" class="button full-width spaced-top" disabled={ui.busy || code.trim() === ""}>
          Beitreten
        </button>
      </form>

      <hr class="divider" />
      <button type="button" class="text-button" disabled={ui.busy} onClick={() => void model.askFarewell("SIGN_OUT")}>
        Abmelden
      </button>
    </>
  );
}

// --- Verbunden ---

/** „Wer bist du?“: einen Spieler ohne Besitzer wählen oder neu dazukommen, dann den eigenen Namen festlegen. */
function ClaimCard({
  state,
  players,
  busy,
  onClaim,
}: {
  state: RoundState;
  players: readonly Player[];
  busy: boolean;
  onClaim: (player: PlayerId | null, name: string) => void;
}) {
  // Wählbar ist, wer noch niemandem gehört. Solange der Stand des Servers fehlt, gilt das für alle.
  const candidates = players.filter((it) => !it.archived && (state.players.get(it.id)?.owner ?? null) === null);
  const [chosen, setChosen] = useState<PlayerId | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [name, setName] = useState("");
  const [confirm, setConfirm] = useState(false);
  const titleId = useId();
  // Hat inzwischen jemand anders den gewählten Spieler übernommen, gilt die Wahl nicht mehr.
  const chosenStill = chosen !== null && candidates.some((it) => it.id === chosen) ? chosen : null;
  const ready = !busy && name.trim() !== "" && (isNew || chosenStill !== null);
  return (
    <section class="card accent" aria-labelledby={titleId}>
      <h2 id={titleId} class="card-title">
        Wer bist du?
      </h2>
      <div class="stack-8" role="group" aria-labelledby={titleId}>
        {candidates.length > 0 ? (
          <div class="choice-grid-2">
            {candidates.map((player) => (
              <ChoiceTile
                key={player.id}
                text={player.name}
                selected={!isNew && chosenStill === player.id}
                onClick={() => {
                  setChosen(player.id);
                  setIsNew(false);
                  setName(PLACEHOLDER_NAME.test(player.name) ? "" : player.name);
                }}
              />
            ))}
          </div>
        ) : null}
        <ChoiceTile
          text="Ich bin neu"
          selected={isNew}
          onClick={() => {
            setIsNew(true);
            setChosen(null);
          }}
        />
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) setConfirm(true);
        }}
      >
        <TextField
          label="Dein Name"
          value={name}
          onInput={setName}
          hint="Dein Name lässt sich danach nicht mehr ändern."
          capitalize="words"
          maxLength={40}
        />
        <button type="submit" class="button primary main full-width spaced-top" disabled={!ready}>
          Das bin ich
        </button>
      </form>
      {confirm ? (
        <Dialog
          title={`Als ${name.trim()} festlegen?`}
          onClose={() => setConfirm(false)}
          actions={
            <>
              <button type="button" class="text-button" onClick={() => setConfirm(false)}>
                Abbrechen
              </button>
              <button
                type="button"
                class="button primary"
                onClick={() => {
                  setConfirm(false);
                  onClaim(isNew ? null : chosenStill, name);
                }}
              >
                Festlegen
              </button>
            </>
          }
        >
          <p>Der Name lässt sich danach nicht mehr ändern.</p>
        </Dialog>
      ) : null}
    </section>
  );
}

/** Der erzeugte Einladungslink zum Weitergeben. Teilen und Kopieren brauchen im Browser jeweils ein eigenes Antippen. */
function ShareCard({ roundName, link, onDone }: { roundName: string; link: string; onDone: () => void }) {
  const { platform } = useServices();
  const [feedback, setFeedback] = useState<string | null>(null);
  const text = inviteMessage(roundName, link);
  const titleId = useId();
  const share = async () => {
    const outcome = await platform.shareText("Einladung weitergeben", text);
    if (outcome === "copied") setFeedback("Die Einladung ist kopiert.");
    else if (outcome === "failed") setFeedback("Das Weitergeben hat nicht geklappt. Bitte den Text von Hand kopieren.");
    else setFeedback(null);
  };
  const copy = async () => {
    setFeedback((await platform.copyText(text)) ? "Die Einladung ist kopiert." : "Das Kopieren hat nicht geklappt. Bitte den Text von Hand kopieren.");
  };
  return (
    <section class="card" aria-labelledby={titleId}>
      <h2 id={titleId} class="card-title">
        Einladung weitergeben
      </h2>
      <p class="invite-text">{text}</p>
      <div class="row">
        {platform.canShareText() ? (
          <button type="button" class="button primary" onClick={() => void share()}>
            Teilen
          </button>
        ) : null}
        <button type="button" class={platform.canShareText() ? "button" : "button primary"} onClick={() => void copy()}>
          Kopieren
        </button>
      </div>
      {feedback ? (
        <p class="hint" role="status">
          {feedback}
        </p>
      ) : null}
      <button type="button" class="text-button full-width" onClick={onDone}>
        Fertig
      </button>
    </section>
  );
}

function Linked({
  state,
  link,
  ui,
  model,
  controller,
  players,
}: {
  state: RoundState;
  link: RoundLink;
  ui: RoundUi;
  model: RoundScreenModel;
  controller: RoundController;
  players: readonly Player[];
}) {
  const expired = needsSignIn(state);
  const byId = useMemo(() => new Map(players.map((it) => [it.id, it])), [players]);
  return (
    <>
      <h2 class="round-heading">{link.roundName}</h2>
      <p class="round-status-row">
        <StatusLine state={state} live />
      </p>

      {expired ? (
        <button type="button" class="button primary main full-width spaced-top" disabled={ui.busy} onClick={() => void model.signIn()}>
          Mit Google anmelden
        </button>
      ) : (
        <>
          <button type="button" class="button full-width spaced-top" disabled={ui.busy} onClick={() => controller.syncNow()}>
            Jetzt abgleichen
          </button>
          {link.playerId === null ? (
            <ClaimCard state={state} players={players} busy={ui.busy} onClaim={(player, name) => void model.claim(player, name)} />
          ) : null}
        </>
      )}

      <SectionHeading>Mitglieder</SectionHeading>
      <ul class="list" aria-label="Mitglieder">
        {link.members.map((member) => {
          const player = member.playerId === null ? undefined : byId.get(member.playerId);
          const isMe = member.userId === link.userId;
          return (
            <li key={member.userId} class="member">
              {player === undefined ? (
                <span class="hint">{isMe ? "Noch ohne Namen (du)" : "Noch ohne Namen"}</span>
              ) : (
                <PlayerName name={isMe ? `${player.name} (du)` : player.name} colorIndex={player.colorIndex} />
              )}
            </li>
          );
        })}
      </ul>

      {expired ? null : (
        <>
          <button type="button" class="button primary main full-width spaced-top" disabled={ui.busy} onClick={() => void model.invite()}>
            Einladen
          </button>
          <Hint>Der Link gilt 14 Tage.</Hint>
          {ui.shareLink !== null ? <ShareCard roundName={link.roundName} link={ui.shareLink} onDone={() => model.shared()} /> : null}
        </>
      )}

      {state.notices.length > 0 ? (
        <>
          <SectionHeading>Hinweise</SectionHeading>
          <ul class="list notices" aria-label="Hinweise">
            {state.notices.map((notice, index) => (
              <li key={index}>{noticeText(notice)}</li>
            ))}
          </ul>
          <button type="button" class="text-button" onClick={() => controller.clearNotices()}>
            Hinweise ausblenden
          </button>
        </>
      ) : null}

      <hr class="divider" />
      <div class="row">
        {expired ? null : (
          <button type="button" class="text-button danger-text" disabled={ui.busy} onClick={() => void model.askFarewell("LEAVE")}>
            Runde verlassen
          </button>
        )}
        <button type="button" class="text-button" disabled={ui.busy} onClick={() => void model.askFarewell("SIGN_OUT")}>
          Abmelden
        </button>
      </div>
    </>
  );
}

// --- Rückfragen ---

function ReplaceDialog({ eveningCount, model, onBackup }: { eveningCount: number; model: RoundScreenModel; onBackup: () => void }) {
  return (
    <Dialog
      title="Daten dieses Geräts ersetzen?"
      onClose={() => model.cancelReplace()}
      actions={
        <>
          <button type="button" class="text-button" onClick={() => model.cancelReplace()}>
            Abbrechen
          </button>
          <button type="button" class="text-button" onClick={onBackup}>
            Zur Sicherung
          </button>
          <button type="button" class="button danger" onClick={() => void model.confirmReplace()}>
            Ersetzen
          </button>
        </>
      }
    >
      <p>{replaceText(eveningCount)}</p>
    </Dialog>
  );
}

function FarewellDialog({
  kind,
  pending,
  link,
  model,
}: {
  kind: Farewell;
  pending: number;
  link: RoundLink | null;
  model: RoundScreenModel;
}) {
  const leaving = kind === "LEAVE";
  return (
    <Dialog
      title={leaving ? "Runde verlassen?" : "Abmelden?"}
      onClose={() => model.cancelFarewell()}
      actions={
        <>
          <button type="button" class="text-button" onClick={() => model.cancelFarewell()}>
            Abbrechen
          </button>
          <button type="button" class="button danger" onClick={() => void model.confirmFarewell()}>
            {leaving ? "Runde verlassen" : "Abmelden"}
          </button>
        </>
      }
    >
      <p>{farewellText(kind, link !== null, onlyMember(link))}</p>
      {link !== null && pending > 0 ? <p class="error-text">{pendingWarning(pending)}</p> : null}
    </Dialog>
  );
}

/** Rückfrage vor dem Verlassen einer Runde des Kontos, die nicht mit diesem Gerät verbunden ist. */
function LeaveRoundDialog({ round, model }: { round: RoundSummary; model: RoundScreenModel }) {
  return (
    <Dialog
      title={`${round.name} verlassen?`}
      onClose={() => model.cancelLeave()}
      actions={
        <>
          <button type="button" class="text-button" onClick={() => model.cancelLeave()}>
            Abbrechen
          </button>
          <button type="button" class="button danger" onClick={() => void model.confirmLeave()}>
            Verlassen
          </button>
        </>
      }
    >
      <p>{LEAVE_ROUND_TEXT}</p>
      {round.memberCount === 1 ? <p class="error-text">{LAST_MEMBER_TEXT}</p> : null}
    </Dialog>
  );
}

// --- Der Bildschirm ---

function Configured({ controller }: { controller: RoundController }) {
  const { store, router } = useServices();
  const { players } = useAppState();
  const state = useRoundState();
  const model = useMemo(() => new RoundScreenModel(controller, () => store.getState().evenings.length), [controller, store]);
  const [ui, setUi] = useState(model.getUi());
  useEffect(() => {
    setUi(model.getUi());
    const unsubscribe = model.subscribe(() => setUi(model.getUi()));
    return () => {
      unsubscribe();
      model.dispose();
    };
  }, [model]);

  // Meldungen und Fehler stehen oben; wer gerade weiter unten getippt hat, soll sie trotzdem sehen.
  const showsMessage = state.message !== null || ui.error !== null;
  useEffect(() => {
    if (showsMessage) window.scrollTo({ top: 0 });
  }, [showsMessage, state.message, ui.error]);

  // Auch während die Anmeldung nach der Rückkehr aus dem Browser eingelöst wird, ruhen die Knöpfe.
  const shown: RoundUi = state.signingIn ? { ...ui, busy: true } : ui;
  const link = state.link;
  let body;
  if (link !== null) {
    body = <Linked state={state} link={link} ui={shown} model={model} controller={controller} players={players} />;
  } else if (!state.loaded) {
    // Die Anmeldung wird noch gelesen (oder die Anbindung an den Server lässt sich gerade nicht laden).
    body = state.problem === "OFFLINE" ? <p>{roundErrorText("OFFLINE")}</p> : null;
  } else if (state.account === null) {
    body = <SignedOut invited={state.pendingInvite !== null} busy={shown.busy} onSignIn={() => void model.signIn()} />;
  } else {
    body = <SignedIn state={state} ui={shown} model={model} />;
  }

  return (
    <>
      <main class="content round">
        {state.message !== null ? <MessageCard text={messageText(state.message)} onDismiss={() => controller.clearMessage()} /> : null}
        {ui.error !== null ? <MessageCard text={errorText(ui.error)} onDismiss={() => model.clearError()} isError /> : null}
        {body}
        {shown.busy || (!state.loaded && link === null && state.problem !== "OFFLINE") ? (
          <p class="hint" role="status">
            Einen Moment …
          </p>
        ) : null}
      </main>
      {ui.replace !== null ? (
        <ReplaceDialog
          eveningCount={ui.eveningCount}
          model={model}
          onBackup={() => {
            model.cancelReplace();
            router.open({ name: "settings" });
          }}
        />
      ) : null}
      {ui.farewell !== null ? <FarewellDialog kind={ui.farewell} pending={ui.farewellPending} link={link} model={model} /> : null}
      {ui.leaving !== null ? <LeaveRoundDialog round={ui.leaving} model={model} /> : null}
    </>
  );
}

export function RoundScreen() {
  const { round, router } = useServices();
  if (round === null) return <RoundDisabledScreen />;
  return (
    <div class="screen">
      <TopBar title="Gemeinsame Runde" onBack={() => router.back()} />
      <Configured controller={round} />
    </div>
  );
}
