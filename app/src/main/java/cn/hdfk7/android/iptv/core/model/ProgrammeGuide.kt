package cn.hdfk7.android.iptv.core.model

data class GuideRef(val address: String, val channelId: String, val channelName: String = "")

data class Programme(val title: String, val startsAt: Long, val endsAt: Long)

data class ProgrammePreview(val current: String?, val next: String?)

internal fun programmePreview(programmes: List<Programme>, now: Long): ProgrammePreview {
    val current = programmes.lastOrNull(predicate = { item -> item.startsAt <= now && now < item.endsAt })
    val next = programmes.firstOrNull(predicate = { item -> item.startsAt >= (current?.endsAt ?: now) && item != current })
    return ProgrammePreview(current?.title, next?.title)
}
