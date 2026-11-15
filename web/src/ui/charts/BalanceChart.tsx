/*
 * Liniendiagramm „Vermögensverlauf“: der Kontostand je Spieler nach jedem Spiel. Gestaltet nach den Regeln für
 * Diagramme: dünne Linien (2 px), zurückhaltende Gitterlinien, eine einzige Achse, die Null deutlich, Namen direkt
 * am Linienende, Legende, Fadenkreuz mit allen Werten (Finger oder Maus, auch mit den Pfeiltasten) und eine Tabelle
 * als Alternative. Die Farben folgen dem Spieler, nie seinem Platz.
 */
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { seriesClass } from "../components";
import { formatAmount } from "../format";

export interface ChartSeries {
  readonly id: string;
  readonly name: string;
  readonly colorIndex: number;
  /** Ein Wert je Punkt; Punkt 0 ist der Start. */
  readonly values: readonly number[];
}

const DEFAULT_WIDTH = 360;
const HEIGHT = 260;
const MARGIN = { top: 14, right: 88, bottom: 30, left: 68 };
const LABEL_GAP = 16;

/** Runde Teilstriche für die y-Achse (höchstens etwa fünf). */
export function niceTicks(min: number, max: number, target = 4): number[] {
  if (min === max) {
    const pad = Math.max(1, Math.abs(min) * 0.1);
    min -= pad;
    max += pad;
  }
  const rough = (max - min) / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((it) => it * magnitude).find((it) => it >= rough) ?? 10 * magnitude;
  const ticks: number[] = [];
  for (let value = Math.floor(min / step) * step; value <= max + step * 1e-9; value += step) {
    ticks.push(Math.round(value * 1e6) / 1e6);
  }
  if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step);
  return ticks;
}

/** Schiebt Beschriftungen auseinander, damit sie sich nicht überlappen (Abstand mindestens `gap`). */
export function spreadLabels(positions: readonly number[], gap: number, min: number, max: number): number[] {
  const order = positions.map((y, index) => ({ y, index })).sort((a, b) => a.y - b.y);
  const placed = order.map((it) => it.y);
  for (let i = 1; i < placed.length; i++) placed[i] = Math.max(placed[i], placed[i - 1] + gap);
  const overflow = placed.length > 0 ? placed[placed.length - 1] - max : 0;
  if (overflow > 0) for (let i = 0; i < placed.length; i++) placed[i] -= overflow;
  for (let i = placed.length - 2; i >= 0; i--) placed[i] = Math.min(placed[i], placed[i + 1] - gap);
  if (placed.length > 0 && placed[0] < min) {
    const shift = min - placed[0];
    for (let i = 0; i < placed.length; i++) placed[i] += shift;
  }
  const result = new Array<number>(positions.length);
  order.forEach((it, rank) => {
    result[it.index] = placed[rank];
  });
  return result;
}

