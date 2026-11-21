package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.PlayerId
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/**
 * Was sich an einem Abend nachträglich ändern lässt (Bildschirm „Abend bearbeiten“): wann er begann, wo er war, die
 * Sitzordnung und wer das erste Spiel gegeben hat.
 *
 * Wer am Tisch sitzt, bleibt, denn die Einträge nennen die Spieler. Die Einträge selbst bleiben ebenso; das Regelwerk
 * hat einen eigenen Bildschirm. Weil Beträge, Salden und Geber jedes Mal neu berechnet werden (siehe
 * [EveningCalculator]), ändern sich mit der Sitzordnung und dem ersten Geber nur die Geber, keine Beträge.
 */
data class EveningDetails(
    val startedAt: Instant,
    val location: String,
    val seating: List<PlayerId>,
    val firstDealer: PlayerId,
) {
    companion object {
        /**
         * Höchstlänge des Orts in Zeichen, wie auf dem Bildschirm „Neuer Abend“. Seit dem 21.11.2026 100 statt 40,
         * damit ein Ortsvorschlag mit Adresse und Postleitzahl hineinpasst (docs/ARCHITEKTUR.md, Abschnitt 4.3). Das
         * Dateiformat prüft die Länge nicht; ältere Versionen lesen längere Orte deshalb ohne Weiteres.
         */
        const val MAX_LOCATION_LENGTH = 100

        fun of(evening: Evening): EveningDetails =
            EveningDetails(evening.startedAt, evening.location, evening.seating, evening.firstDealer)
    }
}

/**
 * Nachträgliche Änderungen an einem Abend (docs/ARCHITEKTUR.md, Abschnitt 2, „Abend“). Reine Funktion; der Port für die
 * Web-App liegt in `web/src/core/evening/EveningEdit.ts` und wird mit den Golden-Daten geprüft.
 */
object EveningEdit {

    /**
     * Übernimmt in [current] nur, was zwischen [original] (dem Stand beim Öffnen von „Abend bearbeiten“) und [edited]
     * geändert wurde. Alles andere bleibt, wie es inzwischen ist: In der gemeinsamen Runde kann ein anderes Handy den
     * Abend in der Zwischenzeit geändert haben, und das soll nicht mit dem alten Stand überschrieben werden.
     *
     * - **Beginn:** wird übernommen. Ein beendeter Abend behält seine Dauer, das Ende verschiebt sich mit (läge es
     *   außerhalb des Bereichs von [Instant], bleibt es). Die Einträge behalten ihre Zeiten.
     * - **Ort:** ohne Leerraum an den Rändern und höchstens [EveningDetails.MAX_LOCATION_LENGTH] Zeichen lang.
     * - **Sitzordnung:** nur, wenn es genau die Spieler am Tisch sind, jeder einmal.
     * - **Erster Geber:** nur, wenn er danach am Tisch sitzt.
     */
    fun apply(current: Evening, original: EveningDetails, edited: EveningDetails): Evening {
        var result = current
        if (edited.startedAt != original.startedAt) {
            result = result.copy(
                startedAt = edited.startedAt,
                finishedAt = current.finishedAt?.let { shifted(it, Duration.between(current.startedAt, edited.startedAt)) },
            )
        }
        if (edited.location != original.location) {
            result = result.copy(location = cleanLocation(edited.location))
        }
        if (edited.seating != original.seating && isRearrangement(edited.seating, current.seating)) {
            result = result.copy(seating = edited.seating)
        }
        if (edited.firstDealer != original.firstDealer && edited.firstDealer in result.seating) {
            result = result.copy(firstDealer = edited.firstDealer)
        }
        return result
    }

    /** Der Ort, wie er gespeichert wird: ohne Leerraum an den Rändern, höchstens [EveningDetails.MAX_LOCATION_LENGTH] Zeichen. */
    fun cleanLocation(location: String): String = location.trim().take(EveningDetails.MAX_LOCATION_LENGTH).trim()

    private fun shifted(instant: Instant, shift: Duration): Instant =
        try {
            instant.plus(shift)
        } catch (_: DateTimeException) {
            instant
        }

    /** Ob [seating] genau die Spieler von [table] sind, jeder einmal, in beliebiger Reihenfolge. */
    private fun isRearrangement(seating: List<PlayerId>, table: List<PlayerId>): Boolean =
        seating.size == table.size && seating.toSet().size == seating.size && seating.toSet() == table.toSet()
}
