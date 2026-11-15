/*
 * Einstieg für alle Auswertungen: ein einzelner Abend oder alle Abende zusammen, die „ewige Tabelle“
 * (Kotlin: `domain/stats/StatisticEngine.kt`).
 */
import { EveningCalculator } from "../evening/EveningCalculator";
import {
  compareNumbers,
  compareProducts,
  compareText,
  intAdd,
  intMul,
  intSub,
  intSum,
  mapValues,
  maxByOrNull,
  minWithOrNull,
} from "../kotlin";
import type { Evening, EveningId, GameType, PlayerId } from "../model";
import { GAME_TYPES } from "../model";
import { hasDeclarer, isSolo } from "../rules/RuleSet";
import type { PlayedGame } from "./EveningDigest";
import { EveningDigest, isPlayedGame } from "./EveningDigest";
import type { HistoryStats } from "./HistoryStatistics";
import { historyDigests, historyFromDigests, isPair } from "./HistoryStatistics";

/**
 * Eine Zeile der Tabelle: der Kontostand eines Spielers (Kotlin: `Standing`).
 *
 * @property balance Saldo in Cent, auch aus freien Buchungen.
 * @property rank Platz ab 1. Bei Gleichstand teilen sich Spieler einen Platz; der nächste Platz wird übersprungen.
 */
export interface Standing {
  readonly player: PlayerId;
  readonly balance: number;
  readonly rank: number;
}

/**
 * Die Finanz-Übersicht: Kontostände und Vermögensverlauf (Kotlin: `FinanceOverview`).
 *
 * @property standings Kontostand und Platz je Spieler, in der Reihenfolge von `Statistics.players`.
 * @property balanceSeries Der Vermögensverlauf je Spieler für das Liniendiagramm, in der Reihenfolge von
 *   `Statistics.players`. Jede Reihe hat `gameCount + 1` Punkte: Index 0 ist der Start bei 0, Index n der Stand in Cent
 *   nach dem n-ten abgerechneten Spiel, gezählt über alle Abende in zeitlicher Reihenfolge. Der letzte Punkt ist immer
 *   der Saldo aus `standings`. Freie Buchungen zählen zum nächsten Spiel desselben Abends; stehen sie hinter dem
 *   letzten Spiel des Abends, zählen sie zu dessen Punkt. Buchungen eines Abends ohne abgerechnetes Spiel zählen zum
 *   nächsten Spiel eines späteren Abends und, wenn keines mehr folgt, zum letzten Punkt (ohne ein einziges Spiel ist
 *   das der einzige Punkt der Reihe). Wer an einem Abend nicht am Tisch saß, bleibt dort waagerecht; einzige Ausnahme
 *   ist eine solche Buchung von einem Abend ohne Spiel, die im Punkt eines anderen Abends erscheint.
 * @property eveningStarts Wo im Verlauf ein Abend beginnt: der Index des Punktes vor seinem ersten Spiel. Die Spiele
 *   des Abends sind die Punkte dahinter bis einschließlich des nächsten Eintrags dieser Liste (oder bis zum Ende).
 *   Abende ohne abgerechnetes Spiel fehlen.
 * @property eveningIds Die Abende zu `eveningStarts`, in derselben (zeitlichen) Reihenfolge.
 * @property turnover Umsatz in Cent: die Summe aller positiven Beträge aller Einträge, auch der freien Buchungen.
 */
export interface FinanceOverview {
  readonly standings: readonly Standing[];
  readonly balanceSeries: ReadonlyMap<PlayerId, readonly number[]>;
  readonly eveningStarts: readonly number[];
  readonly eveningIds: readonly EveningId[];
  readonly turnover: number;
}

/** Kontostand und Platz eines Spielers (Kotlin: `FinanceOverview.standingOf`); `null`, wenn er nie am Tisch saß. */
export function standingOf(finance: FinanceOverview, player: PlayerId): Standing | null {
  return finance.standings.find((it) => it.player === player) ?? null;
}

