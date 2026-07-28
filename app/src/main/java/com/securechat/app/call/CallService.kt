package com.securechat.app.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securechat.app.ui.activity.MainActivity

/**
 * 通话前台服务：通话期间常驻，持有音频焦点、避免系统回收。
 * 前台类型 microphone（语音）+ camera（视频），由调用方按通话类型传入。
 */
class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "securechat_call"
        private const val NOTIF_ID = 2002
        private const val ACTION_STOP = "com.securechat.action.STOP_CALL"
        const val EXTRA_VIDEO = "extra_video"

        fun start(context: Context, isVideo: Boolean) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra(EXTRA_VIDEO, isVideo)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP -> stopSelf")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val isVideo = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val notification = buildNotification(isVideo)
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
            Log.i(TAG, "CallService foreground started (video=$isVideo)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        return START_STICKY
    }

    private fun buildNotification(isVideo: Boolean): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SecureChat 通话中")
            .setContentText(if (isVideo) "视频通话进行中" else "语音通话进行中")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SecureChat 通话", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
