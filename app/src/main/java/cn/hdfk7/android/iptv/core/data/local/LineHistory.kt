package cn.hdfk7.android.iptv.core.data.local

import android.content.SharedPreferences
import cn.hdfk7.android.iptv.core.model.LineQuality
import java.security.MessageDigest
import org.json.JSONObject

internal class LineHistory(private val preferences: SharedPreferences) {
    private companion object {
        const val MAX_ENTRIES = 512
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }

    private val timestamps = preferences.all.mapValues(transform = { entry ->
        runCatching(block = { JSONObject(entry.value as String).getLong("at") }).getOrDefault(0L)
    }).toMutableMap()

    init {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        for ((key, at) in timestamps.toMap()) {
            if (now - at !in 0..MAX_AGE_MS) {
                timestamps.remove(key)
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun key(scope: String, program: String, url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$scope\u0000$program\u0000$url".toByteArray())
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun read(key: String): LineQuality? {
        val at = timestamps[key] ?: return null
        if (System.currentTimeMillis() - at !in 0..MAX_AGE_MS) {
            return null
        }
        return runCatching(block = {
            val json = JSONObject(preferences.getString(key, null) ?: return null)
            LineQuality(
                width = json.getInt("width"), height = json.getInt("height"), frameRate = json.getDouble("fps").toFloat(),
                startupMs = json.getLong("startup"), playedMs = json.getLong("played"), stalledMs = json.getLong("stalled"),
                renderedFrames = json.getInt("rendered"), droppedFrames = json.getInt("dropped"), failures = json.getInt("failures")
            )
        }).getOrNull()
    }

    fun write(key: String, quality: LineQuality): Unit {
        val now = System.currentTimeMillis()
        val json = JSONObject().put("at", now).put("width", quality.width).put("height", quality.height)
            .put("fps", quality.frameRate.toDouble()).put("startup", quality.startupMs).put("played", quality.playedMs)
            .put("stalled", quality.stalledMs).put("rendered", quality.renderedFrames)
            .put("dropped", quality.droppedFrames).put("failures", quality.failures)
        val editor = preferences.edit()
        timestamps[key] = now
        if (timestamps.size > MAX_ENTRIES) {
            val oldest = timestamps.minBy(selector = { entry -> entry.value }).key
            timestamps.remove(oldest)
            editor.remove(oldest)
        }
        editor.putString(key, json.toString()).apply()
    }
}
