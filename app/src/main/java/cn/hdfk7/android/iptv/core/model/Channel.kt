package cn.hdfk7.android.iptv.core.model

data class Channel(
    val name: String,
    val group: String,
    val streams: List<String>,
    val sourceId: String = "",
    val sourceName: String = "",
    val responseTimesMs: Map<String, Int> = emptyMap(),
    val guideRefs: List<GuideRef> = emptyList(),
    val guideIds: List<String> = emptyList()
) {
    val key: String = ChannelNames.key(name)
    val id: String = channelId(sourceId, key)

    fun streamsByResponseTime(): List<String> {
        if (responseTimesMs.isEmpty()) {
            return streams
        }
        return streams.sortedBy(selector = { url -> responseTimesMs[url] ?: Int.MAX_VALUE })
    }
}

internal fun channelId(sourceId: String, channelKey: String): String = "$sourceId/$channelKey"

data class PlaylistSnapshot(val channels: List<Channel>, val updatedAt: Long)

data class RefreshResult(val snapshot: PlaylistSnapshot?, val failed: Boolean)
