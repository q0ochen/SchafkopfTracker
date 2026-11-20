package io.github.q0ochen.schafkopf.data.round

import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Was die App zwischen zwei Starts von der Anmeldung behält. Das Zugriffstoken gehört nicht dazu: Es lebt nur im
 * Arbeitsspeicher und wird bei Bedarf mit dem Refresh-Token neu geholt.
 */
data class StoredSession(val refreshToken: String, val userId: String, val email: String?)

/** Sicherer Ablageort für die Anmeldung; die App legt ihn verschlüsselt an. */
interface SessionStorage {
    /** `null`, wenn niemand angemeldet ist oder sich der Eintrag nicht mehr lesen lässt. */
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)

    suspend fun clear()

    /** Der PKCE-Verifier einer begonnenen Anmeldung; er muss überstehen, dass Android die App im Hintergrund beendet. */
    suspend fun pendingVerifier(): String?

    suspend fun setPendingVerifier(verifier: String?)
}

/** Die Anmeldung dieses Geräts aus Sicht der Oberfläche. Als Schnittstelle, damit sich die Abläufe ohne Server prüfen lassen. */
interface RoundAccount {
    /** Das angemeldete Konto; `null`, solange niemand angemeldet ist oder [load] noch nicht gelaufen ist. */
    val account: StateFlow<Account?>

    /** Liest die gespeicherte Anmeldung (einmal genügt). */
    suspend fun load(): Account?

    /** Beginnt die Anmeldung und liefert die Adresse für den Browser. */
    suspend fun beginSignIn(): String

    /** Schließt die Anmeldung mit dem Code aus der Rückkehr-Adresse ab. */
    suspend fun completeSignIn(code: String): Account

    /** Meldet ab; auf dem Gerät klappt das immer, auch ohne Netz. */
    suspend fun signOut()

    companion object {
        /**
         * [RoundException.detail], wenn [completeSignIn] ohne begonnene Anmeldung gerufen wird (zum Beispiel, weil
         * derselbe Link ein zweites Mal kommt).
         */
        const val NO_SIGN_IN_PENDING = "keine begonnene Anmeldung"
    }
}

/** Liefert gültige Zugriffstoken für Aufrufe im Namen des Kontos. */
interface AccessTokenSource {
    /** Ein gültiges Token; es wird bei Bedarf erneuert. Ohne Anmeldung [RoundError.SIGNED_OUT]. */
    suspend fun accessToken(): String

    /** Nach einer Antwort 401: [rejected] verwerfen und ein neues Token holen. */
    suspend fun refreshAfterUnauthorized(rejected: String): String
}

/**
 * Die Anmeldung dieses Geräts beim Server der gemeinsamen Runde: anmelden (im Browser, siehe [beginSignIn] und
 * [completeSignIn]), erneuern, abmelden.
 *
 * - Das Refresh-Token wird bei jeder Erneuerung ersetzt und sofort gespeichert.
 * - Kennt der Server die Sitzung nicht mehr, gilt das Gerät als abgemeldet. Bei einem Netzfehler oder einer
 *   vorübergehenden Störung bleibt es angemeldet.
 * - Der Speicher wird erst bei Bedarf gelesen.
 */
class RoundSession(
    private val auth: AuthApi,
    private val storage: SessionStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : AccessTokenSource, RoundAccount {

    private val mutex = Mutex()
    private var loaded = false
    private var stored: StoredSession? = null
    private var accessToken: String? = null
    private var accessTokenExpiresAt: Instant = Instant.EPOCH
    private val state = MutableStateFlow<Account?>(null)

    override val account: StateFlow<Account?> = state.asStateFlow()

    override suspend fun load(): Account? = mutex.withLock {
        ensureLoaded()
        state.value
    }

    override suspend fun beginSignIn(): String {
        val verifier = Pkce.newVerifier(random)
        storage.setPendingVerifier(verifier)
        return auth.authorizeUrl(Pkce.challenge(verifier))
    }

    /**
     * Schließt die Anmeldung mit dem Code aus der Rückkehr-Adresse ab. Ohne begonnene Anmeldung (oder beim zweiten
     * Aufruf mit demselben Code) scheitert sie mit [RoundError.SIGNED_OUT].
     */
    override suspend fun completeSignIn(code: String): Account = mutex.withLock {
        ensureLoaded()
        val verifier = storage.pendingVerifier() ?: throw RoundException(RoundError.SIGNED_OUT, RoundAccount.NO_SIGN_IN_PENDING)
        val tokens = auth.exchangeCode(code, verifier)
        // Nur den benutzten Verifier vergessen: Wurde inzwischen eine neue Anmeldung begonnen, gehört der gespeicherte
        // schon zu ihr.
        if (storage.pendingVerifier() == verifier) storage.setPendingVerifier(null)
        apply(tokens)
        tokens.account
    }

    override suspend fun accessToken(): String = mutex.withLock {
        ensureLoaded()
        val token = accessToken
        if (token != null && clock.instant().isBefore(accessTokenExpiresAt.minus(EXPIRY_MARGIN))) return@withLock token
        refreshLocked()
    }

    override suspend fun refreshAfterUnauthorized(rejected: String): String = mutex.withLock {
        ensureLoaded()
        val token = accessToken
        // Ein anderer Aufruf hat inzwischen schon erneuert.
        if (token != null && token != rejected && clock.instant().isBefore(accessTokenExpiresAt)) return@withLock token
        refreshLocked()
    }

    /** Meldet beim Server ab, wenn er erreichbar ist, und vergisst die Anmeldung auf dem Gerät in jedem Fall. */
    override suspend fun signOut() {
        try {
            auth.signOut(accessToken())
        } catch (e: RoundException) {
            // Abmelden klappt auf dem Gerät immer, auch ohne Netz; der Server lässt die Sitzung dann ablaufen.
        }
        mutex.withLock { forgetLocked() }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val session = storage.load()
        stored = session
        state.value = session?.let { Account(it.userId, it.email) }
        loaded = true
    }

    private suspend fun refreshLocked(): String {
        val current = stored ?: throw RoundException(RoundError.SIGNED_OUT)
        val tokens = try {
            auth.refresh(current.refreshToken)
        } catch (e: RoundException) {
            // Nur wenn der Server die Sitzung sicher nicht mehr kennt, ist das gespeicherte Token wertlos.
            if (e.error == RoundError.SIGNED_OUT && e.detail == AuthApi.SESSION_GONE) forgetLocked()
            throw e
        }
        apply(tokens)
        return tokens.accessToken
    }

    private suspend fun apply(tokens: AuthTokens) {
        val session = StoredSession(tokens.refreshToken, tokens.account.userId, tokens.account.email)
        // Erst speichern, dann benutzen: Das alte Refresh-Token gilt ab jetzt nicht mehr.
        try {
            storage.save(session)
        } catch (e: RuntimeException) {
            // Streikt der Schlüsselspeicher, bleibt die Anmeldung wenigstens bis zum Beenden der App erhalten.
        }
        stored = session
        accessToken = tokens.accessToken
        accessTokenExpiresAt = tokens.expiresAt
        state.value = tokens.account
    }

    private suspend fun forgetLocked() {
        storage.clear()
        stored = null
        accessToken = null
        accessTokenExpiresAt = Instant.EPOCH
        state.value = null
        loaded = true
    }

    private companion object {
        /** So lange vor dem Ablauf gilt ein Token schon als abgelaufen, damit es unterwegs nicht verfällt. */
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
