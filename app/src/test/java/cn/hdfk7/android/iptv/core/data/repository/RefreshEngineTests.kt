package cn.hdfk7.android.iptv.core.data.repository

import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.PlaylistSnapshot
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RefreshEngineTests {
    private val old = PlaylistSnapshot(listOf(Channel("Old", "TV", listOf("https://example.org/old"))), 100)
    private val valid = "#EXTM3U\n#EXTINF:-1,New\nhttps://example.org/new.m3u8"

    @Test
    fun successfulRefreshReplacesCacheAndTimestamp(): Unit = runBlocking(block = {
        val store = MemoryStore(old)
        val engine = RefreshEngine(store, { address ->
            assertEquals(RefreshEngine.SOURCE_URL, address)
            valid
        }, { 200L })
        val result = engine.refresh()
        assertFalse(result.failed)
        assertEquals("New", store.value?.channels?.single()?.name)
        assertEquals(200L, result.snapshot?.updatedAt)
    })

    @Test
    fun networkOrInvalidOrEmptyResponsesKeepLastSuccessfulCache(): Unit = runBlocking(block = {
        for (body in listOf("", "<html>error</html>", "#EXTM3U")) {
            val store = MemoryStore(old)
            val result = RefreshEngine(store, { _ -> body }, { 200L }).refresh()
            assertTrue(result.failed)
            assertEquals(old, store.value)
            assertEquals(old, result.snapshot)
        }
        val store = MemoryStore(old)
        val result = RefreshEngine(store, { _ -> throw IOException("offline") }, { 200L }).refresh()
        assertTrue(result.failed)
        assertEquals(old, result.snapshot)
    })

    @Test
    fun failedDiskWriteKeepsPriorSnapshot(): Unit = runBlocking(block = {
        val store = MemoryStore(old, failWrite = true)
        val result = RefreshEngine(store, { _ -> valid }, { 200L }).refresh()
        assertTrue(result.failed)
        assertEquals(old, result.snapshot)
        assertEquals(old, store.value)
    })

    @Test
    fun firstLaunchOfflineReturnsEmptyFailureAndCancellationPropagates(): Unit = runBlocking(block = {
        val store = MemoryStore(null)
        val result = RefreshEngine(store, { _ -> throw IOException("offline") }, { 200L }).refresh()
        assertTrue(result.failed)
        assertNull(result.snapshot)
        assertThrows(CancellationException::class.java, {
            runBlocking(block = { RefreshEngine(store, { _ -> throw CancellationException() }, { 200L }).refresh() })
        })
    })

    @Test
    fun staleBoundaryAndClockRollbackAreDetected() {
        assertFalse(RefreshEngine.isStale(old, old.updatedAt + RefreshEngine.DAY_MILLIS - 1))
        assertTrue(RefreshEngine.isStale(old, old.updatedAt + RefreshEngine.DAY_MILLIS))
        assertTrue(RefreshEngine.isStale(old, 99))
    }

    private class MemoryStore(var value: PlaylistSnapshot?, private val failWrite: Boolean = false) : PlaylistStore {
        override fun read(): PlaylistSnapshot? {
            return value
        }

        override fun write(snapshot: PlaylistSnapshot) {
            if (failWrite) {
                throw IOException("disk full")
            }
            value = snapshot
        }
    }
}