/**
 * Bilanz eines Spielers in einer Spielart (Kotlin: `ModeRecord`).
 *
 * @property games Abgerechnete Spiele.
 * @property won Davon auf der Gewinnerseite.
 * @property balance Summe der eigenen Beträge aus diesen Spielen in Cent.
 * @property lost Wie viele dieser Spiele verloren gingen (abgeleitet).
 * @property winRate Anteil der Siege zwischen 0 und 1; `null` ohne Spiel (abgeleitet).
 * @property averageAmount Durchschnittlicher Gewinn (positiv) oder Verlust (negativ) je Spiel in ganzen Cent,
 *   kaufmännisch gerundet (ab einem halben Cent weg von der Null); `null` ohne Spiel (abgeleitet).
 */
export interface ModeRecord {
  readonly games: number;
  readonly won: number;
  readonly balance: number;
  readonly lost: number;
  readonly winRate: number | null;
  readonly averageAmount: number | null;
}

export function modeRecord(games: number, won: number, balance: number): ModeRecord {
  return {
    games,
    won,
    balance,
    lost: intSub(games, won),
    winRate: games === 0 ? null : won / games,
    averageAmount: games === 0 ? null : roundedAverage(balance, games),
  };
}

/**
 * Die Spielart-Analyse eines Spielers. Beide Aufstellungen enthalten jede Spielart (Reihenfolge wie `GAME_TYPES`),
 * notfalls mit 0 Spielen (Kotlin: `PlayerGameModes`).
 *
 * @property atTable Alle Spiele der Spielart, bei denen der Spieler am Tisch saß, egal in welcher Rolle.
 * @property asPlayer Nur selbst gespielte Spiele: als Ansager oder Mitspieler eines Rufspiels oder als Alleinspieler.
 *   Bei Über Kreuz sagt niemand an; dort ist es dasselbe wie `atTable`.
 */
export interface PlayerGameModes {
  readonly player: PlayerId;
  readonly atTable: ReadonlyMap<GameType, ModeRecord>;
  readonly asPlayer: ReadonlyMap<GameType, ModeRecord>;
}

/** Das Fazit zum Legen eines Spielers, augenzwinkernd gemeint. Die Texte dazu stehen in der Oberfläche. */
export type GelegtBadge =
  /** Hat nie gelegt. */
  | "STREICHHOLZ_SPARER"
  /** Hat erst ein- oder zweimal gelegt: zu wenig für ein Urteil. */
  | "NOCH_OFFEN"
  /** Gewinnt mindestens 60 % der gelegten Spiele und steht damit nicht im Minus. */
  | "SICHERE_BANK"
  /** Gewinnt höchstens 40 % der gelegten Spiele und steht damit im Minus: Er sollte es lassen. */
  | "BRANDSTIFTER"
  /** Weder sichere Bank noch Brandstifter, aber unterm Strich im Plus. */
  | "GLUECKSRITTER"
  /** Weder sichere Bank noch Brandstifter und unterm Strich nicht im Plus. */
  | "WACKELKANDIDAT";

/** Alle Abzeichen in der Reihenfolge des Kotlin-Enums. */
export const GELEGT_BADGES: readonly GelegtBadge[] = [
  "STREICHHOLZ_SPARER",
  "NOCH_OFFEN",
  "SICHERE_BANK",
  "BRANDSTIFTER",
  "GLUECKSRITTER",
  "WACKELKANDIDAT",
];

/** Ab so vielen gelegten Spielen gibt es ein Urteil. */
const MIN_GELEGT_GAMES = 3;

