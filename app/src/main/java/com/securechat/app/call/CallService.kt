package com.securechat.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securechat.app.ui.activity.MainActivity

/**
 * 通话前台服务：通话期间常驻，持有音频焦点、避免系统回收。
 *
 * 关键改进（锁屏持续响铃，仿微信）：
 *   - MODE_RING（被叫收到 invite 到接听前）：在**前台服务**内循环播放 Ringtone（STREAM_RING）+
 *     重复震动 + 持有 PARTIAL_WAKE_LOCK，保证锁屏/息屏时也能持续响铃直到接听或超时；
 *     通知带 fullScreenIntent + 高优先级，锁屏下直接弹出接听界面并点亮屏幕。
 *   - MODE_ACTIVE（已接听 / 主叫）：停止响铃与震动、释放唤醒锁，转为「通话中」常驻通知。
 *
 * 原先的循环响铃写在 Compose 浮层里，只有 App 前台、亮屏时才生效；锁屏时浮层不 compose，
 * 只剩通知的一次性提示音 —— 这就是「锁屏只响一声」的根因。
 */
class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_CALL = "securechat_call"
        private const val CHANNEL_INCOMING = "securechat_incoming_call"
        private const val NOTIF_ID = 2002
        private const val ACTION_STOP = "com.securechat.action.STOP_CALL"
        const val EXTRA_VIDEO = "extra_video"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val MODE_RING = "mode_ring"
        const val MODE_ACTIVE = "mode_active"

        fun start(context: Context, isVideo: Boolean, mode: String = MODE_ACTIVE, peerName: String = "") {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_VIDEO, isVideo)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var currentMode: String = MODE_ACTIVE
    private var isVideo = false
    private var peerName: String = ""

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP -> stopAll")
            stopAll()
            return START_NOT_STICKY
        }
        isVideo = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: ""
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ACTIVE

        when (mode) {
            MODE_RING -> startRinging()
            MODE_ACTIVE -> stopRinging()
        }
        val notification = buildNotification(mode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (isVideo) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIF_ID, notification, fgsType)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Log.i(TAG, "CallService foreground updated (mode=$mode, video=$isVideo)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        return START_STICKY
    }

    // ───────────────────────────── 响铃控制（被叫来电，锁屏也持续） ─────────────────────────────

    private fun startRinging() {
        if (currentMode == MODE_RING && ringtone?.isPlaying == true) return
        currentMode = MODE_RING

        // 1) 循环响铃（走 STREAM_RING，受铃声音量控制，锁屏也可响，仿系统来电）
        stopRingtone()
        runCatching {
            val rt = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            rt.isLooping = true
            rt.play()
            ringtone = rt
        }.onFailure { Log.e(TAG, "play ringtone failed", it) }

        // 2) 重复震动（pattern 末尾 repeat=0 表示从头循环）
        runCatching {
            vibrator = getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 400, 800, 400, 800)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }.onFailure { Log.e(TAG, "vibrate failed", it) }

        // 3) 唤醒锁：保持 CPU 清醒，避免 Doze 把响铃进程挂起（响铃窗口最长 30s，给 60s 余量）
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "securechat:call_ring")
            if (wakeLock?.isHeld != true) wakeLock?.acquire(60_000L)
        }.onFailure { Log.e(TAG, "wakeLock acquire failed", it) }
    }

    private fun stopRinging() {
        currentMode = MODE_ACTIVE
        stopRingtone()
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun stopAll() {
        stopRinging()
        stopForeground(true)
        stopSelf()
    }

    // ───────────────────────────── 通知构建 ─────────────────────────────

    private fun buildNotification(mode: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (mode == MODE_RING) {
            // 来电通知：高优先级 + 全屏意图（锁屏弹出接听界面 + 点亮屏幕）。
            // 不挂 DEFAULT_SOUND：响铃由本服务循环播放，避免「一次性提示音 + 循环响铃」叠加。
            NotificationCompat.Builder(this, CHANNEL_INCOMING)
                .setContentTitle("来电：$peerName")
                .setContentText(if (isVideo) "视频通话邀请" else "语音通话邀请")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
        } else {
            // 通话中通知（已接听 / 主叫）：无声、常驻
            NotificationCompat.Builder(this, CHANNEL_CALL)
                .setContentTitle("SecureChat 通话中")
                .setContentText(if (isVideo) "视频通话进行中" else "语音通话进行中")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 通话中：静音低优先级
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "SecureChat 通话", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
        )
        // 来电：高优先级（headsup + 全屏），但本身不发声（响铃由服务播放）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "来电提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "来电响铃与震动提醒"
                setShowBadge(true)
                enableLights(true)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }
}
