package io.github.q0ochen.schafkopf.app.ui

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.SystemClock
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import kotlin.coroutines.ContinuationInterceptor

/**
 * Wartet, bis ein Text auf dem Bildschirm steht. Die Datenbank lädt im Hintergrund; davon weiß die Testumgebung von
 * Compose nichts, und ohne Warten tippte ein Test womöglich ins Leere oder fotografierte „Wird geladen …“.
 *
 * Die Frist ist großzügig: Auf den Rechnern von GitHub blieb ein Test am 19.11.2026 einmal länger als 10 Sekunden
 * stehen, ohne dass sich das hier nachstellen ließ (auch nicht auf einem einzelnen Prozessorkern).
 */
fun ComposeTestRule.waitForText(text: String, timeoutMillis: Long = 30_000, details: () -> String = { "" }) =
    waitFor("„$text“", timeoutMillis, details) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

/**
 * Wartet, bis [condition] gilt. Sonst sagt die Fehlermeldung, was stattdessen auf dem Bildschirm stand, wie voll der
 * Speicher war, was in der Warteschlange des Hauptthreads lag und was jeder Thread gerade tat: Der Test der Startseite
 * lief auf GitHub mehrmals in die Wartefrist (19. und 21.11.2026). Am 21.11.2026 war der Speicher dabei nur zu einem
 * Siebtel belegt, kein Thread hing, und das ViewModel hatte die Daten längst (Lauf 57); nur die Anzeige bekam sie nicht.
 *
 * @param what Was erscheinen soll, für die Fehlermeldung.
 * @param details Was die Fehlermeldung außerdem nennen soll, etwa den Zustand des ViewModels.
 */
fun ComposeTestRule.waitFor(
    what: String,
    timeoutMillis: Long = 30_000,
    details: () -> String = { "" },
    condition: () -> Boolean,
) {
    try {
        waitUntil(timeoutMillis, condition)
    } catch (e: ComposeTimeoutException) {
        val seen = try {
            onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::textsOf)
        } catch (_: AssertionError) {
            listOf("(kein Bildschirm)")
        }
        val extra = runCatching(details).getOrElse { "(nicht lesbar: $it)" }
        throw AssertionError(
            "$what ist nach $timeoutMillis ms nicht erschienen. Auf dem Bildschirm steht: $seen. ${memory()} $extra\n" +
                "${mainQueue()}\n${uiDispatcher()}\n${threads()}",
            e,
        )
    }
}

/**
 * Was in der Warteschlange des Hauptthreads liegt. Robolectric führt eine Nachricht erst aus, wenn ihre Zeit auf der
 * angehaltenen Uhr erreicht ist; eine Nachricht „in der Zukunft“ wartet also, bis jemand die Uhr vorstellt. Eine
 * Sperre (Nachricht ohne Ziel) hält alle nicht asynchronen Nachrichten dahinter auf. Gelesen wird über die Felder der
 * Warteschlange, weil Robolectric sie im angehaltenen Modus nicht über seine Schatten herausgibt (Lauf 57).
 */
private fun mainQueue(): String = runCatching {
    val queue = Looper.getMainLooper().queue
    val head = MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
    val next = Message::class.java.getDeclaredField("next").apply { isAccessible = true }
    val now = SystemClock.uptimeMillis()
    val messages = synchronized(queue) {
        generateSequence(head.get(queue) as Message?) { next.get(it) as Message? }.take(MAX_MESSAGES).map { message ->
            val what = when {
                message.target == null -> "Sperre"
                else -> message.callback?.javaClass?.name ?: "${message.target.javaClass.name} (what=${message.what})"
            }
            "${message.`when` - now} ms${if (message.isAsynchronous) ", asynchron" else ""}: $what"
        }.toList()
    }
    "Hauptthread: Uhr $now ms, ${messages.size} Nachrichten" + messages.joinToString("") { "\n    $it" }
}.getOrElse { "Hauptthread: nicht lesbar ($it)" }

private const val MAX_MESSAGES = 30

/**
 * Der Zustand des Taktgebers, auf dem Compose seine Arbeit für den Hauptthread sammelt (`AndroidUiDispatcher.Main`):
 * wie viel dort wartet und ob ein Durchlauf angemeldet ist. Am 21.11.2026 zeigte der Test der Startseite: Das ViewModel
 * hatte die Abende, die Anzeige hörte zu („sammelnd: 1“), der Hauptthread war leer, und doch blieb die Anzeige beim
 * Anfangsstand. Wartet hier Arbeit, obwohl ein Durchlauf als angemeldet gilt, ist der Taktgeber hängen geblieben.
 * Gelesen über die Felder, weil es dafür keine Schnittstelle gibt.
 */
private fun uiDispatcher(): String = runCatching {
    val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] ?: return@runCatching "AndroidUiDispatcher: keiner"
    val fields = dispatcher.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { field ->
        field.isAccessible = true
        val value: Any? = field.get(dispatcher)
        val shown = when {
            value is Collection<*> -> "${value.size} wartend"
            value is Boolean -> value.toString()
            value == null -> "null"
            else -> value.javaClass.simpleName
        }
        "${field.name}=$shown"
    }
    "AndroidUiDispatcher: " + fields.joinToString(", ")
}.getOrElse { "AndroidUiDispatcher: nicht lesbar ($it)" }

/**
 * Wie viele gerade einen `StateFlow` aus `stateIn` sammeln (für Fehlermeldungen). Steht dort 0, hört die Oberfläche
 * nicht mehr zu; dann bleibt sie beim alten Stand, auch wenn das ViewModel längst einen neuen hat.
 */
fun subscribers(flow: StateFlow<*>?): String = runCatching {
    val inner = flow?.javaClass?.declaredFields?.firstNotNullOfOrNull { field ->
        field.isAccessible = true
        field.get(flow) as? MutableStateFlow<*>
    }
    inner?.subscriptionCount?.value?.toString() ?: "?"
}.getOrElse { "? ($it)" }

/** Wie voll der Speicher der Test-JVM gerade ist (in MB); ist er fast voll, war die JVM vermutlich mit Aufräumen beschäftigt. */
private fun memory(): String {
    val runtime = Runtime.getRuntime()
    val mb = 1024 * 1024
    val used = (runtime.totalMemory() - runtime.freeMemory()) / mb
    return "Speicher: $used von ${runtime.maxMemory() / mb} MB belegt."
}

/** Alle Threads der Test-JVM mit Zustand, Sperre (worauf und bei wem sie warten) und den obersten Aufrufen. */
private fun threads(maxFrames: Int = 20): String =
    ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)
        .sortedBy { it.threadName }
        .joinToString("\n", prefix = "Threads:\n") { info ->
            buildString {
                append('"').append(info.threadName).append("\" ").append(info.threadState)
                info.lockName?.let { append(", wartet auf ").append(it) }
                info.lockOwnerName?.let { append(" (gehalten von \"").append(it).append("\")") }
                info.stackTrace.take(maxFrames).forEach { append("\n        at ").append(it) }
                if (info.stackTrace.size > maxFrames) append("\n        …")
                info.lockedSynchronizers.forEach { append("\n        hält ").append(it) }
            }
        }

/** Alle Texte unterhalb eines Knotens, in der Reihenfolge des Baums. */
private fun textsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    return own + node.children.flatMap(::textsOf)
}
