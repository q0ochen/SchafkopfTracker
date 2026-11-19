package io.github.q0ochen.schafkopf.domain.round

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.RuleSet

/*
 * Die gemeinsame Runde: Mehrere Handys tragen in dieselben Abende ein und gleichen sich über einen Server ab.
 * Vertrag: docs/ARCHITEKTUR.md, Abschnitt 8.
 */

/** Kennung einer gemeinsamen Runde auf dem Server. */
@JvmInline
value class RoundId(val value: String)

/**
 * Ein Mitglied der Runde: ein Konto und, sobald es „Das bin ich“ gesagt hat, sein Spieler.
 *
 * @property userId Kennung des Kontos beim Server.
 */
data class RoundMember(val userId: String, val playerId: PlayerId?)

/**
 * Ein Spieler, wie ihn der Server kennt.
 *
 * @property position Reihenfolge des Anlegens in der Runde.
 * @property owner Das Konto, dem der Spieler gehört; `null` bei einem Gast ohne Konto.
 * @property nameLocked Der Name ist fest: Sein Besitzer hat ihn selbst festgelegt.
 * @property deleted Der Spieler wurde gelöscht; die Zeile bleibt, damit alle Geräte davon erfahren.
 * @property rev Mit welcher Änderung der Runde die Zeile zuletzt geschrieben wurde.
 */
data class RemotePlayer(
    val id: PlayerId,
    val name: String,
    val colorIndex: Int,
    val archived: Boolean,
    val position: Int = 0,
    val owner: String? = null,
    val nameLocked: Boolean = false,
    val deleted: Boolean = false,
    val rev: Long = 0,
) {
    /** Der Spieler, wie ihn die App führt. */
    fun toPlayer(): Player = Player(id, name, colorIndex, archived)

    /** Ob [player] in allem übereinstimmt, was Geräte ändern können: Name, Farbe und „archiviert“. */
    fun sameContent(player: Player): Boolean =
        player.id == id && player.name == name && player.colorIndex == colorIndex && player.archived == archived
}

/**
 * Ein Abend, wie ihn der Server hat.
 *
 * @property version Zählt jede gespeicherte Fassung dieses Abends, ab 1.
 * @property deleted Der Abend wurde gelöscht; die Zeile bleibt, damit alle Geräte davon erfahren.
 * @property evening Der Inhalt; `null`, wenn sich das Dokument nicht lesen lässt.
 */
data class RemoteEvening(
    val id: EveningId,
    val version: Int,
    val deleted: Boolean,
    val rev: Long,
    val evening: Evening?,
)

/** Die Fassung eines Abends, die dieses Gerät zuletzt vom Server gesehen oder dorthin geschrieben hat. */
data class EveningBase(val version: Int, val evening: Evening)

/**
 * Der Kopf einer Runde.
 *
 * @property rev Zählt jede Änderung der Runde (Abende, Spieler, Regeln, Mitglieder).
 * @property rules Das Regelwerk für neue Abende; `null`, solange keines hinterlegt ist oder es sich nicht lesen lässt.
 * @property rulesUnreadable Der Server hat ein Regelwerk, das diese Version der App nicht lesen kann. Es wird dann
 *   weder übernommen noch überschrieben.
 * @property createdBy Konto des Erstellers; `null`, wenn es ihn nicht mehr gibt.
 */
data class RoundHead(
    val id: RoundId,
    val name: String,
    val rev: Long,
    val rules: RuleSet?,
    val createdBy: String?,
    val rulesUnreadable: Boolean = false,
)

/** Eine Runde des angemeldeten Kontos in der Übersicht. */
data class RoundSummary(
    val id: RoundId,
    val name: String,
    val rev: Long,
    val memberCount: Int,
    val myPlayerId: PlayerId?,
    val isCreator: Boolean,
)

/** Was eine Einladung verrät, bevor man beitritt. */
data class InviteInfo(
    val roundId: RoundId,
    val roundName: String,
    val memberCount: Int,
    val alreadyMember: Boolean,
)

/** Antwort auf ein gespeichertes Abend-Dokument. */
data class SavedEvening(val version: Int, val rev: Long)

/**
 * Die Runde, mit der dieses Gerät verbunden ist.
 *
 * @property userId Das angemeldete Konto.
 * @property playerId Der eigene Spieler; `null`, solange „Das bin ich“ noch aussteht.
 * @property cursor Das größte `rev`, das dieses Gerät vollständig verarbeitet hat.
 * @property rulesBase Das zuletzt abgeglichene Regelwerk für neue Abende.
 */
data class RoundLink(
    val roundId: RoundId,
    val roundName: String,
    val userId: String,
    val playerId: PlayerId? = null,
    val isCreator: Boolean = false,
    val cursor: Long = 0,
    val rulesBase: RuleSet? = null,
    val members: List<RoundMember> = emptyList(),
)

/** Was beim Reden mit dem Server schiefgehen kann. Die Namen in Kleinbuchstaben sind die Fehlercodes des Servers. */
enum class RoundError {
    /** Keine Verbindung zum Server. */
    OFFLINE,

    /** Die Anmeldung ist abgelaufen oder wurde widerrufen. */
    SIGNED_OUT,

    /** Der Server antwortet mit einem Fehler (zum Beispiel, weil das Projekt pausiert ist). */
    SERVER,

    /** Die App kennt keinen Server (keine Konfiguration). */
    NOT_CONFIGURED,
    NOT_MEMBER,
    INVALID_NAME,
    NAME_TAKEN,
    NAME_LOCKED,
    ALREADY_CLAIMED,
    PLAYER_TAKEN,
    PLAYER_OWNED,
    INVALID_INVITE,
    LIMIT_REACHED,
    TOO_LARGE,
    INVALID_DOC,
    ;

    companion object {
        /** Der Fehler zu einem Fehlercode des Servers (zum Beispiel `name_taken`); `null`, wenn der Code unbekannt ist. */
        fun fromCode(code: String): RoundError? =
            entries.firstOrNull { it.ordinal >= NOT_MEMBER.ordinal && it.name.lowercase() == code }
    }
}

/** Ein Aufruf des Servers ist gescheitert. */
class RoundException(val error: RoundError, detail: String? = null, cause: Throwable? = null) :
    Exception(if (detail.isNullOrEmpty()) error.name else "${error.name}: $detail", cause)
