package com.securechat.app.di

import android.content.Context
import androidx.room.Room
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.FriendDao
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.MessageDao
import com.securechat.app.data.local.MessageDatabase
import com.securechat.app.data.local.MIGRATION_4_5
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideMessageDatabase(@ApplicationContext ctx: Context): MessageDatabase {
        return Room.databaseBuilder(
            ctx,
            MessageDatabase::class.java,
            "securechat_db"
        )
            // 一次性破坏性重建（仅针对被 1.0.58 损坏的 version 2/3 旧库），
            // 让所有设备 messages 表 schema 统一为当前期望（snake_case 文件字段），
            // 杜绝 1.0.60 在"camelCase + snake_case 双列"旧库上的迁移崩溃。
            // 注意：破坏性回退仅限定在 2/3 版；未来从 version 4 改表必须写显式 Migration，
            // 否则 Room 会直接崩溃暴露问题，不会再次静默清空数据。
            .fallbackToDestructiveMigrationFrom(2, 3)
            .addMigrations(MIGRATION_4_5)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideMessageDao(database: MessageDatabase): MessageDao {
        return database.messageDao()
    }
    
    @Provides
    @Singleton
    fun provideConversationDao(database: MessageDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    @Singleton
    fun provideFriendRequestDao(database: MessageDatabase): FriendRequestDao {
        return database.friendRequestDao()
    }

    @Provides
    @Singleton
    fun provideFriendDao(database: MessageDatabase): FriendDao {
        return database.friendDao()
    }
}
