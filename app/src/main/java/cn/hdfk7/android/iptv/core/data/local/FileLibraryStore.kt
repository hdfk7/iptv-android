package cn.hdfk7.android.iptv.core.data.local

import android.util.AtomicFile
import cn.hdfk7.android.iptv.core.data.repository.LibraryStore
import cn.hdfk7.android.iptv.core.model.PlaylistSource
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

class FileLibraryStore(directory: File) : LibraryStore {
    private val file = AtomicFile(File(directory, "library.json"))

    override fun read(): SourceLibrary? {
        val text = try {
            file.openRead().bufferedReader().use(block = { reader -> reader.readText() })
        } catch (exception: FileNotFoundException) {
            return null
        }
        val json = JSONObject(text)
        val sourceArray = json.getJSONArray("sources")
        val favoriteArray = json.getJSONArray("favorites")
        val sources = (0 until sourceArray.length()).map(transform = { index ->
            val source = sourceArray.getJSONObject(index)
            PlaylistSource(source.getString("id"), source.getString("name"), source.getString("url"),
                source.optString("guideAddress").takeIf(predicate = String::isNotBlank))
        })
        val favorites = (0 until favoriteArray.length()).map(transform = { index -> ChannelJson.decode(favoriteArray.getJSONObject(index)) })
        return SourceLibrary(sources, json.getString("activeSourceId"), favorites)
    }

    override fun write(library: SourceLibrary) {
        val sources = JSONArray()
        for (source in library.sources) {
            sources.put(JSONObject().put("id", source.id).put("name", source.name).put("url", source.url)
                .put("guideAddress", source.guideAddress))
        }
        val favorites = JSONArray()
        for (favorite in library.favorites) {
            favorites.put(ChannelJson.encode(favorite))
        }
        val data = JSONObject().put("version", 1).put("sources", sources).put("activeSourceId", library.activeSourceId).put("favorites", favorites).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (exception: Exception) {
            file.failWrite(output)
            throw exception
        }
    }
}
