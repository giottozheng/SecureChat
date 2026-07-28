package com.securechat.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 统一的「服务端地址」管理。
 *
 * 客户端所有对服务端的 HTTP / WebSocket 连接都从这里取地址，
 * 取代原先散落在各处的硬编码默认服务端地址（示例：`http://securechat.example.com:9999`）。
 *
 * 用户可在「设置 → 服务端连接」中绑定自定义的服务端（IP 或域名 + 端口）。
 * 未配置时使用默认地址 [DEFAULT_HOST]:[DEFAULT_PORT]。
 */
object ServerConfig {
    private const val PREFS = "securechat_prefs"
    private const val KEY_HOST = "server_host"
    private const val KEY_PORT = "server_port"

    // 示例默认服务端地址（仓库不含真实部署地址）。正式打包前请改为你的服务端域名/IP:端口，
    // 或在登录界面的「服务端地址」中手动填写。
    const val DEFAULT_HOST = "securechat.example.com"
    const val DEFAULT_PORT = 9999

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 中调用一次，之后无 Context 也能取地址。 */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun prefs(ctx: Context?): SharedPreferences? {
        val c = ctx ?: appContext
        return c?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getHost(ctx: Context? = appContext): String {
        val h = prefs(ctx)?.getString(KEY_HOST, null)
        return if (!h.isNullOrBlank()) h.trim() else DEFAULT_HOST
    }

    fun getPort(ctx: Context? = appContext): Int {
        val p = prefs(ctx)?.getInt(KEY_PORT, -1) ?: -1
        return if (p > 0) p else DEFAULT_PORT
    }

    /** HTTP 基础地址，如 http://1.hackdz.dpdns.org:9999 */
    fun getBaseUrl(ctx: Context? = appContext): String =
        "http://${getHost(ctx)}:${getPort(ctx)}"

    /** WebSocket 基础地址，如 ws://1.hackdz.dpdns.org:9999 */
    fun getWsUrl(ctx: Context? = appContext): String =
        "ws://${getHost(ctx)}:${getPort(ctx)}"

    /** 推送 WebSocket 完整地址（含 token 与 deviceId 查询参数） */
    fun getWsPushUrl(ctx: Context? = appContext, token: String, deviceId: String): String =
        "${getWsUrl(ctx)}/ws/push?token=$token&deviceId=$deviceId"

    /** 拼接完整 HTTP 路径，如 getUrl("/api/login") */
    fun getUrl(ctx: Context? = appContext, path: String): String {
        val base = getBaseUrl(ctx)
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    fun setServer(ctx: Context, host: String, port: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
    }

    /**
     * 解析用户输入的「服务端地址」字符串，支持以下形式：
     *   - `域名或IP:端口`      （如 192.168.10.99:8080）
     *   - `域名或IP`           （省略端口 → 使用默认端口）
     *   - `http(s)://host:port` / `ws://host:port`（自动去除协议头）
     * 返回 (host, port)。host 为空时回退到 [DEFAULT_HOST]，端口非法时回退到 [DEFAULT_PORT]。
     */
    fun parseAddress(raw: String): Pair<String, Int> {
        var s = raw.trim()
            .removePrefix("wss://").removePrefix("ws://")
            .removePrefix("https://").removePrefix("http://")
            .removeSuffix("/")
        val idx = s.lastIndexOf(':')
        return if (idx > 0) {
            val host = s.substring(0, idx).trim()
            val port = s.substring(idx + 1).trim().toIntOrNull() ?: DEFAULT_PORT
            (if (host.isBlank()) DEFAULT_HOST else host) to port
        } else {
            (if (s.isBlank()) DEFAULT_HOST else s) to DEFAULT_PORT
        }
    }

    /** 直接根据用户输入的地址字符串持久化服务端配置。 */
    fun setServerFromAddress(ctx: Context, address: String) {
        val (host, port) = parseAddress(address)
        setServer(ctx, host, port)
    }

    fun isDefault(ctx: Context? = appContext): Boolean =
        getHost(ctx) == DEFAULT_HOST && getPort(ctx) == DEFAULT_PORT

    fun resetToDefault(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .apply()
    }
}
