package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import java.time.Instant

/** Warum eine Sicherung nicht gelesen oder nicht vollständig eingespielt werden konnte. */
enum class BackupErrorKind {
    /** Die Datei ist kein gültiges JSON, zum Beispiel abgeschnitten oder eine ganz andere Datei. */
    INVALID_JSON,

    /** Die Datei ist lesbar, aber keine Sicherung dieser App (oder ihr Aufbau ist beschädigt). */
    WRONG_FORMAT,

    /** Die Sicherung stammt aus einer neueren Version der App. */
    NEWER_VERSION,

    /** Beim Speichern auf dem Gerät ist etwas schiefgegangen, zum Beispiel weil der Speicher voll ist. */
    STORAGE_FAILED,
}

/**
 * Ein Fehler beim Lesen oder Einspielen einer Sicherung.
 *
 * @property message Fertiger deutscher Satz für die Anzeige.
 */
data class BackupError(val kind: BackupErrorKind, val message: String)

/** Warum ein einzelner Abend aus der Sicherung nicht übernommen wird. */
enum class EveningProblem {
    /** Der Abend ist beschädigt: kein lesbarer Aufbau, fehlende Angaben, unbekannte Werte oder unmögliche Zeitpunkte. */
    UNREADABLE,

    /** Der Abend wurde mit einer neueren Version der App gespeichert. */
    NEWER_VERSION,

    /** Dieselbe Kennung steht mehrfach in der Sicherung; nur der erste Abend wird übernommen. */
    DUPLICATE_ID,

    /** Am Tisch sitzen nicht genau vier verschiedene Spieler, oder einer von ihnen hat keine Kennung. */
    WRONG_SEATING,

    /** Der erste Geber sitzt nicht am Tisch. */
    DEALER_NOT_AT_TABLE,

    /** In einem Eintrag kommt ein Spieler vor, der nicht am Tisch sitzt. */
    UNKNOWN_PLAYER,

    /** Die Beträge einer freien Buchung ergeben zusammen nicht null. */
    MANUAL_NOT_ZERO_SUM,

    /**
     * Die Beträge eines Spiels ergeben zusammen nicht null, zum Beispiel weil der Ansager als sein eigener
     * Mitspieler eingetragen ist.
     */
    GAME_NOT_ZERO_SUM,

    /**
     * Ein Spielwert oder ein Saldo liegt außerhalb des Bereichs, mit dem die App rechnen kann (negativ oder
     * unsinnig groß). Der Eintrag bucht dann nichts und fehlte stillschweigend in den Salden des Abends.
     */
    AMOUNT_OUT_OF_RANGE,

    /** Zwei Einträge des Abends tragen dieselbe Kennung; jeder Eintrag braucht seine eigene. */
    DUPLICATE_ENTRY_ID,
}

/**
 * Ein Abend aus der Sicherung, der nicht übernommen wird.
 *
 * @property id Kennung des Abends, soweit sie lesbar war; sonst leer.
 * @property startedAt Beginn des Abends, soweit er lesbar war.
 * @property reason Verständlicher deutscher Satz für die Anzeige.
 */
data class InvalidEvening(
    val id: String,
    val startedAt: Instant?,
    val problem: EveningProblem,
    val reason: String,
)

