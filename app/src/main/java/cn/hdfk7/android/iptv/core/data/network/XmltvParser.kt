package cn.hdfk7.android.iptv.core.data.network

import android.util.Xml
import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.model.ChannelNames
import cn.hdfk7.android.iptv.core.model.GuideRef
import cn.hdfk7.android.iptv.core.model.programmeDay
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser

private val xmltvDate = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
    .withResolverStyle(ResolverStyle.STRICT)
private val xmltvTimeSeparator = Regex("\\s+")

internal fun xmltvTime(value: String?): Long? {
    val parts = value?.trim()?.split(xmltvTimeSeparator, limit = 2) ?: return null
    return runCatching(block = {
        val local = LocalDateTime.parse(parts.first(), xmltvDate)
        val offset = parts.getOrNull(1)?.let(block = ZoneOffset::of) ?: ZoneOffset.UTC
        local.toInstant(offset).toEpochMilli()
    }).getOrNull()
}

internal suspend fun parseXmltv(text: String, refs: List<GuideRef>, now: Long): Map<GuideRef, List<Programme>> {
    val context = currentCoroutineContext()
    context.ensureActive()
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(text.removePrefix("\uFEFF").reader())
    val result = mutableMapOf<String, MutableList<Programme>>()
    val declaredIds = mutableSetOf<String>()
    val displayIds = mutableMapOf<String, MutableSet<String>>()
    var resolved: Map<GuideRef, String>? = null
    var channelIds = emptySet<String>()
    var declaredChannel: String? = null
    var channel: String? = null
    var start: Long? = null
    var end: Long? = null
    var title: String? = null
    var inTitle = false
    var root = false
    val horizon = now + 48 * 60 * 60 * 1000L
    val dayStart = programmeDay(now).first
    var event = parser.nextToken()
    var eventsUntilCancellationCheck = 256
    while (event != XmlPullParser.END_DOCUMENT) {
        eventsUntilCancellationCheck -= 1
        if (eventsUntilCancellationCheck == 0) {
            context.ensureActive()
            eventsUntilCancellationCheck = 256
        }
        when {
            event == XmlPullParser.START_TAG && parser.depth == 1 -> {
                require(parser.name == "tv")
                root = true
            }
            event == XmlPullParser.START_TAG && parser.depth == 2 && parser.name == "channel" -> {
                declaredChannel = parser.getAttributeValue(null, "id")
                declaredChannel?.let(block = declaredIds::add)
            }
            event == XmlPullParser.START_TAG && parser.depth == 3 && parser.name == "display-name" && declaredChannel != null -> {
                val name = ChannelNames.key(parser.nextText())
                if (name.isNotEmpty()) {
                    displayIds.getOrPut(name, defaultValue = { mutableSetOf() }).add(declaredChannel)
                }
            }
            event == XmlPullParser.END_TAG && parser.depth == 2 && parser.name == "channel" -> {
                declaredChannel = null
            }
            event == XmlPullParser.START_TAG && parser.depth == 2 && parser.name == "programme" -> {
                if (resolved == null) {
                    val matches = refs.mapNotNull(transform = { ref ->
                        val named = displayIds[ChannelNames.key(ref.channelName)]?.singleOrNull()
                        val id = ref.channelId.takeIf(predicate = { value ->
                            value.isNotEmpty() && (value in declaredIds || declaredIds.isEmpty())
                        }) ?: named
                        id?.let(block = { value -> ref to value })
                    }).toMap()
                    resolved = matches
                    channelIds = matches.values.toSet()
                }
                channel = parser.getAttributeValue(null, "channel")?.takeIf(predicate = channelIds::contains)
                start = if (channel != null) xmltvTime(parser.getAttributeValue(null, "start")) else null
                end = if (channel != null) xmltvTime(parser.getAttributeValue(null, "stop")) else null
                title = null
            }
            event == XmlPullParser.START_TAG && parser.depth == 3 && parser.name == "title" -> {
                inTitle = start != null && end != null && title == null
            }
            inTitle && (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) -> {
                title = (title.orEmpty() + parser.text.orEmpty()).take(240)
            }
            event == XmlPullParser.END_TAG && parser.depth == 3 && parser.name == "title" -> {
                inTitle = false
            }
            event == XmlPullParser.END_TAG && parser.depth == 2 && parser.name == "programme" -> {
                if (channel != null && start != null && end != null && end > start && end > dayStart && start < horizon && !title.isNullOrBlank()) {
                    result.getOrPut(channel, defaultValue = { mutableListOf() }).add(Programme(title.trim(), start, end))
                }
                channel = null
                start = null
                end = null
            }
        }
        event = parser.nextToken()
    }
    context.ensureActive()
    require(root)
    val sorted = result.mapValues(transform = { entry -> entry.value.distinct().sortedBy(selector = Programme::startsAt) })
    return refs.associateWith(valueSelector = { ref -> sorted[resolved?.get(ref)].orEmpty() })
}
