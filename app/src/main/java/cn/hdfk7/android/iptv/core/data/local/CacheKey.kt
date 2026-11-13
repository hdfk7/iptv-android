package cn.hdfk7.android.iptv.core.data.local

import java.security.MessageDigest

internal fun cacheKey(value: String): String {
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
}
