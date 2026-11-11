package cn.hdfk7.android.iptv.feature.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvOpeningKeyTests {
    @Test
    fun consumesOnlyTheOpeningPressAndItsRepeats(): Unit {
        val key = TvOpeningKey()
        key.record(21, 100L)
        assertTrue(key.consume(21, 100L, released = false))
        assertTrue(key.consume(21, 100L, released = true))
        assertFalse(key.consume(21, 200L, released = false))
    }

    @Test
    fun acceptsNextPressWhenFocusChangeLosesTheOpeningRelease(): Unit {
        val key = TvOpeningKey()
        key.record(21, 100L)
        assertFalse(key.consume(21, 200L, released = false))
        assertFalse(key.consume(21, 200L, released = true))
    }

    @Test
    fun doesNotConsumeOtherKeys(): Unit {
        val key = TvOpeningKey()
        key.record(21, 100L)
        assertFalse(key.consume(22, 100L, released = false))
    }

    @Test
    fun newPanelOpeningReplacesThePreviousPress(): Unit {
        val key = TvOpeningKey()
        key.record(21, 100L)
        key.record(82, 200L)
        assertFalse(key.consume(21, 100L, released = true))
        assertTrue(key.consume(82, 200L, released = false))
        assertTrue(key.consume(82, 200L, released = true))
        assertFalse(key.consume(82, 200L, released = false))
    }
}
