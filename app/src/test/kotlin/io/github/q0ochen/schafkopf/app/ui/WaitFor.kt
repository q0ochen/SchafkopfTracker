package io.github.q0ochen.schafkopf.app.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Wartet, bis ein Text auf dem Bildschirm steht. Die Datenbank lädt im Hintergrund; davon weiß die Testumgebung von
 * Compose nichts, und ohne Warten tippte ein Test womöglich ins Leere oder fotografierte „Wird geladen …“.
 *
 * Erscheint der Text nicht, sagt die Fehlermeldung, was stattdessen auf dem Bildschirm stand. Die Frist ist großzügig:
 * Auf den Rechnern von GitHub blieb ein Test am 19.11.2026 einmal länger als 10 Sekunden stehen, ohne dass sich das
 * hier nachstellen ließ (auch nicht auf einem einzelnen Prozessorkern).
 */
fun ComposeTestRule.waitForText(text: String, timeoutMillis: Long = 30_000) {
    try {
        waitUntil(timeoutMillis) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) {
        val seen = try {
            onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::textsOf)
        } catch (_: AssertionError) {
            listOf("(kein Bildschirm)")
        }
        throw AssertionError("„$text“ ist nach $timeoutMillis ms nicht erschienen. Auf dem Bildschirm steht: $seen. ${memory()}", e)
    }
}

/** Wie voll der Speicher der Test-JVM gerade ist (in MB); ist er fast voll, war die JVM vermutlich mit Aufräumen beschäftigt. */
private fun memory(): String {
    val runtime = Runtime.getRuntime()
    val mb = 1024 * 1024
    val used = (runtime.totalMemory() - runtime.freeMemory()) / mb
    return "Speicher: $used von ${runtime.maxMemory() / mb} MB belegt."
}

/** Alle Texte unterhalb eines Knotens, in der Reihenfolge des Baums. */
private fun textsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    return own + node.children.flatMap(::textsOf)
}
