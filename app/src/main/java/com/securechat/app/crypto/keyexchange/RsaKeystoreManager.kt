package com.securechat.app.crypto.keyexchange

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RSA Keystore Manager — UNIFIED key alias system
 *
 * ALL RSA keys use format: "securechat_rsa_${userId}"
 *   e.g., "securechat_rsa_user-1", "securechat_rsa_user-5"
 *
 * NO device-based aliases. NO hardcoded "secure_chat_local_key".
 * This eliminates the previous 3-way alias mismatch that made
 * encryption/decryption impossible.
 */
@Singleton
class RsaKeystoreManager @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "RsaKeystoreManager"
        private const val ALIAS_PREFIX = "securechat_rsa_"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    // Cache of team member public keys: userId -> PEM
    @Volatile
    private var teamPublicKeyCache: MutableMap<String, String> = mutableMapOf()

    // ── Key generation ──

    /**
     * Generate RSA-2048 key pair for the given userId if not already exists.
     * Alias: "securechat_rsa_${userId}"
     */
    fun generateKeyPair(userId: String): Boolean {
        val alias = ALIAS_PREFIX + userId
        return try {
            if (!keyStore.containsAlias(alias)) {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
                )
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair for $userId", e)
            false
        }
    }

    /**
     * 初始化当前登录用户的 RSA 密钥对。
     * 开放注册后不再有「团队」概念，仅需为当前账号生成本机密钥。
     * 在登录时（getPublicKeyPem）与 Hilt 注入时调用。
     */
    fun initAllTeamKeys(): Int {
        val userId = currentUserId()
        if (userId == "unknown_user") {
            Log.w(TAG, "initAllTeamKeys: 尚未登录，跳过密钥初始化")
            return 0
        }
        val alias = ALIAS_PREFIX + userId
        return try {
            if (!keyStore.containsAlias(alias)) {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
                )
                generator.initialize(spec)
                generator.generateKeyPair()
            }
            val cert = keyStore.getCertificate(alias)
            if (cert != null) {
                teamPublicKeyCache[userId] = toPem(cert.publicKey)
            }
            Log.i(TAG, "initAllTeamKeys: 已初始化当前用户 $userId 的密钥")
            teamPublicKeyCache.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init key for $userId", e)
            0
        }
    }

    // ── Public key access ──

    /**
     * Get the current user's RSA public key (PEM).
     * Alias: "securechat_rsa_${currentUserId()}"
     */
    fun getPublicKeyPem(): String {
        val userId = currentUserId()
        generateKeyPair(userId)
        val alias = ALIAS_PREFIX + userId
        val cert = keyStore.getCertificate(alias)
            ?: throw IllegalStateException("Key not found: $alias")
        return toPem(cert.publicKey)
    }

    /**
     * Get any team member's public key by userId (PEM).
     * 防御性：若缓存为空（如跨实例），尝试从 AndroidKeyStore 重新读取。
     */
    fun getTeamPublicKeyPem(userId: String): String? {
        val cached = teamPublicKeyCache[userId]
        if (cached != null) return cached
        // 缓存未命中的兜底：重新初始化团队密钥缓存
        try {
            initAllTeamKeys()
        } catch (e: Exception) {
            Log.w(TAG, "Fallback initAllTeamKeys failed", e)
        }
        return teamPublicKeyCache[userId]
    }

    // ── Crypto operations ──

    /**
     * Encrypt data with a remote public key (PEM).
     */
    fun encryptWithRemotePublicKey(plainData: ByteArray, publicKeyPem: String): String {
        return try {
            val publicKey = fromPem(publicKeyPem) as PublicKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            Base64.encodeToString(cipher.doFinal(plainData), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encrypt failed", e)
            throw e
        }
    }

    /**
     * Decrypt with the current user's local RSA private key.
     * Alias: "securechat_rsa_${currentUserId()}"
     */
    fun decryptWithLocalPrivateKey(cipherData: ByteArray): ByteArray {
        val userId = currentUserId()
        generateKeyPair(userId)
        val alias = ALIAS_PREFIX + userId
        return try {
            val privateKey = keyStore.getKey(alias, null) as PrivateKey
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            cipher.doFinal(cipherData)
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt failed for $userId (alias: $alias)", e)
            throw e
        }
    }

    // ── Identity ──

    fun currentUserId(): String {
        return try {
            context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
                .getString("auth_user_id", null)
                ?.takeIf { it.isNotEmpty() }
                ?: "unknown_user"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read auth_user_id", e)
            "unknown_user"
        }
    }

    // ── Helpers ──

    private fun toPem(key: Key): String {
        return "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encodeToString(key.encoded, Base64.NO_WRAP) +
            "\n-----END PUBLIC KEY-----"
    }

    private fun fromPem(pem: String): Key {
        val content = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
        val decoded = Base64.decode(content, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
    }
}
