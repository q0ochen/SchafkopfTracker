/* Das Zusammenführen zweier Fassungen eines Abends (docs/ARCHITEKTUR.md, Abschnitt 8.5). Port von `EveningMergeTest.kt`. */
import { describe, expect, it } from "vitest";
import { append, remove, replace } from "../evening/Evening";
import type { BockTriggerEntry, Evening, EveningEntry, GameEntry, ManualEntry, ThrownInEntry } from "../model";
import { RuleSets } from "../rules/RuleSet";
import { sameEvening } from "./equality";
import { EveningMerge } from "./EveningMerge";
import { after, evening, game, ids, P1, P2, P3, P4, TestRandom } from "./testing/fixtures";

const g1 = game("g1", 60);
const g2 = game("g2", 120);
const g3 = game("g3", 180);

describe("Einträge: neu, geändert, gelöscht", () => {
  it("gleichzeitig auf zwei Handys eingetragene Spiele stehen in der Reihenfolge ihrer Uhrzeit", () => {
    const base = evening([g1]);
    const mine = game("m", 200);
    const theirs = game("t", 190);

    const merged = EveningMerge.merge3(base, evening([g1, mine]), evening([g1, theirs]));

    expect(ids(merged)).toEqual(["g1", "t", "m"]);
    // Egal, wer zuerst abgleicht: Am Ende steht dieselbe Liste.
    expect(EveningMerge.merge3(base, evening([g1, theirs]), evening([g1, mine]))).toEqual(merged);
  });

  it("bei gleicher Uhrzeit entscheidet die Kennung", () => {
    const base = evening([g1]);
    const first = game("a", 200);
    const second = game("b", 200);

    expect(ids(EveningMerge.merge3(base, evening([g1, second]), evening([g1, first])))).toEqual(["g1", "a", "b"]);
    expect(ids(EveningMerge.merge3(base, evening([g1, first]), evening([g1, second])))).toEqual(["g1", "a", "b"]);
  });

  it("mehrere neue Spiele beider Seiten werden wie zwei sortierte Stapel gemischt", () => {
    const base = evening([g1]);
    const local = evening([g1, game("m1", 200), game("m2", 400)]);
    const remote = evening([g1, game("t1", 300), game("t2", 500)]);

    expect(ids(EveningMerge.merge3(base, local, remote))).toEqual(["g1", "m1", "t1", "m2", "t2"]);
    expect(ids(EveningMerge.merge3(base, remote, local))).toEqual(["g1", "m1", "t1", "m2", "t2"]);
  });

  it("die eigene Reihenfolge bleibt, auch wenn die eigene Uhr zwischendurch zurückgestellt wurde", () => {
    const base = evening([g1]);
    // m2 wurde nach m1 eingetragen, trägt aber die frühere Zeit.
    const local = evening([g1, game("m1", 400), game("m2", 200)]);
    const remote = evening([g1, game("t", 300)]);

    expect(ids(EveningMerge.merge3(base, local, remote))).toEqual(["g1", "t", "m1", "m2"]);
  });

  it("ein neues Spiel bleibt hinter dem Eintrag, hinter dem es hier steht", () => {
    const base = evening([g1, g2, g3]);
    // Nach dem Löschen und „Rückgängig“ steht x wieder zwischen g1 und g2, hat aber die späteste Zeit.
    const local = evening([g1, game("x", 900), g2, g3]);

    expect(ids(EveningMerge.merge3(base, local, base))).toEqual(["g1", "x", "g2", "g3"]);
  });

  it("ein neuer Eintrag ganz vorn bleibt vorn", () => {
    const base = evening([g1, g2]);
    const local = evening([game("x", 900), g1, g2]);
    const remote = evening([g1, g2, game("t", 300)]);

    expect(ids(EveningMerge.merge3(base, local, remote))).toEqual(["x", "g1", "g2", "t"]);
  });

  it("eine Änderung von hier bleibt, eine Änderung des Servers kommt an", () => {
    const base = evening([g1, g2]);
    const mine: GameEntry = { ...g1, declarerWon: false };
    const theirs: GameEntry = { ...g2, laufende: 3 };

    const merged = EveningMerge.merge3(base, evening([mine, g2]), evening([g1, theirs]));

    expect(merged.entries).toEqual([mine, theirs]);
  });

  it("ändern beide denselben Eintrag, gewinnt die Änderung von hier", () => {
    const base = evening([g1]);
    const mine: GameEntry = { ...g1, laufende: 4 };
    const theirs: GameEntry = { ...g1, laufende: 5 };

    expect(EveningMerge.merge3(base, evening([mine]), evening([theirs])).entries).toEqual([mine]);
    expect(EveningMerge.merge3(base, evening([theirs]), evening([mine])).entries).toEqual([theirs]);
  });

  it("haben beide dasselbe geändert, ist das kein Konflikt", () => {
    const base = evening([g1]);
    const changed: GameEntry = { ...g1, laufende: 4 };

    expect(EveningMerge.merge3(base, evening([changed]), evening([{ ...changed }])).entries).toEqual([changed]);
  });

  it("hier gelöscht und auf dem Server unverändert bleibt gelöscht", () => {
    const base = evening([g1, g2, g3]);

    expect(ids(EveningMerge.merge3(base, evening([g1, g3]), base))).toEqual(["g1", "g3"]);
  });

  it("auf dem Server gelöscht und hier unverändert entfällt", () => {
    const base = evening([g1, g2, g3]);

    expect(ids(EveningMerge.merge3(base, base, evening([g1, g3])))).toEqual(["g1", "g3"]);
  });

  it("eine Änderung gewinnt gegen das Löschen, in beiden Richtungen", () => {
    const base = evening([g1, g2, g3]);
    const changed: GameEntry = { ...g2, declarerWon: false };

    // Hier geändert, auf dem Server gelöscht: Der Eintrag bleibt an seiner Stelle.
    expect(EveningMerge.merge3(base, evening([g1, changed, g3]), evening([g1, g3])).entries).toEqual([g1, changed, g3]);
    // Hier gelöscht, auf dem Server geändert: Der Eintrag kommt zurück.
    expect(EveningMerge.merge3(base, evening([g1, g3]), evening([g1, changed, g3])).entries).toEqual([g1, changed, g3]);
  });

  it("löschen beide denselben Eintrag, bleibt er gelöscht", () => {
    const base = evening([g1, g2]);

    expect(ids(EveningMerge.merge3(base, evening([g1]), evening([g1])))).toEqual(["g1"]);
  });

  it("gelöscht, hochgeladen und dann Rückgängig bringt den Eintrag an seine Stelle zurück", () => {
    // Der Server kennt schon die Fassung ohne g2; hier wurde g2 danach wieder eingefügt.
    const base = evening([g1, g3]);

    expect(ids(EveningMerge.merge3(base, evening([g1, g2, g3]), base))).toEqual(["g1", "g2", "g3"]);
  });

  it("alle Arten von Einträgen werden zusammengeführt", () => {
    const thrown: ThrownInEntry = { kind: "thrown_in", id: "w", recordedAt: after(200) };
    const bock: BockTriggerEntry = { kind: "bock", id: "b", recordedAt: after(210), reason: "SECHS_NIXER", player: P3, handThrownIn: true };
    const manual: ManualEntry = {
      kind: "manual",
      id: "f",
      recordedAt: after(220),
      amounts: new Map([
        [P1, 50],
        [P2, -50],
      ]),
      note: "Runde Schnaps",
    };
    const base = evening([g1]);

    const merged = EveningMerge.merge3(base, evening([g1, thrown, manual]), evening([g1, bock]));

    expect(merged.entries).toEqual([g1, thrown, bock, manual]);
  });
});

