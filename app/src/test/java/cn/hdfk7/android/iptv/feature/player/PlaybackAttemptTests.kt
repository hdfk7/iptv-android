package cn.hdfk7.android.iptv.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackAttemptTests {
    @Test
    fun callbacksFromPreviousChannelCannotClaimTheNewAttempt(): Unit {
        val session = PlaybackSession()
        val old = session.startAttempt("same-url", 0L)
        session.invalidate()
        val current = session.startAttempt("same-url", 1L)
        assertFalse(session.owns(old, old.id))
        assertFalse(session.owns(current, old.id))
        assertTrue(session.owns(current, current.id))
    }

    @Test
    fun restartingPlayerForProxyChangeInvalidatesQueuedFallback(): Unit {
        val session = PlaybackSession()
        val attempt = session.startAttempt("first", 0L)
        val queuedGeneration = session.generation
        session.clearAttempt()
        assertFalse(session.owns(attempt, attempt.id))
        assertTrue(session.isCurrent(queuedGeneration))
        session.invalidate()
        session.startAttempt("first", 1L)
        assertFalse(session.isCurrent(queuedGeneration))
    }

    @Test
    fun readyVideoWaitsForFirstFrameButAudioOnlyCanStart(): Unit {
        val attempt = PlaybackAttempt("1", "test", 100L)
        assertEquals(PlaybackStatus.LOADING, attempt.status(true, true, true, false))
        assertEquals(PlaybackStatus.PLAYING, attempt.status(true, true, true, true))
        attempt.firstFrameAt = 200L
        assertEquals(PlaybackStatus.PLAYING, attempt.status(true, true, true, false))
        assertEquals(PlaybackStatus.LOADING, attempt.status(false, false, true, false))
        assertEquals(PlaybackStatus.PAUSED, attempt.status(false, false, false, false))
    }

    @Test
    fun noFirstFrameTimesOutEvenWhenReadyAndUserPauseResetsTheBudget(): Unit {
        val attempt = PlaybackAttempt("1", "test", 0L)
        assertFalse(attempt.startupTimedOut(7999L, waiting = true, audioOnly = false, limit = 8000L))
        assertFalse(attempt.startupTimedOut(60000L, waiting = false, audioOnly = false, limit = 8000L))
        assertFalse(attempt.startupTimedOut(7999L, waiting = true, audioOnly = false, limit = 8000L))
        assertTrue(attempt.startupTimedOut(1L, waiting = true, audioOnly = false, limit = 8000L))
        assertFalse(attempt.startupTimedOut(8000L, waiting = true, audioOnly = true, limit = 8000L))
    }
}
