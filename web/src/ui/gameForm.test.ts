import { describe, expect, it } from "vitest";
import type { GameEntry } from "../core/model";
import type { GameDraft } from "./gameForm";
import {
  defaultType,
  draftOf,
  emptyDraft,
  formState,
  laufendeChoices,
  oppositeOf,
  saveDraft,
  selectAnnouncement,
  selectKontra,
  selectKontraBy,
  selectSuit,
  selectType,
  setBock,
  tapPlayer,
  toggleKlopfer,
} from "./gameForm";
import { fixedFactory, game, SEATING, testEvening } from "./testing";

const evening = testEvening();
const draft = (fields: Partial<GameDraft> = {}): GameDraft => ({ ...emptyDraft("RUFSPIEL"), ...fields });

describe("Spieler antippen (wie GameFormViewModel.tapPlayer)", () => {
  it("Rufspiel: erst Spieler, dann Mitspieler; ein dritter ersetzt den Mitspieler", () => {
    let d = tapPlayer(draft(), evening, "spieler-1");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", null]);
    d = tapPlayer(d, evening, "spieler-2");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", "spieler-2"]);
    d = tapPlayer(d, evening, "spieler-4");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", "spieler-4"]);
    d = tapPlayer(d, evening, "spieler-4");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", null]);
    d = tapPlayer(tapPlayer(d, evening, "spieler-2"), evening, "spieler-1");
    expect([d.declarer, d.partner]).toEqual([null, null]);
  });

  it("Einzelspiel: der Angetippte spielt", () => {
    let d = tapPlayer(draft({ type: "WENZ" }), evening, "spieler-2");
    expect(d.declarer).toBe("spieler-2");
    d = tapPlayer(d, evening, "spieler-3");
    expect([d.declarer, d.partner]).toEqual(["spieler-3", null]);
  });

  it("Über Kreuz: ein Tipp wählt das Paar mit dem Gegenüber", () => {
    let d = tapPlayer(draft({ type: "UEBER_KREUZ" }), evening, "spieler-2");
    expect([d.declarer, d.partner]).toEqual(["spieler-2", "spieler-4"]);
    d = tapPlayer(d, evening, "spieler-4");
    expect([d.declarer, d.partner]).toEqual([null, null]);
    expect(oppositeOf("spieler-3", SEATING)).toBe("spieler-1");
    expect(oppositeOf("niemand", SEATING)).toBeNull();
  });

  it("Wer Kontra gegeben hat, muss Gegenspieler bleiben", () => {
    let d = draft({ declarer: "spieler-1", kontra: "KONTRA", kontraBy: "spieler-2" });
    d = tapPlayer(d, evening, "spieler-2");
    expect(d.partner).toBe("spieler-2");
    expect(d.kontraBy).toBeNull();
  });
});

describe("Spielart wechseln (wie selectType)", () => {
  it("vom Rufspiel zum Solo fällt der Mitspieler weg, Farbe wird zurückgesetzt", () => {
    const d = selectType(draft({ declarer: "spieler-1", partner: "spieler-3", suit: "GRAS", laufende: 3 }), evening, "FARBSOLO");
    expect(d).toMatchObject({ type: "FARBSOLO", declarer: "spieler-1", partner: null, suit: null, laufende: 3 });
  });

  it("Laufende außerhalb des Bereichs der neuen Spielart werden zurückgesetzt", () => {
    expect(selectType(draft({ laufende: 3 }), evening, "WENZ").laufende).toBe(3);
    expect(selectType(draft({ laufende: 5 }), evening, "WENZ").laufende).toBe(0);
    expect(selectType(draft({ type: "WENZ", laufende: 2 }), evening, "RUFSPIEL").laufende).toBe(0);
  });

  it("zu Über Kreuz gehört das Gegenüber, zurück zum Rufspiel wird der Mitspieler neu gewählt", () => {
    let d = selectType(draft({ declarer: "spieler-1", partner: "spieler-2" }), evening, "UEBER_KREUZ");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", "spieler-3"]);
    d = selectType(d, evening, "RUFSPIEL");
    expect([d.declarer, d.partner]).toEqual(["spieler-1", null]);
  });

  it("Tout bleibt bei Einzelspielen, Sie nur beim Farbsolo; Kontra-Geber wird vergessen", () => {
    const tout = selectType(draft({ type: "FARBSOLO", announcement: "TOUT" }), evening, "GEIER");
    expect(tout.announcement).toBe("TOUT");
    const sie = selectType(draft({ type: "FARBSOLO", announcement: "SIE", kontraBy: "spieler-2" }), evening, "WENZ");
    expect(sie.announcement).toBe("NONE");
    expect(sie.kontraBy).toBeNull();
    expect(selectType(draft({ type: "FARBSOLO", announcement: "TOUT" }), evening, "RUFSPIEL").announcement).toBe("NONE");
  });
});

