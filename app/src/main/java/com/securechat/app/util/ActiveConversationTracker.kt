package com.securechat.app.util

/**
 * 跟踪「当前在前台且正在查看的聊天会话」，供推送层决定是否抑制新消息通知。
 *
 * 业务规则（来自产品需求）：
 *  - A 给 B 发消息时，若 B 正停留在该会话（A）的聊天详情页，则 B 不收到新消息提醒；
 *  - 若此时 C 给 B 发消息（非当前会话），则照常提醒；
 *  - 若 B 已退出该会话（回到列表/切到别的会话/App 退到后台），则 A 的消息也要提醒；
 *  - 语音 / 视频通话走 CallService 独立通道（CATEGORY_CALL + IMPORTANCE_HIGH），
 *    始终提醒，不受本规则影响。
 *
 * 设计要点：
 *  - openConversationId 由聊天详情页在组合进入时写入、在生命周期 ON_RESUME 时补登记、
 *    真正离开（dispose）且「当前登记的仍是自己」时才清空；
 *  - appInForeground 由 MainActivity 的 onResume/onPause 维护，是唯一的「后台」闸门；
 *  - 两条件同时成立才抑制，任一不满足就照常弹通知。
 *
 * 修复记录（1.0.95）：
 *  旧实现有两处会导致「人就在聊天窗口里，对方来消息却仍弹通知」：
 *  ① setAppForeground(false) 顺带把 openConversationId 清成 null —— 而聊天页仍压在栈顶、
 *     Compose 组合并未销毁，DisposableEffect 不会再跑，于是从后台切回前台后登记永久丢失；
 *     实际上抑制判定已含 appInForeground 条件，后台天然不会抑制，这次清空纯属多余且有害。
 *  ② 旧页面的 onDispose 无条件 setOpenConversation(null) —— 会话间跳转时若旧页面的
 *     dispose 晚于新页面的登记执行，会把刚登记好的新会话误清成 null。
 */
object ActiveConversationTracker {

    @Volatile
    var openConversationId: String? = null
        private set

    @Volatile
    var appInForeground: Boolean = false
        private set

    /** 聊天详情页进入时调用，登记当前会话。 */
    fun setOpenConversation(conversationId: String?) {
        openConversationId = conversationId
    }

    /**
     * 仅当「当前登记的会话正是这一个」时才清空。
     * 避免旧页面 dispose 晚于新页面登记时，把新会话的登记误清掉。
     */
    fun clearConversationIfCurrent(conversationId: String?) {
        if (!conversationId.isNullOrBlank() && openConversationId == conversationId) {
            openConversationId = null
        }
    }

    /**
     * App 前后台切换时调用。
     *
     * 注意：退到后台「不清空」openConversationId —— 抑制判定本身已要求 appInForeground，
     * 后台必然不会抑制；保留登记才能在切回前台、聊天页仍在栈顶时立刻恢复抑制，
     * 否则从后台返回后停留在聊天窗口内也会弹通知（1.0.95 修复）。
     */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    /**
     * 是否应抑制该会话的新消息通知。
     * 仅当 App 在前台 且 用户正停留在该会话详情页 时返回 true（普通消息）。
     * 语音 / 视频通话不走此处，始终提醒。
     */
    fun shouldSuppressNotification(conversationId: String): Boolean {
        if (!appInForeground) return false
        val open = openConversationId ?: return false
        if (conversationId.isBlank()) return false
        return open == conversationId
    }
}
