/*
 * Bausteine der Oberfläche, angelehnt an die Android-App (`ui/common/Components.kt`, `SettingRows.kt`): große
 * Tippziele (mindestens 48 px, Hauptaktionen 56 px und mehr), Auswahl nie nur über die Farbe (Haken und
 * `aria-pressed`), Beträge immer mit Vorzeichen.
 */
import type { ComponentChildren, JSX } from "preact";
import { useEffect, useId, useRef, useState } from "preact/hooks";
import { amountTone, formatAmount } from "./format";

// ---------------------------------------------------------------- Symbole

export type IconName =
  | "back"
  | "more"
  | "delete"
  | "check"
  | "matchbox"
  | "up"
  | "down"
  | "add"
  | "edit"
  | "share"
  | "place"
  | "cloud-done"
  | "cloud-upload"
  | "cloud-off";

/** Der Umriss der Wolke, in den die Zeichen für „abgeglichen“ und „wartet“ gestanzt werden. */
const CLOUD = "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z";

const ICON_PATHS: Record<Exclude<IconName, "matchbox">, string> = {
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  more: "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
  delete: "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  up: "M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z",
  down: "M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  share:
    "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11A2.99 2.99 0 0 0 21 5a3 3 0 1 0-5.91.7L8.04 9.81A3 3 0 1 0 6 15c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65A2.92 2.92 0 1 0 18 16.08z",
  // Ortsvorschläge (wie `Icons.Filled.Place` in der Android-App).
  place: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z",
  // Gemeinsame Runde: Wolke mit Haken (abgeglichen), mit Pfeil nach oben (es wartet etwas), durchgestrichen (Störung).
  "cloud-done": `${CLOUD}M10 17l-3.5-3.5 1.41-1.41L10 14.17 15.18 9l1.41 1.41L10 17z`,
  "cloud-upload": `${CLOUD}M14 13v4h-4v-4H7l5-5 5 5h-3z`,
  "cloud-off":
    "M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z",
};

/** Symbol in der Textfarbe. Ohne `label` ist es Schmuck und für Screenreader unsichtbar. */
export function Icon({ name, label, size = 24 }: { name: IconName; label?: string; size?: number }) {
  const a11y = label ? { role: "img" as const, "aria-label": label } : { "aria-hidden": true as const };
  if (name === "matchbox") {
    return (
      <svg class="icon" viewBox="0 0 24 24" width={size} height={size} focusable="false" {...a11y}>
        <path
          d="M2.5,6.5 h11 a1,1 0 0 1 1,1 v9 a1,1 0 0 1 -1,1 h-11 a1,1 0 0 1 -1,-1 v-9 a1,1 0 0 1 1,-1 z M3.6,13.6 v1.8 h8.8 v-1.8 z"
          fill="currentColor"
          fill-rule="evenodd"
        />
        <path d="M15.6,8.4 h6.1 v7.2 h-6.1" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M15.2,9.9 h3 v1 h-3 z M15.2,13.1 h3 v1 h-3 z" fill="currentColor" />
        <path
          d="M17.9,10.4 a1.3,1.3 0 1 0 2.6,0 a1.3,1.3 0 1 0 -2.6,0 z M17.9,13.6 a1.3,1.3 0 1 0 2.6,0 a1.3,1.3 0 1 0 -2.6,0 z"
          fill="currentColor"
        />
      </svg>
    );
  }
  return (
    <svg class="icon" viewBox="0 0 24 24" width={size} height={size} focusable="false" {...a11y}>
      <path d={ICON_PATHS[name]} fill="currentColor" />
    </svg>
  );
}

// ---------------------------------------------------------------- Rahmen eines Bildschirms

/** Kopfzeile mit Zurück-Knopf, Titel und weiteren Aktionen. */
export function TopBar({ title, onBack, actions }: { title: string; onBack?: () => void; actions?: ComponentChildren }) {
  return (
    <header class="top-bar">
      {onBack ? (
        <button type="button" class="icon-button" onClick={onBack} aria-label="Zurück">
          <Icon name="back" />
        </button>
      ) : null}
      <h1 class="top-bar-title">{title}</h1>
      {actions ? <div class="top-bar-actions">{actions}</div> : null}
    </header>
  );
}

