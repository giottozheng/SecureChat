// 生物识别验证管理器
package com.securechat.app.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 生物识别验证管理器 — 接入真实的 BiometricPrompt
 */
class BiometricAuthManager(private val context: Context) {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private var deferred: CompletableDeferred<Result<Unit>>? = null

    /**
     * 执行生物识别验证（接入真实指纹/面容）
     * @param activity 必须是 FragmentActivity（Activity 的子类）
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        promptMessage: String = "使用指纹或面容解锁 SecureChat"
    ): Result<Unit> {
        // 检查硬件
        val biometricManager = BiometricManager.from(context)
        val authFlags = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (biometricManager.canAuthenticate(authFlags)) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                return Result.failure(AuthenticationException("设备不支持生物识别硬件"))
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                return Result.failure(AuthenticationException("生物识别硬件暂不可用"))
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                return Result.failure(AuthenticationException("未注册任何生物识别方式，请在系统设置中添加指纹或面容"))
            }
        }

        deferred = CompletableDeferred()
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SecureChat 安全验证")
            .setSubtitle(promptMessage)
            .setNegativeButtonText("使用密码解锁")
            .setAllowedAuthenticators(authFlags)
            .build()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                deferred?.complete(Result.failure(AuthenticationException(errString.toString(), errorCode)))
                deferred = null
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                deferred?.complete(Result.success(Unit))
                deferred = null
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // 重试，不取消挂起
            }
        }

        // 在主线程调用 BiometricPrompt — 使用 FragmentActivity 构造函数
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            val prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo)
        }

        return deferred!!.await()
    }

    fun cancelAuthentication() {
        deferred?.complete(Result.failure(AuthenticationException("验证被取消")))
        deferred = null
    }
}

class AuthenticationException(
    message: String,
    val errorCode: Int = 0
) : Exception(message)