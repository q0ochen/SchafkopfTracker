package io.github.q0ochen.schafkopf.app.ai

import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.ports.GeneratedText
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator
import okhttp3.OkHttpClient

/** Ein Sprachmodell zum Testen: merkt sich, was es bekommt, und antwortet wie vorgegeben. */
class FakeTextGenerator(var answer: LlmResult<GeneratedText> = LlmResult.Success(GeneratedText("Servus!", "gemini-test"))) :
    TextGenerator {
    val prompts = ArrayList<Pair<String, String>>()
    var models: LlmResult<List<String>> = LlmResult.Success(listOf("gemini-a", "gemini-b"))

    override suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText> {
        prompts += systemPrompt to userText
        return answer
    }

    override suspend fun listModels(): LlmResult<List<String>> = models
}

/** Liefert immer dasselbe Test-Sprachmodell und merkt sich Einstellungen und Schlüssel der letzten Anfrage. */
class FakeGeneratorFactory(val generator: FakeTextGenerator = FakeTextGenerator()) : TextGeneratorFactory(OkHttpClient()) {
    var lastSettings: AiSettings? = null
    var lastKey: String? = null

    override fun create(ai: AiSettings, key: String?): TextGenerator {
        lastSettings = ai
        lastKey = key
        return generator
    }
}
