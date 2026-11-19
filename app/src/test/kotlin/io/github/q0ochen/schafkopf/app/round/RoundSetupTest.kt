package io.github.q0ochen.schafkopf.app.round

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import io.github.q0ochen.schafkopf.app.security.EncryptedValues
import io.github.q0ochen.schafkopf.app.security.SecretCipher
import io.github.q0ochen.schafkopf.data.llm.defaultHttpClient
import io.github.q0ochen.schafkopf.data.round.StoredSession
import io.github.q0ochen.schafkopf.data.round.SupabaseConfig
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.SyncOutcome
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Die Bausteine der gemeinsamen Runde in der App: Ablage der Anmeldung, Einladung merken, Text für die Datenbank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoundSetupTest {

    /** Ersatz für den Schlüsselspeicher von Android, den es in Robolectric nicht gibt; verändert prüfbar etwas. */
    private class XorCipher : SecretCipher {
        var failOnEncrypt = false
        var failOnDecrypt = false

        override fun encrypt(plain: ByteArray): ByteArray {
            if (failOnEncrypt) throw java.security.KeyStoreException("Schlüsselspeicher gestört")
            return plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        override fun decrypt(sealed: ByteArray): ByteArray {
            check(!failOnDecrypt) { "nicht lesbar" }
            return sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val session = StoredSession("ERNEUERN-GEHEIM", "konto-1", "konto-1@example.org")

    @Test
    fun `die Anmeldung liegt nur verschluesselt in der Datei und kommt unveraendert zurueck`() = runBlocking {
        val cipher = XorCipher()
        val storage = EncryptedSessionStorage(EncryptedValues(context, cipher))
        assertNull(storage.load())

        storage.save(session)
        storage.setPendingVerifier("VERIFIER-GEHEIM")

        // Ein neuer Speicher über derselben Datei, wie nach einem Neustart der App.
        val again = EncryptedSessionStorage(EncryptedValues(context, cipher))
        assertEquals(session, again.load())
        assertEquals("VERIFIER-GEHEIM", again.pendingVerifier())
        val raw = context.getSharedPreferences(EncryptedValues.FILE, 0).all.values.joinToString()
        assertFalse(raw, raw.contains("GEHEIM") || raw.contains("konto-1"))

        assertEquals(session.copy(email = null), again.apply { save(session.copy(email = null)) }.load())
        again.clear()
        again.setPendingVerifier(null)
        assertNull(storage.load())
        assertNull(storage.pendingVerifier())
    }

    @Test
    fun `laesst sich die Anmeldung nicht mehr lesen, gilt das Geraet als abgemeldet`() = runBlocking {
        val cipher = XorCipher()
        val values = EncryptedValues(context, cipher)
        val storage = EncryptedSessionStorage(values)
        storage.save(session)

        cipher.failOnDecrypt = true
        assertNull(storage.load())
        cipher.failOnDecrypt = false
        values.set("runde_sitzung", "kein json")
        assertNull(storage.load())
        values.set("runde_sitzung", "{\"userId\":\"konto-1\"}")
        assertNull(storage.load())
    }

    @Test
    fun `streikt der Schluesselspeicher, bleibt die Anmeldung bis zum Beenden der App im Speicher`() = runBlocking {
        val cipher = XorCipher().apply { failOnEncrypt = true }
        val storage = EncryptedSessionStorage(EncryptedValues(context, cipher))

        storage.save(session)
        storage.setPendingVerifier("VERIFIER")

        assertEquals(session, storage.load())
        assertEquals("VERIFIER", storage.pendingVerifier())
        // In der Datei steht nichts; ein neuer Speicher (nach einem Neustart) weiß davon nichts mehr.
        assertNull(EncryptedSessionStorage(EncryptedValues(context, XorCipher())).load())
        storage.clear()
        assertNull(storage.load())
    }

    @Test
    fun `eine Einladung bleibt gemerkt, bis sie eingeloest oder verworfen ist`() {
        val memory = PreferencesInviteMemory(context)
        assertNull(memory.get())

        memory.set("0123456789abcdef0123456789abcdef")

        assertEquals("0123456789abcdef0123456789abcdef", PreferencesInviteMemory(context).get())
        memory.set(null)
        assertNull(PreferencesInviteMemory(context).get())
    }

    @Test
    fun `Runde und Abend stehen als Text in der Datenbank und lassen sich wieder lesen`() {
        val link = RoundLink(
            roundId = RoundId("runde"),
            roundName = "Stammtisch „Zur Post“",
            userId = "konto-1",
            playerId = PlayerId("spieler-1"),
            isCreator = true,
            cursor = 123_456_789_012L,
            rulesBase = RuleSet.sauspiel(),
            members = listOf(RoundMember("konto-1", PlayerId("spieler-1")), RoundMember("konto-2", null)),
        )
        val start = Instant.parse("2026-12-12T18:00:00Z")
        val seating = listOf("spieler-1", "spieler-2", "spieler-3", "spieler-4").map(::PlayerId)
        val evening = Evening(EveningId("abend"), start, seating = seating, firstDealer = seating[0], rules = RuleSet.standard(), entries = listOf(ThrownInEntry(EntryId("e1"), start)))

        assertEquals(link, JsonRoundCodec.decodeLink(JsonRoundCodec.encodeLink(link)))
        val bare = RoundLink(RoundId("runde"), "Stammtisch", "konto-1")
        assertEquals(bare, JsonRoundCodec.decodeLink(JsonRoundCodec.encodeLink(bare)))
        assertEquals(evening, JsonRoundCodec.decodeEvening(JsonRoundCodec.encodeEvening(evening)))
        // Unlesbares gilt als nicht vorhanden; ein unlesbares Regelwerk in der Runde als unbekannt.
        assertNull(JsonRoundCodec.decodeLink("kein json"))
        assertNull(JsonRoundCodec.decodeLink("{\"roundName\":\"ohne Kennung\"}"))
        assertNull(JsonRoundCodec.decodeEvening("{}"))
        val odd = JsonRoundCodec.encodeLink(link).replace("\"prices\":{", "\"prices\":\"kaputt\",\"alt\":{")
        assertEquals(link.copy(rulesBase = null), JsonRoundCodec.decodeLink(odd))
    }

    @Test
    fun `ohne Adresse des Servers ist die gemeinsame Runde abgeschaltet`() {
        val storage = EncryptedSessionStorage(EncryptedValues(context, XorCipher()))

        assertFalse(RoundServer(null, defaultHttpClient(), storage).configured)
        assertTrue(RoundServer(SupabaseConfig.of("https://abc.supabase.co", "sb_publishable_x"), defaultHttpClient(), storage).configured)
        // So wie die App heute gebaut wird, solange app/supabase.properties leer ist.
        assertNull(SupabaseConfig.of("", ""))
    }

    @Test
    fun `der Auftrag im Hintergrund versucht es nur dann noch einmal, wenn Warten hilft`() {
        fun result(outcome: SyncOutcome, attempt: Int = 0) = RoundSyncWorker.result(outcome, attempt)

        assertEquals(Result.success(), result(SyncOutcome.Synced(pulled = 0, pushed = 2, pending = 0)))
        assertEquals(Result.retry(), result(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 1)))
        // Es wartet etwas, aber nichts hat sich bewegt: Erst eine Änderung hilft weiter.
        assertEquals(Result.success(), result(SyncOutcome.Synced(pulled = 0, pushed = 0, pending = 1)))
        assertEquals(Result.retry(), result(SyncOutcome.Offline))
        assertEquals(Result.retry(), result(SyncOutcome.Failed(RoundError.SERVER)))
        assertEquals(Result.failure(), result(SyncOutcome.Offline, attempt = RoundSyncWorker.MAX_ATTEMPTS))
        assertEquals(Result.success(), result(SyncOutcome.Synced(pulled = 1, pushed = 0, pending = 1), attempt = RoundSyncWorker.MAX_ATTEMPTS))
        for (done in listOf(SyncOutcome.SignedOut, SyncOutcome.NotLinked, SyncOutcome.Detached, SyncOutcome.Unchanged)) {
            assertEquals(Result.success(), result(done))
        }
    }
}