/** Was in Kotlin im `companion object` von `GelegtBadge` steht. */
export const GelegtBadge = {
  /** Ab so vielen gelegten Spielen gibt es ein Urteil. */
  MIN_GAMES: MIN_GELEGT_GAMES,

  /**
   * Das Abzeichen zu `count` gelegten Spielen, von denen `won` gewonnen wurden, mit der Netto-Bilanz `netBalance` in
   * Cent. Die Quoten werden mit ganzen Zahlen verglichen, damit die Schwellen (60 % und 40 %) exakt gelten. (Kotlin
   * rechnet dabei mit `Long`; die Produkte von Int-Werten mit 10, 6 oder 4 sind auch als JavaScript-Zahl exakt.)
   */
  of(count: number, won: number, netBalance: number): GelegtBadge {
    if (count <= 0) return "STREICHHOLZ_SPARER";
    if (count < MIN_GELEGT_GAMES) return "NOCH_OFFEN";
    if (won * 10 >= count * 6 && netBalance >= 0) return "SICHERE_BANK";
    if (won * 10 <= count * 4 && netBalance < 0) return "BRANDSTIFTER";
    if (netBalance > 0) return "GLUECKSRITTER";
    return "WACKELKANDIDAT";
  },
} as const;

/**
 * Der „Sollte-er-es-lassen?“-Index eines Spielers: was beim Legen (Klopfen) herauskommt (Kotlin: `GelegtRecord`).
 *
 * @property count In wie vielen abgerechneten Spielen der Spieler gelegt hat.
 * @property won Wie viele davon er gewonnen hat.
 * @property netBalance Netto-Bilanz in Cent: die Summe der eigenen Beträge aus den Spielen, in denen er gelegt hat.
 * @property effect Was das eigene Legen gekostet (negativ) oder gebracht hat (positiv), in Cent: je Spiel der gebuchte
 *   Betrag minus der Betrag desselben Spiels ohne den eigenen Leger, mit dem Regelwerk des Abends exakt nachgerechnet.
 * @property lost Wie viele der gelegten Spiele verloren gingen (abgeleitet).
 * @property winRate Gewinnquote der gelegten Spiele zwischen 0 und 1; `null`, wenn er nie gelegt hat (abgeleitet).
 * @property badge Das Abzeichen (siehe `GelegtBadge.of`, abgeleitet).
 * @property shouldLeaveIt Die Antwort auf „Sollte er es lassen?“: nur beim Brandstifter ja (abgeleitet).
 */
export interface GelegtRecord {
  readonly player: PlayerId;
  readonly count: number;
  readonly won: number;
  readonly netBalance: number;
  readonly effect: number;
  readonly lost: number;
  readonly winRate: number | null;
  readonly badge: GelegtBadge;
  readonly shouldLeaveIt: boolean;
}

export function gelegtRecord(player: PlayerId, count: number, won: number, netBalance: number, effect: number): GelegtRecord {
  const badge = GelegtBadge.of(count, won, netBalance);
  return {
    player,
    count,
    won,
    netBalance,
    effect,
    lost: intSub(count, won),
    winRate: count === 0 ? null : won / count,
    badge,
    shouldLeaveIt: badge === "BRANDSTIFTER",
  };
}

/**
 * Bilanz eines Spielers aus den Bockspielen (Kotlin: `BockRecord`).
 *
 * @property games Abgerechnete Bockspiele am Tisch.
 * @property won Davon auf der Gewinnerseite.
 * @property balance Summe der eigenen Beträge aus diesen Spielen in Cent.
 * @property winRate Anteil der Siege zwischen 0 und 1; `null` ohne Bockspiel (abgeleitet).
 */
export interface BockRecord {
  readonly player: PlayerId;
  readonly games: number;
  readonly won: number;
  readonly balance: number;
  readonly winRate: number | null;
}

export function bockRecord(player: PlayerId, games: number, won: number, balance: number): BockRecord {
  return { player, games, won, balance, winRate: games === 0 ? null : won / games };
}

/**
 * Wer in Bockspielen am meisten gewinnt (Kotlin: `BockOverview`).
 *
 * @property records Bilanz je Spieler, in der Reihenfolge von `Statistics.players`.
 * @property king Der Bock-König: der Spieler mit dem höchsten Saldo aus Bockspielen, aber nur, wenn dieser über 0
 *   liegt. Bei Gleichstand der, der in `Statistics.players` weiter vorn steht.
 */
export interface BockOverview {
  readonly records: readonly BockRecord[];
  readonly king: BockRecord | null;
}

