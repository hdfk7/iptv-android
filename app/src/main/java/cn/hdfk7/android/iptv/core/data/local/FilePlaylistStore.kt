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
                Channel(entry.getString("name"), entry.getString("group"), (0 until urls.length()).map(transform = { urlIndex -> urls.getString(urlIndex) }))
            })
            PlaylistSnapshot(channels, json.getLong("updatedAt"))
        }).getOrNull()
    })

    override fun write(snapshot: PlaylistSnapshot) {
        val array = JSONArray()
        for (channel in snapshot.channels) {
            array.put(JSONObject().put("name", channel.name).put("group", channel.group).put("streams", JSONArray(channel.streams)))
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
