package cn.hdfk7.android.iptv.feature.player

import cn.hdfk7.android.iptv.core.model.LineQuality
import cn.hdfk7.android.iptv.core.model.PlaybackLine

internal class PlaybackLineRanking(
    private val readHistory: ((String, String) -> LineQuality?)? = null,
    private val writeHistory: ((String, String, LineQuality) -> Unit)? = null
) {
    private val qualities = mutableMapOf<String, MutableMap<String, LineQuality>>()

    fun reset(): Unit {
        qualities.clear()
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
        return urls.distinct().mapIndexed(transform = { index, url -> PlaybackLine(url, index, readHistory?.invoke(program, url) ?: known[url]) })
            .sortedWith(compareBy<PlaybackLine>({ line -> tier(line.quality) })
                .thenBy({ line -> (stallRatio(line.quality) / 0.05).toInt() })
                .thenBy({ line -> (dropRatio(line.quality) / 0.05).toInt() })
                .thenByDescending({ line -> (line.quality?.width ?: 0).toLong() * (line.quality?.height ?: 0) })
                .thenByDescending({ line -> line.quality?.frameRate ?: 0f })
                .thenBy({ line -> line.quality?.startupMs ?: Long.MAX_VALUE })
                .thenBy({ line -> line.sort }))
            .mapIndexed(transform = { index, line -> line.copy(sort = index) })
    }

    private fun tier(quality: LineQuality?): Int {
        return when {
            quality == null -> 1
            quality.failures > 0 -> 3
            quality.playedMs < 3000L -> 1
            stallRatio(quality) > 0.1 || dropRatio(quality) > 0.1 -> 2
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
