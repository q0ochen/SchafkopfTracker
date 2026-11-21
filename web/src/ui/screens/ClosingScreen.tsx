/*
 * „Abend teilen“ (bis 21.11.2026 „Abschluss“; docs/ARCHITEKTUR.md, 4.4): Endstand, Vermögensverlauf und wer wem wie
 * viel zahlt – als Bild oder als Text zum Teilen.
 */
import { useMemo, useState } from "preact/hooks";
import { EveningCalculator } from "../../core/evening/EveningCalculator";
import { StatisticEngine } from "../../core/stats/StatisticEngine";
import { BalanceChart } from "../charts/BalanceChart";
import { closingOf, closingSubtitle, closingText, closingTitle, transferText } from "../closing";
import { Amount, Hint, PlayerName, SectionHeading, TopBar } from "../components";
import { useAppState, useColors, useNames, useServices } from "../context";
import { formatAmount } from "../format";
import { EVENING_IMAGE_NAME, eveningShareImage } from "../shareImage";
import { ShareImageButton } from "../ShareImageButton";
import { balanceSeriesOf } from "../statistics";

const TITLE = "Abend teilen";

export function ClosingScreen({ eveningId }: { eveningId: string }) {
  const { router, platform } = useServices();
  const { evenings } = useAppState();
  const names = useNames();
  const colors = useColors();
  const evening = evenings.find((it) => it.id === eveningId);
  const state = useMemo(() => (evening ? EveningCalculator.compute(evening) : null), [evening]);
  const closing = useMemo(() => (state ? closingOf(state, names) : null), [state, names]);
  // Der Verlauf wie in der Statistik dieses Abends.
  const series = useMemo(
    () => (evening ? balanceSeriesOf(StatisticEngine.forEvening(evening), names, colors) : []),
    [evening, names, colors],
  );
  const [message, setMessage] = useState<string | null>(null);

  if (!evening || !state || !closing) {
    return (
      <div class="screen">
        <TopBar title={TITLE} onBack={() => router.back()} />
        <main class="content">
          <p class="empty">Diesen Abend gibt es nicht mehr.</p>
        </main>
      </div>
    );
  }

  const asMoney = evening.rules.displayAsMoney;
  const shareText = async () => {
    const outcome = await platform.shareText("Schafkopf-Abend", closingText(state, closing, names));
    if (outcome === "copied") setMessage("Der Text ist in der Zwischenablage.");
    else if (outcome === "failed") setMessage("Teilen ist hier leider nicht möglich.");
    else setMessage(null);
  };

  return (
    <div class="screen">
      <TopBar title={TITLE} onBack={() => router.back()} />
      <main class="content">
        <p class="card-title">{closingTitle(evening)}</p>
        <Hint>{closingSubtitle(state)}</Hint>

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

        <SectionHeading>Vermögensverlauf</SectionHeading>
        <BalanceChart
          series={series}
          asMoney={asMoney}
          pointLabel={(index) => (index === 0 ? "Start" : `nach Spiel ${index}`)}
          title="Vermögensverlauf: Kontostand je Spieler nach jedem Spiel"
        />

        <SectionHeading>Wer zahlt wem</SectionHeading>
        {closing.transfers.length === 0 ? (
          <Hint>Niemand muss etwas zahlen.</Hint>
        ) : (
          <ul class="list" aria-label="Wer zahlt wem">
            {closing.transfers.map((transfer, index) => (
              <li key={index} class="list-item">
                <span class="grow">{transferText(transfer, names)}</span>
                <strong class="amount">{formatAmount(transfer.amount, asMoney)}</strong>
              </li>
            ))}
          </ul>
        )}
        {!closing.balanced ? (
          <p class="hint error-text">Die Stände gehen nicht auf (eine freie Buchung ist fehlerhaft); ein Rest bleibt offen.</p>
        ) : null}

        <ShareImageButton
          image={() => eveningShareImage({ state, closing, series, names, colors })}
          fileName={EVENING_IMAGE_NAME}
          onMessage={setMessage}
        />
        <button type="button" class="button main full-width spaced" onClick={() => void shareText()}>
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
