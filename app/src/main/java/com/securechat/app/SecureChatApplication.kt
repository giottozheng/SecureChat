package com.securechat.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.crypto.keyexchange.RsaKeystoreManager
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.MessageDao
import com.securechat.app.network.push.PushManager
import com.securechat.app.network.push.RetentionWorker
import com.securechat.app.repository.AccountRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.securechat.app.BuildConfig
import com.securechat.app.util.ServerConfig
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import org.json.JSONObject

/**
 * Application entry point — initializes Hilt DI container.
 *
 * 同时把单例依赖注入到 ServiceLocator，供 PushConnectionService（非 Hilt 注入的普通 Service）使用，
 * 避免 Hilt 在 Service 上构造注入导致的 "no zero argument constructor" 崩溃。
 */
@HiltAndroidApp
class SecureChatApplication : Application() {

    @Inject lateinit var messageEncryptor: MessageEncryptor
    @Inject lateinit var messageDao: MessageDao
    @Inject lateinit var conversationDao: ConversationDao
    @Inject lateinit var friendRequestDao: FriendRequestDao
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var rsaKeystoreManager: RsaKeystoreManager
    @Inject lateinit var callManager: com.securechat.app.call.CallManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // 初始化统一的服务端地址管理（供各网络模块取代硬编码地址）
        ServerConfig.init(this)
        // 启动期诊断：自愈合上次原生崩溃（面包屑停在 *_start 则禁用对应子系统）+ 抓取 logcat 原生现场上报
        appScope.launch {
            applyCrashSelfHeal()
            captureNativeCrashLogcat()
        }
        ServiceLocator.init(messageEncryptor, messageDao, conversationDao, friendRequestDao)
        ServiceLocator.callManager = callManager

        val prefs = getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val hasToken = !prefs.getString("auth_token", "").isNullOrBlank()

