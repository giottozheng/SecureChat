package com.securechat.app.network.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.securechat.app.ServiceLocator
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.ConversationEntity
import com.securechat.app.data.local.MessageDao
import com.securechat.app.data.local.MessageEntity
import com.securechat.app.util.remoteDisplayNameOverrides
import com.securechat.app.util.teamDisplayName
import com.securechat.app.util.ActiveConversationTracker
import com.securechat.app.util.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 后台轮询 Worker —— 当 WebSocket 前台 Service 不可用（app 已关闭 / 被杀）时，
 * 由 AlarmManager 周期性唤醒本 Worker，向服务端拉取离线消息并弹出系统通知。
 *
 * 设计要点：
 * - 不依赖 Hilt 注入（Application 未配置 Hilt WorkerFactory，注入字段会为 null），
 *   改为从 ServiceLocator 读取单例依赖，用 OkHttp 直连轮询接口。
 * - 服务端 /api/messages/poll 会消费（删除）已拉取的离线消息，避免 app 重连时重复投递；
 *   同时以消息 id 在本地库去重，绝对不会出现重复通知。
 * - 拉到新消息即解密预览并弹通知，实现「关掉 app 也能收到推送」。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        if (!ServiceLocator.isInitialized()) {
            // Application 可能尚未完成初始化（冷启动早期），稍后重试
            Log.w(TAG, "ServiceLocator 未就绪，稍后重试")
            return Result.retry()
        }

        val prefs = applicationContext.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("auth_token", "") ?: ""
        val userId = prefs.getString("auth_user_id", "") ?: ""
        if (token.isBlank() || userId.isBlank()) {
            Log.w(TAG, "无登录态，跳过轮询")
            return Result.success()
        }

        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("userId", userId)
                    put("since", 0)
                }.toString()

                val request = Request.Builder()
                    .url(ServerConfig.getUrl(applicationContext, "/api/messages/poll"))
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "轮询失败: ${response.code}")
                    response.close()
                    return@withContext Result.retry()
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val arr: JSONArray = json.optJSONArray("messages") ?: JSONArray()

                val messageDao: MessageDao = ServiceLocator.messageDao
                val conversationDao: ConversationDao = ServiceLocator.conversationDao
                val encryptor = ServiceLocator.messageEncryptor

                var newCount = 0
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val msgId = m.optString("id", UUID.randomUUID().toString())
                    val senderId = m.optString("senderId", "")
                    val conversationId = m.optString("conversationId", "")
                    val encrypted = m.optString("encryptedPayload", m.optString("encryptedContent", ""))
                    val timestamp = m.optLong("timestamp", System.currentTimeMillis())
                    val senderName = m.optString("senderName", "")

                    // 去重：本地已存在则跳过，避免重复通知
                    if (messageDao.getMessageById(msgId) != null) continue

                    val decrypted = encryptor.decryptMessage(encrypted) ?: "[无法解密的消息]"

                    messageDao.upsertMessage(
                        MessageEntity(
                            id = msgId,
                            conversationId = conversationId,
                            senderId = senderId,
                            recipientId = userId,
                            encryptedContent = encrypted,
                            timestamp = timestamp,
                            isEncrypted = true,
                            isSent = false,
                            isRead = false,
                            messageType = m.optString("messageType", "TEXT"),
                            mediaUrl = m.optString("fileId", "").takeIf { it.isNotBlank() },
                            fileName = m.optString("fileName", "").takeIf { it.isNotBlank() },
                            fileMime = m.optString("fileMime", "").takeIf { it.isNotBlank() },
                            fileSize = m.optLong("fileSize", 0L)
                        )
                    )

                    val existing = conversationDao.getConversationSync(conversationId)
                    val newUnread = (existing?.unreadCount ?: 0) + 1
                    conversationDao.upsertConversation(
                        ConversationEntity(
                            id = conversationId,
                            participantIds = "$senderId,$userId",
                            lastMessageId = msgId,
                            lastMessagePreview = "[加密消息]",
                            unreadCount = newUnread,
                            updatedAt = timestamp,
                            name = if (!senderName.isBlank()) senderName else teamDisplayName(senderId)
                        )
                    )

                    if (!senderName.isBlank()) {
                        remoteDisplayNameOverrides = remoteDisplayNameOverrides.toMutableMap().apply {
                            put(senderId, senderName)
                        }
                    }

                    // 仅当 App 在前台且用户正停留在该会话详情页时，普通消息不弹通知
                    // （语音/视频通话始终提醒，不受此影响）。
                    if (!ActiveConversationTracker.shouldSuppressNotification(conversationId)) {
                        PushNotificationHelper.show(
                            applicationContext,
                            "新消息",
                            decrypted.take(100) + if (decrypted.length > 100) "..." else "",
                            conversationId
                        )
                    }
                    newCount++
                }

                Log.i(TAG, "轮询完成，新消息 $newCount 条")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "轮询异常", e)
                Result.retry()
            }
        }
    }
}