/**
 * Was ein Spieler ansagt (Kotlin: `SoloRecord`).
 *
 * @property solos Einzelspiele (Farbsolo, Wenz, Geier) als Alleinspieler.
 * @property calls Rufspiele als Ansager. Über Kreuz zählt nicht, weil dort niemand ansagt.
 * @property announced Alle angesagten Spiele: Einzelspiele und Rufspiele zusammen (abgeleitet).
 * @property soloShare Anteil der Einzelspiele an den angesagten Spielen zwischen 0 und 1; `null`, wenn der Spieler nie
 *   angesagt hat (abgeleitet).
 */
export interface SoloRecord {
  readonly player: PlayerId;
  readonly solos: number;
  readonly calls: number;
  readonly announced: number;
  readonly soloShare: number | null;
}

export function soloRecord(player: PlayerId, solos: number, calls: number): SoloRecord {
  const announced = intAdd(solos, calls);
  return { player, solos, calls, announced, soloShare: announced === 0 ? null : solos / announced };
}

/**
 * Solo-Spezialist und Mauerblümchen (Kotlin: `SoloOverview`).
 *
 * @property records Die Ansagen je Spieler, in der Reihenfolge von `Statistics.players`.
 * @property specialist Der Solo-Spezialist: wer die meisten Einzelspiele gespielt hat (mindestens eines). Bei
 *   Gleichstand der, der in `Statistics.players` weiter vorn steht.
 * @property wallflower Das Mauerblümchen, das fast nur ruft: unter den Spielern mit mindestens
 *   `StatisticEngine.MIN_ANNOUNCED_GAMES` angesagten Spielen (der Spezialist ausgenommen) der mit dem kleinsten
 *   Solo-Anteil, aber nur, wenn dieser höchstens 20 % beträgt. Bei gleichem Anteil der mit mehr angesagten Spielen,
 *   danach der, der in `Statistics.players` weiter vorn steht.
 */
export interface SoloOverview {
  readonly records: readonly SoloRecord[];
  readonly specialist: SoloRecord | null;
  readonly wallflower: SoloRecord | null;
}

/**
 * Die Bilanz eines Spielers aus `BockOverview.records` oder `SoloOverview.records` (Kotlin: `recordOf`); `null`,
 * wenn er an keinem der Abende am Tisch saß.
 */
export function recordOf<R extends { readonly player: PlayerId }>(
  overview: { readonly records: readonly R[] },
  player: PlayerId,
): R | null {
  return overview.records.find((it) => it.player === player) ?? null;
}

/**
 * Bilanz eines Zweiergespanns aus den Spielen zu zweit gegen zwei (Rufspiel und Über Kreuz), in denen beide auf
 * derselben Seite standen, egal ob als Spieler- oder als Gegenspielerpaar. Das Gespann ist ungeordnet; `first` ist
 * der Spieler mit der kleineren Kennung (Kotlin: `PartnerPair`; `isPair` aus `HistoryStatistics.ts` gilt auch hier).
 *
 * @property games Gemeinsame abgerechnete Spiele.
 * @property won Davon gewonnen.
 * @property balance Gemeinsamer Saldo dieser Spiele in Cent: die Summe der Beträge beider Spieler.
 * @property winRate Anteil der Siege zwischen 0 und 1; `null` ohne gemeinsames Spiel (abgeleitet).
 */
export interface PartnerPair {
  readonly first: PlayerId;
  readonly second: PlayerId;
  readonly games: number;
  readonly won: number;
  readonly balance: number;
  readonly winRate: number | null;
}

export function partnerPair(first: PlayerId, second: PlayerId, games: number, won: number, balance: number): PartnerPair {
  return { first, second, games, won, balance, winRate: games === 0 ? null : won / games };
}

