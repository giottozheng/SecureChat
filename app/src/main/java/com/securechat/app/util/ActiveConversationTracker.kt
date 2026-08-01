package com.securechat.app.util

/**
 * 跟踪「当前在前台且正在查看的聊天会话」，供推送层决定是否抑制新消息通知。
 *
 * 业务规则（来自产品需求）：
 *  - A 给 B 发消息时，若 B 正停留在该会话（A）的聊天详情页，则 B 不收到新消息提醒；
 *  - 若此时 C 给 B 发消息（非当前会话），则照常提醒；
 *  - 若 B 已退出该会话（回到列表/切到别的会话/App 退到后台），则 A 的消息也要提醒；
 *  - 语音 / 视频通话走 CallManager 独立通道（高优先级 heads-up），始终提醒，不受本规则影响。
 *
 * 设计要点：
 *  - openConversationId 由聊天详情页在组合进入时写入、离开（dispose）时清空；
 *  - appInForeground 由 MainActivity 的 onResume/onPause 维护，App 退到后台即清空当前会话，
 *    避免「人走了但还压着会话」导致后台消息被误抑制。
 *  - 两条件同时成立才抑制，任一不满足就照常弹通知。
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

    /** App 前后台切换时调用。退到后台即视为「离开聊天窗口」，清空当前会话。 */
    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
        if (!foreground) {
            openConversationId = null
        }
    }

    /**
     * 是否应抑制该会话的新消息通知。
     * 仅当 App 在前台 且 用户正停留在该会话详情页 时返回 true（普通消息）。
     */
    fun shouldSuppressNotification(conversationId: String): Boolean {
        return appInForeground && openConversationId == conversationId
    }
}
