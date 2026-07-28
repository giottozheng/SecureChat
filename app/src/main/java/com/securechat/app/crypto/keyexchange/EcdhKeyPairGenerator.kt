// 密钥握手中的 ECDH 密钥生成与计算工具类
package com.securechat.app.crypto.keyexchange

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac

/**
 * ECDH 临时密钥对生成器
 * 在每次密钥交换时生成新的临时密钥对，提供前向安全性
 */
object EcdhKeyPairGenerator {

    private const val ELLIPTIC_CURVE_NAME = "secp256r1"
    private var myEphemeralPrivateKey: PrivateKey? = null
    private var myEphemeralPublicKey: PublicKey? = null

    /**
     * 生成一对新的 ECDH 临时密钥对
     */
    suspend fun generatePair(): Pair<String, PrivateKey> {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec(ELLIPTIC_CURVE_NAME))
        val keyPair = keyPairGen.generateKeyPair()

        myEphemeralPrivateKey = keyPair.private
        myEphemeralPublicKey = keyPair.public

        val publicKeyBytes = keyPair.public.encoded
        val publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
            android.util.Base64.encodeToString(publicKeyBytes, android.util.Base64.NO_WRAP).trimIndent() +
            "\n-----END PUBLIC KEY-----"

        return Pair(publicKeyPem, keyPair.private)
    }

    /**
     * 获取本机的 ECDH 临时私钥（用于接收方计算共享密钥）
     */
    suspend fun getMyEphemeralPrivateKey(): PrivateKey {
        return myEphemeralPrivateKey
            ?: throw IllegalStateException("请先调用 generatePair() 生成本机临时密钥对")
    }

    /**
     * 使用 ECDH 计算最终共享密钥
     */
    suspend fun computeSharedKey(
        myPrivateKey: PrivateKey,
        peerPublicKeyPem: String,
        peerSharedSecret: ByteArray
    ): ByteArray {
        val publicKeyBytes = extractPublicKeyBytes(peerPublicKeyPem)
        val keySpec = X509EncodedKeySpec(publicKeyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        val peerPublicKey = keyFactory.generatePublic(keySpec) as PublicKey

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedKey = keyAgreement.generateSecret()

        val result = ByteArray(sharedKey.size)
        val ecdhBytes = sharedKey
        for (i in ecdhBytes.indices) {
            result[i] = (ecdhBytes[i].toInt() xor peerSharedSecret[i % peerSharedSecret.size].toInt()).toByte()
        }

        return result
    }

    /**
     * 从 PEM 格式的公钥字符串中提取字节数组
     */
    private fun extractPublicKeyBytes(pem: String): ByteArray {
        val cleaned = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        return android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
    }
}