/**
 * Die Zweiergespanne (Kotlin: `PartnerOverview`).
 *
 * @property pairs Alle Gespanne mit mindestens einem gemeinsamen Spiel: höchster gemeinsamer Saldo zuerst, bei
 *   Gleichstand die höhere Gewinnquote, danach nach den Kennungen.
 * @property favorite Die Lieblingspartner, das erfolgreichste Gespann: das erste Gespann in `pairs` mit mindestens
 *   `StatisticEngine.MIN_PAIR_GAMES` gemeinsamen Spielen, aber nur, wenn sein gemeinsamer Saldo über 0 liegt; sonst
 *   `null`. (Mit wechselnder Besetzung kann das einzige Gespann mit genug Spielen eines im Minus sein.)
 */
export interface PartnerOverview {
  readonly pairs: readonly PartnerPair[];
  readonly favorite: PartnerPair | null;
}

/** Die Bilanz zweier Spieler als Gespann (Kotlin: `PartnerOverview.pairOf`); `null`, wenn sie nie zusammen spielten. */
export function pairOf(overview: PartnerOverview, a: PlayerId, b: PlayerId): PartnerPair | null {
  return overview.pairs.find((pair) => isPair(pair, a, b)) ?? null;
}

/**
 * Alle Auswertungen für einen Abend oder für viele Abende („ewige Tabelle“, Kotlin: `Statistics`). Beträge sind ganze
 * Cent; Quoten sind `null`, wenn es keinen einzigen Fall gab. Gezählt werden nur Spiele, die abgerechnet werden
 * konnten.
 *
 * Ein Abend ohne abgerechnetes Spiel gilt nicht als gespielter Abend. Was dort trotzdem gebucht wurde (freie
 * Buchungen), steht aber im Kontostand, damit kein Betrag verloren geht.
 *
 * @property eveningCount Abende mit mindestens einem abgerechneten Spiel.
 * @property gameCount Abgerechnete Spiele über alle Abende.
 * @property players Alle Spieler, die an mindestens einem der Abende am Tisch saßen. Für einen einzelnen Abend
 *   (`StatisticEngine.forEvening`) in Sitzreihenfolge, sonst höchster Saldo zuerst und bei Gleichstand nach Kennung.
 *   Alle Listen „je Spieler“ haben diese Reihenfolge; nur `history.playerStats` steht immer nach Saldo.
 * @property finance Finanz-Übersicht: Kontostände und Vermögensverlauf.
 * @property gameModes Spielart-Analyse je Spieler.
 * @property gelegt „Sollte-er-es-lassen?“-Index je Spieler.
 * @property bock Bilanz der Bockspiele und der Bock-König.
 * @property solo Solo-Spezialist und Mauerblümchen.
 * @property partners Die Zweiergespanne und die Lieblingspartner.
 * @property history Die übrigen Kennzahlen (Rollen, Serien, Schneider, teuerstes Spiel und so weiter). Ihre
 *   Spielerliste steht immer nach Saldo absteigend, auch für einen einzelnen Abend.
 */
export interface Statistics {
  readonly eveningCount: number;
  readonly gameCount: number;
  readonly players: readonly PlayerId[];
  readonly finance: FinanceOverview;
  readonly gameModes: readonly PlayerGameModes[];
  readonly gelegt: readonly GelegtRecord[];
  readonly bock: BockOverview;
  readonly solo: SoloOverview;
  readonly partners: PartnerOverview;
  readonly history: HistoryStats;
}

/** Die Spielart-Analyse eines Spielers (Kotlin: `Statistics.gameModesOf`); `null`, wenn er nie am Tisch saß. */
export function gameModesOf(statistics: Statistics, player: PlayerId): PlayerGameModes | null {
  return statistics.gameModes.find((it) => it.player === player) ?? null;
}

/** Der „Sollte-er-es-lassen?“-Index eines Spielers (Kotlin: `Statistics.gelegtOf`); `null`, wenn er nie am Tisch saß. */
export function gelegtOf(statistics: Statistics, player: PlayerId): GelegtRecord | null {
  return statistics.gelegt.find((it) => it.player === player) ?? null;
}

/** Ab so vielen angesagten Spielen kann jemand das Mauerblümchen sein. */
const MIN_ANNOUNCED_GAMES = 3;

