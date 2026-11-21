/*
 * Bausteine der gemeinsamen Runde, die mehrere Bildschirme brauchen (docs/ARCHITEKTUR.md, Abschnitt 8.9): die
 * Statuszeile, die Einstiegszeile „Gemeinsame Runden“ auf dem Start-Bildschirm (mit der Runden-Auswahl) und in den
 * Einstellungen, die „Rückfrage vor dem Wechseln“, Meldungen und das Wolken-Symbol in der Kopfzeile eines Abends. Der
 * Text sagt immer alles; Symbol und Farbe sind nur Beiwerk.
 */
import { useEffect, useId, useMemo, useState } from "preact/hooks";
import { targetName, type RoundUiError, type SwitchConfirm } from "../round/actions";
import { needsSignIn, type RoundController, type RoundState } from "../round/controller";
import { roundChoices, RoundPicker, showsPicker } from "../round/picker";
import {
  messageText,
  NO_CODE_ERROR,
  roundErrorText,
  ROUNDS_TITLE,
  roundStatus,
  STORAGE_FAILED_TEXT,
  statusIconLabel,
  switchTexts,
  type RoundStatus,
  type RoundStatusKind,
} from "../round/texts";
import { Dialog, Icon, type IconName } from "./components";
import { useRoundState, useServices } from "./context";

/** Die Statuszeile zum Stand der Runde. */
export function statusOf(state: RoundState): RoundStatus {
  return roundStatus({
    needsSignIn: needsSignIn(state),
    problem: state.problem,
    pending: state.pending,
    unreadable: state.unreadable,
    refused: state.refused,
  });
}

const STATUS_ICONS: Record<RoundStatusKind, IconName> = { SYNCED: "cloud-done", PENDING: "cloud-upload", PROBLEM: "cloud-off" };

/** Die Statuszeile mit Symbol. `live`: Screenreader lesen Änderungen von selbst vor. */
export function StatusLine({ state, live }: { state: RoundState; live?: boolean }) {
  const status = statusOf(state);
  return (
    <span class={status.kind === "PROBLEM" ? "round-status problem" : "round-status"} role={live ? "status" : undefined}>
      <Icon name={STATUS_ICONS[status.kind]} size={20} />
      <span>{status.text}</span>
    </span>
  );
}

/** Der Text zu einem Fehler einer Aktion der gemeinsamen Runde. */
export function uiErrorText(error: RoundUiError): string {
  switch (error.kind) {
    case "NoCode":
      return NO_CODE_ERROR;
    case "Storage":
      return STORAGE_FAILED_TEXT;
    case "Server":
      return roundErrorText(error.error, error.roundName);
  }
}

/** Eine Meldung, die stehen bleibt, bis man sie mit „OK“ wegtippt; Screenreader lesen sie von selbst vor. */
export function MessageCard({ text, onDismiss, isError }: { text: string; onDismiss: () => void; isError?: boolean }) {
  return (
    <div class={isError ? "message-card error" : "message-card"} role={isError ? "alert" : "status"}>
      <span>{text}</span>
      <button type="button" class="text-button" onClick={onDismiss}>
        OK
      </button>
    </div>
  );
}

/**
 * Die „Rückfrage vor dem Wechseln“ (Wechseln, Beitreten oder Anlegen mit verbundener Runde). Geht dabei etwas
 * verloren, steht das in einer eigenen Zeile, und der Knopf ist als gefährlich markiert (der Text sagt es auch).
 */
export function SwitchDialog({ confirm, onCancel, onConfirm }: { confirm: SwitchConfirm; onCancel: () => void; onConfirm: () => void }) {
  const texts = switchTexts(confirm.target.kind, targetName(confirm.target), confirm.from, confirm.pending);
  return (
    <Dialog
      title={texts.title}
      onClose={onCancel}
      actions={
        <>
          <button type="button" class="text-button" onClick={onCancel}>
            Abbrechen
          </button>
          <button type="button" class={texts.loss === null ? "button primary" : "button danger"} onClick={onConfirm}>
            {texts.confirm}
          </button>
        </>
      }
    >
      <p>{texts.lead}</p>
      <p>{texts.keeps}</p>
      {texts.loss !== null ? <p class="error-text">{texts.loss}</p> : null}
    </Dialog>
  );
}

