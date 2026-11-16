package io.github.q0ochen.schafkopf.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.q0ochen.schafkopf.domain.ports.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ver- und Entschlüsseln kleiner Geheimnisse. Als Schnittstelle, damit Tests ohne den Schlüsselspeicher von Android auskommen. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws Exception, wenn sich der Text nicht entschlüsseln lässt (zum Beispiel auf einem anderen Gerät). */
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM mit einem Schlüssel, der den Schlüsselspeicher von Android (Android Keystore) nie verlässt. Selbst wer die
 * Dateien der App ausliest, kann damit nichts entschlüsseln. Der Schlüssel entsteht beim ersten Gebrauch.
 */
class AndroidKeystoreCipher(private val alias: String = ALIAS) : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "zu kurz" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "schafkopf-geheimnisse"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}

/**
 * Kleine Geheimnisse (Schlüssel der KI, Anmeldung bei Google Drive), verschlüsselt in einer eigenen Datei der App.
 * Die Datei ist von der Datensicherung von Android ausgenommen (`res/xml/backup_rules.xml`): Auf einem anderen Gerät
 * ließe sie sich ohnehin nicht entschlüsseln. Lässt sich ein Wert nicht entschlüsseln, gilt er als nicht vorhanden.
 */
class EncryptedValues(context: Context, private val cipher: SecretCipher) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    suspend fun get(name: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(name, null) ?: return@withContext null
        try {
            cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Speichert den Wert verschlüsselt; `null` löscht ihn. */
    suspend fun set(name: String, value: String?) = withContext(Dispatchers.IO) {
        if (value == null) {
            prefs.edit { remove(name) }
        } else {
            val sealed = Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit { putString(name, sealed) }
        }
    }

    companion object {
        /** Name der Datei; muss zu den Ausnahmen in `res/xml/backup_rules.xml` und `data_extraction_rules.xml` passen. */
        const val FILE = "geheimnisse"
    }
}

/** Der Schlüssel des Abend-Kommentators, verschlüsselt auf dem Gerät. */
class EncryptedSecretStore(private val values: EncryptedValues) : SecretStore {
    override suspend fun apiKey(): String? = values.get(API_KEY)

    override suspend fun setApiKey(key: String?) {
        values.set(API_KEY, key?.trim()?.takeIf { it.isNotEmpty() })
    }

    private companion object {
        const val API_KEY = "ki_schluessel"
    }
}
