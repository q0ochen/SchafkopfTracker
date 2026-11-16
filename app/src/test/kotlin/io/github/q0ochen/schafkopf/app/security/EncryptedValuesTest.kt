package io.github.q0ochen.schafkopf.app.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geheimnisse liegen nur verschlüsselt in der Datei. Der Schlüsselspeicher von Android fehlt in Robolectric; deshalb
 * steht hier eine einfache Ersatz-Verschlüsselung, die prüfbar etwas verändert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EncryptedValuesTest {

    private class XorCipher(private val mask: Byte = 0x5A) : SecretCipher {
        var broken = false
        override fun encrypt(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor mask.toInt()).toByte() }
        override fun decrypt(sealed: ByteArray): ByteArray {
            check(!broken && sealed.first() == 1.toByte()) { "falscher Schlüssel" }
            return sealed.drop(1).map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `speichert verschluesselt, liest zurueck und loescht`() = runBlocking {
        val cipher = XorCipher()
        val values = EncryptedValues(context, cipher)
        values.set("a", "streng geheim")
        assertEquals("streng geheim", values.get("a"))
        val raw = context.getSharedPreferences(EncryptedValues.FILE, 0).all.values.joinToString()
        assertFalse(raw, raw.contains("geheim"))
        values.set("a", null)
        assertNull(values.get("a"))
    }

    @Test
    fun `ein nicht mehr lesbarer Wert gilt als nicht vorhanden`() = runBlocking {
        val cipher = XorCipher()
        val values = EncryptedValues(context, cipher)
        values.set("a", "x")
        cipher.broken = true
        assertNull(values.get("a"))
    }

    @Test
    fun `der Schluessel des Kommentators wird getrimmt, leer heisst loeschen`() = runBlocking {
        val store = EncryptedSecretStore(EncryptedValues(context, XorCipher()))
        store.setApiKey("  abc  ")
        assertEquals("abc", store.apiKey())
        store.setApiKey("   ")
        assertNull(store.apiKey())
    }
}
