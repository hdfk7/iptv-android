package cn.hdfk7.android.iptv.feature.player

internal class PlaybackHealth(private val attempt: PlaybackAttempt, private val limit: Long) {
    enum class Decision {
        Continue, Fallback, RecordQuality
    }

    private val observation = LineObservationWindow()
    private var lastSample = attempt.startedAt
    private var lastRenderedFrames: Int? = null
    private var stalledMs = 0L
    private var stableMs = 0L
    var startupMs = 0L
        private set
    var playedMs = 0L
        private set
    var bufferingMs = 0L
        private set

    fun sample(
        now: Long,
        wantsPlayback: Boolean,
        previewing: Boolean,
        audioOnly: Boolean,
        playing: Boolean,
        buffering: Boolean,
        videoExpected: Boolean,
        renderedFrames: Int?
    ): Decision {
        val elapsed = now - lastSample
        lastSample = now
        if (attempt.startupTimedOut(elapsed, wantsPlayback || previewing, audioOnly, limit)) {
            return Decision.Fallback
        }
        val stalled = buffering ||
            (playing && videoExpected && (renderedFrames == null || renderedFrames == lastRenderedFrames))
        lastRenderedFrames = renderedFrames

        if (!wantsPlayback) {
            stalledMs = 0
            stableMs = 0
            return Decision.Continue
        }
        if (playing && !stalled && attempt.canPresent(audioOnly)) {
            if (startupMs == 0L) {
                startupMs = (attempt.firstFrameAt ?: now) - attempt.startedAt
            }
            playedMs += elapsed
        } else if (stalled && startupMs > 0L) {
            bufferingMs += elapsed
        }

        if (stalled) {
            stalledMs += elapsed
            stableMs = 0
            if (stalledMs >= limit) {
                return Decision.Fallback
            }
        } else if (playing) {
            stableMs += elapsed
            if (stableMs >= limit) {
                stalledMs = 0
            }
        }
        return if (observation.advance(elapsed, hasStarted = startupMs > 0L, paused = !playing && !stalled)) {
            Decision.RecordQuality
        } else {
            Decision.Continue
        }
    }
}
