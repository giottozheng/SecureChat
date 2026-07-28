package com.securechat.app.data.model

import java.io.Serializable

/**
 * Represents a chat conversation participant.
 */
data class User(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = false
) : Serializable

/**
 * Represents a single chat conversation between users.
 */
data class Conversation(
    val id: String,
    val participants: List<String>,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val name: String? = null  // null for DM, group name for chats
) : Serializable

/**
 * Represents a single chat message (encrypted payload).
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val encryptedContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean = false,
    val isRead: Boolean = false,
    val messageType: MessageType = MessageType.TEXT,
    val mediaUrl: String? = null,
    // 文件消息元数据（明文：文件名/类型/大小，非文件内容；内容经端到端加密后以 mediaUrl=fileId 引用）
    val fileName: String? = null,
    val fileMime: String? = null,
    val fileSize: Long = 0
) : Serializable

/**
 * Message type enum.
 */
enum class MessageType {
    TEXT, IMAGE, VOICE, FILE, SYSTEM, CALL
}

/**
 * WebSocket message protocol from push server.
 */
data class WSMessage(
    val type: String? = null,
    val payload: String? = null,
    val senderId: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null
)

/**
 * Push notification from self-built push server.
 */
data class PushNotification(
    val userId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String? = null,
    val preview: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
