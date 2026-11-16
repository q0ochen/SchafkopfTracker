package io.github.q0ochen.schafkopf.app.storage

import io.github.q0ochen.schafkopf.data.storage.FileStorage
import io.github.q0ochen.schafkopf.data.storage.RuleSetJson
import io.github.q0ochen.schafkopf.data.storage.SettingsJson
import io.github.q0ochen.schafkopf.database.ImportedCounts
import io.github.q0ochen.schafkopf.database.RulesCodec
import io.github.q0ochen.schafkopf.database.SchafkopfDatabase
import io.github.q0ochen.schafkopf.database.SettingsCodec
import io.github.q0ochen.schafkopf.database.importMissing
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Regelwerke in der Datenbank in derselben Schreibweise wie in Sicherung und Web-App ([RuleSetJson]). */
object JsonRulesCodec : RulesCodec {
    override fun encode(rules: RuleSet): String = RuleSetJson.encode(rules)
    override fun decode(text: String): RuleSet? = RuleSetJson.decode(text)
}

/** Einstellungen in der Datenbank in der Schreibweise der früheren Datei `settings.json` ([SettingsJson]). */
object JsonSettingsCodec : SettingsCodec {
    override fun encode(settings: AppSettings): String = SettingsJson.encode(settings)
    override fun decode(text: String): AppSettings? = SettingsJson.decode(text)
}

/**
 * Übernimmt die Daten der ersten Testversionen in die Datenbank. Sie speicherten alles als JSON-Dateien im Ordner
 * „daten“ ([FileStorage]); seit der Datenbank (Issue #6) liest die App diesen Ordner nicht mehr.
 *
 * Die Übernahme überschreibt nichts: Spieler und Abende, die es in der Datenbank schon gibt, bleiben, wie sie sind;
 * die Einstellungen werden nur übernommen, solange die Datenbank noch keine hat. Alles geschieht in einer
 * Transaktion; geht etwas schief, bleibt der Ordner liegen und der nächste Start versucht es noch einmal. Erst nach
 * dem Erfolg wird der Ordner umbenannt ([RETIRED_SUFFIX]); er bleibt als Rückfall erhalten.
 */
object LegacyFileImport {
    /** Der Ordner der ersten Testversionen im privaten Speicher der App. */
    const val FOLDER = "daten"

    /** Anhang für den Ordner nach der Übernahme. */
    const val RETIRED_SUFFIX = "-uebernommen"

    /** @return was übernommen wurde, oder `null`, wenn es nichts zu übernehmen gab (kein alter Ordner). */
    suspend fun runIfNeeded(folder: Path, database: SchafkopfDatabase): ImportedCounts? {
        if (!Files.isDirectory(folder)) return null
        val old = FileStorage(folder, Dispatchers.IO)
        val counts = database.importMissing(
            players = old.players.players.first(),
            evenings = old.evenings.evenings.first(),
            settings = old.settings.settings.first(),
            rulesCodec = JsonRulesCodec,
            settingsCodec = JsonSettingsCodec,
        )
        retire(folder)
        return counts
    }

    private fun retire(folder: Path) {
        var target = folder.resolveSibling(folder.fileName.toString() + RETIRED_SUFFIX)
        var number = 2
        while (Files.exists(target)) target = folder.resolveSibling("${folder.fileName}$RETIRED_SUFFIX-${number++}")
        Files.move(folder, target, StandardCopyOption.ATOMIC_MOVE)
    }
}
