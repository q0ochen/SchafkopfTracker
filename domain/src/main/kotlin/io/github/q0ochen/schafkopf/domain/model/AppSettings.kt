package io.github.q0ochen.schafkopf.domain.model

import io.github.q0ochen.schafkopf.domain.rules.RuleSet

/** Helles oder dunkles Erscheinungsbild. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Anbieter für die optionale KI-Zusammenfassung. */
enum class AiProvider {
    /** Gemini API von Google (eigener Schlüssel des Nutzers). */
    GEMINI,

    /** Beliebiger Dienst mit OpenAI-kompatibler Schnittstelle, zum Beispiel ein eigener Ollama-Server. */
    OPENAI_COMPATIBLE,
}

/**
 * Einstellungen der optionalen KI-Zusammenfassung. Der Schlüssel selbst steht nie hier, sondern im [SecretStore]
 * (siehe `domain.ports`).
 *
 * @property enabled Standardmäßig aus: Ohne ausdrückliches Einschalten geht nichts ins Netz.
 * @property model Gewähltes Modell; leer bedeutet, dass die App eines aus dem Katalog des Anbieters vorschlägt.
 * @property baseUrl Adresse des Dienstes beim Anbieter [AiProvider.OPENAI_COMPATIBLE].
 * @property sendRealNames Standardmäßig aus: Es werden Platzhalter statt der Spielernamen gesendet.
 * @property consentHost Für diesen Dienst hat der Nutzer dem Senden zugestimmt; leer bedeutet: noch nie.
 */
data class AiSettings(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.GEMINI,
    val model: String = "",
    val baseUrl: String = "",
    val sendRealNames: Boolean = false,
    val consentHost: String = "",
)

/**
 * Einstellungen der App.
 *
 * @property defaultRules Vorlage für neue Abende. Jeder Abend bekommt eine eigene Kopie.
 * @property keepScreenOn Bildschirm anlassen, solange ein Abend läuft.
 * @property themeMode Erscheinungsbild; dunkel ist der Standard (gut lesbar im schummrigen Wirtshaus).
 */
data class AppSettings(
    val defaultRules: RuleSet = RuleSet.standard(),
    val keepScreenOn: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val haptics: Boolean = true,
    val ai: AiSettings = AiSettings(),
)
