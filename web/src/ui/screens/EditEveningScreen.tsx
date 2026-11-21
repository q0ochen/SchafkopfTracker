/*
 * „Abend bearbeiten“: Datum (jeder Tag, auch einer nach heute), Ort (mit Ortsvorschlägen), Sitzordnung und ersten
 * Geber eines Abends nachträglich ändern (docs/ARCHITEKTUR.md, Abschnitt 4.1). Gespeichert wird erst mit „Speichern“;
 * zurück ohne Speichern verwirft die Änderungen.
 */
import { useRef, useState } from "preact/hooks";
import { EveningEdit } from "../../core/evening/EveningEdit";
import { Hint, SeatingList, SectionHeading, TopBar } from "../components";
import { useAppState, useNames, useRoundState, useServices } from "../context";
import {
  changed,
  editedDetails,
  moveSeat,
  setDate,
  setFirstDealer,
  setLocation,
  startEditing,
  type EditEveningDraft,
  type EditEveningStart,
} from "../editEvening";
import { LocationField } from "../LocationField";

export function EditEveningScreen({ eveningId }: { eveningId: string }) {
  const { store, router } = useServices();
  const { evenings } = useAppState();
  const names = useNames();
  const round = useRoundState();
  // Beim Öffnen einmal gelesen: Gegen diesen Stand zählt, was geändert ist (`original` für EveningEdit.apply).
  const [start] = useState<EditEveningStart | null>(() => {
    const opened = evenings.find((it) => it.id === eveningId);
    return opened ? startEditing(opened) : null;
  });
  const [draft, setDraft] = useState<EditEveningDraft | null>(start?.draft ?? null);
  // Schutz gegen doppeltes Antippen: Gespeichert wird nur einmal.
  const done = useRef(false);

  // Auch wenn ein anderes Handy der gemeinsamen Runde den Abend inzwischen gelöscht hat.
  if (start === null || draft === null || !evenings.some((it) => it.id === eveningId)) {
    return (
      <div class="screen">
        <TopBar title="Abend bearbeiten" onBack={() => router.back()} />
        <main class="content">
          <p class="empty">Diesen Abend gibt es nicht mehr.</p>
        </main>
      </div>
    );
  }

  const { original } = start;
  const canSave = changed(original, draft);
  const name = (id: string) => names.get(id) ?? "?";
  const change = (transform: (current: EditEveningDraft) => EditEveningDraft) =>
    setDraft((current) => (current === null ? current : transform(current)));

  const save = () => {
    if (done.current || !canSave) return;
    done.current = true;
    const edited = editedDetails(original, draft);
    void store.updateEvening(eveningId, (current) => EveningEdit.apply(current, original, edited));
    router.back();
  };

  return (
    <div class="screen">
      <TopBar title="Abend bearbeiten" onBack={() => router.back()} />
      <main class="content with-bottom-bar">
        <div class="text-field">
          <h2 class="section-heading">
            <label for="evening-date">Datum</label>
          </h2>
          <input
            id="evening-date"
            type="date"
            value={draft.date}
            onInput={(event) => {
              const input = event.currentTarget as HTMLInputElement;
              const next = setDate(draft, input.value);
              // Ein geleertes Feld oder etwas, das kein Datum ist, zählt nicht: Das Feld zeigt wieder den gewählten Tag.
              if (input.value !== next.date) input.value = next.date;
              setDraft(next);
            }}
          />
        </div>

        <LocationField id="evening-location" value={draft.location} onChange={(value) => change((current) => setLocation(current, value))} />

        <SectionHeading>Sitzordnung im Uhrzeigersinn</SectionHeading>
        <Hint>In dieser Reihenfolge wechselt der Geber. Antippen legt fest, wer zuerst gibt.</Hint>
        <SeatingList
          seating={draft.seating}
          firstDealer={draft.firstDealer}
          name={name}
          onFirstDealer={(id) => change((current) => setFirstDealer(current, id))}
          onMove={(id, delta) => change((current) => moveSeat(current, id, delta))}
        />

        <Hint>Eingetragene Spiele und Beträge bleiben unverändert. Wer gibt, ergibt sich aus Sitzordnung und erstem Geber.</Hint>
        {/* Mit gemeinsamer Runde ändert sich der Abend bei allen: Das soll vorher dastehen. */}
        {round.link !== null ? <Hint>Die Änderungen gelten für alle in der gemeinsamen Runde.</Hint> : null}
      </main>
      <div class="bottom-bar">
        <div class="bottom-bar-inner">
          <button type="button" class="button primary big" disabled={!canSave} onClick={save}>
            Speichern
          </button>
        </div>
      </div>
    </div>
  );
}