describe("weitere Auswahl", () => {
  it("Farbe, Gelegt und Kontra lassen sich an- und abwählen", () => {
    expect(selectSuit(selectSuit(draft(), "EICHEL"), "EICHEL").suit).toBeNull();
    const twice = toggleKlopfer(toggleKlopfer(draft(), "spieler-2"), "spieler-4");
    expect(twice.klopfer).toEqual(["spieler-2", "spieler-4"]);
    expect(toggleKlopfer(twice, "spieler-2").klopfer).toEqual(["spieler-4"]);
    const kontra = selectKontraBy(selectKontra(draft(), "KONTRA"), "spieler-2");
    expect(kontra.kontraBy).toBe("spieler-2");
    expect(selectKontraBy(kontra, "spieler-2").kontraBy).toBeNull();
    expect(selectKontra(kontra, "NONE").kontraBy).toBeNull();
    expect(selectKontra(kontra, "RETOUR").kontraBy).toBe("spieler-2");
  });

  it("eine Ansage schließt Schneider aus, Sie ist immer gewonnen", () => {
    const sie = selectAnnouncement(draft({ type: "FARBSOLO", level: "SCHNEIDER", declarerWon: false }), "SIE");
    expect(sie).toMatchObject({ announcement: "SIE", level: "NORMAL", declarerWon: true });
    expect(selectAnnouncement(sie, "SIE").announcement).toBe("NONE");
    expect(selectAnnouncement(sie, "TOUT").announcement).toBe("TOUT");
  });

  it("Bock von Hand: gleich dem Zähler heißt wieder automatisch", () => {
    expect(setBock(draft(), true, 0).bockOverride).toBe("FORCE_ON");
    expect(setBock(draft(), false, 0).bockOverride).toBe("AUTO");
    expect(setBock(draft(), false, 1).bockOverride).toBe("FORCE_OFF");
    expect(setBock(draft(), true, 1).bockOverride).toBe("AUTO");
  });

  it("bietet „keine“ und die zählenden Laufenden an", () => {
    expect(laufendeChoices(evening, "RUFSPIEL")).toEqual([0, 3, 4, 5, 6, 7, 8]);
    expect(laufendeChoices(evening, "WENZ")).toEqual([0, 2, 3, 4]);
  });

  it("nimmt die erste erlaubte Spielart als Vorgabe", () => {
    expect(defaultType(evening)).toBe("RUFSPIEL");
    expect(defaultType(testEvening([], { rules: { ...evening.rules, enabledTypes: ["WENZ", "GEIER"] } }))).toBe("WENZ");
  });
});

