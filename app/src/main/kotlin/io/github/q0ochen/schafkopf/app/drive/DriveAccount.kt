package io.github.q0ochen.schafkopf.app.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.q0ochen.schafkopf.app.security.EncryptedValues
import io.github.q0ochen.schafkopf.data.drive.GoogleDriveClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationException.GeneralErrors
import net.openid.appauth.AuthorizationException.TokenRequestErrors
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anmeldestand bei Google Drive.
 *
 * @property email Das Google-Konto, soweit Google es mitteilt.
 */
data class DriveSignIn(val signedIn: Boolean = false, val email: String? = null)

/** Wie die Anmeldung ausging. */
enum class SignInResult {
    OK,

    /** Abgebrochen oder gescheitert. */
    FAILED,

    /** Angemeldet, aber den Zugriff auf Google Drive nicht erlaubt (bei Google lässt er sich einzeln abwählen). */
    DRIVE_NOT_ALLOWED,
}

/** Die Anmeldung bei Google für die Sicherung in Drive. Als Schnittstelle, damit sich die Abläufe ohne Google prüfen lassen. */
interface DriveAccount {
    /** `false`, solange in dieser Version der App keine Client-ID von Google eingetragen ist. */
    val configured: Boolean
    val status: StateFlow<DriveSignIn>

    /** Liest den gespeicherten Anmeldestand (einmal genügt; danach hält [status] ihn aktuell). */
    suspend fun load()

    /**
     * Die Anmeldung im Browser; das Ergebnis geht an [completeSignIn].
     *
     * @throws android.content.ActivityNotFoundException, wenn es auf dem Gerät keinen Browser gibt.
     */
    fun signInIntent(): Intent

    suspend fun completeSignIn(result: Intent?): SignInResult

    /**
     * Ein gültiges Zugriffstoken (bei Bedarf frisch geholt) oder `null`, wenn eine neue Anmeldung nötig ist.
     *
     * @throws IOException, wenn Google gerade nicht erreichbar ist (dann später noch einmal).
     */
    suspend fun accessToken(): String?

    /** Abmelden: Die Anmeldung wird auf dem Gerät gelöscht und, soweit erreichbar, auch bei Google widerrufen. */
    suspend fun signOut()
}

/**
 * Anmeldung mit AppAuth (freie Bibliothek der OpenID Foundation) im Browser des Geräts: OAuth 2.0 mit PKCE, ohne
 * Google-Bibliotheken und ohne Play-Dienste. Die App verlangt nur `drive.file` (sie sieht nur ihre eigenen Dateien)
 * und die E-Mail-Adresse zur Anzeige. Der Anmeldestand (mit dem Token zum Erneuern) liegt verschlüsselt auf dem Gerät.
 *
 * Ein Zugriffstoken gilt bei Google eine Stunde; erneuert wird erst, wenn es abläuft. Nur dann entsteht ein
 * [AuthorizationService] (es verbindet sich mit dem Browser) und wird der Anmeldestand neu gespeichert.
 *
 * @param clientId Die Client-ID des Android-Clients im Google-Cloud-Projekt; leer, solange es keines gibt.
 * @param http Für den Widerruf beim Abmelden.
 */
