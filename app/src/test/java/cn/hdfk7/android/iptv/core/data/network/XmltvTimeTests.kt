package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.model.ProgrammePreview
import cn.hdfk7.android.iptv.core.model.programmeCurrentIndex
import cn.hdfk7.android.iptv.core.model.programmePreview
import cn.hdfk7.android.iptv.core.model.programmeDay
import cn.hdfk7.android.iptv.core.model.programmeScheduleForDay
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class XmltvTimeTests {
    @Test
    fun scheduleKeepsOnlyTheSelectedLocalDayAcrossMidnight(): Unit {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = Instant.parse("2026-11-11T16:00:00Z").toEpochMilli()
        val previous = Programme("Previous", midnight - 120_000, midnight - 60_000)
        val overnight = Programme("Overnight", midnight - 60_000, midnight + 60_000)
        val next = Programme("Next", midnight + 60_000, midnight + 120_000)
        val programmes = listOf(previous, overnight, next)
        val (beforeStart, beforeEnd) = programmeDay(midnight - 1, zone)
        assertEquals(listOf(previous, overnight.copy(endsAt = midnight)),
            programmeScheduleForDay(programmes, beforeStart, beforeEnd))
        val (afterStart, afterEnd) = programmeDay(midnight, zone)
        assertEquals(listOf(overnight.copy(startsAt = midnight), next),
            programmeScheduleForDay(programmes, afterStart, afterEnd))
        assertEquals("Overnight", programmePreview(programmes, midnight).current)
    }

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

    @Test
    fun previewAndScheduleSelectTheSameProgrammeWhenTimesOverlap(): Unit {
        val items = listOf(Programme("Earlier", 10, 30), Programme("Later", 20, 40), Programme("Next", 40, 50))
        val schedule = programmeScheduleForDay(items, 15, 50)
        assertEquals(0, programmeCurrentIndex(schedule, 19))
        assertEquals(1, programmeCurrentIndex(schedule, 20))
        assertEquals(programmePreview(items, 20).current, schedule[programmeCurrentIndex(schedule, 20)].title)
        assertEquals(2, programmeCurrentIndex(schedule, 40))
        assertEquals(-1, programmeCurrentIndex(schedule, 50))
    }
}
