package com.securechat.app.network.push

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences wrapper for storing small config values.
 * Used by PushConnectionService for device_id and auth_token.
 */
object PreferenceManager {
    
    private const val PREFS_NAME = "securechat_prefs"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getString(context: Context, key: String, defaultValue: String?): String? {
        return getPrefs(context).getString(key, defaultValue)
    }
    
    fun setString(context: Context, key: String, value: String?) {
        getPrefs(context).edit().putString(key, value).apply()
    }
    
    fun getInt(context: Context, key: String, defaultValue: Int): Int {
        return getPrefs(context).getInt(key, defaultValue)
    }
    
    fun setInt(context: Context, key: String, value: Int) {
        getPrefs(context).edit().putInt(key, value).apply()
    }
    
    fun getLong(context: Context, key: String, defaultValue: Long): Long {
        return getPrefs(context).getLong(key, defaultValue)
    }
    
    fun setLong(context: Context, key: String, value: Long) {
        getPrefs(context).edit().putLong(key, value).apply()
    }
    
    fun getBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
        return getPrefs(context).getBoolean(key, defaultValue)
    }
    
    fun setBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }
    
    fun removeKey(context: Context, key: String) {
        getPrefs(context).edit().remove(key).apply()
    }
    
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
