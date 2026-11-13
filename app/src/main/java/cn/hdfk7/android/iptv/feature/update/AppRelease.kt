package cn.hdfk7.android.iptv.feature.update

internal data class AppRelease(val versionCode: Int, val versionName: String, val downloadUrl: String, val notes: String = "") {
    fun differsFrom(installedCode: Long, installedName: String?): Boolean {
        return versionCode.toLong() != installedCode || versionName != installedName
    }
}
