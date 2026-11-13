package cn.hdfk7.android.iptv.feature.update

import android.content.Context
import cn.hdfk7.android.iptv.BuildConfig
import java.io.File

internal class AppUpdateStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val installation = context.getSharedPreferences("app_update_installation", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "updates")
    val apk = File(directory, "iptv-update.apk")

    fun pendingRelease(): AppRelease? {
        val version = preferences.getInt("version", 0)
        if (version <= 0 || !apk.isFile || apk.length() == 0L) {
            return null
        }
        val name = preferences.getString("name", null) ?: return null
        val url = preferences.getString("url", null) ?: return null
        return AppRelease(version, name, url, preferences.getString("notes", "").orEmpty(), preferences.getString("sha256", null))
            .takeIf(predicate = { release -> release.differsFrom(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME) })
    }

    fun save(release: AppRelease): Boolean {
        return preferences.edit()
            .putInt("version", release.versionCode)
            .putString("name", release.versionName)
            .putString("url", release.downloadUrl)
            .putString("notes", release.notes)
            .putString("sha256", release.sha256)
            .commit()
    }

    fun markInstalling(release: AppRelease): Boolean {
        return installation.edit().clear()
            .putInt("version", release.versionCode)
            .putString("name", release.versionName)
            .putString("sha256", release.sha256)
            .commit()
    }

    fun installingRelease(): AppRelease? {
        val hash = installation.getString("sha256", null) ?: return null
        val name = installation.getString("name", null) ?: return null
        return AppRelease(installation.getInt("version", 0), name, "", sha256 = hash)
    }

    fun clearInstalling(): Unit {
        installation.edit().clear().apply()
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
