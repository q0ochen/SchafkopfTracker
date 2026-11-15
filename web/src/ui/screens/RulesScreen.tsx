/*
 * Regeln bearbeiten: die Vorlage für neue Abende oder das Regelwerk eines Abends (wie `ui/settings/RulesScreen.kt`
 * der Android-App). Jede Änderung wird sofort gespeichert; bei einem Abend werden die Stände neu berechnet.
 */
import { useEffect, useId, useState } from "preact/hooks";
import type { BockStacking, GameType, PayoutRule, RuleSet } from "../../core/model";
import { BOCK_STACKINGS, GAME_TYPES, PAYOUT_RULES } from "../../core/model";
import { isSolo, laufendeMax, laufendeMin, price, RuleSets } from "../../core/rules/RuleSet";
import { ChoiceRow, ChoiceTile, Hint, SectionHeading, StepperRow, SwitchRow, TopBar } from "../components";
import { useAppState, useServices } from "../context";
import { BOCK_STACKING_LABELS, GAME_TYPE_LABELS, PAYOUT_HINTS, PAYOUT_LABELS } from "../format";

const MAX_RULES_NAME_LENGTH = 40;
const MAX_CENT_DIGITS = 5;
const TYPES_PER_ROW = 3;

/** Setzt Einheit, Rufspiel-Preis und den Preis aller Einzelspiele auf einmal. */
function withTariff(rules: RuleSet, unit: number, rufspiel: number, solo: number): RuleSet {
  const prices: Partial<Record<GameType, number>> = {};
  for (const type of GAME_TYPES) prices[type] = isSolo(type) ? solo : rufspiel;
  return { ...rules, unit, prices };
}

/** Betrag in Cent als Eingabefeld (nur Ziffern, höchstens fünf). */
function CentField({ label, value, onChange }: { label: string; value: number; onChange: (value: number) => void }) {
  const id = useId();
  const [text, setText] = useState(String(value));
  useEffect(() => setText(String(value)), [value]);
  const invalid = !/^\d+$/.test(text);
  return (
    <div class="text-field">
      <label for={id}>{label}</label>
      <input
        id={id}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={text}
        aria-invalid={invalid}
        onInput={(event) => {
          const digits = (event.currentTarget as HTMLInputElement).value.replace(/\D/g, "").slice(0, MAX_CENT_DIGITS);
          setText(digits);
          (event.currentTarget as HTMLInputElement).value = digits;
          if (digits !== "") {
            const number = Number.parseInt(digits, 10);
            if (number !== value) onChange(number);
          }
        }}
      />
    </div>
  );
}

