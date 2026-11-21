/*
 * Verbindet dieses Gerät mit einer gemeinsamen Runde, wechselt zwischen Runden und trennt es wieder
 * (docs/ARCHITEKTUR.md, Abschnitt 8.7). Port von `domain/round/RoundConnection.kt`.
 *
 * Die Aufrufe, die mit dem Server reden, können mit einer `RoundException` scheitern; dann ist auf dem Gerät nichts
 * verändert, außer es steht ausdrücklich dabei. Ist das Gerät schon mit einer Runde verbunden, wechseln `create`,
 * `join` und `use` in die neue Runde (siehe `switchDevice`): Die Abende der bisherigen bleiben auf dem Server, das Gerät
 * hält danach die Daten der neuen.
 *
 * Kotlin lässt den Zug „abgleichen, leeren, verbinden“ ohne Abbruch zu Ende laufen (`NonCancellable`). In JavaScript
 * lässt sich ein laufender Aufruf ohnehin nicht abbrechen: Wer den Bildschirm verlässt, hält den Wechsel nicht auf.
 */
import { involves } from "../evening/Evening";
import type { Player, PlayerId } from "../model";
import { nextColorIndex } from "../players";
import { samePlayer } from "./equality";
import { RoundException, roundLink, switchFrom, type RoundId, type RoundLink, type RoundSummary, type SwitchFrom } from "./RoundModels";
import { RoundNames } from "./RoundNames";
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
   * Legt eine Runde an. Ohne verbundene Runde bringt sie die Daten dieses Geräts mit: Alle Abende und Spieler gehen
   * beim ersten Abgleich hoch; vorbelegte Spieler, die noch niemand umbenannt hat und die in keinem Abend vorkommen,
   * entfallen vorher. Ist das Gerät schon verbunden, beginnt die neue Runde leer, und die bisherige behält ihre Abende.
   *
   * Hat das Konto schon eine Runde dieses Namens, scheitert es mit `NAME_TAKEN` (docs/ARCHITEKTUR.md 8.7, „Eindeutige
   * Namen“) – außer es ist die eigene, noch leere Runde eines Versuchs, dessen Antwort verloren ging (nie die
   * verbundene): Die wird verwendet, statt eine zweite anzulegen.
   *
   * @param userId Das angemeldete Konto.
   * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
   */
  async create(name: string, userId: string, placeholders: readonly Player[], from: SwitchFrom | null = null): Promise<SyncOutcome> {
    const expected = from ?? switchFrom((await this.store.link())?.roundId ?? null);
    const rounds = await this.api.myRounds();
    let round = emptyOwnRound(rounds, name, expected.roundId);
    if (round === null) {
      if (RoundNames.taken(name, rounds)) throw new RoundException("NAME_TAKEN");
      round = await this.api.createRound(name);
    }
    const link = roundLink({ roundId: round, roundName: RoundNames.clean(name), userId, isCreator: true });
    const ended = await this.switchDevice(expected, link, async () => {
      // Ohne Runde kommen die Daten des Geräts mit; nur unberührte vorbelegte Spieler ohne Abend entfallen.
      const all = await this.evenings.evenings();
      const untouched = (await this.players.players()).filter(
        (player) => isPlaceholder(player, placeholders) && !all.some((evening) => involves(evening, player.id)),
      );
      for (const player of untouched) await this.players.delete(player.id);
    });
    return ended ?? this.sync.sync();
  }

  /**
   * Tritt der Runde hinter dem Einladungscode bei und übernimmt deren Daten. **Abende und Spieler dieses Geräts
   * werden dabei gelöscht** (ohne verbundene Runde vorher nachfragen, siehe {@link hasOwnData}) – aber erst, wenn der
   * Server die Runde gezeigt hat. Antwortet er nicht, bleibt das Gerät, wie es ist. Gehört die Einladung zur
   * verbundenen Runde selbst, wird nur abgeglichen.
   *
   * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
   */
  async join(token: string, userId: string, from: SwitchFrom | null = null): Promise<SyncOutcome> {
    const expected = from ?? switchFrom((await this.store.link())?.roundId ?? null);
    const info = await this.api.inviteInfo(token);
    if ((await this.store.link())?.roundId === info.roundId) return this.sync.sync();
    const round = await this.api.joinRound(token);
    return this.attach(roundLink({ roundId: round, roundName: info.roundName, userId }), expected);
  }

  /**
   * Verwendet eine Runde, der das Konto schon angehört, auf diesem Gerät (zum Beispiel auf einem neuen Handy) oder
   * wechselt zu ihr. **Abende und Spieler dieses Geräts werden dabei gelöscht**, sobald der Server die Runde gezeigt
   * hat – ohne verbundene Runde vorher nachfragen (siehe {@link hasOwnData}); mit verbundener Runde sind sie nach dem
   * Abgleich in `switchDevice` auf dem Server. Gibt es die Runde für dieses Konto nicht (mehr), scheitert der Aufruf
   * mit `NOT_MEMBER`, und das Gerät bleibt, wie es ist. Ist `round` die verbundene Runde, wird nur abgeglichen.
   *
   * @param from Was die Rückfrage gezeigt hat; ohne Angabe gilt der Stand beim Aufruf, und verloren gehen darf nichts.
   */
  async use(round: RoundSummary, userId: string, from: SwitchFrom | null = null): Promise<SyncOutcome> {
    const expected = from ?? switchFrom((await this.store.link())?.roundId ?? null);
    if ((await this.store.link())?.roundId === round.id) return this.sync.sync();
    return this.attach(
      roundLink({ roundId: round.id, roundName: round.name, userId, playerId: round.myPlayerId, isCreator: round.isCreator }),
      expected,
    );
  }

  private async attach(link: RoundLink, from: SwitchFrom): Promise<SyncOutcome> {
    // Erst fragen, dann leeren: Ohne Antwort des Servers bleibt das Gerät, wie es ist. Sonst stünde man ohne Netz mit
    // einem leeren Gerät da, das schon mit der Runde verbunden ist.
    if ((await this.api.head(link.roundId)) === null) throw new RoundException("NOT_MEMBER");
    return (await this.switchDevice(from, link, null)) ?? this.sync.sync();
  }

  /**
   * Verbindet das Gerät mit `link`, am Stück.
   *
   * War das Gerät mit einer Runde verbunden, wird in derselben Sperre zuerst noch einmal vollständig abgeglichen, damit
   * auch ein gerade eingetragenes Spiel oben ist, dann das Gerät geleert. Wartet danach mehr, als die Rückfrage nannte
   * (`from.acceptedLoss`), oder hängt das Gerät inzwischen an einer anderen (oder keiner) Runde, wird nicht gewechselt
   * (`CHANGED_MEANWHILE`).
   *
   * Ohne verbundene Runde bleiben die Daten, wenn `keep` gesetzt ist (es räumt vorher auf, siehe {@link create}); sonst
   * wird das Gerät geleert (Beitreten, Verwenden).
   *
   * @returns `null`, wenn verbunden ist; `Detached`, wenn es die bisherige Runde nicht mehr gibt (die Daten bleiben dann
   *   als Kopie, und es wird nicht gewechselt).
   * @throws RoundException ohne Netz, bei abgelaufener Anmeldung, einem Fehler des Servers oder `CHANGED_MEANWHILE`;
   *   dann bleibt alles, wie es ist.
   */
  private switchDevice(from: SwitchFrom, link: RoundLink, keep: (() => Promise<void>) | null): Promise<SyncOutcome | null> {
    return this.sync.syncThen(async (outcome) => {
      if (from.roundId !== null) {
        switch (outcome.kind) {
          case "Synced":
          case "Unchanged":
            break;
          case "Detached":
            return outcome;
          case "NotLinked":
            throw new RoundException("CHANGED_MEANWHILE");
          case "Offline":
            throw new RoundException("OFFLINE");
          case "SignedOut":
            throw new RoundException("SIGNED_OUT");
          case "Failed":
            throw new RoundException(outcome.error);
        }
        if ((await this.sync.pendingCount()) > from.acceptedLoss) throw new RoundException("CHANGED_MEANWHILE");
      }
      if (((await this.store.link())?.roundId ?? null) !== from.roundId) throw new RoundException("CHANGED_MEANWHILE");
      if (from.roundId === null && keep !== null) {
        await keep();
        await this.store.clearBases();
      } else {
        await this.clearDevice();
      }
      await this.store.setLink(link);
      return null;
    });
  }

  /**
   * Leert das Gerät für eine andere Runde: erst Verbindung und Basen, dann die Daten. Mit Basen sähe das Leeren aus wie
   * Löschen und ginge beim nächsten Abgleich als Löschung in die Runde hoch; und bräche es vor dem Verbinden ab, ginge
   * der Rest sonst als neu hoch. Nur innerhalb der Sperre des Abgleichs.
   */
  private async clearDevice(): Promise<void> {
    await this.store.setLink(null);
    await this.store.clearBases();
    for (const evening of await this.evenings.evenings()) await this.evenings.delete(evening.id);
    for (const player of await this.players.players()) await this.players.delete(player.id);
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

/**
 * Eine eigene, noch leere Runde dieses Namens (nie die verbundene `linked`). Sie stammt von einem Versuch, dessen
 * Antwort verloren ging; ohne diese Prüfung legte der zweite Versuch eine zweite Runde an, und die erste bliebe für
 * immer leer liegen.
 */
function emptyOwnRound(rounds: readonly RoundSummary[], name: string, linked: RoundId | null): RoundId | null {
  return rounds.find((it) => RoundNames.reusable(it) && it.id !== linked && RoundNames.same(it.name, name))?.id ?? null;
}

/** Ob der Spieler genau so in der Liste der vorbelegten Spieler steht (Kotlin: `player in placeholders`). */
function isPlaceholder(player: Player, placeholders: readonly Player[]): boolean {
  return placeholders.some((placeholder) => samePlayer(placeholder, player));
}
