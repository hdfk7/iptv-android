package cn.hdfk7.android.iptv.core.model

internal data class AdvancedSettings(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val lineTimeoutMs: Int,
    val debugPlayback: Boolean,
    val updateManifestUrl: String
)

internal fun parseTimeoutSeconds(value: String): Int? = runCatching(block = {
    value.trim().toBigDecimal().movePointRight(3).intValueExact().takeIf(predicate = { ms -> ms > 0 })
}).getOrNull()

internal fun formatTimeoutSeconds(value: Int): String =
    value.toBigDecimal().movePointLeft(3).stripTrailingZeros().toPlainString()
