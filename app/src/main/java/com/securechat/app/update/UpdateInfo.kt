package com.securechat.app.update

/**
 * 服务端 /api/update/check 返回的版本信息。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** APK 下载地址：可为绝对 URL，或相对路径（如 /apk/app-debug.apk，会自动拼接域名） */
    val apkUrl: String,
    val forceUpdate: Boolean = false,
    val changelog: String = "",
    val size: Long = 0L
)
