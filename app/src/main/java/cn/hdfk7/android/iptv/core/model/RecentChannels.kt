package cn.hdfk7.android.iptv.core.model

internal object RecentChannels {
    private const val LIMIT = 20

    fun record(ids: List<String>, channelId: String): List<String> {
        return (listOf(channelId) + ids.filterNot(predicate = { id -> id == channelId })).take(LIMIT)
    }

    fun visible(ids: List<String>, channels: List<Channel>): List<Channel> {
        val byId = channels.associateBy(keySelector = Channel::id)
        return ids.mapNotNull(transform = byId::get)
    }
}
