package cn.hdfk7.android.iptv.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.feature.update.AppUpdateStore
import cn.hdfk7.android.iptv.feature.update.installedReleaseMessage
import kotlin.concurrent.thread
import cn.hdfk7.android.iptv.core.data.local.ProxySettingsStore
import cn.hdfk7.android.iptv.core.data.network.PlaylistDownloader
import cn.hdfk7.android.iptv.core.data.repository.PlaylistRepository

class IptvApplication : Application() {
    override fun onCreate(): Unit {
        super.onCreate()
        val store = AppUpdateStore(this)
        val release = store.installingRelease() ?: return
        thread(name = "iptv-update-verification", block = {
            val result = installedReleaseMessage(this, release)
            if (result != null) {
                store.clearInstalling()
                if (result == R.string.app_update_installed_checksum_mismatch) {
                    Handler(Looper.getMainLooper()).post(Runnable {
                        Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    })
                }
            }
        })
    }

    internal val viewingPreferences by lazy(initializer = { getSharedPreferences("viewing", MODE_PRIVATE) })
    val proxySettings by lazy(initializer = { ProxySettingsStore(this) })
    internal val downloader by lazy(initializer = { PlaylistDownloader(settings = { checkNotNull(proxySettings.current) }) })
    val playlistRepository: PlaylistRepository by lazy(initializer = {
        PlaylistRepository(
            filesDir,
            downloader,
            restoreActiveSource = { viewingPreferences.getBoolean("resume", false) }
        )
    })
}
