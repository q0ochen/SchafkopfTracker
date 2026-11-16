/*
 * Statistik eines Abends oder aller Abende („ewige Tabelle“): Kontostand, Vermögensverlauf, Spielarten,
 * „Sollte-er-es-lassen?“-Index, Auszeichnungen und weitere Zahlen. Gerechnet wird mit der `StatisticEngine`
 * (verhaltensgleich zur Android-App).
 */
import type { ComponentChildren } from "preact";
import { useMemo } from "preact/hooks";
import type { Evening, PlayerId } from "../../core/model";
import { StatisticEngine, type GelegtBadge, type Statistics } from "../../core/stats/StatisticEngine";
import { BalanceChart, type ChartSeries } from "../charts/BalanceChart";
import { GameModeChart, modeChartGroups } from "../charts/GameModeChart";
import { Amount, Hint, PlayerName, SectionHeading, TopBar } from "../components";
import { useAppState, useColors, useNames, useServices } from "../context";
import { formatAmount, formatDate, formatPercent, plural } from "../format";

const BADGE_TEXTS: Readonly<Record<GelegtBadge, string>> = {
  STREICHHOLZ_SPARER: "Streichholz-Sparer: hat noch nie gelegt",
  NOCH_OFFEN: "Noch offen: zu selten gelegt für ein Urteil",
  SICHERE_BANK: "Sichere Bank: Legen lohnt sich",
  BRANDSTIFTER: "Brandstifter: sollte es lassen!",
  GLUECKSRITTER: "Glücksritter: unterm Strich im Plus",
  WACKELKANDIDAT: "Wackelkandidat: unterm Strich nicht im Plus",
};

function Fact({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <li>
      <span class="fact-label">{label}</span>
      {children}
    </li>
  );
}

