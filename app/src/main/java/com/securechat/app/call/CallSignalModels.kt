package com.securechat.app.call

/**
 * 通话相关模型与信令定义。
 *
 * 信令复用现有 WS 推送通道（PushConnectionService），新增 type=call_signal，
 * 服务端按 data.to 原样转发，不解析 payload 内部。
 */

/** 通话类型：语音 / 视频 */
enum class CallType { AUDIO, VIDEO }

/**
 * 通话 UI 状态机。
 *
 * Idle → OutgoingRing（我方发起）/ IncomingRing（我方被叫）
 *      → Connected（媒体链路建立）
 *      → Ended（任意一方挂断/拒接/未接通/忙线）
 */
sealed class CallState {
    object Idle : CallState()

    data class OutgoingRing(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val type: CallType
    ) : CallState()

    data class IncomingRing(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val type: CallType
    ) : CallState()

    data class Connected(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val type: CallType,
        val isCaller: Boolean
    ) : CallState()

    data class Ended(
        val callId: String,
        val peerId: String,
        val peerName: String,
        val reason: String,
        val durationSec: Long
    ) : CallState()
}

/** 信令动作（与服务端协议一致） */
object CallActions {
    const val INVITE = "invite"
    const val ACCEPT = "accept"
    const val DECLINE = "decline"
    const val CANCEL = "cancel"
    const val BUSY = "busy"
    const val OFFER = "offer"
    const val ANSWER = "answer"
    const val ICE_CANDIDATE = "ice-candidate"
    const val HANGUP = "hangup"
}

/** 结束原因（用于通话记录留痕） */
object CallEndReason {
    const val CANCELLED = "对方已取消"      // 主叫取消，被叫看到
    const val DECLINED = "对方已拒绝"       // 被叫拒接，主叫看到
    const val BUSY = "对方忙线中"           // 被叫正在通话
    const val HANGUP = "通话结束"           // 正常挂断（已接通）
    const val MISSED = "未接听"             // 被叫超时未接
    const val NO_ANSWER = "对方未接听"      // 主叫超时
    const val OFFLINE = "对方不在线"        // 服务端回 call_missed
}
