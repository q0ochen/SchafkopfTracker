/* Einstellungen: Erscheinungsbild, Bildschirm anlassen, Regel-Vorlage, Sicherung und Version. */
import { useRef, useState } from "preact/hooks";
import { backupFileName, hasAnythingToImport, importPlan, importSummary, type ImportPreview } from "../../app/backup";
import type { ThemeMode } from "../../core/model";
import { ChoiceRow, Dialog, Hint, SectionHeading, SwitchRow, TopBar } from "../components";
import { useAppState, useServices } from "../context";
import { formatDateTime, THEME_LABELS } from "../format";

const THEMES: readonly ThemeMode[] = ["DARK", "LIGHT", "SYSTEM"];
/** Größere Dateien sind sicher keine Sicherung dieser App (schützt vor dem Einfrieren der Seite). */
const MAX_BACKUP_BYTES = 50 * 1024 * 1024;

function ImportDialog({ preview, onClose }: { preview: ImportPreview; onClose: (message: string | null) => void }) {
  const { store } = useServices();
  const { players, settings } = useAppState();
  const [takeNames, setTakeNames] = useState(true);
  const [takeRules, setTakeRules] = useState(false);
  const [busy, setBusy] = useState(false);
  const readable = preview.error === null;
  const nameChanges = preview.differingPlayers.map((player) => {
    const before = players.find((it) => it.id === player.id)?.name ?? "?";
    return before === player.name ? player.name : `${before} → ${player.name}`;
  });
  const rulesDiffer = preview.defaultRules !== null && JSON.stringify(preview.defaultRules) !== JSON.stringify(settings.defaultRules);

  const apply = async () => {
    setBusy(true);
    const plan = importPlan(preview, { takeNames, takeRules: takeRules && rulesDiffer });
    await store.importData(plan.players, plan.evenings);
    const rules = plan.defaultRules;
    if (rules !== null) await store.updateSettings((it) => ({ ...it, defaultRules: rules }));
    const failed = store.getState().saveError !== null;
    onClose(failed ? "Die Sicherung ließ sich nicht vollständig speichern. Ist der Speicher voll?" : "Sicherung eingelesen.");
  };

  return (
    <Dialog
      title={readable ? "Sicherung einlesen?" : "Sicherung nicht lesbar"}
      onClose={() => onClose(null)}
      actions={
        readable && hasAnythingToImport(preview) ? (
          <>
            <button type="button" class="text-button" onClick={() => onClose(null)}>
              Abbrechen
            </button>
            <button type="button" class="button primary" disabled={busy} onClick={() => void apply()}>
              Einlesen
            </button>
          </>
        ) : (
          <button type="button" class="button primary" onClick={() => onClose(null)}>
            OK
          </button>
        )
      }
    >
      {readable && preview.exportedAt !== null ? (
        <p class="hint">
          Erstellt am {formatDateTime(preview.exportedAt)}
          {preview.appVersion !== "" ? ` (Version ${preview.appVersion})` : ""}
        </p>
      ) : null}
      {importSummary(preview).map((line, index) => (
        <p key={index}>{line}</p>
      ))}
      {readable && nameChanges.length > 0 ? (
        <label class="check-row">
          <input type="checkbox" checked={takeNames} onChange={(event) => setTakeNames((event.currentTarget as HTMLInputElement).checked)} />
          <span>Spieler wie in der Sicherung benennen: {nameChanges.join(", ")}</span>
        </label>
      ) : null}
      {readable && rulesDiffer ? (
        <label class="check-row">
          <input type="checkbox" checked={takeRules} onChange={(event) => setTakeRules((event.currentTarget as HTMLInputElement).checked)} />
          <span>Regeln für neue Abende aus der Sicherung übernehmen („{preview.defaultRules?.name}“)</span>
        </label>
      ) : null}
      {readable && !hasAnythingToImport(preview) ? <p>Alles aus dieser Sicherung ist schon da.</p> : null}
    </Dialog>
  );
}

export function SettingsScreen() {
  const { store, router, platform, backup, version } = useServices();
  const { settings, players, evenings } = useAppState();
  const fileInput = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const saveBackup = async () => {
    const now = store.clock.now();
    const text = backup.write(players, evenings, settings.defaultRules, version, now);
    const file = new File([text], backupFileName(now), { type: "application/json" });
    const outcome = await platform.saveFile(file);
    if (outcome === "downloaded") setMessage("Die Sicherung wurde heruntergeladen.");
    else if (outcome === "failed") setMessage("Die Sicherung ließ sich nicht speichern.");
    else setMessage(null);
  };

  const readBackup = async (file: File) => {
    setMessage(null);
    if (file.size > MAX_BACKUP_BYTES) {
      setMessage("Diese Datei ist zu groß für eine Sicherung des Schafkopf Trackers.");
      return;
    }
    let text: string;
    try {
      text = await file.text();
    } catch {
      setMessage("Die Datei ließ sich nicht lesen.");
      return;
    }
    const current = store.getState();
    setPreview(backup.read(text, current.players, current.evenings));
  };

  return (
    <div class="screen">
      <TopBar title="Einstellungen" onBack={() => router.back()} />
      <main class="content">
        <SectionHeading>Erscheinungsbild</SectionHeading>
        <ChoiceRow<ThemeMode>
          groupLabel="Erscheinungsbild"
          options={THEMES}
          selected={settings.themeMode}
          label={(mode) => THEME_LABELS[mode]}
          onSelect={(themeMode) => void store.updateSettings((it) => ({ ...it, themeMode }))}
        />
        <SwitchRow
          label="Bildschirm anlassen"
          hint="Solange ein Abend läuft, geht der Bildschirm nicht aus (soweit der Browser das erlaubt)."
          checked={settings.keepScreenOn}
          onChange={(keepScreenOn) => void store.updateSettings((it) => ({ ...it, keepScreenOn }))}
        />
        <button type="button" class="button full-width spaced-top" onClick={() => router.open({ name: "rules", eveningId: null })}>
          Regeln für neue Abende
        </button>

        <SectionHeading>Sicherung</SectionHeading>
        <Hint>
          Alle Daten liegen nur in diesem Browser auf diesem Gerät. Bitte ab und zu eine Sicherung speichern, zum Beispiel
          in „Dateien“ oder per Mail an dich selbst. Die Datei passt auch zur Android-App.
        </Hint>
        <div class="row">
          <button type="button" class="button main" onClick={() => void saveBackup()}>
            Sicherung speichern
          </button>
          <button type="button" class="button main" onClick={() => fileInput.current?.click()}>
            Sicherung einlesen
          </button>
        </div>
        <input
          ref={fileInput}
          type="file"
          accept=".json,application/json,text/plain"
          class="visually-hidden"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            const input = event.currentTarget as HTMLInputElement;
            const file = input.files?.[0];
            input.value = "";
            if (file) void readBackup(file);
          }}
        />
        {message ? (
          <p class="hint" role="status">
            {message}
          </p>
        ) : null}

        <SectionHeading>Über die App</SectionHeading>
        <Hint>Schafkopf Tracker {version} · Web-App für iPhone und Browser · freie Software (CC0 1.0)</Hint>
        <Hint>Es gibt keinen Server: Nichts verlässt dieses Gerät, außer du gibst eine Sicherung selbst weiter.</Hint>
      </main>
      {preview ? (
        <ImportDialog
          preview={preview}
          onClose={(result) => {
            setPreview(null);
            if (result !== null) setMessage(result);
          }}
        />
      ) : null}
    </div>
  );
}
