package com.securechat.app.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.MessageDao
import com.securechat.app.data.local.ConversationEntity
import com.securechat.app.data.local.MessageEntity
import com.securechat.app.data.model.*
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.crypto.RecipientDevice
import com.securechat.app.crypto.V2Envelope
import com.securechat.app.network.api.ApiService
import com.securechat.app.util.teamDisplayName
import com.securechat.app.util.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for all messaging data.
 * Handles encryption, decryption, Room persistence, and API calls.
 */
@Singleton
class MessageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val apiService: ApiService,
    private val messageEncryptor: MessageEncryptor
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // 服务端公钥缓存：recipientId -> (PEM, 缓存时的 keyEpoch)（避免每次发送都网络请求）
    private data class CachedPeerKey(val pem: String, val epoch: Long)
    private val remotePublicKeyCache = mutableMapOf<String, CachedPeerKey>()

    /**
     * 清空对端公钥缓存。在「重新同步密钥」时调用，迫使下次发送重新拉取最新公钥。
     */
    fun invalidatePeerCache() {
        remotePublicKeyCache.clear()
    }

    // ── P3：多设备（v2 信封）支持 ──

    private data class DeviceInfo(val deviceId: String, val pubKey: String)
    private data class RecipientDevicesResult(
        val mobilePubKey: String,
        val mobileKeyEpoch: Long,
        val desktopDevices: List<DeviceInfo>
    )

    /**
     * 从服务端 GET /api/users/:userId/devices 拉取收件人全部设备公钥。
     * 返回移动端主公钥（mobilePubKey）与所有桌面端设备公钥；同时刷新对端 keyEpoch。
     * 网络失败返回 null，调用方应回退到单移动端 v1 信封。
     */
    private suspend fun fetchRecipientDevices(recipientId: String, authToken: String): RecipientDevicesResult? {
        return withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/users/$recipientId/devices"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                response = http.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val json = org.json.JSONObject(body ?: "{}")
                    val mobilePubKey = json.optString("mobilePubKey", "").takeIf { it.isNotBlank() } ?: ""
                    val mobileKeyEpoch = json.optLong("mobileKeyEpoch", 0L)
                    if (mobilePubKey.isNotBlank()) {
                        com.securechat.app.crypto.PeerKeyEpochStore.set(recipientId, mobileKeyEpoch)
                    }
                    val arr = json.optJSONArray("devices")
                    val desktop = mutableListOf<DeviceInfo>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val d = arr.optJSONObject(i) ?: continue
                            if (d.optString("type", "") == "desktop") {
                                val pk = d.optString("pubKey", "")
                                val did = d.optString("deviceId", "")
                                if (pk.isNotBlank() && did.isNotBlank()) desktop.add(DeviceInfo(did, pk))
                            }
                        }
                    }
                    RecipientDevicesResult(mobilePubKey, mobileKeyEpoch, desktop)
                } else {
                    Log.w(TAG, "Fetch recipient devices HTTP ${response.code} for $recipientId")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch recipient devices", e)
                null
            } finally {
                response?.close()
            }
        }
    }

    /**
     * 构造 v2 收件人列表：移动端('m') + 每个桌面端(deviceId)。
     */
    private fun buildV2Recipients(devices: RecipientDevicesResult): List<RecipientDevice> {
        val list = mutableListOf<RecipientDevice>()
        if (devices.mobilePubKey.isNotBlank()) {
            list.add(RecipientDevice("m", devices.mobilePubKey))
        }
        devices.desktopDevices.forEach { list.add(RecipientDevice(it.deviceId, it.pubKey)) }
        return list
    }

    /**
     * 获取收件人的真实 RSA 公钥：优先缓存，否则从服务端拉取（收件人登录时已注册）。
     * 当已知对端 keyEpoch 大于缓存中的 epoch 时，视为密钥已变更，强制重新拉取。
     */
    private suspend fun getRecipientPublicKey(recipientId: String, authToken: String): String? {
        val knownEpoch = com.securechat.app.crypto.PeerKeyEpochStore.get(recipientId)
        val cached = remotePublicKeyCache[recipientId]
        if (cached != null && (knownEpoch == null || cached.epoch >= knownEpoch)) {
            return cached.pem
        }
        var key = fetchRecipientPublicKeyFromServer(recipientId, authToken)
        if (key.isNullOrBlank()) {
            // 兜底重试一次：对方可能刚启动应用，公钥尚未注册到服务器
            kotlinx.coroutines.delay(800)
            key = fetchRecipientPublicKeyFromServer(recipientId, authToken)
        }
        if (!key.isNullOrBlank()) remotePublicKeyCache[recipientId] = CachedPeerKey(key, knownEpoch ?: 0L)
        return key
    }

    /**
     * 从服务端 GET /api/users/:userId/public-key 拉取收件人真实公钥。
     */
    private suspend fun fetchRecipientPublicKeyFromServer(recipientId: String, authToken: String): String? {
        return withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/users/$recipientId/public-key"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                response = http.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val json = org.json.JSONObject(body ?: "{}")
                    val pem = json.optString("publicKeyPem", "").takeIf { it.isNotBlank() }
                    val epoch = json.optLong("keyEpoch", 0L)
                    if (pem != null) com.securechat.app.crypto.PeerKeyEpochStore.set(recipientId, epoch)
                    pem
                } else {
                    Log.w(TAG, "Fetch recipient key HTTP ${response.code} for $recipientId")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch recipient public key", e)
                null
            } finally {
                response?.close()
            }
        }
    }

    companion object {
        private const val TAG = "MessageRepository"
    }

    /**
     * Get the current logged-in user's ID from SharedPreferences.
     * Falls back to "local_user" if not available.
     */
    val localUserId: String
        get() = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            .getString("auth_user_id", "local_user")
            ?.takeIf { it.isNotEmpty() }
            ?: "local_user"

    /**
     * Get all conversations sorted by recency.
     */
    fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
            .mapNotNull { entities ->
                entities.map { entity ->
                    Conversation(
                        id = entity.id,
                        participants = entity.participantIds.split(","),
                        lastMessage = null,  // TODO: fetch last message
                        unreadCount = entity.unreadCount,
                        updatedAt = entity.updatedAt,
                        name = entity.name
                    )
                }
            }
    }

    /**
     * Get messages for a specific conversation.
     */
    fun getConversationMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesByConversation(conversationId)
            .map { entities ->
                entities.map { entity ->
                Message(
                    id = entity.id,
                    conversationId = entity.conversationId,
                    senderId = entity.senderId,
                    encryptedContent = entity.encryptedContent,
                    timestamp = entity.timestamp,
                    isSent = entity.isSent,
                    isRead = entity.isRead,
                    messageType = when (entity.messageType) {
                        "IMAGE" -> MessageType.IMAGE
                        "VOICE" -> MessageType.VOICE
                        "FILE" -> MessageType.FILE
                        "SYSTEM" -> MessageType.SYSTEM
                        "CALL" -> MessageType.CALL
                        else -> MessageType.TEXT
                    },
                    mediaUrl = entity.mediaUrl,
                    fileName = entity.fileName,
                    fileMime = entity.fileMime,
                    fileSize = entity.fileSize
                )
                }
            }
    }

    /**
     * Save a message locally (after encryption).
     */
    suspend fun saveMessage(
        conversationId: String,
        senderId: String,
        recipientId: String,
        encryptedContent: String,
        messageType: MessageType = MessageType.TEXT,
        mediaUrl: String? = null,
        fileName: String? = null,
        fileMime: String? = null,
        fileSize: Long = 0
    ): String {
        val messageId = java.util.UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            encryptedContent = encryptedContent,
            timestamp = System.currentTimeMillis(),
            isEncrypted = true,
            isSent = true,
            isRead = false,
            messageType = messageType.name,
            mediaUrl = mediaUrl,
            fileName = fileName,
            fileMime = fileMime,
            fileSize = fileSize
        )
        messageDao.upsertMessage(entity)

        // Update conversation — 显式写入对方显示名，避免后续发送时把接收方已
        // 写入的 name 覆盖为 null（会导致会话列表显示「未知联系人」）
        conversationDao.upsertConversation(
            ConversationEntity(
                id = conversationId,
                participantIds = "$senderId,$recipientId",
                lastMessageId = messageId,
                lastMessagePreview = "[加密消息]",
                updatedAt = System.currentTimeMillis(),
                name = teamDisplayName(recipientId)
            )
        )

        return messageId
    }

    /**
     * 加密并发送一条消息（信封加密）
     * 使用 MessageEncryptor 进行 AES-256-GCM + RSA 信封加密
     * 然后通过 HTTP POST 发送到服务端，由服务端推送到收件人 WebSocket
     */
    suspend fun sendEncryptedMessage(
        plaintext: String,
        conversationId: String,
        recipientId: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 读取 auth token（用于服务端获取收件人真实公钥 & 发送消息）
                val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
                val authToken = prefs.getString("auth_token", "") ?: ""

                // 1. 拉取收件人设备；若存在桌面端设备则走 v2 多 wrap 信封（关闭「移动→桌面」
                //    单向不可达缺口），否则回退单移动端 v1 双信封（兼容旧版纯移动端接收方）。
                val devices = fetchRecipientDevices(recipientId, authToken)
                val encryptedPayload: String? = if (devices != null && devices.desktopDevices.isNotEmpty()) {
                    val recipients = buildV2Recipients(devices)
                    messageEncryptor.encryptMessageV2(plaintext, recipients, localUserId)?.envelope
                } else {
                    val recipientPublicKeyPem = getRecipientPublicKey(recipientId, authToken)
                    if (recipientPublicKeyPem.isNullOrBlank()) {
                        Log.e(TAG, "Recipient public key not found for $recipientId (server may not have it registered)")
                        return@withContext Result.failure(Exception("对方公钥未找到，请让对方重新打开 SecureChat 应用（以注册公钥）"))
                    }
                    messageEncryptor.encryptMessage(plaintext, recipientPublicKeyPem)
                }
                if (encryptedPayload == null) {
                    return@withContext Result.failure(Exception("Encryption failed"))
                }

                // 3. 保存到本地数据库
                val messageId = saveMessage(
                    conversationId = conversationId,
                    senderId = localUserId,
                    recipientId = recipientId,
                    encryptedContent = encryptedPayload
                )

                // 4. 发送 HTTP POST 到服务端 /api/messages/send
                // 服务端会路由消息到收件人的 WebSocket 连接，并校验好友关系
                val code = sendMessageToServer(
                    senderId = localUserId,
                    recipientId = recipientId,
                    conversationId = conversationId,
                    encryptedContent = encryptedPayload,
                    authToken = authToken,
                    messageType = "TEXT"
                )

                if (code !in 200..299) {
                    messageDao.deleteMessage(messageId)
                    val err = if (code == 403) "你们已不是好友，无法通信" else "消息发送失败（HTTP $code）"
                    Log.w(TAG, "Send failed with code $code to $recipientId")
                    return@withContext Result.failure(Exception(err))
                }

                Log.i(TAG, "Message sent: $messageId to $recipientId")
                Result.success(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send encrypted message", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 加密并发送一张图片（端到端信封加密）。
     * 图片先压缩到合理尺寸（长边 1280、JPEG 75%），再字节级信封加密，
     * 最后随消息作为 encryptedContent 发送，messageType=IMAGE。
     */
    suspend fun sendEncryptedImage(
        uri: Uri,
        conversationId: String,
        recipientId: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
                val authToken = prefs.getString("auth_token", "") ?: ""

                // 1. 压缩图片为字节
                val imageBytes = compressImage(uri)
                    ?: return@withContext Result.failure(Exception("图片读取或压缩失败"))

                // 2. 获取收件人设备，决定 v2 统一媒体还是 v1 传统信封
                val fileName = "image_${System.currentTimeMillis()}.jpg"
                val fileMime = "image/jpeg"
                val devices = fetchRecipientDevices(recipientId, authToken)
                val useV2 = devices != null && devices.desktopDevices.isNotEmpty()

                val (encryptedPayload, metaFileId) = if (useV2 && devices != null) {
                    // 统一媒体设计：消息元数据 + 图片字节共用一把会话密钥
                    val sessionKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val msgIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val fileIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val fileCt = messageEncryptor.encryptData(sessionKey, fileIv, imageBytes)
                        ?: return@withContext Result.failure(Exception("图片加密失败"))
                    val fileId = uploadEncryptedFile(
                        Base64.encodeToString(fileCt, Base64.NO_WRAP), authToken
                    ) ?: return@withContext Result.failure(Exception("图片上传失败"))
                    val meta = JSONObject().apply {
                        put("kind", "image")
                        put("fileId", fileId)
                        put("fileIv", Base64.encodeToString(fileIv, Base64.NO_WRAP))
                        put("name", fileName)
                        put("mime", fileMime)
                        put("size", imageBytes.size)
                    }.toString()
                    val recipients = buildV2Recipients(devices)
                    val v2 = messageEncryptor.encryptMessageV2WithKey(
                        meta, recipients, localUserId, sessionKey, msgIv
                    ) ?: return@withContext Result.failure(Exception("图片元数据加密失败"))
                    Pair(v2.envelope as String?, fileId as String?)
                } else {
                    val recipientPublicKeyPem = getRecipientPublicKey(recipientId, authToken)
                    if (recipientPublicKeyPem.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("对方公钥未找到，请让对方重新打开 SecureChat 应用（以注册公钥）"))
                    }
                    val enc = messageEncryptor.encryptBytes(imageBytes, recipientPublicKeyPem)
                        ?: return@withContext Result.failure(Exception("图片加密失败"))
                    Pair(enc as String?, null as String?)
                }
                if (encryptedPayload == null) {
                    return@withContext Result.failure(Exception("图片加密失败"))
                }

                // 3. 存本地
                val messageId = saveMessage(
                    conversationId = conversationId,
                    senderId = localUserId,
                    recipientId = recipientId,
                    encryptedContent = encryptedPayload,
                    messageType = MessageType.IMAGE,
                    mediaUrl = metaFileId,
                    fileName = fileName,
                    fileMime = fileMime,
                    fileSize = imageBytes.size.toLong()
                )

                // 4. 发送
                val code = sendMessageToServer(
                    senderId = localUserId,
                    recipientId = recipientId,
                    conversationId = conversationId,
                    encryptedContent = encryptedPayload,
                    authToken = authToken,
                    messageType = "IMAGE",
                    fileName = fileName,
                    fileMime = fileMime,
                    fileSize = imageBytes.size.toLong(),
                    fileId = metaFileId
                )
                if (code !in 200..299) {
                    messageDao.deleteMessage(messageId)
                    val err = if (code == 403) "你们已不是好友，无法通信" else "图片发送失败（HTTP $code）"
                    Log.w(TAG, "Send image failed with code $code to $recipientId")
                    return@withContext Result.failure(Exception(err))
                }

                Result.success(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send image", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 加密并发送一个文件（端到端信封加密）。
     * 流程：读取文件字节 -> AES-GCM 信封加密 -> 上传密文到 /api/files/upload 得到 fileId
     *       -> 把「fileId/文件名/类型/大小」作为元数据 JSON 再次信封加密 -> 发送 FILE 消息。
     * 文件内容全程以密文存在于服务器，仅收发双方密钥可解密。
     */
    suspend fun sendEncryptedFile(
        uri: Uri,
        conversationId: String,
        recipientId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
                val authToken = prefs.getString("auth_token", "") ?: ""

                // 1. 读取文件字节 + 元数据
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("无法读取文件"))
                val fileBytes = input.use { it.readBytes() }
                val fileName = getFileNameFromUri(uri) ?: "file_${System.currentTimeMillis()}"
                val fileMime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val fileSize = fileBytes.size.toLong()
                onProgress(0.1f)

                // 体积保护：单文件上限 50MB（加密后会更大），超出直接拒绝避免 OOM / 超时
                val MAX = 50 * 1024 * 1024
                if (fileSize > MAX) {
                    return@withContext Result.failure(Exception("文件过大（约 ${fileSize / 1024 / 1024}MB），上限 50MB"))
                }

                // 2. 获取收件人设备，决定 v2 统一媒体还是 v1 传统信封
                val devices = fetchRecipientDevices(recipientId, authToken)
                val useV2 = devices != null && devices.desktopDevices.isNotEmpty()

                val (encryptedMeta, metaFileId) = if (useV2 && devices != null) {
                    // 统一媒体设计：文件字节与元数据共用一把会话密钥
                    val sessionKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val msgIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val fileIv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                    val fileCt = messageEncryptor.encryptData(sessionKey, fileIv, fileBytes)
                        ?: return@withContext Result.failure(Exception("文件加密失败"))
                    onProgress(0.2f)
                    val fileId = uploadEncryptedFile(
                        Base64.encodeToString(fileCt, Base64.NO_WRAP), authToken
                    ) { frac -> onProgress(0.2f + 0.75f * frac) }
                        ?: return@withContext Result.failure(Exception("文件上传失败"))
                    val meta = JSONObject().apply {
                        put("kind", "file")
                        put("fileId", fileId)
                        put("fileIv", Base64.encodeToString(fileIv, Base64.NO_WRAP))
                        put("name", fileName)
                        put("mime", fileMime)
                        put("size", fileSize)
                    }.toString()
                    val recipients = buildV2Recipients(devices)
                    val v2 = messageEncryptor.encryptMessageV2WithKey(
                        meta, recipients, localUserId, sessionKey, msgIv
                    ) ?: return@withContext Result.failure(Exception("元数据加密失败"))
                    Pair(v2.envelope as String?, fileId as String?)
                } else {
                    val recipientPublicKeyPem = getRecipientPublicKey(recipientId, authToken)
                    if (recipientPublicKeyPem.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("对方公钥未找到，请让对方重新打开 SecureChat 应用（以注册公钥）"))
                    }
                    val encryptedFile = messageEncryptor.encryptBytes(fileBytes, recipientPublicKeyPem)
                        ?: return@withContext Result.failure(Exception("文件加密失败"))
                    onProgress(0.2f)
                    val fileId = uploadEncryptedFile(encryptedFile, authToken) { frac ->
                        onProgress(0.2f + 0.75f * frac)
                    } ?: return@withContext Result.failure(Exception("文件上传失败"))
                    val meta = JSONObject().apply {
                        put("fileId", fileId)
                        put("name", fileName)
                        put("mime", fileMime)
                        put("size", fileSize)
                    }.toString()
                    val encMeta = messageEncryptor.encryptMessage(meta, recipientPublicKeyPem)
                        ?: return@withContext Result.failure(Exception("元数据加密失败"))
                    Pair(encMeta as String?, fileId as String?)
                }
                if (encryptedMeta == null) {
                    return@withContext Result.failure(Exception("文件加密失败"))
                }

                // 3. 存本地（明文存文件名/类型/大小便于列表与离线显示；mediaUrl 存 fileId）
                val messageId = saveMessage(
                    conversationId = conversationId,
                    senderId = localUserId,
                    recipientId = recipientId,
                    encryptedContent = encryptedMeta,
                    messageType = MessageType.FILE,
                    mediaUrl = metaFileId,
                    fileName = fileName,
                    fileMime = fileMime,
                    fileSize = fileSize
                )

                // 4. 发送 FILE 消息（带上文件元数据，供服务器转发）
                val code = sendMessageToServer(
                    senderId = localUserId,
                    recipientId = recipientId,
                    conversationId = conversationId,
                    encryptedContent = encryptedMeta,
                    authToken = authToken,
                    messageType = "FILE",
                    fileName = fileName,
                    fileMime = fileMime,
                    fileSize = fileSize,
                    fileId = metaFileId
                )
                if (code !in 200..299) {
                    messageDao.deleteMessage(messageId)
                    val err = if (code == 403) "你们已不是好友，无法通信" else "文件发送失败（HTTP $code）"
                    Log.w(TAG, "Send file failed with code $code to $recipientId")
                    return@withContext Result.failure(Exception(err))
                }

                onProgress(1.0f)
                Result.success(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 上传加密后的文件密文（纯文本 v1:...）到服务器，返回 fileId。
     * @param onProgress 上报上传进度（0f..1f），供 UI 显示发送进度条。
     */
    private suspend fun uploadEncryptedFile(
        encryptedPayload: String,
        authToken: String,
        onProgress: (Float) -> Unit = {}
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val body = ProgressRequestBody(
                    "text/plain".toMediaType(),
                    encryptedPayload,
                    onProgress
                )
                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/files/upload"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .addHeader("Content-Type", "text/plain")
                    .post(body)
                    .build()
                val response = http.newCall(request).execute()
                val bodyText = response.body?.string()
                response.close()
                if (!response.isSuccessful || bodyText.isNullOrBlank()) {
                    Log.w(TAG, "Upload file failed HTTP ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(bodyText)
                if (json.optBoolean("success", false)) json.optString("fileId", "").takeIf { it.isNotBlank() } else null
            } catch (e: Exception) {
                Log.e(TAG, "uploadEncryptedFile failed", e)
                null
            }
        }
    }

    /**
     * 带进度上报的 RequestBody：上传文件密文时按已发送字节比例实时回调 onProgress（0f..1f）。
     * 分块写入，避免一次性 load 大文件到内存；每写完一块即回调，保证进度条平滑刷新。
     */
    private class ProgressRequestBody(
        private val mediaType: okhttp3.MediaType?,
        private val content: String,
        private val onProgress: (Float) -> Unit
    ) : okhttp3.RequestBody() {
        private val bytes = content.toByteArray(Charsets.UTF_8)
        override fun contentType(): okhttp3.MediaType? = mediaType
        override fun contentLength(): Long = bytes.size.toLong()
        override fun writeTo(sink: okio.BufferedSink) {
            val total = bytes.size.toLong()
            if (total == 0L) return
            var offset = 0
            val chunk = 8192
            while (offset < bytes.size) {
                val len = if (chunk < bytes.size - offset) chunk else bytes.size - offset
                sink.write(bytes, offset, len)
                offset += len
                onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    /**
     * 从 Uri 解析文件名（DocumentsContract / MediaStore / 最后路径段兜底）。
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            if (uri.scheme == "content") {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                }
            } else {
                uri.lastPathSegment
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将选中图片压缩为合理体积的 JPEG 字节（长边 <= 1280，质量 75%）。
     */
    private fun compressImage(uri: Uri): ByteArray? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            if (original == null) return null

            val maxDim = 1280
            val scaled = if (original.width > maxDim || original.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(original.width, original.height)
                val w = (original.width * ratio).toInt()
                val h = (original.height * ratio).toInt()
                Bitmap.createScaledBitmap(original, w, h, true)
            } else {
                original
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            if (scaled != original) scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "compressImage failed", e)
            null
        }
    }

    /**
     * Send an encrypted message to the server via HTTP POST.
     * Server will route it to recipient's WebSocket connection.
     */
    private suspend fun sendMessageToServer(
        senderId: String,
        recipientId: String,
        conversationId: String,
        encryptedContent: String,
        authToken: String,
        messageType: String = "TEXT",
        fileName: String? = null,
        fileMime: String? = null,
        fileSize: Long? = null,
        fileId: String? = null
    ): Int {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("senderId", senderId)
                    put("recipientId", recipientId)
                    put("conversationId", conversationId)
                    put("encryptedContent", encryptedContent)
                    put("senderName", selfDisplayName())
                    put("messageType", messageType)
                    if (fileId != null) put("fileId", fileId)
                    if (fileName != null) put("fileName", fileName)
                    if (fileMime != null) put("fileMime", fileMime)
                    if (fileSize != null) put("fileSize", fileSize)
                }

                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/messages/send"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = http.newCall(request).execute()
                val code = response.code
                Log.i(TAG, "Send message HTTP response: $code (success=${response.isSuccessful})")
                response.close()
                code
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message via HTTP", e)
                -1
            }
        }
    }

    /**
     * 解密消息 payload（AES-GCM 信封解密）
     */
    fun decryptPayload(encryptedPayload: String): String? {
        return messageEncryptor.decryptMessage(encryptedPayload)
    }

    /**
     * 历史共享（P1）：配对批准后，把本地已解密的历史用目标桌面设备公钥重加密并上传到服务端。
     * 服务端只搬密文，桌面端拉取后用自身私钥解密即可看到完整历史。
     *
     * 实现：遍历本机全部会话的本地消息 → 用本机私钥解密得到明文（文本或统一媒体元数据 JSON）
     *      → 用桌面端公钥重新做一次 v1 信封加密 → 批量 POST 到 /api/history/share。
     * 说明：
     *  - 文本消息：明文即消息文本，重加密后桌面可直接解密显示。
     *  - 统一媒体（IMAGE/FILE/VOICE 且带 mediaUrl=fileId）：明文为元数据 JSON（含 fileId/fileIv），
     *    重加密后桌面用自身私钥解出会话密钥，再下载 fileId 密文用会话密钥解密。
     *  - 旧版 v1 媒体（二进制直接封在信封里、无 fileId）：解密为二进制经 UTF-8 字符串会损坏，
     *    无法可靠重加密，故跳过（这类消息在桌面端将不会出现在历史中）。
     *
     * @return 成功上传的历史条目数（Result）
     */
    suspend fun shareAllHistoryWithDesktop(targetDeviceId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            val authToken = prefs.getString("auth_token", "") ?: ""
            if (authToken.isBlank()) return@withContext Result.failure(Exception("未登录，无法共享历史"))

            // 1) 拉取目标桌面设备公钥
            val desktopPubKey = fetchDevicePublicKey(targetDeviceId, authToken)
                ?: return@withContext Result.failure(Exception("找不到目标桌面设备公钥"))

            // 2) 遍历本机全部会话与消息
            val conversations = getConversations().first()
            val entries = mutableListOf<org.json.JSONObject>()
            for (conv in conversations) {
                val participants = conv.id.split("_")
                val peerId = participants.firstOrNull { it != localUserId } ?: localUserId
                val messages = getConversationMessages(conv.id).first()
                for (msg in messages) {
                    // 旧版 v1 二进制媒体：无 fileId，跳过
                    val isUnifiedMedia = msg.messageType != MessageType.TEXT &&
                        msg.messageType != MessageType.SYSTEM &&
                        msg.messageType != MessageType.CALL &&
                        !msg.mediaUrl.isNullOrBlank()
                    if (!isUnifiedMedia && (msg.messageType == MessageType.IMAGE ||
                                msg.messageType == MessageType.FILE ||
                                msg.messageType == MessageType.VOICE)
                    ) {
                        // 旧版 v1 媒体（无 fileId），跳过
                        continue
                    }
                    val plaintext = decryptPayload(msg.encryptedContent) ?: continue
                    val reEncrypted = messageEncryptor.encryptMessage(plaintext, desktopPubKey) ?: continue
                    val entry = org.json.JSONObject().apply {
                        put("id", msg.id)
                        put("conversationId", msg.conversationId)
                        put("peerId", peerId)
                        put("senderId", msg.senderId)
                        put("encryptedContent", reEncrypted)
                        put("messageType", msg.messageType.name)
                        put("timestamp", msg.timestamp)
                        if (!msg.mediaUrl.isNullOrBlank()) put("fileId", msg.mediaUrl)
                        if (!msg.fileName.isNullOrBlank()) put("fileName", msg.fileName)
                        if (!msg.fileMime.isNullOrBlank()) put("fileMime", msg.fileMime)
                        if (msg.fileSize > 0) put("fileSize", msg.fileSize)
                    }
                    entries.add(entry)
                }
            }

            if (entries.isEmpty()) {
                // 仍然调一次接口，确保服务端该设备历史被清空（避免拉到旧的）
                postHistoryBatch(targetDeviceId, emptyList(), authToken)
                return@withContext Result.success(0)
            }

            // 3) 分批上传（每批 200 条，避免单次请求体过大）
            var uploaded = 0
            entries.chunked(200).forEach { batch ->
                val n = postHistoryBatch(targetDeviceId, batch, authToken)
                if (n >= 0) uploaded += n
            }
            Log.i(TAG, "shareAllHistoryWithDesktop: uploaded $uploaded entries to device $targetDeviceId")
            Result.success(uploaded)
        } catch (e: Exception) {
            Log.e(TAG, "shareAllHistoryWithDesktop failed", e)
            Result.failure(e)
        }
    }

    /**
     * 从 GET /api/users/:userId/devices 取本账号下指定 deviceId 的桌面设备公钥。
     */
    private suspend fun fetchDevicePublicKey(deviceId: String, authToken: String): String? {
        return withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/users/$localUserId/devices"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                response = http.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val json = org.json.JSONObject(response.body?.string() ?: "{}")
                val arr = json.optJSONArray("devices") ?: return@withContext null
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    if (d.optString("type", "") == "desktop" && d.optString("deviceId", "") == deviceId) {
                        val pk = d.optString("pubKey", "")
                        if (pk.isNotBlank()) return@withContext pk
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "fetchDevicePublicKey failed", e)
                null
            } finally {
                response?.close()
            }
        }
    }

    /**
     * 上传一批历史条目到 /api/history/share。返回该批被接受的条目数（-1 表示失败）。
     */
    private suspend fun postHistoryBatch(
        deviceId: String,
        batch: List<org.json.JSONObject>,
        authToken: String
    ): Int {
        return withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val body = org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("entries", org.json.JSONArray(batch))
                }
                val req = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/history/share"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                response = http.newCall(req).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "postHistoryBatch HTTP ${response.code}")
                    return@withContext -1
                }
                val json = org.json.JSONObject(response.body?.string() ?: "{}")
                json.optInt("accepted", batch.size)
            } catch (e: Exception) {
                Log.e(TAG, "postHistoryBatch failed", e)
                -1
            } finally {
                response?.close()
            }
        }
    }

    /**
     * Fetch unread messages from server (for sync).
     */
    suspend fun fetchUnreadFromServer(authToken: String): Result<List<Message>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.fetchUnreadMessages("Bearer $authToken")
                if (response.isSuccessful && response.body() != null) {
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("HTTP error"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Mark message as read.
     */
    suspend fun markMessageRead(messageId: String, authToken: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                apiService.markAsRead("Bearer $authToken", messageId)
                messageDao.upsertMessage(
                    MessageEntity(
                        id = messageId,
                        conversationId = "",
                        senderId = "",
                        recipientId = localUserId,
                        encryptedContent = "",
                        timestamp = System.currentTimeMillis(),
                        isRead = true
                    )
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 删除单条本地消息（端到端加密，仅本地 DB；无服务端删除接口）。
     */
    suspend fun deleteMessage(messageId: String) {
        withContext(Dispatchers.IO) {
            try {
                messageDao.deleteMessage(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "deleteMessage failed", e)
            }
        }
    }

    /**
     * 批量删除本地消息。
     */
    suspend fun deleteMessages(ids: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                ids.forEach { messageDao.deleteMessage(it) }
            } catch (e: Exception) {
                Log.e(TAG, "deleteMessages failed", e)
            }
        }
    }

    /**
     * 打开会话时清零未读数（已读）。
     */
    suspend fun markConversationRead(conversationId: String) {
        withContext(Dispatchers.IO) {
            try {
                conversationDao.clearUnread(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "clearUnread failed", e)
            }
        }
    }

    /**
     * 保存一条通话记录到本地（messageType=CALL，明文存储通话摘要，非聊天内容）。
     * 仅本地留痕，不发送服务器。
     */
    suspend fun saveCallLog(conversationId: String, peerId: String, text: String, isOutgoing: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val messageId = java.util.UUID.randomUUID().toString()
                messageDao.upsertMessage(
                    MessageEntity(
                        id = messageId,
                        conversationId = conversationId,
                        senderId = if (isOutgoing) localUserId else peerId,
                        recipientId = if (isOutgoing) peerId else localUserId,
                        encryptedContent = text,
                        timestamp = System.currentTimeMillis(),
                        isEncrypted = false,
                        isSent = true,
                        isRead = true,
                        messageType = "CALL"
                    )
                )
                conversationDao.upsertConversation(
                    ConversationEntity(
                        id = conversationId,
                        participantIds = "$localUserId,$peerId",
                        lastMessageId = messageId,
                        lastMessagePreview = text,
                        unreadCount = 0,
                        updatedAt = System.currentTimeMillis(),
                        name = teamDisplayName(peerId)
                    )
                )
                Log.i(TAG, "Call log saved: $text (conv=$conversationId)")
            } catch (e: Exception) {
                Log.e(TAG, "saveCallLog failed", e)
            }
        }
    }

    /**
     * 发送方当前显示名：优先本地昵称（修改昵称后即时生效），否则走团队默认名。
     */
    private fun selfDisplayName(): String {
        val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getString("auth_display_name", null)
        val selfId = prefs.getString("auth_user_id", "") ?: ""
        return if (!stored.isNullOrBlank()) stored else teamDisplayName(selfId)
    }
}
