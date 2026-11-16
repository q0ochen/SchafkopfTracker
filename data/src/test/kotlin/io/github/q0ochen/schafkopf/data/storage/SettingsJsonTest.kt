package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsJsonTest {

    @Test
    fun `Einstellungen kommen als Text unveraendert zurueck, wie in settings json`() {
        val settings = AppSettings(
            defaultRules = RuleSet.sauspiel(),
            keepScreenOn = false,
            themeMode = ThemeMode.LIGHT,
            haptics = false,
            ai = AiSettings(enabled = true, provider = AiProvider.GEMINI, model = "m", sendRealNames = true),
        )
        assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)))
        assertEquals(StorageCodec.encodeSettings(settings), SettingsJson.encode(settings))
        assertEquals(AppSettings(), SettingsJson.decode(SettingsJson.encode(AppSettings())))
    }

    @Test
    fun `ein unlesbarer Text ergibt null statt einer Ausnahme`() {
        for (text in listOf("", "kein JSON", "[]", "{\"themeMode\":\"bunt\"}", "{\"schemaVersion\":99}")) {
            assertNull(SettingsJson.decode(text), text)
        }
    }
}
