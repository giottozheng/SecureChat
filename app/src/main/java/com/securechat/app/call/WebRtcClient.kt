package com.securechat.app.call

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * WebRTC 封装：PeerConnection + 本地音视频采集 + SDP/ICE 交换。
 *
 * - 主叫（caller）在收到对方 accept 后创建 offer；
 * - 被叫（callee）在收到 offer 后创建 answer；
 * - ICE 候选双向转发，trickle ICE（边收集边发送）。
 *
 * 媒体流使用 WebRTC 原生 DTLS-SRTP 加密，不做应用层逐帧 E2E。
 */
class WebRtcClient(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val listener: Listener,
    isVideo: Boolean
) {
    interface Listener {
        /** 本地 SDP 生成（offer/answer），由 CallManager 经 WS 发出 */
        fun onLocalDescription(sdp: SessionDescription)

        /** 本地 ICE 候选，由 CallManager 经 WS 发出 */
        fun onIceCandidate(candidate: IceCandidate)

        /** 远程媒体流到达（视频/音频轨可用） */
        fun onRemoteStream(stream: MediaStream)

        /** 连接建立 */
        fun onConnected()

        /** 连接断开 */
        fun onDisconnected()

        /** 出错 */
        fun onError(err: String)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null

    @Volatile
    var localRenderer: SurfaceViewRenderer? = null
        private set
    @Volatile
    var remoteRenderer: SurfaceViewRenderer? = null
        private set
    private var remoteVideoTrack: VideoTrack? = null

    private val sdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            executor.execute {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        listener.onLocalDescription(sdp)
                    }
                    override fun onCreateFailure(p0: String?) {
                        listener.onError("setLocalDescription failed: $p0")
                    }
                    override fun onSetFailure(p0: String?) {
                        listener.onError("setLocalDescription failed: $p0")
                    }
                }, sdp)
            }
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            listener.onError("create SDP failed: $error")
        }
        override fun onSetFailure(error: String?) {
            listener.onError("set SDP failed: $error")
        }
    }

    init {
        Companion.ensurePcInitialized(context)
        if (Companion.initFailed) {
            // 受控抛出，由 CallManager.initWebRtcAsync 的 catch(Throwable) 捕获并降级为 onError
            throw IllegalStateException("WebRTC native init failed")
        }
        // 关键修复（通话闪退）：EGL 仅视频通话才创建。语音通话不需要 EGL，跳过
        // EglBase.create() 即可彻底避免其原生 segfault（此前语音/视频都无条件创建 EGL，
        // 故两者都崩）。视频通话尝试创建 EGL，失败（含原生崩溃已由自愈合禁用）则降级为
        // 纯音频工厂，UI 显示占位而非崩溃。
        val eglOk = if (isVideo) Companion.ensureEgl(context) else false
        factory = if (eglOk) {
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(Companion.getEglContext()!!, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(Companion.getEglContext()!!))
                .createPeerConnectionFactory()
        } else {
            PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
        Log.i(TAG, "WebRtcClient initialized (video=$isVideo, egl=$eglOk)")
    }

    /** 初始化本地音视频采集。isVideo=true 时开启摄像头并预览到 localRenderer。 */
    fun initLocalMedia(isVideo: Boolean) {
        executor.execute {
            try {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("echoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("autoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("noiseSuppression", "true"))
                }
                val audioSource = factory.createAudioSource(audioConstraints)
                audioTrack = factory.createAudioTrack("AUDIO_TRACK", audioSource)
                localStream = factory.createLocalMediaStream("LOCAL_STREAM")
                localStream?.addTrack(audioTrack)

                if (isVideo) {
                    val eglCtx = getEglContext()
                    videoCapturer = createCameraCapturer()
                    if (videoCapturer != null && eglCtx != null) {
                        videoSource = factory.createVideoSource(false)
                        videoCapturer?.initialize(
                            SurfaceTextureHelper.create("CaptureThread", eglCtx),
                            context,
                            videoSource?.capturerObserver
                        )
                        videoCapturer?.startCapture(1280, 720, 30)
                        videoTrack = factory.createVideoTrack("VIDEO_TRACK", videoSource)
                        localRenderer?.let { videoTrack?.addSink(it) }
                        localStream?.addTrack(videoTrack)
                    } else {
                        Log.w(TAG, "No EGL/camera, video call falls back to audio-only")
                    }
                }
                Log.i(TAG, "Local media initialized (video=$isVideo)")
            } catch (e: Throwable) {
                Log.e(TAG, "initLocalMedia failed", e)
                listener.onError("initLocalMedia failed: ${e.message}")
            }
        }
    }

    /** 创建 PeerConnection 并加入本地流。 */
    fun createPeerConnection() {
        executor.execute {
            Companion.writeBreadcrumb(context, "create_pc_start")
            if (peerConnection != null) {
                Log.d(TAG, "PeerConnection already exists")
                return@execute
            }
            try {
                val config = PeerConnection.RTCConfiguration(iceServers).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> listener.onConnected()
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED -> listener.onDisconnected()
                            else -> {}
                        }
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let { listener.onIceCandidate(it) }
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    // Plan B 兼容分支（Unified Plan 下不会触发，保留以防异常）
                    override fun onAddStream(stream: MediaStream?) {
                        stream?.let {
                            remoteVideoTrack = it.videoTracks.firstOrNull()
                            remoteRenderer?.let { r -> remoteVideoTrack?.addSink(r) }
                            listener.onRemoteStream(it)
                        }
                    }
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                        streams?.firstOrNull()?.let { listener.onRemoteStream(it) }
                    }
                    // ★ Unified Plan 主路径：远端轨通过 onTrack 到达。
                    // 现代 libjingle 默认 Unified Plan，createPeerConnection 后必须走 onTrack，
                    // 且本地轨发布必须用 addTrack（绝不能用 addStream，否则 RTC_CHECK(!IsUnifiedPlan()) abort）。
                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        when (track) {
                            is VideoTrack -> {
                                remoteVideoTrack = track
                                remoteRenderer?.let { track.addSink(it) }
                                val ms = factory.createLocalMediaStream("REMOTE_STREAM")
                                ms.addTrack(track)
                                listener.onRemoteStream(ms)
                                Log.i(TAG, "Remote VIDEO track attached")
                            }
                            is AudioTrack -> {
                                Log.i(TAG, "Remote AUDIO track received (auto-rendered to device)")
                            }
                        }
                    }
                })
                // ★ Unified Plan 正确用法：用 addTrack 发布本地轨。
                // 绝不能用 peerConnection.addStream()（Plan B 旧 API），现代 libjingle 默认
                // Unified Plan，addStream 会触发 RTC_CHECK(!IsUnifiedPlan()) -> abort 闪退。
                // 这就是此前语音/视频通话必崩的根因（崩溃于 signaling 线程、createPeerConnection 之后）。
                audioTrack?.let { peerConnection?.addTrack(it, listOf("LOCAL")) }
                videoTrack?.let { peerConnection?.addTrack(it, listOf("LOCAL")) }
                Companion.writeBreadcrumb(context, "create_pc_done")
                Log.i(TAG, "PeerConnection created")
            } catch (e: Throwable) {
                Log.e(TAG, "createPeerConnection failed", e)
                listener.onError("createPeerConnection failed: ${e.message}")
            }
        }
    }

    fun createOffer() {
        executor.execute {
            peerConnection?.createOffer(sdpObserver, MediaConstraints())
        }
    }

    fun createAnswer() {
        executor.execute {
            peerConnection?.createAnswer(sdpObserver, MediaConstraints())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    listener.onError("setRemoteDescription failed: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    listener.onError("setRemoteDescription failed: $p0")
                }
            }, sdp)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    /**
     * 绑定本地预览 SurfaceViewRenderer（UI 创建后调用）。
     * 必须在主线程调用：WebRTC 的 SurfaceViewRenderer.init 内部有主线程约束，
     * 且后续系统在主线程的 surfaceCreated 回调里会做 EGL 操作。若放在后台线程 init，
     * 会因线程不一致在主线程回调中抛未捕获异常 -> 通话界面闪退。
     * CallActiveScreen 的 AndroidView factory 即在主线程调用本方法，故此处不切换线程。
     */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        try {
            val eglCtx = getEglContext() ?: run {
                Log.e(TAG, "attachLocalRenderer: no EGL context, skip (audio-only)")
                return
            }
            renderer.init(eglCtx, null)
            localRenderer = renderer
            videoTrack?.addSink(renderer)
            Log.i(TAG, "Local renderer attached")
        } catch (e: Throwable) {
            Log.e(TAG, "attachLocalRenderer failed", e)
        }
    }

    /**
     * 绑定远程视频 SurfaceViewRenderer（UI 创建后调用）。
     * 同 attachLocalRenderer：必须在主线程 init（WebRTC 约束），避免 surfaceCreated 回调中
     * 主线程 EGL 操作崩溃。语音通话也走本方法（远程视频大窗），故这也覆盖了「语音通话闪退」。
     */
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        try {
            val eglCtx = getEglContext() ?: run {
                Log.e(TAG, "attachRemoteRenderer: no EGL context, skip (audio-only)")
                return
            }
            renderer.init(eglCtx, null)
            remoteRenderer = renderer
            remoteVideoTrack?.addSink(renderer)
            Log.i(TAG, "Remote renderer attached (track=${remoteVideoTrack != null})")
        } catch (e: Throwable) {
            Log.e(TAG, "attachRemoteRenderer failed", e)
        }
    }

    /** 切换摄像头前后置。 */
    fun switchCamera() {
        executor.execute {
            (videoCapturer as? Camera2Capturer)?.switchCamera(null)
        }
    }

    /** 静音/取消静音本地音频。 */
    fun setMicrophoneEnabled(enabled: Boolean) {
        executor.execute { audioTrack?.setEnabled(enabled) }
    }

    /** 开启/关闭本地视频采集（黑屏）。 */
    fun setVideoEnabled(enabled: Boolean) {
        executor.execute { videoTrack?.setEnabled(enabled) }
    }

    fun dispose() {
        executor.execute {
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoSource?.dispose()
                localStream?.dispose()
                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null
                // 注意：factory / eglBase 为跨通话复用的共享单例，不在此释放，
                // 否则再次 initialize 会抛 IllegalStateException 导致闪退
                Log.i(TAG, "WebRtcClient disposed (peer only)")
        } catch (e: Throwable) {
            Log.e(TAG, "dispose failed", e)
        }
        }
        executor.shutdown()
    }

    private fun createCameraCapturer(): VideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(context)
            val devices = enumerator.deviceNames
            // 优先前置摄像头
            val front = devices.firstOrNull { enumerator.isFrontFacing(it) }
            val back = devices.firstOrNull { enumerator.isBackFacing(it) }
            val chosen = front ?: back
            if (chosen != null) enumerator.createCapturer(chosen, null) else null
        } catch (e: Throwable) {
            Log.e(TAG, "createCameraCapturer failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "WebRtcClient"
        @Volatile
        private var pcInitialized = false
        // EGL 上下文：视频编解码需要；语音通话不创建（避免 EglBase.create 原生 segfault）。
        private var eglBase: EglBase? = null
        // 初始化失败标记：PeerConnectionFactory.initialize 抛异常时置位，
        // WebRtcClient 构造据此抛受控异常（被调用方协程捕获，不闪退），而非未捕获冒泡。
        private var initFailed = false
        private const val PREFS = "securechat_prefs"
        private const val KEY_EGL_DISABLED = "webrtc_egl_disabled"

        /** 返回 EGL 上下文，未初始化/降级时为 null。 */
        fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

        /**
         * 全局只初始化一次 PeerConnectionFactory（重复 initialize 会抛 IllegalStateException）。
         * 异常全部捕获：native 初始化异常（含 UnsatisfiedLinkError 等）若抛 Java 异常则被捕获，
         * 置 initFailed 后由构造函数抛受控异常降级；若是原生 segfault 则面包屑会停在 pc_init_start，
         * 由 SecureChatApplication 自愈合禁用 WebRTC。
         */
        @Synchronized
        fun ensurePcInitialized(context: Context) {
            if (pcInitialized) return
            writeBreadcrumb(context, "pc_init_start")
            try {
                // 打开 WebRTC 内部 verbose 日志（输出到 logcat），崩溃前日志便于远程诊断
                try { Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE) } catch (_: Throwable) {}
                val options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                pcInitialized = true
                writeBreadcrumb(context, "pc_init_done")
            } catch (t: Throwable) {
                Log.e(TAG, "PeerConnectionFactory.initialize failed; WebRTC unavailable", t)
                initFailed = true
                writeBreadcrumb(context, "pc_init_failed")
            }
        }

        /**
         * 懒创建 EGL（仅视频通话需要）。返回是否成功。
         * 若此前已因原生崩溃被持久化禁用（webrtc_egl_disabled），直接返回 false，
         * 视频降级为「已连接但无画面」，避免反复原生 segfault 闪退。
         */
        @Synchronized
        fun ensureEgl(context: Context): Boolean {
            if (eglBase != null) return true
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_EGL_DISABLED, false)) {
                Log.w(TAG, "EGL disabled by previous native crash; skip (video degrades to no-video)")
                return false
            }
            writeBreadcrumb(context, "egl_create_start")
            return try {
                eglBase = EglBase.create()
                writeBreadcrumb(context, "egl_create_done")
                true
            } catch (t: Throwable) {
                // Java 层异常（少数设备）：捕获并永久禁用 EGL
                Log.e(TAG, "EglBase.create failed; disable EGL permanently", t)
                prefs.edit().putBoolean(KEY_EGL_DISABLED, true).apply()
                writeBreadcrumb(context, "egl_create_failed")
                false
            }
        }

        /** 面包屑：记录最后执行的原生步骤，用于原生崩溃（SIGSEGV 不走 Java handler）远程诊断。 */
        private fun writeBreadcrumb(context: Context, step: String) {
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("webrtc_breadcrumb", step).apply()
            }
        }
    }
}
