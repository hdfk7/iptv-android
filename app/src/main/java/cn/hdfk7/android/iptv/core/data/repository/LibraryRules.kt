package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.LibraryError
import cn.hdfk7.android.iptv.core.model.LibraryException
import cn.hdfk7.android.iptv.core.model.PlaylistSource
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import java.net.URI
import java.util.UUID

object LibraryRules {
    private const val DOMESTIC_SOURCE_ID = "best-fan"
    const val DEFAULT_URL = "https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8"
    internal const val DOMESTIC_GUIDE_URL = "http://epg.51zmt.top:8000/e.xml"

    fun defaults(): SourceLibrary {
        val sources = listOf(
            PlaylistSource(DOMESTIC_SOURCE_ID, "国内精选", DEFAULT_URL)
        )
        return SourceLibrary(sources, sources.first().id)
    }

    fun isDomestic(source: PlaylistSource): Boolean {
        return source.id == DOMESTIC_SOURCE_ID && source.url == DEFAULT_URL
    }

    fun restoreDefaults(library: SourceLibrary): SourceLibrary {
        val sources = defaults().sources
        return library.copy(sources = sources, activeSourceId = sources.first().id)
    }

    fun normalizeAddress(address: String): String = normalizeAddress(address, allowRepositoryDefaults = true)

    private fun normalizeAddress(address: String, allowRepositoryDefaults: Boolean): String {
        val input = address.trim()
        val uri = runCatching(block = { URI(input) }).getOrNull()
        val valid = uri != null && uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535) && input.length <= 4096
        if (!valid) {
            throw LibraryException(LibraryError.ADDRESS)
        }
        if (uri.host.equals("github.com", ignoreCase = true)) {
            val path = uri.path.trim('/').removeSuffix(".git")
            if (allowRepositoryDefaults && path.equals("best-fan/iptv-sources", ignoreCase = true)) {
                return DEFAULT_URL
            }
            val parts = uri.rawPath.trim('/').split('/')
            if (parts.size >= 5 && parts[2] == "blob") {
                return "https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts.drop(3).joinToString("/")}"
            }
            throw LibraryException(LibraryError.ADDRESS)
        }
        return uri.toASCIIString()
    }

    fun save(library: SourceLibrary, id: String?, name: String, address: String,
        newId: String = UUID.randomUUID().toString(), guideAddress: String? = null): SourceLibrary {
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.length > 48) {
            throw LibraryException(LibraryError.NAME)
        }
        if (id != null && library.sources.none(predicate = { source -> source.id == id })) {
            throw LibraryException(LibraryError.MISSING)
        }
        val url = normalizeAddress(address)
        val duplicate = library.sources.any(predicate = { source -> source.id != id && source.url == url })
        if (duplicate) {
            throw LibraryException(LibraryError.DUPLICATE)
        }
        val guide = guideAddress?.takeIf(predicate = String::isNotBlank)?.let(block = { input ->
            try {
                normalizeAddress(input, allowRepositoryDefaults = false)
            } catch (exception: LibraryException) {
                throw LibraryException(LibraryError.GUIDE_ADDRESS)
            }
        })
        val source = PlaylistSource(id ?: newId, cleanName, url, guide)
        val sources = if (id == null) {
            library.sources + source
        } else {
            library.sources.map(transform = { existing -> if (existing.id == id) source else existing })
        }
        val favorites = library.favorites.map(transform = { channel ->
            if (channel.sourceId == source.id) {
                channel.copy(sourceName = source.name)
            } else {
                channel
            }
        })
        return library.copy(sources = sources, favorites = favorites)
    }

    fun activate(library: SourceLibrary, id: String): SourceLibrary {
        if (id != SourceLibrary.FAVORITES_ID && library.sources.none(predicate = { source -> source.id == id })) {
            throw LibraryException(LibraryError.MISSING)
        }
        return library.copy(activeSourceId = id)
    }

    fun delete(library: SourceLibrary, id: String): SourceLibrary {
        if (library.sources.none(predicate = { source -> source.id == id })) {
            throw LibraryException(LibraryError.MISSING)
        }
        val sources = library.sources.filter(predicate = { source -> source.id != id })
        val activeId = if (library.activeSourceId == id) {
            sources.firstOrNull()?.id ?: SourceLibrary.FAVORITES_ID
        } else {
            library.activeSourceId
        }
        // Favorites keep their last saved stream addresses even after source deletion.
        return library.copy(sources = sources, activeSourceId = activeId)
    }

    fun toggleFavorite(library: SourceLibrary, channel: Channel): SourceLibrary {
        if (channel.sourceId == SourceLibrary.FAVORITES_ID) {
            return library.copy(favorites = library.favorites.filter(predicate = { favorite -> favorite.key != channel.key }))
        }
        val existing = library.favorites.any(predicate = { favorite -> favorite.id == channel.id })
        val favorites = if (existing) {
            library.favorites.filter(predicate = { favorite -> favorite.id != channel.id })
        } else {
            if (library.sources.none(predicate = { source -> source.id == channel.sourceId })) {
                throw LibraryException(LibraryError.MISSING)
            }
            library.favorites + channel.copy(responseTimesMs = emptyMap())
        }
        return library.copy(favorites = favorites)
    }

    fun syncFavorites(library: SourceLibrary, source: PlaylistSource, channels: List<Channel>): SourceLibrary {
        val byKey = channels.associateBy(keySelector = { channel -> channel.key })
        val favorites = library.favorites.map(transform = { favorite ->
            if (favorite.sourceId == source.id) {
                val current = byKey[favorite.key]
                current?.copy(sourceId = source.id, sourceName = source.name, responseTimesMs = emptyMap()) ?: favorite
            } else {
                favorite
            }
        })
        return library.copy(favorites = favorites)
    }
}
