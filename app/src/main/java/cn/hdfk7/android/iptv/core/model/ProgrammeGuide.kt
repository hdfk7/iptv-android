package cn.hdfk7.android.iptv.core.model

import java.time.Instant
import java.time.ZoneId

data class GuideRef(val address: String, val channelId: String, val channelName: String = "")

data class Programme(val title: String, val startsAt: Long, val endsAt: Long)

data class ProgrammePreview(val current: String?, val next: String?)

internal fun programmeDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return date.atStartOfDay(zone).toInstant().toEpochMilli() to
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

internal fun programmeScheduleForDay(programmes: List<Programme>, start: Long, end: Long): List<Programme> {
    return programmes.mapNotNull(transform = { item ->
        val startsAt = maxOf(start, item.startsAt)
        val endsAt = minOf(end, item.endsAt)
        if (startsAt < endsAt) {
            item.copy(startsAt = startsAt, endsAt = endsAt)
        } else {
            null
        }
    }).distinct()
}

internal fun programmeCurrentIndex(programmes: List<Programme>, now: Long): Int {
    return programmes.indexOfLast(predicate = { item -> item.startsAt <= now && now < item.endsAt })
}

internal fun programmePreview(programmes: List<Programme>, now: Long): ProgrammePreview {
    val current = programmes.getOrNull(programmeCurrentIndex(programmes, now))
    val next = programmes.firstOrNull(predicate = { item -> item.startsAt >= (current?.endsAt ?: now) && item != current })
    return ProgrammePreview(current?.title, next?.title)
}

internal class ProgrammePreviewIndex(private val programmes: List<Programme>) {
    private val ordered = programmes.indices.all(predicate = { index ->
        val programme = programmes[index]
        programme.startsAt < programme.endsAt &&
            (index == 0 || programmes[index - 1].startsAt <= programme.startsAt)
    })
    private val latestEnds = if (ordered) {
        var latest = Long.MIN_VALUE
        LongArray(programmes.size, init = { index ->
            latest = maxOf(latest, programmes[index].endsAt)
            latest
        })
    } else {
        LongArray(0)
    }

    fun preview(now: Long): ProgrammePreview {
        if (!ordered) {
            return programmePreview(programmes, now)
        }
        var index = firstStart(now, includeEqual = false) - 1
        // When the prefix maximum has expired, no earlier programme can be current.
        while (index >= 0 && latestEnds[index] > now) {
            val item = programmes[index]
            if (item.endsAt > now) {
                val next = programmes.getOrNull(firstStart(item.endsAt, includeEqual = true))
                return ProgrammePreview(item.title, next?.title)
            }
            index -= 1
        }
        return ProgrammePreview(null, programmes.getOrNull(firstStart(now, includeEqual = true))?.title)
    }

    private fun firstStart(time: Long, includeEqual: Boolean): Int {
        var left = 0
        var right = programmes.size
        while (left < right) {
            val middle = left + (right - left) / 2
            val start = programmes[middle].startsAt
            if (start < time || (!includeEqual && start == time)) {
                left = middle + 1
            } else {
                right = middle
            }
        }
        return left
    }
}
