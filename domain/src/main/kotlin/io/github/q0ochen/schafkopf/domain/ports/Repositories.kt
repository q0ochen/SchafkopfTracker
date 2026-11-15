package io.github.q0ochen.schafkopf.domain.ports

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import kotlinx.coroutines.flow.Flow

/*
 * Ports: Die Fachlogik legt fest, was sie von der Außenwelt braucht. Umgesetzt werden sie im Modul `data`
 * (Dateien) und im Modul `app` (Android). Tests benutzen einfache Umsetzungen im Speicher.
 */

/** Die Spieler der Runde. */
interface PlayerRepository {
    /** Alle Spieler, auch archivierte, in der Reihenfolge, in der sie angelegt wurden. */
    val players: Flow<List<Player>>

    /** Legt einen Spieler an oder ersetzt den Spieler mit derselben Kennung. */
    suspend fun save(player: Player)
}

/** Die Abende mit ihren Einträgen. */
interface EveningRepository {
    /** Alle Abende, der neueste zuerst. */
    val evenings: Flow<List<Evening>>

    /** Ein einzelner Abend; `null`, wenn es ihn nicht (mehr) gibt. */
    fun evening(id: EveningId): Flow<Evening?>

    /**
     * Speichert den Abend dauerhaft. Kehrt erst zurück, wenn die Daten sicher auf dem Gerät liegen.
     */
    suspend fun save(evening: Evening)

    suspend fun delete(id: EveningId)
}

/** Die Einstellungen der App. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Sicherer Ablageort für den API-Schlüssel der optionalen KI-Zusammenfassung.
 * Der Schlüssel erscheint nie in Einstellungen, Sicherungen, Protokollen oder Fehlermeldungen.
 */
interface SecretStore {
    /** `null`, wenn kein Schlüssel hinterlegt ist oder er sich nicht mehr lesen lässt. */
    suspend fun apiKey(): String?

    /** Speichert den Schlüssel; `null` oder ein leerer Text löscht ihn. */
    suspend fun setApiKey(key: String?)
}
