package cn.hdfk7.android.iptv.core.model

internal data class ProgrammeGuideSnapshot(
    val previews: Map<String, ProgrammePreview>,
    val schedules: Map<String, List<Programme>>,
    val nextChangeAt: Long
)

/** A worker-confined index for the current guide data and channel list. */
internal class ProgrammeGuideIndex(channels: List<Channel>, data: Map<GuideRef, List<Programme>>) {
    private val timelines = data.mapValues(transform = { entry -> GuideTimeline(entry.value) })
    private val channelsByGuide = channels.mapNotNull(transform = { channel ->
        val refs = channel.guideRefs.mapNotNull(transform = timelines::get)
        if (refs.isEmpty()) null else channel.id to refs
    })
    private var day: Pair<Long, Long>? = null
    private var computedAt = Long.MIN_VALUE
    private var snapshot: ProgrammeGuideSnapshot? = null

    fun snapshot(now: Long): ProgrammeGuideSnapshot {
        val today = programmeDay(now)
        val previous = snapshot
        if (previous != null && today == day && now >= computedAt && now < previous.nextChangeAt) {
            return previous
        }
        if (today != day) {
            for (timeline in timelines.values) {
                timeline.schedule = programmeScheduleForDay(timeline.programmes, today.first, today.second)
            }
            day = today
        }
        val previews = mutableMapOf<String, ProgrammePreview>()
        val schedules = mutableMapOf<String, List<Programme>>()
        var nextChangeAt = today.second
        for (timeline in timelines.values) {
            timeline.updatePreview(now)
            nextChangeAt = minOf(nextChangeAt, timeline.nextChangeAt)
        }
        for ((id, candidates) in channelsByGuide) {
            val preferred = candidates.firstOrNull(predicate = { timeline -> timeline.preview.current != null })
                ?: candidates.firstOrNull(predicate = { timeline -> timeline.preview.next != null })
            if (preferred != null) {
                previews[id] = preferred.preview
            }
            val schedule = preferred?.schedule?.takeIf(predicate = List<Programme>::isNotEmpty)
                ?: candidates.firstOrNull(predicate = { timeline -> timeline.schedule.isNotEmpty() })?.schedule
            if (schedule != null) {
                schedules[id] = schedule
            }
        }
        computedAt = now
        return ProgrammeGuideSnapshot(previews, schedules, nextChangeAt).also(block = { result -> snapshot = result })
    }

    private class GuideTimeline(val programmes: List<Programme>) {
        private val boundaries = programmes.flatMap(transform = { item -> listOf(item.startsAt, item.endsAt) })
            .distinct().sorted().toLongArray()
        private val previewIndex = ProgrammePreviewIndex(programmes)
        private var computedAt = Long.MIN_VALUE
        var schedule = emptyList<Programme>()
        var preview = ProgrammePreview(null, null)
            private set
        var nextChangeAt = Long.MIN_VALUE
            private set

        fun updatePreview(now: Long): Unit {
            if (now >= computedAt && now < nextChangeAt) {
                return
            }
            preview = previewIndex.preview(now)
            val match = boundaries.binarySearch(now)
            val next = if (match >= 0) match + 1 else -match - 1
            nextChangeAt = boundaries.getOrNull(next) ?: Long.MAX_VALUE
            computedAt = now
        }
    }
}
