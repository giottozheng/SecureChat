package com.securechat.app.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.securechat.app.crypto.MessageEncryptor
import com.securechat.app.util.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件传输辅助：负责「下载端到端加密密文 -> 本地信封解密 -> 存入 Downloads -> 用系统查看器打开」。
 * 服务器只保存密文，本类在客户端完成解密，文件内容不落地为明文于服务器。
 */
@Singleton
class FileTransferHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageEncryptor: MessageEncryptor
) {
    companion object {
        private const val TAG = "FileTransferHelper"
        private const val SUBDIR = "securechat"
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val authToken: String
        get() = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)
            .getString("auth_token", "") ?: ""

    /**
     * 下载加密文件密文 -> 信封解密 -> 保存到 Downloads/securechat，返回目标文件（失败返回 null）。
     */
    suspend fun fetchDecryptSave(fileId: String, fileName: String, fileMime: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(ServerConfig.getUrl(context, "/api/files/$fileId"))
                    .addHeader("Authorization", "Bearer $authToken")
                    .get()
                    .build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download failed HTTP ${response.code}")
                    response.close()
                    return@withContext null
                }
                val cipherText = response.body?.string() ?: return@withContext null
                response.close()
                val plainBytes = messageEncryptor.decryptBytes(cipherText)
                    ?: run { Log.e(TAG, "Decrypt file failed"); return@withContext null }
                saveBytes(plainBytes, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "fetchDecryptSave failed", e)
                null
            }
        }
    }

    /** 本地已解密的字节（如图片原图）直接保存到 Downloads/securechat。 */
    fun saveBytes(bytes: ByteArray, fileName: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
            dir.mkdirs()
            val target = File(dir, sanitize(fileName))
            target.writeBytes(bytes)
            target
        } catch (e: Exception) {
            Log.e(TAG, "saveBytes failed", e)
            null
        }
    }

    /** 用系统查看器打开文件（视频/文档/图片均走 ACTION_VIEW，由系统按 mime 选择对应应用）。 */
    fun openInSystemViewer(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openInSystemViewer failed", e)
        }
    }

    /** 是否为视频（用于判断气泡显示「播放」按钮）。 */
    fun isVideo(mime: String?): Boolean = (mime ?: "").startsWith("video/")

    private fun sanitize(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (base.isEmpty()) "file_${System.currentTimeMillis()}" else base
    }
}
