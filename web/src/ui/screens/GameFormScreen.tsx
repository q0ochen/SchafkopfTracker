/*
 * Ein Spiel eintragen oder ändern (wie `ui/gameform/GameFormScreen.kt` der Android-App). Alles steht auf einem
 * Bildschirm; ein neues Spiel wird mit „Gewonnen“ oder „Verloren“ sofort gespeichert.
 */
import { useMemo, useRef, useState } from "preact/hooks";
import type { Evening, GameEntry, KontraLevel, ResultLevel, Suit } from "../../core/model";
import { GAME_TYPES, KONTRA_LEVELS, RESULT_LEVELS, SUITS } from "../../core/model";
import { allowsSie, allowsTout, hasDeclarer, isSolo } from "../../core/rules/RuleSet";
import { ChoiceRow, ChoiceTile, Dialog, Hint, Icon, SectionHeading, TopBar } from "../components";
import { useAppState, useNames, useServices } from "../context";
import { formatAmount, GAME_TYPE_LABELS, KONTRA_LABELS, LEVEL_LABELS, SUIT_LABELS } from "../format";
import {
  defaultType,
  draftOf,
  emptyDraft,
  findGame,
  formState,
  laufendeChoices,
  saveDraft,
  selectAnnouncement,
  selectKontra,
  selectKontraBy,
  selectLaufende,
  selectLevel,
  selectResult,
  selectSuit,
  selectType,
  setBock,
  tapPlayer,
  toggleKlopfer,
  type GameDraft,
  type GameFormState,
} from "../gameForm";

/** Bis zu so vielen Spielarten stehen in einer Zeile; sind es mehr, stehen drei je Zeile. */
const MAX_TYPES_IN_ONE_ROW = 4;
const TYPES_PER_ROW = 3;