/** Aufklappmenü hinter dem Knopf „Mehr“ (⋮). Schließt bei Escape und beim Tippen daneben. */
export function MoreMenu({ items }: { items: ReadonlyArray<{ label: string; onSelect: () => void; danger?: boolean }> }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const listId = useId();
  useEffect(() => {
    if (!open) return;
    const onClick = (event: Event) => {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("click", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("click", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return (
    <div class="more-menu" ref={ref}>
      <button
        type="button"
        class="icon-button"
        aria-label="Mehr"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((it) => !it)}
      >
        <Icon name="more" />
      </button>
      {open ? (
        <ul class="more-menu-list" id={listId}>
          {items.map((item) => (
            <li key={item.label}>
              <button
                type="button"
                class={item.danger ? "menu-item danger" : "menu-item"}
                onClick={() => {
                  setOpen(false);
                  item.onSelect();
                }}
              >
                {item.label}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

export function SectionHeading({ children }: { children: ComponentChildren }) {
  return <h2 class="section-heading">{children}</h2>;
}

/** Hinweistext in gedämpfter Farbe. */
export function Hint({ children, live }: { children: ComponentChildren; live?: boolean }) {
  return (
    <p class="hint" aria-live={live ? "polite" : undefined}>
      {children}
    </p>
  );
}

// ---------------------------------------------------------------- Auswahl

/**
 * Eine Auswahlkachel. Ausgewählt zeigt sie einen Haken in der Ecke und eine kräftige Fläche (nie nur die Farbe);
 * Screenreader hören „gedrückt“/„nicht gedrückt“.
 */
export function ChoiceTile({
  text,
  supporting,
  selected,
  onClick,
  icon,
  tall,
  label,
  disabled,
}: {
  text: string;
  supporting?: string | null;
  selected: boolean;
  onClick: () => void;
  icon?: IconName;
  /** 56 px statt 48 px hoch. */
  tall?: boolean;
  /** Vorlesetext, falls er vom sichtbaren Text abweichen soll. */
  label?: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      class={`choice-tile${selected ? " selected" : ""}${tall ? " tall" : ""}`}
      aria-pressed={selected}
      aria-label={label}
      onClick={onClick}
      disabled={disabled}
    >
      {selected ? (
        <span class="choice-tile-check">
          <Icon name="check" size={16} />
        </span>
      ) : null}
      <span class="choice-tile-main">
        {icon ? <Icon name={icon} size={18} /> : null}
        <span class="choice-tile-text">{text}</span>
      </span>
      {supporting ? <span class="choice-tile-supporting">{supporting}</span> : null}
    </button>
  );
}

/** Eine Reihe von Kacheln, aus der genau eine (oder keine) gewählt ist. */
export function ChoiceRow<T>({
  options,
  selected,
  label,
  onSelect,
  groupLabel,
  columns,
  tall,
}: {
  options: readonly T[];
  selected: T | null;
  label: (option: T) => string;
  onSelect: (option: T) => void;
  groupLabel: string;
  /** Höchstens so viele je Zeile (sonst alle in einer Zeile). */
  columns?: number;
  tall?: boolean;
}) {
  const perRow = columns ?? options.length;
  return (
    <div class="choice-row" role="group" aria-label={groupLabel} style={{ "--columns": String(Math.max(1, perRow)) }}>
      {options.map((option) => (
        <ChoiceTile key={label(option)} text={label(option)} selected={option === selected} onClick={() => onSelect(option)} tall={tall} />
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- Sitzordnung

/**
 * Die Sitzordnung im Uhrzeigersinn („Neuer Abend“ und „Abend bearbeiten“): je Spieler eine Zeile mit seinem Platz.
 * Antippen macht ihn zum ersten Geber („gibt zuerst“), die Pfeile rücken ihn einen Platz nach vorn oder hinten.
 */
export function SeatingList({
  seating,
  firstDealer,
  name,
  onFirstDealer,
  onMove,
}: {
  seating: readonly string[];
  firstDealer: string | null;
  name: (player: string) => string;
  onFirstDealer: (player: string) => void;
  /** −1: einen Platz nach vorn, +1: einen Platz nach hinten. */
  onMove: (player: string, delta: number) => void;
}) {
  return (
    <ol class="list" aria-label="Sitzordnung">
      {seating.map((id, index) => {
        const isDealer = id === firstDealer;
        return (
          <li key={id} class={isDealer ? "seat first-dealer" : "seat"}>
            <button type="button" class="seat-main" role="radio" aria-checked={isDealer} onClick={() => onFirstDealer(id)}>
              <span class="seat-number" aria-hidden="true">
                {index + 1}
              </span>
              <span>
                {name(id)}
                {isDealer ? <span class="badge">gibt zuerst</span> : null}
              </span>
            </button>
            <button
              type="button"
              class="icon-button"
              aria-label={`${name(id)} einen Platz nach vorn`}
              disabled={index === 0}
              onClick={() => onMove(id, -1)}
            >
              <Icon name="up" />
            </button>
            <button
              type="button"
              class="icon-button"
              aria-label={`${name(id)} einen Platz nach hinten`}
              disabled={index === seating.length - 1}
              onClick={() => onMove(id, 1)}
            >
              <Icon name="down" />
            </button>
          </li>
        );
      })}
    </ol>
  );
}

// ---------------------------------------------------------------- Einstellungen

/** Schalter mit Beschriftung (ganze Zeile antippbar). */
export function SwitchRow({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div class="switch-row">
      <label for={id} class="switch-row-label">
        <span>{label}</span>
        {hint ? <span class="switch-row-hint">{hint}</span> : null}
      </label>
      <input
        id={id}
        type="checkbox"
        role="switch"
        class="switch"
        checked={checked}
        onChange={(event) => onChange((event.currentTarget as HTMLInputElement).checked)}
      />
    </div>
  );
}

/** Zahl mit „−“ und „+“. */
export function StepperRow({
  label,
  value,
  min,
  max,
  step = 1,
  onChange,
  format = String,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  onChange: (value: number) => void;
  format?: (value: number) => string;
}) {
  return (
    <div class="stepper-row">
      <span class="stepper-label">{label}</span>
      <div class="stepper">
        <button
          type="button"
          class="stepper-button"
          aria-label={`${label} verringern`}
          disabled={value <= min}
          onClick={() => onChange(Math.max(min, value - step))}
        >
          {"−"}
        </button>
        <output class="stepper-value" aria-live="polite">
          {format(value)}
        </output>
        <button
          type="button"
          class="stepper-button"
          aria-label={`${label} erhöhen`}
          disabled={value >= max}
          onClick={() => onChange(Math.min(max, value + step))}
        >
          +
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Beträge

/** Betrag mit Vorzeichen; grün für Gewinn, rot für Verlust (das Vorzeichen steht immer dabei). */
export function Amount({
  cents,
  asMoney,
  signed = true,
  class: extraClass,
}: {
  cents: number;
  asMoney: boolean;
  signed?: boolean;
  class?: string;
}) {
  return <span class={`amount ${amountTone(cents)}${extraClass ? ` ${extraClass}` : ""}`}>{formatAmount(cents, asMoney, signed)}</span>;
}

/** Farbklasse eines Spielers (Punkte, Linien, Legenden). */
export function seriesClass(colorIndex: number): string {
  return `series-${((colorIndex % 8) + 8) % 8}`;
}

/** Name mit dem Farbpunkt des Spielers (der Text bleibt in Textfarbe, gut lesbar). */
export function PlayerName({ name, colorIndex }: { name: string; colorIndex: number }) {
  return (
    <span class="player-name">
      <span class={`player-dot ${seriesClass(colorIndex)}`} aria-hidden="true" />
      <span class="player-name-text">{name}</span>
    </span>
  );
}

// ---------------------------------------------------------------- Dialoge und Meldungen

/** Modaler Dialog (natives `<dialog>`, schließt mit Escape). */
export function Dialog({
  title,
  children,
  actions,
  onClose,
}: {
  title: string;
  children?: ComponentChildren;
  actions: ComponentChildren;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) {
      if (typeof dialog.showModal === "function") dialog.showModal();
      else dialog.setAttribute("open", "");
    }
  }, []);
  const onCancel = (event: JSX.TargetedEvent<HTMLDialogElement, Event>) => {
    event.preventDefault();
    onClose();
  };
  return (
    <dialog ref={ref} class="dialog" aria-labelledby={titleId} onCancel={onCancel}>
      <h2 id={titleId} class="dialog-title">
        {title}
      </h2>
      <div class="dialog-body">{children}</div>
      <div class="dialog-actions">{actions}</div>
    </dialog>
  );
}

/** Kurze Meldung am unteren Rand, wahlweise mit einer Aktion (zum Beispiel „Wiederherstellen“). */
export function Snackbar({ message, actionLabel, onAction }: { message: string; actionLabel?: string; onAction?: () => void }) {
  return (
    <div class="snackbar" role="status" aria-live="polite">
      <span>{message}</span>
      {actionLabel && onAction ? (
        <button type="button" class="text-button" onClick={onAction}>
          {actionLabel}
        </button>
      ) : null}
    </div>
  );
}
