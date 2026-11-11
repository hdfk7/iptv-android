package cn.hdfk7.android.iptv.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DomesticChannelGroupTests {
    @Test
    fun keepsAllChannelsAndOrdersOnlyTheCctvGroup(): Unit {
        val channels = listOf(
            Channel("CCTV10", "", listOf("a")),
            Channel("CCTV5+", "", listOf("b")),
            Channel("湖南卫视", "", listOf("c")),
            Channel("CCTV5", "", listOf("d")),
            Channel("本地频道", "", listOf("e")),
            Channel("CCTV2", "", listOf("f"))
        )
        val groups = groupDomesticChannels(channels)
        assertEquals(channels, groups[DomesticChannelGroup.ALL])
        assertEquals(listOf(channels[5], channels[3], channels[1], channels[0]), groups[DomesticChannelGroup.CCTV])
        assertEquals(listOf(channels[2]), groups[DomesticChannelGroup.SATELLITE])
        assertEquals(listOf(channels[4]), groups[DomesticChannelGroup.OTHER])
    }
}
