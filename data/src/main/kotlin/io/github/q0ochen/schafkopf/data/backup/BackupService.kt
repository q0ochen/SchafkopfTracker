package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.FileStorage
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Sicherung als Datei: alles in einen JSON-Text ausgeben und einen solchen Text wieder einspielen.
 *
 * Der Import führt zusammen und löscht nichts: Spieler und Abende, die es noch nicht gibt, kommen dazu;
 * was es schon gibt, bleibt unverändert. Die Einstellungen der App werden beim Import nicht angetastet.
 * Wo die Sicherung von Vorhandenem abweicht (schon vorhandene Spieler heißen dort anders, die Vorlage des
 * Regelwerks ist eine andere), nennt das die Vorschau in [ImportPreview.differingPlayers] und
 * [ImportPreview.defaultRules]; ob das übernommen wird, entscheidet der Aufrufer und speichert es dann selbst.
 * Keine der Funktionen wirft wegen einer unbrauchbaren Datei oder weil sich etwas nicht speichern lässt;
 * der Grund steht dann im Ergebnis.
 *
 * Alle Funktionen dürfen von der Oberfläche aus aufgerufen werden: Das Zerlegen und Zusammenbauen des Textes
 * läuft auf dem übergebenen Dispatcher, nicht auf dem Hauptthread.
 *
 * @param players Woher die Spieler kommen und wohin neue gespeichert werden.
 * @param evenings Woher die Abende kommen und wohin neue gespeichert werden.
 * @param settings Liefert die Vorlage des Regelwerks für die Sicherung; wird nie verändert.
 * @param appVersion Version der App; sie steht im Kopf jeder Sicherung.
 * @param dispatcher Darauf läuft die Rechenarbeit (Text zerlegen und zusammenbauen).
 */
class BackupService(
    private val players: PlayerRepository,
    private val evenings: EveningRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher,
) {
    /** Sicherung für den Datei-Speicher; die Rechenarbeit läuft auf dessen IO-Dispatcher. */
    constructor(storage: FileStorage, clock: Clock, appVersion: String) :
        this(storage.players, storage.evenings, storage.settings, clock, appVersion, storage.ioDispatcher)

    /** Sorgt dafür, dass zwei Importe (zum Beispiel durch doppeltes Tippen) nacheinander laufen. */
    private val importLock = Mutex()

    /**
     * Die Sicherung als UTF-8-JSON-Text: alle Spieler, alle Abende und die Vorlage des Regelwerks.
     * Die Einstellungen der KI-Zusammenfassung und der Schlüssel sind nie enthalten.
     */
    suspend fun exportJson(): String {
        // Erst die Abende, dann die Spieler lesen: Spieler werden nie gelöscht. Kommt zwischen den beiden Schritten
        // etwas dazu (zum Beispiel durch einen gleichzeitigen Import), steht so höchstens ein Spieler ohne Abend in
        // der Sicherung, aber nie ein Abend, dessen Spieler fehlt.
        val allEvenings = evenings.evenings.first()
        val allPlayers = players.players.first()
        val defaultRules = settings.settings.first().defaultRules
        val exportedAt = clock.instant()
        return withContext(dispatcher) {
            BackupFormat.write(allPlayers, allEvenings, defaultRules, appVersion, exportedAt)
        }
    }

    /** Zeigt, was ein Import dieser Sicherung bewirken würde. Speichert nichts. */
    suspend fun preview(json: String): ImportPreview {
        val knownPlayers = players.players.first()
        val knownEvenings = evenings.evenings.first()
        return withContext(dispatcher) { BackupFormat.read(json, knownPlayers, knownEvenings) }
    }

    /**
     * Spielt die Sicherung ein: legt neue Spieler und neue, gültige Abende an. Vorhandenes bleibt unverändert,
     * ungültige Abende werden ausgelassen. Ein zweiter Import derselben Datei ändert nichts mehr.
     *
     * Lässt sich unterwegs etwas nicht speichern, steht das in [ImportResult.error]; die Listen nennen dann,
     * was bis dahin gespeichert wurde.
     */
    suspend fun import(json: String): ImportResult = importLock.withLock {
        val plan = preview(json)
        if (plan.error != null) return@withLock ImportResult(error = plan.error)

        val savedPlayers = ArrayList<Player>()
        val savedEvenings = ArrayList<Evening>()
        val error = try {
            for (player in plan.newPlayers) {
                players.save(player)
                savedPlayers += player
            }
            for (evening in plan.newEvenings) {
                evenings.save(evening)
                savedEvenings += evening
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Egal, warum das Speichern scheitert (voller Speicher, Fehler der Datenbank): Die Oberfläche bekommt
            // ein Ergebnis mit verständlichem Text statt eines Absturzes.
            BackupError(
                BackupErrorKind.STORAGE_FAILED,
                "Die Sicherung konnte nicht vollständig eingespielt werden, weil sich die Daten nicht speichern ließen. " +
                    "Ist der Speicher voll?",
            )
        }

        ImportResult(
            error = error,
            newPlayers = savedPlayers,
            sameNamePlayers = plan.sameNamePlayers.filter { it in savedPlayers },
            existingPlayerCount = plan.existingPlayerCount,
            newEvenings = savedEvenings,
            existingEvenings = plan.existingEvenings,
            invalidEvenings = plan.invalidEvenings,
        )
    }
}
