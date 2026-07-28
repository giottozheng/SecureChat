package com.securechat.app.network.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.ServiceCompat
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securechat.app.ServiceLocator
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.ConversationEntity
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.FriendRequestEntity
import com.securechat.app.data.local.MessageDao
import com.securechat.app.data.local.MessageEntity
import com.securechat.app.ui.activity.MainActivity
import com.securechat.app.util.remoteDisplayNameOverrides
import com.securechat.app.util.teamDisplayName
import com.securechat.app.util.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 推送前台服务 — WebSocket 长连接 + 心跳保活 + 消息处理
 *
 * 注意：本 Service 不使用 @AndroidEntryPoint 注入。原因：Hilt 在 Service 上的构造注入
 * 在运行时会导致 "has no zero argument constructor" 崩溃（系统直接实例化原始 Service 类，
 * 未用 Hilt 生成的子类替代）。
 * 改为从 ServiceLocator（由 @HiltAndroidApp 的 Application 填充）读取单例依赖。
 * 本类是普通 Service，拥有 Kotlin 隐式零参构造，可被系统正常实例化。
 */
class PushConnectionService : Service() {

    private lateinit var messageEncryptor: MessageEncryptor
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var friendRequestDao: FriendRequestDao

    companion object {
        private const val TAG = "PushConnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_CONN_ID = "securechat_conn"
        private const val MAX_RECONNECTION_ATTEMPTS = 20

        // 持有活实例，便于其他组件（如 ViewModel）经活连接发送已读回执
        @Volatile
        private var liveInstance: PushConnectionService? = null

        /**
         * 服务存活标记，供看门狗（PollingReceiver）判断是否需重启。
         * 进程被系统/OEM 强杀后，新进程此值复位为 false，看门狗据此重新拉起服务。
         */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * 经当前活 WebSocket 发送「已读回执」：通知消息发送方其发出的消息已被本端读取。
         * @param conversationId 会话 ID
         * @param readerId 读取方（本端）userId
         * @param senderId 消息发送方（对方）userId
         */
        fun sendReadReceipt(conversationId: String, readerId: String, senderId: String) {
            val inst = liveInstance ?: run {
                Log.w(TAG, "sendReadReceipt: service 未存活，跳过")
                return
            }
            val ws = inst.webSocket ?: run {
                Log.w(TAG, "sendReadReceipt: websocket 未连接，跳过")
                return
            }
            try {
                val json = JSONObject().apply {
                    put("type", "read_receipt")
                    put("conversationId", conversationId)
                    put("readerId", readerId)
                    put("senderId", senderId)
                }.toString()
                val sent = ws.send(json)
                Log.d(TAG, "sendReadReceipt -> $senderId (conv=$conversationId) sent=$sent")
            } catch (e: Exception) {
                Log.e(TAG, "sendReadReceipt failed", e)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, PushConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PushConnectionService::class.java).apply {
                action = ACTION_STOP
            })
        }

        /**
         * 经活 WebSocket 发送通话信令（call_signal）。供 CallManager 调用。
         */
        fun sendCallSignal(json: String) {
            val inst = liveInstance ?: run {
                Log.w(TAG, "sendCallSignal: service 未存活")
                return
            }
            val ws = inst.webSocket ?: run {
                Log.w(TAG, "sendCallSignal: websocket 未连接")
                return
            }
            try {
                ws.send(json)
                Log.d(TAG, "sendCallSignal sent: ${json.take(80)}")
            } catch (e: Exception) {
                Log.e(TAG, "sendCallSignal failed", e)
            }
        }

