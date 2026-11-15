package io.github.q0ochen.schafkopf.domain.ports

/** Warum ein Sprachmodell keinen Text geliefert hat. Die Oberfläche zeigt zu jedem Fall einen verständlichen Hinweis. */
enum class LlmError {
    /** Der Schlüssel fehlt oder wird vom Dienst nicht anerkannt. */
    INVALID_KEY,

    /** Der Dienst verweigert den Zugriff (zum Beispiel Region oder fehlende Abrechnung). */
    ACCESS_DENIED,

    /** Das gewählte Modell gibt es nicht (mehr). */
    MODEL_UNAVAILABLE,

    /** Das Kontingent ist erschöpft; später noch einmal versuchen. */
    QUOTA_EXCEEDED,

    /** Der Dienst hat Anfrage oder Antwort aus inhaltlichen Gründen blockiert. */
    BLOCKED,

    /** Die Antwort war leer oder wurde abgeschnitten. */
    EMPTY_OR_TRUNCATED,

    /** Der Dienst ist gerade nicht erreichbar oder überlastet. */
    SERVER_UNAVAILABLE,

    /** Keine Internetverbindung oder Zeitüberschreitung. */
    NETWORK,

    /** Die Adresse des Dienstes ist ungültig oder nicht erlaubt (nur HTTPS; HTTP nur im eigenen Netz). */
    INVALID_ENDPOINT,

    /** Alles andere. */
    UNKNOWN,
}

/** Ergebnis einer Anfrage an ein Sprachmodell. */
sealed interface LlmResult<out T> {
    data class Success<T>(val value: T) : LlmResult<T>

    /**
     * @property detail Kurzer technischer Zusatz für die Anzeige (zum Beispiel der HTTP-Status).
     *   Enthält nie den Schlüssel.
     */
    data class Failure(val error: LlmError, val detail: String = "") : LlmResult<Nothing>
}

/**
 * Erzeugter Text samt dem Modell, das ihn tatsächlich geschrieben hat
 * (Anbieter leiten abgekündigte Modelle manchmal stillschweigend um).
 */
data class GeneratedText(val text: String, val model: String)

/**
 * Ein Sprachmodell hinter einer anbieterneutralen Schnittstelle. Umsetzungen liegen im Modul `data`
 * (Gemini per REST, OpenAI-kompatible Dienste). Die Fachlogik kennt keine Anbieter-Einzelheiten.
 */
interface TextGenerator {
    /**
     * @param systemPrompt Anweisung zu Rolle, Ton und Form der Antwort.
     * @param userText Die Fakten, über die geschrieben werden soll.
     */
    suspend fun generate(systemPrompt: String, userText: String): LlmResult<GeneratedText>

    /** Modelle, die sich für Textantworten eignen. Dient auch als Test, ob der Schlüssel funktioniert. */
    suspend fun listModels(): LlmResult<List<String>>
}
