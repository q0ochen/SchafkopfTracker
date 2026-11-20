/* Die Texte der gemeinsamen Runde, wörtlich wie in docs/ARCHITEKTUR.md, Abschnitt 8.9. */
import { describe, expect, it } from "vitest";
import { ROUND_ERRORS } from "../core/round/RoundModels";
import {
  dealerHint,
  GENERIC_ERROR,
  inviteMessage,
  memberCountText,
  messageText,
  NO_CODE_ERROR,
  noticeText,
  pendingWarning,
  replaceText,
  roundErrorText,
  roundStatus,
  SIGN_IN_MESSAGES,
  statusIconLabel,
  type RoundStatusInput,
} from "./texts";

const CALM: RoundStatusInput = { needsSignIn: false, problem: null, pending: 0, unreadable: 0, refused: 0 };

describe("Statuszeile", () => {
  it.each([
    [{ ...CALM }, "SYNCED", "Abgeglichen"],
    [{ ...CALM, pending: 1 }, "PENDING", "1 Änderung wartet"],
    [{ ...CALM, pending: 7 }, "PENDING", "7 Änderungen warten"],
    [{ ...CALM, problem: "OFFLINE" }, "PROBLEM", "Kein Netz"],
    [{ ...CALM, problem: "OFFLINE", pending: 1 }, "PROBLEM", "Kein Netz – 1 Änderung wartet"],
    [{ ...CALM, problem: "OFFLINE", pending: 3 }, "PROBLEM", "Kein Netz – 3 Änderungen warten"],
    [{ ...CALM, problem: "SERVER" }, "PROBLEM", "Server nicht erreichbar"],
    [{ ...CALM, problem: "SERVER", pending: 1 }, "PROBLEM", "Server nicht erreichbar – 1 Änderung wartet"],
    [{ ...CALM, problem: "SERVER", pending: 2 }, "PROBLEM", "Server nicht erreichbar – 2 Änderungen warten"],
    [{ ...CALM, refused: 1 }, "PROBLEM", "1 Abend nimmt der Server nicht an"],
    [{ ...CALM, refused: 4 }, "PROBLEM", "4 Abende nimmt der Server nicht an"],
    [{ ...CALM, unreadable: 1 }, "PROBLEM", "1 Abend kann diese Version nicht lesen – bitte die App aktualisieren"],
    [{ ...CALM, unreadable: 2 }, "PROBLEM", "2 Abende kann diese Version nicht lesen – bitte die App aktualisieren"],
    [{ ...CALM, needsSignIn: true }, "PROBLEM", "Anmeldung abgelaufen – bitte neu anmelden"],
  ] as const)("%o: %s „%s“", (input, kind, text) => {
    expect(roundStatus(input)).toEqual({ kind, text });
  });

  it("es gilt die erste zutreffende Zeile: Anmeldung, unlesbar, nicht angenommen, kein Netz, Server, wartend", () => {
    const everything: RoundStatusInput = { needsSignIn: true, problem: "OFFLINE", pending: 5, unreadable: 2, refused: 3 };
    expect(roundStatus(everything).text).toBe("Anmeldung abgelaufen – bitte neu anmelden");
    expect(roundStatus({ ...everything, needsSignIn: false }).text).toBe("2 Abende kann diese Version nicht lesen – bitte die App aktualisieren");
    expect(roundStatus({ ...everything, needsSignIn: false, unreadable: 0 }).text).toBe("3 Abende nimmt der Server nicht an");
    expect(roundStatus({ ...everything, needsSignIn: false, unreadable: 0, refused: 0 }).text).toBe("Kein Netz – 5 Änderungen warten");
    expect(roundStatus({ ...everything, needsSignIn: false, unreadable: 0, refused: 0, problem: "SERVER" }).text).toBe(
      "Server nicht erreichbar – 5 Änderungen warten",
    );
    // Eine abgelaufene Anmeldung, die die Oberfläche (noch) nicht als solche zeigt, fällt auf das Wartende zurück.
    expect(roundStatus({ ...everything, needsSignIn: false, unreadable: 0, refused: 0, problem: "SIGNED_OUT" }).text).toBe("5 Änderungen warten");
  });

  it("das Wolken-Symbol im Abend trägt die Statuszeile mit „Gemeinsame Runde: “ davor", () => {
    expect(statusIconLabel(roundStatus(CALM))).toBe("Gemeinsame Runde: Abgeglichen");
    expect(statusIconLabel(roundStatus({ ...CALM, problem: "OFFLINE", pending: 1 }))).toBe("Gemeinsame Runde: Kein Netz – 1 Änderung wartet");
  });
});

