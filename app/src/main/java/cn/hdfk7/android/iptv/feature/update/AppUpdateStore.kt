package cn.hdfk7.android.iptv.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import cn.hdfk7.android.iptv.BuildConfig
import java.io.File

internal class AppUpdateStore(private val context: Context) {
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
    }

    fun isUpdate(release: AppRelease): Boolean {
        return release.isUpdateFor(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME, installedDigest = ::installedDigest)
    }

    private fun installedDigest(): String? = File(context.applicationInfo.sourceDir).updateDigest()

    fun previousInstallDigest(): String? = installation.getString("previousDigest", null)

    fun save(release: AppRelease): Boolean {
        return preferences.edit()
            .putInt("version", release.versionCode)
            .putString("name", release.versionName)
            .putString("url", release.downloadUrl)
            .putString("notes", release.notes)
            .putString("sha256", release.sha256)
            .commit()
    }

    fun markInstalling(release: AppRelease, uri: Uri): Boolean {
        val previousDigest = if (release.sha256 != null &&
            !release.differsFrom(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)) {
            installedDigest() ?: return false
        } else {
            null
        }
        revokeInstallerReadPermission()
        return installation.edit().clear()
            .putString("apkUri", uri.toString())
            .putString("previousDigest", previousDigest)
            .putInt("version", release.versionCode)
            .putString("name", release.versionName)
            .putString("sha256", release.sha256)
            .commit()
    }

    fun installingRelease(): AppRelease? {
        val version = installation.getInt("version", 0)
        if (version <= 0) {
            return null
        }
        val name = installation.getString("name", null) ?: return null
        return AppRelease(version, name, "", sha256 = installation.getString("sha256", null))
    }

    fun clearInstalling(): Unit {
        revokeInstallerReadPermission()
        installation.edit().clear().apply()
    }

    private fun revokeInstallerReadPermission(): Unit {
        val uri = installation.getString("apkUri", null) ?: return
        context.revokeUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
