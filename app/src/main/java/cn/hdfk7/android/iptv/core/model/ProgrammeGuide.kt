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
