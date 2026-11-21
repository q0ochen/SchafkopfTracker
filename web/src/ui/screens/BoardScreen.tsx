/*
 * Die Liste eines Abends: oben der Stand je Spieler, darunter jeder Eintrag mit den Beträgen, unten die großen
 * Knöpfe zum Eintragen (wie `ui/board/BoardScreen.kt` der Android-App).
 */
import { useEffect, useMemo, useState } from "preact/hooks";
import { isRunning } from "../../core/evening/Evening";
import { EveningCalculator, type EntryResult, type EveningState } from "../../core/evening/EveningCalculator";
import type { PlayerId } from "../../core/model";
import { DuplicateCheck } from "../../core/round/DuplicateCheck";
import { dealerHint } from "../../round/texts";
import { addBockTrigger, addThrownIn, removeLast, restore, type RemovedEntry } from "../board";
import { Amount, Dialog, MoreMenu, PlayerName, Snackbar, TopBar } from "../components";
import { useAppState, useColors, useNames, useRoundState, useServices } from "../context";
import { entryText } from "../entryText";
import { formatAmount, formatDate, formatShortDate, plural } from "../format";
import { RoundStatusButton } from "../round";

/** So lange bleibt „Wiederherstellen“ sichtbar (wie die lange Meldung der Android-App). */
const UNDO_SECONDS = 10;

function PlayerHeader({
  computed,
  names,
  colors,
}: {
  computed: EveningState;
  names: ReadonlyMap<string, string>;
  colors: ReadonlyMap<string, number>;
}) {
  const evening = computed.evening;
  const asMoney = evening.rules.displayAsMoney;
  const running = isRunning(evening);
  const parts: string[] = [];
  if (running) {
    parts.push(`Spiel ${computed.gamesPlayed + 1}`);
    parts.push(`Geber: ${names.get(computed.nextDealer) ?? "?"}`);
    if (computed.bockGamesRemaining > 0) {
      parts.push(`Bock: noch ${plural(computed.bockGamesRemaining, "Spiel", "Spiele")}`);
    }
  } else {
    parts.push("Dieser Abend ist beendet.");
    parts.push(plural(computed.gamesPlayed, "Spiel", "Spiele"));
  }
  return (
    <section class="board-header" aria-label="Stand">
      <div class="board-header-inner">
        <ul class="board-players">
          {evening.seating.map((player) => {
            const name = names.get(player) ?? "?";
            const balance = computed.balances.get(player) ?? 0;
            const rank = computed.ranks.get(player) ?? 0;
            const isDealer = running && player === computed.nextDealer;
            const description = `${name}: Stand ${formatAmount(balance, asMoney, true)}, Platz ${rank}${isDealer ? ", Geber" : ""}`;
            return (
              <li key={player} class={isDealer ? "board-player dealer" : "board-player"} aria-label={description}>
                <PlayerName name={name} colorIndex={colors.get(player) ?? 0} />
                <Amount cents={balance} asMoney={asMoney} />
                <span class="board-player-rank" aria-hidden="true">
                  {rank}.{isDealer ? " · Geber" : ""}
                </span>
              </li>
            );
          })}
        </ul>
        <p class="board-status">{parts.join(" · ")}</p>
      </div>
    </section>
  );
}

function EntryRow({
  result,
  seating,
  names,
  asMoney,
  duplicate,
  onOpen,
}: {
  result: EntryResult;
  seating: readonly PlayerId[];
  names: ReadonlyMap<string, string>;
  asMoney: boolean;
  /** Dasselbe Spiel steht direkt davor schon einmal: vermutlich haben zwei gleichzeitig eingetragen. */
  duplicate: boolean;
  onOpen: (() => void) | null;
}) {
  const text = entryText(result, names, asMoney);
  const showAmounts = [...result.amounts.values()].some((it) => it !== 0);
  const body = (
    <>
      <span class="card-line entry-title">{text.title}</span>
      {text.detail !== "" ? <span class="card-line entry-detail">{text.detail}</span> : null}
      {duplicate ? <span class="card-line entry-issue entry-duplicate">Doppelt eingetragen?</span> : null}
      {result.issues.length > 0 ? <span class="card-line entry-issue">Bitte prüfen: Dieser Eintrag passt nicht zu den Regeln.</span> : null}
      {showAmounts ? (
        <span class="entry-amounts">
          {seating.map((player) => (
            <Amount
              key={player}
              cents={result.amounts.get(player) ?? 0}
              asMoney={asMoney}
              class="entry-amount"
            />
          ))}
        </span>
      ) : null}
    </>
  );
  return (
    <li class="entry">
      {onOpen ? (
        <button type="button" class="entry-button" onClick={onOpen}>
          {body}
        </button>
      ) : (
        <div class="entry-static">{body}</div>
      )}
    </li>
  );
}

