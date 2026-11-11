package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ChannelNames
import java.net.URI

/** Extended M3U channel catalog; HLS manifests are media, not channel catalogs. */
class PlaylistParser {
    private data class Entry(val name: String, var group: String, val urls: MutableSet<String> = linkedSetOf())

    private val header = Regex("""#EXTM3U(?:\s.*)?""")
    private val tvgName = Regex("""(?:^|\s)tvg-name="([^"]*)"""")
    private val groupTitle = Regex("""(?:^|\s)group-title="([^"]*)"""")

    fun parse(text: String, sourceUrl: String): List<Channel> {
        val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
        require(value = lines.hasNext() && header.matches(lines.next().trim()), lazyMessage = { "Expected an extended M3U catalog" })
        val base = URI(sourceUrl)
        val channels = linkedMapOf<String, Entry>()
        var name: String? = null
        var group = ""
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
                group = attribute(line, groupTitle)
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val channelName = name
                name = null
                if (channelName.isNullOrBlank()) {
                    continue
                }
                val uri = runCatching(block = { base.resolve(line) }).getOrNull()
                if (uri != null && (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535)) {
                    val entry = channels.getOrPut(key = ChannelNames.key(channelName), defaultValue = { Entry(ChannelNames.display(channelName), group) })
                    if (entry.group.isBlank()) {
                        entry.group = group
                    }
                    entry.urls.add(uri.toString())
                }
            }
        }
        return channels.values.map(transform = { entry -> Channel(entry.name, entry.group, entry.urls.toList()) })
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
