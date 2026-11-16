package cn.hdfk7.android.iptv.feature.player

/** Owns the attempt identity across player, channel and proxy changes. */
internal class PlaybackSession {
    @Volatile var attempt: PlaybackAttempt? = null
        private set
    var generation = 0
        private set
    private var attemptNumber = 0L

    fun invalidate(): Unit {
        generation += 1
        clearAttempt()
    }

    fun clearAttempt(): Unit {
        attempt = null
    }

    fun startAttempt(address: String, now: Long): PlaybackAttempt {
        val active = PlaybackAttempt((++attemptNumber).toString(), address, now)
        attempt = active
        return active
    }

    fun owns(active: PlaybackAttempt, mediaId: String?): Boolean {
        return attempt === active && mediaId == active.id
    }

    fun isCurrent(expectedGeneration: Int): Boolean = generation == expectedGeneration
}
