package cn.hdfk7.android.iptv.core.model

internal enum class DomesticChannelGroup {
    ALL, CCTV, SATELLITE, OTHER
}

internal fun groupDomesticChannels(channels: List<Channel>): Map<DomesticChannelGroup, List<Channel>> {
    val groups = channels.groupBy(keySelector = { channel ->
        when (ChannelCategory.classify(channel)) {
            ChannelCategory.CCTV -> DomesticChannelGroup.CCTV
            ChannelCategory.SATELLITE -> DomesticChannelGroup.SATELLITE
            else -> DomesticChannelGroup.OTHER
        }
    })
    val cctv = groups[DomesticChannelGroup.CCTV].orEmpty().sortedWith(
        compareBy<Channel>({ channel ->
            channel.key.removePrefix("CCTV").removeSuffix("+").toIntOrNull() ?: Int.MAX_VALUE
        }).thenBy({ channel -> channel.key.endsWith("+") })
    )
    return mapOf(
        DomesticChannelGroup.ALL to channels,
        DomesticChannelGroup.CCTV to cctv,
        DomesticChannelGroup.SATELLITE to groups[DomesticChannelGroup.SATELLITE].orEmpty(),
        DomesticChannelGroup.OTHER to groups[DomesticChannelGroup.OTHER].orEmpty()
    )
}
