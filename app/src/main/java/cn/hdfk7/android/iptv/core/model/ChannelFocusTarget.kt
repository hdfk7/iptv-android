package cn.hdfk7.android.iptv.core.model

fun channelFocusTarget(channels: List<Channel>, playingId: String?): Channel? {
    return channels.firstOrNull(predicate = { channel -> channel.id == playingId }) ?: channels.firstOrNull()
}
