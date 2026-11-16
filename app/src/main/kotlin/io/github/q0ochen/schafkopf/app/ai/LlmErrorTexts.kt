package io.github.q0ochen.schafkopf.app.ai

import androidx.annotation.StringRes
import io.github.q0ochen.schafkopf.R
import io.github.q0ochen.schafkopf.domain.ports.LlmError

/** Ein verständlicher Satz zu jedem Fehler des Sprachmodells. */
@StringRes
fun LlmError.messageRes(): Int = when (this) {
    LlmError.INVALID_KEY -> R.string.llm_error_invalid_key
    LlmError.ACCESS_DENIED -> R.string.llm_error_access_denied
    LlmError.MODEL_UNAVAILABLE -> R.string.llm_error_model_unavailable
    LlmError.QUOTA_EXCEEDED -> R.string.llm_error_quota
    LlmError.BLOCKED -> R.string.llm_error_blocked
    LlmError.EMPTY_OR_TRUNCATED -> R.string.llm_error_empty
    LlmError.SERVER_UNAVAILABLE -> R.string.llm_error_server
    LlmError.NETWORK -> R.string.llm_error_network
    LlmError.INVALID_ENDPOINT -> R.string.llm_error_endpoint
    LlmError.UNKNOWN -> R.string.llm_error_unknown
}
