package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val PLAYERS_FILE = "players.json"
private const val SETTINGS_FILE = "settings.json"
private const val EVENINGS_FOLDER = "evenings"
private const val JSON_SUFFIX = ".json"
private const val TEMP_SUFFIX = ".tmp"
private const val BROKEN_SUFFIX = ".defekt"

/** Längere Kennungen werden im Dateinamen gekürzt, damit der Name auf jedem Dateisystem erlaubt ist. */
private const val MAX_STEM_LENGTH = 80

/** Der neueste Abend zuerst; bei gleichem Beginn entscheidet die Kennung, damit die Reihenfolge feststeht. */
internal val NEWEST_FIRST: Comparator<Evening> =
    compareByDescending<Evening> { it.startedAt }.thenBy { it.id.value }

/**
 * Alles, was die App im Speicher hält: der Inhalt sämtlicher Dateien.
 *
 * @property evenings Die Abende, der neueste zuerst.
 * @property eveningFiles In welcher Datei jeder Abend liegt.
 */
internal data class Snapshot(
    val players: List<Player>,
    val evenings: List<Evening>,
    val settings: AppSettings,
    val eveningFiles: Map<EveningId, Path>,
)

/** Setzt eine fertig geschriebene Datei an ihren Platz. In Tests austauschbar. */
internal fun interface FileMover {
    /** @param atomic `true`: in einem einzigen, unteilbaren Schritt ersetzen. */
    fun move(source: Path, target: Path, atomic: Boolean)
}

