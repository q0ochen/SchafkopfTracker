/*
 * Verbindet dieses Gerät mit einer gemeinsamen Runde und trennt es wieder (docs/ARCHITEKTUR.md, Abschnitt 8.7).
 * Port von `domain/round/RoundConnection.kt`.
 *
 * Die Aufrufe, die mit dem Server reden, können mit einer `RoundException` scheitern; dann ist auf dem Gerät nichts
 * verändert, außer es steht ausdrücklich dabei.
 */
import { involves } from "../evening/Evening";
import { kotlinTrim } from "../kotlin";
import type { Player, PlayerId } from "../model";
import { nextColorIndex } from "../players";
import { samePlayer } from "./equality";
import { RoundException, roundLink, type RoundId, type RoundLink, type RoundSummary } from "./RoundModels";
import type { EveningRepository, IdGenerator, PlayerRepository, RoundApi, RoundStore } from "./RoundPorts";
import { SyncOutcome, type RoundSync } from "./RoundSync";

/** Den Stand `rev = 1` hat eine Runde nur unmittelbar nach dem Anlegen. */
const FRESH_REV = 1;

/** Wie Javas „\s+“: Folgen von ASCII-Leerraum. */
const WHITESPACE = /[ \t\n\v\f\r]+/g;

export class RoundConnection {
  constructor(
    private readonly api: RoundApi,
    private readonly store: RoundStore,
    private readonly players: PlayerRepository,
    private readonly evenings: EveningRepository,
    private readonly sync: RoundSync,
    private readonly ids: IdGenerator,
  ) {}

  /**
   * Ob auf dem Gerät etwas liegt, das beim Beitreten ersetzt würde: Abende oder andere Spieler als die unberührten
   * vorbelegten. Dann fragt die Oberfläche vorher nach.
   *
   * @param placeholders Die vorbelegten Spieler, wie die App sie beim ersten Start anlegt.
   */
  async hasOwnData(placeholders: readonly Player[]): Promise<boolean> {
    if ((await this.evenings.evenings()).length > 0) return true;
    return (await this.players.players()).some((player) => !isPlaceholder(player, placeholders));
  }

  /**
   * Legt eine Runde an und bringt die Daten dieses Geräts mit: Alle Abende und Spieler gehen beim ersten Abgleich
   * hoch. Vorbelegte Spieler, die noch niemand umbenannt hat und die in keinem Abend vorkommen, entfallen vorher.
   * Hat das Konto schon eine eigene, noch leere Runde dieses Namens (die Antwort auf einen früheren Versuch ging
   * verloren), wird sie verwendet statt eine zweite anzulegen.
   *
   * @param userId Das angemeldete Konto.
   */
  async create(name: string, userId: string, placeholders: readonly Player[]): Promise<SyncOutcome> {
    await this.sync.exclusive(async () => {
      const round = (await this.emptyOwnRound(name)) ?? (await this.api.createRound(name));
      const all = await this.evenings.evenings();
      const untouched = (await this.players.players()).filter(
        (player) => isPlaceholder(player, placeholders) && !all.some((evening) => involves(evening, player.id)),
      );
      for (const player of untouched) await this.players.delete(player.id);
      await this.store.clearBases();
      await this.store.setLink(roundLink({ roundId: round, roundName: kotlinTrim(name), userId, isCreator: true }));
    });
    return this.sync.sync();
  }

  /**
   * Tritt der Runde hinter dem Einladungscode bei und übernimmt deren Daten. **Abende und Spieler dieses Geräts
   * werden dabei gelöscht** (vorher nachfragen, siehe {@link hasOwnData}) – aber erst, wenn der Server die Runde
   * gezeigt hat. Antwortet er nicht, bleibt das Gerät, wie es ist.
   */
  async join(token: string, userId: string): Promise<SyncOutcome> {
    const info = await this.api.inviteInfo(token);
    const round = await this.api.joinRound(token);
    return this.attach(roundLink({ roundId: round, roundName: info.roundName, userId }));
  }

