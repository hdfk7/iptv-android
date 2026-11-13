package cn.hdfk7.android.iptv.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ProgrammeGuideIndexTests {
    @Test
    fun cachesScheduleUntilMidnightAndUpdatesPreviewsAtBoundaries(): Unit {
        val midnight = programmeDay(System.currentTimeMillis()).second
        val ref = GuideRef("guide", "channel")
        val channel = Channel("Test", "", listOf("stream"), guideRefs = listOf(ref))
        val first = Programme("First", midnight - 120_000, midnight - 60_000)
        val overnight = Programme("Overnight", midnight - 60_000, midnight + 60_000)
        val last = Programme("Last", midnight + 60_000, midnight + 120_000)
        val index = ProgrammeGuideIndex(listOf(channel), mapOf(ref to listOf(first, overnight, last)))
        val initial = index.snapshot(midnight - 100_000)
        assertSame(initial, index.snapshot(midnight - 99_000))
        assertEquals(ProgrammePreview("First", "Overnight"), initial.previews[channel.id])
        val changed = index.snapshot(midnight - 60_000)
        assertSame(initial.schedules[channel.id], changed.schedules[channel.id])
        assertEquals(ProgrammePreview("Overnight", "Last"), changed.previews[channel.id])
        val tomorrow = index.snapshot(midnight)
        assertEquals(listOf(overnight.copy(startsAt = midnight), last), tomorrow.schedules[channel.id])
        assertEquals(ProgrammePreview("First", "Overnight"), index.snapshot(midnight - 100_000).previews[channel.id])
    }

    @Test
    fun indexedPreviewMatchesScanForOverlapGapsTiesAndReverseTime(): Unit {
        val programmes = listOf(
            Programme("Long", 5, 35),
            Programme("Short", 10, 15),
            Programme("Same start", 10, 16),
            Programme("Evening", 30, 45),
            Programme("Next day", 55, 65)
        )
        for (input in listOf(programmes, programmes.reversed(), emptyList())) {
            val index = ProgrammePreviewIndex(input)
            for (time in (70L downTo 0L) + (0L..70L)) {
                assertEquals(programmePreview(input, time), index.preview(time), "time=$time, input=$input")
            }
        }
    }
}
