package com.securechat.app.ui.screen.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securechat.app.util.ServerConfig
import com.securechat.app.viewmodel.LoginViewModel

/**
 * 登录 / 注册页面 — 与 LoginViewModel 联动。
 * 支持「登录」与「注册」两种模式切换（开放注册，无硬编码测试账号）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    // 服务端地址（首次登录/注册时填写，立即持久化到 ServerConfig）
    var serverAddress by remember {
        mutableStateOf(
            ServerConfig.getBaseUrl(context)
                .removePrefix("http://")
                .removePrefix("https://")
        )
    }

    val submit: () -> Unit = {
        if (uiState.isRegisterMode) {
            viewModel.register(username.trim(), password, displayName.trim())
        } else {
            viewModel.login(username.trim(), password)
        }
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onLoginSuccess()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "SecureChat",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (uiState.isRegisterMode) "注册新账号" else "加密即时通讯",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(48.dp))

            // 服务端地址（首次登录/注册即可填写，支持自定义部署的服务端）
            OutlinedTextField(
                value = serverAddress,
                onValueChange = {
                    serverAddress = it
                    ServerConfig.setServerFromAddress(context, it)
                    // 地址变化后清除旧的连接状态，提示用户重新测试
                    if (uiState.connectionStatus != null) {
                        viewModel.clearConnectionStatus()
                    }
                },
                label = { Text("服务端地址") },
                placeholder = { Text("域名或IP:端口，如 192.168.10.99:8080") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
                singleLine = true
            )

            // 连接测试
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.testConnection() },
                    enabled = !uiState.isLoading && !uiState.isTestingConnection
                ) {
                    if (uiState.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连接")
                    }
                }
                uiState.connectionStatus?.let { status ->
                    Spacer(modifier = Modifier.width(8.dp))
                    val isOk = status == "连接成功"
                    Text(
                        text = if (isOk) "✅ $status" else "❌ $status",
                        color = if (isOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 用户名
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                placeholder = { Text("用于登录与被人添加") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 昵称（仅注册模式）
            if (uiState.isRegisterMode) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("昵称") },
                    placeholder = { Text("联系人/聊天中显示的姓名") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 密码
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                placeholder = { Text("请输入密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!uiState.isLoading) submit()
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 错误提示
            val errorMsg = uiState.errorMessage
            if (errorMsg != null) {
                Text(
                    text = errorMsg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 主按钮
            Button(
                onClick = { if (!uiState.isLoading) submit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (uiState.isRegisterMode) "注册" else "登录")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 模式切换
            TextButton(onClick = { viewModel.setRegisterMode(!uiState.isRegisterMode) }) {
                Text(
                    if (uiState.isRegisterMode) "已有账号？去登录"
                    else "没有账号？注册一个"
                )
            }
        }
    }
}
