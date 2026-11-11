package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ChannelNames
import java.net.URI
import cn.hdfk7.android.iptv.core.model.GuideRef

/** Extended M3U channel catalog; HLS manifests are media, not channel catalogs. */
class PlaylistParser {
    private data class Entry(val name: String, var group: String, val urls: MutableMap<String, Int?> = linkedMapOf(), val guideRefs: MutableSet<GuideRef> = linkedSetOf())

    private val header = Regex("""#EXTM3U(?:\s.*)?""")
    private val guideAddress = Regex("""(?:^|\s)(?:x-tvg-url|url-tvg)="([^"]*)"""")
    private val guideId = Regex("""(?:^|\s)tvg-id="([^"]*)"""")
    private val tvgName = Regex("""(?:^|\s)tvg-name="([^"]*)"""")
    private val groupTitle = Regex("""(?:^|\s)group-title="([^"]*)"""")
    private val responseTime = Regex("""(?:^|\s)response-time="([0-9]{1,5})ms"""")

    fun parse(
        text: String,
        sourceUrl: String,
        readResponseTime: Boolean = false,
        fallbackGuideAddress: String? = null
    ): List<Channel> {
        val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
        val first = if (lines.hasNext()) lines.next().trim() else ""
        require(value = header.matches(first), lazyMessage = { "Expected an extended M3U catalog" })
        val base = URI(sourceUrl)
        val declaredGuides = attribute(first, guideAddress).ifBlank(defaultValue = { fallbackGuideAddress.orEmpty() })
        val guides = declaredGuides.split(',').mapNotNull(transform = { address ->
            if (address.isBlank()) {
                null
            } else {
                runCatching(block = { base.resolve(address.trim()) }).getOrNull()?.takeIf(predicate = ::validHttp)?.toString()
            }
        }).distinct().take(4)
        val channels = linkedMapOf<String, Entry>()
        var name: String? = null
        var group = ""
        var latency: Int? = null
        var channelGuideId = ""
        for (rawLine in lines) {
            val line = rawLine.trim()
            require(value = !line.startsWith("#EXT-X-"), lazyMessage = { "HLS media manifests are not channel catalogs" })
            if (line.startsWith("#EXTINF:")) {
                val separator = metadataSeparator(line)
                name = if (separator >= 0) {
                    line.substring(separator + 1).trim().ifEmpty(defaultValue = { attribute(line, tvgName) })
                } else {
                    null
                }
                val metadata = if (separator >= 0) line.substring(0, separator) else ""
                group = attribute(metadata, groupTitle)
                channelGuideId = attribute(metadata, guideId).trim()
                latency = if (readResponseTime && separator >= 0) {
                    attribute(line.substring(0, separator), responseTime).toIntOrNull()?.takeIf(predicate = { time -> time in 1..60_000 })
                } else {
                    null
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val channelName = name
                name = null
                if (channelName.isNullOrBlank()) {
                    continue
                }
                val uri = runCatching(block = { base.resolve(line) }).getOrNull()
                if (uri != null && validHttp(uri)) {
                    val entry = channels.getOrPut(key = ChannelNames.key(channelName), defaultValue = { Entry(ChannelNames.display(channelName), group) })
                    if (entry.group.isBlank()) {
                        entry.group = group
                    }
                    entry.guideRefs.addAll(guides.map(transform = { address ->
                        GuideRef(address, channelGuideId, entry.name)
                    }))
                    val url = uri.toString()
                    if (entry.urls[url] == null) {
                        entry.urls[url] = latency
                    }
                }
            }
        }
        return channels.values.map(transform = { entry ->
            val timings = entry.urls.entries.mapNotNull(transform = { line -> line.value?.let(block = { time -> line.key to time }) }).toMap()
            Channel(entry.name, entry.group, entry.urls.keys.toList(), responseTimesMs = timings, guideRefs = entry.guideRefs.toList())
        })
    }

    private fun validHttp(uri: URI): Boolean {
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535)
    }

    private fun attribute(line: String, pattern: Regex): String {
        return pattern.find(line)?.groupValues?.get(1).orEmpty()
    }

    private fun metadataSeparator(line: String): Int {
        var quoted = false
        for ((index, character) in line.withIndex()) {
            if (character == '"') {
                quoted = !quoted
            } else if (character == ',' && !quoted) {
                return index
            }
        }
        return -1
    }
}
