package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType

/**
 * Eine Quote mit Zähler und Nenner: wie oft etwas vorkam ([played]) und wie oft es gut ausging ([won]).
 */
data class RoleRecord(val played: Int, val won: Int) {
    /** Wie oft es schlecht ausging. */
    val lost: Int get() = played - won

    /** Anteil der Siege zwischen 0 und 1; `null`, wenn es keinen einzigen Fall gab. */
    val rate: Double? get() = if (played == 0) null else won.toDouble() / played
}

/**
 * Ein einzelner Betrag aus einem gespielten Spiel, samt der Stelle, an der das Spiel zu finden ist.
 *
 * @property amount Betrag in Cent (positiv = gewonnen, negativ = verloren).
 */
data class GameAmount(val eveningId: EveningId, val entryId: EntryId, val gameNumber: Int, val amount: Int)

/** Der Saldo eines Spielers an einem Abend in Cent. */
data class EveningBalance(val eveningId: EveningId, val balance: Int)

/**
 * Alle Kennzahlen eines Spielers, für einen Abend oder über viele Abende hinweg.
 *
 * Gezählt werden gespielte Spiele, die abgerechnet werden konnten. „Gewonnen“ heißt immer: Der Spieler stand auf
 * der Gewinnerseite, egal in welcher Rolle. Beträge sind ganze Cent.
 *
 * @property evenings Abende, an denen der Spieler am Tisch saß und mindestens ein Spiel gespielt wurde.
 * @property games Gespielte Spiele am Tisch.
 * @property balance Summe aller Beträge, auch aus freien Buchungen.
 * @property gamesWon Spiele auf der Gewinnerseite.
 * @property asDeclarer Spiele als Ansager (Spieler).
 * @property asPartner Rufspiele als Mitspieler des Ansagers.
 * @property asOpponent Spiele als Gegenspieler.
 * @property declarerByType Spiele als Ansager, getrennt nach Spielart. Jede Spielart ist enthalten, notfalls mit 0.
 * @property klopferGiven Spiele, in denen der Spieler geklopft (gelegt) hat, und wie viele davon er gewann.
 * @property klopferEffect Was das eigene Klopfen gebracht oder gekostet hat: Über alle selbst geklopften Spiele die
 *   Summe aus gebuchtem Betrag minus dem Betrag desselben Spiels ohne den eigenen Klopfer. Weil jeder Klopfer
 *   verdoppelt, ist das genau die Hälfte des gebuchten Betrags.
 * @property kontraGiven Spiele, in denen der Spieler Kontra gegeben hat (nur wenn eingetragen ist, wer es war).
 * @property retourGiven Spiele, in denen der Spieler Retour gegeben hat (nur wenn eingetragen ist, wer es war).
 * @property schneiderWon Spiele mit Schneider auf der Gewinnerseite. Bei Tout und Sie zählt die Stufe nicht.
 * @property schneiderLost Spiele mit Schneider auf der Verliererseite.
 * @property schwarzWon Spiele mit Schwarz auf der Gewinnerseite. Schwarz zählt nicht zusätzlich als Schneider.
 * @property schwarzLost Spiele mit Schwarz auf der Verliererseite.
 * @property toutPlayed Tout und Sie als Ansager.
 * @property biggestWin Höchster Einzelbetrag aus einem Spiel; `null`, wenn es keinen Gewinn gab.
 * @property biggestLoss Niedrigster Einzelbetrag aus einem Spiel; `null`, wenn es keinen Verlust gab.
 * @property longestWinStreak Längste Folge gewonnener Spiele innerhalb eines Abends. Zusammengeworfene Blätter,
 *   Bock-Auslöser und freie Buchungen unterbrechen die Folge nicht.
 * @property longestLoseStreak Längste Folge verlorener Spiele innerhalb eines Abends.
 * @property nixerTriggers Wie oft der Spieler mit 6 Nixern eine Bockrunde ausgelöst hat.
 * @property eveningWins Abende auf Platz 1, auch geteilt. Stehen alle gleich, gibt es keinen Sieger.
 * @property eveningLastPlaces Abende auf dem letzten Platz, auch geteilt. Stehen alle gleich, gibt es keinen Letzten.
 * @property bestEvening Abend mit dem höchsten Saldo; `null`, wenn kein Abend zählt.
 * @property worstEvening Abend mit dem niedrigsten Saldo; `null`, wenn kein Abend zählt.
 */
data class PlayerStats(
    val player: PlayerId,
    val evenings: Int,
    val games: Int,
    val balance: Int,
    val gamesWon: Int,
    val asDeclarer: RoleRecord,
    val asPartner: RoleRecord,
    val asOpponent: RoleRecord,
    val declarerByType: Map<GameType, RoleRecord>,
    val klopferGiven: RoleRecord,
    val klopferEffect: Int,
    val kontraGiven: RoleRecord,
    val retourGiven: RoleRecord,
    val schneiderWon: Int,
    val schneiderLost: Int,
    val schwarzWon: Int,
    val schwarzLost: Int,
    val toutPlayed: RoleRecord,
    val biggestWin: GameAmount?,
    val biggestLoss: GameAmount?,
    val longestWinStreak: Int,
    val longestLoseStreak: Int,
    val nixerTriggers: Int,
    val eveningWins: Int,
    val eveningLastPlaces: Int,
    val bestEvening: EveningBalance?,
    val worstEvening: EveningBalance?,
) {
    /** Spiele auf der Verliererseite. */
    val gamesLost: Int get() = games - gamesWon

    /** Alle Spiele als Quote: gespielt und gewonnen. */
    val overall: RoleRecord get() = RoleRecord(games, gamesWon)

    /** Wie oft der Spieler geklopft hat. */
    val klopferCount: Int get() = klopferGiven.played

    /** Wie oft der Spieler nach dem eigenen Klopfen verloren hat. */
    val klopferLost: Int get() = klopferGiven.lost
}
