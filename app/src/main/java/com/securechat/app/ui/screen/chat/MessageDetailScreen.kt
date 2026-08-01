@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.securechat.app.ui.screen.chat

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securechat.app.util.teamDisplayName
import com.securechat.app.util.getCachedAvatarUrl
import com.securechat.app.util.ServerConfig
import com.securechat.app.util.ActiveConversationTracker
import com.securechat.app.ui.components.AvatarBox
import com.securechat.app.data.model.Message
import com.securechat.app.data.model.MessageType
import com.securechat.app.viewmodel.DisplayMessage
import com.securechat.app.viewmodel.MessageViewModel
import com.securechat.app.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import com.securechat.app.ServiceLocator
import com.securechat.app.call.CallType
import android.Manifest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Chat window — message list + input.
 * Supports text and image messages (end-to-end encrypted).
 *
 * 单条消息交互：
 *   - 长按气泡 → 底部操作面板（复制 / 引用 / 多选 / 删除）
 *   - 多选模式下点击气泡 → 切换选中；顶栏显示已选数量、全选、批量删除
 *
 * Navigation params:
 *   conversationId: used for DB query (can be userId for DMs from contacts tab)
 *   recipientId:    the other party's userId, used for encryption recipient lookup
 */
@Composable
fun MessageDetailScreen(
    conversationId: String,
    recipientId: String?,
    onBack: () -> Unit
) {
    val viewModel: MessageViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fileProgress by viewModel.fileSendProgress.collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf("") }
    // 当前被引用的消息（结构化引用，非文本拼接）
    var quotedMessage by remember { mutableStateOf<DisplayMessage?>(null) }
    var zoomedBytes by remember { mutableStateOf<ByteArray?>(null) }

    // 长按消息弹出的操作面板目标
    var actionTarget by remember { mutableStateOf<DisplayMessage?>(null) }
    // 待删除确认的消息 id 列表（单条或批量）
    var confirmDeleteIds by remember { mutableStateOf<List<String>?>(null) }

    // 通话权限 launcher（语音：RECORD_AUDIO；视频：RECORD_AUDIO + CAMERA）
    // 注意：LocalContext.current 必须在 Composable 主体取，不能放在非 Composable 的回调 lambda 内
    val context = LocalContext.current
    val audioCallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && recipientId != null) {
            ServiceLocator.callManager?.startCall(recipientId, CallType.AUDIO)
        } else if (recipientId != null) {
            android.widget.Toast.makeText(context, "需要麦克风权限才能通话", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val videoCallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audioOk = result[Manifest.permission.RECORD_AUDIO] == true
        if (audioOk && recipientId != null) {
            ServiceLocator.callManager?.startCall(recipientId, CallType.VIDEO)
        } else if (recipientId != null) {
            android.widget.Toast.makeText(context, "需要麦克风权限才能通话", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 键盘避让说明（1.0.35）：
    //   实际避让已提升到 SecureChatShell 外层 Column——外层 padding 确保底部导航栏
    //   （聊天/联系人/设置）也随键盘上移，不被盖住。
    //   此处保留 rawKbPx/cappedKbPx 诊断数据供定标用，不再施加额外 padding。
    val density = LocalDensity.current
    val isModernAndroid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val rawKbPx = rememberRealKeyboardHeight()
    // 保留封顶计算供诊断显示
    val view = LocalView.current
    val maxKbPx = remember {
        (view.resources.displayMetrics.widthPixels * 0.65f).toInt().coerceAtLeast(200)
    }
    val cappedKbPx = rawKbPx.coerceAtMost(maxKbPx)
    val kbPadDp = with(density) { cappedKbPx.toDp() } // 仅用于诊断行显示

    // 自动滚动到最新消息
    val listState = rememberLazyListState()
    var initialScrollDone by remember { mutableStateOf(false) }
    // 发送消息时强制滚到底部（不依赖 canScrollForward，避免新消息追加后该判断失效导致不滚）
    var forceScroll by remember { mutableStateOf(false) }
    // 是否跟随到底部：发消息/在底部时为真；手动上滑看历史时为假。
    // 唯一会改变它的来源：用户手指「离开」列表（DragInteraction.Stop/Cancel）那一刻，
    // 按当前是否贴底判定一次。程序化 scrollToItem 不触发 DragInteraction，
    // 因此永远不参与 stickToBottom 的判定 → 彻底消除「自动滚动 vs 跟随状态」的竞态。
    // （推论：只要用户不主动上滑，stickToBottom 恒为真，收到任何消息必然自动滚到底。）
    var stickToBottom by remember { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null) true
            else (last.offset + last.size) <= info.viewportEndOffset + 50
        }
    }
    LaunchedEffect(conversationId) {
        initialScrollDone = false
        forceScroll = false
        stickToBottom = true
    }
    // 登记当前打开的会话，供推送层决定是否抑制新消息通知：
    // 进入本聊天页时标记 conversationId，离开（dispose）时清空。
    DisposableEffect(conversationId) {
        ActiveConversationTracker.setOpenConversation(conversationId)
        onDispose {
            ActiveConversationTracker.setOpenConversation(null)
        }
    }
    // 仅用户手指离开列表时，用最终落点判定一次跟随状态。拖拽过程中不碰 stickToBottom，
    // 避免每帧重组以及和程序化滚动抢夺该状态（1.0.40 的 wasDragged+拖拽中实时改态方案会
    // 因时序竞态把状态弄歪，导致「安装后就不自动滚」）。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
                val atBottomNow = (last.offset + last.size) <= info.viewportEndOffset + 50
                if (stickToBottom != atBottomNow) {
                    stickToBottom = atBottomNow
                    if (BuildConfig.ENABLE_LOGGING)
                        Log.d("SecureChatScroll", "drag-stop -> stickToBottom=$stickToBottom (atBottomNow=$atBottomNow)")
                }
            }
        }
    }
    // 首屏 / 收到新消息 / 发消息 → 跟随态则滚到底部
    // 历史会话（长列表）首屏一次性布局时 scrollToItem 常一次不到位（中间项未测量、
    // 懒解密占位→真实高度变化导致估算偏移），故重试若干次直到真正贴底，
    // 根治「旧会话打开后停在中间、需手动上划」的问题。
    LaunchedEffect(uiState.displayItems.size, forceScroll) {
        if (uiState.displayItems.isEmpty()) return@LaunchedEffect
        if (!initialScrollDone || forceScroll || stickToBottom) {
            var tries = 0
            while (tries < 6) {
                listState.scrollToItem(uiState.displayItems.lastIndex)
                delay(24)
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val atBottomNow = last != null &&
                        (last.offset + last.size) <= info.viewportEndOffset + 50
                if (atBottomNow) break
                tries++
            }
            if (BuildConfig.ENABLE_LOGGING)
                Log.d("SecureChatScroll", "scroll settled tries=$tries size=${uiState.displayItems.size} stick=$stickToBottom")
            initialScrollDone = true
            forceScroll = false
        }
    }
    // 键盘弹起/收起改变视口高度时，若处于跟随态则重新锚定到底部
    // （用 kbPadDp 作 key，避免与下方 layoutInfo 兜底形成布局竞态）
    LaunchedEffect(kbPadDp) {
        if (stickToBottom && uiState.displayItems.isNotEmpty()) {
            if (BuildConfig.ENABLE_LOGGING)
                Log.d("SecureChatScroll", "re-anchor on keyboard change, stick=$stickToBottom")
            listState.scrollToItem(uiState.displayItems.lastIndex)
        }
    }
    // 懒解密导致底部气泡高度变化（占位单行→真实多行）后重新锚定到底部，
    // 否则最后 1~2 条会被顶出视口。仅在「确实偏离底部」时滚，避免抖动。
    LaunchedEffect(listState.layoutInfo) {
        if (stickToBottom && !isAtBottom && uiState.displayItems.isNotEmpty()) {
            listState.scrollToItem(uiState.displayItems.lastIndex)
        }
    }

    // 图片选择器：用户选图后直接交给 ViewModel 加密发送
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            forceScroll = true
            viewModel.sendImage(it)
        }
    }

    // 文件选择器：选任意文件（图片/视频/文档/其他）后加密上传并发送 FILE 消息
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            forceScroll = true
            viewModel.sendFile(it)
        }
    }

    val headerTitle = (recipientId ?: conversationId).let { id ->
        if (id.startsWith("user-")) teamDisplayName(id) else id
    }

    // 构造引用文本：把「发送者：内容」用不可见分隔符拼到正文前，
    // MessageBubble 会解析并渲染成微信式引用块（左侧灰条 + 原文 + 回复）。
    fun buildQuote(item: DisplayMessage): String {
        val sender = if (item.isOwn) "我" else headerTitle
        val body = item.text ?: "[图片]"
        return "\u0001${sender}：${body}\u0001"
    }

    fun quoteSender(item: DisplayMessage): String = if (item.isOwn) "我" else headerTitle
    fun quotePreviewText(item: DisplayMessage): String = item.text ?: "[图片]"

    // Load messages + set recipient when this conversation is opened
    LaunchedEffect(conversationId) {
        viewModel.openConversation(conversationId, recipientId)
    }

    // 删除确认弹窗
    confirmDeleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDeleteIds = null },
            title = { Text("删除消息") },
            text = {
                Text(
                    if (ids.size == 1) "确定删除这条消息吗？"
                    else "确定删除选中的 ${ids.size} 条消息吗？此操作仅删除本地记录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ids.size == 1) viewModel.deleteMessage(ids.first())
                    else viewModel.deleteSelected()
                    confirmDeleteIds = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteIds = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (uiState.selectionMode) {
                TopAppBar(
                    title = { Text("已选 ${uiState.selectedIds.size} 条") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::exitSelection) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                viewModel.selectAll(uiState.displayItems.map { it.message.id })
                            }
                        ) {
                            Text("全选")
                        }
                        IconButton(onClick = { confirmDeleteIds = uiState.selectedIds.toList() }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除选中")
                        }
                    },
                    windowInsets = WindowInsets.statusBars
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val context = LocalContext.current
                            val avatarUrl = recipientId?.let { uid ->
                                getCachedAvatarUrl(uid)?.let { path ->
                                    ServerConfig.getBaseUrl(context) + path
                                }
                            }
                            AvatarBox(
                                avatarUrl = avatarUrl,
                                displayName = headerTitle,
                                size = 36.dp
                            )
                            Text(
                                text = headerTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {},
                    windowInsets = WindowInsets.statusBars
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        // 1.0.35：键盘避让已提升到 SecureChatShell 外层 Column（保护底部导航栏）。
        // 此处仅用系统 paddingValues；安卓10 的 adjustResize 也通过此路径正常工作。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Messages（Box 包裹以支持「回到底部」浮动按钮 overlay）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = uiState.displayItems,
                        key = { _, item -> item.message.id }
                    ) { _, item ->
                        MessageBubble(
                            displayItem = item,
                            viewModel = viewModel,
                            isSelected = uiState.selectedIds.contains(item.message.id),
                            selectionMode = uiState.selectionMode,
                            onLongClick = { actionTarget = item },
                            onClick = {
                                if (uiState.selectionMode) {
                                    viewModel.toggleSelection(item.message.id)
                                }
                            },
                            onImageClick = { bytes -> zoomedBytes = bytes }
                        )
                    }

                    if (uiState.displayItems.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator()
                                } else {
                                    Text(
                                        "暂无消息，发送第一条加密消息",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 未跟随底部时显示「回到底部」浮动按钮（兜底 UX：点一下立即滚到底）
                androidx.compose.animation.AnimatedVisibility(
                    visible = !stickToBottom,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            stickToBottom = true
                            forceScroll = true
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "回到底部"
                        )
                    }
                }
            }

            // Error display (shown inline above the input bar)
            uiState.error?.let { errorText ->
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 0.dp, 16.dp, 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Input bar（多选模式下隐藏，避免误操作）
            if (!uiState.selectionMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column {
                        // 引用预览条（灰色圆角底 + 左侧彩色竖线 + 最多两行省略号 + × 取消）
                        quotedMessage?.let { quoted ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(36.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp)
                                ) {
                                    Text(
                                        text = quoteSender(quoted),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = quotePreviewText(quoted),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { quotedMessage = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "取消引用",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        var showAttachmentPanel by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                                placeholder = { Text("输入加密消息...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (messageText.isNotBlank() || quotedMessage != null) {
                                            forceScroll = true
                                            val finalText = quotedMessage?.let { buildQuote(it) + messageText } ?: messageText
                                            viewModel.sendMessage(finalText)
                                            messageText = ""
                                            quotedMessage = null
                                            showAttachmentPanel = false
                                        }
                                    }
                                )
                            )

                            // + 号：展开/收起附件面板（相册、文件等）
                            IconButton(
                                onClick = { showAttachmentPanel = !showAttachmentPanel },
                                enabled = !uiState.isSending
                            ) {
                                Icon(
                                    imageVector = if (showAttachmentPanel) Icons.Default.Close else Icons.Default.Add,
                                    contentDescription = "更多"
                                )
                            }

                            // 发送
                            IconButton(
                                onClick = {
                                    if (messageText.isNotBlank() || quotedMessage != null) {
                                        forceScroll = true
                                        val finalText = quotedMessage?.let { buildQuote(it) + messageText } ?: messageText
                                        viewModel.sendMessage(finalText)
                                        messageText = ""
                                        quotedMessage = null
                                        showAttachmentPanel = false
                                    }
                                },
                                enabled = (messageText.isNotBlank() || quotedMessage != null) && !uiState.isSending
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送")
                            }
                        }

                        // 文件/视频发送进度条（发送中显示，发送完成自动消失）
                        fileProgress?.let { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (p < 0.2f) "加密中 ${(p * 100).toInt()}%" else "发送中 ${(p * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 附件面板：点击 + 后展开，参考微信更多面板
                        AnimatedVisibility(visible = showAttachmentPanel) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            ) {
                                Text(
                                    text = "附件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    AttachmentItem(
                                        icon = Icons.Default.Image,
                                        label = "相册",
                                        onClick = {
                                            showAttachmentPanel = false
                                            imagePicker.launch("image/*")
                                        }
                                    )
                                    AttachmentItem(
                                        icon = Icons.Default.AttachFile,
                                        label = "文件",
                                        onClick = {
                                            showAttachmentPanel = false
                                            filePicker.launch("*/*")
                                        }
                                    )
                                }
                                val cm = ServiceLocator.callManager
                                if (cm != null && recipientId != null) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "通话",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                                    ) {
                                        AttachmentItem(
                                            icon = Icons.Default.Call,
                                            label = "语音通话",
                                            onClick = {
                                                showAttachmentPanel = false
                                                audioCallLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        )
                                        AttachmentItem(
                                            icon = Icons.Default.Videocam,
                                            label = "视频通话",
                                            onClick = {
                                                showAttachmentPanel = false
                                                videoCallLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.RECORD_AUDIO,
                                                        Manifest.permission.CAMERA
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 单条消息操作面板（长按弹出）
    actionTarget?.let { target ->
        val hasText = target.text != null
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null }
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                // 复制
                ListItem(
                    headlineContent = { Text("复制") },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                    modifier = Modifier.clickable(enabled = hasText) {
                        target.text?.let { clipboard.setText(AnnotatedString(it)) }
                        scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
                        actionTarget = null
                    }
                )
                // 引用（支持文本/图片；结构化保存到 quotedMessage，发送时自动拼到正文前）
                ListItem(
                    headlineContent = { Text("引用") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        scope.launch {
                            val preview = viewModel.resolveQuoteText(target)
                            quotedMessage = target.copy(text = preview)
                            actionTarget = null
                        }
                    }
                )
                // 多选
                ListItem(
                    headlineContent = { Text("多选") },
                    leadingContent = {
                        Icon(Icons.Default.Check, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        viewModel.enterSelection(target.message.id)
                        actionTarget = null
                    }
                )
                // 删除
                ListItem(
                    headlineContent = { Text("删除") },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        confirmDeleteIds = listOf(target.message.id)
                        actionTarget = null
                    }
                )
            }
        }
    }

    // 图片放大查看（捏合缩放 / 双击放大还原 / 拖拽平移；发送与接收图片通用）
    zoomedBytes?.let { bytes ->
        Dialog(
            onDismissRequest = { zoomedBytes = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bmp?.let {
                    ZoomableImage(
                        bitmap = it,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 关闭按钮（右上角），点击直接退出预览
                IconButton(
                    onClick = { zoomedBytes = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭预览",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 可缩放图片查看器：捏合缩放（1x~5x）、双击在 1x/2.5x 间切换、拖拽平移。
 * 单击图片不关闭（避免误触），通过右上角关闭按钮或返回键退出。
 */
@Composable
private fun ZoomableImage(
    bitmap: android.graphics.Bitmap,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "图片预览",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .transformable(transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { /* 单击图片不关闭，避免误触 */ },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                )
            }
    )
}

private fun formatTime(ts: Long): String {
    return try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } catch (e: Exception) {
        ""
    }
}

/**
 * 解析带引用标记的消息文本。
 * 新格式：\u0001发送者：内容\u0001正文
 * 旧格式兼容：「发送者：内容」\n正文
 * 返回 (引用文本, 回复正文)。
 */
private fun splitQuoteAndBody(text: String): Pair<String?, String> {
    if (text.startsWith("\u0001")) {
        val parts = text.split("\u0001")
        if (parts.size >= 3) {
            val quote = parts[1].takeIf { it.isNotBlank() }
            val body = parts.drop(2).joinToString("\u0001")
            return quote to body
        }
    }
    val regex = Regex("^「(.+?)」\\n(.*)$", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(text)
    if (match != null) {
        val quote = match.groupValues[1].takeIf { it.isNotBlank() }
        val body = match.groupValues[2]
        return quote to body
    }
    return null to text
}

/**
 * 微信式引用块：左侧灰竖条 + 引用文本（最多两行省略）。
 */
@Composable
private fun QuoteBlock(quoteText: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Text(
            text = quoteText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
    }
}

@Composable
fun MessageBubble(
    displayItem: DisplayMessage,
    viewModel: MessageViewModel,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    onImageClick: (ByteArray) -> Unit = {}
) {
    val isOwn = displayItem.isOwn
    val msg = displayItem.message
    val isImage = msg.messageType == MessageType.IMAGE
    val isCall = msg.messageType == MessageType.CALL
    val isFile = msg.messageType == MessageType.FILE
    val context = LocalContext.current

    // 懒解密：仅当前可见气泡触发，后台并行解密（每条走 AndroidKeyStore RSA 解信封 ~20ms），
    // 几十 ms 内填充真实内容；屏幕外气泡不解密、进会话首帧不解密 → 窗口秒开、点击不卡。
    var text by remember(msg.id) { mutableStateOf<String?>(null) }
    var imageBytes by remember(msg.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(msg.id) {
        if (isCall || isFile) return@LaunchedEffect  // 文件内容下载时才解密，文件名已明文，无需提前解密
        if (isImage) imageBytes = viewModel.decryptImageIfNeeded(msg.encryptedContent, msg.id)
        else text = viewModel.decryptTextIfNeeded(msg.encryptedContent, msg.id)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isCall) Alignment.Center else if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (isCall) {
            // 通话记录：居中灰色提示，明文存储、不解密
            Text(
                text = msg.encryptedContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .then(if (isOwn) Modifier.shadow(4.dp) else Modifier)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.large
                        )
                    } else Modifier
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (isOwn) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isFile) {
                    FileMessageContent(msg = msg, viewModel = viewModel, isOwn = isOwn)
                } else if (imageBytes != null) {
                    // 图片消息
                    val bytes = imageBytes!!
                    val bitmap = remember(bytes) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    bitmap?.let {
                        val imgScope = rememberCoroutineScope()
                        var imgBusy by remember(msg.id) { mutableStateOf(false) }
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "图片消息",
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    enabled = !selectionMode,
                                    onClick = { onImageClick(bytes) },
                                    onLongClick = {
                                        if (imgBusy) return@combinedClickable
                                        imgBusy = true
                                        imgScope.launch(Dispatchers.IO) {
                                            val ok = viewModel.saveImageToDownloads(
                                                bytes,
                                                msg.fileName ?: "image_${msg.id}.jpg"
                                            )
                                            withContext(Dispatchers.Main) {
                                                imgBusy = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (ok) "原图已保存到下载" else "保存失败",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                ),
                            contentScale = ContentScale.FillWidth
                        )
                    } ?: Text(
                        "🔒 [图片解密失败]",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (isImage) {
                    Text(
                        "🔒 [图片解密中...]",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (text == null) {
                    Text(
                        text = "🔒 解密中...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    val t = text ?: ""
                    val (quote, body) = remember(t) { splitQuoteAndBody(t) }
                    Column {
                        quote?.let {
                            QuoteBlock(quoteText = it)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (body.isNotBlank()) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                // 时间戳 + 已读状态（仅自己发的消息显示状态）
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        text = formatTime(displayItem.message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isOwn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val statusText = when {
                            !displayItem.message.isSent -> "发送中"
                            displayItem.message.isRead -> "已读"
                            else -> "已送达"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (displayItem.message.isRead)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 文件消息气泡内容（视频可播放/保存，文档/其他可下载）──
@Composable
private fun FileMessageContent(
    msg: Message,
    viewModel: MessageViewModel,
    isOwn: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(msg.id) { mutableStateOf(false) }
    val fileId = msg.mediaUrl
    val fileName = msg.fileName ?: "文件"
    val fileMime = msg.fileMime ?: "application/octet-stream"
    val isVideo = fileMime.startsWith("video/")
    val canOpen = !fileId.isNullOrBlank()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isVideo) Icons.Default.PlayCircle
                else if (fileMime.startsWith("image/")) Icons.Default.Image
                else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(msg.fileSize)} · ${fileMime.substringAfter('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else if (canOpen) {
                if (isVideo) {
                    Button(onClick = {
                        launchFile(viewModel, scope, context, fileId!!, fileName, "video/*") { busy = it }
                    }) { Text("▶ 播放") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = {
                    launchFile(viewModel, scope, context, fileId!!, fileName, fileMime) { busy = it }
                }) { Text(if (isVideo) "⤓ 保存" else "⤓ 下载") }
            } else {
                Text(
                    "文件不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun launchFile(
    viewModel: MessageViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    fileId: String,
    fileName: String,
    openMime: String,
    setBusy: (Boolean) -> Unit
) {
    setBusy(true)
    scope.launch(Dispatchers.IO) {
        val ok = viewModel.downloadAndOpenFile(fileId, fileName, openMime)
        withContext(Dispatchers.Main) {
            setBusy(false)
            android.widget.Toast.makeText(
                context,
                if (ok) "已打开" else "下载或解密失败",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 在 edge-to-edge 模式下（setDecorFitsSystemWindows(false)）测量键盘高度原始值。
 *
 * 原理：edge-to-edge 开启后 decorView 延伸到系统栏和键盘后面。
 * getWindowVisibleDisplayFrame() 返回的是「未被任何系统UI（含键盘）遮挡的可见区域」，
 * 键盘弹出时该区域底部上移，全屏高度 − 可见底部 = 键盘像素高度。
 *
 * 注意：返回的是**原始测量值**，调用方应自行施加封顶（1.0.34）。
 * ColorOS/安卓16 上此值可能虚高（如 996px），需用屏幕宽度百分比截断。
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

/**
 * 附件面板中的单一项（图标 + 文字），用于微信式「+」展开面板。
 */
@Composable
private fun AttachmentItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(enabled = true, onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
