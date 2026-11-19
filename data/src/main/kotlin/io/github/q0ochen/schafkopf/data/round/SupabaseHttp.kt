package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * Der Server der gemeinsamen Runde ist ein Supabase-Projekt (docs/ARCHITEKTUR.md, Abschnitt 8). Die App spricht
 * ihn ohne Bibliothek von Supabase an: nur OkHttp, damit sie F-Droid-tauglich bleibt.
 */

/**
 * Adresse und öffentlicher Schlüssel (Publishable Key) des Supabase-Projekts. Beides ist kein Geheimnis: Es steht
 * in jeder ausgelieferten App. Was ein Konto lesen und schreiben darf, regelt allein der Server.
 */
data class SupabaseConfig(val url: String, val publishableKey: String) {
    companion object {
        /**
         * Die Konfiguration aus den Angaben, mit denen die App gebaut wurde; `null`, wenn sie fehlen oder unbrauchbar
         * sind (dann gibt es in dieser App keine gemeinsame Runde). Verlangt wird eine `https`-Adresse.
         */
        fun of(url: String, publishableKey: String): SupabaseConfig? {
            val address = url.trim().trimEnd('/')
            val key = publishableKey.trim()
            val parsed = address.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps || key.isEmpty()) return null
            return SupabaseConfig(address, key)
        }
    }
}

/** Die Antwort auf einen Aufruf; der Inhalt ist schon gelesen. */
internal data class HttpAnswer(val status: Int, val body: String) {
    val isSuccessful: Boolean get() = status in 200..299

    /**
     * Der maschinenlesbare Fehlercode aus dem Inhalt:
     * - Funktionen des Servers (`RAISE EXCEPTION … ERRCODE 'P0001'`): der Text aus `message`, zum Beispiel `name_taken`;
     * - andere Fehler der Datenschnittstelle: ihr `code` (zum Beispiel `42501` oder `PGRST301`);
     * - Anmeldung: `error_code` oder `error` (zum Beispiel `refresh_token_not_found`, `invalid_grant`).
     */
    fun errorCode(): String? {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        fun text(name: String): String? = (json[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        text("error_code")?.let { return it }
        val code = text("code")
        if (code == RAISED_EXCEPTION) return text("message")
        return text("error") ?: code
    }

    companion object {
        /** SQLSTATE der Fehler, die die Funktionen des Servers selbst auslösen. */
        const val RAISED_EXCEPTION = "P0001"
    }
}

/**
 * Dünne Schicht über OkHttp für alle Aufrufe beim Server: baut Adressen und Kopfzeilen (`apikey`, dazu
 * `Authorization: Bearer <Zugriffstoken>` für Aufrufe im Namen eines Kontos) und liefert Status und Inhalt.
 * Ohne Netz scheitert der Aufruf mit [RoundError.OFFLINE].
 */
class SupabaseHttp(
    client: OkHttpClient,
    config: SupabaseConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Kurze Wartezeiten, weil der Abgleich im Hintergrund läuft und nie lange am Netz hängen soll; keine
     * Weiterleitungen, damit das Zugriffstoken nirgendwo anders hingeht.
     */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val base: HttpUrl = config.url.toHttpUrl()
    private val key: String = config.publishableKey

    /** Die Adresse zu einem Pfad wie `rest/v1/rounds`; die Angaben in [query] werden richtig kodiert. */
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder()
            .addPathSegments(path.trim('/'))
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

    internal suspend fun get(url: HttpUrl, accessToken: String?): HttpAnswer = send("GET", url, null, accessToken)

    internal suspend fun post(url: HttpUrl, json: JsonElement, accessToken: String?): HttpAnswer =
        send("POST", url, json.toString(), accessToken)

    private suspend fun send(method: String, url: HttpUrl, json: String?, accessToken: String?): HttpAnswer {
        val builder = Request.Builder().url(url).header("apikey", key).header("Accept", "application/json")
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, json?.toRequestBody(JSON_TYPE))
        val request = builder.build()
        return withContext(dispatcher) {
            try {
                http.newCall(request).execute().use { response -> HttpAnswer(response.code, response.body.string()) }
            } catch (e: IOException) {
                // Kein Netz, Zeitüberschreitung, abgebrochene Verbindung: später noch einmal.
                throw RoundException(RoundError.OFFLINE, e.javaClass.simpleName, e)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
