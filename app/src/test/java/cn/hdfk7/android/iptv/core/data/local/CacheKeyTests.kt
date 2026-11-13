package cn.hdfk7.android.iptv.core.data.local

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CacheKeyTests {
    @Test
    fun producesTheSameFileKeysAsPreviousVersions(): Unit {
        val values = listOf("", "https://example.org/live.m3u", "https://example.org/节目单.xml", "source\u0000CCTV5+")
        for (value in values) {
            val legacyKey = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString(separator = "", transform = { byte -> "%02x".format(byte) })
            assertEquals(legacyKey, cacheKey(value))
        }
    }
}
