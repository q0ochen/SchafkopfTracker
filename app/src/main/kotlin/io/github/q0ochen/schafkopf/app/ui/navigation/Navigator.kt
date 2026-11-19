package io.github.q0ochen.schafkopf.app.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Die Bildschirme der App. Jeder Wert lässt sich als Text speichern ([encode]) und wiederherstellen ([decode]),
 * damit die App nach einer Unterbrechung dort weitermacht, wo sie war.
 */
sealed interface Screen {
    data object Start : Screen
    data object NewEvening : Screen
    data class Board(val eveningId: String) : Screen

    /** @property entryId `null`: neues Spiel eintragen. Sonst: diesen Eintrag ändern. */
    data class GameForm(val eveningId: String, val entryId: String?) : Screen
    data class Closing(val eveningId: String) : Screen

    /** @property eveningId `null`: alle Abende („ewige Tabelle“). Sonst: die Statistik dieses Abends. */
    data class Statistics(val eveningId: String?) : Screen
    data object Players : Screen
    data object Settings : Screen

    /** @property eveningId `null`: die Vorlage für neue Abende. Sonst: das Regelwerk dieses Abends ansehen. */
    data class Rules(val eveningId: String?) : Screen
    data object AiSettings : Screen
    data object Backup : Screen

    /** Die gemeinsame Runde: anmelden, anlegen, beitreten, Mitglieder. */
    data object Round : Screen

    fun encode(): String = when (this) {
        Start -> "start"
        NewEvening -> "new"
        is Board -> "board$SEP$eveningId"
        is GameForm -> "form$SEP$eveningId$SEP${entryId.orEmpty()}"
        is Closing -> "closing$SEP$eveningId"
        is Statistics -> "stats$SEP${eveningId.orEmpty()}"
        Players -> "players"
        Settings -> "settings"
        is Rules -> "rules$SEP${eveningId.orEmpty()}"
        AiSettings -> "ai"
        Backup -> "backup"
        Round -> "round"
    }

    companion object {
        private const val SEP = "|"

        fun decode(text: String): Screen? {
            val parts = text.split(SEP)
            return when (parts[0]) {
                "start" -> Start
                "new" -> NewEvening
                "board" -> parts.getOrNull(1)?.let { Board(it) }
                "form" -> parts.getOrNull(1)?.let { GameForm(it, parts.getOrNull(2)?.takeIf(String::isNotEmpty)) }
                // „Verlauf“ hieß früher ein eigener Bildschirm; heute ist das die Statistik des Abends.
                "progress" -> parts.getOrNull(1)?.let { Statistics(it) }
                "closing" -> parts.getOrNull(1)?.let { Closing(it) }
                "stats" -> Statistics(parts.getOrNull(1)?.takeIf(String::isNotEmpty))
                "players" -> Players
                "settings" -> Settings
                "rules" -> Rules(parts.getOrNull(1)?.takeIf(String::isNotEmpty))
                "ai" -> AiSettings
                "backup" -> Backup
                "round" -> Round
                else -> null
            }
        }
    }
}

/** Ein Bildschirm auf dem Stapel. Die Kennung unterscheidet zwei gleiche Bildschirme voneinander. */
data class NavEntry(val id: String, val screen: Screen)

/**
 * Der Stapel der geöffneten Bildschirme. Der oberste wird angezeigt; „Zurück“ nimmt ihn weg.
 * Bewusst ohne Navigationsbibliothek: wenige Zeilen, vollständig ohne Android testbar.
 */
@Stable
class Navigator(initial: List<NavEntry> = listOf(NavEntry("0", Screen.Start))) {
    private val stack = mutableStateListOf<NavEntry>().apply { addAll(initial.ifEmpty { listOf(NavEntry("0", Screen.Start)) }) }
    private var counter = (initial.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1

    val entries: List<NavEntry> get() = stack
    val current: NavEntry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(screen: Screen) {
        stack.add(NavEntry((counter++).toString(), screen))
    }

    /** @return `false`, wenn nur noch der erste Bildschirm übrig ist. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Ersetzt den obersten Bildschirm, zum Beispiel „Neuer Abend“ durch den gestarteten Abend. */
    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = NavEntry((counter++).toString(), screen)
    }

    companion object {
        private const val SEP = "\u0000"

        val Saver: Saver<Navigator, Any> = listSaver(
            save = { navigator -> navigator.entries.map { it.id + SEP + it.screen.encode() } },
            restore = { saved ->
                Navigator(
                    saved.mapNotNull { line ->
                        val id = line.substringBefore(SEP)
                        Screen.decode(line.substringAfter(SEP))?.let { NavEntry(id, it) }
                    },
                )
            },
        )
    }
}