describe("Gleichheit wie in Kotlin", () => {
  it("Mengen und Maps zählen ohne Reihenfolge, Zeitpunkte als Zeit", () => {
    const klopfer: GameEntry = { ...g1, klopfer: [P2, P3] };
    const manual: ManualEntry = {
      kind: "manual",
      id: "f",
      recordedAt: after(220),
      amounts: new Map([
        [P1, 50],
        [P2, -50],
      ]),
      note: "",
    };
    const base = evening([klopfer, manual]);
    // Der Server liefert dasselbe mit anderer Reihenfolge der Schlüssel und anderer Schreibweise der Zeit.
    const reordered = evening([
      { ...klopfer, klopfer: [P3, P2], recordedAt: "2026-12-12T18:01:00.000Z" },
      {
        ...manual,
        amounts: new Map([
          [P2, -50],
          [P1, 50],
        ]),
      },
    ]);
    const rules = RuleSets.standard();
    const remote: Evening = {
      ...reordered,
      rules: { ...rules, prices: { GEIER: 50, WENZ: 50, FARBSOLO: 50, UEBER_KREUZ: 10, RUFSPIEL: 10 }, enabledTypes: [...rules.enabledTypes].reverse() },
    };
    expect(sameEvening(base, remote)).toBe(true);

    const local = append(base, game("m", 300));
    const merged = EveningMerge.merge3(base, local, remote);

    // Nichts davon gilt als Änderung des Servers: Es bleibt alles von hier, mit dem neuen Spiel.
    expect(sameEvening(merged, local)).toBe(true);
    expect(merged.entries[0]).toBe(klopfer);
  });

  it("jedes Feld eines Spiels zählt", () => {
    const variants: Array<Partial<GameEntry>> = [
      { recordedAt: after(61) },
      { type: "WENZ" },
      { suit: "HERZ" },
      { declarer: P3 },
      { partner: null },
      { declarerWon: false },
      { level: "SCHNEIDER" },
      { laufende: 3 },
      { announcement: "TOUT" },
      { klopfer: [P2] },
      { kontra: "KONTRA" },
      { kontraBy: P3 },
      { retourBy: P1 },
      { bockOverride: "FORCE_ON" },
      { declarerEyes: 61 },
      { note: "schön" },
    ];
    // Alle Felder außer `kind` und `id` kommen vor (die Kennung ordnet zu, die Art ist fest).
    expect(new Set(variants.flatMap((it) => Object.keys(it))).size).toBe(Object.keys(g1).length - 2);
    for (const variant of variants) {
      const changed: GameEntry = { ...g1, ...variant };
      // Hier unverändert, auf dem Server geändert: Die Änderung kommt an.
      expect(EveningMerge.merge3(evening([g1]), evening([g1]), evening([changed])).entries, JSON.stringify(variant)).toEqual([changed]);
    }
  });
});

