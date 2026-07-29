package com.securechat.app.crypto

import android.util.Base64
import android.util.Log
import com.securechat.app.crypto.keyexchange.RsaKeystoreManager
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信封加密引擎
 *
 * 工作原理（AES-256-GCM + RSA-2048 信封加密）：
 * 1. 每次加密生成一个新的随机 AES 会话密钥（ephemeral）
 * 2. 用该 AES 密钥加密消息体（AES-256-GCM）
 * 3. 用收件人的 RSA 公钥包裹 AES 会话密钥
 * 4. 最终格式：v1:<RSA加密的AES密钥>:<IV Base64>:<密文 Base64>
 *
 * 密钥管理：
 *   - 所有 RSA 密钥统一由 RsaKeystoreManager 管理
 *   - 别名格式：securechat_rsa_${userId}
 *   - 解密时使用 RsaKeystoreManager.decryptWithLocalPrivateKey()
 *   - 不再维护自己的 KeyStore 实例和 RSA_KEY_ALIAS
 */
@Singleton
/**
 * 收件人设备（用于 v2 多 wrap 信封）。
 * tag：'m' = 移动端（账号主密钥），桌面端用各自 deviceId。
 */
data class RecipientDevice(val tag: String, val pubPem: String)

/**
 * v2 加密结果：信封字符串 + 会话密钥字节 + 消息 IV 字节。
 * 会话密钥需回传调用方，供「统一媒体设计」加密图片/文件字节（与消息元数据共用一把密钥）。
 */
