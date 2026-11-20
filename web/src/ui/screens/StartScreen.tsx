/* Startseite: laufender Abend, „Neuer Abend“, Statistik, Spieler, Einstellungen und alle Abende. */
import { useMemo } from "preact/hooks";
import { isRunning } from "../../core/evening/Evening";
import { EveningCalculator } from "../../core/evening/EveningCalculator";
import type { Evening } from "../../core/model";
import { Amount, TopBar } from "../components";
import { useAppState, useNames, useServices } from "../context";
import { formatDate, plural } from "../format";
import { RoundRow } from "../round";

interface Standing {
  readonly id: string;
  readonly name: string;
  readonly balance: number;
  readonly rank: number;
}

interface EveningCard {
  readonly evening: Evening;
  readonly running: boolean;
  readonly games: number;
  readonly standings: readonly Standing[];
}

function card(evening: Evening, names: ReadonlyMap<string, string>): EveningCard {
  const computed = EveningCalculator.compute(evening);
  const standings = evening.seating
    .map((id) => ({ id, name: names.get(id) ?? "?", balance: computed.balances.get(id) ?? 0, rank: computed.ranks.get(id) ?? 0 }))
    .sort((a, b) => a.rank - b.rank);
  return { evening, running: isRunning(evening), games: computed.gamesPlayed, standings };
}

/** Datum, Zahl der Spiele und die Rangliste eines Abends. */
function EveningSummary({ item }: { item: EveningCard }) {
  const date = formatDate(item.evening.startedAt);
  const asMoney = item.evening.rules.displayAsMoney;
  return (
    <>
      <span class="card-title">
        {item.evening.location.trim() === "" ? date : `${date} · ${item.evening.location}`}
        {item.running ? <span class="badge">läuft</span> : null}
      </span>
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
  const { router } = useServices();
  const { evenings, unreadableEvenings } = useAppState();
  const names = useNames();
  const cards = useMemo(() => evenings.map((evening) => card(evening, names)), [evenings, names]);
  const running = cards.find((it) => it.running);

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
            <EveningSummary item={running} />
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
        <RoundRow />

        <h2 class="section-heading">Abende</h2>
        {unreadableEvenings > 0 ? (
          <p class="hint error-text">
            {plural(unreadableEvenings, "gespeicherter Abend ließ", "gespeicherte Abende ließen")} sich nicht lesen. Sie
            bleiben unverändert gespeichert.
          </p>
        ) : null}
        {cards.length === 0 ? (
          <p class="hint">Noch kein Abend eingetragen. Mit „Neuer Abend“ geht es los.</p>
        ) : (
          <ul class="list" aria-label="Alle Abende">
            {cards.map((item) => (
              <li key={item.evening.id}>
                <button type="button" class="card" onClick={() => router.open({ name: "board", eveningId: item.evening.id })}>
                  <EveningSummary item={item} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
