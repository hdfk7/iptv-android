package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.data.local.FileLibraryStore
import cn.hdfk7.android.iptv.core.data.local.FilePlaylistStore
import cn.hdfk7.android.iptv.core.data.local.cacheKey
import cn.hdfk7.android.iptv.core.data.network.PlaylistDownloader
import cn.hdfk7.android.iptv.core.model.CatalogState
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ChannelAggregation
import cn.hdfk7.android.iptv.core.model.GuideRef
import cn.hdfk7.android.iptv.core.model.LibraryError
import cn.hdfk7.android.iptv.core.model.LibraryException
import cn.hdfk7.android.iptv.core.model.PlaylistSnapshot
import cn.hdfk7.android.iptv.core.model.PlaylistSource
import cn.hdfk7.android.iptv.core.model.RefreshResult
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface LibraryStore {
    fun read(): SourceLibrary?
    fun write(library: SourceLibrary)
}

class PlaylistRepository(
    private val libraryStore: LibraryStore,
    private val playlistStores: (PlaylistSource) -> PlaylistStore,
    private val download: suspend (String) -> String,
    private val clock: () -> Long,
    private val restoreActiveSource: () -> Boolean = { false }
) {
    constructor(
        directory: File,
        downloader: PlaylistDownloader = PlaylistDownloader(),
        restoreActiveSource: () -> Boolean = { false }
    ) : this(
        FileLibraryStore(directory),
        { source -> FilePlaylistStore(directory, "playlist-${cacheKey(source.url)}.json") },
        downloader::download,
        System::currentTimeMillis,
        restoreActiveSource
    )

    private val mutex = Mutex()
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private val snapshots = mutableMapOf<String, PlaylistSnapshot>()
    private val refreshingIds = mutableSetOf<String>()
    private val failedIds = mutableSetOf<String>()
    private var library = LibraryRules.defaults()
    private var loaded = false
    private var storageFailed = false
    private var publishedSourceChannels: List<Channel> = emptyList()
    private val mutableState = MutableStateFlow(CatalogState())
    val state = mutableState.asStateFlow()

    suspend fun initialize() {
        withContext(context = Dispatchers.IO, block = {
            mutex.withLock(action = { load() })
        })
    }

    suspend fun activate(id: String) {
        mutate(transform = { current -> LibraryRules.activate(current, id) })
    }

    suspend fun saveSource(id: String?, name: String, address: String, guideAddress: String? = null) {
        mutate(transform = { current -> LibraryRules.save(current, id, name, address, guideAddress = guideAddress) })
    }

    suspend fun deleteSource(id: String) {
        mutate(transform = { current ->
            val address = current.sources.firstOrNull(predicate = { source -> source.id == id })?.guideAddress
            val updated = LibraryRules.delete(current, id)
            updated.copy(favorites = updated.favorites.map(transform = { channel ->
                if (channel.sourceId == id) withGuideAddress(channel, address) else channel
            }))
        })
    }

    suspend fun restoreDefaults() {
        mutate(transform = { current -> LibraryRules.restoreDefaults(current) })
    }

    suspend fun toggleFavorite(channel: Channel) {
        mutate(transform = { current ->
            val original = snapshots[channel.sourceId]?.channels?.firstOrNull(predicate = { entry -> entry.key == channel.key })
            val contribution = if (original != null) {
                channel.copy(guideRefs = original.guideRefs, guideIds = original.guideIds)
            } else {
                channel
            }
            LibraryRules.toggleFavorite(current, contribution)
        })
    }

    private suspend fun mutate(transform: (SourceLibrary) -> SourceLibrary) {
        withContext(context = Dispatchers.IO, block = {
            mutex.withLock(action = {
                load()
                val updated = transform(library)
                try {
                    libraryStore.write(updated)
                } catch (exception: Exception) {
                    throw LibraryException(LibraryError.STORAGE)
                }
                val oldSources = library.sources.associateBy(keySelector = { source -> source.id })
                library = updated
                val remainingIds = updated.sources.map(transform = { source -> source.id }).toSet()
                snapshots.keys.retainAll(remainingIds)
                failedIds.retainAll(remainingIds)
                for (source in updated.sources) {
                    if (oldSources[source.id]?.url != source.url) {
                        snapshots.remove(source.id)
                        failedIds.remove(source.id)
                        val cached = playlistStores(source).read()
                        if (cached != null) {
                            snapshots[source.id] = cached
                        }
                    }
                }
                storageFailed = false
                publish()
            })
        })
    }

    suspend fun refresh(): RefreshResult {
        return withContext(context = Dispatchers.IO, block = {
            val sources = mutex.withLock(action = {
                load()
                if (library.activeSourceId == SourceLibrary.FAVORITES_ID) {
                    val ids = library.favorites.map(transform = { channel -> channel.sourceId }).toSet()
                    library.sources.filter(predicate = { source -> source.id in ids })
                } else {
                    library.sources.filter(predicate = { source -> source.id == library.activeSourceId })
                }
            })
            var failed = false
            for (source in sources) {
                failed = refreshSource(source) || failed
            }
            val current = state.value
            val snapshot = if (current.channels.isEmpty()) null else PlaylistSnapshot(current.channels, current.updatedAt ?: 0)
            RefreshResult(snapshot, failed || current.storageFailed)
        })
    }

    private suspend fun refreshSource(source: PlaylistSource): Boolean {
        val sourceLock = sourceLocks.getOrPut(key = source.id, defaultValue = { Mutex() })
        return sourceLock.withLock(action = {
            val stillPresent = mutex.withLock(action = {
                val matches = library.sources.any(predicate = { current -> current.id == source.id && current.url == source.url })
                if (matches) {
                    refreshingIds.add(source.id)
                    publish()
                }
                matches
            })
            if (!stillPresent) {
                return@withLock false
            }
            try {
                val domestic = LibraryRules.isDomestic(source)
                val result = RefreshEngine(playlistStores(source), download, clock).refresh(
                    source.url,
                    readResponseTime = domestic,
                    fallbackGuideAddress = if (domestic) LibraryRules.DOMESTIC_GUIDE_URL else null
                )
                mutex.withLock(action = {
                    val currentSource = library.sources.firstOrNull(predicate = { current -> current.id == source.id && current.url == source.url })
                    if (currentSource != null) {
                        if (result.snapshot != null) {
                            snapshots[source.id] = result.snapshot
                            val updated = LibraryRules.syncFavorites(library, currentSource, result.snapshot.channels)
                            if (updated != library) {
                                try {
                                    libraryStore.write(updated)
                                    library = updated
                                    storageFailed = false
                                } catch (exception: Exception) {
                                    storageFailed = true
                                }
                            }
                        }
                        if (result.failed) {
                            failedIds.add(source.id)
                        } else {
                            failedIds.remove(source.id)
                        }
                    }
                })
                result.failed
            } finally {
                withContext(context = NonCancellable, block = {
                    mutex.withLock(action = {
                        refreshingIds.remove(source.id)
                        publish()
                    })
                })
            }
        })
    }

    private fun load() {
        if (loaded) {
            return
        }
        try {
            val stored = libraryStore.read()
            val saved = stored ?: LibraryRules.defaults()
            val savedSourceExists = saved.activeSourceId == SourceLibrary.FAVORITES_ID ||
                saved.sources.any(predicate = { source -> source.id == saved.activeSourceId })
            library = if (restoreActiveSource() && savedSourceExists) {
                saved
            } else {
                saved.copy(activeSourceId = saved.sources.firstOrNull()?.id ?: SourceLibrary.FAVORITES_ID)
            }
            if (stored == null || library != stored) {
                libraryStore.write(library)
            }
            for (source in library.sources) {
                val cached = playlistStores(source).read()
                if (cached != null) {
                    snapshots[source.id] = cached
                }
            }
            loaded = true
            storageFailed = false
            publish()
        } catch (exception: Exception) {
            storageFailed = true
            publish()
            throw LibraryException(LibraryError.STORAGE)
        }
    }

    private fun publish() {
        val previous = mutableState.value
        val favoritesMode = library.activeSourceId == SourceLibrary.FAVORITES_ID
        val source = library.sources.firstOrNull(predicate = { entry -> entry.id == library.activeSourceId })
        val sourceChannels = source?.let(block = { current -> snapshots[current.id]?.channels }).orEmpty()
        val channels = if (previous.library == library && publishedSourceChannels == sourceChannels) {
            previous.channels
        } else if (favoritesMode) {
            val sources = library.sources.associateBy(keySelector = { entry -> entry.id })
            val contributions = library.favorites.map(transform = { channel ->
                withGuideAddress(channel, sources[channel.sourceId]?.guideAddress)
            })
            ChannelAggregation.merge(contributions, SourceLibrary.FAVORITES_ID)
        } else if (source != null) {
            sourceChannels.map(transform = { channel ->
                withGuideAddress(channel.copy(sourceId = source.id, sourceName = source.name), source.guideAddress)
            })
        } else {
            emptyList()
        }
        val relevantIds = if (favoritesMode) {
            library.favorites.map(transform = { channel -> channel.sourceId }).toSet()
        } else {
            setOf(library.activeSourceId)
        }
        val updatedAt = relevantIds.mapNotNull(transform = { id -> snapshots[id]?.updatedAt }).minOrNull()
        publishedSourceChannels = sourceChannels
        mutableState.value = CatalogState(
            library = library,
            loaded = loaded,
            channels = channels,
            updatedAt = updatedAt,
            refreshing = relevantIds.any(predicate = { id -> id in refreshingIds }),
            refreshFailed = relevantIds.any(predicate = { id -> id in failedIds }),
            storageFailed = storageFailed
        )
    }

    private fun withGuideAddress(channel: Channel, address: String?): Channel {
        if (address == null) {
            return channel
        }
        val ids = (channel.guideIds + channel.guideRefs.map(transform = { ref -> ref.channelId }))
            .filter(predicate = String::isNotBlank).distinct().ifEmpty(defaultValue = { listOf("") })
        return channel.copy(guideRefs = ids.map(transform = { id ->
            GuideRef(address, id, channel.name)
        }))
    }
}
