package cn.hdfk7.android.iptv.feature.update

internal data class AppRelease(val versionCode: Int, val versionName: String, val downloadUrl: String, val notes: String = "", val sha256: String? = null) {
    fun isUpdateFor(installedCode: Long, installedName: String?, installedDigest: () -> String?): Boolean {
        if (differsFrom(installedCode, installedName)) {
            return true
        }
        val expected = sha256 ?: return false
        val actual = installedDigest() ?: return false
        return !expected.equals(actual, ignoreCase = true)
    }

    fun matchesDownload(saved: AppRelease): Boolean {
        return !differsFrom(saved.versionCode.toLong(), saved.versionName) &&
            sha256.equals(saved.sha256, ignoreCase = true) &&
            (sha256 != null || downloadUrl == saved.downloadUrl)
    }

    fun differsFrom(installedCode: Long, installedName: String?): Boolean {
        return versionCode.toLong() != installedCode || versionName != installedName
    }
}
