package com.securechat.app.ui.screen.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import com.securechat.app.data.model.Conversation
import com.securechat.app.util.teamDisplayName

/**
 * Conversation list screen — shows all active conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    conversations: List<Conversation> = emptyList(),
    onConversationClick: (String) -> Unit = {},
    onSendMessage: (String, String) -> Unit = { _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var messageDialogShown by remember { mutableStateOf(false) }

    val prefs = LocalContext.current.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
    val currentUserId = prefs.getString("auth_user_id", "local_user") ?: "local_user"

    val filteredConversations = conversations.filter { conv ->
        val other = conv.participants.firstOrNull { it != currentUserId } ?: ""
        val name = if (other.isBlank()) "未知联系人" else teamDisplayName(other)
        name.contains(searchQuery, ignoreCase = true)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SecureChat") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { messageDialogShown = true },
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                text = { Text("新建消息") }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索聊天...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            
            // Conversation list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredConversations, key = { it.id }) { conv ->
                    ConversationListItem(
                        conversation = conv,
                        onClick = { onConversationClick(conv.id) }
                    )
                }
            }
        }
    }
    
    // New message dialog
    if (messageDialogShown) {
        AlertDialog(
            onDismissRequest = { messageDialogShown = false },
            title = { Text("新建消息") },
            text = { Text("选择联系人开始加密聊天") },
            confirmButton = {
                TextButton(onClick = { messageDialogShown = false }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
fun ConversationListItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    val prefs = LocalContext.current.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
    val me = prefs.getString("auth_user_id", "local_user") ?: "local_user"
    val other = conversation.participants.firstOrNull { it != me } ?: ""
    val resolvedName = if (other.isBlank()) "未知联系人" else teamDisplayName(other)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        resolvedName.firstOrNull()?.uppercaseChar()?.toString()
                            ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolvedName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "[加密消息]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Timestamp + unread badge
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatTimestamp(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Badge {
                        Text(conversation.unreadCount.toString())
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
