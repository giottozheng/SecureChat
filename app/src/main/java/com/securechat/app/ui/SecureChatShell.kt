package com.securechat.app.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.securechat.app.ServiceLocator
import com.securechat.app.call.CallActiveScreen
import com.securechat.app.call.CallIncomingOverlay
import com.securechat.app.call.CallOutgoingOverlay
import com.securechat.app.call.CallState
import com.securechat.app.ui.screen.chat.ConversationListScreen
import com.securechat.app.ui.screen.chat.MessageDetailScreen
import com.securechat.app.ui.screen.contacts.ContactsScreen
import com.securechat.app.ui.screen.settings.SettingsScreen
import com.securechat.app.util.ServerConfig
import com.securechat.app.util.canonicalConversationId
import com.securechat.app.viewmodel.ConversationViewModel
import com.securechat.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SecureChat Shell — 带底部导航结构。
 *
 * 键盘避让（1.0.35）：
 *   安卓11+ 上 adjustResize 失效（窗口不收缩），需要手动推高内容区以避免被键盘遮挡。
 *   关键：padding 必须加在**外层 Column**上，这样 NavHost 内的消息页面 + 底部导航栏
 *   一起上移。之前 1.0.34 只在 MessageDetailScreen 内部加 padding → 导航栏被盖住。
 *   使用与 MessageDetailScreen 相同的封顶方案（min(rawKb, screenWidth*75%)），
 *   对 ColorOS 虚报值(996px) 截断，安卓10 不加（adjustResize 原生有效）。
 *
 * 通话浮层（1.0.47）：
 *   订阅 CallManager.callState，来电 / 通话中 / 结束时在 Shell 最上层叠加对应界面，
 *   保证在任意底部 tab 下都能弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureChatShell(
    onLogout: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val navController = rememberNavController()
    val prefs = LocalContext.current.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
    val currentUserId = prefs.getString("auth_user_id", "local_user") ?: "local_user"

    val tabTitles = listOf("聊天", "联系人", "设置")
    val tabIcons = listOf(
        Icons.Default.Mail,
        Icons.Default.People,
        Icons.Default.Settings
    )
    val tabRoutes = listOf("chats", "contacts", "settings")

    // 通话状态订阅（来电 / 通话中 / 结束）
    val callManager = ServiceLocator.callManager
    val callState by (callManager?.callState?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<CallState>(CallState.Idle) })

    // 键盘避让：封顶后的高度作为外层 Column 底部 padding
    val density = LocalDensity.current
    val isModernAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val shellKbPadDp = if (isModernAndroid) {
        val rawKbPx = rememberRealKeyboardHeight()
        val view = LocalView.current
        val maxKbPx = remember {
            (view.resources.displayMetrics.widthPixels * 0.82f).toInt().coerceAtLeast(200)
        }
        val cappedKbPx = rawKbPx.coerceAtMost(maxKbPx)
        with(density) { cappedKbPx.toDp() }
    } else {
        0.dp
    }

    // ── 系统公告（启动时从服务端拉取最新一条，未读则弹窗）──
    var announcement by remember { mutableStateOf<Announcement?>(null) }

    LaunchedEffect(Unit) {
        launch {
            try {
                val url = "${ServerConfig.getBaseUrl()}/api/announcement"
                val req = Request.Builder().url(url).get().build()
                val resp = withContext(Dispatchers.IO) {
                    OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build().newCall(req).execute()
                }
                val body = resp.body?.string().orEmpty()
                resp.close()
                if (resp.isSuccessful) {
                    val o = JSONObject(body)
                    val ann = o.optJSONObject("announcement")
                    if (ann != null) {
                        val id = ann.optString("id", "")
                        val lastSeen = prefs.getString("last_announcement_id", "") ?: ""
                        if (id.isNotBlank() && id != lastSeen) {
                            announcement = Announcement(
                                id = id,
                                title = ann.optString("title", "系统公告"),
                                content = ann.optString("content", "")
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // 公告拉取失败不影响正常使用
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .then(if (isModernAndroid && shellKbPadDp.value > 0) Modifier.padding(bottom = shellKbPadDp) else Modifier)
        ) {
            NavHost(
                navController = navController,
                startDestination = "root",
                modifier = Modifier.weight(1f)
            ) {
                composable("root") {
                    navController.navigate(tabRoutes[0]) {
                        popUpTo("root") { inclusive = true }
                    }
                }

                tabContainer(
                    navController = navController,
                    currentUserId = currentUserId,
                    onLogout = onLogout
                )

                // Detail screen — path params: {conversationId}/{recipientId}
                // recipientId may be empty string when not available (fallback to convId)
                composable(
                    "detail/{conversationId}/{recipientId}",
                    arguments = listOf(
                        navArgument("conversationId") { type = NavType.StringType },
                        navArgument("recipientId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val convId = backStackEntry.arguments?.getString("conversationId") ?: ""
                    val rcptId = backStackEntry.arguments?.getString("recipientId")
                        ?.takeIf { it.isNotEmpty() }
                    MessageDetailScreen(
                        conversationId = convId,
                        recipientId = rcptId,
                        onBack = { navController.popBackStack("root", inclusive = false) }
                    )
                }
            }

            // Bottom nav bar
            NavigationBar(modifier = Modifier.fillMaxWidth()) {
                tabRoutes.forEachIndexed { index, route ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tabIcons[index], contentDescription = tabTitles[index]) },
                        label = { Text(tabTitles[index]) }
                    )
                }
            }
        }

        // ── 通话浮层（来电 / 通话中 / 结束）──
        when (val cs = callState) {
            is CallState.OutgoingRing -> CallOutgoingOverlay(cs)
            is CallState.IncomingRing -> CallIncomingOverlay(cs)
            is CallState.Connected -> CallActiveScreen(cs)
            is CallState.Ended -> {
                LaunchedEffect(cs.callId) { delay(1800); callManager?.reset() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            cs.reason,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {}
        }

        // ── 系统公告弹窗 ──
        announcement?.let { ann ->
            AlertDialog(
                onDismissRequest = {
                    prefs.edit().putString("last_announcement_id", ann.id).apply()
                    announcement = null
                },
                title = { Text("📢 ${ann.title}") },
                text = { Text(ann.content, style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("last_announcement_id", ann.id).apply()
                        announcement = null
                    }) { Text("我知道了") }
                }
            )
        }
    }
}

/** 系统公告数据载体 */
private data class Announcement(
    val id: String,
    val title: String,
    val content: String
)

/**
 * Registers the three bottom-nav tab routes inside a NavHost.
 */
fun NavGraphBuilder.tabContainer(
    navController: NavHostController,
    currentUserId: String,
    onLogout: () -> Unit
) {
    composable("chats") {
        val vm: ConversationViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()

        ConversationListScreen(
            conversations = state.conversations,
            onConversationClick = { convId ->
                // Find the other participant (recipient) from the conversation
                val conv = state.conversations.find { it.id == convId }
                val recipientId = conv?.participants
                    ?.find { it != currentUserId }
                    ?: convId
                // Use path params: detail/{conversationId}/{recipientId}
                navController.navigate("detail/$convId/$recipientId")
            }
        )
    }

    composable("contacts") {
        ContactsScreen(
            onChatWith = { userId ->
                // 规范化会话 ID：双方用同一 ID，避免发送方/接收方会话错位
                val convId = canonicalConversationId(currentUserId, userId)
                navController.navigate("detail/$convId/$userId")
            }
        )
    }

    composable("settings") {
        val settingsVm: SettingsViewModel = hiltViewModel()
        val settingsState by settingsVm.uiState.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // 用户反馈（昵称/密码修改结果）
        LaunchedEffect(settingsState.message) {
            settingsState.message?.let {
                // 简单 toast 反馈
                android.widget.Toast.makeText(
                    context, it, android.widget.Toast.LENGTH_SHORT
                ).show()
                settingsVm.clearMessage()
            }
        }

        SettingsScreen(
            displayName = settingsState.displayName,
            userId = currentUserId,
            onChangeNickname = { settingsVm.updateNickname(it) },
            onChangePassword = { old, new -> settingsVm.changePassword(old, new) },
            onClearCache = { /* TODO: clear cache */ },
            onResetKeys = { /* TODO: reset all keys */ },
            onLogout = onLogout,
            onAbout = { /* TODO: show about dialog */ }
        )
    }
}

/**
 * 在 edge-to-edge 模式下测量键盘高度原始值（与 MessageDetailScreen 中的同名函数相同逻辑）。
 *
 * 返回原始像素值，调用方自行施加封顶。
 */
@Composable
private fun rememberRealKeyboardHeight(): Int {
    var keyboardHeight by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val diff = screenHeight - rect.bottom
            // 键盘高度至少占屏幕 15% 才算有效（排除状态栏等微小变化）
            keyboardHeight = if (diff > screenHeight * 0.15) diff else 0
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return keyboardHeight
}
