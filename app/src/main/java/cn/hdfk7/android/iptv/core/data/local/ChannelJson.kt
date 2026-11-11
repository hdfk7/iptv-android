package cn.hdfk7.android.iptv.core.data.local

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.GuideRef
import org.json.JSONArray
import org.json.JSONObject

internal object ChannelJson {
    fun encode(channel: Channel): JSONObject {
        val entry = JSONObject().put("name", channel.name).put("group", channel.group)
            .put("streams", JSONArray(channel.streams)).put("sourceId", channel.sourceId).put("sourceName", channel.sourceName)
        if (channel.guideRefs.isNotEmpty()) {
            entry.put("guideRefs", JSONArray(channel.guideRefs.map(transform = { ref ->
                JSONObject().put("address", ref.address).put("id", ref.channelId).put("name", ref.channelName)
            })))
        }
        if (channel.guideIds.isNotEmpty()) {
            entry.put("guideIds", JSONArray(channel.guideIds))
        }
        return entry
    }

    fun decode(json: JSONObject): Channel {
        val urls = json.getJSONArray("streams")
        val guideRefs = json.optJSONArray("guideRefs")
        val guideIds = json.optJSONArray("guideIds")
        return Channel(
            name = json.getString("name"),
            group = json.getString("group"),
            streams = (0 until urls.length()).map(transform = { index -> urls.getString(index) }),
            sourceId = json.optString("sourceId"),
            sourceName = json.optString("sourceName"),
            guideRefs = (0 until (guideRefs?.length() ?: 0)).mapNotNull(transform = { index ->
                guideRefs?.optJSONObject(index)?.let(block = { ref -> GuideRef(ref.getString("address"), ref.getString("id"), ref.optString("name")) })
            }),
            guideIds = (0 until (guideIds?.length() ?: 0)).mapNotNull(transform = { index ->
                guideIds?.optString(index)?.takeIf(predicate = String::isNotEmpty)
            })
        )
    }
}
