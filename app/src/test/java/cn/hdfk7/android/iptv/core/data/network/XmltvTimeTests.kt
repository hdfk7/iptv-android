package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.model.ProgrammePreview
import cn.hdfk7.android.iptv.core.model.programmePreview
import cn.hdfk7.android.iptv.core.model.programmeDay
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class XmltvTimeTests {
    @Test
    fun programmeDayUsesLocalMidnightAcrossDaylightSaving(): Unit {
        val now = Instant.parse("2026-03-08T17:00:00Z").toEpochMilli()
        val (start, end) = programmeDay(now, ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(), end)
    }
    @Test
    fun appliesExplicitOffsetAndRejectsInvalidDate(): Unit {
        assertEquals(Instant.parse("2026-11-11T10:00:00Z").toEpochMilli(), xmltvTime("20261111180000 +0800"))
        assertNull(xmltvTime("20260230180000 +0800"))
    }

    @Test
    fun selectsNextProgrammeAtTheBoundaryAndDuringGaps(): Unit {
        val items = listOf(Programme("First", 10, 20), Programme("Second", 20, 30), Programme("Third", 40, 50))
        assertEquals(ProgrammePreview("Second", "Third"), programmePreview(items, 20))
        assertEquals(ProgrammePreview(null, "Third"), programmePreview(items, 35))
    }
}
