package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.ports.LlmError
import io.github.q0ochen.schafkopf.domain.ports.LlmResult
import io.github.q0ochen.schafkopf.domain.ports.TextGenerator

/**
 * Lässt die Zusammenfassung eines Abends (oder die Bilanz über alle Abende) von einem Sprachmodell schreiben.
 *
 * Gesendet werden nur die Anweisung und die Fakten als JSON (siehe [SummaryPrompt]). Die Antwort wird aufbereitet,
 * bevor sie jemand zu sehen bekommt. Geht etwas schief, bleibt immer die [TemplateSummary] als Ausweg.
 */
class SummaryService(private val generator: TextGenerator) {

    /**
     * @param useRealNames `false`: Das Sprachmodell bekommt Platzhalter statt der Spielernamen.
     * @return Die aufbereitete Zusammenfassung. Fehler des Sprachmodells werden unverändert weitergegeben; bleibt
     *   nach dem Aufbereiten kein Text übrig, ist das Ergebnis [LlmError.EMPTY_OR_TRUNCATED].
     */
    suspend fun generate(facts: SummaryFacts, useRealNames: Boolean): LlmResult<FinishedSummary> =
        generate(SummaryPrompt.SYSTEM_PROMPT, SummaryPrompt.payload(facts, useRealNames))

    /** Wie [generate], aber die Bilanz über alle Abende ([SummaryPrompt.HISTORY_SYSTEM_PROMPT]). */
    suspend fun generateHistory(facts: HistoryFacts, useRealNames: Boolean): LlmResult<FinishedSummary> =
        generate(SummaryPrompt.HISTORY_SYSTEM_PROMPT, SummaryPrompt.historyPayload(facts, useRealNames))

    private suspend fun generate(systemPrompt: String, payload: PromptPayload): LlmResult<FinishedSummary> {
        return when (val answer = generator.generate(systemPrompt, payload.userText)) {
            is LlmResult.Failure -> answer
            is LlmResult.Success -> {
                val finished = SummaryPrompt.finish(answer.value.text, payload)
                if (finished.text.isBlank()) {
                    LlmResult.Failure(LlmError.EMPTY_OR_TRUNCATED)
                } else {
                    LlmResult.Success(finished.copy(model = answer.value.model))
                }
            }
        }
    }
}