export function BalanceChart({
  series,
  asMoney,
  boundaries = [],
  pointLabel,
  title,
}: {
  series: readonly ChartSeries[];
  asMoney: boolean;
  /** Punkte, an denen ein neuer Abend beginnt (feine senkrechte Linien). */
  boundaries?: readonly number[];
  /** Beschriftung eines Punktes im Fadenkreuz, zum Beispiel „nach Spiel 7“. */
  pointLabel: (index: number) => string;
  title: string;
}) {
  const points = Math.max(0, ...series.map((it) => it.values.length));
  const last = Math.max(1, points - 1);
  const [active, setActive] = useState<number | null>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  // In echten Pixeln zeichnen, damit die Schrift auf jedem Bildschirm gleich groß bleibt.
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  const hasChart = points >= 2;
  useEffect(() => {
    const element = wrapper.current;
    if (!element) return;
    const measure = () => setWidth(Math.max(240, Math.round(element.getBoundingClientRect().width) || DEFAULT_WIDTH));
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasChart]);
  const WIDTH = width;

  const geometry = useMemo(() => {
    const all = series.flatMap((it) => it.values);
    const ticks = niceTicks(Math.min(0, ...all), Math.max(0, ...all));
    const yMin = ticks[0];
    const yMax = ticks[ticks.length - 1];
    const plotWidth = WIDTH - MARGIN.left - MARGIN.right;
    const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
    const x = (index: number) => MARGIN.left + (index / last) * plotWidth;
    const y = (value: number) => MARGIN.top + (1 - (value - yMin) / (yMax - yMin || 1)) * plotHeight;
    const ends = series.map((it) => y(it.values[it.values.length - 1] ?? 0));
    const labels = spreadLabels(ends, LABEL_GAP, MARGIN.top + 6, HEIGHT - MARGIN.bottom);
    return { ticks, x, y, plotWidth, plotHeight, labels };
  }, [series, last, WIDTH]);

  if (!hasChart) return <p class="hint">Der Verlauf erscheint nach dem ersten Spiel.</p>;

  const { ticks, x, y, labels } = geometry;
  const pathOf = (values: readonly number[]) =>
    values.map((value, index) => `${index === 0 ? "M" : "L"}${x(index).toFixed(1)},${y(value).toFixed(1)}`).join("");

  const pick = (clientX: number) => {
    const box = wrapper.current?.getBoundingClientRect();
    if (!box || box.width === 0) return;
    const svgX = ((clientX - box.left) / box.width) * WIDTH;
    const index = Math.round(((svgX - MARGIN.left) / (WIDTH - MARGIN.left - MARGIN.right)) * last);
    setActive(Math.min(last, Math.max(0, index)));
  };

  const readout =
    active === null
      ? null
      : series
          .map((it) => ({ ...it, value: it.values[Math.min(active, it.values.length - 1)] ?? 0 }))
          .sort((a, b) => b.value - a.value);
  const tooltipLeft = active === null ? 0 : (x(active) / WIDTH) * 100;

  return (
    <figure class="chart">
      <figcaption class="visually-hidden">{title}</figcaption>
      <div
        class="chart-plot"
        ref={wrapper}
        tabIndex={0}
        role="group"
        aria-label={`${title}. Mit den Pfeiltasten durch die Spiele gehen.`}
        onPointerDown={(event) => pick(event.clientX)}
        onPointerMove={(event) => pick(event.clientX)}
        onPointerLeave={(event) => {
          if (event.pointerType === "mouse") setActive(null);
        }}
        onKeyDown={(event) => {
          if (event.key === "ArrowRight") setActive((it) => Math.min(last, (it ?? -1) + 1));
          else if (event.key === "ArrowLeft") setActive((it) => Math.max(0, (it ?? last + 1) - 1));
          else if (event.key === "Escape") setActive(null);
          else return;
          event.preventDefault();
        }}
        onBlur={() => setActive(null)}
      >
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} width={WIDTH} height={HEIGHT} aria-hidden="true" focusable="false">
          {ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={MARGIN.left}
                x2={WIDTH - MARGIN.right}
                y1={y(tick)}
                y2={y(tick)}
                class={tick === 0 ? "chart-zero" : "chart-grid"}
              />
              <text x={MARGIN.left - 8} y={y(tick)} class="chart-tick" text-anchor="end" dominant-baseline="middle">
                {formatAmount(tick, asMoney)}
              </text>
            </g>
          ))}
          {boundaries
            .filter((it) => it > 0 && it < last)
            .map((it) => (
              <line key={`b${it}`} x1={x(it)} x2={x(it)} y1={MARGIN.top} y2={HEIGHT - MARGIN.bottom} class="chart-boundary" />
            ))}
          <text x={MARGIN.left} y={HEIGHT - 8} class="chart-tick" text-anchor="start">
            Start
          </text>
          <text x={WIDTH - MARGIN.right} y={HEIGHT - 8} class="chart-tick" text-anchor="end">
            {pointLabel(last)}
          </text>
          {series.map((it) => (
            <path key={it.id} d={pathOf(it.values)} class={`chart-line ${seriesClass(it.colorIndex)}`} />
          ))}
          {series.map((it, index) => {
            const lastIndex = it.values.length - 1;
            return (
              <g key={`end-${it.id}`} class={seriesClass(it.colorIndex)}>
                <circle cx={x(lastIndex)} cy={y(it.values[lastIndex] ?? 0)} r={4} class="chart-dot" />
                <text x={x(lastIndex) + 8} y={labels[index]} class="chart-label" dominant-baseline="middle">
                  {it.name.length > 10 ? `${it.name.slice(0, 9)}…` : it.name}
                </text>
              </g>
            );
          })}
          {active !== null ? (
            <g>
              <line x1={x(active)} x2={x(active)} y1={MARGIN.top} y2={HEIGHT - MARGIN.bottom} class="chart-crosshair" />
              {series.map((it) => (
                <circle
                  key={`a-${it.id}`}
                  cx={x(active)}
                  cy={y(it.values[Math.min(active, it.values.length - 1)] ?? 0)}
                  r={5}
                  class={`chart-dot ring ${seriesClass(it.colorIndex)}`}
                />
              ))}
            </g>
          ) : null}
        </svg>
        {readout ? (
          <div class={`chart-tooltip${tooltipLeft > 55 ? " left" : ""}`} style={{ left: `${tooltipLeft}%` }} role="status">
            <strong>{pointLabel(active ?? 0)}</strong>
            {readout.map((it) => (
              <span key={it.id} class="chart-tooltip-row">
                <span class={`legend-swatch ${seriesClass(it.colorIndex)}`} aria-hidden="true" />
                <span class="grow">{it.name}</span>
                <span class="amount">{formatAmount(it.value, asMoney, true)}</span>
              </span>
            ))}
          </div>
        ) : null}
      </div>
      <ul class="chart-legend" aria-label="Legende">
        {series.map((it) => (
          <li key={it.id}>
            <span class={`legend-swatch ${seriesClass(it.colorIndex)}`} aria-hidden="true" />
            {it.name}
          </li>
        ))}
      </ul>
    </figure>
  );
}
