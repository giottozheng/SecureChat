package com.securechat.app.network.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器：设备重启后重新武装推送（前台 Service + AlarmManager 轮询），
 * 使「关掉 app 也能收消息」在重启后依然生效（类似微信的自启动保活）。
 *
 * 注意：仅当用户已登录（本地存有 token）时才重新武装；未登录则不打扰。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("auth_token", null)
            if (!token.isNullOrBlank()) {
                Log.i(TAG, "开机完成，重新武装推送")
                PushManager.initialize(context.applicationContext)
            }
        }
    }
}