function FormContent({
  evening,
  state,
  names,
  change,
}: {
  evening: Evening;
  state: GameFormState;
  names: ReadonlyMap<string, string>;
  change: (transform: (draft: GameDraft) => GameDraft) => void;
}) {
  const draft = state.draft;
  const rules = evening.rules;
  const asMoney = rules.displayAsMoney;
  const name = (id: string) => names.get(id) ?? "?";
  const opponents = evening.seating.filter((it) => it !== draft.declarer && it !== draft.partner);
  const types = GAME_TYPES.filter((type) => rules.enabledTypes.includes(type) || type === draft.type);
  const kontraLevels = KONTRA_LEVELS.filter((level) => level !== "RETOUR" || rules.retourEnabled || draft.kontra === level);
  const suits: readonly Suit[] = draft.type === "RUFSPIEL" ? ["EICHEL", "GRAS", "SCHELLEN"] : SUITS;
  const breakdown = state.breakdown;

  const role = (player: string): string | null => {
    if (!hasDeclarer(draft.type) && (player === draft.declarer || player === draft.partner)) return "Paar";
    if (player === draft.declarer) return "Spieler";
    if (player === draft.partner) return "Mitspieler";
    return null;
  };

  return (
    <>
      <SectionHeading>Spielart</SectionHeading>
      <ChoiceRow
        groupLabel="Spielart"
        options={types}
        selected={draft.type}
        label={(type) => GAME_TYPE_LABELS[type]}
        onSelect={(type) => change((d) => selectType(d, evening, type))}
        columns={types.length > MAX_TYPES_IN_ONE_ROW ? TYPES_PER_ROW : types.length}
        tall
      />

      <SectionHeading>Wer spielt?</SectionHeading>
      <div class="choice-grid-2" role="group" aria-label="Wer spielt?">
        {evening.seating.map((player) => (
          <ChoiceTile
            key={player}
            text={name(player)}
            supporting={role(player)}
            selected={player === draft.declarer || player === draft.partner}
            onClick={() => change((d) => tapPlayer(d, evening, player))}
            tall
          />
        ))}
      </div>
      {!isSolo(draft.type) && draft.partner === null ? (
        <Hint>
          {hasDeclarer(draft.type)
            ? "Erst den Spieler antippen, dann den Mitspieler."
            : "Einen aus dem Paar antippen – sein Gegenüber gehört dazu. Dann „Gewonnen“ oder „Verloren“."}
        </Hint>
      ) : null}

      {rules.klopfenEnabled || draft.klopfer.length > 0 ? (
        <>
          <div class="heading-with-icon">
            <Icon name="matchbox" label="Streichholzschachtel: gelegt" />
            <SectionHeading>Gelegt</SectionHeading>
          </div>
          <div class="choice-grid-2" role="group" aria-label="Gelegt">
            {evening.seating.map((player) => (
              <ChoiceTile
                key={player}
                text={name(player)}
                selected={draft.klopfer.includes(player)}
                onClick={() => change((d) => toggleKlopfer(d, player))}
              />
            ))}
          </div>
        </>
      ) : null}

      {rules.kontraEnabled || draft.kontra !== "NONE" ? (
        <>
          <SectionHeading>Kontra</SectionHeading>
          <ChoiceRow<KontraLevel>
            groupLabel="Kontra"
            options={kontraLevels}
            selected={draft.kontra}
            label={(level) => KONTRA_LABELS[level]}
            onSelect={(level) => change((d) => selectKontra(d, level))}
          />
          {draft.kontra !== "NONE" && draft.declarer !== null ? (
            <>
              <Hint>Kontra von (freiwillig)</Hint>
              <ChoiceRow
                groupLabel="Kontra von"
                options={opponents}
                selected={draft.kontraBy}
                label={name}
                onSelect={(player) => change((d) => selectKontraBy(d, player))}
              />
            </>
          ) : null}
        </>
      ) : null}

      <SectionHeading>Laufende</SectionHeading>
      <div class="choice-scroll" role="group" aria-label="Laufende">
        {laufendeChoices(evening, draft.type).map((count) => (
          <ChoiceTile
            key={count}
            text={count === 0 ? "keine" : String(count)}
            label={count === 0 ? "keine Laufenden" : `${count} Laufende`}
            selected={draft.laufende === count}
            onClick={() => change((d) => selectLaufende(d, count))}
          />
        ))}
      </div>

      {isSolo(draft.type) && (allowsTout(rules, draft.type) || allowsSie(rules, draft.type)) ? (
        <>
          <SectionHeading>Ansage</SectionHeading>
          <div class="row" role="group" aria-label="Ansage">
            {allowsTout(rules, draft.type) ? (
              <ChoiceTile
                text="Tout"
                selected={draft.announcement === "TOUT"}
                onClick={() => change((d) => selectAnnouncement(d, "TOUT"))}
              />
            ) : null}
            {allowsSie(rules, draft.type) ? (
              <ChoiceTile text="Sie" selected={draft.announcement === "SIE"} onClick={() => change((d) => selectAnnouncement(d, "SIE"))} />
            ) : null}
          </div>
        </>
      ) : null}

      {draft.announcement === "NONE" ? (
        <>
          <SectionHeading>Ausgang</SectionHeading>
          <ChoiceRow<ResultLevel>
            groupLabel="Ausgang"
            options={RESULT_LEVELS}
            selected={draft.level}
            label={(level) => LEVEL_LABELS[level]}
            onSelect={(level) => change((d) => selectLevel(d, level))}
          />
        </>
      ) : null}

      {rules.bock.enabled || state.bockExponent > 0 ? (
        <div class="spaced-top">
          <ChoiceTile
            text="Bock (zählt doppelt)"
            supporting={state.autoBockExponent > 0 && draft.bockOverride === "AUTO" ? "Vorbelegt, weil eine Bockrunde läuft." : null}
            selected={state.bockExponent > 0}
            onClick={() => change((d) => setBock(d, state.bockExponent === 0, state.autoBockExponent))}
            tall
          />
        </div>
      ) : null}

      {draft.type === "RUFSPIEL" || draft.type === "FARBSOLO" ? (
        <>
          <SectionHeading>{draft.type === "RUFSPIEL" ? "Rufsau (freiwillig)" : "Trumpffarbe (freiwillig)"}</SectionHeading>
          <ChoiceRow<Suit>
            groupLabel={draft.type === "RUFSPIEL" ? "Rufsau" : "Trumpffarbe"}
            options={suits}
            selected={draft.suit}
            label={(suit) => SUIT_LABELS[suit]}
            onSelect={(suit) => change((d) => selectSuit(d, suit))}
          />
        </>
      ) : null}

      {breakdown ? (
        <div class="value-preview" aria-live="polite">
          <span>Spielwert</span>
          <strong>
            {breakdown.totalFactor > 1
              ? `${formatAmount(breakdown.sum, asMoney)} × ${breakdown.totalFactor} = ${formatAmount(breakdown.value, asMoney)}`
              : formatAmount(breakdown.value, asMoney)}
          </strong>
        </div>
      ) : null}
    </>
  );
}

