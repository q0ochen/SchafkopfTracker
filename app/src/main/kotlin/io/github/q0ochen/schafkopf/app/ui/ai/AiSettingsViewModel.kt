package io.github.q0ochen.schafkopf.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.app.ai.TextGeneratorFactory
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Zustand der Einstellungen des Abend-Kommentators. Der Schlüssel selbst steht nie im Zustand, nur ob einer da ist.
 *
 * @property models Die Modelle des Dienstes nach „Modelle laden“; `null`, solange nicht geladen.
 * @property modelsError Warum das Laden scheiterte.
 * @property hostAllowed `false`, wenn die Adresse des eigenen Servers nicht erlaubt ist.
 */
data class AiSettingsUiState(
    val ai: AiSettings = AiSettings(),
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val models: List<String>? = null,
    val modelsError: LlmError? = null,
    val hostAllowed: Boolean = true,
    val keyMessage: KeyMessage? = null,
)

/** Was mit dem Schlüssel geschah. */
enum class KeyMessage { STORED, DELETED, FAILED }

private data class Local(
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val models: List<String>? = null,
    val modelsError: LlmError? = null,
    val keyMessage: KeyMessage? = null,
)

class AiSettingsViewModel(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val generators: TextGeneratorFactory,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AiSettingsUiState> =
        combine(settings.settings, local) { appSettings, mine ->
            AiSettingsUiState(
                ai = appSettings.ai,
                hasKey = mine.hasKey,
                busy = mine.busy,
                models = mine.models,
                modelsError = mine.modelsError,
                // Beim eigenen Server erst meckern, wenn eine Adresse eingetragen ist.
                hostAllowed = appSettings.ai.provider != AiProvider.OPENAI_COMPATIBLE || appSettings.ai.baseUrl.isBlank() ||
                    generators.host(appSettings.ai) != null,
                keyMessage = mine.keyMessage,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    init {
        viewModelScope.launch { local.update { it.copy(hasKey = secrets.apiKey() != null) } }
    }

    fun setEnabled(enabled: Boolean) = change { it.copy(enabled = enabled) }

    /** Ein anderer Dienst braucht eine neue Zustimmung zum Senden; die geladene Modellliste gilt nicht mehr. */
    fun setProvider(provider: AiProvider) {
        local.update { it.copy(models = null, modelsError = null) }
        change { if (it.provider == provider) it else it.copy(provider = provider, model = "", consentHost = "") }
    }

    fun setModel(model: String) = change { it.copy(model = model.trim()) }

    fun setBaseUrl(baseUrl: String) {
        local.update { it.copy(models = null, modelsError = null) }
        change { it.copy(baseUrl = baseUrl.trim(), consentHost = "") }
    }

    /** Wer die echten Namen einschaltet, soll vor dem nächsten Senden noch einmal gefragt werden. */
    fun setSendRealNames(on: Boolean) = change {
        if (on && !it.sendRealNames) it.copy(sendRealNames = true, consentHost = "") else it.copy(sendRealNames = on)
    }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            try {
                secrets.setApiKey(key)
                local.update { it.copy(hasKey = secrets.apiKey() != null, keyMessage = KeyMessage.STORED, models = null, modelsError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Der Schlüsselspeicher des Geräts kann gerade nicht verschlüsseln: lieber nichts speichern als im Klartext.
                local.update { it.copy(keyMessage = KeyMessage.FAILED) }
            }
        }
    }

    fun deleteKey() {
        viewModelScope.launch {
            secrets.setApiKey(null)
            local.update { it.copy(hasKey = false, keyMessage = KeyMessage.DELETED, models = null) }
        }
    }

    fun keyMessageShown() = local.update { it.copy(keyMessage = null) }

    /** Lädt die Modelle des Dienstes; das prüft zugleich Adresse und Schlüssel. */
    fun loadModels() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, modelsError = null) }
        viewModelScope.launch {
            try {
                val ai = settings.settings.first().ai
                when (val result = generators.create(ai, secrets.apiKey()).listModels()) {
                    is LlmResult.Success -> local.update { it.copy(models = result.value) }
                    is LlmResult.Failure -> local.update { it.copy(models = null, modelsError = result.error) }
                }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun change(transform: (AiSettings) -> AiSettings) {
        viewModelScope.launch { settings.update { it.copy(ai = transform(it.ai)) } }
    }
}