describe("ohne Basis", () => {
  it("ohne Basis bleibt alles von beiden Seiten, bei gleicher Kennung der Eintrag von hier", () => {
    const mine: GameEntry = { ...g1, laufende: 2 };
    const local = evening([mine, game("m", 300)]);
    const remote = evening([g1, g2]);

    const merged = EveningMerge.merge3(null, local, remote);

    expect(ids(merged)).toEqual(["g1", "g2", "m"]);
    expect(merged.entries[0]).toEqual(mine);
  });

  it("ohne Basis gilt der Kopf des Servers", () => {
    const local: Evening = { ...evening([g1]), location: "Wirtshaus", finishedAt: after(7200) };
    const remote: Evening = { ...evening([g1]), location: "Stüberl", firstDealer: P2 };

    const merged = EveningMerge.merge3(null, local, remote);

    expect(merged).toEqual({ ...remote, entries: [g1] });
  });
});

describe("Kopf", () => {
  it("im Kopf bleibt je Feld die Änderung von hier, alles andere kommt vom Server", () => {
    const base = evening([g1]);
    const finished = after(10_800);
    const local: Evening = { ...base, finishedAt: finished };
    const remote: Evening = { ...base, location: "Wirtshaus", firstDealer: P3 };

    const merged = EveningMerge.merge3(base, local, remote);

    expect(merged.finishedAt).toBe(finished);
    expect(merged.location).toBe("Wirtshaus");
    expect(merged.firstDealer).toBe(P3);
    expect(merged.seating).toEqual(base.seating);
  });

  it("ändern beide dasselbe Feld im Kopf, gewinnt das von hier", () => {
    const base = evening();
    const local: Evening = { ...base, location: "hier", rules: RuleSets.sauspiel(), seating: [P4, P3, P2, P1], startedAt: after(1) };
    const remote: Evening = { ...base, location: "dort", rules: base.rules, seating: [P2, P1, P4, P3], startedAt: after(2) };

    expect(EveningMerge.merge3(base, local, remote)).toEqual(local);
    expect({ ...EveningMerge.merge3(base, remote, local), rules: base.rules }).toEqual(remote);
  });

  it("ein hier wieder geöffneter Abend bleibt offen", () => {
    const base: Evening = { ...evening([g1]), finishedAt: after(3600) };
    const local: Evening = { ...base, finishedAt: null };

    expect(EveningMerge.merge3(base, local, base).finishedAt).toBeNull();
  });
});

describe("beschädigte Daten", () => {
  it("doppelte Kennungen ergeben nie doppelte Einträge", () => {
    const twice: GameEntry = { ...g1, laufende: 7 };
    const merged = EveningMerge.merge3(
      evening([g1]),
      evening([g1, twice, game("m", 300), game("m", 400)]),
      evening([g1, twice, g2, g2]),
    );

    expect(ids(merged)).toEqual(["g1", "g2", "m"]);
  });

  it("bei doppelten Kennungen in der Basis zählt der letzte Eintrag (wie Kotlins associateBy)", () => {
    // Abweichung der Kotlin-Vorlage vom Text des Vertrags („jeweils der erste“), hier bewusst nachgebaut:
    // Gegen den letzten Basis-Eintrag gilt das Spiel von hier als geändert und bleibt.
    const twice: GameEntry = { ...g1, laufende: 7 };

    expect(EveningMerge.merge3(evening([g1, twice]), evening([g1]), evening([twice])).entries).toEqual([g1]);
  });
});

