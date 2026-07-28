package com.securechat.app.crypto.keyexchange

import android.util.Base64

/**
 * Utility for base64 encoding without imports conflict.
 */
object Base64Util {
    fun encodeToString(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decode(str: String): ByteArray {
        return Base64.decode(str, Base64.NO_WRAP)
    }
}
