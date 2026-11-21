/*
 * „Neuer Abend“: vier Spieler wählen, Sitzordnung und ersten Geber festlegen, Datum wählen (auch einen späteren Tag:
 * dann ist der Abend geplant, docs/ARCHITEKTUR.md 4.2), Ort eintragen (mit Ortsvorschlägen, 4.3), starten.
 */
import { useMemo, useState } from "preact/hooks";
import { MAX_NAME_LENGTH } from "../../app/players";
import { ChoiceTile, Hint, SeatingList, SectionHeading, TopBar } from "../components";
import { useAppState, useServices } from "../context";
import { localDateOf } from "../localDate";
import { LocationField } from "../LocationField";
import {
  createEvening,
  EMPTY_SELECTION,
  moveSeat,
  newEveningStart,
  newEveningView,
  seatNewPlayer,
  setDate,
  setFirstDealer,
  setLocation,
  toggleSeat,
  adoptLastSeating,
  type NewEveningSelection,
} from "../newEvening";

export function NewEveningScreen() {
  const { store, router } = useServices();
  const { players, evenings, settings } = useAppState();
  const [selection, setSelection] = useState<NewEveningSelection>(EMPTY_SELECTION);
  const [newName, setNewName] = useState("");
  const [nameError, setNameError] = useState(false);
  const today = localDateOf(store.clock.now());
  const view = useMemo(() => newEveningView(players, evenings, selection, today), [players, evenings, selection, today]);
  const name = (id: string) => players.find((it) => it.id === id)?.name ?? "?";

  const addPlayer = (event: Event) => {
    event.preventDefault();
    const player = store.addPlayer(newName);
    if (!player) {
      setNameError(true);
      return;
    }
    setNewName("");
    setNameError(false);
    setSelection((current) => seatNewPlayer(view, current, player.id));
  };

  const start = () => {
    const evening = createEvening(view, settings.defaultRules, store.clock.newId(), newEveningStart(selection, store.clock.now()));
    if (!evening) return;
    void store.saveEvening(evening);
    router.open({ name: "board", eveningId: evening.id }, { replace: true });
  };

  return (
    <div class="screen">
      <TopBar title="Neuer Abend" onBack={() => router.back()} />
      <main class="content with-bottom-bar">
        {view.canUseLast ? (
          <button type="button" class="button full-width spaced" onClick={() => setSelection((current) => adoptLastSeating(view, current))}>
            Wie letztes Mal
          </button>
        ) : null}

        <SectionHeading>Wer spielt mit? Vier auswählen</SectionHeading>
        <div class="choice-grid-2" role="group" aria-label="Spieler auswählen">
          {view.players.map((player) => (
            <ChoiceTile
              key={player.id}
              text={player.name}
              selected={view.seating.includes(player.id)}
              onClick={() => setSelection((current) => toggleSeat(view, current, player.id))}
            />
          ))}
        </div>

        <form class="text-field" onSubmit={addPlayer}>
          <label for="new-player-name">Neuer Spieler</label>
          <div class="row">
            <input
              id="new-player-name"
              type="text"
              autoComplete="off"
              autoCapitalize="words"
              enterKeyHint="done"
              maxLength={MAX_NAME_LENGTH + 10}
              value={newName}
              aria-invalid={nameError}
              aria-describedby={nameError ? "new-player-error" : undefined}
              onInput={(event) => {
                setNewName((event.currentTarget as HTMLInputElement).value);
                setNameError(false);
              }}
            />
            <button type="submit" class="button no-grow" disabled={newName.trim() === ""}>
              Hinzufügen
            </button>
          </div>
          {nameError ? (
            <p id="new-player-error" class="hint error-text" role="alert">
              Bitte einen Namen mit höchstens 24 Zeichen eingeben.
            </p>
          ) : null}
        </form>

        <SectionHeading>Sitzordnung im Uhrzeigersinn</SectionHeading>
        {view.seating.length === 0 ? (
          <Hint>Erst vier Spieler auswählen.</Hint>
        ) : (
          <>
            <Hint>In dieser Reihenfolge wechselt der Geber. Antippen legt fest, wer zuerst gibt.</Hint>
            <SeatingList
              seating={view.seating}
              firstDealer={view.firstDealer}
              name={name}
              onFirstDealer={(id) => setSelection((current) => setFirstDealer(current, id))}
              onMove={(id, delta) => setSelection((current) => moveSeat(view, current, id, delta))}
            />
          </>
        )}

        <div class="text-field">
          <h2 class="section-heading">
            <label for="evening-date">Datum</label>
          </h2>
          <input
            id="evening-date"
            type="date"
            value={view.date}
            aria-describedby={view.planned ? "evening-planned" : undefined}
            onInput={(event) => {
              const input = event.currentTarget as HTMLInputElement;
              const next = setDate(selection, input.value);
              // Ein geleertes Feld oder etwas, das kein Datum ist, zählt nicht: Das Feld zeigt wieder den gewählten Tag.
              const shown = next.date ?? today;
              if (input.value !== shown) input.value = shown;
              setSelection(next);
            }}
          />
        </div>
        {/* Immer da, damit Screenreader den Hinweis vorlesen, sobald ein späterer Tag gewählt ist. */}
        <div aria-live="polite">
          {view.planned ? (
            <p id="evening-planned" class="hint">
              Der Abend ist geplant. Bis zu diesem Tag steht er unter „Abende“ als „geplant“.
            </p>
          ) : null}
        </div>

        <LocationField id="location" value={view.location} onChange={(value) => setSelection((current) => setLocation(current, value))} />
        <Hint>Regeln: {settings.defaultRules.name}</Hint>
      </main>
      <div class="bottom-bar">
        <div class="bottom-bar-inner">
          <button type="button" class="button primary big" disabled={!view.canStart} onClick={start}>
            {view.missing > 0 ? `Noch ${view.missing} auswählen` : view.planned ? "Abend anlegen" : "Abend starten"}
          </button>
        </div>
      </div>
    </div>
  );
}
