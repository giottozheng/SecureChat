package com.securechat.app.network.api

import com.securechat.app.data.model.PushNotification
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * API interface for the self-built backend.
 * All calls use TLS (HTTPS).
 */
interface ApiService {
    
    /**
     * Register device with the push server.
     * Called once after login — returns a device token for WebSocket routing.
     */
    @GET("api/v1/push/register")
    suspend fun registerDevice(
        @Header("Authorization") token: String,
        @Query("platform") platform: String = "android",
        @Query("device_id") deviceId: String
    ): Response<RegisterResponse>
    
    /**
     * Poll for unseen notification counts.
     * This is the polling fallback when WebSocket is unavailable.
     */
    @GET("api/v1/messages/unseen")
    suspend fun getUnseenMessageCount(
        @Header("Authorization") authHeader: String
    ): Response<UnseenCountResponse>
    
    /**
     * Fetch unread messages for the current user.
     * Called after a push notification fires.
     */
    @GET("api/v1/messages/unread")
    suspend fun fetchUnreadMessages(
        @Header("Authorization") authHeader: String,
        @Query("since") sinceTimestamp: Long = 0
    ): Response<List<com.securechat.app.data.model.Message>>
    
    /**
     * Mark a message as read.
     */
    @GET("api/v1/messages/read")
    suspend fun markAsRead(
        @Header("Authorization") authHeader: String,
        @Query("messageId") messageId: String
    ): Response<Unit>
    
    /**
     * Get conversation list.
     */
    @GET("api/v1/conversations")
    suspend fun getConversations(
        @Header("Authorization") authHeader: String
    ): Response<List<com.securechat.app.data.model.Conversation>>
}

data class RegisterResponse(
    val serverToken: String? = null,
    val serverUrl: String? = null,
    val heartbeatInterval: Int? = null
)

data class UnseenCountResponse(
    val totalCount: Int? = null,
    val conversations: Map<String, Int>? = null
)
