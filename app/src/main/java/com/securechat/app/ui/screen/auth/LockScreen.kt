// 锁屏页面 — 应用启动时触发生物识别验证
package com.securechat.app.ui.screen.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.securechat.app.auth.BiometricAuthManager
import kotlinx.coroutines.launch

/**
 * 锁屏状态数据类
 */
data class LockScreenUiState(
    val isAuthenticateSuccess: Boolean = false,
    val isAuthenticating: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 锁屏页面 Composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreen(
    activity: FragmentActivity,
    biometricManager: BiometricAuthManager,
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier
) {
    var uiState by remember { mutableStateOf(LockScreenUiState()) }
    var showDialog by remember { mutableStateOf(false) }
    var dialogMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(key1 = Unit) {
        authenticateOnce(activity, biometricManager) { result ->
            result.onSuccess {
                uiState = uiState.copy(isAuthenticateSuccess = true, isAuthenticating = false)
                onAuthenticated()
            }.onFailure { error ->
                val msg = error.message ?: ""
                // 无可用生物识别（无硬件 / 未录入）时自动放行，避免模拟器/无指纹设备每次启动被拦截
                if (msg.contains("不支持生物识别硬件") ||
                    msg.contains("生物识别硬件暂不可用") ||
                    msg.contains("未注册任何生物识别方式")) {
                    onAuthenticated()
                } else {
                    uiState = uiState.copy(isAuthenticating = false, errorMessage = msg)
                    showDialog = true
                    dialogMessage = msg
                }
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = "指纹解锁",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Text("SecureChat", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "使用指纹或面容解锁您的安全通讯",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.isAuthenticating) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
            } else if (uiState.errorMessage != null) {
                Text(
                    "验证失败: ${uiState.errorMessage}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            authenticateOnce(activity, biometricManager) { result ->
                                result.onSuccess {
                                    uiState = uiState.copy(isAuthenticateSuccess = true)
                                    onAuthenticated()
                                }.onFailure { err ->
                                    uiState = uiState.copy(errorMessage = err.message)
                                    showDialog = true
                                    dialogMessage = err.message ?: "验证失败"
                                }
                            }
                        }
                    },
                    enabled = uiState.errorMessage != "设备不支持生物识别硬件"
                ) {
                    Text("重试")
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                // 无生物识别设备则跳过
                if (uiState.errorMessage?.contains("不支持生物识别硬件") == true ||
                    uiState.errorMessage?.contains("未注册任何生物识别方式") == true) {
                    onAuthenticated()
                }
            },
            title = { Text("验证提示") },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    if (uiState.errorMessage?.contains("不支持生物识别硬件") == true ||
                        uiState.errorMessage?.contains("未注册任何生物识别方式") == true) {
                        onAuthenticated()
                    }
                }) { Text("确定") }
            }
        )
    }
}

private suspend fun authenticateOnce(
    activity: FragmentActivity,
    manager: BiometricAuthManager,
    callback: (Result<Unit>) -> Unit
) {
    try {
        val result = manager.authenticate(activity = activity)
        callback(result)
    } catch (e: Exception) {
        callback(Result.failure(e))
    }
}