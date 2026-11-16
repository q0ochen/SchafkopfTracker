package io.github.q0ochen.schafkopf.app.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Wartet, bis ein Text auf dem Bildschirm steht. Die Datenbank lädt im Hintergrund; davon weiß die Testumgebung von
 * Compose nichts, und ohne Warten tippte ein Test womöglich ins Leere oder fotografierte „Wird geladen …“.
 */
fun ComposeTestRule.waitForText(text: String, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
}