/** Ab so vielen gemeinsamen Spielen kann ein Gespann zu den Lieblingspartnern werden. */
const MIN_PAIR_GAMES = 3;

/** Das Mauerblümchen spielt höchstens jedes fünfte seiner angesagten Spiele allein (20 %). */
const WALLFLOWER_SHARE_DIVISOR = 5;

/**
 * Teilt `sum` durch `count` und rundet kaufmännisch: ab der Hälfte weg von der Null. `count` ist größer als 0.
 * Kotlin rechnet dabei mit `Long`; alle Zwischenwerte liegen unter 2^34 und sind als JavaScript-Zahl exakt, und der
 * Quotient zweier solcher Zahlen rutscht beim Runden nie über eine ganze Zahl (der Abstand zur nächsten ganzen Zahl
 * ist mindestens 1/(2 count), viel mehr als die Rundung), sodass `Math.trunc` genau die Ganzzahl-Division ergibt.
 */
function roundedAverage(sum: number, count: number): number {
  const rounded = Math.trunc((2 * Math.abs(sum) + count) / (2 * count));
  return sum < 0 ? -rounded | 0 : rounded | 0;
}

/**
 * Kleinster Solo-Anteil zuerst, bei gleichem Anteil der mit mehr angesagten Spielen. Die Anteile werden als Brüche
 * über Kreuz verglichen, also ohne Rundung.
 */
function bySoloShare(a: SoloRecord, b: SoloRecord): number {
  return compareProducts(a.solos, b.announced, b.solos, a.announced) || compareNumbers(b.announced, a.announced);
}

/**
 * Höchster gemeinsamer Saldo zuerst, dann die höhere Gewinnquote (als Brüche über Kreuz verglichen, also ohne
 * Rundung), dann nach den Kennungen. Jedes Gespann hat mindestens ein Spiel.
 */
function pairOrder(a: PartnerPair, b: PartnerPair): number {
  return (
    compareNumbers(b.balance, a.balance) ||
    compareProducts(b.won, a.games, a.won, b.games) ||
    compareText(a.first, b.first) ||
    compareText(a.second, b.second)
  );
}

/** Zählt Spiele, Siege und Beträge eines Spielers in einer Spielart. */
class ModeTally {
  private games = 0;
  private won = 0;
  private balance = 0;

  add(game: PlayedGame, player: PlayerId): void {
    this.games += 1;
    if (game.won(player)) this.won += 1;
    this.balance = intAdd(this.balance, game.amount(player));
  }

  toRecord(): ModeRecord {
    return modeRecord(this.games, this.won, this.balance);
  }
}

function gameModes(player: PlayerId, games: readonly PlayedGame[]): PlayerGameModes {
  const atTable = new Map(GAME_TYPES.map((type) => [type, new ModeTally()]));
  const asPlayer = new Map(GAME_TYPES.map((type) => [type, new ModeTally()]));
  for (const game of games) {
    atTable.get(game.entry.type)?.add(game, player);
    if (game.playedBy(player)) asPlayer.get(game.entry.type)?.add(game, player);
  }
  return {
    player,
    atTable: mapValues(atTable, (tally) => tally.toRecord()),
    asPlayer: mapValues(asPlayer, (tally) => tally.toRecord()),
  };
}

function gelegt(player: PlayerId, games: readonly PlayedGame[]): GelegtRecord {
  const own = games.filter((game) => game.entry.klopfer.includes(player));
  return gelegtRecord(
    player,
    own.length,
    own.filter((game) => game.won(player)).length,
    intSum(own, (game) => game.amount(player)),
    intSum(own, (game) => game.klopferEffect(player)),
  );
}

function bockRecordOf(player: PlayerId, games: readonly PlayedGame[]): BockRecord {
  const bockGames = games.filter((game) => game.isBockGame);
  return bockRecord(
    player,
    bockGames.length,
    bockGames.filter((game) => game.won(player)).length,
    intSum(bockGames, (game) => game.amount(player)),
  );
}

