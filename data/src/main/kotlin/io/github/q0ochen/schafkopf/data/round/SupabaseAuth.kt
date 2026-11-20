package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * PKCE (RFC 7636) für die Anmeldung im Browser: Der `code_verifier` bleibt auf dem Gerät, an den Server geht nur die
 * `code_challenge` = base64url(SHA-256(verifier)). Wer die Rückkehr-Adresse abfängt, kann mit dem Code allein nichts
 * anfangen.
 */
object Pkce {
    /** 64 zufällige Bytes ergeben 86 Zeichen; erlaubt sind 43 bis 128. */
    private const val VERIFIER_BYTES = 64
    private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        require(isValidVerifier(verifier)) { "Ungültiger PKCE-Verifier." }
        return encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    fun isValidVerifier(verifier: String): Boolean = VERIFIER.matches(verifier)
}

/**
 * Das angemeldete Konto.
 *
 * @property userId Kennung des Kontos beim Server.
 * @property email Nur zum Anzeigen („Angemeldet als …“); sie verlässt das Gerät nicht wieder.
 */
data class Account(val userId: String, val email: String?)

/** Eine Sitzung nach dem Anmelden oder Erneuern. */
data class AuthTokens(val accessToken: String, val refreshToken: String, val expiresAt: Instant, val account: Account)

/** Die Anmeldung beim Server: nur Google, im Browser, mit PKCE. */
interface AuthApi {
    /** Die Adresse, die der Browser für die Anmeldung öffnet. */
    fun authorizeUrl(codeChallenge: String): String

    /** Tauscht den Code aus der Rückkehr-Adresse gegen eine Sitzung. */
    suspend fun exchangeCode(code: String, verifier: String): AuthTokens

    /**
     * Erneuert die Sitzung; das Refresh-Token wird dabei ersetzt. Scheitert mit [RoundError.SIGNED_OUT], wenn der
     * Server die Sitzung nicht mehr kennt; [RoundException.detail] sagt dann, ob das endgültig ist ([SESSION_GONE]).
     */
    suspend fun refresh(refreshToken: String): AuthTokens

    /** Beendet diese eine Sitzung auf dem Server. */
    suspend fun signOut(accessToken: String)

    companion object {
        /** Der Server kennt die Sitzung sicher nicht mehr: Das gespeicherte Refresh-Token ist wertlos. */
        const val SESSION_GONE = "session_gone"
    }
}

/** Die Anmeldung über Supabase Auth (docs/ARCHITEKTUR.md, Abschnitt 8.1). */
class SupabaseAuthApi(
    private val http: SupabaseHttp,
    private val clock: Clock = Clock.systemUTC(),
) : AuthApi {

    override fun authorizeUrl(codeChallenge: String): String = http.url(
        "auth/v1/authorize",
        listOf(
            "provider" to "google",
            "redirect_to" to REDIRECT_URL,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "s256",
            // Google fragt jedes Mal, welches Konto: Sonst käme man von einem falsch gewählten Konto nicht mehr weg.
            "prompt" to "select_account",
        ),
    ).toString()

    override suspend fun exchangeCode(code: String, verifier: String): AuthTokens {
        val body = buildJsonObject {
            put("auth_code", code)
            put("code_verifier", verifier)
        }
        val answer = http.post(http.url("auth/v1/token", listOf("grant_type" to "pkce")), body, accessToken = null)
        if (!answer.isSuccessful) throw RoundException(RoundError.SERVER, "Anmeldung: HTTP ${answer.status} ${answer.errorCode().orEmpty()}".trim())
        return parseTokens(answer.body)
    }

    override suspend fun refresh(refreshToken: String): AuthTokens {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        val answer = http.post(http.url("auth/v1/token", listOf("grant_type" to "refresh_token")), body, accessToken = null)
        if (answer.isSuccessful) return parseTokens(answer.body)
        val code = answer.errorCode()
        throw when {
            code in GONE_CODES -> RoundException(RoundError.SIGNED_OUT, AuthApi.SESSION_GONE)
            // Abgelehnt, aber aus unbekanntem Grund: neu anmelden hilft, das Refresh-Token bleibt vorerst.
            answer.status in REJECTED_STATUS -> RoundException(RoundError.SIGNED_OUT, "HTTP ${answer.status} ${code.orEmpty()}".trim())
            else -> RoundException(RoundError.SERVER, "Anmeldung: HTTP ${answer.status}")
        }
    }

    override suspend fun signOut(accessToken: String) {
        val answer = http.post(http.url("auth/v1/logout", listOf("scope" to "local")), JsonObject(emptyMap()), accessToken)
        // 401/403/404: Die Sitzung ist ohnehin schon ungültig.
        if (!answer.isSuccessful && answer.status !in setOf(401, 403, 404)) {
            throw RoundException(RoundError.SERVER, "Abmelden: HTTP ${answer.status}")
        }
    }

    internal fun parseTokens(body: String): AuthTokens {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw unreadable()
        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
        val user = json["user"] as? JsonObject ?: throw unreadable()
        val expiresAt = json.number("expires_at")?.let(Instant::ofEpochSecond)
            ?: clock.instant().plusSeconds(json.number("expires_in") ?: DEFAULT_LIFETIME_SECONDS)
        return AuthTokens(
            accessToken = json.text("access_token") ?: throw unreadable(),
            refreshToken = json.text("refresh_token") ?: throw unreadable(),
            expiresAt = expiresAt,
            account = Account(userId = user.text("id") ?: throw unreadable(), email = user.text("email")),
        )
    }

    private fun unreadable() = RoundException(RoundError.SERVER, "Antwort der Anmeldung nicht lesbar")

    companion object {
        /** Die Adresse, unter der der Browser die App nach der Anmeldung wieder öffnet; beim Server als erlaubt eingetragen. */
        const val REDIRECT_URL = "${InviteLinks.APP_SCHEME}://auth-callback"

        private const val DEFAULT_LIFETIME_SECONDS = 3600L

        /** Mit diesen Antworten ist die Sitzung endgültig vorbei. */
        private val GONE_CODES = setOf(
            "invalid_grant",
            "refresh_token_not_found",
            "refresh_token_already_used",
            "session_not_found",
            "session_expired",
            "user_not_found",
            "user_banned",
            "bad_jwt",
        )
        private val REJECTED_STATUS = setOf(400, 401, 403, 404)
    }
}

/** Was der Browser der App nach der Anmeldung zurückgibt (`schafkopftracker://auth-callback?…`). */
sealed interface AuthCallback {
    data class Code(val code: String) : AuthCallback

    /** Der Server oder Google meldet einen Fehler, zum Beispiel `access_denied` (abgebrochen). */
    data class Failure(val error: String, val description: String?) : AuthCallback

    companion object {
        /** `null`, wenn [uri] keine Rückkehr aus der Anmeldung ist. Die Angaben können hinter `?` oder `#` stehen. */
        fun parse(uri: String): AuthCallback? {
            val parsed = try {
                URI(uri)
            } catch (e: URISyntaxException) {
                return null
            }
            if (parsed.scheme?.lowercase() != InviteLinks.APP_SCHEME || parsed.host?.lowercase() != "auth-callback") return null
            val values = parameters(parsed.rawQuery) + parameters(parsed.rawFragment)
            values["code"]?.takeIf { it.isNotBlank() }?.let { return Code(it) }
            return Failure(values["error"] ?: values["error_code"] ?: "unknown", values["error_description"])
        }

        private fun parameters(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split('&').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else decode(part.substring(0, index)) to decode(part.substring(index + 1))
            }.toMap()
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }
    }
}
