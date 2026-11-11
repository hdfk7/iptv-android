package cn.hdfk7.android.iptv.core.model

data class Channel(
    val name: String,
    val group: String,
    val streams: List<String>,
    val sourceId: String = "",
    val sourceName: String = ""
) {
    val key: String = ChannelNames.key(name)
    val id: String = "$sourceId/$key"
}

data class PlaylistSnapshot(val channels: List<Channel>, val updatedAt: Long)

data class RefreshResult(val snapshot: PlaylistSnapshot?, val failed: Boolean)
