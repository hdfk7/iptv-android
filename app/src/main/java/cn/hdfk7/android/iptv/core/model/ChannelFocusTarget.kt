package cn.hdfk7.android.iptv.core.model

fun channelFocusTarget(channels: List<Channel>, playingId: String?, returnToId: String? = null): Channel? {
    val returningChannel = returnToId?.let(block = { id ->
        channels.firstOrNull(predicate = { channel -> channel.id == id })
    })
    return returningChannel ?: channels.firstOrNull(predicate = { channel -> channel.id == playingId }) ?: channels.firstOrNull()
}
