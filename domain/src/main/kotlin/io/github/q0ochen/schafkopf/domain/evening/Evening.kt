package io.github.q0ochen.schafkopf.domain.evening

import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import java.time.Instant

/**
 * Ein Eintrag in der Liste eines Abends. Gespeichert werden nur Tatsachen;
 * Beträge, Salden, Geber und Bock-Status werden daraus jedes Mal neu berechnet (siehe [EveningCalculator]).
 */
sealed interface EveningEntry {
    val id: EntryId
    val recordedAt: Instant
}

/** Ob ein Spiel abweichend vom Bock-Zähler als Bockspiel zählt. */
enum class BockOverride {
    /** Der Bock-Zähler entscheidet. */
    AUTO,

    /** Dieses Spiel ist ein Bockspiel, auch wenn der Zähler leer ist. */
    FORCE_ON,

    /** Dieses Spiel ist kein Bockspiel; der Zähler läuft nicht weiter. */
    FORCE_OFF,
}

/**
 * Ein gespieltes Spiel.
 *
 * Gespeichert wird, wer gespielt hat und ob die Spielerseite gewonnen hat. Die Gewinner ergeben sich daraus
 * (siehe [winners]); so kann die Statistik auch zeigen, wer wie oft selbst gespielt hat.
 *
 * @property declarer Wer das Spiel angesagt hat. Bei Über Kreuz einer aus dem einen Paar.
 * @property partner Der Mitspieler beim Rufspiel und bei Über Kreuz; bei Einzelspielen `null`.
 * @property suit Rufsau beim Rufspiel bzw. Trumpffarbe beim Farbsolo; freiwillige Angabe für die Statistik.
 * @property klopfer Wer gelegt (geklopft) hat. Jeder, der gelegt hat, verdoppelt den Spielwert.
 * @property kontraBy Wer Kontra gegeben hat; freiwillige Angabe für die Statistik.
 * @property bockOverride Der Bock-Schalter dieses Spiels (siehe [BockOverride]).
 * @property declarerEyes Augen der Spielerseite (0 bis 120); freiwillige Angabe.
 */
data class GameEntry(
    override val id: EntryId,
    override val recordedAt: Instant,
    val type: GameType,
    val suit: Suit? = null,
    val declarer: PlayerId,
    val partner: PlayerId? = null,
    val declarerWon: Boolean,
    val level: ResultLevel = ResultLevel.NORMAL,
    val laufende: Int = 0,
    val announcement: Announcement = Announcement.NONE,
    val klopfer: Set<PlayerId> = emptySet(),
    val kontra: KontraLevel = KontraLevel.NONE,
    val kontraBy: PlayerId? = null,
    val retourBy: PlayerId? = null,
    val bockOverride: BockOverride = BockOverride.AUTO,
    val declarerEyes: Int? = null,
    val note: String = "",
) : EveningEntry {
    /** Die Spielerseite: der Ansager und – außer beim Einzelspiel – sein Mitspieler. */
    val declarerSide: Set<PlayerId> get() = setOfNotNull(declarer, partner.takeIf { !type.isSolo })

    /**
     * Die Gewinner dieses Spiels in Sitzreihenfolge: zwei beim Rufspiel und bei Über Kreuz,
     * beim Einzelspiel der Alleinspieler oder seine drei Gegner.
     */
    fun winners(seating: List<PlayerId>): List<PlayerId> = seating.filter { (it in declarerSide) == declarerWon }

    /** Die Verlierer dieses Spiels in Sitzreihenfolge. */
    fun losers(seating: List<PlayerId>): List<PlayerId> = seating.filter { (it in declarerSide) != declarerWon }
}

/** Alle haben „weiter“ gesagt: Das Blatt wurde zusammengeworfen, niemand zahlt. */
data class ThrownInEntry(
    override val id: EntryId,
    override val recordedAt: Instant,
) : EveningEntry

/** Warum eine Bockrunde ausgelöst wurde. */
enum class BockReason { SECHS_NIXER, MANUAL }

/**
 * Eine Bockrunde wurde ausgelöst.
 *
 * @property player Wer die 6 Nixer hatte; freiwillige Angabe für die Statistik.
 * @property handThrownIn Ob das Blatt dabei zusammengeworfen und neu gegeben wurde.
 */
data class BockTriggerEntry(
    override val id: EntryId,
    override val recordedAt: Instant,
    val reason: BockReason,
    val player: PlayerId? = null,
    val handThrownIn: Boolean = true,
) : EveningEntry

/**
 * Freie Buchung für alles, was die App nicht kennt (zum Beispiel eine Sonderabmachung).
 * Die Beträge in Cent müssen sich zu 0 summieren.
 */
data class ManualEntry(
    override val id: EntryId,
    override val recordedAt: Instant,
    val amounts: Map<PlayerId, Int>,
    val note: String,
) : EveningEntry

/**
 * Ein Schafkopf-Abend: vier Spieler in fester Sitzordnung und die Liste dessen, was passiert ist.
 *
 * @property seating Die vier Spieler im Uhrzeigersinn. In dieser Reihenfolge wechselt der Geber.
 * @property firstDealer Wer das erste Spiel des Abends gegeben hat.
 * @property rules Eigene Kopie des Regelwerks; spätere Änderungen an der Vorlage wirken hier nicht.
 * @property finishedAt `null`, solange der Abend läuft.
 */
data class Evening(
    val id: EveningId,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val location: String = "",
    val seating: List<PlayerId>,
    val firstDealer: PlayerId,
    val rules: RuleSet,
    val entries: List<EveningEntry> = emptyList(),
) {
    val isRunning: Boolean get() = finishedAt == null

    /** Hängt einen Eintrag ans Ende an. */
    fun append(entry: EveningEntry): Evening = copy(entries = entries + entry)

    /** Ersetzt den Eintrag mit derselben Kennung; die Position in der Liste bleibt. */
    fun replace(entry: EveningEntry): Evening =
        copy(entries = entries.map { if (it.id == entry.id) entry else it })

    /** Entfernt den Eintrag mit dieser Kennung. */
    fun remove(entryId: EntryId): Evening = copy(entries = entries.filterNot { it.id == entryId })

    /** Fügt einen Eintrag an einer bestimmten Stelle ein (für „Rückgängig“ nach dem Löschen). */
    fun insertAt(index: Int, entry: EveningEntry): Evening {
        val position = index.coerceIn(0, entries.size)
        return copy(entries = entries.subList(0, position) + entry + entries.subList(position, entries.size))
    }

    /**
     * Ob der Spieler in diesem Abend vorkommt: am Tisch, als Geber oder in irgendeinem Eintrag (auch in beschädigten
     * Daten, in denen ein Eintrag jemanden nennt, der nicht am Tisch sitzt). Solche Spieler dürfen nicht gelöscht
     * werden, sonst verlöre der Abend seine Namen und ließe sich nicht mehr sichern und einlesen.
     */
    fun involves(player: PlayerId): Boolean =
        player in seating || player == firstDealer || entries.any { entry ->
            when (entry) {
                is GameEntry -> player == entry.declarer || player == entry.partner || player in entry.klopfer ||
                    player == entry.kontraBy || player == entry.retourBy
                is BockTriggerEntry -> player == entry.player
                is ManualEntry -> player in entry.amounts.keys
                is ThrownInEntry -> false
            }
        }
}
