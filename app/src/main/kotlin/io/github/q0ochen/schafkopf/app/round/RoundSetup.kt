package io.github.q0ochen.schafkopf.app.round

import android.content.Context
import androidx.core.content.edit
import io.github.q0ochen.schafkopf.app.security.EncryptedValues
import io.github.q0ochen.schafkopf.data.round.RoundSession
import io.github.q0ochen.schafkopf.data.round.RoundStoreJson
import io.github.q0ochen.schafkopf.data.round.SessionStorage
import io.github.q0ochen.schafkopf.data.round.StoredSession
import io.github.q0ochen.schafkopf.data.round.SupabaseAuthApi
import io.github.q0ochen.schafkopf.data.round.SupabaseConfig
import io.github.q0ochen.schafkopf.data.round.SupabaseHttp
import io.github.q0ochen.schafkopf.data.round.SupabaseRoundApi
import io.github.q0ochen.schafkopf.database.RoundCodec
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.round.RoundApi
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Der Server der gemeinsamen Runde, wie diese App ihn kennt: ob er eingerichtet ist, die Anmeldung und die Aufrufe.
 *
 * Ohne Konfiguration (App ohne Adresse und Schlüssel gebaut) zeigt alles auf eine Adresse, die es nicht geben kann;
 * benutzt wird sie nie, weil die Oberfläche dann nur „Der Server ist noch nicht eingerichtet“ zeigt.
 */
class RoundServer(config: SupabaseConfig?, client: OkHttpClient, storage: SessionStorage) {
    val configured: Boolean = config != null

    private val http = SupabaseHttp(client, config ?: SupabaseConfig(NOWHERE, "nicht-eingerichtet"))
    val session = RoundSession(SupabaseAuthApi(http), storage)
    val api: RoundApi = SupabaseRoundApi(http, session)

    private companion object {
        /** Die Endung `.invalid` ist dafür reserviert, dass es sie nie gibt. */
        const val NOWHERE = "https://nicht-eingerichtet.invalid"
    }
}

/**
 * Die Anmeldung bei der gemeinsamen Runde, verschlüsselt auf dem Gerät (wie der Schlüssel des Abend-Kommentators).
 * Lässt sich der Eintrag nicht lesen, gilt das Gerät als abgemeldet. Streikt der Schlüsselspeicher beim Schreiben,
 * bleibt die Anmeldung wenigstens bis zum Beenden der App im Arbeitsspeicher; abstürzen darf die App deswegen nie.
 */
class EncryptedSessionStorage(private val values: EncryptedValues) : SessionStorage {
    /** Nur gefüllt, wenn sich nicht verschlüsselt speichern ließ. */
    @Volatile
    private var unsavedSession: StoredSession? = null

    @Volatile
    private var unsavedVerifier: String? = null

    override suspend fun load(): StoredSession? {
        unsavedSession?.let { return it }
        val text = values.get(SESSION) ?: return null
        return try {
            val json = JSONObject(text)
            StoredSession(
                refreshToken = json.getString("refreshToken"),
                userId = json.getString("userId"),
                email = json.optString("email").takeIf { it.isNotEmpty() },
            )
        } catch (_: JSONException) {
            null
        }
    }

    override suspend fun save(session: StoredSession) {
        val json = JSONObject()
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("email", session.email.orEmpty())
        unsavedSession = if (write(SESSION, json.toString())) null else session
    }

    override suspend fun clear() {
        unsavedSession = null
        write(SESSION, null)
    }

    override suspend fun pendingVerifier(): String? = unsavedVerifier ?: values.get(VERIFIER)

    override suspend fun setPendingVerifier(verifier: String?) {
        unsavedVerifier = if (write(VERIFIER, verifier)) null else verifier
    }

    /** @return `false`, wenn der Schlüsselspeicher des Geräts nicht verschlüsseln konnte. */
    private suspend fun write(name: String, value: String?): Boolean = try {
        values.set(name, value)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val SESSION = "runde_sitzung"
        const val VERIFIER = "runde_pkce"
    }
}

/** Runde und Basen in der Datenbank in der Schreibweise von [RoundStoreJson] (Abende wie in der Sicherung). */
object JsonRoundCodec : RoundCodec {
    override fun encodeLink(link: RoundLink): String = RoundStoreJson.encodeLink(link)
    override fun decodeLink(text: String): RoundLink? = RoundStoreJson.decodeLink(text)
    override fun encodeEvening(evening: Evening): String = RoundStoreJson.encodeEvening(evening)
    override fun decodeEvening(text: String): Evening? = RoundStoreJson.decodeEvening(text)
}

/** Merkt sich einen Einladungscode, bis er eingelöst oder verworfen ist: auch über die Anmeldung im Browser hinweg. */
interface InviteMemory {
    fun get(): String?

    fun set(token: String?)

    /** Die zuletzt auf diesem Gerät erzeugte Einladung (zum Wiederverwenden); `null`, wenn keine. */
    fun issued(): IssuedInvite?

    fun setIssued(invite: IssuedInvite?)
}

/** Eine Einladung, die dieses Gerät erzeugt hat (docs/ARCHITEKTUR.md, 8.4). */
data class IssuedInvite(val roundId: String, val token: String, val createdAt: Instant)

/** Der Einladungscode in den Einstellungen der App. Er ist kein Geheimnis: Ohne Anmeldung nützt er niemandem. */
class PreferencesInviteMemory(context: Context) : InviteMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(token: String?) = prefs.edit { if (token == null) remove(KEY) else putString(KEY, token) }

    override fun issued(): IssuedInvite? {
        val roundId = prefs.getString(ISSUED_ROUND, null) ?: return null
        val token = prefs.getString(ISSUED_TOKEN, null) ?: return null
        val createdAt = prefs.getLong(ISSUED_AT, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        return IssuedInvite(roundId, token, Instant.ofEpochMilli(createdAt))
    }

    override fun setIssued(invite: IssuedInvite?) = prefs.edit {
        if (invite == null) {
            remove(ISSUED_ROUND)
            remove(ISSUED_TOKEN)
            remove(ISSUED_AT)
        } else {
            putString(ISSUED_ROUND, invite.roundId)
            putString(ISSUED_TOKEN, invite.token)
            putLong(ISSUED_AT, invite.createdAt.toEpochMilli())
        }
    }

    private companion object {
        const val FILE = "gemeinsame-runde"
        const val KEY = "einladung"
        const val ISSUED_ROUND = "erzeugt-runde"
        const val ISSUED_TOKEN = "erzeugt-code"
        const val ISSUED_AT = "erzeugt-am"
    }
}