data class V2Envelope(val envelope: String, val sessionKey: ByteArray, val iv: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as V2Envelope
        return envelope == other.envelope &&
            sessionKey.contentEquals(other.sessionKey) &&
            iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = envelope.hashCode()
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

class MessageEncryptor @Inject constructor(
    private val rsaKeystoreManager: RsaKeystoreManager
) {

    companion object {
        private const val TAG = "MessageEncryptor"
        private const val AES_KEY_SIZE = 256
        private const val AES_GCM_IV_LENGTH = 12
        private const val AES_GCM_TAG_LENGTH = 128

        /**
         * 获取一个允许调用者指定 IV 的 AES/GCM Cipher。
         * 平台默认 provider(AndroidKeyStoreBCWorkaround)在 Android 9+ 拒绝 caller-provided IV，
         * 因此显式选择支持该用法的 provider(BC / AndroidOpenSSL / SunJCE / SunPKCS11)。
         */
        private fun getAesGcmCipher(): Cipher {
            val providers = listOf("BC", "AndroidOpenSSL", "SunJCE", "SunPKCS11")
            for (p in providers) {
                try {
                    return Cipher.getInstance("AES/GCM/NoPadding", p)
                } catch (_: Exception) {
                }
            }
            return getAesGcmCipher()
        }
    }

    // ── Crypto operations ──

    /**
     * 用本地 RSA 私钥解包 AES 密钥
     * 委托给 RsaKeystoreManager.decryptWithLocalPrivateKey()
     */
    private fun unwrapAesKey(encryptedAesKeyBytes: ByteArray): SecretKey? {
        return try {
            val decryptedBytes = rsaKeystoreManager.decryptWithLocalPrivateKey(encryptedAesKeyBytes)
            SecretKeySpec(decryptedBytes, "AES")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap AES key", e)
            null
        }
    }

    /**
     * 加密消息 — 信封加密
     * @param plaintext 明文消息
     * @param recipientPublicKeyPem 收件人的 RSA 公钥（PEM 字符串）
     * @return 加密后 payload 字符串，或直接 null 表示失败
     */
    fun encryptMessage(
        plaintext: String,
        recipientPublicKeyPem: String
    ): String? {
        return try {
            // 1. 生成随机 AES 会话密钥
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val sessionKey = keyGen.generateKey()

            // 2. 生成随机 IV
            val iv = ByteArray(AES_GCM_IV_LENGTH).also {
                java.security.SecureRandom().nextBytes(it)
            }

            // 3. AES-GCM 加密消息体
            val encryptCipher = getAesGcmCipher()
            encryptCipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            val ciphertext = encryptCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 4. 用收件人（真实）RSA 公钥包裹 AES 会话密钥
            val recipientPubKey = parsePublicKeyFromPem(recipientPublicKeyPem)
            val wrapR = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, recipientPubKey)
            }.doFinal(sessionKey.encoded)

            // 5. 同时用发送者自己的 RSA 公钥包裹（双信封），使发送者本地也能解密自己发出的消息
            val senderPem = rsaKeystoreManager.getPublicKeyPem()
            val senderPubKey = parsePublicKeyFromPem(senderPem)
            val wrapS = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, senderPubKey)
            }.doFinal(sessionKey.encoded)

            // 6. 组装 payload: v1:<收件人包裹>:<发送者包裹>:<IV>:<密文>
            "v1:" + Base64.encodeToString(wrapR, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(wrapS, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    /**
     * 解密消息 — 信封解密
     * @param payload 加密 payload 字符串（v1:... 格式）
     * @return 明文，失败返回 null
     */
    fun decryptMessage(payload: String): String? {
        return try {
            // 双信封格式: v1:<wrapR>:<wrapS>:<iv>:<ciphertext>
            val parts = payload.split(":", limit = 5)
            if (parts.size != 5 || parts[0] != "v1") {
                Log.w(TAG, "Invalid payload version or format: ${parts.size} parts")
                return null
            }

            // Step 1: 解包 AES 会话密钥（先试收件人包裹，失败再试发送者/自己包裹）
            val wrappedR = Base64.decode(parts[1], Base64.NO_WRAP)
            val wrappedS = Base64.decode(parts[2], Base64.NO_WRAP)
            val sessionKey = unwrapEither(wrappedR, wrappedS) ?: return null

            // Step 2: 解码 IV
            val iv = Base64.decode(parts[3], Base64.NO_WRAP)

            // Step 3: AES-GCM 解密
            val decryptCipher = getAesGcmCipher()
            decryptCipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            val plaintextBytes = decryptCipher.doFinal(Base64.decode(parts[4], Base64.NO_WRAP))

            String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * 加密字节数组（用于图片等二进制数据）— 信封加密
     * 与 encryptMessage 相同流程，但直接以字节数组为明文输入。
     * @return v1:<wrapR>:<wrapS>:<iv>:<密文Base64> 格式 payload
     */
    fun encryptBytes(
        plainBytes: ByteArray,
        recipientPublicKeyPem: String
    ): String? {
        return try {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val sessionKey = keyGen.generateKey()

            val iv = ByteArray(AES_GCM_IV_LENGTH).also {
                java.security.SecureRandom().nextBytes(it)
            }

            val encryptCipher = getAesGcmCipher()
            encryptCipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            val ciphertext = encryptCipher.doFinal(plainBytes)

            val recipientPubKey = parsePublicKeyFromPem(recipientPublicKeyPem)
            val wrapR = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, recipientPubKey)
            }.doFinal(sessionKey.encoded)

            val senderPem = rsaKeystoreManager.getPublicKeyPem()
            val senderPubKey = parsePublicKeyFromPem(senderPem)
            val wrapS = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, senderPubKey)
            }.doFinal(sessionKey.encoded)

            "v1:" + Base64.encodeToString(wrapR, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(wrapS, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Byte encryption failed", e)
            null
        }
    }

    /**
     * 解密字节数组（图片等二进制）— 信封解密
     * @return 明文字节数组，失败返回 null
     */
    fun decryptBytes(payload: String): ByteArray? {
        return try {
            val parts = payload.split(":", limit = 5)
            if (parts.size != 5 || parts[0] != "v1") {
                Log.w(TAG, "Invalid byte payload version or format: ${parts.size} parts")
                return null
            }
            val wrappedR = Base64.decode(parts[1], Base64.NO_WRAP)
            val wrappedS = Base64.decode(parts[2], Base64.NO_WRAP)
            val sessionKey = unwrapEither(wrappedR, wrappedS) ?: return null
            val iv = Base64.decode(parts[3], Base64.NO_WRAP)
            val decryptCipher = getAesGcmCipher()
            decryptCipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
            decryptCipher.doFinal(Base64.decode(parts[4], Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Byte decryption failed", e)
            null
        }
    }

    /**
     * 双信封解包：先用收件人包裹，失败再用发送者（自己）包裹解密。
     * 仅与本地私钥匹配的包裹能成功解包。
     */
    private fun unwrapEither(wrappedR: ByteArray, wrappedS: ByteArray): SecretKey? {
        unwrapAesKey(wrappedR)?.let { return it }
        return unwrapAesKey(wrappedS)
    }

    /**
     * AES-256-GCM 加密（指定密钥 + IV，无信封前缀）。供 v2 信封与统一媒体复用。
     */
    private fun aesEncryptBytes(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = getAesGcmCipher()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        return cipher.doFinal(data)
    }

    /**
     * AES-256-GCM 解密（指定密钥 + Base64 IV/密文）。供统一媒体接收侧复用（当前预留）。
     */
    private fun aesDecryptBytes(key: ByteArray, ivB64: String, ctB64: String): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = getAesGcmCipher()
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(AES_GCM_TAG_LENGTH, iv))
        return cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
    }

    // ── v2 多设备信封（P3） ──

    /**
     * v2 多 wrap 信封加密（随机会话密钥）。
     * 为每个收件人设备生成一段 RSA 包裹，另加发送者自读包裹；服务端按目标设备抽 wrap 重组为 v1 下发。
     * @param senderDevTag 发送者设备标识（移动端传自身 userId，桌面端传自身 deviceId），
     *   务必与收件人 'm' 区分，避免 wrap 表 key 冲突导致服务端抽错包裹。
     */
    fun encryptMessageV2(
        plaintext: String,
        recipients: List<RecipientDevice>,
        senderDevTag: String
    ): V2Envelope? {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE)
        val sessionKey = keyGen.generateKey()
        val iv = ByteArray(AES_GCM_IV_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
        return encryptMessageV2WithKey(plaintext, recipients, senderDevTag, sessionKey.encoded, iv)
    }

    /**
     * 用「指定会话密钥 + IV」加密 v2 信封（统一媒体设计：消息元数据与媒体字节共用一把会话密钥）。
     */
    fun encryptMessageV2WithKey(
        plaintext: String,
        recipients: List<RecipientDevice>,
        senderDevTag: String,
        sessionKey: ByteArray,
        iv: ByteArray
    ): V2Envelope? {
        return try {
            val ct = aesEncryptBytes(sessionKey, iv, plaintext.toByteArray(Charsets.UTF_8))
            val parts = mutableListOf("v2", recipients.size.toString())
            for (r in recipients) {
                val pub = parsePublicKeyFromPem(r.pubPem)
                val wrap = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                    init(Cipher.ENCRYPT_MODE, pub)
                }.doFinal(sessionKey)
                parts.add(r.tag)
                parts.add(Base64.encodeToString(wrap, Base64.NO_WRAP))
            }
            // 发送者自读包裹（用自身公钥）
            val senderPub = parsePublicKeyFromPem(rsaKeystoreManager.getPublicKeyPem())
            val wSelf = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(Cipher.ENCRYPT_MODE, senderPub)
            }.doFinal(sessionKey)
            parts.add(senderDevTag)
            parts.add(Base64.encodeToString(wSelf, Base64.NO_WRAP))
            parts.add(Base64.encodeToString(iv, Base64.NO_WRAP))
            parts.add(Base64.encodeToString(ct, Base64.NO_WRAP))
            V2Envelope(parts.joinToString(":"), sessionKey, iv)
        } catch (e: Exception) {
            Log.e(TAG, "v2 encryption failed", e)
            null
        }
    }

    /**
     * 用指定会话密钥 + IV 加密任意字节（统一媒体设计：图片/文件字节，无信封前缀）。
     * 与 decryptData 配套，供收发双方用同一会话密钥解开消息元数据与媒体字节。
     */
    fun encryptData(sessionKey: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
        return try {
            aesEncryptBytes(sessionKey, iv, data)
        } catch (e: Exception) {
            Log.e(TAG, "encryptData failed", e)
            null
        }
    }

    /**
     * 用指定会话密钥 + IV(Base64) 解密原始密文（统一媒体设计：图片/文件字节）。
     * 预留给后续接收侧解析统一媒体（桌面→移动）。当前接收侧按既有逻辑处理，未启用。
     */
    fun decryptData(sessionKey: ByteArray, ivB64: String, ctB64: String): ByteArray? {
        return try {
            aesDecryptBytes(sessionKey, ivB64, ctB64)
        } catch (e: Exception) {
            Log.e(TAG, "decryptData failed", e)
            null
        }
    }

    /**
     * 获取本用户的 RSA 公钥（PEM 字符串），委托给 RsaKeystoreManager
     */
    fun getLocalPublicKeyPem(): String? {
        return try {
            rsaKeystoreManager.getPublicKeyPem()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local public key PEM", e)
            null
        }
    }

    /**
     * 从 PEM 字符串解析 PublicKey
     */
    private fun parsePublicKeyFromPem(pem: String): PublicKey {
        val cleaned = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val encoded = Base64.decode(cleaned, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(encoded)
        val factory = java.security.KeyFactory.getInstance("RSA")
        return factory.generatePublic(spec)
    }
}
