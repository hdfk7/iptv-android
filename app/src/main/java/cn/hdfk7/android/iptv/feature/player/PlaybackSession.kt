package cn.hdfk7.android.iptv.feature.player

import kotlinx.coroutines.delay

/** Owns attempt identity and fallback progress across playback changes. */
internal class PlaybackSession {
    @Volatile var attempt: PlaybackAttempt? = null
        private set
    var generation = 0
        private set
    private var attemptNumber = 0L
    private val attemptedLines = mutableSetOf<String>()
    private val failures = mutableListOf<PlaybackFailure>()

    val attemptedCount: Int get() = attemptedLines.size
    val failureReasons: List<PlaybackFailure> get() = failures

    fun resetLines(): Unit {
        attemptedLines.clear()
        failures.clear()
    }

    fun retainLines(addresses: List<String>): Unit {
        attemptedLines.retainAll(addresses.toSet())
    }

    fun recordFailure(address: String, reason: PlaybackFailure): Unit {
        attemptedLines.add(address)
        failures.add(reason)
    }

    fun nextLine(addresses: List<String>?): Int =
        addresses?.indexOfFirst(predicate = { address -> address !in attemptedLines }) ?: -1

    suspend fun watchHealth(
        active: PlaybackAttempt,
        limit: Long,
        isCurrent: () -> Boolean,
        sample: (PlaybackHealth) -> PlaybackHealth.Decision
    ): Unit {
        val health = PlaybackHealth(active, limit)
        while (true) {
            delay(minOf(1000L, limit.coerceAtLeast(1L)))
            if (!isCurrent() || sample(health) == PlaybackHealth.Decision.Fallback) {
                return
            }
        }
    }

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
