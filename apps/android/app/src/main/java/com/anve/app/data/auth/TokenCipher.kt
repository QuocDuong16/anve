package com.anve.app.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted blob: ciphertext plus the random IV used to seal it. */
data class SealedBytes(val ciphertext: ByteArray, val iv: ByteArray)

/**
 * AES-256-GCM sealing behind an interface, so JVM tests can substitute a
 * plain in-memory cipher while production uses the Android Keystore.
 */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): SealedBytes
    fun decrypt(sealed: SealedBytes): ByteArray
}

/**
 * Seals session tokens with a key that never leaves the Android Keystore
 * (hardware-backed where the device supports it). The same key encrypts
 * every blob; each encryption uses a fresh random IV — never reuse one
 * under GCM.
 */
class KeystoreTokenCipher(
    keyAlias: String = DEFAULT_KEY_ALIAS,
) : TokenCipher {

    private val key: SecretKey = getOrCreateKey(keyAlias)

    override fun encrypt(plaintext: ByteArray): SealedBytes {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return SealedBytes(cipher.doFinal(plaintext), iv)
    }

    override fun decrypt(sealed: SealedBytes): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "anve_session_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}
