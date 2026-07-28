package com.securechat.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * RSA public/private key management for envelope encryption key wrapping.
 * Stores keys in Android Keystore for hardware-backed security.
 */
class EncryptionEngine @Inject constructor() {

    companion object {
        private const val TAG = "EncryptionEngine"
    }

    /**
     * Encrypts a small payload (e.g., AES session key) with the recipient's public key.
     */
    fun encryptWithPublicKey(publicKey: PublicKey, plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plaintext)
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypts ciphertext with the local private key from Keystore.
     */
    fun decryptWithPrivateKey(privateKey: PrivateKey, encryptedBase64: String): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encrypted = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        return cipher.doFinal(encrypted)
    }

    /**
     * Retrieves the local user's public key from Keystore.
     * Generates if first time.
     */
    fun getLocalPublicKey(): PublicKey? {
        return try {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                "secure_chat_rsa_key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair().public
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get public key", e)
            null
        }
    }

    /**
     * Retrieves the local user's private key from Keystore.
     */
    fun getLocalPrivateKey(): PrivateKey? {
        return try {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                "secure_chat_rsa_key",
                KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair().private
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get private key", e)
            null
        }
    }
}