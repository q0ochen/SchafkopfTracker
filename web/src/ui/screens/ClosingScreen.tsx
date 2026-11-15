/* Abschluss eines Abends: Endstand, wer wem wie viel zahlt, und alles als Text zum Teilen. */
import { useMemo, useState } from "preact/hooks";
import { isRunning } from "../../core/evening/Evening";
import { EveningCalculator } from "../../core/evening/EveningCalculator";
import { closingOf, closingText } from "../closing";
import { Amount, Hint, Icon, PlayerName, SectionHeading, TopBar } from "../components";
import { useAppState, useColors, useNames, useServices } from "../context";
import { formatAmount, formatDate, plural } from "../format";

export function ClosingScreen({ eveningId }: { eveningId: string }) {
  const { router, platform } = useServices();
  const { evenings } = useAppState();
  const names = useNames();
  const colors = useColors();
  const evening = evenings.find((it) => it.id === eveningId);
  const state = useMemo(() => (evening ? EveningCalculator.compute(evening) : null), [evening]);
  const closing = useMemo(() => (state ? closingOf(state, names) : null), [state, names]);
  const [message, setMessage] = useState<string | null>(null);

  if (!evening || !state || !closing) {
    return (
      <div class="screen">
        <TopBar title="Abschluss" onBack={() => router.back()} />
        <main class="content">
          <p class="empty">Diesen Abend gibt es nicht mehr.</p>
        </main>
      </div>
    );
  }

  const asMoney = evening.rules.displayAsMoney;
  const share = async () => {
    const outcome = await platform.shareText("Schafkopf-Abend", closingText(state, closing, names));
    if (outcome === "copied") setMessage("Der Text ist in der Zwischenablage.");
    else if (outcome === "failed") setMessage("Teilen ist hier leider nicht möglich.");
    else setMessage(null);
  };

  return (
    <div class="screen">
      <TopBar title="Abschluss" onBack={() => router.back()} />
      <main class="content">
        <p class="card-title">
          {formatDate(evening.startedAt)}
          {evening.location.trim() !== "" ? ` · ${evening.location}` : ""}
        </p>
        <Hint>
          {plural(state.gamesPlayed, "Spiel", "Spiele")}
          {isRunning(evening) ? " · Der Abend läuft noch." : ""}
        </Hint>

        <SectionHeading>Endstand</SectionHeading>
        <ol class="list" aria-label="Endstand">
          {closing.standings.map((standing) => (
            <li key={standing.player} class="list-item">
              <span class="grow">
                {standing.rank}. <PlayerName name={standing.name} colorIndex={colors.get(standing.player) ?? 0} />
              </span>
              <Amount cents={standing.balance} asMoney={asMoney} />
            </li>
          ))}
        </ol>

        <SectionHeading>Wer zahlt wem</SectionHeading>
        {closing.transfers.length === 0 ? (
          <Hint>Niemand muss etwas zahlen.</Hint>
        ) : (
          <ul class="list" aria-label="Wer zahlt wem">
            {closing.transfers.map((transfer, index) => (
              <li key={index} class="list-item">
                <span class="grow">
                  {names.get(transfer.from) ?? "?"} zahlt {names.get(transfer.to) ?? "?"}
                </span>
                <strong class="amount">{formatAmount(transfer.amount, asMoney)}</strong>
              </li>
            ))}
          </ul>
        )}
        {!closing.balanced ? (
          <p class="hint error-text">Die Stände gehen nicht auf (eine freie Buchung ist fehlerhaft); ein Rest bleibt offen.</p>
        ) : null}

        <button type="button" class="button primary main full-width spaced-top" onClick={() => void share()}>
          <Icon name="share" />
          Als Text teilen
        </button>
        <button
          type="button"
          class="button full-width spaced"
          onClick={() => router.open({ name: "statistics", eveningId })}
        >
          Statistik dieses Abends
        </button>
        {message ? (
          <p class="hint" role="status">
            {message}
          </p>
        ) : null}
      </main>
    </div>
  );
}
