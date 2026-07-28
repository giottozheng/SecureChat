package com.securechat.app.network.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Broadcast receiver that triggers periodic polling for unread messages.
 * Scheduled via AlarmManager as a fallback when WebSocket is unavailable.
 */
class PollingReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PollingReceiver"
        private const val POLLING_REQUEST_CODE = 42
        
        fun schedulePolling(context: Context, intervalMs: Long = 30_000L) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = PendingIntent.getBroadcast(
                context,
                POLLING_REQUEST_CODE,
                Intent(context, PollingReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Cannot schedule exact alarms — use inexact
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs,
                    intent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs,
                    intent
                )
            }
        }
        
        fun cancelPolling(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = PendingIntent.getBroadcast(
                context,
                POLLING_REQUEST_CODE,
                Intent(context, PollingReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            intent?.let {
                alarmManager.cancel(it)
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Polling triggered")

        // Launch the polling worker (HTTP 同步兜底)
        val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10, java.util.concurrent.TimeUnit.SECONDS
            )
            .build()

        androidx.work.WorkManager.getInstance(context).enqueue(request)

        // ── 看门狗：进程被划掉/OEM 强杀后，重新拉起前台推送服务 ──
        // 本闹钟由系统 AlarmManager 持有，App 进程死亡后依然会响。
        // 闹钟触发运行在系统回调上下文，且服务类型为 dataSync（后台启动豁免），
        // 因此此处 startForegroundService 合法。仅当服务确实未存活时才拉起，
        // 避免对已连接的服务造成重连风暴。
        if (!PushConnectionService.isAlive) {
            Log.i(TAG, "看门狗：推送服务未存活，重新拉起")
            try {
                PushConnectionService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "看门狗拉起推送服务失败", e)
            }
        } else {
            Log.d(TAG, "看门狗：推送服务存活，无需拉起")
        }

        // Reschedule next poll
        schedulePolling(context)
    }
}
