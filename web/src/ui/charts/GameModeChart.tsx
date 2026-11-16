/*
 * Spielarten-Analyse als Balkendiagramm (wie `GameModeChart.kt` der Android-App): je Spielart eine Gruppe, je Spieler
 * eine Zeile mit Name, Balken, Durchschnitt je Spiel und Gewinnquote. Der Balken wächst von der Null in der Mitte
 * nach rechts (Gewinn) oder nach links (Verlust). Jede Spielart hat ihren eigenen Maßstab, weil ein Solo ein
 * Vielfaches eines Rufspiels bringt; verglichen wird innerhalb einer Spielart. Gebaut als Tabelle: Screenreader lesen jede Zeile mit ihren Spaltennamen. Der Balken ist nur Bild; jede Zahl steht
 * als Text daneben, und die Farbe kennzeichnet nur den Spieler.
 */
import type { GameType, PlayerId } from "../../core/model";
import { GAME_TYPES } from "../../core/model";
import type { ModeRecord, Statistics } from "../../core/stats/StatisticEngine";
import { Amount, PlayerName, seriesClass } from "../components";
import { formatPercent, GAME_TYPE_LABELS, plural } from "../format";

/** Ein Spieler in einer Spielart; ohne Spiel (oder `null`) heißt: nicht selbst gespielt. */
export interface ModeChartRow {
  readonly id: PlayerId;
  readonly name: string;
  readonly colorIndex: number;
  readonly record: ModeRecord | null;
}

/** Eine Spielart im Diagramm mit der Zahl ihrer Spiele und einer Zeile je Spieler. */
export interface ModeChartGroup {
  readonly type: GameType;
  readonly title: string;
  readonly games: number;
  readonly rows: readonly ModeChartRow[];
}

/**
 * Skala einer Spielart in Cent: die Null in der Mitte, nach beiden Seiten so weit wie der größte Betrag, egal ob
 * Gewinn oder Verlust (wie `BarScale` der Android-App).
 */
export function barScale(values: readonly number[]): number {
  return Math.max(0, ...values.map((it) => Math.abs(it)));
}

/**
 * Länge des Balkens für `value` in Prozent der ganzen Breite, von der Null in der Mitte aus: beim größten Betrag 50,
 * bei 0 (oder ohne Skala) 0. Ein Wert außerhalb der Skala reicht nur bis an den Rand.
 */
export function barPercent(value: number, max: number): number {
  if (value === 0 || max <= 0) return 0;
  return Math.min(1, Math.abs(value) / max) * 50;
}

/** Die Spiele eines Spielers, sofern es welche gab. */
export function played(row: ModeChartRow): ModeRecord | null {
  return row.record !== null && row.record.games > 0 ? row.record : null;
}

/**
 * Die Gruppen aus der Statistik: nur Spielarten, die gespielt wurden, in der festen Reihenfolge; je Gruppe alle
 * Spieler der Statistik mit ihren selbst gespielten Spielen.
 */
export function modeChartGroups(
  stats: Statistics,
  name: (id: PlayerId) => string,
  colorIndex: (id: PlayerId) => number,
): ModeChartGroup[] {
  return GAME_TYPES.filter((type) => (stats.history.gameTypeCounts.get(type) ?? 0) > 0).map((type) => ({
    type,
    title: GAME_TYPE_LABELS[type],
    games: stats.history.gameTypeCounts.get(type) ?? 0,
    rows: stats.gameModes.map((modes) => ({
      id: modes.player,
      name: name(modes.player),
      colorIndex: colorIndex(modes.player),
      record: modes.asPlayer.get(type) ?? null,
    })),
  }));
}

export function GameModeChart({ groups, asMoney }: { groups: readonly ModeChartGroup[]; asMoney: boolean }) {
  return (
    <div class="table-scroll">
      <table class="stat-table mode-chart">
        <colgroup>
          <col class="mode-col-player" />
          <col />
          <col class="mode-col-rate" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">Spieler</th>
            <th scope="col">Ø je Spiel</th>
            <th scope="col">gewonnen</th>
          </tr>
        </thead>
        {groups.map((group) => {
          const max = barScale(group.rows.flatMap((row) => {
            const average = played(row)?.averageAmount;
            return average === null || average === undefined ? [] : [average];
          }));
          return (
          <tbody key={group.type}>
            <tr class="mode-group">
              <th scope="rowgroup" colSpan={3}>
                {group.title} <span class="mode-group-games">· {plural(group.games, "Spiel", "Spiele")}</span>
              </th>
            </tr>
            {group.rows.map((row) => {
              const record = played(row);
              const average = record?.averageAmount ?? null;
              const length = average === null ? 0 : barPercent(average, max);
              return (
                <tr key={row.id}>
                  <th scope="row">
                    <PlayerName name={row.name} colorIndex={row.colorIndex} />
                  </th>
                  <td class="mode-average">
                    <div class="mode-average-inner">
                      <div class="mode-track" aria-hidden="true">
                        {average !== null && length > 0 ? (
                          // Rund nur am Ende des Wertes, gerade an der Null; ein Gewinn wächst nach rechts.
                          <span
                            class={`mode-bar ${average > 0 ? "gain" : "loss"} ${seriesClass(row.colorIndex)}`}
                            style={{ width: `${length}%` }}
                          />
                        ) : null}
                        <span class="mode-zero" />
                      </div>
                      <span class="mode-value">
                        {average === null ? (
                          <>
                            <span aria-hidden="true">–</span>
                            <span class="visually-hidden">nicht selbst gespielt</span>
                          </>
                        ) : (
                          <Amount cents={average} asMoney={asMoney} />
                        )}
                      </span>
                    </div>
                  </td>
                  <td class="mode-rate">
                    {record === null ? (
                      "–"
                    ) : (
                      <>
                        <span class="mode-rate-value">{formatPercent(record.winRate)}</span>
                        <span class="mode-count">
                          {record.won}/{record.games}
                        </span>
                      </>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
          );
        })}
      </table>
    </div>
  );
}
