package com.securechat.app.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * 对端公钥版本号共享表（进程内单例）。
 *
 * 用途：解决「密钥变更后对端仍用缓存旧公钥加密 → 本端无法解密」的问题。
 * - [AccountRepository.fetchFriends] 在拉取好友列表时把每个好友的 keyEpoch 写入此处；
 * - [MessageRepository.getRecipientPublicKey] 发送前比对缓存公钥的 epoch 与最新已知 epoch，
 *   若不一致则重新从服务端拉取，确保始终用对方当前公钥加密。
 *
 * 这样当用户点击「重新同步密钥」导致服务端 keyEpoch 自增后，对端在下一次发送时会
 * 检测到 epoch 变化并自动重新拉取新公钥，无需对方手动重启 App。
 */
object PeerKeyEpochStore {
    private val epochs = ConcurrentHashMap<String, Long>()

    /** 用好友列表（userId -> keyEpoch）批量更新 */
    fun update(friends: Map<String, Long>) {
        epochs.putAll(friends)
    }

    /** 单条更新（如从 /public-key 接口拿到最新 epoch） */
    fun set(id: String, epoch: Long) {
        epochs[id] = epoch
    }

    fun get(id: String): Long? = epochs[id]

    fun clear() = epochs.clear()
}
