@file:OptIn(ExperimentalFoundationApi::class)

package com.securechat.app.ui.screen.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securechat.app.data.local.FriendEntity
import com.securechat.app.viewmodel.ContactsViewModel

/**
 * 联系人界面 — 仅显示「已互为好友」的成员。
 * 好友关系由服务端维护；未添加好友的账号不出现在此列表。
 * 长按好友项可删除好友（双向解除，删除后双方无法通信）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel = hiltViewModel(),
    onChatWith: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showRequestDialog by remember { mutableStateOf(false) }
    var inputTarget by remember { mutableStateOf("") }
    var friendToRemove by remember { mutableStateOf<FriendEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 用户反馈（请求发送结果 / 接受拒绝结果 / 删除结果）
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("联系人") },
                actions = {
                    IconButton(onClick = { showRequestDialog = true }) {
                        Icon(Icons.Default.Person, contentDescription = "添加联系人")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRequestDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加好友") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // ── 好友请求区 ──
            if (uiState.pendingRequests.isNotEmpty()) {
                FriendRequestSection(
                    requests = uiState.pendingRequests,
                    onAccept = viewModel::accept,
                    onReject = viewModel::reject
                )
            }

            if (uiState.friends.isEmpty()) {
                EmptyContacts()
            } else {
                FriendList(
                    friends = uiState.friends,
                    onChatWith = onChatWith,
                    onLongPress = { friendToRemove = it }
                )
            }
        }
    }

    // 添加好友弹窗 — 输入对方用户名
    if (showRequestDialog) {
        AlertDialog(
            onDismissRequest = { showRequestDialog = false },
            title = { Text("添加好友") },
            text = {
                Column {
                    Text("输入对方用户名，对方将收到好友请求。对方接受后，你们会出现在彼此的联系人列表中。")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputTarget,
                        onValueChange = { inputTarget = it },
                        placeholder = { Text("对方用户名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (inputTarget.isNotBlank()) {
                                    viewModel.sendRequest(inputTarget.trim())
                                    showRequestDialog = false
                                    inputTarget = ""
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputTarget.isNotBlank()) {
                            viewModel.sendRequest(inputTarget.trim())
                            showRequestDialog = false
                            inputTarget = ""
                        }
                    },
                    enabled = inputTarget.isNotBlank()
                ) { Text("发送请求") }
            },
            dismissButton = {
                TextButton(onClick = { showRequestDialog = false; inputTarget = "" }) { Text("取消") }
            }
        )
    }

    // 删除好友确认弹窗
    friendToRemove?.let { friend ->
        AlertDialog(
            onDismissRequest = { friendToRemove = null },
            title = { Text("删除好友") },
            text = {
                Text("确定删除「${friend.displayName}」(${friend.userId}) 吗？删除后双方将无法互发消息，聊天记录仍保留。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFriend(friend.userId)
                        friendToRemove = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { friendToRemove = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FriendRequestSection(
    requests: List<com.securechat.app.data.local.FriendRequestEntity>,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp, 16.dp, 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "好友请求 (${requests.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(8.dp))
            requests.forEach { req ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarBox(null, req.fromName.ifBlank { req.fromId })
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(req.fromName.ifBlank { req.fromId }, style = MaterialTheme.typography.bodyLarge)
                        Text(req.fromId, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = { onAccept(req.fromId) }) {
                        Icon(Icons.Default.Check, contentDescription = "接受", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onReject(req.fromId) }) {
                        Icon(Icons.Default.Close, contentDescription = "拒绝", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyContacts() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有好友", style = MaterialTheme.typography.bodyLarge)
            Text(
                "点击右下角「添加好友」，输入对方用户名发送请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FriendList(
    friends: List<FriendEntity>,
    onChatWith: (String) -> Unit,
    onLongPress: (FriendEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(friends, key = { it.userId }) { friend ->
            FriendCard(
                friend = friend,
                onClick = { onChatWith(friend.userId) },
                onLongPress = { onLongPress(friend) }
            )
        }
    }
}

@Composable
private fun FriendCard(
    friend: FriendEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (friend.isOnline) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBox(null, friend.displayName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(friend.displayName, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "密钥已交换",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(friend.userId, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            OnlineDot(friend.isOnline)
        }
    }
}

@Composable
private fun AvatarBox(resId: Int?, displayName: String) {
    val label = displayName.firstOrNull()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun OnlineDot(isOnline: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(
                if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small
            )
    )
}
