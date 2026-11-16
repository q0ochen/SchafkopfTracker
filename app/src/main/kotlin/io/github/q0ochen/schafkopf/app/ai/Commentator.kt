package io.github.q0ochen.schafkopf.app.ai

import io.github.q0ochen.schafkopf.app.ui.common.Money
import io.github.q0ochen.schafkopf.data.llm.EndpointPolicy
import io.github.q0ochen.schafkopf.data.llm.GeminiTextGenerator
import io.github.q0ochen.schafkopf.data.llm.OpenAiCompatibleTextGenerator
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import io.github.q0ochen.schafkopf.domain.summary.FinishedSummary
import io.github.q0ochen.schafkopf.domain.summary.HistoryFactsBuilder
import io.github.q0ochen.schafkopf.domain.summary.SummaryFactsBuilder
import io.github.q0ochen.schafkopf.domain.summary.SummaryService
import io.github.q0ochen.schafkopf.domain.summary.TemplateSummary
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/** Baut die Anbindung an das gewählte Sprachmodell (Gemini oder ein eigener, OpenAI-kompatibler Dienst). */
open class TextGeneratorFactory(private val client: OkHttpClient) {

    open fun create(ai: AiSettings, key: String?): TextGenerator = when (ai.provider) {
        AiProvider.GEMINI -> GeminiTextGenerator(client, key, ai.model.ifBlank { DEFAULT_GEMINI_MODEL })
        AiProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleTextGenerator(client, key, ai.model, ai.baseUrl)
    }

    /** Wohin gesendet würde; `null`, wenn die Adresse nicht erlaubt ist (nur HTTPS, HTTP nur im eigenen Netz). */
    fun host(ai: AiSettings): String? = when (ai.provider) {
        AiProvider.GEMINI -> EndpointPolicy.host(GeminiTextGenerator.DEFAULT_BASE_URL)
        AiProvider.OPENAI_COMPATIBLE -> EndpointPolicy.host(ai.baseUrl)
    }

    companion object {
        /**
         * Ohne gewähltes Modell nimmt die App den Verweis von Google auf das jeweils neueste schnelle Modell. So muss
         * niemand die App ändern, wenn Google ein Modell abkündigt.
         */
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    }
}

/** Woher der Text des Kommentators stammt. */
enum class CommentarySource { AI, TEMPLATE }

/** Ein fertiger Kommentar zum Abend. @property model Das Modell, das ihn geschrieben hat (nur bei [CommentarySource.AI]). */
data class Commentary(val text: String, val source: CommentarySource, val model: String = "")

/** Was beim Schreiben herauskam. */
sealed interface CommentaryOutcome {
    data class Done(val commentary: Commentary) : CommentaryOutcome

    /** Für diesen Dienst fehlt noch die Zustimmung zum Senden; die Oberfläche fragt und ruft dann [Commentator.consent]. */
    data class NeedsConsent(val host: String, val realNames: Boolean) : CommentaryOutcome

    /** Das Sprachmodell lieferte nichts; stattdessen gibt es die Zusammenfassung aus Textbausteinen. */
    data class Failed(val error: LlmError, val detail: String, val fallback: Commentary) : CommentaryOutcome
}

/**
 * Der Abend-Kommentator (Issue #16): Mit eingeschaltetem Sprachmodell schreibt es eine witzige Zusammenfassung des
 * Abends oder eine Bilanz über alle Abende ([SummaryService]); sonst oder bei einem Fehler entsteht sie aus
 * Textbausteinen ([TemplateSummary]).
 * Gesendet wird erst, wenn der Nutzer für genau diesen Dienst zugestimmt hat.
 */
class Commentator(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val generators: TextGeneratorFactory,
) {
    /** Der Kommentar zu einem Abend. */
    suspend fun write(state: EveningState, names: Map<PlayerId, String>): CommentaryOutcome {
        val facts = SummaryFactsBuilder.build(state, names, Money)
        return write(TemplateSummary.render(facts)) { service, realNames -> service.generate(facts, realNames) }
    }

    /**
     * Die Bilanz über alle Abende („ewige Tabelle“).
     *
     * @param asMoney Euro oder Punkte, wie in der Statistik über alle Abende.
     */
    suspend fun writeHistory(evenings: List<Evening>, names: Map<PlayerId, String>, asMoney: Boolean): CommentaryOutcome {
        val facts = HistoryFactsBuilder.build(evenings, names, Money, asMoney)
        return write(TemplateSummary.renderHistory(facts)) { service, realNames -> service.generateHistory(facts, realNames) }
    }

    /**
     * Ohne Sprachmodell gibt es den Text aus Textbausteinen ([templateText]); sonst schreibt [generate], sobald für
     * den gewählten Dienst die Zustimmung vorliegt.
     */
    private suspend fun write(
        templateText: String,
        generate: suspend (SummaryService, Boolean) -> LlmResult<FinishedSummary>,
    ): CommentaryOutcome {
        val template = Commentary(templateText, CommentarySource.TEMPLATE)
        val ai = settings.settings.first().ai
        if (!ai.enabled) return CommentaryOutcome.Done(template)
        val host = generators.host(ai) ?: return CommentaryOutcome.Failed(LlmError.INVALID_ENDPOINT, "", template)
        if (ai.consentHost != host) return CommentaryOutcome.NeedsConsent(host, ai.sendRealNames)
        val generator = generators.create(ai, secrets.apiKey())
        return when (val result = generate(SummaryService(generator), ai.sendRealNames)) {
            is LlmResult.Success -> CommentaryOutcome.Done(Commentary(result.value.text, CommentarySource.AI, result.value.model))
            is LlmResult.Failure -> CommentaryOutcome.Failed(result.error, result.detail, template)
        }
    }

    /** Der Nutzer erlaubt das Senden an diesen Dienst (bis er Dienst oder Namensregel ändert). */
    suspend fun consent(host: String) {
        settings.update { it.copy(ai = it.ai.copy(consentHost = host)) }
    }
}