describe("Gesetze, mit vielen zufälligen Fällen geprüft", () => {
  /** Ändert einen Abend so, wie es die App kann: Einträge ändern, löschen und neue anhängen, den Abend beenden. */
  function change(base: Evening, random: TestRandom, prefix: string, editable: readonly EveningEntry[], header: boolean): Evening {
    let result = base;
    for (const entry of editable) {
      const dice = random.nextInt(6);
      if (dice === 0) result = remove(result, entry.id);
      else if (dice === 1) result = replace(result, { ...(entry as GameEntry), laufende: random.nextInt(1, 9) });
    }
    // Neue Einträge trägt jedes Handy mit fortlaufender Zeit ein.
    let time = 1000;
    const count = random.nextInt(0, 4);
    for (let index = 0; index < count; index++) {
      time += random.nextInt(1, 200);
      result = append(result, game(`${prefix}-${index}`, time));
    }
    if (header && random.nextInt(4) === 0) result = { ...result, finishedAt: after(random.nextInt(3600, 7200)) };
    if (header && random.nextInt(4) === 0) result = { ...result, location: prefix };
    return result;
  }

  /** Lässt `check` mit 400 zufälligen, aber bei jedem Lauf gleichen Fällen laufen. */
  function repeatRandom(conflicts: boolean, check: (base: Evening, local: Evening, remote: Evening) => void): void {
    const random = new TestRandom(20261212);
    for (let round = 0; round < 400; round++) {
      const base = evening(Array.from({ length: random.nextInt(0, 7) }, (_, index) => game(`g${index}`, 60 * (index + 1))));
      const forLocal = base.entries.filter((_, index) => conflicts || index % 2 === 0);
      const forRemote = base.entries.filter((_, index) => conflicts || index % 2 === 1);
      const local = change(base, random, `m${round}`, forLocal, conflicts || round % 2 === 0);
      const remote = change(base, random, `t${round}`, forRemote, conflicts || round % 2 === 1);
      check(base, local, remote);
    }
  }

  it("nichts Neues geht verloren und die Reihenfolge des Servers bleibt", () => {
    repeatRandom(true, (base, local, remote) => {
      const merged = EveningMerge.merge3(base, local, remote);
      const all = ids(merged);

      expect(new Set(all).size, "doppelte Kennungen").toBe(all.length);
      const known = new Set(ids(base));
      for (const id of ids(local)) if (!known.has(id)) expect(all, "ein hier neuer Eintrag fehlt").toContain(id);
      for (const id of ids(remote)) if (!known.has(id)) expect(all, "ein neuer Eintrag des Servers fehlt").toContain(id);
      // Was der Server kennt, steht in seiner Reihenfolge.
      const kept = all.filter((id) => ids(remote).includes(id));
      expect(kept).toEqual(ids(remote).filter((id) => kept.includes(id)));
      // Was nur hier steht, bleibt in der Reihenfolge von hier.
      const own = all.filter((id) => !ids(remote).includes(id));
      expect(own).toEqual(ids(local).filter((id) => own.includes(id)));
    });
  });

  it("ist eine Seite unverändert, gilt die andere", () => {
    repeatRandom(true, (base, local, remote) => {
      expect(EveningMerge.merge3(base, local, local)).toEqual(local);
      expect(EveningMerge.merge3(base, base, remote)).toEqual(remote);
      expect(EveningMerge.merge3(base, local, base)).toEqual(local);
      expect(EveningMerge.merge3(base, base, base)).toEqual(base);
    });
  });

  it("noch einmal mit derselben Fassung des Servers zusammengeführt ändert sich nichts", () => {
    repeatRandom(true, (base, local, remote) => {
      const merged = EveningMerge.merge3(base, local, remote);

      expect(EveningMerge.merge3(base, merged, remote)).toEqual(merged);
      // Nach dem Abgleich ist die Fassung des Servers die neue Basis.
      expect(EveningMerge.merge3(remote, merged, remote)).toEqual(merged);
    });
  });

  it("ohne Konflikt ist es gleich, wer zuerst abgleicht", () => {
    repeatRandom(false, (base, local, remote) => {
      // Das eine Handy lädt hoch, das andere führt zusammen – und umgekehrt.
      expect(EveningMerge.merge3(base, remote, local)).toEqual(EveningMerge.merge3(base, local, remote));
    });
  });

  it("nach dem Hochladen übernehmen alle anderen die Fassung des Servers", () => {
    repeatRandom(true, (base, local, remote) => {
      // Handy 1 hat `remote` hochgeladen, Handy 2 führt zusammen und lädt hoch, Handy 1 holt das Ergebnis.
      const merged = EveningMerge.merge3(base, local, remote);

      expect(EveningMerge.merge3(remote, remote, merged)).toEqual(merged);
    });
  });
});
