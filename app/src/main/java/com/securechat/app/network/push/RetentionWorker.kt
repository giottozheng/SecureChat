package com.securechat.app.network.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.securechat.app.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据保留 Worker —— 删除本地早于 1 天的聊天记录与会话。
 *
 * 与「服务端 1 天保留」按消息时间戳对齐：服务端定时清理 >1 天的密文，
 * 客户端定时清理 >1 天的本地副本，二者按同一时间窗独立执行，效果即「同步删除」。
 *
 * 不依赖 Hilt 注入，改为从 ServiceLocator 读取单例 DAO（与 SyncWorker 一致）。
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RetentionWorker"
        const val RETENTION_MS = 24L * 60 * 60 * 1000 // 1 天
        const val NAME = "securechat_retention"
    }

    override suspend fun doWork(): Result {
        if (!ServiceLocator.isInitialized()) {
            Log.w(TAG, "ServiceLocator 未就绪，稍后重试")
            return Result.retry()
        }
        val prefs = applicationContext.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getString("auth_user_id", "") ?: ""
        if (userId.isBlank()) {
            // 未登录无需清理
            return Result.success()
        }

        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return withContext(Dispatchers.IO) {
            try {
                val removed = ServiceLocator.messageDao.deleteMessagesOlderThan(cutoff)
                ServiceLocator.conversationDao.deleteConversationsOlderThan(cutoff)
                Log.i(TAG, "Retention: 已清理早于 1 天的聊天记录 removed=${removed} (cutoff=${cutoff})")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Retention 执行异常", e)
                Result.retry()
            }
        }
    }
}
