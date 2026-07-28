package com.securechat.app.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for encrypted messages persisted locally.
 * All content is encrypted — zero plaintext at rest.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    @ColumnInfo(name = "encrypted_content") val encryptedContent: String,
    val timestamp: Long,
    val isEncrypted: Boolean = true,
    val isRead: Boolean = false,
    val isSent: Boolean = false,
    val messageType: String = "TEXT",
    // 文件消息元数据（明文存储，便于列表/离线显示；mediaUrl 存 fileId 供下载解密）
    // 注意：列名必须与迁移 MIGRATION_2_3 及 1.0.58 已重建的线上库保持一致（snake_case），
    // 否则 Room 迁移校验 Expected(camelCase) != Found(snake_case) 会抛 IllegalStateException 致 App 启动崩溃。
    @ColumnInfo(name = "media_url") val mediaUrl: String? = null,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "file_mime") val fileMime: String? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long = 0
)

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    fun getMessagesByConversation(convId: String, limit: Int = 50, offset: Int = 0): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE isRead = 0 AND recipientId = :userId ORDER BY timestamp DESC")
    fun getUnreadMessages(userId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
    
    // 对方回复即视为已读：把会话中「自己发出的消息」标记为已读
    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :convId AND senderId = :myId")
    suspend fun markOwnMessagesRead(convId: String, myId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
    
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversationMessages(conversationId: String)

    // ── 数据保留：删除早于 cutoff 的聊天记录（默认 1 天）──
    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    suspend fun deleteMessagesOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0 AND recipientId = :userId")
    fun getUnreadCount(userId: String): Flow<Int>
}

/**
 * Room entity for conversations.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val participantIds: String,  // JSON array string, e.g. "[\"u1\",\"u2\"]"
    val lastMessageId: String? = null,
    val lastMessagePreview: String? = null,
    val unreadCount: Int = 0,
    val updatedAt: Long,
    val name: String? = null
)

@Dao
interface ConversationDao {
    
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getConversation(id: String): Flow<ConversationEntity?>
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationSync(id: String): ConversationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)
    
    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    // ── 数据保留：删除早于 cutoff 且无近期消息的会话（默认 1 天）──
    @Query("DELETE FROM conversations WHERE updatedAt < :cutoff")
    suspend fun deleteConversationsOlderThan(cutoff: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnread(conversationId: String)
}

/**
 * Room entity for incoming friend requests (pending contact invitations).
 * Stored locally so requests survive app restarts / missed push.
 */
@Entity(tableName = "friend_requests")
data class FriendRequestEntity(
    @PrimaryKey val fromId: String,
    val fromName: String,
    val toId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending" // pending | accepted | rejected
)

@Dao
interface FriendRequestDao {
    
    @Query("SELECT * FROM friend_requests WHERE status = 'pending' ORDER BY timestamp DESC")
    fun getPendingRequests(): Flow<List<FriendRequestEntity>>
    
    @Query("SELECT * FROM friend_requests WHERE fromId = :fromId")
    suspend fun getRequest(fromId: String): FriendRequestEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(request: FriendRequestEntity)
    
    @Query("UPDATE friend_requests SET status = :status WHERE fromId = :fromId")
    suspend fun setStatus(fromId: String, status: String)
    
    @Query("DELETE FROM friend_requests WHERE fromId = :fromId")
    suspend fun deleteRequest(fromId: String)
}

/**
 * Room entity for accepted friends (contacts list source of truth).
 * Populated from server /api/contacts (friends only), not hardcoded.
 */
@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val publicKey: String,
    val isOnline: Boolean = false,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY displayName ASC")
    fun getFriends(): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFriends(friends: List<FriendEntity>)

    @Query("DELETE FROM friends WHERE userId = :userId")
    suspend fun deleteFriend(userId: String)

    @Query("DELETE FROM friends")
    suspend fun clearFriends()
}

/**
 * Room database instance for SecureChat.
 * Holds all encrypted message data + friends.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        FriendRequestEntity::class,
        FriendEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MessageDatabase : RoomDatabase() {
    
    abstract fun messageDao(): MessageDao
    
    abstract fun conversationDao(): ConversationDao
    
    abstract fun friendRequestDao(): FriendRequestDao

    abstract fun friendDao(): FriendDao
}

/**
 * 数据库版本 = 4。
 *
 * ⚠️ 一次性的破坏性重建（fallbackToDestructiveMigrationFrom(2, 3)）只为修复 1.0.58 的
 * schema 损坏事故：当年漏升版本号 + fallbackToDestructiveMigration，导致部分设备库的
 * messages 表同时残留 camelCase(mediaUrl…) 与 snake_case(media_url…) 两套文件字段列，
 * 1.0.60 的 MIGRATION_2_3 在其上再加一列后 Room 校验 Expected≠Found 直接崩溃（见
 * data/crash_reports）。对 version 2/3 的旧库做一次干净重建即可让所有设备 schema 一致，
 * 本地已丢失的聊天/好友会由服务端 /api/contacts 与 WS offline_sync 重新拉回。
 *
 * 🔒 铁律（未来必须遵守）：凡改动 @Entity 字段/表结构，必须
 *   ① 升 version；② 写对应 Migration(old, new)；③ 绝不能再让 (2,3) 之外的版本走破坏性回退。
 *   fallbackToDestructiveMigrationFrom(2, 3) 已把破坏性回退**仅限定**在 2/3 版，
 *   未来若从 4 改表却漏写迁移，Room 会直接启动崩溃暴露问题，而不是静默清空数据。
 */

/**
 * 数据库迁移 4 → 5：好友表新增 avatar_url 列（头像相对路径，可空）。
 * 对应 FriendEntity.avatarUrl 字段（已用 @ColumnInfo(name="avatar_url") 显式指定蛇形列名，
 * 否则 Room 默认按字段名生成驼峰列名 avatarUrl，与下方迁移创建的 avatar_url 不一致，
 * 会在迁移校验时抛 IllegalStateException 致 App 启动/打开联系人即闪退——即 1.0.65 的回归 bug）。
 *
 * 防御式：先查 PRAGMA table_info 判断 avatar_url 是否已存在。
 * 原因：迁移校验失败会抛异常，但 ALTER 可能已在磁盘上提交（version 未升）。
 * 若直接再次 ALTER 会报 "duplicate column: avatar_url" 二次崩溃。
 * 已存在则跳过，仅让 Room 完成校验即可。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query("PRAGMA table_info(friends)")
        var exists = false
        try {
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "avatar_url") {
                    exists = true
                    break
                }
            }
        } finally {
            cursor.close()
        }
        if (!exists) {
            db.execSQL("ALTER TABLE friends ADD COLUMN avatar_url TEXT")
        }
    }
}
