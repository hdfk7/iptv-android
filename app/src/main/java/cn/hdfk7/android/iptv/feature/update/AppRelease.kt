package cn.hdfk7.android.iptv.feature.update

internal data class AppRelease(val versionCode: Int, val versionName: String, val downloadUrl: String, val notes: String = "", val sha256: String? = null) {
    fun differsFrom(installedCode: Long, installedName: String?): Boolean {
        return versionCode.toLong() != installedCode || versionName != installedName
    }
}
