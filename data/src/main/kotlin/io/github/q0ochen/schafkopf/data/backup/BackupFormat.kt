package io.github.q0ochen.schafkopf.data.backup

import io.github.q0ochen.schafkopf.data.storage.BYTE_ORDER_MARK
import io.github.q0ochen.schafkopf.data.storage.EveningDto
import io.github.q0ochen.schafkopf.data.storage.NewerSchemaException
import io.github.q0ochen.schafkopf.data.storage.PlayerDto
import io.github.q0ochen.schafkopf.data.storage.RuleSetDto
import io.github.q0ochen.schafkopf.data.storage.StorageCodec
import io.github.q0ochen.schafkopf.data.storage.UnreadableDataException
import io.github.q0ochen.schafkopf.data.storage.parseInstant
import io.github.q0ochen.schafkopf.data.storage.toDto
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.DateTimeException
import java.time.Instant

/**
 * Aufbau der Sicherungsdatei. Sie enthält die Spieler, die Abende und die Vorlage des Regelwerks, aber nie
 * die Einstellungen der KI-Zusammenfassung und nie einen Schlüssel.
 */
@Serializable
internal data class BackupDto(
    @SerialName("format") val format: String,
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("appVersion") val appVersion: String,
    @SerialName("exportedAt") val exportedAt: String,
    @SerialName("players") val players: List<PlayerDto>,
    @SerialName("evenings") val evenings: List<EveningDto>,
    @SerialName("defaultRules") val defaultRules: RuleSetDto,
)

/** Befund zu einem Abend, der so nicht übernommen werden kann. */
internal data class EveningFinding(val problem: EveningProblem, val reason: String)

/** Schreibt und liest die Sicherungsdatei. Reine Funktionen ohne Zugriff auf den Datenträger. */
internal object BackupFormat {

    /** Kennzeichen im Kopf der Datei, an dem die App ihre eigenen Sicherungen erkennt. */
    const val NAME = "schafkopf-tracker-backup"

    /** Version des Aufbaus der Sicherungsdatei. */
    const val VERSION = 1

    /** Für Menschen lesbar eingerückt, damit sich die Datei notfalls auch von Hand ansehen lässt. */
    private val readableJson = Json(StorageCodec.json) { prettyPrint = true }

    fun write(
        players: List<Player>,
        evenings: List<Evening>,
        defaultRules: RuleSet,
        appVersion: String,
        exportedAt: Instant,
    ): String = readableJson.encodeToString(
        BackupDto.serializer(),
        BackupDto(
            format = NAME,
            schemaVersion = VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt.toString(),
            players = players.map { it.toDto() },
            evenings = evenings.sortedBy { it.startedAt }.map { it.toDto() },
            defaultRules = defaultRules.toDto(),
        ),
    )

