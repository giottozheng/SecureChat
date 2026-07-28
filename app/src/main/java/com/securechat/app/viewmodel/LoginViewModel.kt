package com.securechat.app.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.securechat.app.crypto.keyexchange.Base64Util
import com.securechat.app.crypto.keyexchange.RsaKeystoreManager
import com.securechat.app.network.push.PushManager
import com.securechat.app.repository.AccountRepository
import com.securechat.app.util.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 登录 / 注册页面 UI 状态
 */
data class LoginUiState(
    val username: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false,
    // 注册模式
    val isRegisterMode: Boolean = false,
    val displayName: String = "",
    // 服务端连接测试
    val isTestingConnection: Boolean = false,
    val connectionStatus: String? = null
)

/**
 * 登录 / 注册 ViewModel
 *
 * - 登录：调用 POST /api/login，保存 token/userId，生成本机 RSA 密钥并上传公钥。
 * - 注册：调用 POST /api/register 创建账号，成功后等同登录流程。
 * 不再使用硬编码的团队账号列表（测试账号已移除，改为服务端开放注册）。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rsaKeystoreManager: RsaKeystoreManager,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }

    fun setRegisterMode(on: Boolean) {
        _uiState.update { it.copy(isRegisterMode = on, errorMessage = null) }
    }

    // ── 登录 ──
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入用户名和密码") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val deviceId = getDeviceId()
                    val json = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                        put("deviceId", deviceId)
                    }
                    val req = Request.Builder()
                        .url(ServerConfig.getUrl(context, "/api/login"))
                        .addHeader("Content-Type", "application/json")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    resp.close()
                    if (!resp.isSuccessful) {
                        val err = runCatching { JSONObject(body).optString("error", "登录失败") }.getOrDefault("登录失败")
                        throw Exception(err)
                    }
                    val o = JSONObject(body)
                    val token = o.getString("token")
                    val userId = o.getString("userId")
                    val displayName = o.optJSONObject("userProfile")?.optString("displayName") ?: username
                    AuthResult(token, userId, displayName)
                }
            }
            if (result.isSuccess) {
                completeLogin(result.getOrThrow())
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = result.exceptionOrNull()?.message ?: "登录失败") }
            }
        }
    }

    // ── 注册 ──
    fun register(username: String, password: String, displayName: String) {
        if (username.isBlank() || password.isBlank() || displayName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "用户名、密码、昵称均需填写") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val deviceId = getDeviceId()
                    val json = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                        put("displayName", displayName)
                        put("deviceId", deviceId)
                    }
                    val req = Request.Builder()
                        .url(ServerConfig.getUrl(context, "/api/register"))
                        .addHeader("Content-Type", "application/json")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    resp.close()
                    if (!resp.isSuccessful) {
                        val err = runCatching { JSONObject(body).optString("error", "注册失败") }.getOrDefault("注册失败")
                        throw Exception(err)
                    }
                    val o = JSONObject(body)
                    val token = o.getString("token")
                    val userId = o.getString("userId")
                    val name = o.optJSONObject("userProfile")?.optString("displayName") ?: displayName
                    AuthResult(token, userId, name)
                }
            }
            if (result.isSuccess) {
                completeLogin(result.getOrThrow())
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = result.exceptionOrNull()?.message ?: "注册失败") }
            }
        }
    }

    /**
     * 登录/注册成功后的公共流程：保存会话、生成本机密钥、上传公钥、启动推送、拉取好友。
     */
    private suspend fun completeLogin(auth: AuthResult) {
        val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("auth_token", auth.token)
            putString("auth_user_id", auth.userId)
            putString("auth_display_name", auth.displayName)
            // device_id 已在 getDeviceId() 中写入
            apply()
        }

        // 生成本机 RSA 密钥对（仅当前用户），并把真实公钥注册到服务端
        val pem = try {
            rsaKeystoreManager.getPublicKeyPem()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local public key", e)
            null
        }
        if (pem != null) {
            accountRepository.registerPublicKey(auth.userId, auth.token, pem)
        }

        // 启动推送服务（前台 WebSocket + 轮询兜底）
        PushManager.initialize(context)

        // 拉取好友列表（联系人来源）
        viewModelScope.launch { accountRepository.fetchFriends() }

        _uiState.update {
            it.copy(isAuthenticated = true, isLoading = false, errorMessage = null)
        }
    }

    /**
     * 生成合法的 JWT-like token（base64-encoded）
     * 格式: header.payload.signature，payload 为 base64 编码的 JSON
     */
    private fun generateJwtToken(userId: String, deviceId: String): String {
        val header = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" // base64 of {"alg":"HS256","typ":"JWT"}
        val payload = Base64Util.encodeToString(
            """{"userId":"$userId","deviceId":"$deviceId","exp":${System.currentTimeMillis() / 1000 + 86400L}}"""
                .toByteArray()
        )
        val signature = Base64Util.encodeToString("securechat_signature_placeholder".toByteArray())
        return "$header.$payload.$signature"
    }

    /**
     * 测试当前已配置的服务端是否可达（GET /health）。
     * 结果写入 uiState.connectionStatus，供登录页展示 ✅/❌。
     */
    fun testConnection() {
        if (uiState.value.isTestingConnection) return
        _uiState.update { it.copy(isTestingConnection = true, connectionStatus = null, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = ServerConfig.getUrl(context, "/health")
                    val req = Request.Builder().url(url).get().build()
                    val resp = httpClient.newCall(req).execute()
                    val ok = resp.isSuccessful
                    val code = resp.code
                    resp.close()
                    if (!ok) throw Exception("服务端返回 HTTP $code")
                    "ok"
                }
            }
            if (result.isSuccess) {
                _uiState.update { it.copy(isTestingConnection = false, connectionStatus = "连接成功") }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "无法连接"
                _uiState.update { it.copy(isTestingConnection = false, connectionStatus = "无法连接：$msg") }
            }
        }
    }

    /** 用户修改服务端地址后清除旧的连接状态，避免误导。 */
    fun clearConnectionStatus() {
        _uiState.update { it.copy(connectionStatus = null) }
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    /**
     * 登出：清除本地会话与密钥缓存（服务端 token 为无状态 JWT，本地清除即失效）。
     */
    fun logout() {
        val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        _uiState.update { LoginUiState() }
    }

    private data class AuthResult(
        val token: String,
        val userId: String,
        val displayName: String
    )
}
