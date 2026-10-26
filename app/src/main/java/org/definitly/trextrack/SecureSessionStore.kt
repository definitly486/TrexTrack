package org.definitly.trextrack

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the complete session encrypted with an AES-GCM key held by Android Keystore. */
class SecureSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class StoredSession(val account: String, val token: String)

    @Synchronized
    fun saveSession(account: String, token: String) {
        val plaintext = JSONObject()
            .put("account", account)
            .put("token", token)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val packed = ByteArray(cipher.iv.size + ciphertext.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(ciphertext, 0, packed, cipher.iv.size, ciphertext.size)
        check(prefs.edit().putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Не удалось сохранить зашифрованную сессию"
        }
        plaintext.fill(0)
    }

    @Synchronized
    fun readSession(): StoredSession? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_IV_LENGTH) { "Некорректные данные сессии" }
            val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
                val account = json.getString("account")
                val token = json.getString("token")
                if (account.isBlank() || token.isBlank()) null else StoredSession(account, token)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // If the key was invalidated or the blob is corrupted, discard the unusable session.
            clearSession()
            null
        }
    }

    @Synchronized
    fun clearSession() {
        prefs.edit().remove(KEY_BLOB).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "trextrack_secure_session"
        const val KEY_BLOB = "encrypted_session_v1"
        const val KEY_ALIAS = "trextrack_session_aes_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