// `maxByOrNull` liefert bei Gleichstand den ersten Treffer, also den, der in der Spielerliste weiter vorn steht.
function bock(records: readonly BockRecord[]): BockOverview {
  return {
    records,
    king: maxByOrNull(
      records.filter((record) => record.balance > 0),
      (record) => record.balance,
    ),
  };
}

function soloRecordOf(player: PlayerId, games: readonly PlayedGame[]): SoloRecord {
  const announced = games.filter((game) => hasDeclarer(game.entry.type) && game.entry.declarer === player);
  return soloRecord(
    player,
    announced.filter((game) => isSolo(game.entry.type)).length,
    announced.filter((game) => !isSolo(game.entry.type)).length,
  );
}

function solo(records: readonly SoloRecord[]): SoloOverview {
  const specialist = maxByOrNull(
    records.filter((record) => record.solos > 0),
    (record) => record.solos,
  );
  const candidate = minWithOrNull(
    records.filter(
      (record) =>
        record.announced >= MIN_ANNOUNCED_GAMES && (specialist === null || record.player !== specialist.player),
    ),
    bySoloShare,
  );
  const wallflower =
    candidate !== null && intMul(candidate.solos, WALLFLOWER_SHARE_DIVISOR) <= candidate.announced ? candidate : null;
  return { records, specialist, wallflower };
}

/** Zählt die gemeinsamen Spiele eines Gespanns. */
class PairTally {
  games = 0;
  won = 0;
  balance = 0;

  add(game: PlayedGame, first: PlayerId, second: PlayerId): void {
    this.games += 1;
    if (game.won(first)) this.won += 1;
    this.balance = intAdd(this.balance, intAdd(game.amount(first), game.amount(second)));
  }
}

function partners(digests: readonly EveningDigest[]): PartnerOverview {
  // Schlüssel ist das Gespann als JSON-Text; so kann keine Kennung zwei Gespanne verwechseln.
  const tallies = new Map<string, { readonly first: PlayerId; readonly second: PlayerId; readonly tally: PairTally }>();
  for (const digest of digests) {
    for (const game of digest.games) {
      if (isSolo(game.entry.type)) continue;
      const declarers = digest.seating.filter((player) => game.declarerSide.has(player));
      const opponents = digest.seating.filter((player) => !game.declarerSide.has(player));
      if (declarers.length !== 2 || opponents.length !== 2) continue;
      for (const side of [declarers, opponents]) {
        const [first, second] = [...side].sort(compareText);
        const key = JSON.stringify([first, second]);
        let entry = tallies.get(key);
        if (entry === undefined) {
          entry = { first, second, tally: new PairTally() };
          tallies.set(key, entry);
        }
        entry.tally.add(game, first, second);
      }
    }
  }
  const pairs = [...tallies.values()]
    .map(({ first, second, tally }) => partnerPair(first, second, tally.games, tally.won, tally.balance))
    .sort(pairOrder);
  // Die Liste steht nach Saldo: Ist das erste Gespann mit genug Spielen nicht im Plus, ist es keines.
  const favorite = pairs.find((pair) => pair.games >= MIN_PAIR_GAMES) ?? null;
  return { pairs, favorite: favorite !== null && favorite.balance > 0 ? favorite : null };
}