describe("Fehlertexte", () => {
  it("jeder Fehler hat seinen Text aus dem Vertrag; alles andere den allgemeinen", () => {
    expect(roundErrorText("INVALID_INVITE")).toBe("Die Einladung ist abgelaufen oder ungültig.");
    expect(roundErrorText("NAME_TAKEN")).toBe("Den Namen gibt es in der Runde schon.");
    expect(roundErrorText("INVALID_NAME")).toBe("Bitte einen Namen mit 1 bis 24 Zeichen eingeben.");
    expect(roundErrorText("INVALID_NAME", true)).toBe("Bitte einen Namen mit 1 bis 40 Zeichen eingeben.");
    expect(roundErrorText("PLAYER_TAKEN")).toBe("Diesen Spieler hat gerade jemand anders gewählt.");
    expect(roundErrorText("ALREADY_CLAIMED")).toBe("Du hast schon einen Namen in dieser Runde.");
    expect(roundErrorText("LIMIT_REACHED")).toBe("Mehr geht nicht: Die Grenze für Runden, Mitglieder oder Einladungen ist erreicht.");
    expect(roundErrorText("OFFLINE")).toBe("Keine Verbindung. Bitte später noch einmal versuchen.");
    expect(roundErrorText("SIGNED_OUT")).toBe("Anmeldung abgelaufen – bitte neu anmelden");
    expect(GENERIC_ERROR).toBe("Das hat nicht geklappt. Bitte später noch einmal versuchen.");
    expect(NO_CODE_ERROR).toBe("Das ist kein gültiger Einladungslink.");
    const own = new Set(["INVALID_INVITE", "NAME_TAKEN", "INVALID_NAME", "PLAYER_TAKEN", "ALREADY_CLAIMED", "LIMIT_REACHED", "OFFLINE", "SIGNED_OUT"]);
    for (const error of ROUND_ERRORS) {
      if (!own.has(error)) expect(roundErrorText(error), error).toBe(GENERIC_ERROR);
      expect(roundErrorText(error).length, error).toBeGreaterThan(10);
    }
  });
});

describe("Hinweise aus dem Abgleich", () => {
  it("jeder Grund hat seinen Text", () => {
    const notice = { player: "spieler-1", name: "Wirt" };
    expect(noticeText({ ...notice, reason: "NAME_LOCKED", nowName: "Nord" })).toBe("Nord hat den Namen selbst festgelegt. Er bleibt.");
    expect(noticeText({ ...notice, reason: "NAME_TAKEN", nowName: null })).toBe(
      "Den Namen Wirt gibt es in der Runde schon. Der neue Spieler wurde nicht angelegt.",
    );
    expect(noticeText({ ...notice, reason: "NAME_TAKEN", nowName: "Wirt 2" })).toBe(
      "Den Namen Wirt gibt es in der Runde schon. Der Spieler heißt jetzt Wirt 2.",
    );
    expect(noticeText({ ...notice, reason: "PLAYER_OWNED", nowName: "Wirt" })).toBe("Wirt gehört zu einem Mitglied und lässt sich nicht löschen.");
    expect(noticeText({ ...notice, reason: "LIMIT_REACHED", nowName: "Wirt" })).toBe("Die Runde hat schon 40 Spieler. Wirt wurde nicht hochgeladen.");
    expect(noticeText({ ...notice, reason: "INVALID_NAME", nowName: "Wirt" })).toBe("Der Name Wirt ist nicht erlaubt.");
    // Ein Spieler, den der Server aus einem anderen Grund nicht annimmt (zum Beispiel wegen eines Zeichens im Namen).
    expect(noticeText({ ...notice, reason: "INVALID_DOC", nowName: "Wirt" })).toBe("Der Name Wirt ist nicht erlaubt.");
  });
});

describe("Meldungen und Rückfragen", () => {
  it("die Meldungen", () => {
    expect(messageText("SIGN_IN_FAILED")).toBe("Die Anmeldung hat nicht geklappt. Bitte noch einmal versuchen.");
    expect(messageText("SIGN_IN_CANCELLED")).toBe("Die Anmeldung wurde abgebrochen.");
    expect(messageText("OTHER_ACCOUNT")).toBe(
      "Diese Runde gehört zu einem anderen Google-Konto. Melde dich mit dem Konto an, mit dem du der Runde beigetreten bist.",
    );
    expect(messageText("DETACHED")).toBe(
      "Die Runde gibt es nicht mehr, oder du gehörst nicht mehr dazu. Die Daten bleiben als Kopie auf diesem Gerät.",
    );
    // Eine geglückte Anmeldung erledigt die Meldungen zur Anmeldung, nicht aber die über eine aufgelöste Runde.
    expect([...SIGN_IN_MESSAGES].sort()).toEqual(["OTHER_ACCOUNT", "SIGN_IN_CANCELLED", "SIGN_IN_FAILED"]);
  });

  it("Zahlen in Einzahl und Mehrzahl", () => {
    expect(memberCountText(1)).toBe("1 Mitglied");
    expect(memberCountText(4)).toBe("4 Mitglieder");
    expect(replaceText(0)).toBe("Die Spieler dieses Geräts werden durch die der Runde ersetzt.");
    expect(replaceText(1)).toBe(
      "Auf diesem Gerät liegt 1 Abend. Er wird durch die Daten der Runde ersetzt. Sichere ihn vorher, wenn du ihn behalten willst.",
    );
    expect(replaceText(12)).toBe(
      "Auf diesem Gerät liegen 12 Abende. Sie werden durch die Daten der Runde ersetzt. Sichere sie vorher, wenn du sie behalten willst.",
    );
    expect(pendingWarning(1)).toBe("1 Änderung ist noch nicht hochgeladen und kommt nicht mehr in der Runde an.");
    expect(pendingWarning(3)).toBe("3 Änderungen sind noch nicht hochgeladen und kommen nicht mehr in der Runde an.");
  });

  it("der Text der Einladung und der Hinweis, wer gibt", () => {
    expect(inviteMessage("Stammtisch", "https://app.example/#/einladung/abc")).toBe(
      "Komm in unsere Schafkopfrunde Stammtisch: https://app.example/#/einladung/abc",
    );
    expect(dealerHint(true, "Nord")).toBe("Du gibst – trag dieses Spiel ein.");
    expect(dealerHint(false, "Nord")).toBe("Nord gibt und trägt ein.");
  });
});
