package com.securechat.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.data.model.Message
import com.securechat.app.data.model.MessageType
import com.securechat.app.file.FileTransferHelper
import com.securechat.app.network.push.PushConnectionService
import com.securechat.app.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 单条消息的展示模型：文本或图片二选一。
 */
data class DisplayMessage(
    val message: Message,
    val isOwn: Boolean,
    val text: String? = null,
    val imageBytes: ByteArray? = null
)

/**
 * 解密内容缓存：只缓存解密出的文本/图片字节。
 * 注意：isRead/isSent 等状态始终从实时 [Message] 读取，不缓存进 DisplayMessage，
 * 否则数据库里「已读」状态变化后 UI 不刷新（已读回执不生效的隐藏根因）。
 */
private data class DecryptedContent(
    val text: String? = null,
    val imageBytes: ByteArray? = null
)

/**
 * UI state for message detail screen.
 * displayItems carries decoded content (text or image) for rendering.
 */
data class MessageDetailUiState(
    val messages: List<Message> = emptyList(),
    val displayItems: List<DisplayMessage> = emptyList(),
    val isSending: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet()
)

/**
 * ViewModel for individual message/conversation detail screen.
 */
@HiltViewModel
class MessageViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val messageEncryptor: MessageEncryptor,
    private val fileTransfer: FileTransferHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessageDetailUiState())
    val uiState: StateFlow<MessageDetailUiState> = _uiState.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    private val _recipientId = MutableStateFlow<String?>(null)
    val recipientId: StateFlow<String?> = _recipientId.asStateFlow()

    // 文件/视频发送进度（0f..1f），null 表示当前没有正在发送的文件
    private val _fileSendProgress = MutableStateFlow<Float?>(null)
    val fileSendProgress: StateFlow<Float?> = _fileSendProgress.asStateFlow()

    // 解密内容缓存：避免每次 Flow 发射都对所有消息重新解密（性能优化）
    // 用 ConcurrentHashMap：解密在 Dispatchers.Default 多线程池访问，需线程安全
    private val decryptedCache = java.util.concurrent.ConcurrentHashMap<String, DecryptedContent>()

    /**
     * Open a conversation by ID.
     * @param conversationId the conversation ID (used for DB query)
     * @param recipientId the other user's ID (used for encryption recipient)
     */
    fun openConversation(conversationId: String, recipientId: String? = null) {
        _selectedConversationId.value = conversationId
        _recipientId.value = recipientId
        // 注意：不清除 decryptedCache —— key 用 message id，跨会话不冲突。
        // 保留缓存可让「重新打开同一会话」秒开，且消息越多不会越转越久（解密结果复用）。
        loadMessages(conversationId)
        // 打开会话即标记已读，清零未读角标；并通知对方「已读」（让对方看到已读状态）
        viewModelScope.launch {
            repository.markConversationRead(conversationId)
            val reader = repository.localUserId
            val partner = recipientId
            if (!partner.isNullOrBlank() && !reader.isNullOrBlank()) {
                PushConnectionService.sendReadReceipt(conversationId, reader, partner)
            }
        }
    }

    private fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            repository.getConversationMessages(conversationId)
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
                }
                .collect { messages ->
                    val myId = repository.localUserId

                    // 关键性能改动（1.0.18）：进会话时【不再预解密】任何消息。
                    // 原因：每条解密都要走 AndroidKeyStore 做 RSA 私钥解信封（~20ms/条），
                    // 之前在主线程同步解密整段历史（50 条≈1s）会阻塞导航首帧 → 「点击进窗口卡 1 秒」。
                    // 现在只构造占位 DisplayMessage（text/imageBytes 为 null），首帧极轻 → 窗口秒开、点击不卡。
                    // 真实内容由 MessageBubble 对【仅可见气泡】做后台懒解密（并行、每条几十 ms），
                    // 解密结果写入 decryptedCache 复用。窗口秒开 + 可见消息几十 ms 真实显示（近秒显），
                    // 且屏幕外消息不解密，无全量开销。
                    val display = messages.map { msg ->
                        DisplayMessage(msg, msg.senderId == myId, null, null)
                    }
                    _uiState.update {
                        it.copy(
                            messages = messages.reversed(),
                            displayItems = display.reversed(),
                            isLoading = false
                        )
                    }
                }
        }
    }

    /**
     * 懒解密文本：仅对「即将渲染的可见气泡」调用。
     * 优先命中 decryptedCache（二次进入同会话零解密、真正秒显）；
     * 未命中则在 Dispatchers.Default 后台解密并写回缓存，绝不占用主线程 → 点击进窗口不卡。
     */
    suspend fun decryptTextIfNeeded(payload: String, id: String): String? {
        decryptedCache[id]?.text?.let { return it }
        return withContext(Dispatchers.Default) {
            val t = messageEncryptor.decryptMessage(payload) ?: "[无法解密的消息]"
            val prev = decryptedCache[id]
            decryptedCache[id] = (prev ?: DecryptedContent()).copy(text = t)
            t
        }
    }

    /**
     * 懒解密图片字节：同上，仅可见图片气泡调用。
     */
    suspend fun decryptImageIfNeeded(payload: String, id: String): ByteArray? {
        decryptedCache[id]?.imageBytes?.let { return it }
        return withContext(Dispatchers.Default) {
            val b = messageEncryptor.decryptBytes(payload)
            val prev = decryptedCache[id]
            decryptedCache[id] = (prev ?: DecryptedContent()).copy(imageBytes = b)
            b
        }
    }

    /**
     * 为引用预览/发送前构造真实文本。
     * 因 DisplayMessage.text 初始为 null（懒解密），引用时必须从 payload 主动解密一次。
     * 图片消息直接返回 "[图片]"，复用 decryptedCache 避免重复解密。
     */
    suspend fun resolveQuoteText(item: DisplayMessage): String {
        return when (item.message.messageType) {
            MessageType.IMAGE -> "[图片]"
            else -> {
                val payload = item.message.encryptedContent
                val id = item.message.id
                decryptedCache[id]?.text?.let { return it }
                withContext(Dispatchers.Default) {
                    val t = messageEncryptor.decryptMessage(payload) ?: "[无法解密的消息]"
                    val prev = decryptedCache[id]
                    decryptedCache[id] = (prev ?: DecryptedContent()).copy(text = t)
                    t
                }
            }
        }
    }

    /**
     * Send a message to the current conversation.
     * Uses the recipientId set by openConversation().
     * Encrypts with recipient's RSA public key and sends to server via HTTP.
     */
    fun sendMessage(plaintext: String) {
        if (plaintext.isBlank()) return

        val convId = _selectedConversationId.value
        val rcpt = _recipientId.value

        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(error = "对话未选择") }
            return
        }
        if (rcpt.isNullOrBlank()) {
            _uiState.update { it.copy(error = "收件人未指定") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null) }

            try {
                val result = repository.sendEncryptedMessage(
                    plaintext = plaintext,
                    conversationId = convId,
                    recipientId = rcpt
                )

                result.onSuccess {
                    // Reload messages to show the newly sent message
                    loadMessages(convId)
                    _uiState.update { it.copy(isSending = false) }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = e.message ?: "发送失败"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = e.message ?: "发送失败"
                    )
                }
            }
        }
    }

    /**
     * 发送一张图片：压缩 -> 端到端加密 -> 上传。
     */
    fun sendImage(uri: Uri) {
        val convId = _selectedConversationId.value
        val rcpt = _recipientId.value

        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(error = "对话未选择") }
            return
        }
        if (rcpt.isNullOrBlank()) {
            _uiState.update { it.copy(error = "收件人未指定") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null) }
            try {
                val result = repository.sendEncryptedImage(uri, convId, rcpt)
                result.onSuccess {
                    loadMessages(convId)
                    _uiState.update { it.copy(isSending = false) }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = e.message ?: "图片发送失败"
                        )
                    }
                }
                } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = e.message ?: "图片发送失败"
                    )
                }
            }
        }
    }

    /**
     * 发送一个文件：加密 -> 上传 -> 发送 FILE 消息。
     */
    fun sendFile(uri: Uri) {
        val convId = _selectedConversationId.value
        val rcpt = _recipientId.value

        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(error = "对话未选择") }
            return
        }
        if (rcpt.isNullOrBlank()) {
            _uiState.update { it.copy(error = "收件人未指定") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, error = null) }
            _fileSendProgress.value = 0f
            try {
                val result = repository.sendEncryptedFile(uri, convId, rcpt) { p ->
                    _fileSendProgress.value = p
                }
                result.onSuccess {
                    loadMessages(convId)
                    _uiState.update { it.copy(isSending = false) }
                    _fileSendProgress.value = null
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = e.message ?: "文件发送失败"
                        )
                    }
                    _fileSendProgress.value = null
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = e.message ?: "文件发送失败"
                    )
                }
                _fileSendProgress.value = null
            }
        }
    }

    // ===== 消息操作：复制 / 引用 / 多选 / 删除 =====

    /** 进入多选模式，并默认选中指定消息。 */
    fun enterSelection(messageId: String) {
        _uiState.update { it.copy(selectionMode = true, selectedIds = setOf(messageId)) }
    }

    /** 切换某条消息的选中状态；若全部取消则退出多选模式。 */
    fun toggleSelection(messageId: String) {
        _uiState.update {
            val sel = it.selectedIds.toMutableSet()
            if (sel.contains(messageId)) sel.remove(messageId) else sel.add(messageId)
            if (sel.isEmpty()) it.copy(selectionMode = false, selectedIds = emptySet())
            else it.copy(selectedIds = sel)
        }
    }

    /** 全选当前会话所有消息。 */
    fun selectAll(ids: List<String>) {
        _uiState.update { it.copy(selectedIds = ids.toSet()) }
    }

    /** 退出多选模式。 */
    fun exitSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    /** 删除单条消息（本地）。 */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            decryptedCache.remove(messageId)
            _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        }
    }

    /** 批量删除选中的消息（本地）。 */
    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            repository.deleteMessages(ids)
            ids.forEach { decryptedCache.remove(it) }
            _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        }
    }

    /**
     * 下载并打开一个文件消息（视频/文档/图片均走系统查看器）。
     * @return true 表示成功触发打开，false 表示下载或解密失败。
     */
    suspend fun downloadAndOpenFile(fileId: String, fileName: String, fileMime: String): Boolean {
        val file = fileTransfer.fetchDecryptSave(fileId, fileName, fileMime) ?: return false
        fileTransfer.openInSystemViewer(file, fileMime)
        return true
    }

    /**
     * 把已解密的图片字节保存为原图到 Downloads 并用系统查看器打开。
     */
    suspend fun saveImageToDownloads(bytes: ByteArray, fileName: String): Boolean {
        val file = fileTransfer.saveBytes(bytes, fileName) ?: return false
        fileTransfer.openInSystemViewer(file, "image/*")
        return true
    }
}
