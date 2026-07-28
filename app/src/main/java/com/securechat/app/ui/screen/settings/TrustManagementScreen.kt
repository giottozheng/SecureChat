// TrustManagementScreen — 联系人密钥信任管理界面
package com.securechat.app.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 信任关系数据
 */
data class TrustRelationship(
    val userId: String,
    val displayName: String,
    val publicKeyPem: String,
    val exchangedAt: Long,
    val isVerified: Boolean
)

/**
 * 密钥信任管理界面
 * 显示所有已交换密钥的联系人列表及信任状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustManagementScreen(
    relationships: List<TrustRelationship> = emptyList(),
    onToggleVerify: (String) -> Unit = {},
    onDeleteTrust: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    var showDeleteDialogFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("密钥信任管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // Info banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "如何验证密钥？",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "与联系人在安全通道（面对面或通过其他方式）比对密钥指纹，确认后点击验证。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (relationships.isEmpty()) {
                EmptyTrustList()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(relationships, key = { it.userId }) { rel ->
                        TrustRelationshipCard(
                            relationship = rel,
                            onToggleVerify = { onToggleVerify(rel.userId) },
                            onDelete = { showDeleteDialogFor = rel.userId }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialogFor?.let { userId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialogFor = null },
            title = { Text("删除信任关系") },
            text = { Text("确定要与 ${userId} 删除密钥信任关系吗？后续通信需要重新交换密钥。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrust(userId)
                        showDeleteDialogFor = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogFor = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun TrustRelationshipCard(
    relationship: TrustRelationship,
    onToggleVerify: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (relationship.isVerified) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (relationship.isVerified) Icons.Default.Verified else Icons.Default.Key,
                        contentDescription = null,
                        tint = if (relationship.isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        relationship.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "密钥指纹: ${getFingerprint(relationship.publicKeyPem)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "交换于: ${formatTimestamp(relationship.exchangedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (!relationship.isVerified) {
                    TextButton(onClick = onToggleVerify) {
                        Text("验证")
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun EmptyTrustList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "暂无已验证的密钥",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 从 RSA 公钥 PEM 中提取简短指纹用于显示
 */
fun getFingerprint(publicKeyPem: String): String {
    val cleaned = publicKeyPem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\n", "")
        .replace("\r", "")
        .trim()
    val bytes = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
    // 取前 16 字节做 SHA-256
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(bytes)
    return hash.take(8).joinToString(":") { "%02X".format(it) }
}

/**
 * 格式化时间戳
 */
fun formatTimestamp(timestamp: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(java.util.Date(timestamp))
}