/** Unterer Rand: „Verloren“ und „Gewonnen“; beim Ändern zusätzlich „Änderung speichern“. */
function SaveBar({
  state,
  asMoney,
  onResult,
  onSaveChanges,
}: {
  state: GameFormState;
  asMoney: boolean;
  onResult: (won: boolean) => void;
  onSaveChanges: () => void;
}) {
  const draft = state.draft;
  let hint: string | null = null;
  if (state.missing === "DECLARER") hint = hasDeclarer(draft.type) ? "Bitte wählen, wer spielt." : "Bitte das Paar wählen.";
  else if (state.missing === "PARTNER") hint = "Bitte den Mitspieler wählen.";
  const lostHighlighted = !state.editing || !draft.declarerWon;
  const wonHighlighted = !state.editing || draft.declarerWon;
  return (
    <div class="bottom-bar">
      <div class="bottom-bar-inner">
        {hint ? (
          <p class="bottom-hint" aria-live="polite">
            {hint}
          </p>
        ) : null}
        <div class="row">
          <button
            type="button"
            class={`result-button lost${lostHighlighted ? " highlighted" : ""}`}
            disabled={!state.canSave || draft.announcement === "SIE"}
            aria-pressed={state.editing ? !draft.declarerWon : undefined}
            onClick={() => onResult(false)}
          >
            <span class="result-label">Verloren</span>
            <span class="result-amount">{formatAmount(-state.declarerAmount, asMoney, true)}</span>
          </button>
          <button
            type="button"
            class={`result-button won${wonHighlighted ? " highlighted" : ""}`}
            disabled={!state.canSave}
            aria-pressed={state.editing ? draft.declarerWon : undefined}
            onClick={() => onResult(true)}
          >
            <span class="result-label">Gewonnen</span>
            <span class="result-amount">{formatAmount(state.declarerAmount, asMoney, true)}</span>
          </button>
        </div>
        {state.editing ? (
          <button type="button" class="button primary main" disabled={!state.canSave} onClick={onSaveChanges}>
            Änderung speichern
          </button>
        ) : null}
      </div>
    </div>
  );
}

function initialDraft(evening: Evening, existing: GameEntry | null): GameDraft {
  return existing ? draftOf(existing) : emptyDraft(defaultType(evening));
}

export function GameFormScreen({ eveningId, entryId }: { eveningId: string; entryId: string | null }) {
  const { store, router } = useServices();
  const { evenings } = useAppState();
  const names = useNames();
  const evening = evenings.find((it) => it.id === eveningId);
  const existing = evening ? findGame(evening, entryId) : null;
  const [draft, setDraft] = useState<GameDraft | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  // Schutz gegen doppeltes Antippen: Ein Spiel wird nur einmal gespeichert.
  const done = useRef(false);
  const shown = draft ?? (evening ? initialDraft(evening, existing) : null);
  const state = useMemo(() => (evening && shown ? formState(evening, existing, shown) : null), [evening, existing, shown]);

  if (!evening || !state || !shown || (entryId !== null && !existing)) {
    return (
      <div class="screen">
        <TopBar title="Spiel" onBack={() => router.back()} />
        <main class="content">
          <p class="empty">{evening ? "Dieses Spiel gibt es nicht mehr." : "Diesen Abend gibt es nicht mehr."}</p>
        </main>
      </div>
    );
  }

  const change = (transform: (d: GameDraft) => GameDraft) => setDraft(transform(shown));
  const save = (won: boolean | null) => {
    if (done.current) return;
    done.current = true;
    void store.updateEvening(eveningId, (current) => saveDraft(current, findGame(current, entryId), shown, won, store.clock));
    router.back();
  };

  return (
    <div class="screen">
      <TopBar
        title={state.editing ? "Spiel ändern" : `Spiel ${state.gameNumber} eintragen`}
        onBack={() => router.back()}
        actions={
          state.editing ? (
            <button type="button" class="icon-button" aria-label="Spiel löschen" onClick={() => setConfirmDelete(true)}>
              <Icon name="delete" />
            </button>
          ) : null
        }
      />
      <main class="content with-bottom-bar">
        <FormContent evening={evening} state={state} names={names} change={change} />
      </main>
      <SaveBar
        state={state}
        asMoney={evening.rules.displayAsMoney}
        onResult={(won) => (state.editing ? change((d) => selectResult(d, won)) : save(won))}
        onSaveChanges={() => save(null)}
      />
      {confirmDelete ? (
        <Dialog
          title="Spiel löschen?"
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
                  if (done.current) return;
                  done.current = true;
                  void store.updateEvening(eveningId, (current) => ({
                    ...current,
                    entries: current.entries.filter((it) => it.id !== entryId),
                  }));
                  router.back();
                }}
              >
                Spiel löschen
              </button>
            </>
          }
        >
          <p>Das Spiel wird aus dem Abend entfernt; die Stände werden neu berechnet.</p>
        </Dialog>
      ) : null}
    </div>
  );
}
