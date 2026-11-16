package io.github.q0ochen.schafkopf.app.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.q0ochen.schafkopf.app.security.EncryptedValues
import io.github.q0ochen.schafkopf.data.drive.GoogleDriveClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anmeldestand bei Google Drive.
 *
 * @property email Das Google-Konto, soweit Google es mitteilt.
 */
data class DriveSignIn(val signedIn: Boolean = false, val email: String? = null)

/** Die Anmeldung bei Google für die Sicherung in Drive. Als Schnittstelle, damit sich die Abläufe ohne Google prüfen lassen. */
interface DriveAccount {
    /** `false`, solange in dieser Version der App keine Client-ID von Google eingetragen ist. */
    val configured: Boolean
    val status: StateFlow<DriveSignIn>

    /** Liest den gespeicherten Anmeldestand (einmal genügt; danach hält [status] ihn aktuell). */
    suspend fun load()

    /** Die Anmeldung im Browser; das Ergebnis geht an [completeSignIn]. */
    fun signInIntent(): Intent

    /** @return `true`, wenn die Anmeldung geklappt hat. */
    suspend fun completeSignIn(result: Intent?): Boolean

    /**
     * Ein gültiges Zugriffstoken (bei Bedarf frisch geholt) oder `null`, wenn eine neue Anmeldung nötig ist.
     *
     * @throws IOException, wenn Google gerade nicht erreichbar ist (dann später noch einmal).
     */
    suspend fun accessToken(): String?

    suspend fun signOut()
}

/**
 * Anmeldung mit AppAuth (freie Bibliothek der OpenID Foundation) im Browser des Geräts: OAuth 2.0 mit PKCE, ohne
 * Google-Bibliotheken und ohne Play-Dienste. Die App verlangt nur `drive.file` (sie sieht nur ihre eigenen Dateien)
 * und die E-Mail-Adresse zur Anzeige. Der Anmeldestand (mit dem Token zum Erneuern) liegt verschlüsselt auf dem Gerät.
 *
 * @param clientId Die Client-ID des Android-Clients im Google-Cloud-Projekt; leer, solange es keines gibt.
 * @param redirectScheme Das eigene URI-Schema der App (der Paketname), wie es Google für Android-Clients verlangt.
 */
class AppAuthDriveAccount(
    context: Context,
    private val values: EncryptedValues,
    private val clientId: String,
    private val redirectScheme: String,
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
            Uri.parse("$redirectScheme:/oauth2redirect"),
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

    override suspend fun completeSignIn(result: Intent?): Boolean = lock.withLock {
        val data = result ?: return@withLock false
        val response = AuthorizationResponse.fromIntent(data) ?: return@withLock false
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
        if (!fresh.isAuthorized) return@withLock false
        save(fresh)
        true
    }

    override suspend fun accessToken(): String? = lock.withLock {
        val current = current() ?: return@withLock null
        val service = AuthorizationService(appContext)
        try {
            val token = suspendCancellableCoroutine { continuation ->
                current.performActionWithFreshTokens(service) { accessToken, _, error ->
                    when {
                        error == null -> continuation.resume(accessToken)
                        error.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> continuation.resume(null)
                        // Kein Netz oder Google überlastet: später noch einmal, die Anmeldung bleibt.
                        else -> continuation.resumeWithException(IOException("Token nicht erneuert (${error.type}/${error.code})"))
                    }
                }
            }
            if (token == null) clear() else save(current)
            token
        } finally {
            service.dispose()
        }
    }

    override suspend fun signOut() {
        lock.withLock { clear() }
    }

    /** Muss unter [lock] laufen. */
    private suspend fun current(): AuthState? {
        if (!loaded) {
            authState = values.get(KEY)?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
            loaded = true
            publish()
        }
        return authState?.takeIf { it.isAuthorized }
    }

    private suspend fun save(fresh: AuthState) {
        authState = fresh
        loaded = true
        values.set(KEY, fresh.jsonSerializeString())
        publish()
    }

    private suspend fun clear() {
        authState = null
        loaded = true
        values.set(KEY, null)
        publish()
    }

    private fun publish() {
        val current = authState?.takeIf { it.isAuthorized }
        state.value = DriveSignIn(
            signedIn = current != null,
            email = current?.parsedIdToken?.additionalClaims?.get("email") as? String,
        )
    }

    private companion object {
        const val KEY = "google_drive"
    }
}