/**
 * Was ein Import bewirken würde, ohne dass etwas gespeichert wird.
 *
 * @property error Gesetzt, wenn die Datei gar nicht als Sicherung lesbar ist; alle Listen sind dann leer.
 * @property exportedAt Wann die Sicherung erstellt wurde, soweit lesbar.
 * @property appVersion Version der App, die die Sicherung erstellt hat.
 * @property newPlayers Spieler, die angelegt würden.
 * @property sameNamePlayers Der Teil von [newPlayers], der so heißt wie ein schon vorhandener Spieler mit
 *   anderer Kennung. Sie werden trotzdem als eigene Spieler angelegt.
 * @property existingPlayerCount Spieler der Sicherung, die es schon gibt; sie bleiben, wie sie sind.
 * @property differingPlayers Der Teil der schon vorhandenen Spieler, der in der Sicherung anders heißt oder anders
 *   aussieht (Name, Farbe, archiviert), jeweils in der Fassung der Sicherung. Der Import lässt auch sie, wie sie
 *   sind. Wer die Fassung der Sicherung will, speichert diese Spieler anschließend selbst; das ist vor allem beim
 *   Zurückspielen auf ein neues Gerät nötig, auf dem die vorbelegten Spieler noch ihre Platzhalternamen tragen.
 * @property newEvenings Abende, die angelegt würden.
 * @property existingEvenings Abende, die es schon gibt (in ihrer vorhandenen Fassung); sie bleiben, wie sie sind.
 * @property invalidEvenings Abende, die nicht übernommen werden, jeweils mit Grund.
 * @property defaultRules Die Vorlage des Regelwerks aus der Sicherung; `null`, wenn sie fehlt oder sich nicht lesen
 *   lässt. Der Import übernimmt sie nicht, weil er die Einstellungen nie antastet; wer sie will, setzt sie selbst.
 */
data class ImportPreview(
    val error: BackupError? = null,
    val exportedAt: Instant? = null,
    val appVersion: String = "",
    val newPlayers: List<Player> = emptyList(),
    val sameNamePlayers: List<Player> = emptyList(),
    val existingPlayerCount: Int = 0,
    val differingPlayers: List<Player> = emptyList(),
    val newEvenings: List<Evening> = emptyList(),
    val existingEvenings: List<Evening> = emptyList(),
    val invalidEvenings: List<InvalidEvening> = emptyList(),
    val defaultRules: RuleSet? = null,
) {
    /** `true`, wenn die Datei eine lesbare Sicherung ist. */
    val isReadable: Boolean get() = error == null

    /** `true`, wenn ein Import etwas hinzufügen würde. */
    val hasNewData: Boolean get() = newPlayers.isNotEmpty() || newEvenings.isNotEmpty()

    val newPlayerCount: Int get() = newPlayers.size
    val sameNamePlayerCount: Int get() = sameNamePlayers.size
    val differingPlayerCount: Int get() = differingPlayers.size
    val newEveningCount: Int get() = newEvenings.size
    val existingEveningCount: Int get() = existingEvenings.size
    val invalidEveningCount: Int get() = invalidEvenings.size
}

/**
 * Was ein Import tatsächlich bewirkt hat. Der Aufbau entspricht [ImportPreview].
 *
 * @property error Gesetzt, wenn die Datei nicht lesbar war (dann wurde nichts gespeichert) oder wenn das
 *   Speichern unterwegs scheiterte (dann nennen die Listen, was bis dahin gespeichert wurde).
 * @property newPlayers Spieler, die angelegt wurden.
 * @property sameNamePlayers Der Teil von [newPlayers], der so heißt wie ein schon vorhandener Spieler.
 * @property existingPlayerCount Spieler der Sicherung, die es schon gab; sie wurden nicht verändert.
 * @property newEvenings Abende, die angelegt wurden.
 * @property existingEvenings Abende, die es schon gab; sie wurden nicht verändert.
 * @property invalidEvenings Abende, die nicht übernommen wurden, jeweils mit Grund.
 */
data class ImportResult(
    val error: BackupError? = null,
    val newPlayers: List<Player> = emptyList(),
    val sameNamePlayers: List<Player> = emptyList(),
    val existingPlayerCount: Int = 0,
    val newEvenings: List<Evening> = emptyList(),
    val existingEvenings: List<Evening> = emptyList(),
    val invalidEvenings: List<InvalidEvening> = emptyList(),
) {
    /** `true`, wenn der Import ohne Fehler durchgelaufen ist (auch wenn es nichts Neues gab). */
    val isSuccess: Boolean get() = error == null

    val newPlayerCount: Int get() = newPlayers.size
    val sameNamePlayerCount: Int get() = sameNamePlayers.size
    val newEveningCount: Int get() = newEvenings.size
    val existingEveningCount: Int get() = existingEvenings.size
    val invalidEveningCount: Int get() = invalidEvenings.size
}