describe("Spielwert und Betrag im Formular (von Hand gerechnet, Standardregeln)", () => {
  it("Rufspiel ohne Zuschläge: 10 Cent", () => {
    const state = formState(evening, null, draft({ declarer: "spieler-1", partner: "spieler-3" }));
    expect(state.breakdown?.value).toBe(10);
    expect(state.declarerAmount).toBe(10);
    expect(state.canSave).toBe(true);
    expect(state.gameNumber).toBe(1);
  });

  it("Farbsolo nach der Schafkopf-Regel: der Alleinspieler bekommt dreimal 50 Cent", () => {
    const state = formState(evening, null, draft({ type: "FARBSOLO", declarer: "spieler-2" }));
    expect(state.breakdown?.value).toBe(50);
    expect(state.declarerAmount).toBe(150);
  });

  it("Rufspiel mit 3 Laufenden, Schneider und einmal gelegt: (10 + 30 + 10) × 2 = 100", () => {
    const state = formState(
      evening,
      null,
      draft({ declarer: "spieler-1", partner: "spieler-3", laufende: 3, level: "SCHNEIDER", klopfer: ["spieler-2"] }),
    );
    expect(state.breakdown?.sum).toBe(50);
    expect(state.breakdown?.totalFactor).toBe(2);
    expect(state.breakdown?.value).toBe(100);
  });

  it("meldet, was noch fehlt", () => {
    expect(formState(evening, null, draft()).missing).toBe("DECLARER");
    expect(formState(evening, null, draft({ declarer: "spieler-1" })).missing).toBe("PARTNER");
    expect(formState(evening, null, draft({ type: "GEIER", declarer: "spieler-1" })).missing).toBeNull();
    expect(formState(evening, null, draft({ declarer: "spieler-1" })).declarerAmount).toBe(0);
  });

  it("nach einer Bockrunde ist das nächste Spiel automatisch ein Bockspiel (doppelt)", () => {
    const withBock = testEvening([
      { kind: "bock", id: "b1", recordedAt: "2026-11-14T19:01:00Z", reason: "SECHS_NIXER", player: "spieler-2", handThrownIn: true },
    ]);
    const state = formState(withBock, null, draft({ declarer: "spieler-1", partner: "spieler-3" }));
    expect(state.autoBockExponent).toBe(1);
    expect(state.bockExponent).toBe(1);
    expect(state.breakdown?.value).toBe(20);
    expect(formState(withBock, null, draft({ declarer: "spieler-1", partner: "spieler-3", bockOverride: "FORCE_OFF" })).breakdown?.value).toBe(10);
  });

  it("beim Ändern zählt nur, was vor dem Spiel geschah", () => {
    const two = testEvening([game("g1"), game("g2", { type: "WENZ", partner: null })]);
    const editing = formState(two, two.entries[0] as GameEntry, draftOf(game("g1")));
    expect(editing.editing).toBe(true);
    expect(editing.gameNumber).toBe(1);
    expect(formState(two, null, draft()).gameNumber).toBe(3);
  });
});

describe("Speichern (wie GameFormViewModel.save)", () => {
  it("hängt ein neues Spiel mit dem angetippten Ergebnis an", () => {
    const saved = saveDraft(evening, null, draft({ declarer: "spieler-1", partner: "spieler-3" }), false, fixedFactory());
    expect(saved?.entries).toEqual([game("id-1", { recordedAt: "2026-11-14T20:00:00Z", declarerWon: false })]);
  });

  it("speichert nichts, solange eine Angabe fehlt", () => {
    expect(saveDraft(evening, null, draft({ declarer: "spieler-1" }), true, fixedFactory())).toBeNull();
    expect(saveDraft(evening, null, draft(), true, fixedFactory())).toBeNull();
  });

  it("Sie ist immer gewonnen und hat keinen Schneider", () => {
    const saved = saveDraft(evening, null, draft({ type: "FARBSOLO", declarer: "spieler-1", announcement: "SIE", level: "SCHWARZ" }), false, fixedFactory());
    expect(saved?.entries[0]).toMatchObject({ declarerWon: true, level: "NORMAL", announcement: "SIE", partner: null });
  });

  it("beim Ändern bleiben Kennung, Zeitpunkt, Notiz und Augen; Retour-Geber nur bei Retour", () => {
    const original = game("g1", { kontra: "RETOUR", retourBy: "spieler-1", declarerEyes: 71, note: "knapp" });
    const before = testEvening([original, game("g2")]);
    const changed = saveDraft(before, original, { ...draftOf(original), declarerWon: false, laufende: 3 }, null, fixedFactory());
    expect(changed?.entries).toEqual([{ ...original, declarerWon: false, laufende: 3 }, game("g2")]);
    const noRetour = saveDraft(before, original, { ...draftOf(original), kontra: "KONTRA" }, null, fixedFactory());
    expect(noRetour?.entries[0]).toMatchObject({ kontra: "KONTRA", retourBy: null, note: "knapp", declarerEyes: 71 });
  });
});
