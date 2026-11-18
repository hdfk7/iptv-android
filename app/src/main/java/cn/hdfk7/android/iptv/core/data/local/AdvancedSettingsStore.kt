package cn.hdfk7.android.iptv.core.data.local

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.hdfk7.android.iptv.BuildConfig
import cn.hdfk7.android.iptv.core.model.AdvancedSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class AdvancedSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "advanced-settings.json"))
    val defaults = AdvancedSettings(
        BuildConfig.STREAM_CONNECT_TIMEOUT_MS,
        BuildConfig.STREAM_READ_TIMEOUT_MS,
        BuildConfig.STREAM_PROBE_TIMEOUT_MS,
        BuildConfig.APP_DEBUG_SWITCH,
        BuildConfig.APP_UPDATE_MANIFEST_URL
    )
    var current by mutableStateOf(load())
        private set

    fun save(settings: AdvancedSettings): Boolean {
        val json = JSONObject()
            .put("connectTimeoutMs", settings.connectTimeoutMs)
            .put("readTimeoutMs", settings.readTimeoutMs)
            .put("lineTimeoutMs", settings.lineTimeoutMs)
            .put("debugPlayback", settings.debugPlayback)
            .put("updateManifestUrl", settings.updateManifestUrl)
        val output = try {
            file.startWrite()
        } catch (_: IOException) {
            return false
        }
        return try {
            output.write(json.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            true
        } catch (_: IOException) {
            file.failWrite(output)
            false
        }
    }

    fun applySaved(settings: AdvancedSettings): Unit {
        current = settings
    }

    private fun load(): AdvancedSettings = runCatching(block = {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        AdvancedSettings(
            json.optInt("connectTimeoutMs").takeIf(predicate = { ms -> ms > 0 }) ?: defaults.connectTimeoutMs,
            json.optInt("readTimeoutMs").takeIf(predicate = { ms -> ms > 0 }) ?: defaults.readTimeoutMs,
            json.optInt("lineTimeoutMs").takeIf(predicate = { ms -> ms > 0 }) ?: defaults.lineTimeoutMs,
            json.optBoolean("debugPlayback", defaults.debugPlayback),
            json.optString("updateManifestUrl").takeIf(::validUpdateManifestUrl) ?: defaults.updateManifestUrl
        )
    }).getOrDefault(defaults)
}

internal fun validUpdateManifestUrl(address: String): Boolean {
    val url = address.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
}
