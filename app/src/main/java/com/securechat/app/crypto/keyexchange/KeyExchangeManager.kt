// Key Exchange Protocol for establishing per-chat encryption keys
package com.securechat.app.crypto.keyexchange

import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥交换管理器
 * 负责在聊天开始时生成/交换会话密钥
 *
 * 流程：
 * 1. 用户A 请求与用户B 建立安全聊天
 * 2. 服务端通知用户B 发起密钥握手
 * 3. 双方各自生成 ECDH 密钥对
 * 4. 交换公钥，计算共享密钥
 * 5. 用对方 RSA 公钥包裹共享密钥，加密传输
 * 6. 双方保存会话密钥用于 AES-GCM 加密
 */
@Singleton
class KeyExchangeManager @Inject constructor(
    private val rsaHelper: RsaKeystoreManager
) {

    /**
     * 会话密钥数据类
     */
    data class SessionKey(
        val peerUserId: String,
        val peerPublicKeyPem: String,
        val sharedSecret: ByteArray,
        val createdAt: Long
    )

    /**
     * 密钥握手中的消息结构
     */
    data class KeyExchangeMessage(
        val fromUserId: String,
        val timestamp: Long,
        val encryptedSharedSecret: String, // RSA 加密后的共享密钥
        val ephemeralPublicKeyPem: String  // ECDH 临时公钥
    )

    /**
     * 初始化密钥交换
     * 生成 ECDH 临时密钥对和共享随机密钥
     */
    suspend fun initiateExchange(peerUserId: String, peerPublicKeyPem: String): KeyExchangeMessage {
        // 1. 生成本机 ECDH 临时密钥对
        val (ephemeralPublicKeyPem, privateKey) = EcdhKeyPairGenerator.generatePair()

        // 2. 生成 256-bit 共享随机密钥
        val sharedSecret = ByteArray(32)
        java.security.SecureRandom.getInstanceStrong().nextBytes(sharedSecret)

        // 3. 用对端 RSA 公钥加密共享密钥
        val encryptedSecret = rsaHelper.encryptWithRemotePublicKey(
            plainData = sharedSecret,
            publicKeyPem = peerPublicKeyPem
        )

        return KeyExchangeMessage(
            fromUserId = rsaHelper.currentUserId(),
            timestamp = System.currentTimeMillis(),
            encryptedSharedSecret = encryptedSecret,
            ephemeralPublicKeyPem = ephemeralPublicKeyPem
        )
    }

    /**
     * 处理接收到的密钥交换消息
     * 计算共享密钥并返回本机响应
     */
    suspend fun processExchange(
        message: KeyExchangeMessage,
        myEphemeralPublicKeyPem: String,
        myUserId: String
    ): SessionKey {
        // 1. 用自己的私钥解密对方的共享密钥
        val peerSharedSecret = rsaHelper.decryptWithLocalPrivateKey(
            cipherData = Base64.decode(message.encryptedSharedSecret, Base64.DEFAULT)
        )

        // 2. 使用 ECDH 计算最终共享密钥
        val finalSharedSecret = EcdhKeyPairGenerator.computeSharedKey(
            myPrivateKey = EcdhKeyPairGenerator.getMyEphemeralPrivateKey(),
            peerPublicKeyPem = message.ephemeralPublicKeyPem,
            peerSharedSecret = peerSharedSecret
        )

        return SessionKey(
            peerUserId = message.fromUserId,
            peerPublicKeyPem = message.ephemeralPublicKeyPem,
            sharedSecret = finalSharedSecret,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * 验证密钥交换是否完成
     */
    fun isSessionActive(sessionKey: SessionKey?): Boolean {
        return sessionKey != null &&
            (System.currentTimeMillis() - sessionKey.createdAt) < MAX_SESSION_LIFETIME_MS
    }

    companion object {
        // 会话密钥有效期 24 小时，之后重新握手
        private const val MAX_SESSION_LIFETIME_MS = 24 * 60 * 60 * 1000L
    }
}
