package com.securechat.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import com.securechat.app.util.ServerConfig

/**
 * OTA 远程升级管理器
 *
 * - [checkUpdate] 调用服务端 /api/update/check 获取最新版本（带失败重试）
 * - [downloadAndInstall] 下载 APK 并触发系统安装器
 *
 * 下载说明：直接使用 update.json 中的【域名】URL 下载（与浏览器访问方式一致），
 * 由系统 DNS 解析域名、OkHttp 负责连接与流式下载。
 *
 * 早期版本曾尝试把域名替换为解析出的 IP 再直连，以规避 DDNS 双 IP 问题；但实测
 * 在蜂窝网络下，运营商会对「明文 IP:端口」格式的 HTTP 请求插入透明代理/拦截，
 * 返回 404（而同一地址用域名访问则正常）。故统一改用域名直连。
 *
 * 下载进度通过 [onProgress] 回传（0–100；-1 表示总大小未知）。回调在主线程执行。
 */
object UpdateManager {

    private const val MAX_RETRY = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data class NoUpdate(val versionName: String) : CheckResult
        data class Error(val msg: String) : CheckResult
    }

    /**
     * 给 URL 追加缓存破坏参数，强制绕过运营商/代理对版本检查与 APK 的透明缓存。
     * 实测：部分蜂窝运营商的透明代理会忽略服务端 no-store 头、按 URL 路径返回陈旧响应，
     * 导致 App 内检查更新拿到旧版本号（如一直显示 1.0.6）。追加时间戳保证每次请求都是缓存未命中。
     */
    private fun withCacheBuster(url: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}_=${System.currentTimeMillis()}"
    }

    /**
     * 检查是否有新版本（带重试）。
     * @param currentVersionCode 当前 App 的 BuildConfig.VERSION_CODE
     */
    suspend fun checkUpdate(currentVersionCode: Int): CheckResult = withContext(Dispatchers.IO) {
        var last: CheckResult = CheckResult.Error("未知错误")
        repeat(MAX_RETRY) { attempt ->
            last = runCatching {
                val req = Request.Builder()
                    // 缓存破坏放 PATH（而非 query）：部分运营商透明代理按 URL path 缓存、忽略 query，
                    // 旧版 ?_=时间戳 完全失效。每次请求 /ota/check/<时间戳> 路径唯一，代理必然回源拿最新。
                    .url("${ServerConfig.getBaseUrl()}/ota/check/${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                if (!resp.isSuccessful) throw Exception("检查更新失败：HTTP ${resp.code}")
                val o = JSONObject(body)
                if (!o.optBoolean("success", false)) throw Exception(o.optString("error", "无更新信息"))
                // 兼容 apkUrl / url 两种字段命名
                val rawApk = o.optString("apkUrl", "").ifEmpty { o.optString("url", "") }
                val info = UpdateInfo(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.optString("versionName", "?"),
                    apkUrl = rawApk,
                    forceUpdate = o.optBoolean("forceUpdate", false),
                    changelog = o.optString("changelog", ""),
                    size = o.optLong("size", 0L)
                )
                if (info.versionCode <= currentVersionCode) CheckResult.NoUpdate(info.versionName)
                else CheckResult.Available(info)
            }.fold(
                onSuccess = { it },
                onFailure = { CheckResult.Error(it.message ?: "未知错误") }
            )
            // 成功（有更新/无更新）直接返回；仅出错时重试
            if (last !is CheckResult.Error) return@withContext last
            if (attempt < MAX_RETRY - 1) delay(800L * (attempt + 1))
        }
        last
    }

    /**
     * 下载 APK 并触发系统安装界面。
     *
     * 直接使用域名 URL 下载（与浏览器一致，规避蜂窝网络下 IP 直连被拦截的问题）。
     * 外层保留整体重试。
     * @param onProgress 进度回调（主线程）
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val rawUrl = if (info.apkUrl.startsWith("http")) info.apkUrl else "${ServerConfig.getBaseUrl()}${info.apkUrl}"
        val downloadUrl = withCacheBuster(rawUrl)
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        dir.mkdirs()
        val apkFile = File(dir, "securechat_update.apk")

        var lastError: Exception? = null
        repeat(MAX_RETRY) { attempt ->
            try {
                if (apkFile.exists()) apkFile.delete()
                val ok = downloadApk(downloadUrl, apkFile, onProgress)
                if (ok) {
                    // 完整性校验：APK 必须以 ZIP 头 PK 开头，且大小与声明一致
                    verifyApk(apkFile, info.size)
                    // 真实性校验：读出下载 APK 的真实 versionCode，必须与期望一致。
                    // 运营商透明代理可能把旧包（同尺寸、同样 PK 头）当新包下发，
                    // 仅靠大小/PK 头无法识别，必须校验真实版本号，否则会静默装成旧版。
                    verifyApkVersion(context, apkFile, info.versionCode)
                    // 安装前确认已授予「安装未知应用」权限（否则系统安装器会静默失败）
                    ensureInstallPermission(context)
                    withContext(Dispatchers.Main) { installApk(context, apkFile) }
                    return@withContext
                }
            } catch (e: Exception) {
                lastError = e
                runCatching { apkFile.delete() }
            }
            if (attempt < MAX_RETRY - 1) delay(800L * (attempt + 1))
        }
        throw lastError ?: Exception("下载失败：所有重试均失败")
    }

    /**
     * 校验下载到的 APK：魔数 PK + 大小一致，避免运营商缓存/截断导致的「下到旧包/坏包」。
     */
    private fun verifyApk(file: File, expectedSize: Long) {
        if (!file.exists() || file.length() == 0L) {
            throw Exception("下载校验失败：文件为空")
        }
        // 读取前 2 字节应为 ZIP/APK 魔数 "PK" (0x50 0x4B)
        val header = ByteArray(2)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(header) != 2) throw Exception("下载校验失败：文件头读取异常")
        }
        if (!(header[0] == 0x50.toByte() && header[1] == 0x4B.toByte())) {
            throw Exception("下载校验失败：不是有效的安装包（可能被运营商缓存/篡改）")
        }
        if (expectedSize > 0 && file.length() != expectedSize) {
            throw Exception("下载校验失败：大小不符（期望 $expectedSize，实际 ${file.length()}）")
        }
    }

    /**
     * 校验下载 APK 的【真实版本号】，防止运营商缓存把旧包（同尺寸、同样 PK 头）当新包下发。
     * 通过 PackageManager 读取 APK 文件的 versionCode，必须与期望一致，否则拒绝安装。
     * 读取失败（极少见，如高版本 Android 限制）时不阻断，仅记录，避免误伤正常更新。
     */
    private fun verifyApkVersion(context: Context, file: File, expectedVersionCode: Int) {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
            val real = info?.versionCode ?: return
            if (real != expectedVersionCode) {
                throw Exception(
                    "下载校验失败：安装包版本($real)与预期($expectedVersionCode)不符，" +
                    "疑似被运营商缓存。请改用手机浏览器打开下载地址手动安装，或稍后重试。"
                )
            }
        }.onFailure { e ->
            // 仅当明确是版本不符才向上抛出；其他解析异常静默忽略
            if (e is Exception && e.message?.contains("下载校验失败") == true) throw e
        }
    }

    /**
     * 安装未知应用权限预检（Android 8+）。
     * 若未授权，直接拉起系统设置页引导用户开启，并抛出明确错误，避免静默失败。
     */
    private fun ensureInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                throw Exception("请先在系统设置中允许「SecureChat」安装未知应用，然后重试更新")
            }
        }
    }

    /**
     * 用 OkHttp 通过【域名】URL 流式下载 APK，并做完整性校验（非空）。
     * 下载进度通过 [onProgress] 在主线程回传。
     */
    private fun downloadApk(rawUrl: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        val req = Request.Builder().url(rawUrl).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw Exception("下载失败：HTTP ${resp.code}")
        }
        val total = resp.body?.contentLength() ?: -1L
        val input = resp.body?.byteStream()
            ?: run { resp.close(); throw Exception("下载失败：响应为空") }
        val output = dest.outputStream()
        val buffer = ByteArray(8192)
        var downloaded = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            val p = if (total > 0) ((downloaded * 100) / total).toInt() else -1
            mainHandler.post { onProgress(p) }
        }
        output.flush(); output.close(); input.close(); resp.close()

        // 命中无效地址时常返回 0 字节空响应，判掉以便重试
        if (downloaded == 0L) throw Exception("下载失败：响应为空（0 字节）")
        return true
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
