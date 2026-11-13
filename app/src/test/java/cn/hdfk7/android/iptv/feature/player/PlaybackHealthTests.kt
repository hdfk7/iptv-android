package cn.hdfk7.android.iptv.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackHealthTests {
    @Test
    fun firstFrameTimeoutAppliesToPlaybackAndStandby(): Unit {
        val playing = PlaybackHealth(PlaybackAttempt("one", "line", 0), limit = 3_000)
        val standby = PlaybackHealth(PlaybackAttempt("two", "line", 0), limit = 3_000)
        assertEquals(PlaybackHealth.Decision.Continue, playing.sample(2_999, true, false, false, true, false, true, 0))
        assertEquals(PlaybackHealth.Decision.Fallback, playing.sample(3_000, true, false, false, true, false, true, 0))
        assertEquals(PlaybackHealth.Decision.Fallback, standby.sample(3_000, false, true, false, true, false, true, 0))
    }

    @Test
    fun pauseClearsStallBudgetBeforeResume(): Unit {
        val attempt = PlaybackAttempt("one", "line", 0).also(block = { it.firstFrameAt = 300 })
        val health = PlaybackHealth(attempt, limit = 3_000)
        health.sample(1_000, true, false, false, true, false, true, 30)
        health.sample(3_000, true, false, false, false, true, true, 30)
        health.sample(10_000, false, false, false, false, true, true, 30)
        assertEquals(PlaybackHealth.Decision.RecordQuality, health.sample(12_000, true, false, false, false, true, true, 30))
        assertEquals(PlaybackHealth.Decision.Fallback, health.sample(13_000, true, false, false, false, true, true, 30))
        assertEquals(300L, health.startupMs)
        assertEquals(5_000L, health.bufferingMs)
    }

    @Test
    fun readyAudioOnlyRecordsQualityWithoutVideoFrames(): Unit {
        val health = PlaybackHealth(PlaybackAttempt("one", "audio", 0), limit = 3_000)
        for (second in 1L..4L) {
            assertEquals(PlaybackHealth.Decision.Continue, health.sample(second * 1_000, true, false, true, true, false, false, null))
        }
        assertEquals(PlaybackHealth.Decision.RecordQuality, health.sample(5_000, true, false, true, true, false, false, null))
        assertEquals(5_000L, health.playedMs)
        assertEquals(0L, health.bufferingMs)
    }
}
