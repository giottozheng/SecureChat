package com.securechat.app.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.securechat.app.BuildConfig
import com.securechat.app.update.UpdateInfo
import com.securechat.app.update.UpdateManager
import com.securechat.app.util.ServerConfig
import com.securechat.app.network.push.PushConnectionService
import com.securechat.app.ui.components.AvatarBox

/**
 * 设置与安全中心
 * 包含：加密状态、数据管理、关于、OTA 升级
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    displayName: String = "",
    avatarUrl: String = "",
    isAvatarUploading: Boolean = false,
    onAvatarChange: (ByteArray) -> Unit = { _ -> },
    onChangeNickname: (String) -> Unit = { _ -> },
    onChangePassword: (String, String) -> Unit = { _, _ -> },
    onClearCache: () -> Unit = {},
    onResetKeys: () -> Unit = {},
    onResyncKeys: () -> Unit = {},
    onLogout: () -> Unit = {},
    onAbout: () -> Unit = {},
    onNavigatePairing: () -> Unit = {},
    userId: String = ""
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showResyncDialog by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf(displayName) }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // ── 服务端连接（绑定自定义 IP/域名 + 端口）──
    val context = LocalContext.current
    var serverHost by remember { mutableStateOf(ServerConfig.getHost(context)) }
    var serverPort by remember { mutableStateOf(ServerConfig.getPort(context).toString()) }
    var serverMsg by remember { mutableStateOf<String?>(null) }
    var testingConn by remember { mutableStateOf(false) }

    // ── OTA 升级状态 ──
    val scope = rememberCoroutineScope()
    var updateUiState by remember { mutableStateOf<OtaUiState>(OtaUiState.Idle) }

    // ── 头像选择（从相册取图 → 压缩为 JPEG → 回调上传）──
    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bytes = compressAvatarToJpeg(context, uri)
            if (bytes == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                onAvatarChange(bytes)
            }
        }
    }

    fun checkForUpdate() {
        scope.launch {
            updateUiState = OtaUiState.Checking
            when (val r = UpdateManager.checkUpdate(BuildConfig.VERSION_CODE)) {
                is UpdateManager.CheckResult.Available -> updateUiState = OtaUiState.Available(r.info)
                is UpdateManager.CheckResult.NoUpdate -> {
                    updateUiState = OtaUiState.Idle
                    Toast.makeText(context, "已是最新版本 v${r.versionName}", Toast.LENGTH_SHORT).show()
                }
                is UpdateManager.CheckResult.Error -> {
                    updateUiState = OtaUiState.Idle
                    Toast.makeText(context, r.msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Dialogs
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新输入账号和密码登录。") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; onLogout() }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有密钥") },
            text = {
                Text("⚠️ 这将删除本机所有加密密钥！重置后，您需要重新与所有联系人交换密钥，且无法解密之前的历史消息。")
            },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; onResetKeys() }) {
                    Text("确认重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResyncDialog) {
        AlertDialog(
            onDismissRequest = { showResyncDialog = false },
            title = { Text("重新同步密钥") },
            text = {
                Text("当对方发来的消息显示「无法解密」时，点击此处可重新上传你的公钥并刷新好友密钥版本。对方在下一次发送时会自动获取你的新公钥，通常无需对方手动重启应用。")
            },
            confirmButton = {
                TextButton(onClick = { showResyncDialog = false; onResyncKeys() }) {
                    Text("重新同步")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResyncDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除本地缓存") },
            text = { Text("清除所有加密消息的临时缓存文件（不影响数据库）") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; onClearCache() }) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 修改昵称弹窗 ──
    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("修改昵称") },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    placeholder = { Text("输入新的显示昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChangeNickname(nicknameInput.trim())
                        showNicknameDialog = false
                    },
                    enabled = nicknameInput.trim().isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 修改密码弹窗 ──
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("修改密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = oldPw,
                        onValueChange = { oldPw = it; passwordError = null },
                        placeholder = { Text("当前密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it; passwordError = null },
                        placeholder = { Text("新密码（至少 6 位）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it; passwordError = null },
                        placeholder = { Text("确认新密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            newPw.length < 6 -> passwordError = "新密码至少 6 位"
                            newPw != confirmPw -> passwordError = "两次输入的新密码不一致"
                            else -> {
                                onChangePassword(oldPw, newPw)
                                oldPw = ""; newPw = ""; confirmPw = ""
                                showPasswordDialog = false
                            }
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("安全设置") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 个人资料 ──
            Text("个人资料", style = MaterialTheme.typography.labelMedium)

            // ── 头像 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(modifier = Modifier.size(80.dp)) {
                    AvatarBox(
                        avatarUrl = avatarUrl.ifBlank { null }?.let { ServerConfig.getBaseUrl(context) + it },
                        displayName = displayName.ifBlank { "我" },
                        size = 80.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = !isAvatarUploading) { avatarLauncher.launch("image/*") }
                    )
                    if (isAvatarUploading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("头像", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isAvatarUploading) "上传中…" else "点击头像更换（建议使用正方形图片）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ListItem(
                headlineContent = { Text("昵称") },
                supportingContent = { Text(displayName.ifBlank { "未设置" }) },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = {
                        nicknameInput = displayName
                        showNicknameDialog = true
                    }) { Text("修改") }
                }
            )

            ListItem(
                headlineContent = { Text("用户 ID") },
                supportingContent = { Text(userId.ifBlank { "未获取" }) },
                leadingContent = { Icon(Icons.Default.Badge, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("用户ID", userId))
                        Toast.makeText(context, "已复制用户ID，可发送给好友以添加您", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                }
            )

            ListItem(
                headlineContent = { Text("密码") },
                supportingContent = { Text("定期更换密码以保护账号安全") },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { showPasswordDialog = true }) { Text("修改") }
                }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 加密状态卡片 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔒 加密状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    EncryptedRow("加密算法", "AES-256-GCM + ECDH(secp256r1)")
                    EncryptedRow("密钥存储", "Android Keystore (TEE)")
                    EncryptedRow("密钥交换", "ECDH 前向安全")
                    EncryptedRow("消息存储", "端到端加密 · 1 天后自动清除")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 服务端连接（可绑定自定义 IP/域名 + 端口）──
            Text("服务端连接", style = MaterialTheme.typography.labelMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "当前连接地址：${ServerConfig.getBaseUrl(context)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text("服务端地址（IP 或域名）") },
                        placeholder = { Text("如 192.168.1.10 或 chat.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val h = serverHost.trim()
                            val p = serverPort.toIntOrNull()
                            if (h.isBlank() || p == null || p <= 0 || p > 65535) {
                                serverMsg = "地址或端口无效"
                                return@Button
                            }
                            ServerConfig.setServer(context, h, p)
                            serverMsg = "已保存，正在重连…"
                            // 重启推送服务以使用新地址
                            PushConnectionService.stop(context)
                            PushConnectionService.start(context)
                            serverMsg = "已保存并应用：$h:$p（需该服务端存在本账号）"
                        }) { Text("保存并连接") }
                        OutlinedButton(onClick = {
                            ServerConfig.resetToDefault(context)
                            serverHost = ServerConfig.DEFAULT_HOST
                            serverPort = ServerConfig.DEFAULT_PORT.toString()
                            serverMsg = "已恢复默认地址"
                            PushConnectionService.stop(context)
                            PushConnectionService.start(context)
                        }) { Text("恢复默认") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val h = serverHost.trim().removePrefix("http://").removePrefix("https://")
                                val p = serverPort.toIntOrNull()
                                if (h.isBlank() || p == null || p <= 0 || p > 65535) {
                                    serverMsg = "请先填写有效的地址和端口"
                                    return@OutlinedButton
                                }
                                testingConn = true
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val url = "http://$h:$p/health"
                                        val req = Request.Builder().url(url).get().build()
                                        val client = OkHttpClient.Builder()
                                            .connectTimeout(8, TimeUnit.SECONDS)
                                            .readTimeout(8, TimeUnit.SECONDS)
                                            .build()
                                        client.newCall(req).execute().use { resp ->
                                            val ok = resp.isSuccessful
                                            val code = resp.code
                                            withContext(Dispatchers.Main) {
                                                serverMsg = if (ok) "连接测试成功 ✅（HTTP $code）" else "连接失败：HTTP $code"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        val msg = e.message ?: e.javaClass.simpleName
                                        withContext(Dispatchers.Main) {
                                            serverMsg = "连接失败：$msg"
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) { testingConn = false }
                                    }
                                }
                            },
                            enabled = !testingConn
                        ) { Text(if (testingConn) "测试中…" else "测试连接") }
                        OutlinedButton(onClick = {
                            val h = serverHost.trim().removePrefix("http://").removePrefix("https://")
                            val p = serverPort.toIntOrNull()
                            if (h.isBlank() || p == null || p <= 0 || p > 65535) {
                                serverMsg = "请先填写有效的地址和端口"
                                return@OutlinedButton
                            }
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val url = "http://$h:$p/api/server-config"
                                    val req = Request.Builder().url(url).get().build()
                                    val client = OkHttpClient.Builder()
                                        .connectTimeout(8, TimeUnit.SECONDS)
                                        .readTimeout(8, TimeUnit.SECONDS)
                                        .build()
                                    client.newCall(req).execute().use { resp ->
                                        val body = resp.body?.string().orEmpty()
                                        if (resp.isSuccessful) {
                                            val o = JSONObject(body)
                                            val nh = o.optString("host", "")
                                            val np = o.optInt("port", -1)
                                            withContext(Dispatchers.Main) {
                                                if (nh.isNotBlank() && np > 0) {
                                                    serverHost = nh
                                                    serverPort = np.toString()
                                                    serverMsg = "已自动获取：$nh:$np（点击「保存并连接」生效）"
                                                } else {
                                                    serverMsg = "自动获取失败：返回数据异常"
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                serverMsg = "自动获取失败：HTTP ${resp.code}"
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    val msg = e.message ?: e.javaClass.simpleName
                                    withContext(Dispatchers.Main) {
                                        serverMsg = "自动获取失败：$msg"
                                    }
                                }
                            }
                        }) { Text("自动获取") }
                    }
                    serverMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 多设备 / 桌面端配对 ──
            Text("多设备", style = MaterialTheme.typography.labelMedium)

            ListItem(
                headlineContent = { Text("桌面端配对审批") },
                supportingContent = { Text("审批网页版 / 桌面 App 的登录配对请求") },
                leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onNavigatePairing) { Text("管理") }
                }
            )

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // ── 数据管理 ──
            Text("数据管理", style = MaterialTheme.typography.labelMedium)

            ListItem(
                headlineContent = { Text("清除本地缓存") },
                supportingContent = { Text("释放加密消息的临时文件") },
                leadingContent = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { showClearDialog = true }) { Text("清除") }
                }
            )

            ListItem(
                headlineContent = { Text("重新同步密钥") },
                supportingContent = { Text("对方消息显示「无法解密」时，刷新你的公钥与好友密钥版本") },
                leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = { showResyncDialog = true }) { Text("同步") }
                }
            )

            ListItem(
                headlineContent = { Text("重置所有密钥") },
                supportingContent = { Text("⚠️ 不可逆操作，将丢失所有历史消息解密能力") },
                leadingContent = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("重置", color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            // ── 后台保活（电池白名单）──
            ListItem(
                headlineContent = { Text("后台保活（电池白名单）") },
                supportingContent = { Text("加入电池优化白名单，避免被系统/OEM 杀掉后台连接") },
                leadingContent = { Icon(Icons.Default.Bolt, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "请手动在 系统设置 → 电池 → 应用锁/后台管理 → SecureChat 中设为「允许后台运行」",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }) { Text("设置") }
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // ── 关于 ──
            Text("关于", style = MaterialTheme.typography.labelMedium)

            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = { Text("当前版本 v${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                trailingContent = {
                    if (updateUiState is OtaUiState.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { checkForUpdate() }) { Text("检查") }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("应用信息") },
                supportingContent = { Text("SecureChat v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) — 面向小型团队的加密即时通讯") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onAbout) { Text("详情") }
                }
            )

            Spacer(Modifier.height(8.dp))

            // ── 退出按钮 ──
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("退出登录")
            }
        }
    }

    // ── OTA 升级弹窗 ──
    if (updateUiState is OtaUiState.Available || updateUiState is OtaUiState.Downloading) {
        val info = when (val s = updateUiState) {
            is OtaUiState.Available -> s.info
            is OtaUiState.Downloading -> s.info
            else -> null
        }
        val isDownloading = updateUiState is OtaUiState.Downloading
        val progress = if (updateUiState is OtaUiState.Downloading) (updateUiState as OtaUiState.Downloading).progress else 0
        if (info != null) {
            AlertDialog(
                onDismissRequest = { if (!isDownloading) updateUiState = OtaUiState.Idle },
                title = { Text("发现新版本 v${info.versionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isDownloading) {
                            if (progress >= 0) {
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("下载中… $progress%", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("下载中…", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Text(
                                info.changelog.ifBlank { "修复与优化" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                },
                confirmButton = {
                    if (isDownloading) {
                        TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                updateUiState = OtaUiState.Downloading(info, -1)
                                try {
                                    UpdateManager.downloadAndInstall(context, info) { p ->
                                        updateUiState = OtaUiState.Downloading(info, p)
                                    }
                                } catch (e: Exception) {
                                    updateUiState = OtaUiState.Idle
                                    Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("立即更新") }
                    }
                },
                dismissButton = {
                    if (!isDownloading) {
                        TextButton(onClick = { updateUiState = OtaUiState.Idle }) { Text("稍后") }
                    }
                }
            )
        }
    }
}

private sealed interface OtaUiState {
    data object Idle : OtaUiState
    data object Checking : OtaUiState
    data class Available(val info: UpdateInfo) : OtaUiState
    data class Downloading(val info: UpdateInfo, val progress: Int) : OtaUiState
}

@Composable
private fun EncryptedRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 将选中的图片 URI 解码、按比例缩放（最长边不超过 maxDim）并压缩为 JPEG 字节数组。
 * 在 IO 线程调用；返回 null 表示读取失败。
 */
private fun compressAvatarToJpeg(
    context: Context,
    uri: Uri,
    maxDim: Int = 512,
    quality: Int = 82
): ByteArray? {
    return try {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val (w, h) = boundsOpts.outWidth to boundsOpts.outHeight
        val inSample = if (w > 0 && h > 0) {
            (maxOf(w, h).toFloat() / maxDim).coerceAtLeast(1f).toInt().coerceAtLeast(1)
        } else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null
        val scaled = if (maxOf(bmp.width, bmp.height) > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled != bmp) scaled.recycle()
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.e("SettingsScreen", "compressAvatar failed", e)
        null
    }
}
