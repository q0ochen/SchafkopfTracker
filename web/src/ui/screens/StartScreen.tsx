/*
 * Startseite: laufender Abend, „Neuer Abend“, Statistik, Spieler, Einstellungen, die Zeile „Gemeinsame Runden“ und alle
 * Abende. Ein geplanter Abend (docs/ARCHITEKTUR.md 4.2) steht nur in der Liste, mit der Marke „geplant“. Mit
 * gemeinsamer Runde trägt jeder Abend die Marke „Runde: ‹Name›“ (8.9): Ein Gerät hängt an höchstens einer Runde, und
 * dann gehören alle Abende darauf zu ihr. Hat das Konto mehrere Runden, lässt sich in der Zeile „Gemeinsame Runden“
 * zwischen ihnen wechseln (Runden-Auswahl, 8.9).
 */
import { useMemo } from "preact/hooks";
import { isRunning } from "../../core/evening/Evening";
import { EveningCalculator } from "../../core/evening/EveningCalculator";
import type { Evening } from "../../core/model";
import { Amount, TopBar } from "../components";
import { useAppState, useNames, useRoundState, useServices } from "../context";
import { formatDate, plural } from "../format";
import { isPlanned, localDateOf } from "../localDate";
import { RoundRow } from "../round";

interface Standing {
  readonly id: string;
  readonly name: string;
  readonly balance: number;
  readonly rank: number;
}

/**
 * Kurzfassung eines Abends für die Startseite.
 *
 * @property planned Geplant (nicht beendet, Tag nach heute): Marke „geplant“ statt „läuft“, nie in „Abend läuft“.
 */
interface EveningCard {
  readonly evening: Evening;
  readonly running: boolean;
  readonly planned: boolean;
  readonly games: number;
  readonly standings: readonly Standing[];
}

/** @param today Das Ortsdatum von heute („JJJJ-MM-TT“). */
function card(evening: Evening, names: ReadonlyMap<string, string>, today: string): EveningCard {
  const computed = EveningCalculator.compute(evening);
  const standings = evening.seating
    .map((id) => ({ id, name: names.get(id) ?? "?", balance: computed.balances.get(id) ?? 0, rank: computed.ranks.get(id) ?? 0 }))
    .sort((a, b) => a.rank - b.rank);
  return { evening, running: isRunning(evening), planned: isPlanned(evening, today), games: computed.gamesPlayed, standings };
}

/**
 * Datum, Zahl der Spiele und die Rangliste eines Abends.
 *
 * @param roundName Die verbundene Runde; `null` ohne Runde, dann gibt es keine Marke.
 */
function EveningSummary({ item, roundName }: { item: EveningCard; roundName: string | null }) {
  const date = formatDate(item.evening.startedAt);
  const asMoney = item.evening.rules.displayAsMoney;
  return (
    <>
      <span class="card-title">
        {item.evening.location.trim() === "" ? date : `${date} · ${item.evening.location}`}
        {item.planned ? <span class="badge">geplant</span> : item.running ? <span class="badge">läuft</span> : null}
      </span>
      {roundName !== null ? <span class="round-label">Runde: {roundName}</span> : null}
      <span class="card-line hint">{plural(item.games, "Spiel", "Spiele")}</span>
      {item.standings.map((standing) => (
        <span class="standing-line" key={standing.id}>
          <span>
            {standing.rank}. {standing.name}
          </span>
          <Amount cents={standing.balance} asMoney={asMoney} />
        </span>
      ))}
    </>
  );
}

/** Hinweis für Safari auf dem iPhone, solange die App nicht auf dem Home-Bildschirm liegt. */
function InstallHint() {
  const standalone = (navigator as { standalone?: boolean }).standalone;
  if (standalone !== false) return null;
  return (
    <div class="banner">
      <strong>Tipp fürs iPhone:</strong> In Safari unten auf „Teilen“ und dann auf „Zum Home-Bildschirm“ tippen. Dann
      startet der Schafkopf Tracker wie eine App und funktioniert auch ohne Netz.
    </div>
  );
}

export function StartScreen() {
  const { store, router } = useServices();
  const { evenings, unreadableEvenings } = useAppState();
  const names = useNames();
  const roundName = useRoundState().link?.roundName ?? null;
  const today = localDateOf(store.clock.now());
  const cards = useMemo(() => evenings.map((evening) => card(evening, names, today)), [evenings, names, today]);
  // „Abend läuft“ zeigt nie einen geplanten Abend, auch wenn er in der Liste ganz oben steht (der neueste zuerst).
  const running = cards.find((it) => it.running && !it.planned);

  return (
    <div class="screen">
      <TopBar title="Schafkopf Tracker" />
      <main class="content">
        <InstallHint />
        {running ? (
          <section class="card highlight" aria-labelledby="running-title">
            <h2 id="running-title" class="card-title">
              Abend läuft
            </h2>
            <EveningSummary item={running} roundName={roundName} />
            <button
              type="button"
              class="button primary main full-width spaced-top"
              onClick={() => router.open({ name: "board", eveningId: running.evening.id })}
            >
              Weiterspielen
            </button>
          </section>
        ) : null}
        <button
          type="button"
          class={running ? "button main full-width spaced" : "button primary main full-width spaced"}
          onClick={() => router.open({ name: "newEvening" })}
        >
          Neuer Abend
        </button>
        <nav class="row nav-row" aria-label="Bereiche">
          <button type="button" class="button" onClick={() => router.open({ name: "statistics", eveningId: null })}>
            Statistik
          </button>
          <button type="button" class="button" onClick={() => router.open({ name: "players" })}>
            Spieler
          </button>
          <button type="button" class="button" onClick={() => router.open({ name: "settings" })}>
            Einstellungen
          </button>
        </nav>
        <RoundRow picker />

        <h2 class="section-heading">Abende</h2>
        {unreadableEvenings.length > 0 ? (
          <p class="hint error-text">
            {plural(unreadableEvenings.length, "gespeicherter Abend ließ", "gespeicherte Abende ließen")} sich nicht lesen.
            Sie bleiben unverändert gespeichert.
          </p>
        ) : null}
        {cards.length === 0 ? (
          <p class="hint">Noch kein Abend eingetragen. Mit „Neuer Abend“ geht es los.</p>
        ) : (
          <ul class="list" aria-label="Alle Abende">
            {cards.map((item) => (
              <li key={item.evening.id}>
                <button type="button" class="card" onClick={() => router.open({ name: "board", eveningId: item.evening.id })}>
                  <EveningSummary item={item} roundName={roundName} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
