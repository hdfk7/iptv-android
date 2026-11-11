package cn.hdfk7.android.iptv.core.data.local

import android.util.AtomicFile
import cn.hdfk7.android.iptv.core.data.repository.PlaylistStore
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.PlaylistSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FilePlaylistStore(directory: File, name: String) : PlaylistStore {
    private companion object {
        // The same cache path may be opened by different store instances.
        val fileLock = Any()
    }

    private val file = AtomicFile(File(directory, name))

    override fun read(): PlaylistSnapshot? = synchronized(lock = fileLock, block = {
        runCatching(block = {
            val json = JSONObject(file.openRead().bufferedReader().use(block = { reader -> reader.readText() }))
            val array = json.getJSONArray("channels")
            val channels = (0 until array.length()).map(transform = { index ->
                val entry = array.getJSONObject(index)
                val urls = entry.getJSONArray("streams")
                val streams = (0 until urls.length()).map(transform = { urlIndex -> urls.getString(urlIndex) })
                val hints = entry.optJSONObject("responseTimesMs")
                val timings = if (hints == null) {
                    emptyMap()
                } else {
                    streams.mapNotNull(transform = { url ->
                        val time = hints.optInt(url)
                        if (time in 1..60_000) url to time else null
                    }).toMap()
                }
                Channel(entry.getString("name"), entry.getString("group"), streams, responseTimesMs = timings)
            })
            PlaylistSnapshot(channels, json.getLong("updatedAt"))
        }).getOrNull()
    })

    override fun write(snapshot: PlaylistSnapshot) {
        val array = JSONArray()
        for (channel in snapshot.channels) {
            val entry = JSONObject().put("name", channel.name).put("group", channel.group).put("streams", JSONArray(channel.streams))
            if (channel.responseTimesMs.isNotEmpty()) {
                entry.put("responseTimesMs", JSONObject(channel.responseTimesMs))
            }
            array.put(entry)
        }
        val data = JSONObject().put("updatedAt", snapshot.updatedAt).put("channels", array).toString().toByteArray(Charsets.UTF_8)
        synchronized(lock = fileLock, block = {
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (exception: Exception) {
                file.failWrite(output)
                throw exception
            }
        })
    }
}
