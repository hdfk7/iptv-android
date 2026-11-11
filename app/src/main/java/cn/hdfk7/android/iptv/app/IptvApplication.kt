package cn.hdfk7.android.iptv.app

import android.app.Application
import cn.hdfk7.android.iptv.core.data.local.ProxySettingsStore
import cn.hdfk7.android.iptv.core.data.network.PlaylistDownloader
import cn.hdfk7.android.iptv.core.data.repository.PlaylistRepository

class IptvApplication : Application() {
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
