package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LibraryRulesTests {
    @Test
    fun newLibraryContainsOnlyDomesticSource(): Unit {
        val library = LibraryRules.defaults()
        assertEquals(listOf(PlaylistSource("best-fan", "国内精选", LibraryRules.DEFAULT_URL)), library.sources)
        assertEquals("best-fan", library.activeSourceId)
    }

    @Test
    fun savesAndClearsAnOptionalGuideWithoutChangingPlaylistIdentity(): Unit {
        val initial = LibraryRules.defaults()
        val source = initial.sources.first()
        val changed = LibraryRules.save(initial, source.id, source.name, source.url,
            guideAddress = " https://example.org/guide.xml.gz ")
        assertEquals(source.copy(guideAddress = "https://example.org/guide.xml.gz"), changed.sources.first())
        assertEquals(source, LibraryRules.save(changed, source.id, source.name, source.url,
            guideAddress = "").sources.first())
        val error = assertThrows(LibraryException::class.java, {
            LibraryRules.save(initial, source.id, source.name, source.url, guideAddress = "file:///guide.xml")
        })
        assertEquals(LibraryError.GUIDE_ADDRESS, error.error)
    }
    @Test
    fun restoreDefaultsBringsBackStockSourcesAndKeepsFavorites() {
        val favorite = Channel("CCTV1", "TV", listOf("https://example.org/live"), "best-fan", "精选")
        val emptied = LibraryRules.defaults().let { library ->
            LibraryRules.toggleFavorite(library, favorite).copy(sources = emptyList(), activeSourceId = SourceLibrary.FAVORITES_ID)
        }
        val restored = LibraryRules.restoreDefaults(emptied)
        assertEquals(listOf("国内精选"), restored.sources.map(transform = { source -> source.name }))
        assertEquals("best-fan", restored.activeSourceId)
        assertEquals(listOf(favorite), restored.favorites)
    }

    @Test
    fun knownRepositoriesAndGithubFileLinksResolveToRawPlaylists() {
        assertEquals(LibraryRules.DEFAULT_URL, LibraryRules.normalizeAddress("https://github.com/best-fan/iptv-sources.git"))
        assertEquals("https://example.org/playlist/", LibraryRules.normalizeAddress("https://example.org/playlist/"))
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/tv.m3u", LibraryRules.normalizeAddress("https://github.com/owner/repo/blob/main/tv.m3u"))
        for (url in listOf("file:///tmp/test", "ftp://example.org/tv", "https://user:pass@example.org/tv", "https://github.com/unknown/repo", "not a url")) {
            assertThrows(LibraryException::class.java, { LibraryRules.normalizeAddress(url) })
        }
    }

    @Test
    fun addAndEditPreserveSingleActiveSelectionAndIdentity() {
        val initial = LibraryRules.defaults()
        val added = LibraryRules.save(initial, null, " Custom ", "https://example.org/a.m3u", "custom")
        assertEquals(initial.activeSourceId, added.activeSourceId)
        assertEquals("Custom", added.sources.last().name)
        val edited = LibraryRules.save(added, "custom", "Renamed", "https://example.org/b.m3u")
        assertEquals("custom", edited.sources.last().id)
        assertEquals(initial.sources.size + 1, edited.sources.size)
        val active = LibraryRules.activate(edited, "custom")
        assertEquals("custom", active.activeSourceId)
        assertEquals(SourceLibrary.FAVORITES_ID, LibraryRules.activate(active, SourceLibrary.FAVORITES_ID).activeSourceId)
    }

    @Test
    fun duplicateInvalidAndMissingEditsAreRejected() {
        val initial = LibraryRules.defaults()
        assertEquals(LibraryError.DUPLICATE, assertThrows(LibraryException::class.java, { LibraryRules.save(initial, null, "Duplicate", "https://github.com/best-fan/iptv-sources") }).error)
        assertEquals(LibraryError.NAME, assertThrows(LibraryException::class.java, { LibraryRules.save(initial, null, " ", "https://example.org/a") }).error)
        assertEquals(LibraryError.MISSING, assertThrows(LibraryException::class.java, { LibraryRules.save(initial, "missing", "Test", "https://example.org/a") }).error)
    }

    @Test
    fun deletingActiveSourceSelectsOneRemainingSourceAndKeepsSavedFavorites() {
        var library = LibraryRules.save(LibraryRules.defaults(), null, "Custom", "https://example.org/custom.m3u", "custom")
        val favorite = Channel("CCTV1", "TV", listOf("https://example.org/live"), "best-fan", "First")
        library = LibraryRules.toggleFavorite(library, favorite)
        library = LibraryRules.delete(library, "best-fan")
        assertEquals("custom", library.activeSourceId)
        assertEquals(listOf(favorite), library.favorites)
        library = LibraryRules.delete(library, "custom")
        assertEquals(SourceLibrary.FAVORITES_ID, library.activeSourceId)
        assertTrue(library.sources.isEmpty())
        assertEquals(1, library.favorites.size)
    }

    @Test
    fun sameProgramFromDifferentSourcesContributesDistinctAddressesToOneFavoriteProgram() {
        val first = Channel("CCTV-1", "A", listOf("https://example.org/a", "https://example.org/shared"), "best-fan", "First")
        val second = Channel("cctv1综合", "B", listOf("https://example.org/b", "https://example.org/shared"), "myiptv", "Second")
        val initial = LibraryRules.save(LibraryRules.defaults(), null, second.sourceName, "https://example.org/second.m3u", second.sourceId)
        var library = LibraryRules.toggleFavorite(initial, first)
        library = LibraryRules.toggleFavorite(library, second)
        assertEquals(2, library.favorites.size)
        val aggregate = ChannelAggregation.merge(library.favorites, SourceLibrary.FAVORITES_ID).single()
        assertEquals("CCTV1", aggregate.name)
        assertEquals(listOf("https://example.org/a", "https://example.org/shared", "https://example.org/b"), aggregate.streams)
        assertEquals("First / Second", aggregate.sourceName)
        val removed = LibraryRules.toggleFavorite(library, aggregate)
        assertTrue(removed.favorites.isEmpty())
    }

    @Test
    fun refreshingFavoriteUpdatesUrlsAndKeepsMissingProgramsForOfflineFallback() {
        val source = LibraryRules.defaults().sources.first()
        val first = Channel("CCTV1", "old group", listOf("https://example.org/old"), source.id, source.name)
        val missing = Channel("Missing", "TV", listOf("https://example.org/saved"), source.id, source.name)
        val library = LibraryRules.defaults().copy(favorites = listOf(first, missing))
        val updated = LibraryRules.syncFavorites(library, source, listOf(Channel("CCTV-1综合HD", "new group", listOf("https://example.org/new"))))
        assertEquals(listOf("https://example.org/new"), updated.favorites.first().streams)
        assertEquals(missing, updated.favorites.last())
    }

    @Test
    fun channelNamesMergeCommonVariantsWithoutCombiningDistinctPrograms() {
        for (name in listOf("CCTV1", "cctv-1", "CCTV 1 综合", "CCTV1(720p)", "CCTV1高清")) {
            assertEquals("CCTV1", ChannelNames.key(name))
        }
        assertNotEquals(ChannelNames.key("CCTV5"), ChannelNames.key("CCTV5+"))
        assertNotEquals(ChannelNames.key("CCTV4K"), ChannelNames.key("CCTV4"))
        assertNotEquals(ChannelNames.key("CCTV4欧洲"), ChannelNames.key("CCTV4美洲"))
    }
}