  /**
   * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy).
   * **Abende und Spieler dieses Geräts werden dabei gelöscht**, sobald der Server die Runde gezeigt hat; gibt es sie
   * für dieses Konto nicht (mehr), scheitert der Aufruf mit `NOT_MEMBER`, und das Gerät bleibt, wie es ist.
   */
  use(round: RoundSummary, userId: string): Promise<SyncOutcome> {
    return this.attach(
      roundLink({ roundId: round.id, roundName: round.name, userId, playerId: round.myPlayerId, isCreator: round.isCreator }),
    );
  }

  private async attach(link: RoundLink): Promise<SyncOutcome> {
    // Erst fragen, dann leeren: Ohne Antwort des Servers bleibt das Gerät, wie es ist. Sonst stünde man ohne Netz mit
    // einem leeren Gerät da, das schon mit der Runde verbunden ist.
    if ((await this.api.head(link.roundId)) === null) throw new RoundException("NOT_MEMBER");
    await this.sync.exclusive(async () => {
      // Erst Verbindung und Basen weg, dann die Daten, dann verbinden. Mit Basen sähe das Leeren aus wie Löschen und
      // ginge beim nächsten Abgleich als Löschung in die Runde hoch; und bräche es vor dem Verbinden ab, ginge der Rest
      // sonst als neu hoch.
      await this.store.setLink(null);
      await this.store.clearBases();
      for (const evening of await this.evenings.evenings()) await this.evenings.delete(evening.id);
      for (const player of await this.players.players()) await this.players.delete(player.id);
      await this.store.setLink(link);
    });
    return this.sync.sync();
  }

  /**
   * Eine eigene, noch leere Runde dieses Namens. Sie stammt von einem Versuch, dessen Antwort verloren ging; ohne
   * diese Prüfung legte der zweite Versuch eine zweite Runde an, und die erste bliebe für immer leer liegen.
   */
  private async emptyOwnRound(name: string): Promise<RoundId | null> {
    const wanted = kotlinTrim(name).replace(WHITESPACE, " ");
    const rounds = await this.api.myRounds();
    return rounds.find((it) => it.isCreator && it.memberCount === 1 && it.rev === FRESH_REV && it.name === wanted)?.id ?? null;
  }

  /**
   * „Das bin ich“: Das Konto übernimmt einen Spieler ohne Besitzer oder legt sich neu an und legt seinen Namen fest.
   * Danach lässt sich der Name nicht mehr ändern.
   *
   * @param player Der gewählte Spieler; `null` für „Ich bin neu“.
   */
  async claim(player: PlayerId | null, name: string): Promise<SyncOutcome> {
    const link = await this.store.link();
    if (link === null) return SyncOutcome.NotLinked;
    const existing = await this.players.players();
    const id = player ?? this.ids.newId();
    const colorIndex = existing.find((it) => it.id === id)?.colorIndex ?? nextColorIndex(existing);
    await this.api.claimPlayer(link.roundId, id, name, colorIndex);
    return this.sync.sync();
  }

  /** Verlässt die Runde (auf dem Server) und löst die Verbindung. Die Daten bleiben als Kopie auf dem Gerät. */
  async leave(): Promise<void> {
    const link = await this.store.link();
    if (link === null) return;
    await this.api.leaveRound(link.roundId);
    await this.disconnect();
  }

  /**
   * Löst nur die Verbindung auf diesem Gerät (zum Beispiel beim Abmelden). Die Daten bleiben als Kopie hier; was
   * noch nicht hochgeladen war, kommt nicht mehr in der Runde an.
   */
  async disconnect(): Promise<void> {
    await this.sync.exclusive(async () => {
      await this.store.setLink(null);
      await this.store.clearBases();
    });
  }
}

/** Ob der Spieler genau so in der Liste der vorbelegten Spieler steht (Kotlin: `player in placeholders`). */
function isPlaceholder(player: Player, placeholders: readonly Player[]): boolean {
  return placeholders.some((placeholder) => samePlayer(placeholder, player));
}
