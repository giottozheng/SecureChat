package com.securechat.app.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.securechat.app.ServiceLocator
import org.webrtc.SurfaceViewRenderer

/**
 * 全屏通话界面：远程视频大窗 + 本地小窗（视频通话）+ 控制按钮。
 * 由 SecureChatShell 在 CallState.Connected 时全屏覆盖显示。
 */
@Composable
fun CallActiveScreen(state: CallState.Connected) {
    val callManager = ServiceLocator.callManager ?: return
    val context = LocalContext.current
    var muted by remember { mutableStateOf(false) }
    var videoOff by remember { mutableStateOf(false) }
    val isVideo = state.type == CallType.VIDEO

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // EGL 可用 + 视频通话才创建 SurfaceViewRenderer；否则显示占位，避免未初始化的
        // SurfaceViewRenderer 在系统 surfaceCreated 回调里做 EGL 操作而闪退（通话根因）。
        val canRenderVideo = isVideo && callManager.eglAvailable

        if (canRenderVideo) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        callManager.attachRemoteRenderer(this)
                    }
                },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    Text(
                        "视频渲染不可用，已转为语音通话",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp
                    )
                } else {
                    Text(state.peerName, color = Color.White, fontSize = 48.sp)
                }
            }
        }

        // 本地视频小窗（视频通话且有 EGL 时）
        if (isVideo && canRenderVideo) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        callManager.attachLocalRenderer(this)
                    }
                },
                onRelease = { it.release() },
                modifier = Modifier
                    .size(120.dp, 160.dp)
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 16.dp)
            )
        }

        // 顶部：对方名称 + 状态
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.peerName, color = Color.White, fontSize = 22.sp)
            Text(
                if (isVideo) "视频通话中" else "语音通话中",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        }

        // 底部控制栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 静音
            FloatingActionButton(
                onClick = {
                    muted = !muted
                    callManager.setMuted(muted)
                },
                containerColor = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            ) {
                Icon(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "静音",
                    tint = if (muted) Color.White else Color.Black
                )
            }

            // 关摄像头（视频通话）
            if (isVideo) {
                FloatingActionButton(
                    onClick = {
                        videoOff = !videoOff
                        callManager.setVideoOff(videoOff)
                    },
                    containerColor = if (videoOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Icon(
                        if (videoOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                        contentDescription = "摄像头",
                        tint = if (videoOff) Color.White else Color.Black
                    )
                }
            }

            // 翻转摄像头（视频通话）
            if (isVideo) {
                FloatingActionButton(
                    onClick = { callManager.switchCamera() },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Icon(Icons.Filled.FlipCameraAndroid, contentDescription = "翻转摄像头", tint = Color.Black)
                }
            }

            // 挂断
            FloatingActionButton(
                onClick = { callManager.hangup() },
                containerColor = Color.Red,
                shape = CircleShape
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "挂断", tint = Color.White)
            }
        }
    }
}