/** Fragt, wer die 6 Nixer hatte und ob das Blatt zusammengeworfen wurde. */
function BockDialog({
  seating,
  names,
  onCancel,
  onConfirm,
}: {
  seating: readonly PlayerId[];
  names: ReadonlyMap<string, string>;
  onCancel: () => void;
  onConfirm: (player: PlayerId | null, handThrownIn: boolean) => void;
}) {
  const [player, setPlayer] = useState<PlayerId | null>(null);
  const [thrownIn, setThrownIn] = useState(true);
  const candidates: Array<PlayerId | null> = [...seating, null];
  return (
    <Dialog
      title="Bockrunde auslösen"
      onClose={onCancel}
      actions={
        <>
          <button type="button" class="text-button" onClick={onCancel}>
            Abbrechen
          </button>
          <button type="button" class="button primary" onClick={() => onConfirm(player, thrownIn)}>
            Bockrunde starten
          </button>
        </>
      }
    >
      <fieldset class="plain-fieldset">
        <legend class="section-heading">Wer hatte die 6 Nixer?</legend>
        {candidates.map((candidate) => (
          <label class="radio-row" key={candidate ?? "none"}>
            <input type="radio" name="nixer" checked={player === candidate} onChange={() => setPlayer(candidate)} />
            <span>{candidate === null ? "Ohne Angabe" : (names.get(candidate) ?? "?")}</span>
          </label>
        ))}
      </fieldset>
      <label class="check-row">
        <input type="checkbox" checked={thrownIn} onChange={(event) => setThrownIn((event.currentTarget as HTMLInputElement).checked)} />
        <span>Blatt wurde zusammengeworfen</span>
      </label>
    </Dialog>
  );
}