function RulesEditor({ rules, isTemplate, onChange }: { rules: RuleSet; isTemplate: boolean; onChange: (transform: (rules: RuleSet) => RuleSet) => void }) {
  const nameId = useId();
  const typeRows: GameType[][] = [];
  for (let i = 0; i < GAME_TYPES.length; i += TYPES_PER_ROW) typeRows.push(GAME_TYPES.slice(i, i + TYPES_PER_ROW));
  const priceRows: GameType[][] = [];
  for (let i = 0; i < GAME_TYPES.length; i += 2) priceRows.push(GAME_TYPES.slice(i, i + 2));

  return (
    <>
      <Hint>
        {isTemplate
          ? "Diese Regeln gelten für jeden neuen Abend. Bereits angelegte Abende behalten ihre Regeln."
          : "Änderungen gelten für alle Spiele dieses Abends; die Stände werden neu berechnet."}
      </Hint>
      <div class="text-field">
        <label for={nameId}>Name des Regelwerks</label>
        <input
          id={nameId}
          type="text"
          autoComplete="off"
          maxLength={MAX_RULES_NAME_LENGTH}
          value={rules.name}
          onInput={(event) => {
            const name = (event.currentTarget as HTMLInputElement).value.slice(0, MAX_RULES_NAME_LENGTH);
            onChange((it) => ({ ...it, name }));
          }}
        />
      </div>

      <SectionHeading>Tarif (in Cent)</SectionHeading>
      <div class="row">
        <button type="button" class="button" onClick={() => onChange((it) => withTariff(it, 10, 10, 50))}>
          Runde: 10 / 50
        </button>
        <button type="button" class="button" onClick={() => onChange((it) => withTariff(it, 10, 20, 50))}>
          Sauspiel: 10-20-50
        </button>
      </div>
      <CentField label="Schneider, Schwarz, je Laufendem" value={rules.unit} onChange={(unit) => onChange((it) => ({ ...it, unit }))} />
      {priceRows.map((row) => (
        <div class="row" key={row.join()}>
          {row.map((type) => (
            <CentField
              key={type}
              label={`Preis ${GAME_TYPE_LABELS[type]}`}
              value={price(rules, type)}
              onChange={(value) => onChange((it) => ({ ...it, prices: { ...it.prices, [type]: value } }))}
            />
          ))}
          {row.length < 2 ? <span /> : null}
        </div>
      ))}

      <SectionHeading>Spielarten</SectionHeading>
      <div class="stack-8" role="group" aria-label="Spielarten">
        {typeRows.map((row) => (
          <div class="choice-row" style={{ "--columns": String(TYPES_PER_ROW) }} key={row.join()}>
            {row.map((type) => (
              <ChoiceTile
                key={type}
                text={GAME_TYPE_LABELS[type]}
                selected={rules.enabledTypes.includes(type)}
                onClick={() =>
                  onChange((current) => {
                    const changed = current.enabledTypes.includes(type)
                      ? current.enabledTypes.filter((it) => it !== type)
                      : [...current.enabledTypes, type];
                    // Mindestens eine Spielart muss übrig bleiben.
                    return changed.length === 0 ? current : { ...current, enabledTypes: changed };
                  })
                }
              />
            ))}
          </div>
        ))}
      </div>

      <SectionHeading>Abrechnung beim Einzelspiel</SectionHeading>
      <ChoiceRow<PayoutRule>
        groupLabel="Abrechnung beim Einzelspiel"
        options={PAYOUT_RULES}
        selected={rules.payoutRule}
        label={(rule) => PAYOUT_LABELS[rule]}
        onSelect={(payoutRule) => onChange((it) => ({ ...it, payoutRule }))}
        tall
      />
      <Hint>{PAYOUT_HINTS[rules.payoutRule]}</Hint>

      <SectionHeading>Laufende zählen ab</SectionHeading>
      {GAME_TYPES.map((type) => (
        <StepperRow
          key={type}
          label={GAME_TYPE_LABELS[type]}
          value={laufendeMin(rules, type)}
          min={1}
          max={Math.max(1, laufendeMax(rules, type))}
          onChange={(value) => onChange((it) => ({ ...it, laufendeMin: { ...it.laufendeMin, [type]: value } }))}
        />
      ))}

      <SectionHeading>Verdoppeln</SectionHeading>
      <SwitchRow label="Legen (Klopfen)" checked={rules.klopfenEnabled} onChange={(on) => onChange((it) => ({ ...it, klopfenEnabled: on }))} />
      <SwitchRow
        label="Kontra"
        checked={rules.kontraEnabled}
        onChange={(on) => onChange((it) => ({ ...it, kontraEnabled: on, retourEnabled: it.retourEnabled && on }))}
      />
      {rules.kontraEnabled ? (
        <SwitchRow label="Retour" checked={rules.retourEnabled} onChange={(on) => onChange((it) => ({ ...it, retourEnabled: on }))} />
      ) : null}

      <SectionHeading>Wertung des Sie</SectionHeading>
      <ChoiceRow<number>
        groupLabel="Wertung des Sie"
        options={[2, 4]}
        selected={rules.sieFactor === 2 || rules.sieFactor === 4 ? rules.sieFactor : null}
        label={(factor) => (factor === 2 ? "wie Tout mit 8 Laufenden" : "vierfach")}
        onSelect={(sieFactor) => onChange((it) => ({ ...it, sieFactor }))}
        tall
      />

      <SectionHeading>Bockrunde</SectionHeading>
      <SwitchRow
        label="Bockrunde bei 6 Nixern"
        checked={rules.bock.enabled}
        onChange={(on) => onChange((it) => ({ ...it, bock: { ...it.bock, enabled: on } }))}
      />
      {rules.bock.enabled ? (
        <>
          <StepperRow
            label="Länge in Spielen"
            value={rules.bock.gamesPerTrigger}
            min={1}
            max={12}
            onChange={(value) => onChange((it) => ({ ...it, bock: { ...it.bock, gamesPerTrigger: value } }))}
          />
          <Hint>Wird während einer Bockrunde wieder eine ausgelöst</Hint>
          <ChoiceRow<BockStacking>
            groupLabel="Wird während einer Bockrunde wieder eine ausgelöst"
            options={BOCK_STACKINGS}
            selected={rules.bock.stacking}
            label={(stacking) => BOCK_STACKING_LABELS[stacking]}
            onSelect={(stacking) => onChange((it) => ({ ...it, bock: { ...it.bock, stacking } }))}
          />
          <SwitchRow
            label="Zusammengeworfenes Blatt verbraucht ein Bockspiel"
            checked={rules.bock.thrownInConsumesBockGame}
            onChange={(on) => onChange((it) => ({ ...it, bock: { ...it.bock, thrownInConsumesBockGame: on } }))}
          />
        </>
      ) : null}

      <SectionHeading>Sonstiges</SectionHeading>
      <SwitchRow
        label="Nach dem Zusammenwerfen gibt der Nächste"
        checked={rules.dealerAdvancesAfterThrowIn}
        onChange={(on) => onChange((it) => ({ ...it, dealerAdvancesAfterThrowIn: on }))}
      />
      <Hint>Anzeige</Hint>
      <ChoiceRow<boolean>
        groupLabel="Anzeige"
        options={[true, false]}
        selected={rules.displayAsMoney}
        label={(money) => (money ? "Euro" : "Punkte")}
        onSelect={(displayAsMoney) => onChange((it) => ({ ...it, displayAsMoney }))}
      />

      <button type="button" class="button full-width spaced-top" onClick={() => onChange(() => RuleSets.standard())}>
        Auf die Regeln der Runde zurücksetzen
      </button>
      <button type="button" class="button full-width spaced" onClick={() => onChange(() => RuleSets.sauspiel())}>
        Regeln von sauspiel.de übernehmen
      </button>
    </>
  );
}

export function RulesScreen({ eveningId }: { eveningId: string | null }) {
  const { store, router } = useServices();
  const { settings, evenings } = useAppState();
  const evening = eveningId === null ? null : evenings.find((it) => it.id === eveningId);
  const rules = eveningId === null ? settings.defaultRules : evening?.rules;
  const title = eveningId === null ? "Regeln für neue Abende" : "Regeln dieses Abends";

  const onChange = (transform: (rules: RuleSet) => RuleSet) => {
    if (eveningId === null) void store.updateSettings((it) => ({ ...it, defaultRules: transform(it.defaultRules) }));
    else void store.updateEvening(eveningId, (it) => ({ ...it, rules: transform(it.rules) }));
  };

  return (
    <div class="screen">
      <TopBar title={title} onBack={() => router.back()} />
      <main class="content">
        {rules ? <RulesEditor rules={rules} isTemplate={eveningId === null} onChange={onChange} /> : <p class="empty">Diesen Abend gibt es nicht mehr.</p>}
      </main>
    </div>
  );
}
