package com.securechat.app.network.push

import android.content.Context
import android.util.Log
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PushManager {
    
    private const val TAG = "PushManager"
    
    private var wsConnected = false
    private var pollingScheduled = false
    
    /**
     * Start push service after login.
     */
    fun initialize(context: Context) {
        Log.d(TAG, "Initializing push")
        PushConnectionService.start(context)
        schedulePollingFallback(context)
    }
    
    /**
     * Stop all push connections on logout.
     */
    fun cancel(context: Context) {
        Log.d(TAG, "Canceling push")
        PushConnectionService.stop(context)
        PollingReceiver.cancelPolling(context)
        wsConnected = false
        pollingScheduled = false
    }
    
    fun onConnected() {
        wsConnected = true
        Log.d(TAG, "Push WebSocket connected")
    }
    
    fun onDisconnected() {
        wsConnected = false
        Log.w(TAG, "Push WebSocket disconnected")
    }
    
    private fun schedulePollingFallback(context: Context) {
        if (pollingScheduled) return
        PollingReceiver.schedulePolling(context, 30_000L)
        pollingScheduled = true
        Log.d(TAG, "Polling fallback scheduled")
    }
}
