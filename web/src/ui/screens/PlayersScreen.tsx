/* Spieler anlegen, umbenennen, archivieren und löschen (wie `ui/settings/PlayersScreen.kt` der Android-App). */
import { useState } from "preact/hooks";
import { eveningCount, MAX_NAME_LENGTH } from "../../app/players";
import type { Player } from "../../core/model";
import { Dialog, Icon, PlayerName, SwitchRow, TopBar } from "../components";
import { useAppState, useRoundState, useServices } from "../context";

function EditPlayerDialog({ player, onClose }: { player: Player; onClose: () => void }) {
  const { store } = useServices();
  const { evenings } = useAppState();
  const round = useRoundState();
  const [name, setName] = useState(player.name);
  const [archived, setArchived] = useState(player.archived);
  const [error, setError] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const usedIn = eveningCount(evenings, player.id);
  // Was der Server über den Spieler weiß; ohne gemeinsame Runde nichts.
  const remote = round.link !== null ? round.players.get(player.id) : undefined;
  // In der gemeinsamen Runde hat sein Besitzer den Namen selbst festgelegt; ändern darf ihn nur der Ersteller der Runde.
  const nameLocked = remote?.nameLocked === true && round.link?.isCreator !== true;
  // In der gemeinsamen Runde gehört der Spieler einem Mitglied und lässt sich deshalb nicht löschen.
  const owned = (remote?.owner ?? null) !== null;
  const save = () => {
    if (store.updatePlayer(player.id, name, archived)) onClose();
    else setError(true);
  };

  if (confirmDelete) {
    return (
      <Dialog
        title="Spieler löschen?"
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
                store.deletePlayer(player.id);
                onClose();
              }}
            >
              Löschen
            </button>
          </>
        }
      >
        <p>„{player.name}“ wird endgültig gelöscht. Das lässt sich nicht rückgängig machen.</p>
      </Dialog>
    );
  }

  return (
    <Dialog
      title="Spieler bearbeiten"
      onClose={onClose}
      actions={
        <>
          <button type="button" class="text-button" onClick={onClose}>
            Abbrechen
          </button>
          <button type="button" class="button primary" onClick={save}>
            Speichern
          </button>
        </>
      }
    >
      <form
        class="text-field"
        onSubmit={(event) => {
          event.preventDefault();
          save();
        }}
      >
        <label for="edit-player-name">Name</label>
        <input
          id="edit-player-name"
          type="text"
          autoComplete="off"
          autoCapitalize="words"
          maxLength={MAX_NAME_LENGTH + 10}
          value={name}
          // Einen selbst festgelegten Namen kann hier niemand mehr ändern.
          disabled={nameLocked}
          aria-invalid={error}
          aria-describedby={nameLocked ? "edit-player-locked" : error ? "edit-player-error" : undefined}
          onInput={(event) => {
            setName((event.currentTarget as HTMLInputElement).value);
            setError(false);
          }}
        />
        {nameLocked ? (
          <p id="edit-player-locked" class="hint">
            Name selbst festgelegt
          </p>
        ) : error ? (
          <p id="edit-player-error" class="hint error-text" role="alert">
            Bitte einen Namen mit höchstens 24 Zeichen eingeben.
          </p>
        ) : null}
      </form>
      <SwitchRow
        label="Archiviert (erscheint nicht mehr in der Auswahl)"
        hint="Alte Abende bleiben erhalten."
        checked={archived}
        onChange={setArchived}
      />
      {usedIn === 0 && !owned ? (
        <button type="button" class="text-button danger-text full-width" onClick={() => setConfirmDelete(true)}>
          Spieler löschen
        </button>
      ) : owned ? (
        <p class="hint">Dieser Spieler gehört zu einem Mitglied der Runde und lässt sich nicht löschen.</p>
      ) : (
        <p class="hint">
          {usedIn === 1
            ? "Spielt in 1 Abend mit und lässt sich deshalb nicht löschen. Archivieren blendet den Spieler aus; der Abend bleibt erhalten. Ganz entfernen lässt er sich erst, wenn dieser Abend gelöscht ist."
            : `Spielt in ${usedIn} Abenden mit und lässt sich deshalb nicht löschen. Archivieren blendet den Spieler aus; die Abende bleiben erhalten. Ganz entfernen lässt er sich erst, wenn diese Abende gelöscht sind.`}
        </p>
      )}
    </Dialog>
  );
}

export function PlayersScreen() {
  const { store, router } = useServices();
  const { players } = useAppState();
  const [newName, setNewName] = useState("");
  const [nameError, setNameError] = useState(false);
  const [editing, setEditing] = useState<Player | null>(null);

  return (
    <div class="screen">
      <TopBar title="Spieler" onBack={() => router.back()} />
      <main class="content">
        <form
          class="text-field"
          onSubmit={(event) => {
            event.preventDefault();
            if (store.addPlayer(newName)) {
              setNewName("");
              setNameError(false);
            } else {
              setNameError(true);
            }
          }}
        >
          <label for="players-new-name">Neuer Spieler</label>
          <div class="row">
            <input
              id="players-new-name"
              type="text"
              autoComplete="off"
              autoCapitalize="words"
              enterKeyHint="done"
              maxLength={MAX_NAME_LENGTH + 10}
              value={newName}
              aria-invalid={nameError}
              aria-describedby={nameError ? "players-new-error" : undefined}
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
            <p id="players-new-error" class="hint error-text" role="alert">
              Bitte einen Namen mit höchstens 24 Zeichen eingeben.
            </p>
          ) : null}
        </form>

        {players.length === 0 ? (
          <p class="hint">Noch keine Spieler angelegt.</p>
        ) : (
          <ul class="list" aria-label="Alle Spieler">
            {players.map((player) => (
              <li key={player.id} class="list-item">
                <span class="grow">
                  <PlayerName name={player.name} colorIndex={player.colorIndex} />
                  {player.archived ? <span class="badge">archiviert</span> : null}
                </span>
                <button type="button" class="icon-button" aria-label={`${player.name} bearbeiten`} onClick={() => setEditing(player)}>
                  <Icon name="edit" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </main>
      {editing ? <EditPlayerDialog player={editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}
