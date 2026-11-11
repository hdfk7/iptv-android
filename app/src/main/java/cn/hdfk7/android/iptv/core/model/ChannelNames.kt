package cn.hdfk7.android.iptv.core.model

import java.text.Normalizer
import java.util.Locale

/** Conservative name identity: CCTV's common formatting variants share one program. */
object ChannelNames {
    private val whitespace = Regex("\\s+")
    private val resolution = Regex("[\\(\\[【](?:[0-9]{3,4}[IP]|HD|FHD|UHD|高清|超清|标清)[\\)\\]】]")
    private val cctv = Regex("^CCTV[-_]?([0-9]{1,2})([+]?)(?:(?:综合|财经|综艺|中文国际|体育赛事|体育|电影|国防军事|军事农业|电视剧|纪录|科教|戏曲|社会与法|新闻|少儿|音乐|农业农村|奥林匹克|高清|超清|标清|HD|FHD|UHD|[0-9]{3,4}[IP]))*$")

    fun key(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).uppercase(Locale.ROOT)
        val compact = normalized.replace(whitespace, "").replace(resolution, "")
        val match = cctv.matchEntire(compact)
        if (match != null) {
            return "CCTV${match.groupValues[1]}${match.groupValues[2]}"
        }
        return compact
    }

    fun display(name: String): String {
        val key = key(name)
        if (Regex("^CCTV[0-9]{1,2}[+]?$").matches(key)) {
            return key
        }
        return name.trim()
    }
}

object ChannelAggregation {
    fun merge(channels: List<Channel>, sourceId: String? = null): List<Channel> {
        return channels.groupBy(keySelector = { channel -> channel.key }).values.map(transform = { entries ->
            val first = entries.first()
            first.copy(
                name = ChannelNames.display(first.name),
                group = entries.firstOrNull(predicate = { channel -> channel.group.isNotBlank() })?.group.orEmpty(),
                guideRefs = entries.flatMap(transform = { channel -> channel.guideRefs }).distinct(),
                streams = entries.flatMap(transform = { channel -> channel.streams }).distinct(),
                sourceId = sourceId ?: first.sourceId,
                sourceName = entries.map(transform = { channel -> channel.sourceName }).filter(predicate = { name -> name.isNotBlank() }).distinct().joinToString(" / ")
            )
        })
    }
}