        // 已登录（本地存有 token）则冷启动即武装推送，确保「关掉 app 也能收消息」
        if (hasToken) {
            PushManager.initialize(this)
            // 自动恢复会话：重新把本机公钥注册到服务器 + 拉取好友列表。
            // 关键修复：服务端为内存存储，重启后会清空所有公钥；若仅自动登录而不
            // 重新注册，对方加密时取不到本用户公钥 →「收件人公钥未找到」；同时好友
            // 昵称覆盖表不会被填充 → 名字回退为占位名。每次冷启动都补一次即可自愈。
            appScope.launch {
                try {
                    val userId = prefs.getString("auth_user_id", "") ?: ""
                    val token = prefs.getString("auth_token", "") ?: ""
                    if (userId.isNotBlank() && token.isNotBlank()) {
                        val pem = rsaKeystoreManager.getPublicKeyPem()
                        accountRepository.registerPublicKey(userId, token, pem)
                        accountRepository.fetchFriends()
                        Log.i("SecureChatApp", "Session restored: key re-registered + friends fetched for $userId")
                        // 校验账号是否仍存在于服务器；若不存在则清本地 token 并提示重新登录
                        if (!accountRepository.checkSelfExists(userId)) {
                            prefs.edit().remove("auth_token").remove("auth_user_id").apply()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    this@SecureChatApplication,
                                    "账号已在服务器失效，请退出后重新登录",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Log.w("SecureChatApp", "Self account missing on server, local token cleared")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SecureChatApp", "Session restore (key/friends) failed", e)
                }
            }
        }

        // 数据保留：本地聊天记录 1 天后自动删除（立即执行一次 + 每 6 小时周期执行）
        scheduleRetention()
    }

    private fun scheduleRetention() {
        val periodic = PeriodicWorkRequestBuilder<RetentionWorker>(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RetentionWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }

    /**
     * 全局未捕获异常记录器：任何线程的未捕获异常都会写 crash_log.txt
     * （app 私有目录 + 外部 files 目录，便于导出），随后仍交还系统默认 handler，
     * 保留原生「应用已停止」行为。目的是在不隐藏问题的前提下，为偶发崩溃保留可诊断
     * 的堆栈，避免反复盲修（参考本次通话闪退排障）。
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val text = try {
                val sb = StringBuilder()
                sb.append("TIME: ").append(System.currentTimeMillis()).append("\n")
                sb.append("THREAD: ").append(thread.name).append("\n")
                sb.append(Log.getStackTraceString(throwable))
                sb.toString()
            } catch (e: Throwable) { "crash-log-build-failed" }
            try {
                runCatching { File(filesDir, "crash_log.txt").writeText(text) }
                runCatching { getExternalFilesDir(null)?.let { File(it, "crash_log.txt").writeText(text) } }
                Log.e("SecureChatApp", "CRASH captured:\n$text")
                // 远程上报崩溃堆栈，便于远程诊断（如通话界面偶发闪退）
                reportCrashRemote(text)
            } catch (e: Throwable) {
                // 忽略，避免二次崩溃
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * 把崩溃堆栈上报到服务器 /api/crash。
     * 用 HttpURLConnection 同步调用（崩溃线程即将结束，协程作用域不可靠）；
     * 短超时 + runCatching 确保绝不二次崩溃，也不阻塞系统原生崩溃处理过久。
     */
    /**
     * 把崩溃堆栈以 text/plain 上报到服务端 /api/crash。
     * 用纯文本而非 JSON，避免堆栈中的特殊字符导致 JSON 截断/解析失败（之前出现上报内容变成乱码 t/t/s 的问题）。
     * 同步 HttpURLConnection + 短超时 + runCatching，确保绝不二次崩溃、不阻塞系统原生崩溃处理过久。
     */
    private fun reportCrashRemote(crashText: String) {
        runCatching {
            val url = URL(ServerConfig.getUrl(this, "/api/crash"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { os -> os.write(crashText.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }
    }

    /**
     * 启动期自愈合 + 原生崩溃诊断。
     * 1) 读 WebRTC 面包屑：若最后一步停在 *_start（对应原生调用在上次运行 segfault、未到 *_done），
     *    则持久化禁用该子系统，避免反复原生崩溃闪退。
     * 2) 把面包屑本身上报，便于远程确认最后执行到的原生步骤。
     */
    private fun applyCrashSelfHeal() {
        val prefs = getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val step = prefs.getString("webrtc_breadcrumb", "") ?: ""
        when (step) {
            "egl_create_start" -> {
                prefs.edit().putBoolean("webrtc_egl_disabled", true).apply()
                reportCrashRemote("SELF_HEAL: EglBase.create likely crashed (breadcrumb=$step); disabling EGL -> video degrades to no-video, audio safe.")
            }
            "pc_init_start" -> {
                prefs.edit().putBoolean("webrtc_disabled", true).apply()
                reportCrashRemote("SELF_HEAL: PeerConnectionFactory.initialize likely crashed (breadcrumb=$step); disabling WebRTC calls on this device.")
            }
        }
        if (step.isNotEmpty()) reportCrashRemote("WEBRTC_BREADCRUMB: $step")
    }

    /**
     * 抓取本次启动前的原生崩溃现场（logcat -d）：过滤 FATAL EXCEPTION / signal 11|6 /
     * libjingle|libwebrtc / tombstone / DEBUG / SIGSEGV，上报到 /api/crash。
     * 原生崩溃（SIGSEGV/SIGABRT）不走 Java UncaughtExceptionHandler，只能从 logcat tombstone 拿到真凶。
     *
     * 关键改进：优先读取 CallManager 在通话初始化时落盘的 logcat 快照（files/call_logcat.txt），
     * 其中包含崩溃前的 WebRTC verbose 日志与 Check failed 文本——这些是定位 libjingle 内部
     * RTC_CHECK 失败真凶的唯一可靠来源（实时 logcat 环形 buffer 常被覆盖而丢失）。
     */
    private fun captureNativeCrashLogcat() {
        runCatching {
            // 1) 上一次通话初始化时存的 logcat 快照（含崩溃前 WebRTC verbose 日志）
            val snapshotLines = runCatching { File(filesDir, "call_logcat.txt").readLines() }.getOrElse { emptyList() }
            // 2) 实时抓当前 logcat（tombstone 现场）
            val proc = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val matched = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.contains("FATAL EXCEPTION") || l.contains("AndroidRuntime:") ||
                    l.contains("signal 11") || l.contains("signal 6") ||
                    l.contains("libjingle") || l.contains("libwebrtc") ||
                    l.contains("tombstone") || l.contains("DEBUG") ||
                    l.contains("SIGSEGV") || l.contains("Abort message")
                ) {
                    matched.add(l)
                }
            }
            reader.close()
            runCatching { proc.waitFor(3, TimeUnit.SECONDS) }

            // 3) 优先抽取 Check failed / RTC_ / FATAL / abort message（abort 的真正原因）
            val keyLines = (snapshotLines + matched).filter {
                val u = it.uppercase()
                u.contains("CHECK FAILED") || u.contains("RTC_") || u.contains("FATAL") ||
                        u.contains("ABORT MESSAGE") || u.contains("ASSERT") || u.contains("LIBJINGLE") ||
                        u.contains("WEBRTC")
            }
            if (keyLines.isNotEmpty()) {
                val snippet = keyLines.takeLast(80).joinToString("\n")
                reportCrashRemote("NATIVE_CRASH_KEY (${keyLines.size} lines)\n$snippet")
            }
            if (matched.isNotEmpty()) {
                val snippet = matched.takeLast(120).joinToString("\n")
                reportCrashRemote("NATIVE_CRASH_LOG (${matched.size} lines)\n$snippet")
            }
        }
    }
}