/**
 * Was die Einstiegszeile zeigt: ohne Runde „Alle tragen vom eigenen Handy ein“, mit Runde „Runde: ‹Name›“ (außer die
 * Runden-Auswahl zeigt den Namen) und die Statuszeile.
 */
function RowSummary({ state, withName }: { state: RoundState; withName: boolean }) {
  const link = state.link;
  return (
    <>
      <span class="card-title">{ROUNDS_TITLE}</span>
      {link === null ? (
        <span class="card-line hint">Alle tragen vom eigenen Handy ein</span>
      ) : (
        <>
          {withName ? <span class="card-line round-row-name">Runde: {link.roundName}</span> : null}
          <StatusLine state={state} />
        </>
      )}
    </>
  );
}

/** Die Zeile „Gemeinsame Runden“ mit Runden-Auswahl, Rückfrage und Meldungen (Start-Bildschirm). */
function PickerRow({ controller }: { controller: RoundController }) {
  const { router } = useServices();
  const state = useRoundState();
  const picker = useMemo(() => new RoundPicker(controller), [controller]);
  const [ui, setUi] = useState(picker.getUi());
  const selectId = useId();
  useEffect(() => {
    setUi(picker.getUi());
    const unsubscribe = picker.subscribe(() => setUi(picker.getUi()));
    return () => {
      unsubscribe();
      picker.dispose();
    };
  }, [picker]);

  const link = state.link;
  const choices = link !== null && showsPicker(state) ? roundChoices(link, state.rounds ?? []) : null;
  return (
    <div class="card round-row">
      {/* Nur Überschrift und Statuszeile öffnen den Bildschirm; die Auswahl darunter ist ein eigenes Bedienelement. */}
      <button type="button" class="round-row-open" onClick={() => router.open({ name: "round" })}>
        <RowSummary state={state} withName={choices === null} />
      </button>
      {link !== null && choices !== null ? (
        <div class="select-field">
          <label for={selectId}>Runde</label>
          <div class="select-wrap">
            <select
              id={selectId}
              value={link.roundId}
              disabled={ui.busy}
              onChange={(event) => {
                const select = event.currentTarget;
                const chosen = select.value;
                // Bis zum Wechsel zeigt die Auswahl weiter die verbundene Runde.
                select.value = link.roundId;
                void picker.choose(chosen);
              }}
            >
              {choices.map((choice) => (
                <option key={choice.round.id} value={choice.round.id}>
                  {choice.label}
                </option>
              ))}
            </select>
            <Icon name="down" />
          </div>
        </div>
      ) : null}
      {ui.busy ? (
        <p class="hint" role="status">
          Einen Moment …
        </p>
      ) : null}
      {ui.error !== null ? <MessageCard text={uiErrorText(ui.error)} onDismiss={() => picker.clearError()} isError /> : null}
      {state.message !== null ? <MessageCard text={messageText(state.message)} onDismiss={() => controller.clearMessage()} /> : null}
      {ui.confirm !== null ? <SwitchDialog confirm={ui.confirm} onCancel={() => picker.cancel()} onConfirm={() => void picker.confirm()} /> : null}
    </div>
  );
}

/**
 * Der Weg zur gemeinsamen Runde; mit verbundener Runde stehen hier ihr Name und der Stand des Abgleichs.
 *
 * @param picker Auf dem Start-Bildschirm: mit der Runden-Auswahl, wenn das Konto mehrere Runden hat, und den
 *   Meldungen zum Wechseln.
 */
export function RoundRow({ picker = false }: { picker?: boolean }) {
  const { router, round } = useServices();
  const state = useRoundState();
  if (picker && round !== null) return <PickerRow controller={round} />;
  return (
    <button type="button" class="card round-row" onClick={() => router.open({ name: "round" })}>
      <RowSummary state={state} withName />
    </button>
  );
}

/** Mit gemeinsamer Runde: der Stand des Abgleichs als Symbol in der Kopfzeile; Tippen öffnet „Gemeinsame Runden“. */
export function RoundStatusButton() {
  const { router } = useServices();
  const state = useRoundState();
  if (state.link === null) return null;
  const status = statusOf(state);
  return (
    <button
      type="button"
      class={status.kind === "PROBLEM" ? "icon-button round-problem" : "icon-button"}
      aria-label={statusIconLabel(status)}
      onClick={() => router.open({ name: "round" })}
    >
      <Icon name={STATUS_ICONS[status.kind]} />
    </button>
  );
}