function finance(digests: readonly EveningDigest[], players: readonly PlayerId[]): FinanceOverview {
  const current = new Map<PlayerId, number>();
  for (const player of players) current.set(player, 0);
  const points: ReadonlyMap<PlayerId, number>[] = [new Map(current)];
  const eveningStarts: number[] = [];
  const eveningIds: EveningId[] = [];

  for (const digest of digests) {
    const hasGames = digest.games.length > 0;
    if (hasGames) {
      eveningStarts.push(points.length - 1);
      eveningIds.push(digest.eveningId);
    }
    for (const result of digest.state.results) {
      for (const [player, amount] of result.amounts) current.set(player, intAdd(current.get(player) ?? 0, amount));
      if (isPlayedGame(result)) points.push(new Map(current));
    }
    // Freie Buchungen hinter dem letzten Spiel des Abends zählen zu dessen Punkt.
    if (hasGames) points[points.length - 1] = new Map(current);
  }
  // Buchungen von Abenden ohne Spiel, auf die kein Spiel mehr folgt: Der letzte Punkt ist immer der Saldo.
  points[points.length - 1] = new Map(current);

  const balances = [...current.values()];
  return {
    standings: players.map((player) => {
      const balance = current.get(player) ?? 0;
      return { player, balance, rank: 1 + balances.filter((it) => it > balance).length };
    }),
    balanceSeries: new Map(players.map((player) => [player, points.map((point) => point.get(player) ?? 0)])),
    eveningStarts,
    eveningIds,
    turnover: intSum(digests, (digest) => digest.turnover),
  };
}

/**
 * @param digests Die durchgerechneten Abende in zeitlicher Reihenfolge, jeder genau einmal.
 * @param players Alle Spieler dieser Abende in der Reihenfolge, in der sie erscheinen sollen.
 */
function build(digests: readonly EveningDigest[], players: readonly PlayerId[]): Statistics {
  // Je Spieler die Spiele der Abende, an denen er am Tisch saß.
  const gamesOf = new Map(
    players.map((player) => [
      player,
      digests.filter((digest) => digest.seating.includes(player)).flatMap((digest) => digest.games),
    ]),
  );
  const games = (player: PlayerId): readonly PlayedGame[] => gamesOf.get(player) ?? [];
  return {
    eveningCount: digests.filter((digest) => digest.games.length > 0).length,
    gameCount: intSum(digests, (digest) => digest.games.length),
    players,
    finance: finance(digests, players),
    gameModes: players.map((player) => gameModes(player, games(player))),
    gelegt: players.map((player) => gelegt(player, games(player))),
    bock: bock(players.map((player) => bockRecordOf(player, games(player)))),
    solo: solo(players.map((player) => soloRecordOf(player, games(player)))),
    partners: partners(digests),
    history: historyFromDigests(digests),
  };
}

/**
 * Einstieg für alle Auswertungen: ein einzelner Abend oder alle Abende zusammen („ewige Tabelle“).
 *
 * Reine Funktionen: Nichts wird gespeichert, dieselbe Eingabe ergibt immer dasselbe Ergebnis. Jeder Abend wird mit
 * dem `EveningCalculator` neu durchgerechnet, mit seinem eigenen Regelwerk.
 */
export const StatisticEngine = {
  MIN_ANNOUNCED_GAMES,
  MIN_PAIR_GAMES,

  /** Die Auswertung eines einzelnen Abends. Die Spieler stehen in Sitzreihenfolge. */
  forEvening(evening: Evening): Statistics {
    const digest = new EveningDigest(EveningCalculator.compute(evening));
    return build([digest], digest.seating);
  },

  /**
   * Die Auswertung über viele Abende. Die Reihenfolge der Liste spielt keine Rolle (ausgewertet wird zeitlich
   * geordnet, bei gleichem Beginn nach der Kennung), derselbe Abend doppelt zählt nur einmal. Jede Kennung darf nur
   * einen Abend bezeichnen; von zwei verschiedenen Fassungen unter einer Kennung zählt die zuerst übergebene. Die
   * Spieler stehen nach Saldo absteigend, bei Gleichstand nach Kennung; das gilt auch, wenn die Liste nur einen Abend
   * enthält.
   */
  forEvenings(evenings: readonly Evening[]): Statistics {
    const digests = historyDigests(evenings);
    const balances = new Map<PlayerId, number>();
    for (const digest of digests) {
      for (const player of digest.seating) {
        balances.set(player, intAdd(balances.get(player) ?? 0, digest.balance(player)));
      }
    }
    const players = [...balances.keys()].sort(
      (a, b) => compareNumbers(balances.get(b) ?? 0, balances.get(a) ?? 0) || compareText(a, b),
    );
    return build(digests, players);
  },
} as const;
