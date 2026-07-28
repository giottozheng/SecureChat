package com.securechat.app.util

/**
 * 全局好友头像缓存：userId -> 头像相对路径（如 /avatars/user-5.jpg）。
 *
 * 由 AccountRepository.fetchFriends / fetchAndCacheContacts 拉取联系人时填充。
 * 会话列表等需要展示「对方」头像的场景从此处读取，避免改动 Conversation 持久化模型。
 * 注意：头像 URL 需在调用处拼接服务端 baseUrl 才能加载。
 */
var remoteAvatarOverrides: MutableMap<String, String> = mutableMapOf()

fun getCachedAvatarUrl(userId: String): String? = remoteAvatarOverrides[userId]
