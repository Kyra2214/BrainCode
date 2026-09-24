package com.sandbox.app

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Armazena credenciais cifradas em repouso; a chave nunca sai do Android Keystore. */
class ApiKeyStore(context: Context) {
    internal val appContext: Context = context.applicationContext

    private companion object {
        private const val PREFS_NAME = "api_keys_secure"
        private const val KEY_ALIAS = "braincode-api-keys-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)

    fun get(providerId: String): String? {
        val encoded = prefs.getString(providerId, null)
        if (encoded != null) return decrypt(encoded)
        val legacy = legacyPrefs.getString(providerId, null) ?: return null
        save(providerId, legacy)
        legacyPrefs.edit().remove(providerId).apply()
        return legacy
    }

    fun save(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) clear(providerId)
        else prefs.edit().putString(providerId, encrypt(apiKey)).apply()
    }

    fun clear(providerId: String) {
        prefs.edit().remove(providerId).apply()
        legacyPrefs.edit().remove(providerId).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ByteBuffer.allocate(4 + nonce.size + ciphertext.size).putInt(nonce.size).put(nonce).put(ciphertext).array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(raw)
        val nonce = ByteArray(buffer.int).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce)) }
            .doFinal(ciphertext).toString(Charsets.UTF_8)
    }.getOrNull()
}
