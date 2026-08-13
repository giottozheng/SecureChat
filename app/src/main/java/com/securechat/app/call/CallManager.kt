package com.securechat.app.call

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.securechat.app.network.push.PushConnectionService
import com.securechat.app.repository.MessageRepository
import com.securechat.app.util.canonicalConversationId
import com.securechat.app.util.teamDisplayName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话管理器（单例状态机）。
 *
 * 职责：
 *  - 维护通话生命周期状态（Idle/OutgoingRing/IncomingRing/Connected/Ended），对外暴露 StateFlow；
 *  - 经 PushConnectionService 的活 WebSocket 收发 call_signal 信令；
 *  - 驱动 WebRtcClient 完成 SDP/ICE 协商；
 *  - 通话结束写入本地通话记录（messageType=CALL）。
 *
 * 注意：本类由 Hilt 注入，并在 SecureChatApplication 中塞入 ServiceLocator，
 * 以便无 Hilt 注入的 PushConnectionService 收到 call_signal 时回调 onSignal()。
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: MessageRepository
) : WebRtcClient.Listener {

    // ── ICE 服务器配置（STUN + 自建 TURN）──
    // TURN 静态长期凭据（内网小团队可接受；后续可升级为服务端动态下发）
    private companion object {
        const val TURN_USER = "securechat"
        const val TURN_PASS = "JtNhae88eaW1L3LllUU5bPZ4"
        const val WS_TIMEOUT_MS = 30_000L
        const val TAG = "CallManager"
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _state.asStateFlow()

    private var webRtc: WebRtcClient? = null
    private var isCaller = false
    private var connectedAt = 0L
    private var currentCallType: CallType = CallType.AUDIO

    /** EGL 是否可用（视频渲染能力）。不可用则通话降级为纯音频，UI 不创建 SurfaceViewRenderer。 */
    val eglAvailable: Boolean get() = WebRtcClient.getEglContext() != null

    /** 幂等触发 WebRTC 原生初始化（主线程安全；通话界面渲染前调用一次确保 EGL 就绪）。 */
    fun ensureNativeReady() {
        WebRtcClient.Companion.ensurePcInitialized(appContext)
    }

    // 协程异常兜底：任何未捕获异常只记录，绝不杀进程（避免通话初始化异常导致闪退）
    private val callExceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Uncaught coroutine exception", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + callExceptionHandler)
    // 后台线程初始化 WebRTC 原生组件，避免主线程卡顿/原生初始化崩溃
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + callExceptionHandler)
    private var timeoutJob: Job? = null

    private val myUserId: String
        get() = prefs.getString("auth_user_id", "") ?: ""

    // ───────────────────────────── 对外控制 API ─────────────────────────────

    /** 发起通话（主叫）。前置：音视频权限已授予。 */
    fun startCall(peerId: String, type: CallType) {
        if (prefs.getBoolean("webrtc_disabled", false)) {
            Log.w(TAG, "startCall ignored: WebRTC disabled by previous native crash")
            return
        }
        if (_state.value !is CallState.Idle) {
            Log.w(TAG, "startCall ignored: call in progress (${_state.value})")
            return
        }
        currentCallType = type
        isCaller = true
        val callId = UUID.randomUUID().toString()
        val peerName = teamDisplayName(peerId)
        _state.value = CallState.OutgoingRing(callId, peerId, peerName, type)
        try {
            CallService.start(appContext, type == CallType.VIDEO, CallService.MODE_ACTIVE, peerName)
        } catch (e: Exception) {
            Log.e(TAG, "CallService.start failed", e)
        }
        sendSignal(CallActions.INVITE)
        initWebRtcAsync(type == CallType.VIDEO)
        Log.i(TAG, "startCall -> $peerId ($type) callId=$callId")
        // 30s 无接听 -> 取消
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(WS_TIMEOUT_MS)
            if (_state.value is CallState.OutgoingRing) {
                Log.i(TAG, "OutgoingRing timeout, cancel")
                sendSignal(CallActions.CANCEL)
                endCall(CallEndReason.NO_ANSWER, 0L)
            }
        }
    }

    /** 接听来电（被叫）。前置：音视频权限已授予。 */
    fun acceptCall() {
        if (prefs.getBoolean("webrtc_disabled", false)) {
            Log.w(TAG, "acceptCall ignored: WebRTC disabled by previous native crash")
            return
        }
        val s = _state.value
        if (s !is CallState.IncomingRing) return
        isCaller = false
        _state.value = CallState.Connected(s.callId, s.peerId, s.peerName, s.type, isCaller = false)
        CallService.start(appContext, s.type == CallType.VIDEO, CallService.MODE_ACTIVE, s.peerName)
        sendSignal(CallActions.ACCEPT)
        Log.i(TAG, "acceptCall callId=${s.callId}")
        // 等待主叫发来 offer 后 setRemote + createAnswer
    }

    /** 拒接来电（被叫）。 */
    fun declineCall() {
        val s = _state.value
        if (s !is CallState.IncomingRing) return
        sendSignal(CallActions.DECLINE)
        endCall(CallEndReason.DECLINED, 0L)
    }

    /** 挂断（通话中）或取消（主叫振铃中）或拒接（被叫振铃中）。 */
    fun hangup() {
        when (val s = _state.value) {
            is CallState.Connected -> {
                sendSignal(CallActions.HANGUP)
                val dur = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0
                endCall(CallEndReason.HANGUP, dur)
            }
            is CallState.OutgoingRing -> {
                sendSignal(CallActions.CANCEL)
                endCall(CallEndReason.CANCELLED, 0L)
            }
            is CallState.IncomingRing -> {
                sendSignal(CallActions.DECLINE)
                endCall(CallEndReason.DECLINED, 0L)
            }
            else -> {}
        }
    }

    /** UI 关闭「结束」界面后调用，回到 Idle。 */
    fun reset() {
        if (_state.value is CallState.Ended) {
            _state.value = CallState.Idle
        }
    }

    // ───────────────────────────── 信令入口（Service 回调） ─────────────────────────────

    /** 由 PushConnectionService 在收到 type=call_signal 时调用。 */
    fun onSignal(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("type") != "call_signal") return
            val data = json.optJSONObject("data") ?: return
            val action = data.optString("action")
            val callId = data.optString("callId")
            val from = data.optString("from")
            val callTypeStr = data.optString("callType", "audio")
            val callType = if (callTypeStr == "video") CallType.VIDEO else CallType.AUDIO
            val payload = data.optJSONObject("payload") ?: JSONObject()

            Log.d(TAG, "onSignal action=$action from=$from")

            when (action) {
                CallActions.INVITE -> handleInvite(callId, from, callType)
                CallActions.ACCEPT -> handleAccept(callId, from, callType)
                CallActions.DECLINE -> endCall(CallEndReason.DECLINED, 0L)
                CallActions.CANCEL -> endCall(CallEndReason.CANCELLED, 0L)
                CallActions.BUSY -> endCall(CallEndReason.BUSY, 0L)
                CallActions.OFFER -> {
                    webRtc?.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                    )
                    webRtc?.createAnswer()
                }
                CallActions.ANSWER -> {
                    webRtc?.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                    )
                }
                CallActions.ICE_CANDIDATE -> {
                    val cand = IceCandidate(
                        payload.optString("sdpMid"),
                        payload.optInt("sdpMLineIndex"),
                        payload.optString("candidate")
                    )
                    webRtc?.addIceCandidate(cand)
                }
                CallActions.HANGUP -> {
                    val dur = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0
                    endCall(CallEndReason.HANGUP, dur)
                }
                else -> Log.w(TAG, "Unknown call action: $action")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onSignal parse failed", e)
        }
    }

    /** 服务端回 call_missed（目标离线）。 */
    fun onMissed() {
        if (_state.value is CallState.OutgoingRing) {
            endCall(CallEndReason.OFFLINE, 0L)
        }
    }

    // ───────────────────────────── 信令处理内部逻辑 ─────────────────────────────

    private fun handleInvite(callId: String, from: String, callType: CallType) {
        if (_state.value !is CallState.Idle) {
            // 正在通话 -> 回 busy
            val cur = _state.value
            val peer = when (cur) {
                is CallState.OutgoingRing -> cur.peerId
                is CallState.IncomingRing -> cur.peerId
                is CallState.Connected -> cur.peerId
                else -> from
            }
            sendSignalTo(CallActions.BUSY, callId, from, callType)
            Log.i(TAG, "Busy: incoming call from $from while in call")
            return
        }
        currentCallType = callType
        isCaller = false
        val peerName = teamDisplayName(from)
        _state.value = CallState.IncomingRing(callId, from, peerName, callType)
        try {
            CallService.start(appContext, callType == CallType.VIDEO, CallService.MODE_RING, peerName)
        } catch (e: Exception) {
            Log.e(TAG, "CallService.start failed", e)
        }
        initWebRtcAsync(callType == CallType.VIDEO)
        Log.i(TAG, "IncomingRing from $from ($callType) callId=$callId")
        // 30s 未接 -> 拒接
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(WS_TIMEOUT_MS)
            if (_state.value is CallState.IncomingRing) {
                Log.i(TAG, "IncomingRing timeout, decline")
                sendSignal(CallActions.DECLINE)
                endCall(CallEndReason.MISSED, 0L)
            }
        }
    }

    private fun handleAccept(callId: String, from: String, callType: CallType) {
        // 仅主叫会收到 accept
        if (_state.value !is CallState.OutgoingRing) return
        val s = _state.value as CallState.OutgoingRing
        isCaller = true
        _state.value = CallState.Connected(s.callId, s.peerId, s.peerName, s.type, isCaller = true)
        // 主叫作为 offerer 创建 offer
        webRtc?.createOffer()
        Log.i(TAG, "handleAccept -> createOffer callId=$callId")
    }

    // ───────────────────────────── WebRtcClient.Listener ─────────────────────────────

    override fun onLocalDescription(sdp: SessionDescription) {
        val type = if (sdp.type == SessionDescription.Type.OFFER) CallActions.OFFER else CallActions.ANSWER
        sendSignal(type, mapOf("sdp" to sdp.description))
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        sendSignal(CallActions.ICE_CANDIDATE, mapOf(
            "candidate" to candidate.sdp,
            "sdpMid" to (candidate.sdpMid ?: ""),
            "sdpMLineIndex" to candidate.sdpMLineIndex.toString()
        ))
    }

    override fun onRemoteStream(stream: MediaStream) {
        val videoTrack = stream.videoTracks.firstOrNull()
        if (videoTrack != null) {
            webRtc?.remoteRenderer?.let { videoTrack.addSink(it) }
        }
        Log.i(TAG, "Remote stream received (video=${videoTrack != null})")
    }

    override fun onConnected() {
        if (_state.value is CallState.Connected && connectedAt == 0L) {
            connectedAt = System.currentTimeMillis()
            Log.i(TAG, "Call connected at $connectedAt")
        }
    }

    override fun onDisconnected() {
        if (_state.value is CallState.Connected) {
            val dur = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0
            endCall(CallEndReason.HANGUP, dur)
        }
    }

    override fun onError(err: String) {
        Log.e(TAG, "WebRTC error: $err")
    }

    // ── 供 UI 调用的公有转发方法 ──
    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtc?.attachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRtc?.attachRemoteRenderer(renderer)
    }

    fun setMuted(muted: Boolean) {
        webRtc?.setMicrophoneEnabled(!muted)
    }

    fun setVideoOff(off: Boolean) {
        webRtc?.setVideoEnabled(!off)
    }

    fun switchCamera() {
        webRtc?.switchCamera()
    }

    // ───────────────────────────── 收尾 ─────────────────────────────

    private fun endCall(reason: String, durationSec: Long) {
        // CallService 前台通知（含来电响铃）由 CallService.stop 统一停止
        val s = _state.value
        val callId: String
        val peerId: String
        val peerName: String
        when (s) {
            is CallState.OutgoingRing -> { callId = s.callId; peerId = s.peerId; peerName = s.peerName }
            is CallState.IncomingRing -> { callId = s.callId; peerId = s.peerId; peerName = s.peerName }
            is CallState.Connected -> { callId = s.callId; peerId = s.peerId; peerName = s.peerName }
            else -> { callId = ""; peerId = ""; peerName = "" }
        }
        timeoutJob?.cancel()
        // 释放 WebRTC
        try { webRtc?.dispose() } catch (e: Throwable) { Log.e(TAG, "dispose failed", e) }
        webRtc = null
        connectedAt = 0L
        // 停止前台服务
        CallService.stop(appContext)

        if (callId.isNotBlank() && peerId.isNotBlank()) {
            val text = buildCallLogText(reason, durationSec, isCaller)
            val convId = canonicalConversationId(myUserId, peerId)
            scope.launch {
                try {
                    repository.saveCallLog(convId, peerId, text, isOutgoing = isCaller)
                } catch (e: Throwable) {
                    Log.e(TAG, "saveCallLog failed", e)
                }
            }
            _state.value = CallState.Ended(callId, peerId, peerName, text, durationSec)
        } else {
            _state.value = CallState.Idle
        }
        Log.i(TAG, "endCall reason=$reason duration=$durationSec")
    }

    private fun buildCallLogText(reason: String, durationSec: Long, caller: Boolean): String {
        return when (reason) {
            CallEndReason.HANGUP -> "通话 " + formatDuration(durationSec)
            CallEndReason.CANCELLED -> if (caller) "通话已取消" else "对方已取消"
            CallEndReason.DECLINED -> if (caller) "对方已拒绝" else "已拒绝"
            CallEndReason.BUSY -> if (caller) "对方忙线中" else "忙线中"
            CallEndReason.NO_ANSWER -> if (caller) "对方未接听" else "未接听"
            CallEndReason.MISSED -> "未接听"
            CallEndReason.OFFLINE -> "对方不在线"
            else -> "通话结束"
        }
    }

    private fun formatDuration(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%02d:%02d".format(m, s)
    }

    // ───────────────────────────── 信令发送 ─────────────────────────────

    private fun ensureWebRtc(isVideo: Boolean) {
        if (webRtc == null) {
            val iceServers = listOf(
                PeerConnectionIceServer.stun("stun:stun.l.google.com:19302"),
                // 公网域名 TURN（依赖路由器 3478/udp 端口转发）
                PeerConnectionIceServer.turn(TURN_USER, TURN_PASS, "turn:1.hackdz.dpdns.org:3478?transport=udp"),
                // 内网 LAN TURN：同网段设备（团队内部）无需端口转发即可中继
                PeerConnectionIceServer.turn(TURN_USER, TURN_PASS, "turn:192.168.10.99:3478?transport=udp")
            )
            webRtc = WebRtcClient(appContext, iceServers, this, isVideo)
        }
    }

    /**
     * 后台线程安全地创建 WebRtcClient 并完成本地媒体 / PeerConnection 初始化。
     * 任何原生初始化异常都被捕获，避免主线程崩溃（闪退）。
     */
    private fun initWebRtcAsync(isVideo: Boolean) {
        initScope.launch {
            try {
                // 通话初始化前抓一次 logcat 快照存文件（含此前 WebRTC verbose 日志），
                // 若随后原生崩溃，重启后 SecureChatApplication 读此文件即可看到崩溃前现场。
                snapshotLogcat(appContext)
                // WebRTC 原生初始化（PeerConnectionFactory.initialize / EglBase.create）必须在主线程执行，
                // 部分设备/WebRTC 构建在后台线程做原生初始化会触发不可捕获的原生崩溃（通话点击即闪退）。
                withContext(Dispatchers.Main) { ensureWebRtc(isVideo) }
                webRtc?.initLocalMedia(isVideo)
                webRtc?.createPeerConnection()
            } catch (e: Throwable) {
                Log.e(TAG, "initWebRtcAsync failed", e)
            }
        }
    }

    /** 通话初始化时把当前 logcat 快照存文件，崩溃重启后用于远程诊断（含崩溃前 WebRTC verbose 日志）。 */
    private fun snapshotLogcat(context: Context) {
        runCatching {
            val p = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val txt = p.inputStream.bufferedReader().readText()
            p.destroy()
            context.openFileOutput("call_logcat.txt", Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    private fun sendSignal(action: String, payload: Map<String, String> = emptyMap()) {
        val s = _state.value
        val (callId, peerId, callType) = when (s) {
            is CallState.OutgoingRing -> Triple(s.callId, s.peerId, s.type)
            is CallState.IncomingRing -> Triple(s.callId, s.peerId, s.type)
            is CallState.Connected -> Triple(s.callId, s.peerId, s.type)
            else -> return
        }
        sendSignalTo(action, callId, peerId, callType, payload)
    }

    private fun sendSignalTo(action: String, callId: String, peerId: String, callType: CallType, payload: Map<String, String> = emptyMap()) {
        val json = JSONObject().apply {
            put("type", "call_signal")
            put("data", JSONObject().apply {
                put("callId", callId)
                put("from", myUserId)
                put("to", peerId)
                put("callType", if (callType == CallType.VIDEO) "video" else "audio")
                put("action", action)
                if (payload.isNotEmpty()) put("payload", JSONObject(payload))
            })
        }.toString()
        PushConnectionService.sendCallSignal(json)
        Log.d(TAG, "sendSignal $action -> $peerId")
    }
}

/**
 * ICE server 构造辅助（避免到处写 PeerConnection.IceServer.builder）。
 */
private object PeerConnectionIceServer {
    fun stun(url: String): org.webrtc.PeerConnection.IceServer =
        org.webrtc.PeerConnection.IceServer.builder(url).createIceServer()

    fun turn(user: String, pass: String, url: String): org.webrtc.PeerConnection.IceServer =
        org.webrtc.PeerConnection.IceServer.builder(url)
            .setUsername(user)
            .setPassword(pass)
            .createIceServer()
}