    /**
     * Liest eine Sicherung und vergleicht sie mit dem, was es schon gibt. Wirft nie: Ist die Datei als Ganzes
     * nicht brauchbar, steht der Grund in [ImportPreview.error]; ist ein einzelner Abend nicht brauchbar,
     * steht er mit Grund in [ImportPreview.invalidEvenings].
     */
    fun read(text: String, knownPlayers: List<Player>, knownEvenings: List<Evening>): ImportPreview {
        val root = try {
            StorageCodec.json.parseToJsonElement(text.removePrefix(BYTE_ORDER_MARK))
        } catch (e: IllegalArgumentException) {
            return notJson()
        } catch (e: StackOverflowError) {
            // Eine absichtlich extrem tief verschachtelte Datei darf die App nicht zum Absturz bringen.
            return notJson()
        }
        if (root !is JsonObject || root.text("format") != NAME) {
            return failure(BackupErrorKind.WRONG_FORMAT, "Die Datei ist keine Sicherung dieser App.")
        }

        // Fehlt die Angabe, gilt die erste Version; steht dort etwas anderes als eine Zahl, ist die Datei beschädigt.
        val version = when (val element = root["schemaVersion"]) {
            null -> VERSION
            else -> (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        }
        if (version == null || version < 1) {
            return failure(
                BackupErrorKind.WRONG_FORMAT,
                "Die Sicherung ist beschädigt: Die Angabe zur Version ist ungültig.",
            )
        }
        if (version > VERSION) {
            return failure(
                BackupErrorKind.NEWER_VERSION,
                "Die Sicherung stammt aus einer neueren Version der App. " +
                    "Bitte die App aktualisieren und es dann noch einmal versuchen.",
            )
        }

        val importedPlayers = try {
            StorageCodec.decodePlayerList(root["players"] ?: JsonArray(emptyList())).distinctBy { it.id }
        } catch (e: UnreadableDataException) {
            return unreadablePlayers()
        }
        // Eine leere Kennung ist so viel wert wie eine fehlende: Der Spieler ließe sich später niemandem zuordnen.
        if (importedPlayers.any { it.id.value.isBlank() }) return unreadablePlayers()
        val eveningElements: List<JsonElement> = when (val element = root["evenings"]) {
            null -> emptyList()
            is JsonArray -> element
            else -> return failure(
                BackupErrorKind.WRONG_FORMAT,
                "Die Sicherung ist beschädigt: Die Liste der Abende lässt sich nicht lesen.",
            )
        }

        val knownPlayersById = knownPlayers.associateBy { it.id }
        val newPlayers = importedPlayers.filter { it.id !in knownPlayersById }
        // Dieselbe Kennung, aber anderer Name, andere Farbe oder anderer Archiv-Vermerk als auf diesem Gerät.
        val differingPlayers = importedPlayers.filter { player ->
            val known = knownPlayersById[player.id]
            known != null && known != player
        }
        val knownById = knownEvenings.associateBy { it.id }
        val newEvenings = LinkedHashMap<EveningId, Evening>()
        val existingEvenings = LinkedHashMap<EveningId, Evening>()
        val invalidEvenings = ArrayList<InvalidEvening>()

        for (element in eveningElements) {
            val raw = element as? JsonObject
            val rawId = raw?.text("id").orEmpty()
            val existing = knownById[EveningId(rawId)]
            if (existing != null) {
                existingEvenings[existing.id] = existing
                continue
            }
            val finding = try {
                val evening = StorageCodec.decodeEveningElement(element)
                val problem = findingFor(evening, newEvenings.keys)
                if (problem == null) newEvenings[evening.id] = evening
                problem
            } catch (e: NewerSchemaException) {
                EveningFinding(
                    EveningProblem.NEWER_VERSION,
                    "Der Abend wurde mit einer neueren Version der App gespeichert.",
                )
            } catch (e: UnreadableDataException) {
                EveningFinding(EveningProblem.UNREADABLE, "Der Abend ist beschädigt und lässt sich nicht lesen.")
            }
            if (finding != null) {
                invalidEvenings += InvalidEvening(
                    id = rawId,
                    startedAt = raw?.text("startedAt")?.let { instantOrNull(it) },
                    problem = finding.problem,
                    reason = finding.reason,
                )
            }
        }

        return ImportPreview(
            exportedAt = root.text("exportedAt")?.let { instantOrNull(it) },
            appVersion = root.text("appVersion").orEmpty(),
            newPlayers = newPlayers,
            sameNamePlayers = newPlayers.filter { candidate -> knownPlayers.any { sameName(it.name, candidate.name) } },
            existingPlayerCount = importedPlayers.size - newPlayers.size,
            differingPlayers = differingPlayers,
            newEvenings = newEvenings.values.toList(),
            existingEvenings = existingEvenings.values.toList(),
            invalidEvenings = invalidEvenings,
            defaultRules = root["defaultRules"]?.let { rulesOrNull(it) },
        )
    }

    /** Die Vorlage des Regelwerks, nur zum Anbieten; `null`, wenn sie sich nicht lesen lässt. */
    private fun rulesOrNull(element: JsonElement): RuleSet? = try {
        StorageCodec.decodeRulesElement(element)
    } catch (e: UnreadableDataException) {
        null
    }

    /** Prüft einen lesbaren Abend; `null` heißt: Er kann übernommen werden. */
    private fun findingFor(evening: Evening, alreadyAccepted: Set<EveningId>): EveningFinding? = when {
        evening.id.value.isBlank() ->
            EveningFinding(EveningProblem.UNREADABLE, "Der Abend hat keine Kennung.")

        evening.id in alreadyAccepted -> EveningFinding(
            EveningProblem.DUPLICATE_ID,
            "Der Abend steht mehrfach in der Sicherung. Übernommen wird nur der erste.",
        )

        else -> EveningCheck.firstProblem(evening)
    }

    private fun failure(kind: BackupErrorKind, message: String): ImportPreview =
        ImportPreview(error = BackupError(kind, message))

    private fun notJson(): ImportPreview = failure(
        BackupErrorKind.INVALID_JSON,
        "Die Datei lässt sich nicht lesen. Sie ist beschädigt oder keine Sicherung.",
    )

    private fun unreadablePlayers(): ImportPreview = failure(
        BackupErrorKind.WRONG_FORMAT,
        "Die Sicherung ist beschädigt: Die Liste der Spieler lässt sich nicht lesen.",
    )

    /** Zwei Namen gelten als gleich, wenn sie sich nur in Groß- und Kleinschreibung oder Leerraum am Rand unterscheiden. */
    private fun sameName(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)

    /** Der Zeitpunkt, nur für die Anzeige; `null`, wenn der Text kein brauchbarer Zeitpunkt ist. */
    private fun instantOrNull(text: String): Instant? = try {
        parseInstant(text)
    } catch (e: DateTimeException) {
        null
    }

    /** Der Text unter diesem Schlüssel; `null`, wenn er fehlt oder kein Text ist. */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Prüft, ob ein Abend in sich stimmig ist: genau vier verschiedene Spieler am Tisch, jeder mit einer Kennung, der
 * erste Geber sitzt am Tisch, in den Einträgen kommen nur Spieler vom Tisch vor, jede freie Buchung und jedes Spiel
 * geht auf null auf und kein Eintrag trägt die Kennung eines anderen.
 */
internal object EveningCheck {

    /** Das erste Problem des Abends mit einem verständlichen Satz dazu; `null`, wenn alles stimmt. */
    fun firstProblem(evening: Evening): EveningFinding? {
        val seating = evening.seating
        if (seating.size != ScoreCalculator.PLAYERS_AT_TABLE) {
            return EveningFinding(
                EveningProblem.WRONG_SEATING,
                "Am Tisch müssen genau vier Spieler sitzen, hier sind es ${seating.size}.",
            )
        }
        if (seating.toSet().size != seating.size) {
            return EveningFinding(EveningProblem.WRONG_SEATING, "Am Tisch sitzt derselbe Spieler mehrfach.")
        }
        if (seating.any { it.value.isBlank() }) {
            return EveningFinding(EveningProblem.WRONG_SEATING, "Am Tisch sitzt ein Spieler ohne Kennung.")
        }
        if (evening.firstDealer !in seating) {
            return EveningFinding(EveningProblem.DEALER_NOT_AT_TABLE, "Der erste Geber sitzt nicht am Tisch.")
        }
        // Mit einer gültigen Sitzordnung lässt sich der Abend durchrechnen: ein Ergebnis je Eintrag, in derselben Reihenfolge.
        val results = EveningCalculator.compute(evening).results
        val entryIds = HashSet<EntryId>()
        evening.entries.forEachIndexed { index, entry ->
            val number = index + 1
            if (entry.namedPlayers().any { it !in seating }) {
                return EveningFinding(
                    EveningProblem.UNKNOWN_PLAYER,
                    "Im Eintrag Nr. $number kommt ein Spieler vor, der nicht am Tisch sitzt.",
                )
            }
            // Als Long summiert, damit auch absurd große Beträge die Prüfung nicht durch einen Überlauf täuschen.
            if (entry is ManualEntry && entry.amounts.values.sumOf { it.toLong() } != 0L) {
                return EveningFinding(
                    EveningProblem.MANUAL_NOT_ZERO_SUM,
                    "Die freie Buchung im Eintrag Nr. $number geht nicht auf: Die Beträge ergeben zusammen nicht null.",
                )
            }
            // Ein Spiel zu zweit gegen zwei braucht zwei verschiedene Spieler auf der Spielerseite; sonst ließe es sich
            // nicht abrechnen und fehlte stillschweigend in den Salden des Abends.
            if (entry is GameEntry && !entry.type.isSolo && entry.partner == entry.declarer) {
                return EveningFinding(
                    EveningProblem.GAME_NOT_ZERO_SUM,
                    "Im Spiel im Eintrag Nr. $number ist der Ansager als sein eigener Mitspieler eingetragen.",
                )
            }
            // Jedes Spiel ist ein Nullsummenspiel; die Salden des Abends stimmten sonst nicht mehr.
            val amounts = results.getOrNull(index)?.amounts.orEmpty()
            if (entry is GameEntry && amounts.values.sumOf { it.toLong() } != 0L) {
                return EveningFinding(
                    EveningProblem.GAME_NOT_ZERO_SUM,
                    "Das Spiel im Eintrag Nr. $number geht nicht auf: Die Beträge der Spieler ergeben zusammen nicht null.",
                )
            }
            // Über ihre Kennung werden Einträge geändert und gelöscht; innerhalb eines Abends muss sie eindeutig sein.
            if (!entryIds.add(entry.id)) {
                return EveningFinding(
                    EveningProblem.DUPLICATE_ENTRY_ID,
                    "Der Eintrag Nr. $number hat dieselbe Kennung wie ein früherer Eintrag des Abends.",
                )
            }
        }
        return null
    }

    /** Alle Spieler, die ein Eintrag nennt. */
    private fun EveningEntry.namedPlayers(): Collection<PlayerId> = when (this) {
        is GameEntry -> listOfNotNull(declarer, partner, kontraBy, retourBy) + klopfer
        is ThrownInEntry -> emptyList()
        is BockTriggerEntry -> listOfNotNull(player)
        is ManualEntry -> amounts.keys
    }
}