export function BoardScreen({ eveningId }: { eveningId: string }) {
  const { store, router, platform } = useServices();
  const { evenings, settings } = useAppState();
  const names = useNames();
  const colors = useColors();
  const round = useRoundState();
  const linked = round.link !== null;
  const evening = evenings.find((it) => it.id === eveningId);
  const computed = useMemo(() => (evening ? EveningCalculator.compute(evening) : null), [evening]);
  // Nur in der gemeinsamen Runde tragen mehrere ein; nur dort lohnt der Hinweis auf Dubletten.
  const duplicates = useMemo(() => (linked && evening ? DuplicateCheck.suspects(evening) : new Set<string>()), [linked, evening]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [bockDialog, setBockDialog] = useState(false);
  // Zurückgenommene Einträge, der zuletzt zurückgenommene am Ende. Jeder lässt sich wiederherstellen.
  const [removed, setRemoved] = useState<readonly RemovedEntry[]>([]);
  const running = evening ? isRunning(evening) : false;
  const resultCount = computed?.results.length ?? 0;

  // Bildschirm anlassen, solange der Abend läuft (Einstellung „Bildschirm anlassen“).
  useEffect(() => {
    if (!running || !settings.keepScreenOn) return;
    return platform.keepScreenOn();
  }, [running, settings.keepScreenOn, platform]);

  // Beim Öffnen und nach jedem neuen Eintrag ans Ende der Liste springen (wie in der Android-App).
  useEffect(() => {
    window.scrollTo({ top: document.documentElement.scrollHeight });
  }, [resultCount]);

  // „Wiederherstellen“ wird einige Sekunden nach dem letzten Antippen ausgeblendet.
  useEffect(() => {
    if (removed.length === 0) return;
    const timer = setTimeout(() => setRemoved([]), UNDO_SECONDS * 1000);
    return () => clearTimeout(timer);
  }, [removed]);

  if (!evening || !computed) {
    return (
      <div class="screen">
        <TopBar title="Abend" onBack={() => router.back()} />
        <main class="content">
          <p class="empty">Diesen Abend gibt es nicht mehr.</p>
        </main>
      </div>
    );
  }

  const asMoney = evening.rules.displayAsMoney;
  const update = (transform: Parameters<typeof store.updateEvening>[1]) => void store.updateEvening(eveningId, transform);

  // Über den aktuellen Stand im Zustand, nicht über den zuletzt gezeichneten: Dazwischen kann der Abgleich der
  // gemeinsamen Runde etwas eingefügt haben.
  const undo = () => {
    let taken: RemovedEntry | null = null;
    void store.updateEvening(eveningId, (current) => {
      const result = removeLast(current);
      if (!result) return null;
      taken = result.removed;
      return result.evening;
    });
    const removedNow: RemovedEntry | null = taken;
    if (removedNow !== null) setRemoved((before) => [...before, removedNow]);
  };

  // Der zuletzt zurückgenommene Eintrag kommt zuerst wieder an seine Stelle; so stimmt die Reihenfolge.
  const restoreLast = () => {
    const entry = removed[removed.length - 1];
    if (!entry) return;
    setRemoved(removed.slice(0, -1));
    update((it) => restore(it, entry));
  };

  const menuItems = [
    { label: "Statistik dieses Abends", onSelect: () => router.open({ name: "statistics", eveningId }) },
    { label: "Abend teilen", onSelect: () => router.open({ name: "closing", eveningId }) },
    { label: "Abend bearbeiten", onSelect: () => router.open({ name: "editEvening", eveningId }) },
    { label: "Regeln dieses Abends", onSelect: () => router.open({ name: "rules", eveningId }) },
    running
      ? {
          label: "Abend beenden",
          onSelect: () => {
            update((it) => ({ ...it, finishedAt: store.clock.now() }));
            router.open({ name: "closing", eveningId });
          },
        }
      : { label: "Abend wieder öffnen", onSelect: () => update((it) => ({ ...it, finishedAt: null })) },
    { label: "Abend löschen", danger: true, onSelect: () => setConfirmDelete(true) },
  ];

  return (
    <div class="screen">
      <TopBar
        // Mit dem Wolken-Symbol der gemeinsamen Runde ist neben dem Titel weniger Platz: dann das Datum in Kurzform.
        title={`Abend vom ${linked ? formatShortDate(evening.startedAt) : formatDate(evening.startedAt)}`}
        onBack={() => router.back()}
        actions={
          <>
            <RoundStatusButton />
            <MoreMenu items={menuItems} />
          </>
        }
      />
      <PlayerHeader computed={computed} names={names} colors={colors} />
      <main class={running ? (linked ? "content with-bottom-bar with-dealer-hint" : "content with-bottom-bar") : "content"}>
        {computed.results.length === 0 ? (
          <p class="empty">Noch nichts eingetragen.</p>
        ) : (
          <ol class="entries" aria-label="Einträge">
            {computed.results.map((result) => (
              <EntryRow
                key={result.entry.id}
                result={result}
                seating={evening.seating}
                names={names}
                asMoney={asMoney}
                duplicate={duplicates.has(result.entry.id)}
                onOpen={
                  result.entry.kind === "game"
                    ? () => router.open({ name: "gameForm", eveningId, entryId: result.entry.id })
                    : null
                }
              />
            ))}
          </ol>
        )}
      </main>

      {running ? (
        <div class="bottom-bar">
          <div class="bottom-bar-inner">
            {linked ? (
              // In der gemeinsamen Runde: wer gibt und deshalb in der Regel einträgt. Nur ein Hinweis; eintragen kann jeder.
              <p class={round.link?.playerId === computed.nextDealer ? "dealer-hint mine" : "dealer-hint"}>
                {dealerHint(round.link?.playerId === computed.nextDealer, names.get(computed.nextDealer) ?? "?")}
              </p>
            ) : null}
            <div class="row">
              <button type="button" class="button" onClick={() => update((it) => addThrownIn(it, store.clock))}>
                Zusammengeworfen
              </button>
              {evening.rules.bock.enabled ? (
                <button type="button" class="button" onClick={() => setBockDialog(true)}>
                  6 Nixer – Bock
                </button>
              ) : null}
            </div>
            <div class="row">
              <button
                type="button"
                class="button big no-grow"
                aria-label="Letzten Eintrag zurücknehmen"
                disabled={evening.entries.length === 0}
                onClick={undo}
              >
                Rückgängig
              </button>
              <button
                type="button"
                class="button primary big"
                onClick={() => router.open({ name: "gameForm", eveningId, entryId: null })}
              >
                Spiel eintragen
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {removed.length > 0 ? (
        <Snackbar
          message={removed.length === 1 ? "Eintrag entfernt" : `${removed.length} Einträge entfernt`}
          actionLabel="Wiederherstellen"
          onAction={restoreLast}
        />
      ) : null}

      {confirmDelete ? (
        <Dialog
          title="Abend löschen?"
          onClose={() => setConfirmDelete(false)}
          actions={
            <>
              <button type="button" class="text-button" onClick={() => setConfirmDelete(false)}>
                Behalten
              </button>
              <button
                type="button"
                class="button danger"
                onClick={() => {
                  setConfirmDelete(false);
                  void store.deleteEvening(eveningId);
                  router.open({ name: "start" }, { replace: true });
                }}
              >
                Abend löschen
              </button>
            </>
          }
        >
          <p>Alle Einträge dieses Abends werden gelöscht. Das lässt sich nicht rückgängig machen.</p>
          {/* Mit gemeinsamer Runde verschwindet der Abend bei allen: Das soll vorher dastehen. */}
          {linked ? <p class="error-text">Der Abend wird für alle in der Runde gelöscht.</p> : null}
        </Dialog>
      ) : null}

      {bockDialog ? (
        <BockDialog
          seating={evening.seating}
          names={names}
          onCancel={() => setBockDialog(false)}
          onConfirm={(player, handThrownIn) => {
            setBockDialog(false);
            update((it) => addBockTrigger(it, player, handThrownIn, store.clock));
          }}
        />
      ) : null}
    </div>
  );
}