class AppAuthDriveAccount(
    context: Context,
    private val values: EncryptedValues,
    private val clientId: String,
    private val http: OkHttpClient,
) : DriveAccount {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var authState: AuthState? = null
    private var loaded = false
    private val state = MutableStateFlow(DriveSignIn())

    override val configured: Boolean get() = clientId.isNotBlank()
    override val status: StateFlow<DriveSignIn> = state.asStateFlow()

    private val serviceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"),
    )

    override suspend fun load() {
        lock.withLock { current() }
    }

    override fun signInIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("$REDIRECT_SCHEME:/oauth2redirect"),
        )
            .setScopes("openid", "email", GoogleDriveClient.SCOPE_DRIVE_FILE)
            .build()
        val service = AuthorizationService(appContext)
        try {
            return service.getAuthorizationRequestIntent(request)
        } finally {
            service.dispose()
        }
    }

    override suspend fun completeSignIn(result: Intent?): SignInResult = lock.withLock {
        val data = result ?: return@withLock SignInResult.FAILED
        val response = AuthorizationResponse.fromIntent(data) ?: return@withLock SignInResult.FAILED
        val fresh = AuthState(response, AuthorizationException.fromIntent(data))
        val service = AuthorizationService(appContext)
        try {
            suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, error ->
                    fresh.update(tokens, error)
                    continuation.resume(Unit)
                }
            }
        } finally {
            service.dispose()
        }
        if (!fresh.isAuthorized) return@withLock SignInResult.FAILED
        // Google lässt einzelne Freigaben abwählen. Ohne Drive nützt die Anmeldung nichts: gleich wieder verwerfen.
        val scopes = fresh.scopeSet
        if (scopes != null && GoogleDriveClient.SCOPE_DRIVE_FILE !in scopes) {
            revoke(fresh.refreshToken ?: fresh.accessToken)
            return@withLock SignInResult.DRIVE_NOT_ALLOWED
        }
        try {
            save(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Der Schlüsselspeicher des Geräts streikt: lieber keine Anmeldung als eine, die nach dem Neustart fehlt.
            return@withLock SignInResult.FAILED
        }
        SignInResult.OK
    }

    override suspend fun accessToken(): String? = lock.withLock {
        val current = current() ?: return@withLock null
        if (!current.needsTokenRefresh) return@withLock current.accessToken
        val service = AuthorizationService(appContext)
        val outcome = try {
            suspendCancellableCoroutine { continuation ->
                current.performActionWithFreshTokens(service) { accessToken, _, error ->
                    when {
                        error == null -> continuation.resume(accessToken)
                        error.isTransient() -> continuation.resumeWithException(IOException("Token nicht erneuert (${error.type}/${error.code})"))
                        else -> continuation.resume(null)
                    }
                }
            }
        } finally {
            service.dispose()
        }
        if (outcome == null) clear() else save(current)
        outcome
    }

    override suspend fun signOut() {
        val token = lock.withLock {
            val token = authState?.let { it.refreshToken ?: it.accessToken }
            clear()
            token
        }
        revoke(token)
    }

    /**
     * Nur ein Fehler von Google, der eine neue Anmeldung verlangt (Erneuerung widerrufen oder abgelaufen, Client
     * ungültig), löscht die Anmeldung; alles andere (Netz, Überlastung, unbekannte Antworten) ist vorübergehend.
     */
    private fun AuthorizationException.isTransient(): Boolean = when (this) {
        TokenRequestErrors.INVALID_GRANT, TokenRequestErrors.INVALID_CLIENT, TokenRequestErrors.UNAUTHORIZED_CLIENT -> false
        // Lokal und dauerhaft: kein Token zum Erneuern oder ein unbrauchbares Anmeldetoken.
        AuthorizationException.AuthorizationRequestErrors.CLIENT_ERROR,
        GeneralErrors.ID_TOKEN_PARSING_ERROR,
        GeneralErrors.ID_TOKEN_VALIDATION_ERROR,
        -> false
        else -> true
    }

    /** Widerruft die Anmeldung bei Google; ohne Netz bleibt es beim Löschen auf dem Gerät. */
    private suspend fun revoke(token: String?) {
        if (token.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://oauth2.googleapis.com/revoke")
                    .post(FormBody.Builder().add("token", token).build())
                    .build()
                http.newCall(request).execute().close()
            } catch (_: IOException) {
                // Nicht erreichbar: Auf dem Gerät ist die Anmeldung trotzdem weg.
            }
        }
    }

    /** Muss unter [lock] laufen. Ein nicht lesbarer Anmeldestand wird beim nächsten Mal noch einmal versucht. */
    private suspend fun current(): AuthState? {
        if (!loaded) {
            when (val read = values.read(KEY)) {
                EncryptedValues.Read.Absent -> loaded = true
                EncryptedValues.Read.Unreadable -> Unit
                is EncryptedValues.Read.Present -> {
                    authState = runCatching { AuthState.jsonDeserialize(read.value) }.getOrNull()
                    loaded = true
                }
            }
            publish()
        }
        return authState?.takeIf { it.isAuthorized }
    }

    private suspend fun save(fresh: AuthState) {
        values.set(KEY, fresh.jsonSerializeString())
        authState = fresh
        loaded = true
        publish()
    }

    private suspend fun clear() {
        authState = null
        loaded = true
        try {
            values.set(KEY, null)
        } finally {
            publish()
        }
    }

    private fun publish() {
        val current = authState?.takeIf { it.isAuthorized }
        state.value = DriveSignIn(
            signedIn = current != null,
            email = current?.parsedIdToken?.additionalClaims?.get("email") as? String,
        )
    }

    companion object {
        /**
         * Das eigene URI-Schema für den Rücksprung aus der Anmeldung: der Paketname, wie Google es für Android-Clients
         * verlangt. Muss zu `manifestPlaceholders["appAuthRedirectScheme"]` in `app/build.gradle.kts` passen.
         */
        const val REDIRECT_SCHEME = "io.github.q0ochen.schafkopf"
        private const val KEY = "google_drive"
    }
}
