package com.securechat.app.ui.activity

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.securechat.app.network.push.PushConnectionService
import com.securechat.app.ui.SecureChatShell
import com.securechat.app.util.ActiveConversationTracker
import com.securechat.app.ui.screen.login.LoginScreen
import com.securechat.app.ui.theme.SecureChatTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主入口 Activity
 * 继承 FragmentActivity 以满足 BiometricPrompt 的依赖要求
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 键盘避让（1.0.31 终局 · 基于 1.0.30 诊断数据定标）：
        //   安卓 11+/16（如 1+Ace2Pro）系统 adjustResize 单独使用已不缩小窗口（1.0.26 验证），
        //   但 edge-to-edge + adjustResize 组合使用时，窗口正确收缩且 inset 准确流入 Compose
        //   （Google Material3 官方推荐模式）。旧安卓(<=10，如 1+5T)仅用 adjustResize 即可靠。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        // 浅色主题下让状态栏图标/文字变为深色，确保顶部系统状态栏可见
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        // 统一使用 adjustResize：旧安卓靠它缩小窗口；新安卓靠它 + edge-to-edge 协同工作

        prefs = getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        val hasValidToken = !prefs.getString("auth_token", null).isNullOrBlank()

        setContent {
            SecureChatTheme {
                var isLoggedIn by remember { mutableStateOf(hasValidToken) }
                var showShell by remember { mutableStateOf(hasValidToken) }

                if (!isLoggedIn) {
                    // ── Step 1: 登录页面 ──
                    LoginScreen(
                        onLoginSuccess = {
                            isLoggedIn = true
                            showShell = true
                            // 登录成功后立即启动推送服务（Compose 状态变化不会触发 onResume）
                            if (!serviceStarted) {
                                serviceStarted = true
                                try {
                                    PushConnectionService.start(this@MainActivity)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    serviceStarted = false
                                }
                            }
                        }
                    )
                } else if (showShell) {
                    // ── Step 2: 主界面 Shell ──
                    SecureChatShell(
                        onLogout = {
                            // 退出登录：清除 session，重启到登录页
                            prefs.edit().clear().apply()
                            PushConnectionService.stop(this@MainActivity)
                            serviceStarted = false
                            isLoggedIn = false
                            showShell = false
                        }
                    )
                } else {
                    // 加载中
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    /**
     * 在 Activity 进入前台时启动推送服务
     * 使用 onResume 避免从 Compose 回调中调用 startForegroundService 的生命周期问题
     */
    override fun onResume() {
        super.onResume()
        // App 回到前台：标记，供推送层判断是否抑制「当前会话」的新消息通知
        ActiveConversationTracker.setAppForeground(true)
        val hasValidToken = !prefs.getString("auth_token", null).isNullOrBlank()
        if (hasValidToken && !serviceStarted) {
            serviceStarted = true
            // 延迟一帧启动，避免 startForegroundService 与 Activity 生命周期冲突
            @Suppress("DEPRECATION")
            Handler(Looper.getMainLooper()).post {
                try {
                    PushConnectionService.start(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                    serviceStarted = false
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // App 退到后台：视为「离开聊天窗口」，清空当前会话以避免后台仍抑制通知；
        // 服务在后台持续运行，不需要停止。
        ActiveConversationTracker.setAppForeground(false)
    }
}
