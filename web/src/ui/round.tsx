/*
 * Bausteine der gemeinsamen Runde, die mehrere Bildschirme brauchen (docs/ARCHITEKTUR.md, Abschnitt 8.9): die
 * Statuszeile, die Einstiegszeile auf dem Start-Bildschirm und in den Einstellungen und das Wolken-Symbol in der
 * Kopfzeile eines Abends. Der Text sagt immer alles; Symbol und Farbe sind nur Beiwerk.
 */
import { needsSignIn, type RoundState } from "../round/controller";
import { roundStatus, statusIconLabel, type RoundStatus, type RoundStatusKind } from "../round/texts";
import { Icon, type IconName } from "./components";
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

/** Der Weg zur gemeinsamen Runde; mit verbundener Runde stehen hier ihr Name und der Stand des Abgleichs. */
export function RoundRow() {
  const { router } = useServices();
  const state = useRoundState();
  const link = state.link;
  return (
    <button type="button" class="card round-row" onClick={() => router.open({ name: "round" })}>
      {link === null ? (
        <>
          <span class="card-title">Gemeinsame Runde</span>
          <span class="card-line hint">Alle tragen vom eigenen Handy ein</span>
        </>
      ) : (
        <>
          <span class="card-title">Runde: {link.roundName}</span>
          <StatusLine state={state} />
        </>
      )}
    </button>
  );
}

/** Mit gemeinsamer Runde: der Stand des Abgleichs als Symbol in der Kopfzeile; Tippen öffnet „Gemeinsame Runde“. */
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