internal val NioFileMover = FileMover { source, target, atomic ->
    if (atomic) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Macht aus einer Kennung den Anfang eines ungefährlichen Dateinamens: Buchstaben, Ziffern, `_` und `-` bleiben,
 * jedes andere Zeichen wird zu `_`. So kann aus einer Kennung wie `../x` kein Pfad entstehen.
 */
internal fun safeFileStem(id: String): String {
    val stem = buildString {
        for (character in id.take(MAX_STEM_LENGTH)) append(if (character.isSafeInFileName()) character else '_')
    }
    return stem.ifEmpty { "_" }
}

private fun Char.isSafeInFileName(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == '-'

/**
 * Die Dateien der App: `players.json`, `settings.json` und je Abend eine Datei im Ordner `evenings`.
 *
 * Alle Funktionen arbeiten sofort und blockierend auf dem Datenträger; [FileStorage] ruft sie nur auf dem
 * IO-Dispatcher und immer nur eine zur selben Zeit auf.
 */
internal class StorageFiles(directory: Path, private val mover: FileMover = NioFileMover) {

    private val eveningsFolder: Path = directory.resolve(EVENINGS_FOLDER)
    private val playersFile: Path = directory.resolve(PLAYERS_FILE)
    private val settingsFile: Path = directory.resolve(SETTINGS_FILE)

    /**
     * Liest alle Dateien. Wirft nie: Was sich nicht lesen lässt, wird nach `<Name>.defekt` verschoben und
     * übersprungen; was fehlt, ist leer bzw. bekommt die Standardeinstellungen.
     */
    fun load(): Snapshot {
        createFolders()
        recoverInterruptedWrites()

        val players = read(playersFile) { StorageCodec.decodePlayers(it) }.orEmpty().distinctBy { it.id }
        val settings = read(settingsFile) { StorageCodec.decodeSettings(it) } ?: AppSettings()

        val evenings = LinkedHashMap<EveningId, Evening>()
        val eveningFiles = LinkedHashMap<EveningId, Path>()
        for (file in filesIn(eveningsFolder, JSON_SUFFIX)) {
            val evening = read(file) { StorageCodec.decodeEvening(it) } ?: continue
            val earlierFile = eveningFiles[evening.id]
            if (earlierFile != null) {
                // Zwei Dateien nennen denselben Abend. Es gilt die mit dem regulären Namen, die andere wird beiseitegelegt.
                if (file.fileName.toString() == regularFileName(evening.id)) {
                    quarantine(earlierFile)
                } else {
                    quarantine(file)
                    continue
                }
            }
            evenings[evening.id] = evening
            eveningFiles[evening.id] = file
        }
        return Snapshot(players, evenings.values.sortedWith(NEWEST_FIRST), settings, eveningFiles)
    }

    fun writePlayers(players: List<Player>) = replaceFile(playersFile, StorageCodec.encodePlayers(players))

    fun writeSettings(settings: AppSettings) = replaceFile(settingsFile, StorageCodec.encodeSettings(settings))

    /**
     * Schreibt einen Abend in seine Datei.
     *
     * @param known In welcher Datei jeder schon gespeicherte Abend liegt.
     * @return die Datei, in der der Abend jetzt liegt.
     */
    fun writeEvening(evening: Evening, known: Map<EveningId, Path>): Path {
        val file = known[evening.id] ?: freeFileFor(evening.id, known.values)
        replaceFile(file, StorageCodec.encodeEvening(evening))
        return file
    }

    fun delete(file: Path) {
        Files.deleteIfExists(file)
        // Eine liegengebliebene Hilfsdatei darf das Gelöschte beim nächsten Laden nicht wiederbeleben.
        deleteQuietly(tempFileFor(file))
        syncFolder(file.parent)
    }

    private fun tempFileFor(target: Path): Path = target.resolveSibling(target.fileName.toString() + TEMP_SUFFIX)

    /**
     * Der reguläre Dateiname ist die bereinigte Kennung. Ergeben zwei verschiedene Kennungen denselben Namen
     * (zum Beispiel `a/b` und `a_b`), bekommt der spätere Abend eine laufende Nummer angehängt, damit kein
     * Abend einen anderen überschreibt.
     */
    private fun freeFileFor(id: EveningId, taken: Collection<Path>): Path {
        val takenNames = taken.mapTo(HashSet()) { it.fileName.toString() }
        val stem = safeFileStem(id.value)
        return generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) "$stem$JSON_SUFFIX" else "${stem}_$number$JSON_SUFFIX" }
            .filterNot { it in takenNames }
            .map { eveningsFolder.resolve(it) }
            .first { !Files.exists(it) }
    }

    private fun regularFileName(id: EveningId): String = safeFileStem(id.value) + JSON_SUFFIX

    private fun createFolders() {
        try {
            Files.createDirectories(eveningsFolder)
        } catch (e: IOException) {
            // Beim Schreiben wird es erneut versucht; erst dort wird der Fehler gemeldet.
        }
    }

    /**
     * Räumt auf, was ein abgebrochener Schreibvorgang (Akku leer) hinterlassen hat: eine Hilfsdatei.
     *
     * Gibt es die eigentliche Datei noch, ist sie der letzte bestätigte Stand und die Hilfsdatei wird gelöscht.
     * Fehlt sie, tritt eine vollständig geschriebene Hilfsdatei an ihre Stelle, denn dann ist sie das Einzige,
     * was vom letzten Schreibvorgang übrig ist. Eine nur halb geschriebene Hilfsdatei wird gelöscht.
     *
     * Angefasst werden nur die Hilfsdateien dieses Speichers (`players.json.tmp`, `settings.json.tmp` und
     * `evenings/<Name>.json.tmp`). Was sonst noch auf `.tmp` endet, gehört jemand anderem und bleibt liegen.
     */
    private fun recoverInterruptedWrites() {
        val ownTempFiles = listOf(playersFile, settingsFile).map { tempFileFor(it) }.filter { Files.isRegularFile(it) } +
            filesIn(eveningsFolder, JSON_SUFFIX + TEMP_SUFFIX)
        for (temp in ownTempFiles) {
            val target = temp.resolveSibling(temp.fileName.toString().removeSuffix(TEMP_SUFFIX))
            if (!Files.exists(target) && isCompleteFile(temp)) {
                try {
                    Files.move(temp, target)
                } catch (e: IOException) {
                    deleteQuietly(temp)
                }
            } else {
                deleteQuietly(temp)
            }
        }
    }

    /** Ob die Datei von Anfang bis Ende geschrieben wurde. Eine abgeschnittene Datei ist kein gültiges JSON. */
    private fun isCompleteFile(file: Path): Boolean = try {
        StorageCodec.isJsonObject(Files.readAllBytes(file).decodeToString())
    } catch (e: IOException) {
        false
    }

    /** Die Dateien eines Ordners mit dieser Endung, nach Namen sortiert; leer, wenn sich der Ordner nicht lesen lässt. */
    private fun filesIn(folder: Path, suffix: String): List<Path> = try {
        Files.newDirectoryStream(folder).use { entries ->
            entries
                .filter { it.fileName.toString().endsWith(suffix) && Files.isRegularFile(it) }
                .sortedBy { it.fileName.toString() }
        }
    } catch (e: IOException) {
        emptyList()
    } catch (e: DirectoryIteratorException) {
        emptyList()
    }

    /** `null`, wenn es die Datei nicht gibt oder sie unlesbar ist (dann liegt sie danach unter `<Name>.defekt`). */
    private fun <T : Any> read(file: Path, decode: (String) -> T): T? {
        if (!Files.isRegularFile(file)) return null
        return try {
            decode(Files.readAllBytes(file).decodeToString())
        } catch (e: IOException) {
            quarantine(file)
            null
        } catch (e: UnreadableDataException) {
            quarantine(file)
            null
        }
    }

    /** Legt eine unlesbare Datei beiseite, statt sie zu löschen; eine ältere `.defekt`-Datei wird ersetzt. */
    private fun quarantine(file: Path) {
        try {
            val target = file.resolveSibling(file.fileName.toString() + BROKEN_SUFFIX)
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            // Die Datei bleibt liegen und wird beim nächsten Start wieder übersprungen.
        }
    }

    /**
     * Ersetzt eine Datei so, dass zu jedem Zeitpunkt entweder der alte oder der neue Inhalt vollständig da ist:
     * erst daneben in eine Hilfsdatei schreiben, diese auf den Datenträger zwingen, dann in einem Schritt
     * an die Stelle der alten Datei setzen.
     */
    private fun replaceFile(target: Path, content: String) {
        val folder = target.parent
        Files.createDirectories(folder)
        val temp = tempFileFor(target)
        var tempIsLastCopy = false
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val bytes = ByteBuffer.wrap(content.encodeToByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                mover.move(temp, target, atomic = true)
            } catch (e: AtomicMoveNotSupportedException) {
                // Ersatzweg für Dateisysteme ohne unteilbares Ersetzen: Dabei wird erst die alte Datei entfernt und
                // dann die neue eingesetzt. Geht dazwischen etwas schief, ist die Hilfsdatei die einzige Fassung,
                // die es noch gibt. Sie bleibt dann liegen, und das nächste Laden setzt sie an ihren Platz.
                val existedBefore = Files.exists(target)
                try {
                    mover.move(temp, target, atomic = false)
                } catch (failure: IOException) {
                    tempIsLastCopy = existedBefore && !Files.exists(target)
                    throw failure
                }
            }
        } finally {
            if (!tempIsLastCopy) deleteQuietly(temp)
        }
        syncFolder(folder)
    }

    /**
     * Sorgt dafür, dass auch das Umbenennen oder Löschen einen Stromausfall übersteht. Nicht jedes Dateisystem
     * erlaubt das (Windows zum Beispiel nicht); der Inhalt der Datei liegt dann trotzdem schon sicher.
     * Ein Fehler hier darf das Speichern deshalb nie scheitern lassen.
     */
    private fun syncFolder(folder: Path) {
        try {
            FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Nur eine zusätzliche Absicherung, siehe oben.
        }
    }

    private fun deleteQuietly(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            // Eine liegengebliebene Hilfsdatei stört nicht; sie wird beim nächsten Start entfernt.
        }
    }
}
