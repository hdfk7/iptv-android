package cn.hdfk7.android.iptv.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdvancedSettingsTests {
    @Test
    fun parsesSecondsWithoutLosingMillisecondPrecision(): Unit {
        assertEquals(5000, parseTimeoutSeconds("5"))
        assertEquals(15001, parseTimeoutSeconds("15.001"))
    }

    @Test
    fun rejectsInvalidOrOutOfRangeTimeouts(): Unit {
        assertNull(parseTimeoutSeconds("0"))
        assertNull(parseTimeoutSeconds("-1"))
        assertNull(parseTimeoutSeconds("0.0001"))
        assertNull(parseTimeoutSeconds("2147483.648"))
        assertNull(parseTimeoutSeconds("invalid"))
    }
}
