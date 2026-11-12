package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaylistRepositoryTests {
    private val first = PlaylistSource("a", "First", "https://example.org/a.m3u")
    private val second = PlaylistSource("b", "Second", "https://example.org/b.m3u")
    private val initial = SourceLibrary(listOf(first, second), first.id)

    @Test
    fun explicitGuideUpdatesCachedChannelsAndSurvivesDeletionInFavorites(): Unit = runBlocking(block = {
        val address = "https://example.org/schedule.xml"
        val repository = repository(MemoryLibrary(initial), mutableMapOf(), { _ ->
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"station-42\",Station\n/live"
        })
        repository.refresh()
        assertTrue(repository.state.value.channels.single().guideRefs.isEmpty())
        repository.saveSource(first.id, first.name, first.url, address)
        val channel = repository.state.value.channels.single()
        val expected = listOf(GuideRef(address, "station-42", "Station"))
        assertEquals(expected, channel.guideRefs)
        repository.toggleFavorite(channel)
        repository.activate(SourceLibrary.FAVORITES_ID)
        assertEquals(expected, repository.state.value.channels.single().guideRefs)
        repository.saveSource(first.id, first.name, first.url, "")
        assertTrue(repository.state.value.channels.single().guideRefs.isEmpty())
        repository.saveSource(first.id, first.name, first.url, address)
        repository.deleteSource(first.id)
        assertEquals(expected, repository.state.value.channels.single().guideRefs)
    })

    @Test
    fun domesticTimingsDoNotLeakIntoFavoritesOrAnEditedSource(): Unit = runBlocking(block = {
        val domestic = LibraryRules.defaults().sources.first().copy(name = "我的直播")
        val storage = MemoryLibrary(SourceLibrary(listOf(domestic, second), domestic.id))
        val stores = mutableMapOf<String, MemoryPlaylist>()
        val body = "#EXTM3U\n#EXTINF:-1 response-time=\"300ms\",CCTV1\nhttps://example.org/a\n" +
            "#EXTINF:-1 response-time=\"20ms\",CCTV1\nhttps://example.org/b"
        val repository = repository(storage, stores, { _ -> body })
        repository.refresh()
        val channel = repository.state.value.channels.single()
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), channel.streams)
        assertEquals(channel.streams.reversed(), channel.streamsByResponseTime())
        val restarted = repository(storage, stores, { _ -> body })
        restarted.initialize()
        assertEquals(channel.responseTimesMs, restarted.state.value.channels.single().responseTimesMs)
        repository.toggleFavorite(channel)
        repository.activate(SourceLibrary.FAVORITES_ID)
        repository.refresh()
        assertEquals(channel.streams, repository.state.value.channels.single().streams)
        assertTrue(repository.state.value.channels.single().responseTimesMs.isEmpty())
        repository.activate(domestic.id)
        repository.saveSource(domestic.id, domestic.name, "https://example.org/edited.m3u")
        repository.refresh()
        assertEquals(channel.streams, repository.state.value.channels.single().streams)
        assertTrue(repository.state.value.channels.single().responseTimesMs.isEmpty())
    })

    @Test
    fun sourceSelectionStaysIsolatedAndRestartSelectsFirstSource() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial)
            val stores = mutableMapOf<String, MemoryPlaylist>()
            val repository = repository(storage, stores, { address -> playlist(if (address == first.url) "First" else "Second") })
            repository.refresh()
            assertEquals(listOf("First"), repository.state.value.channels.map(transform = { channel -> channel.name }))
            repository.activate(second.id)
            assertTrue(repository.state.value.channels.isEmpty())
            repository.refresh()
            assertEquals(listOf("Second"), repository.state.value.channels.map(transform = { channel -> channel.name }))
            val restarted = repository(storage, stores, { _ -> throw IOException("offline") })
            restarted.initialize()
            assertEquals(first.id, restarted.state.value.library.activeSourceId)
            assertEquals("First", restarted.state.value.channels.single().name)
            assertTrue(restarted.refresh().failed)
            assertEquals("First", restarted.state.value.channels.single().name)
        })
    }

    @Test
    fun startupLeavesFavoritesForFirstSourceWithoutChangingFavorites() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial.copy(activeSourceId = SourceLibrary.FAVORITES_ID))
            val repository = repository(storage, mutableMapOf(), { _ -> throw AssertionError("No download expected") })
            repository.initialize()
            assertEquals(first.id, repository.state.value.library.activeSourceId)
            repository.activate(second.id)
            repository.initialize()
            assertEquals(second.id, repository.state.value.library.activeSourceId)
        })
    }

    @Test
    fun favoritesRefreshOnlyTheirContributorsAndMergeProgramAddresses() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial)
            val requested = mutableListOf<String>()
            val repository = repository(storage, mutableMapOf(), { address ->
                requested.add(address)
                "#EXTM3U\n#EXTINF:-1,CCTV1\n${address}/live"
            })
            repository.refresh()
            repository.toggleFavorite(repository.state.value.channels.single())
            repository.activate(second.id)
            repository.refresh()
            repository.toggleFavorite(repository.state.value.channels.single())
            repository.activate(SourceLibrary.FAVORITES_ID)
            requested.clear()
            repository.refresh()
            assertEquals(listOf(first.url, second.url), requested)
            assertEquals(1, repository.state.value.channels.size)
            assertEquals(2, repository.state.value.channels.single().streams.size)
            repository.deleteSource(first.id)
            assertEquals(2, repository.state.value.channels.single().streams.size)
            repository.toggleFavorite(repository.state.value.channels.single())
            assertTrue(repository.state.value.channels.isEmpty())
        })
    }

    @Test
    fun failedSaveDoesNotChangeLiveSelectionOrStoredConfig() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial)
            val repository = repository(storage, mutableMapOf(), { _ -> playlist("First") })
            repository.initialize()
            storage.fail = true
            val error = runCatching(block = { repository.activate(second.id) }).exceptionOrNull()
            assertTrue(error is LibraryException)
            assertEquals(first.id, repository.state.value.library.activeSourceId)
            assertEquals(first.id, storage.value?.activeSourceId)
        })
    }

    @Test
    fun editedUrlNeverReusesPreviousUrlsCache() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial)
            val repository = repository(storage, mutableMapOf(), { _ -> playlist("Old") })
            repository.refresh()
            repository.saveSource(first.id, first.name, "https://example.org/new.m3u")
            assertTrue(repository.state.value.channels.isEmpty())
        })
    }

    @Test
    fun delayedOldSourceResponseCannotOverwriteNewActiveSource() {
        runBlocking(block = {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val repository = repository(MemoryLibrary(initial), mutableMapOf(), { address ->
                if (address == first.url) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                playlist(if (address == first.url) "First" else "Second")
            })
            repository.initialize()
            val inFlight = async(context = kotlinx.coroutines.Dispatchers.IO, block = { repository.refresh() })
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                repository.activate(second.id)
                repository.refresh()
            } finally {
                release.countDown()
            }
            inFlight.await()
            assertEquals(second.id, repository.state.value.library.activeSourceId)
            assertEquals("Second", repository.state.value.channels.single().name)
        })
    }

    @Test
    fun deletingAllSourcesPersistsEmptyLibraryWithoutReseedingDefaults() {
        runBlocking(block = {
            val storage = MemoryLibrary(initial)
            val stores = mutableMapOf<String, MemoryPlaylist>()
            val repository = repository(storage, stores, { _ -> playlist("News") })
            repository.deleteSource(first.id)
            repository.deleteSource(second.id)
            val restarted = repository(storage, stores, { _ -> throw AssertionError("No download expected") })
            restarted.refresh()
            assertTrue(restarted.state.value.library.sources.isEmpty())
            assertEquals(SourceLibrary.FAVORITES_ID, restarted.state.value.library.activeSourceId)
        })
    }

    @Test
    fun restoresSavedSourceOnlyWhenEnabledAndAvailable(): Unit = runBlocking(block = {
        val saved = MemoryLibrary(initial.copy(activeSourceId = second.id))
        val restored = PlaylistRepository(saved, { _ -> MemoryPlaylist() }, { _ -> error("Unexpected download") },
            { 1000L }, restoreActiveSource = { true })
        restored.initialize()
        assertEquals(second.id, restored.state.value.library.activeSourceId)

        saved.value = initial.copy(activeSourceId = "deleted")
        val fallback = PlaylistRepository(saved, { _ -> MemoryPlaylist() }, { _ -> error("Unexpected download") },
            { 1000L }, restoreActiveSource = { true })
        fallback.initialize()
        assertEquals(initial.sources.first().id, fallback.state.value.library.activeSourceId)
    })

    private fun repository(storage: MemoryLibrary, stores: MutableMap<String, MemoryPlaylist>, download: suspend (String) -> String): PlaylistRepository {
        return PlaylistRepository(storage, { source ->
            synchronized(stores, block = { stores.getOrPut(key = source.url, defaultValue = { MemoryPlaylist() }) })
        }, download, { 1000L })
    }

    private fun playlist(name: String): String {
        return "#EXTM3U\n#EXTINF:-1,$name\nhttps://example.org/$name/live.m3u8"
    }

    private class MemoryLibrary(var value: SourceLibrary?) : LibraryStore {
        var fail = false
        override fun read(): SourceLibrary? {
            return value
        }

        override fun write(library: SourceLibrary) {
            if (fail) {
                throw IOException("disk full")
            }
            value = library
        }
    }

    private class MemoryPlaylist : PlaylistStore {
        var value: PlaylistSnapshot? = null
        override fun read(): PlaylistSnapshot? {
            return value
        }

        override fun write(snapshot: PlaylistSnapshot) {
            value = snapshot
        }
    }
}
