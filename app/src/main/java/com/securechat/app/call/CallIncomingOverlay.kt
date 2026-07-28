package com.securechat.app.call

import android.Manifest
import android.media.RingtoneManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.securechat.app.ServiceLocator

/**
 * 来电浮层：全屏覆盖，显示来电者信息 + 接听/拒绝。
 * 接听前按来电类型请求相应权限（语音：RECORD_AUDIO；视频：RECORD_AUDIO + CAMERA）。
 * 由 SecureChatShell 在 CallState.IncomingRing 时显示。
 */
@Composable
fun CallIncomingOverlay(state: CallState.IncomingRing) {
    val callManager = ServiceLocator.callManager ?: return
    val context = LocalContext.current
    val isVideo = state.type == CallType.VIDEO

    // ── 来电提醒：响铃 + 震动（覆盖前台无提醒问题）──
    DisposableEffect(Unit) {
        val ringtone = runCatching {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ).also {
                it.isLooping = true
                it.play()
            }
        }.getOrNull()
        val vibrator = runCatching {
            context.getSystemService(Vibrator::class.java)
        }.getOrNull()
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 350, 500), 0))
        }
        onDispose {
            runCatching { ringtone?.stop() }
            runCatching { vibrator?.cancel() }
        }
    }

    // 语音权限（RECORD_AUDIO）
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) callManager.acceptCall()
        else Toast.makeText(context, "需要麦克风权限才能通话", Toast.LENGTH_SHORT).show()
    }

    // 视频权限（RECORD_AUDIO + CAMERA）
    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioOk = result[Manifest.permission.RECORD_AUDIO] == true
        if (audioOk) {
            callManager.acceptCall()
        } else {
            Toast.makeText(context, "需要麦克风权限才能通话", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.peerName,
                color = Color.White,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isVideo) "邀请你视频通话" else "邀请你语音通话",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(48.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拒绝
                FloatingActionButton(
                    onClick = { callManager.declineCall() },
                    containerColor = Color.Red,
                    shape = CircleShape
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "拒绝", tint = Color.White)
                }

                // 接听
                FloatingActionButton(
                    onClick = {
                        if (isVideo) {
                            videoLauncher.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            )
                        } else {
                            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    containerColor = Color(0xFF4CAF50),
                    shape = CircleShape
                ) {
                    Icon(
                        if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        contentDescription = "接听",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 呼出等待浮层：显示「正在呼叫…」+ 取消按钮。
 * 由 SecureChatShell 在 CallState.OutgoingRing 时显示，
 * 解决发起方在对方接起前看不到界面、无法挂断的问题。
 */
@Composable
fun CallOutgoingOverlay(state: CallState.OutgoingRing) {
    val callManager = ServiceLocator.callManager ?: return
    val isVideo = state.type == CallType.VIDEO

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.peerName, color = Color.White, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isVideo) "正在发起视频通话…" else "正在呼叫…",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(56.dp))
            // 取消通话：主叫振铃中挂断 -> 发 cancel 信令，对方收到后结束来电界面
            FloatingActionButton(
                onClick = { callManager.hangup() },
                containerColor = Color.Red,
                shape = CircleShape
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "取消通话", tint = Color.White)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("取消通话", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
