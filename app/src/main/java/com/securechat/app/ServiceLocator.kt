package com.securechat.app

import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.data.local.ConversationDao
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.MessageDao

/**
 * ServiceLocator — 依赖中转单例
 *
 * 问题背景：Hilt 的 @AndroidEntryPoint 在 Service 上注入时，运行时系统直接实例化
 * Service 原始类（需要零参构造），而 Hilt 生成的子类未被用来替代它，
 * 导致 "has no zero argument constructor" 崩溃。
 *
 * 解决方案：由 @HiltAndroidApp 的 Application 在 onCreate() 中把单例依赖注入并写入本对象，
 * PushConnectionService（普通 Service，无 Hilt 注入）在运行时直接读取本对象。
 * 这样完全绕开 Hilt 对 Service 的构造注入，保证 Service 拥有隐式零参构造。
 */
object ServiceLocator {

    lateinit var messageEncryptor: MessageEncryptor
        private set

    lateinit var messageDao: MessageDao
        private set

    lateinit var conversationDao: ConversationDao
        private set

    lateinit var friendRequestDao: FriendRequestDao
        private set

    // 通话管理器（由 SecureChatApplication 注入后赋值；PushConnectionService 经此回调 call_signal）
    var callManager: com.securechat.app.call.CallManager? = null

    fun init(
        encryptor: MessageEncryptor,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        friendRequestDao: FriendRequestDao
    ) {
        messageEncryptor = encryptor
        this.messageDao = messageDao
        this.conversationDao = conversationDao
        this.friendRequestDao = friendRequestDao
    }

    fun isInitialized(): Boolean =
        ::messageEncryptor.isInitialized && ::messageDao.isInitialized &&
            ::conversationDao.isInitialized && ::friendRequestDao.isInitialized
}
