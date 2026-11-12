package cn.hdfk7.android.iptv.feature.player

internal class PlaybackAttempt(val id: String, val address: String, val startedAt: Long) {
    var tracksAt: Long? = null
    var readyAt: Long? = null
    var firstFrameAt: Long? = null
    private var startupWaitingMs = 0L

    fun canPresent(audioOnly: Boolean): Boolean = firstFrameAt != null || audioOnly

    fun status(ready: Boolean, isPlaying: Boolean, wantsPlayback: Boolean, audioOnly: Boolean): PlaybackStatus {
        return when {
            !wantsPlayback -> PlaybackStatus.PAUSED
            !ready || !canPresent(audioOnly) -> PlaybackStatus.LOADING
            isPlaying -> PlaybackStatus.PLAYING
            else -> PlaybackStatus.PAUSED
        }
    }

    fun startupTimedOut(elapsedMs: Long, waiting: Boolean, audioOnly: Boolean, limit: Long): Boolean {
        if (!waiting || canPresent(audioOnly)) {
            startupWaitingMs = 0L
            return false
        }
        startupWaitingMs += elapsedMs
        return startupWaitingMs >= limit
    }
}
