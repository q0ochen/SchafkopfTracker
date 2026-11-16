package io.github.q0ochen.schafkopf.app.ui.ai

import io.github.q0ochen.schafkopf.app.ai.FakeGeneratorFactory
import io.github.q0ochen.schafkopf.app.ui.awaitFirst
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.ports.InMemorySecretStore
import io.github.q0ochen.schafkopf.domain.ports.InMemorySettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Einstellungen des Abend-Kommentators, ohne Oberfläche geprüft. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gemini = "generativelanguage.googleapis.com"

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `ein anderer Anbieter, eine andere Adresse oder echte Namen verlangen eine neue Zustimmung`() = runTest(dispatcher) {
        val settings = InMemorySettingsRepository(AppSettings(ai = AiSettings(enabled = true, model = "m", consentHost = gemini)))
        val viewModel = AiSettingsViewModel(settings, InMemorySecretStore(), FakeGeneratorFactory())
        backgroundScope.launch { viewModel.state.collect {} }

        viewModel.setSendRealNames(false)
        assertEquals(gemini, settings.settings.awaitFirst { true }.ai.consentHost)
        viewModel.setSendRealNames(true)
        assertEquals("", settings.settings.awaitFirst { it.ai.sendRealNames }.ai.consentHost)

        settings.update { it.copy(ai = it.ai.copy(consentHost = gemini)) }
        viewModel.setProvider(AiProvider.OPENAI_COMPATIBLE)
        val switched = settings.settings.awaitFirst { it.ai.provider == AiProvider.OPENAI_COMPATIBLE }.ai
        assertEquals("", switched.model)
        assertEquals("", switched.consentHost)

        viewModel.setBaseUrl("  http://example.com/v1 ")
        assertEquals("http://example.com/v1", settings.settings.awaitFirst { it.ai.baseUrl.isNotEmpty() }.ai.baseUrl)
        assertFalse(viewModel.state.awaitFirst { it.ai.baseUrl.isNotEmpty() }.hostAllowed)
        viewModel.setBaseUrl("http://192.168.1.20:11434/v1")
        assertTrue(viewModel.state.awaitFirst { it.ai.baseUrl.startsWith("http://192") }.hostAllowed)
    }

    @Test
    fun `Schluessel speichern und loeschen, nie im Zustand`() = runTest(dispatcher) {
        val secrets = InMemorySecretStore()
        val viewModel = AiSettingsViewModel(InMemorySettingsRepository(), secrets, FakeGeneratorFactory())
        backgroundScope.launch { viewModel.state.collect {} }
        assertFalse(viewModel.state.value.hasKey)

        viewModel.saveKey("   ")
        assertNull(secrets.apiKey())
        viewModel.saveKey(" geheim ")
        assertTrue(viewModel.state.awaitFirst { it.hasKey }.keyMessage == true)
        assertEquals("geheim", secrets.apiKey()?.trim())
        assertFalse(viewModel.state.value.toString().contains("geheim"))
        viewModel.keyMessageShown()

        viewModel.deleteKey()
        assertEquals(false, viewModel.state.awaitFirst { !it.hasKey }.keyMessage)
        assertNull(secrets.apiKey())
    }

    @Test
    fun `Modelle laden prueft Schluessel und Verbindung`() = runTest(dispatcher) {
        val factory = FakeGeneratorFactory()
        val viewModel = AiSettingsViewModel(InMemorySettingsRepository(), InMemorySecretStore("geheim"), factory)
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.loadModels()
        assertEquals(listOf("gemini-a", "gemini-b"), viewModel.state.awaitFirst { it.models != null }.models)
        assertEquals("geheim", factory.lastKey)

        factory.generator.models = LlmResult.Failure(LlmError.INVALID_KEY)
        viewModel.loadModels()
        val failed = viewModel.state.awaitFirst { it.modelsError != null && !it.busy }
        assertEquals(LlmError.INVALID_KEY, failed.modelsError)
        assertNull(failed.models)

        viewModel.setModel(" gemini-b ")
        assertEquals("gemini-b", viewModel.state.awaitFirst { it.ai.model.isNotEmpty() }.ai.model)
        viewModel.setEnabled(true)
        assertTrue(viewModel.state.awaitFirst { it.ai.enabled }.ai.enabled)
    }
}
