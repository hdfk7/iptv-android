package cn.hdfk7.android.iptv.feature.player

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.PlaylistSource
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.model.PlaybackLine

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, ERROR }

data class PlayerState(
    val channels: List<Channel> = emptyList(),
    val sources: List<PlaylistSource> = emptyList(),
    val activeSourceId: String = SourceLibrary.FAVORITES_ID,
    val favoriteIds: Set<String> = emptySet(),
    val selectedId: String? = null,
    val selectedName: String? = null,
    val selectedSourceName: String? = null,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val updatedAt: Long? = null,
    val stale: Boolean = false,
    val playback: PlaybackStatus = PlaybackStatus.IDLE,
    val line: Int = 0,
    val lineCount: Int = 0,
    val lines: List<PlaybackLine> = emptyList(),
    val fixedVolume: Boolean = false,
    val infoVisible: Boolean = false,
    val message: Int? = null
)
