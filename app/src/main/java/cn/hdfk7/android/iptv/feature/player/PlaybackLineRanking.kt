package cn.hdfk7.android.iptv.feature.player

import cn.hdfk7.android.iptv.core.model.LineQuality
import cn.hdfk7.android.iptv.core.model.PlaybackLine

internal class PlaybackLineRanking(
    private val readHistory: ((String, String) -> LineQuality?)? = null,
    private val writeHistory: ((String, String, LineQuality) -> Unit)? = null
) {
    private data class SortKey(
        val tier: Int,
        val stallBand: Int,
        val dropBand: Int,
        val startupBand: Int,
        val pixels: Long,
        val frameRate: Float,
        val startupMs: Long
    )

    private data class Candidate(val url: String, val key: SortKey)

    private var previousOrder = emptyList<Candidate>()
    private val qualities = mutableMapOf<String, MutableMap<String, LineQuality>>()
    private val orderByQuality = compareBy<Candidate>({ candidate -> candidate.key.tier })
        .thenBy({ candidate -> candidate.key.stallBand })
        .thenBy({ candidate -> candidate.key.dropBand })
        .thenBy({ candidate -> candidate.key.startupBand })
        .thenByDescending({ candidate -> candidate.key.pixels })
        .thenByDescending({ candidate -> candidate.key.frameRate })
        .thenBy({ candidate -> candidate.key.startupMs })

    fun reset(): Unit {
        qualities.clear()
        previousOrder = emptyList()
    }

    fun record(program: String, url: String, quality: LineQuality): Unit {
        if (writeHistory != null) {
            writeHistory.invoke(program, url, quality)
        } else {
            qualities.getOrPut(key = program, defaultValue = { mutableMapOf() })[url] = quality
        }
    }

    fun order(program: String, urls: List<String>, playingUrl: String?): RankedPlaybackLines {
        return RankedPlaybackLines(lines(program, urls), playingUrl)
    }

    fun failed(program: String, url: String): Unit {
        val previous = (readHistory?.invoke(program, url) ?: qualities[program]?.get(url)) ?: LineQuality()
        record(program, url, previous.copy(failures = previous.failures + 1))
    }

    fun lines(program: String, urls: List<String>): List<PlaybackLine> {
        val known = qualities[program].orEmpty()
        val measurements = urls.distinct().associateWith(valueSelector = { url -> readHistory?.invoke(program, url) ?: known[url] })
        val candidates = measurements.map(transform = { (url, quality) -> Candidate(url, sortKey(quality)) })
        val ordered = if (candidates == previousOrder) candidates else candidates.sortedWith(orderByQuality)
        previousOrder = ordered
        return ordered.mapIndexed(transform = { index, candidate -> PlaybackLine(candidate.url, index, measurements[candidate.url]) })
    }

    private fun sortKey(quality: LineQuality?): SortKey {
        val stalls = stallRatio(quality)
        val drops = dropRatio(quality)
        val tier = when {
            quality == null -> 1
            quality.failures > 0 -> 3
            quality.playedMs < 3000L -> 1
            stalls > 0.1 || drops > 0.1 -> 2
            else -> 0
        }
        return SortKey(
            tier, (stalls / 0.05).toInt(), (drops / 0.05).toInt(), slowStartupTier(quality),
            (quality?.width ?: 0).toLong() * (quality?.height ?: 0), quality?.frameRate ?: 0f,
            quality?.startupMs ?: Long.MAX_VALUE
        )
    }

    private fun slowStartupTier(quality: LineQuality?): Int {
        val startup = quality?.startupMs ?: Long.MAX_VALUE
        return when (startup) {
            in 2_001L..4_000L -> 1
            in 4_001L until Long.MAX_VALUE -> 2
            else -> 0
        }
    }

    private fun stallRatio(quality: LineQuality?): Double {
        return if (quality == null) 0.0 else quality.stalledMs.toDouble() / (quality.playedMs + quality.stalledMs).coerceAtLeast(1L)
    }

    private fun dropRatio(quality: LineQuality?): Double {
        return if (quality == null) 0.0 else quality.droppedFrames.toDouble() / (quality.renderedFrames.toLong() + quality.droppedFrames).coerceAtLeast(1L)
    }
}

internal class LineObservationWindow {
    private var observedMs = 0L
    private var nextRankingMs = 5000L

    fun advance(elapsedMs: Long, hasStarted: Boolean, paused: Boolean): Boolean {
        if (!hasStarted || paused) {
            return false
        }
        observedMs += elapsedMs.coerceAtLeast(0L)
        if (observedMs < nextRankingMs) {
            return false
        }
        nextRankingMs = observedMs + 10_000L
        return true
    }
}

internal data class RankedPlaybackLines(val lines: List<PlaybackLine>, val playingUrl: String?) {
    val playingIndex: Int
        get() = lines.indexOfFirst(predicate = { line -> line.url == playingUrl })
}
