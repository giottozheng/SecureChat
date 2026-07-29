package com.securechat.app.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.securechat.app.viewmodel.PairingViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 桌面端配对审批界面。
 * 当网页版/桌面 App 点击「获取配对码」后，这里会列出待审批请求；
 * 点击「批准并绑定」即把该设备注册到当前登录账号（服务端 /api/pairing/approve）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingApprovalScreen(
    navController: NavHostController,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadPending() }
    LaunchedEffect(state.justApprovedCode) {
        state.justApprovedCode?.let {
            Toast.makeText(context, "已批准配对码 $it，桌面端将自动登录", Toast.LENGTH_LONG).show()
            viewModel.clearApproved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面端配对审批") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadPending() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                "当桌面端（网页版或桌面 App）点击「获取配对码」后，会在此列出待审批请求。点击「批准并绑定」即把该设备绑定到当前登录账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading && state.pending.isEmpty() -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.pending.isEmpty() -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无待审批的桌面端配对请求",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.pending, key = { it.code }) { p ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        p.displayName.ifBlank { "桌面端" },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text("配对码：${p.code}", style = MaterialTheme.typography.bodyMedium)
                                    if (p.expiresAt > 0) {
                                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        Text(
                                            "有效期至 ${sdf.format(Date(p.expiresAt))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = { viewModel.approve(p.code) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.isLoading
                                    ) { Text("批准并绑定") }
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
