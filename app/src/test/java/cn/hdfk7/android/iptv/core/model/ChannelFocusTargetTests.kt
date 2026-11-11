package cn.hdfk7.android.iptv.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChannelFocusTargetTests {
    private fun channel(name: String, source: String = "a"): Channel {
        return Channel(name, "", listOf("https://example.org/$name"), source)
    }

    @Test
    fun focusesPlayingChannelAfterGroupOrderChanges(): Unit {
        val first = channel("First")
        val playing = channel("Playing")
        assertEquals(playing, channelFocusTarget(listOf(first, playing), playing.id))
        assertEquals(playing, channelFocusTarget(listOf(playing, first), playing.id))
    }

    @Test
    fun focusesFirstWhenPlayingChannelIsOutsideTheGroup(): Unit {
        val first = channel("First")
        val last = channel("Last")
        assertEquals(first, channelFocusTarget(listOf(first, last), channel("Elsewhere").id))
        assertEquals(first, channelFocusTarget(listOf(first, last), null))
    }

    @Test
    fun sameChannelNameFromAnotherSourceDoesNotMatch(): Unit {
        val first = channel("First")
        val otherSource = channel("Playing", source = "b")
        assertEquals(first, channelFocusTarget(listOf(first, otherSource), channel("Playing").id))
    }

    @Test
    fun emptyGroupHasNoFocusTarget(): Unit {
        assertNull(channelFocusTarget(emptyList(), channel("Playing").id))
    }
}