function StatisticsContent({
  stats,
  evenings,
  asMoney,
  single,
}: {
  stats: Statistics;
  evenings: readonly Evening[];
  asMoney: boolean;
  single: boolean;
}) {
  const names = useNames();
  const colors = useColors();
  const name = (id: PlayerId) => names.get(id) ?? "?";
  const color = (id: PlayerId) => colors.get(id) ?? 0;
  const history = stats.history;
  const playerStat = (id: PlayerId) => history.playerStats.find((it) => it.player === id) ?? null;

  const standings = [...stats.finance.standings].sort((a, b) => a.rank - b.rank);
  const series: ChartSeries[] = stats.players.map((id) => ({
    id,
    name: name(id),
    colorIndex: color(id),
    values: stats.finance.balanceSeries.get(id) ?? [0],
  }));
  const modeGroups = modeChartGroups(stats, name, color);

  // Tabelle zum Verlauf: bei einem Abend jedes Spiel, bei vielen Abenden der Stand nach jedem Abend.
  const lastPoint = Math.max(0, ...series.map((it) => it.values.length - 1));
  const tableRows: Array<{ label: string; index: number }> = single
    ? Array.from({ length: lastPoint }, (_, i) => ({ label: `Spiel ${i + 1}`, index: i + 1 }))
    : stats.finance.eveningIds.map((id, k) => {
        const end = k + 1 < stats.finance.eveningStarts.length ? stats.finance.eveningStarts[k + 1] : lastPoint;
        const evening = evenings.find((it) => it.id === id);
        return { label: evening ? formatDate(evening.startedAt) : `Abend ${k + 1}`, index: end };
      });

  return (
    <>
      <Hint>
        {single ? "" : `${plural(stats.eveningCount, "Abend", "Abende")} · `}
        {plural(stats.gameCount, "Spiel", "Spiele")} · Umsatz {formatAmount(stats.finance.turnover, asMoney)}
      </Hint>

      <SectionHeading>Kontostand</SectionHeading>
      <div class="table-scroll">
        <table class="stat-table">
          <thead>
            <tr>
              <th scope="col">Spieler</th>
              <th scope="col">Saldo</th>
              <th scope="col">Spiele</th>
              <th scope="col">gewonnen</th>
            </tr>
          </thead>
          <tbody>
            {standings.map((standing) => {
              const stat = playerStat(standing.player);
              return (
                <tr key={standing.player}>
                  <th scope="row">
                    {standing.rank}. <PlayerName name={name(standing.player)} colorIndex={color(standing.player)} />
                  </th>
                  <td>
                    <Amount cents={standing.balance} asMoney={asMoney} />
                  </td>
                  <td>{stat?.games ?? 0}</td>
                  <td>{formatPercent(stat && stat.games > 0 ? stat.gamesWon / stat.games : null)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <SectionHeading>Vermögensverlauf</SectionHeading>
      <BalanceChart
        series={series}
        asMoney={asMoney}
        boundaries={single ? [] : stats.finance.eveningStarts}
        pointLabel={(index) => (index === 0 ? "Start" : `nach Spiel ${index}`)}
        title="Vermögensverlauf: Kontostand je Spieler nach jedem Spiel"
      />
      {tableRows.length > 0 ? (
        <details>
          <summary class="text-button">Verlauf als Tabelle</summary>
          <div class="table-scroll">
            <table class="stat-table">
              <thead>
                <tr>
                  <th scope="col">{single ? "nach" : "Abend"}</th>
                  {series.map((it) => (
                    <th scope="col" key={it.id}>
                      {it.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {tableRows.map((row) => (
                  <tr key={`${row.label}-${row.index}`}>
                    <th scope="row">{row.label}</th>
                    {series.map((it) => (
                      <td key={it.id}>
                        <Amount cents={it.values[Math.min(row.index, it.values.length - 1)] ?? 0} asMoney={asMoney} />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ) : null}

      {modeGroups.length > 0 ? (
        <>
          <SectionHeading>Spielarten: selbst gespielt</SectionHeading>
          <GameModeChart groups={modeGroups} asMoney={asMoney} />
          <Hint>
            Der Balken zeigt den Durchschnitt je Spiel: nach rechts Gewinn, nach links Verlust; jede Spielart hat ihren
            eigenen Maßstab. Gezählt sind nur selbst gespielte Spiele (als Ansager, Mitspieler oder Alleinspieler), bei
            Über Kreuz alle Spiele am Tisch, weil dort niemand ansagt.
          </Hint>
        </>
      ) : null}

      <SectionHeading>Sollte er es lassen? (Legen)</SectionHeading>
      <ul class="fact-list">
        {stats.gelegt.map((record) => (
          <li key={record.player}>
            <span class="fact-label">{name(record.player)}</span>
            <strong>{BADGE_TEXTS[record.badge]}</strong>
            {record.count > 0 ? (
              <span class="card-line hint">
                {record.count}× gelegt, {record.won} gewonnen · unterm Strich{" "}
                <Amount cents={record.netBalance} asMoney={asMoney} /> · das Legen selbst brachte{" "}
                <Amount cents={record.effect} asMoney={asMoney} />
              </span>
            ) : null}
          </li>
        ))}
      </ul>

      <SectionHeading>Auszeichnungen</SectionHeading>
      <ul class="fact-list">
        <Fact label="Bock-König (bestes Ergebnis in Bockspielen)">
          {stats.bock.king ? (
            <span>
              {name(stats.bock.king.player)}: <Amount cents={stats.bock.king.balance} asMoney={asMoney} /> in{" "}
              {plural(stats.bock.king.games, "Bockspiel", "Bockspielen")}
            </span>
          ) : (
            <span>noch niemand</span>
          )}
        </Fact>
        <Fact label="Solo-Spezialist (spielt am liebsten allein)">
          {stats.solo.specialist ? (
            <span>
              {name(stats.solo.specialist.player)}: {stats.solo.specialist.solos} von {stats.solo.specialist.announced} angesagten Spielen
              allein
            </span>
          ) : (
            <span>noch niemand</span>
          )}
        </Fact>
        <Fact label="Mauerblümchen (spielt fast nie allein)">
          {stats.solo.wallflower ? (
            <span>
              {name(stats.solo.wallflower.player)}: {stats.solo.wallflower.solos} von {stats.solo.wallflower.announced} angesagten Spielen
              allein
            </span>
          ) : (
            <span>noch niemand</span>
          )}
        </Fact>
        <Fact label="Lieblingspartner (bestes Gespann)">
          {stats.partners.favorite ? (
            <span>
              {name(stats.partners.favorite.first)} und {name(stats.partners.favorite.second)}: {stats.partners.favorite.won} von{" "}
              {plural(stats.partners.favorite.games, "Spiel", "Spielen")} gewonnen,{" "}
              <Amount cents={stats.partners.favorite.balance} asMoney={asMoney} />
            </span>
          ) : (
            <span>noch niemand</span>
          )}
        </Fact>
      </ul>

      <SectionHeading>Weitere Zahlen</SectionHeading>
      <ul class="fact-list">
        <Fact label="Teuerstes Spiel">
          {history.mostExpensiveGame ? (
            <span>
              Spiel {history.mostExpensiveGame.gameNumber}
              {single ? "" : ` am ${formatEveningDate(evenings, history.mostExpensiveGame.eveningId)}`}: Spielwert{" "}
              {formatAmount(history.mostExpensiveGame.value, asMoney)}
            </span>
          ) : (
            <span>–</span>
          )}
        </Fact>
        <Fact label="Führungswechsel">
          <span>{history.leadChanges}</span>
        </Fact>
        <Fact label="Bockspiele / ausgelöste Bockrunden">
          <span>
            {history.bockGamesCount} / {history.bockTriggerCount}
          </span>
        </Fact>
        <Fact label="Zusammengeworfen">
          <span>{history.thrownInCount}</span>
        </Fact>
      </ul>

      <SectionHeading>Rekorde je Spieler</SectionHeading>
      <div class="table-scroll">
        <table class="stat-table">
          <thead>
            <tr>
              <th scope="col">Spieler</th>
              <th scope="col">größter Gewinn</th>
              <th scope="col">größter Verlust</th>
              <th scope="col">Siegesserie</th>
              <th scope="col">Schneider +/−</th>
            </tr>
          </thead>
          <tbody>
            {history.playerStats.map((stat) => (
              <tr key={stat.player}>
                <th scope="row">{name(stat.player)}</th>
                <td>{stat.biggestWin ? <Amount cents={stat.biggestWin.amount} asMoney={asMoney} /> : "–"}</td>
                <td>{stat.biggestLoss ? <Amount cents={stat.biggestLoss.amount} asMoney={asMoney} /> : "–"}</td>
                <td>{stat.longestWinStreak}</td>
                <td>
                  {stat.schneiderWon}/{stat.schneiderLost}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function formatEveningDate(evenings: readonly Evening[], id: string): string {
  const evening = evenings.find((it) => it.id === id);
  return evening ? formatDate(evening.startedAt) : "?";
}

export function StatisticsScreen({ eveningId }: { eveningId: string | null }) {
  const { router } = useServices();
  const { evenings } = useAppState();
  const evening = eveningId === null ? null : (evenings.find((it) => it.id === eveningId) ?? null);
  const stats = useMemo(() => {
    if (eveningId !== null) return evening ? StatisticEngine.forEvening(evening) : null;
    return StatisticEngine.forEvenings(evenings);
  }, [eveningId, evening, evenings]);
  // Euro oder Punkte: wie beim Abend; über alle Abende wie beim neuesten.
  const asMoney = (evening ?? evenings[0])?.rules.displayAsMoney ?? true;
  const title = eveningId === null ? "Statistik" : "Statistik dieses Abends";

  return (
    <div class="screen">
      <TopBar title={title} onBack={() => router.back()} />
      <main class="content">
        {eveningId !== null && !evening ? (
          <p class="empty">Diesen Abend gibt es nicht mehr.</p>
        ) : !stats || stats.gameCount === 0 ? (
          <p class="empty">Noch keine Spiele – die Statistik füllt sich mit dem ersten eingetragenen Spiel.</p>
        ) : (
          <>
            {eveningId === null ? <Hint>Alle Abende zusammen („ewige Tabelle“).</Hint> : null}
            <StatisticsContent stats={stats} evenings={evenings} asMoney={asMoney} single={eveningId !== null} />
          </>
        )}
      </main>
    </div>
  );
}