        private const val ACTION_STOP = "com.securechat.action.STOP_PUSH"
    }

    private var webSocket: WebSocket? = null
    private var reconnectionAttempts = 0
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var connecting: Boolean = false
    private var stopping: Boolean = false

    private lateinit var client: OkHttpClient
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        isAlive = true
        Log.i(TAG, "PushConnectionService onCreate")

        // 从 ServiceLocator 获取单例依赖（由 Application 经 Hilt 注入后填充）
        if (!ServiceLocator.isInitialized()) {
            Log.e(TAG, "ServiceLocator 未初始化，无法启动推送服务")
            stopSelf()
            return
        }
        messageEncryptor = ServiceLocator.messageEncryptor
        messageDao = ServiceLocator.messageDao
        conversationDao = ServiceLocator.conversationDao
        friendRequestDao = ServiceLocator.friendRequestDao

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping foreground notification", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service", e)
        }
        initializeOkHttpClient()
        // 注意：不要在此处主动 connect()。connect() 统一由 onStartCommand 在
        // webSocket==null 时触发，避免与 onStartCommand 重复连接导致「3 秒重连风暴」。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received STOP action")
                stopping = true
                isAlive = false
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (webSocket == null) {
                    connect()
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopping = true
        isAlive = false
        if (liveInstance === this) liveInstance = null
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service destroyed")
        client.dispatcher.cancelAll()
        Log.i(TAG, "PushConnectionService destroyed")
    }

    /**
     * 任务被用户从最近任务列表移除时触发。
     *
     * Android 14(API 34)+ 对 dataSync 等前台服务引入「任务移除后超时停止」硬限制：
     * 若服务未在超时窗口内停止，框架直接抛 ForegroundServiceDidNotStopInTimeException
     * （主线程 FATAL，杀进程）。本服务为 START_STICKY 长驻前台服务，划掉后仍存活，
     * 若不主动停止必然触发该崩溃。故在回调内（API 34+）主动 stopForeground + stopSelf，
     * 使超时计时器无对象可抛。服务停止后由 PollingReceiver 看门狗（≤30s）检测 isAlive=false
     * 并从后台重新拉起长连接（dataSync 后台启动豁免）；看门狗同时触发 SyncWorker 做 HTTP
     * 兜底同步，停机窗口不会丢消息。
     *
     * 仅 API 34+ 需要此停服动作；旧版本无该超时限制且需保持长连，故不改其行为。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.w(TAG, "onTaskRemoved stopForeground 失败", e)
            }
            stopping = true
            isAlive = false
            stopSelf()
        }
        // 兜底：确保看门狗闹钟已武装（ColorOS 可能在上一次强杀时取消了闹钟），
        // 服务停止后由其在下一次闹钟重新拉起长连接。
        try {
            PollingReceiver.schedulePolling(this, 30_000L)
        } catch (e: Exception) {
            Log.w(TAG, "onTaskRemoved 武装看门狗失败", e)
        }
    }

    private fun initializeOkHttpClient() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private fun connect() {
        if (connecting) {
            Log.d(TAG, "connect() ignored, already connecting")
            return
        }
        connecting = true
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        val token = getAuthToken()
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "No auth token, retrying in 5s...")
            scheduleReconnect(delayMs = 5000)
            return
        }

        val url = ServerConfig.getWsPushUrl(this, token, getChatDeviceId())

        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                    connecting = false
                    reconnectionAttempts = 0
                    startHeartbeat()
                    webSocket.send(JSONObject().apply {
                        put("type", "connected_confirm")
                        put("deviceId", getChatDeviceId())
                        put("userId", getAuthUserId() ?: "")
                    }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Received WS message: ${text.take(200)}")
                    handleWsMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closed: $reason ($code)")
                    connecting = false
                    heartbeatJob?.cancel()
                    // 仅当「非主动重连(connect() 关闭旧 socket 时 reason=Reconnecting)」
                    // 且「非服务正在停止」时才安排重连，避免自我循环的 3 秒重连风暴。
                    if (!stopping && reason != "Reconnecting") {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    connecting = false
                    heartbeatJob?.cancel()
                    if (!stopping) {
                        scheduleReconnect()
                    }
                }
            }
        )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(25000)
                webSocket?.send(JSONObject().apply { put("type", "ping") }.toString())
            }
        }
    }

    private fun handleWsMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "message" -> {
                    val data = json.getJSONObject("data")
                    val senderId = data.optString("senderId", "")
                    val conversationId = data.optString("conversationId", "")
                    val encryptedContent = data.optString("encryptedContent", "")
                    val timestamp = data.optLong("timestamp", System.currentTimeMillis())
                    val senderName = data.optString("senderName", "")
                    val messageType = data.optString("messageType", "TEXT")
                    handleRealtimeMessage(
                        senderId, conversationId, encryptedContent, timestamp,
                        if (senderName.isBlank()) null else senderName,
                        messageType,
                        fileId = data.optString("fileId", "").takeIf { it.isNotBlank() },
                        fileName = data.optString("fileName", "").takeIf { it.isNotBlank() },
                        fileMime = data.optString("fileMime", "").takeIf { it.isNotBlank() },
                        fileSize = data.optLong("fileSize", 0L)
                    )
                }
                "offline_sync" -> {
                    val data = json.getJSONObject("data")
                    val arr = data.optJSONArray("messages") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val sId = m.optString("senderId", "")
                        val cId = m.optString("conversationId", "")
                        val enc = m.optString("encryptedPayload", m.optString("encryptedContent", ""))
                        val ts = m.optLong("timestamp", System.currentTimeMillis())
                        val sName = m.optString("senderName", "")
                        val mt = m.optString("messageType", "TEXT")
                        handleRealtimeMessage(
                            sId, cId, enc, ts,
                            if (sName.isBlank()) null else sName,
                            mt,
                            fileId = m.optString("fileId", "").takeIf { it.isNotBlank() },
                            fileName = m.optString("fileName", "").takeIf { it.isNotBlank() },
                            fileMime = m.optString("fileMime", "").takeIf { it.isNotBlank() },
                            fileSize = m.optLong("fileSize", 0L)
                        )
                    }
                    Log.i(TAG, "Processed offline_sync with ${arr.length()} messages")
                }
                "connected" -> Log.i(TAG, "Connection confirmed")
                "ping" -> webSocket?.send(JSONObject().apply { put("type", "pong") }.toString())
                "read_receipt" -> {
                    val data = json.getJSONObject("data")
                    val conversationId = data.optString("conversationId", "")
                    val myId = getAuthUserId() ?: return
                    if (conversationId.isBlank()) return
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // 对方已读本端发出的消息：把自己的（senderId=myId）消息标记为已读
                            messageDao.markOwnMessagesRead(conversationId, myId)
                            Log.i(TAG, "Read receipt applied: conv=$conversationId own messages marked read")
                        } catch (e: Exception) {
                            Log.e(TAG, "markOwnMessagesRead failed", e)
                        }
                    }
                }
                "friend_request" -> {
                    val data = json.getJSONObject("data")
                    val fromId = data.optString("fromId", "")
                    val fromName = data.optString("fromName", fromId)
                    val ts = data.optLong("timestamp", System.currentTimeMillis())
                    handleFriendRequest(fromId, fromName, ts)
                }
                "call_signal" -> {
                    ServiceLocator.callManager?.onSignal(text)
                }
                else -> Log.w(TAG, "Unknown WS message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WS message", e)
        }
    }

    private fun handleRealtimeMessage(
        senderId: String,
        conversationId: String,
        encryptedContent: String,
        timestamp: Long,
        senderName: String? = null,
        messageType: String? = null,
        fileId: String? = null,
        fileName: String? = null,
        fileMime: String? = null,
        fileSize: Long = 0
    ) {
        val decryptedContent = messageEncryptor.decryptMessage(encryptedContent)
            ?: "[无法解密的消息]"

        val messageId = java.util.UUID.randomUUID().toString()
        val recipientId = getAuthUserId() ?: "unknown"

        // 发送方实时昵称：优先用消息携带的 senderName（修改昵称后即时生效），否则本地解析
        val resolvedName = if (!senderName.isNullOrBlank()) senderName else teamDisplayName(senderId)
        if (!senderName.isNullOrBlank()) {
            // 更新其他成员昵称覆盖表，使「修改昵称」对本地会话列表/聊天标题即时生效
            remoteDisplayNameOverrides = remoteDisplayNameOverrides.toMutableMap().apply {
                put(senderId, senderName)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                messageDao.upsertMessage(MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = senderId,
                    recipientId = recipientId,
                    encryptedContent = encryptedContent,
                    timestamp = timestamp,
                    isEncrypted = true,
                    isSent = false,
                    isRead = false,
                    messageType = messageType ?: "TEXT",
                    mediaUrl = fileId,
                    fileName = fileName,
                    fileMime = fileMime,
                    fileSize = fileSize
                ))
                // 累加未读数（避免一直停在 1）：读取当前值 +1 再写回
                val existing = conversationDao.getConversationSync(conversationId)
                val newUnread = (existing?.unreadCount ?: 0) + 1
                conversationDao.upsertConversation(ConversationEntity(
                    id = conversationId,
                    participantIds = "$senderId,$recipientId",
                    lastMessageId = messageId,
                    lastMessagePreview = "[加密消息]",
                    unreadCount = newUnread,
                    updatedAt = timestamp,
                    name = resolvedName
                ))
                // 对方回复即视为已读：把本会话中「自己发出的消息」标记 isRead=true
                messageDao.markOwnMessagesRead(conversationId, recipientId)
                Log.i(TAG, "Message saved: $messageId from $senderId (unread=$newUnread)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save message", e)
            }
        }

        PushNotificationHelper.show(
            this,
            "新消息",
            decryptedContent.take(100) + if (decryptedContent.length > 100) "..." else "",
            conversationId
        )
    }

    /**
     * 处理服务端推送的好友请求：落库 + 通知。ContactsScreen 通过本地库实时展示。
     */
    private fun handleFriendRequest(fromId: String, fromName: String, timestamp: Long) {
        val toId = getAuthUserId() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                friendRequestDao.upsertRequest(
                    FriendRequestEntity(
                        fromId = fromId,
                        fromName = fromName,
                        toId = toId,
                        timestamp = timestamp
                    )
                )
                Log.i(TAG, "Friend request saved from $fromId ($fromName)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save friend request", e)
            }
        }
        PushNotificationHelper.show(
            this,
            "新的好友请求",
            "$fromName 请求添加你为联系人",
            "friend_request_$fromId"
        )
    }

    private fun scheduleReconnect(delayMs: Long = 3000) {
        reconnectJob?.cancel()
        if (reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
            Log.e(TAG, "Max reconnection attempts reached")
            return
        }
        val actualDelay = if (delayMs == 0L) {
            minOf(1000L * (1L shl reconnectionAttempts), 60000L)
        } else {
            delayMs
        }
        reconnectionAttempts++
        Log.d(TAG, "Scheduling reconnect in ${actualDelay}ms (attempt $reconnectionAttempts)")
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(actualDelay)
            connect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 独立的「常驻连接」低优先级通道：静音、不震动、不亮屏，
            // 仅用于表明后台加密连接存活，避免打扰用户。
            val connChannel = NotificationChannel(
                CHANNEL_CONN_ID, "SecureChat 后台连接", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持加密消息连接的常驻后台服务"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(connChannel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_CONN_ID)
            .setContentTitle("SecureChat 后台运行中")
            .setContentText("加密连接已建立，消息实时接收")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    private fun getAuthToken(): String? {
        return getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            .getString("auth_token", null)
    }

    private fun getAuthUserId(): String? {
        return getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            .getString("auth_user_id", null)
    }

    private fun getChatDeviceId(): String {
        val prefs = getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
}
