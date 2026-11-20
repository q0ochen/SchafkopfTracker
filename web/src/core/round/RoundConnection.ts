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
import { roundLink, type RoundLink, type RoundSummary } from "./RoundModels";
import type { EveningRepository, IdGenerator, PlayerRepository, RoundApi, RoundStore } from "./RoundPorts";
import { SyncOutcome, type RoundSync } from "./RoundSync";

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
   *
   * @param userId Das angemeldete Konto.
   */
  async create(name: string, userId: string, placeholders: readonly Player[]): Promise<SyncOutcome> {
    await this.sync.exclusive(async () => {
      const round = await this.api.createRound(name);
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
   * werden dabei gelöscht** (vorher nachfragen, siehe {@link hasOwnData}).
   */
  async join(token: string, userId: string): Promise<SyncOutcome> {
    const info = await this.api.inviteInfo(token);
    const round = await this.api.joinRound(token);
    return this.attach(roundLink({ roundId: round, roundName: info.roundName, userId }));
  }

  /**
   * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy).
   * **Abende und Spieler dieses Geräts werden dabei gelöscht.**
   */
  use(round: RoundSummary, userId: string): Promise<SyncOutcome> {
    return this.attach(
      roundLink({ roundId: round.id, roundName: round.name, userId, playerId: round.myPlayerId, isCreator: round.isCreator }),
    );
  }

  private async attach(link: RoundLink): Promise<SyncOutcome> {
    await this.sync.exclusive(async () => {
      // Erst leeren, dann verbinden: Bräche es dazwischen ab, ginge sonst der Rest als neu in die Runde hoch.
      for (const evening of await this.evenings.evenings()) await this.evenings.delete(evening.id);
      for (const player of await this.players.players()) await this.players.delete(player.id);
      await this.store.clearBases();
      await this.store.setLink(link);
    });
    return this.sync.sync();
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
