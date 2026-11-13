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

    private data class Entry(val recordedAt: Long, val quality: LineQuality)
    private val entries = mutableMapOf<String, Entry>()
    private var keyScope = ""
    private var keyProgram = ""
    private val keys = mutableMapOf<String, String>()

    init {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            val entry = decode(value)
            if (entry != null && now - entry.recordedAt in 0..MAX_AGE_MS) {
                entries[key] = entry
            } else {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun key(scope: String, program: String, url: String): String {
        if (keyScope != scope || keyProgram != program || keys.size >= MAX_ENTRIES && url !in keys) {
            keys.clear()
            keyScope = scope
            keyProgram = program
        }
        return keys.getOrPut(key = url, defaultValue = {
            val bytes = MessageDigest.getInstance("SHA-256").digest("$scope\u0000$program\u0000$url".toByteArray())
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        })
    }

    fun read(key: String): LineQuality? {
        val entry = entries[key] ?: return null
        return entry.quality.takeIf(predicate = { System.currentTimeMillis() - entry.recordedAt in 0..MAX_AGE_MS })
    }

    private fun decode(value: Any?): Entry? {
        val stored = value as? String ?: return null
        return runCatching(block = {
            val json = JSONObject(stored)
            Entry(json.getLong("at"), LineQuality(
                width = json.getInt("width"), height = json.getInt("height"), frameRate = json.getDouble("fps").toFloat(),
                startupMs = json.getLong("startup"), playedMs = json.getLong("played"), stalledMs = json.getLong("stalled"),
                renderedFrames = json.getInt("rendered"), droppedFrames = json.getInt("dropped"), failures = json.getInt("failures")
            ))
        }).getOrNull()
    }

    fun write(key: String, quality: LineQuality): Unit {
        val now = System.currentTimeMillis()
        val json = JSONObject().put("at", now).put("width", quality.width).put("height", quality.height)
            .put("fps", quality.frameRate.toDouble()).put("startup", quality.startupMs).put("played", quality.playedMs)
            .put("stalled", quality.stalledMs).put("rendered", quality.renderedFrames)
            .put("dropped", quality.droppedFrames).put("failures", quality.failures)
        val editor = preferences.edit()
        entries[key] = Entry(now, quality)
        if (entries.size > MAX_ENTRIES) {
            val oldest = entries.minBy(selector = { entry -> entry.value.recordedAt }).key
            entries.remove(oldest)
            editor.remove(oldest)
        }
        editor.putString(key, json.toString()).apply()
    }
}
