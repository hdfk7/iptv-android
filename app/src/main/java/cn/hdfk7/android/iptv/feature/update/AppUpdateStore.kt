package cn.hdfk7.android.iptv.feature.update

import android.content.Context
import cn.hdfk7.android.iptv.BuildConfig
import java.io.File

internal class AppUpdateStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "updates")
    val apk = File(directory, "iptv-update.apk")

    fun pendingRelease(): AppRelease? {
        val version = preferences.getInt("version", 0)
        if (version <= 0 || !apk.isFile || apk.length() == 0L) {
            return null
        }
        val name = preferences.getString("name", null) ?: return null
        val url = preferences.getString("url", null) ?: return null
        return AppRelease(version, name, url, preferences.getString("notes", "").orEmpty())
            .takeIf(predicate = { release -> release.differsFrom(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME) })
    }

    fun save(release: AppRelease): Boolean {
        return preferences.edit()
            .putInt("version", release.versionCode)
            .putString("name", release.versionName)
            .putString("url", release.downloadUrl)
            .putString("notes", release.notes)
            .commit()
    }

    fun shouldResume(): Boolean {
        return preferences.getBoolean("authorizing", false) && pendingRelease() != null
    }

    fun markAuthorizing(): Boolean = preferences.edit().putBoolean("authorizing", true).commit()

    fun clearAuthorizing(): Unit {
        preferences.edit().remove("authorizing").apply()
    }

    fun discard(): Unit {
        apk.delete()
        preferences.edit().clear().apply()
    }
}
