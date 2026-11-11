package cn.hdfk7.android.iptv.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoreMenuNavigationTests {
    private fun rows(
        television: Boolean = true,
        channelCount: Int = 2,
        hasSelection: Boolean = true,
        lineCount: Int = 2,
        fixedVolume: Boolean = false
    ): List<List<MoreAction>> {
        return moreMenuRows(television, channelCount, hasSelection, lineCount, fixedVolume)
    }

    @Test
    fun noSelectionAndFixedVolumeLeaveOnlySourceRefreshAndSettings(): Unit {
        val menu = rows(channelCount = 0, hasSelection = false, fixedVolume = true)
        assertEquals(listOf(MoreAction.SOURCE, MoreAction.REFRESH, MoreAction.SETTINGS), menu.flatten())
        assertEquals(MoreAction.SETTINGS, moveMoreMenuFocus(menu, MoreAction.REFRESH, MoreMenuDirection.DOWN))
    }

    @Test
    fun channelButtonsAreAvailableOnlyOnPhoneWithMultipleChannels(): Unit {
        assertFalse(MoreAction.NEXT_CHANNEL in rows().flatten())
        assertFalse(MoreAction.NEXT_CHANNEL in rows(television = false, channelCount = 1).flatten())
        val phone = rows(television = false)
        assertTrue(listOf(MoreAction.PREVIOUS_CHANNEL, MoreAction.NEXT_CHANNEL) in phone)
        assertEquals(MoreAction.NEXT_CHANNEL, moveMoreMenuFocus(phone, MoreAction.PREVIOUS_CHANNEL, MoreMenuDirection.RIGHT))
    }

    @Test
    fun oneLineSkipsLineSwitchButtons(): Unit {
        val menu = rows(lineCount = 1)
        assertFalse(MoreAction.PREVIOUS_LINE in menu.flatten())
        assertEquals(MoreAction.VOLUME_DOWN, moveMoreMenuFocus(menu, MoreAction.RETRY, MoreMenuDirection.DOWN))
    }

    @Test
    fun verticalMovementPreservesColumnAndHandlesSingleButtonRows(): Unit {
        val menu = rows()
        assertEquals(MoreAction.VOLUME_UP, moveMoreMenuFocus(menu, MoreAction.NEXT_LINE, MoreMenuDirection.DOWN))
        assertEquals(MoreAction.NEXT_LINE, moveMoreMenuFocus(menu, MoreAction.VOLUME_UP, MoreMenuDirection.UP))
        assertEquals(MoreAction.SETTINGS, moveMoreMenuFocus(menu, MoreAction.VOLUME_UP, MoreMenuDirection.DOWN))
        assertEquals(MoreAction.RETRY, moveMoreMenuFocus(menu, MoreAction.NEXT_LINE, MoreMenuDirection.UP))
    }

    @Test
    fun boundariesKeepFocusAndRemovedActionReturnsToSource(): Unit {
        val menu = rows()
        assertEquals(MoreAction.SOURCE, moveMoreMenuFocus(menu, MoreAction.SOURCE, MoreMenuDirection.UP))
        assertEquals(MoreAction.SOURCE, moveMoreMenuFocus(menu, MoreAction.SOURCE, MoreMenuDirection.LEFT))
        assertEquals(MoreAction.SETTINGS, moveMoreMenuFocus(menu, MoreAction.SETTINGS, MoreMenuDirection.DOWN))
        assertEquals(MoreAction.VOLUME_UP, moveMoreMenuFocus(menu, MoreAction.VOLUME_UP, MoreMenuDirection.RIGHT))
        val fixed = rows(fixedVolume = true)
        assertEquals(MoreAction.SOURCE, moveMoreMenuFocus(fixed, MoreAction.VOLUME_UP, MoreMenuDirection.DOWN))
    }
}
