package cn.hdfk7.android.iptv.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentChannelsTests {
    @Test
    fun movesRepeatedChannelToFrontAndCapsHistory(): Unit {
        val ids = (1..20).map(transform = Int::toString)
        assertEquals(listOf("4") + ids.filterNot(predicate = { id -> id == "4" }), RecentChannels.record(ids, "4"))
        assertEquals(listOf("21") + ids.take(19), RecentChannels.record(ids, "21"))
    }

    @Test
    fun keepsHistoryOrderAndOmitsUnavailableChannels(): Unit {
        val first = Channel("First", "", listOf("https://example.org/a"), sourceId = "one")
        val second = first.copy(name = "Second")
        val otherSource = first.copy(sourceId = "two")
        assertEquals(listOf(second, first), RecentChannels.visible(
            listOf(second.id, "one/deleted", otherSource.id, first.id),
            listOf(first, second)
        ))
    }
}
