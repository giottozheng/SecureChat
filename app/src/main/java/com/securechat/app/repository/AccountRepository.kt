package com.securechat.app.repository

import android.content.Context
import android.util.Log
import com.securechat.app.data.local.FriendDao
import com.securechat.app.data.local.FriendEntity
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.FriendRequestEntity
import com.securechat.app.util.remoteDisplayNameOverrides
import com.securechat.app.util.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号相关服务端调用：好友请求、昵称/密码修改、联系人名称缓存。
 * 使用原生 OkHttp（与 MessageRepository 保持一致，避免改动 Retrofit 接口）。
 */
@Singleton
class AccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val friendRequestDao: FriendRequestDao,
    private val friendDao: FriendDao
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
    }
    private val authToken: String get() = prefs.getString("auth_token", "") ?: ""
    private val selfId: String get() = prefs.getString("auth_user_id", "") ?: ""

    // ── 好友请求 ──

    suspend fun sendFriendRequest(targetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("targetId", targetId) }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts/request"))
                .addHeader("Authorization", "Bearer $authToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("发送失败: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "sendFriendRequest failed", e)
            Result.failure(e)
        }
    }

    /**
     * 拉取当前用户收到的待处理好友请求，并合并进本地库（去重由主键保证）。
     */
    suspend fun fetchPendingRequests(): Result<List<FriendRequestEntity>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts/requests"))
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取失败"))
            val arr = JSONObject(body).optJSONArray("requests") ?: JSONArray()
            val list = mutableListOf<FriendRequestEntity>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    FriendRequestEntity(
                        fromId = o.optString("fromId"),
                        fromName = o.optString("fromName"),
                        toId = selfId,
                        timestamp = o.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list.forEach { friendRequestDao.upsertRequest(it) }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPendingRequests failed", e)
            Result.failure(e)
        }
    }

    suspend fun respondFriendRequest(fromId: String, accept: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("fromId", fromId)
                put("action", if (accept) "accept" else "reject")
            }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts/respond"))
                .addHeader("Authorization", "Bearer $authToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            resp.close()
            // 本地标记，未决列表自动过滤
            friendRequestDao.setStatus(fromId, if (accept) "accepted" else "rejected")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "respondFriendRequest failed", e)
            Result.failure(e)
        }
    }

    // ── 删除好友 ──

    suspend fun removeFriend(targetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("targetId", targetId) }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts/remove"))
                .addHeader("Authorization", "Bearer $authToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("删除失败: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "removeFriend failed", e)
            Result.failure(e)
        }
    }

    // ── 昵称 / 密码 ──

    suspend fun updateDisplayName(displayName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("displayName", displayName) }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/users/me/display-name"))
                .addHeader("Authorization", "Bearer $authToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (resp.isSuccessful) {
                prefs.edit().putString("auth_display_name", displayName).apply()
                Result.success(Unit)
            } else Result.failure(Exception("修改失败: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "updateDisplayName failed", e)
            Result.failure(e)
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("oldPassword", oldPassword)
                put("newPassword", newPassword)
            }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/users/me/password"))
                .addHeader("Authorization", "Bearer $authToken")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("修改失败: $body"))
        } catch (e: Exception) {
            Log.e(TAG, "changePassword failed", e)
            Result.failure(e)
        }
    }

    // ── 联系人名称缓存（昵称修改后对其他成员即时生效）──

    /**
     * 拉取当前用户的「好友」列表（服务端 /api/contacts 仅返回已互为好友的成员），
     * 写入本地 friends 表作为联系人界面数据源，并刷新昵称覆盖表。
     */
    suspend fun fetchFriends(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts"))
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取好友失败"))
            val arr = JSONArray(body)
            val friends = mutableListOf<FriendEntity>()
            val nameMap = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val name = o.optString("displayName", id)
                friends.add(
                    FriendEntity(
                        userId = id,
                        displayName = name,
                        publicKey = o.optString("publicKey", ""),
                        isOnline = o.optBoolean("online", false)
                    )
                )
                nameMap[id] = name
            }
            friendDao.clearFriends()
            friendDao.upsertFriends(friends)
            remoteDisplayNameOverrides = nameMap
            Log.i(TAG, "Fetched ${friends.size} friends")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "fetchFriends failed", e)
            Result.failure(e)
        }
    }

    /**
     * 将本机真实 RSA 公钥注册到服务端，供好友加密消息时获取。
     * 在登录完成、以及 app 冷启动自动恢复会话时都会调用，
     * 确保服务端（内存存储，重启后会清空）始终持有本用户最新公钥。
     */
    suspend fun registerPublicKey(userId: String, token: String, pem: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("userId", userId)
                put("publicKeyPem", pem)
            }
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/keys/register"))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = http.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            if (ok) Result.success(Unit) else Result.failure(Exception("注册公钥失败"))
        } catch (e: Exception) {
            Log.e(TAG, "registerPublicKey failed", e)
            Result.failure(e)
        }
    }

    /**
     * 校验本账号是否仍存在于服务器（用于冷启动自动恢复会话时检测账号是否丢失，
     * 例如服务器重启且未持久化、或换设备从未注册过）。
     */
    suspend fun checkSelfExists(userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/users/$userId/public-key"))
                .get()
                .build()
            val resp = http.newCall(req).execute()
            val ok = resp.code == 200
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "checkSelfExists failed", e)
            false
        }
    }

    suspend fun fetchAndCacheContacts(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(ServerConfig.getUrl(context, "/api/contacts"))
                .addHeader("Authorization", "Bearer $authToken")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            resp.close()
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("获取联系人失败"))
            val arr = try {
                JSONArray(body)
            } catch (_: Exception) {
                JSONObject(body).optJSONArray("contacts") ?: JSONArray()
            }
            val map = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.optString("id")] = o.optString("displayName")
            }
            remoteDisplayNameOverrides = map
            Log.i(TAG, "Cached ${map.size} contact display names")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndCacheContacts failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "AccountRepository"
    }
}
