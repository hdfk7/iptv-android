package cn.hdfk7.android.iptv.feature.player

import cn.hdfk7.android.iptv.core.model.LineQuality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackLineRankingTests {
    private val urls = listOf("first", "second", "third")

    @Test
    fun observesFiveSecondsAfterFirstFrameThenUpdatesEveryTenSeconds(): Unit {
        val window = LineObservationWindow()
        assertFalse(window.advance(60_000, hasStarted = false, paused = false))
        assertFalse(window.advance(4999, hasStarted = true, paused = false))
        assertTrue(window.advance(1, hasStarted = true, paused = false))
        assertFalse(window.advance(9999, hasStarted = true, paused = false))
        assertTrue(window.advance(1, hasStarted = true, paused = false))
    }

    @Test
    fun pausesAreExcludedWithoutDiscardingPreviousObservation(): Unit {
        val window = LineObservationWindow()
        assertFalse(window.advance(2000, hasStarted = true, paused = false))
        assertFalse(window.advance(60_000, hasStarted = true, paused = true))
        assertTrue(window.advance(3000, hasStarted = true, paused = false))
    }

    @Test
    fun bufferingCountsTowardObservationAndDemotesTheLine(): Unit {
        val window = LineObservationWindow()
        val ranking = PlaybackLineRanking()
        assertFalse(window.advance(2000, hasStarted = true, paused = false))
        // Buffering remains active observation after first frame; it is not a user pause.
        assertTrue(window.advance(3000, hasStarted = true, paused = false))
        ranking.record("program", "first", LineQuality(playedMs = 2000, stalledMs = 3000))
        assertEquals("second", ranking.lines("program", urls).first().url)
    }

    @Test
    fun switchingProgramsUsesCachedOrderAndStartsANewObservation(): Unit {
        val ranking = PlaybackLineRanking()
        ranking.record("first-program", "second", LineQuality(width = 1920, height = 1080, playedMs = 10_000))
        assertEquals("second", ranking.lines("first-program", urls).first().url)
        val firstWindow = LineObservationWindow()
        assertTrue(firstWindow.advance(5000, hasStarted = true, paused = false))
        val secondWindow = LineObservationWindow()
        assertFalse(secondWindow.advance(4999, hasStarted = true, paused = false))
        assertEquals(urls, ranking.lines("second-program", urls).map(transform = { line -> line.url }))
        assertEquals("second", ranking.lines("first-program", urls).first().url)
    }

    @Test
    fun changingSortRetainsPlayingUrlAndUpdatesItsIndex(): Unit {
        val ranking = PlaybackLineRanking()
        val before = ranking.order("program", urls, "second")
        ranking.record("program", "second", LineQuality(width = 1920, height = 1080, playedMs = 10_000))
        val after = ranking.order("program", urls, before.playingUrl)
        assertEquals(1, before.playingIndex)
        assertEquals(0, after.playingIndex)
        assertEquals(before.playingUrl, after.lines[after.playingIndex].url)
        assertEquals(before.lines.map(transform = { line -> line.url }).toSet(), after.lines.map(transform = { line -> line.url }).toSet())
    }

    @Test
    fun unplayedLinesRetainSourceOrderAndReceiveContiguousSort(): Unit {
        val lines = PlaybackLineRanking().lines("program", urls)
        assertEquals(urls, lines.map(transform = { line -> line.url }))
        assertEquals(listOf(0, 1, 2), lines.map(transform = { line -> line.sort }))
    }

    @Test
    fun smoothHighResolutionOutranksSmoothLowResolution(): Unit {
        val ranking = PlaybackLineRanking()
        ranking.record("program", "first", LineQuality(width = 1280, height = 720, playedMs = 10_000))
        ranking.record("program", "second", LineQuality(width = 1920, height = 1080, playedMs = 10_000))
        assertEquals(listOf("second", "first", "third"), ranking.lines("program", urls).map(transform = { line -> line.url }))
    }

    @Test
    fun bufferingAndDecoderDropsDemoteHigherResolution(): Unit {
        for (poor in listOf(
            LineQuality(width = 3840, height = 2160, playedMs = 10_000, stalledMs = 5000),
            LineQuality(width = 3840, height = 2160, playedMs = 10_000, renderedFrames = 100, droppedFrames = 100)
        )) {
            val ranking = PlaybackLineRanking()
            ranking.record("program", "first", poor)
            ranking.record("program", "second", LineQuality(width = 1280, height = 720, playedMs = 10_000))
            assertEquals(listOf("second", "third", "first"), ranking.lines("program", urls).map(transform = { line -> line.url }))
        }
    }

    @Test
    fun ranksPersistedMeasurementsAfterRecreationAndIgnoresRemovedUrls(): Unit {
        val data = mutableMapOf<Pair<String, String>, LineQuality>()
        fun ranking(): PlaybackLineRanking {
            return PlaybackLineRanking(
                readHistory = { program, url -> data[program to url] },
                writeHistory = { program, url, quality -> data[program to url] = quality }
            )
        }
        val initial = ranking()
        initial.failed("program", "first")
        initial.record("program", "second", LineQuality(playedMs = 6000))
        assertEquals(listOf("second", "third", "first"), ranking().lines("program", urls).map(transform = { line -> line.url }))
        assertEquals(listOf("third", "first"), ranking().lines("program", listOf("first", "third")).map(transform = { line -> line.url }))
    }

    @Test
    fun measurementsAreIsolatedByProgramAndResetWithSourceRefresh(): Unit {
        val ranking = PlaybackLineRanking()
        ranking.failed("program", "first")
        assertEquals("second", ranking.lines("program", urls).first().url)
        assertEquals("first", ranking.lines("other", urls).first().url)
        ranking.reset()
        assertEquals(urls, ranking.lines("program", urls).map(transform = { line -> line.url }))
    }

    @Test
    fun removedAndDuplicateUrlsDoNotLeaveGapsInSort(): Unit {
        val ranking = PlaybackLineRanking()
        ranking.record("program", "first", LineQuality(playedMs = 10_000))
        val lines = ranking.lines("program", listOf("second", "third", "second"))
        assertEquals(listOf("second", "third"), lines.map(transform = { line -> line.url }))
        assertEquals(listOf(0, 1), lines.map(transform = { line -> line.sort }))
    }
}
