package cn.hdfk7.android.iptv.feature.player

import cn.hdfk7.android.iptv.core.data.local.GuideCache
import cn.hdfk7.android.iptv.core.data.network.parseXmltv
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.GuideRef
import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.model.ProgrammeGuideIndex
import cn.hdfk7.android.iptv.core.model.ProgrammeGuideSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlayerProgrammeGuide(
    private val scope: CoroutineScope,
    private val cache: GuideCache,
    private val download: suspend (String) -> String,
    private val publish: (ProgrammeGuideSnapshot) -> Unit
) {
    private var channels: List<Channel> = emptyList()
    private var refs: Set<GuideRef> = emptySet()
    private var data: Map<GuideRef, List<Programme>> = emptyMap()
    private var downloadJob: Job? = null
    private var displayJob: Job? = null

    fun show(current: List<Channel>, force: Boolean = false): Unit {
        if (!force && current == channels) {
            return
        }
        channels = current
        val requested = current.flatMap(transform = Channel::guideRefs).toSet()
        val reload = force || requested != refs
        refs = requested
        data = data.filterKeys(predicate = refs::contains)
        display()
        if (!reload) {
            return
        }
        downloadJob?.cancel()
        if (refs.isEmpty()) {
            return
        }
        val sources = refs.groupBy(keySelector = GuideRef::address).entries.take(4)
        val missing = refs - data.keys
        downloadJob = scope.launch(block = {
            if (missing.isNotEmpty()) {
                merge(read(sources.map(transform = { (address, entries) -> address to entries.filter(predicate = missing::contains) }),
                    fromCache = true))
            }
            merge(read(sources.map(transform = { (address, entries) -> address to entries }), fromCache = false))
        })
    }

    fun stop(): Unit {
        downloadJob?.cancel()
        displayJob?.cancel()
    }

    private suspend fun read(sources: List<Pair<String, List<GuideRef>>>, fromCache: Boolean): Map<GuideRef, List<Programme>> {
        return withContext(context = Dispatchers.IO, block = {
            val now = System.currentTimeMillis()
            val result = mutableMapOf<GuideRef, List<Programme>>()
            for ((address, entries) in sources) {
                if (entries.isEmpty()) {
                    continue
                }
                try {
                    val feed = if (fromCache) cache.read(address, now) else download(address)
                    if (feed != null) {
                        result.putAll(parseXmltv(feed, entries, now))
                        if (!fromCache) {
                            currentCoroutineContext().ensureActive()
                            cache.write(address, feed)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // An unavailable guide leaves the last successful schedule visible.
                }
            }
            result
        })
    }

    private fun merge(incoming: Map<GuideRef, List<Programme>>): Unit {
        if (incoming.any(predicate = { (ref, programmes) -> data[ref] != programmes })) {
            data = data + incoming
            display()
        }
    }

    private fun display(): Unit {
        displayJob?.cancel()
        if (data.isEmpty()) {
            publish(ProgrammeGuideSnapshot(emptyMap(), emptyMap(), Long.MAX_VALUE))
            return
        }
        val current = channels
        val guides = data
        displayJob = scope.launch(context = Dispatchers.Default, block = {
            val index = ProgrammeGuideIndex(current, guides)
            var previous: ProgrammeGuideSnapshot? = null
            while (true) {
                val now = System.currentTimeMillis()
                val snapshot = index.snapshot(now)
                if (snapshot !== previous) {
                    withContext(context = Dispatchers.Main, block = { publish(snapshot) })
                    previous = snapshot
                }
                // Check for clock or time-zone changes without recalculating unchanged schedules.
                delay((snapshot.nextChangeAt - now).coerceIn(1L, 30_000L))
            }
        })
    }
}
