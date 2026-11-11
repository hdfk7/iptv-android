package cn.hdfk7.android.iptv.core.data.network

import cn.hdfk7.android.iptv.core.model.ChannelCategory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaylistParserTests {
    @Test
    fun usesValidResponseTimesOnlyWhenRequested(): Unit {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 response-time="120ms",CCTV1
            slow.m3u8
            #EXTINF:-1 response-time="0ms",CCTV1
            unknown.m3u8
            #EXTINF:-1 response-time="45ms",CCTV-1
            fast.m3u8
            #EXTINF:-1 response-time="45ms",CCTV1
            tied.m3u8
            #EXTINF:-1 response-time="1ms",CCTV1
            slow.m3u8
        """.trimIndent()
        val url = "https://example.org/list.m3u"
        val parser = PlaylistParser()
        val original = parser.parse(playlist, url).single().streams
        val measured = parser.parse(playlist, url, readResponseTime = true).single()
        assertEquals(listOf("slow", "unknown", "fast", "tied"), original.map(transform = { line -> line.substringAfterLast('/').substringBefore('.') }))
        assertEquals(original, measured.streams)
        assertEquals(listOf(original[2], original[3], original[0], original[1]), measured.streamsByResponseTime())
    }

    private val parser = PlaylistParser()
    private val base = "https://example.org/catalog/list.m3u8"

    @Test
    fun interlacedCctvLineMergesIntoItsChannel() {
        val channel = parser.parse("""
            #EXTM3U
            #EXTINF:-1 group-title="其他频道",CCTV-9 (576i)
            https://example.org/interlaced
            #EXTINF:-1,CCTV9
            https://example.org/other
        """.trimIndent(), base).single()

        assertEquals("CCTV9", channel.name)
        assertEquals(ChannelCategory.CCTV, ChannelCategory.classify(channel))
        assertEquals(2, channel.streams.size)
    }

    @Test
    fun missingOrUnrecognizedGroupsUseExplicitClassification() {
        val channels = parser.parse("""
            #EXTM3U
            #EXTINF:-1,CCTV-4K (1080p)
            https://example.org/4k
            #EXTINF:-1 group-title="备用频道",Local
            https://example.org/local
            #EXTINF:-1,湖南卫视
            https://example.org/hunan
            #EXTINF:-1 group-title="港澳台频道",Local HK
            https://example.org/hk
        """.trimIndent(), base)

        assertEquals(listOf(ChannelCategory.CCTV, ChannelCategory.OTHER, ChannelCategory.SATELLITE, ChannelCategory.GREATER_CHINA),
            channels.map(transform = ChannelCategory::classify))
        assertEquals("备用频道", channels[1].group)
    }

    @Test
    fun mergedProgramKeepsFirstNonEmptyCategory() {
        val channels = PlaylistParser().parse("""
            #EXTM3U
            #EXTINF:-1,CCTV-17 (1080p)
            https://example.org/first
            #EXTINF:-1 group-title="央视频道",CCTV17
            https://example.org/second
        """.trimIndent(), "https://example.org/list.m3u")
        assertEquals(1, channels.size)
        assertEquals("央视频道", channels.single().group)
        assertEquals(2, channels.single().streams.size)
    }

    @Test
    fun acceptsRepositoryHeaderAttributesAndMergesProgramsAcrossGroups() {
        val text = "#EXTM3U x-tvg-url=\"https://example.org/epg.xml\"\n#EXTINF:-1 group-title=\"A\",CCTV1\nhttps://example.org/a\n#EXTINF:-1 group-title=\"B\",CCTV-1综合HD\nhttps://example.org/b"
        val channels = parser.parse(text, base)
        assertEquals(1, channels.size)
        assertEquals(2, channels.single().streams.size)
    }

    @Test
    fun mergesAlternativeLinesAndRemovesExactDuplicates() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="News" group-title="TV",News
            https://example.org/a.m3u8?token=x,y
            #EXTINF:-1 group-title="TV",News
            ../b.m3u8
            #EXTINF:-1 group-title="TV",News
            ../b.m3u8
        """.trimIndent()
        val channels = parser.parse(text, base)
        assertEquals(1, channels.size)
        assertEquals(listOf("https://example.org/a.m3u8?token=x,y", "https://example.org/b.m3u8"), channels[0].streams)
        assertEquals("TV", channels[0].group)
    }

    @Test
    fun acceptsBomCrLfQuotedCommaAndDisplayNameComma() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"News, live\",Channel, HD\r\nhttps://example.org/live\r\n"
        val channel = parser.parse(text, base).single()
        assertEquals("News, live", channel.group)
        assertEquals("Channel, HD", channel.name)
    }

    @Test
    fun ignoresCommentsOrphansUnsafeSchemesAndMissingUrls() {
        val text = """
            #EXTM3U
            https://example.org/orphan
            #EXTINF:-1,Invalid
            file:///etc/passwd
            #EXTINF:-1,Credential
            https://user:password@example.org/live
            #EXTINF:-1,Missing
            #EXTINF:-1,Valid
            # comment
            https://example.org/live
            #EXTINF:-1,Dangling
        """.trimIndent()
        assertEquals(listOf("Valid"), parser.parse(text, base).map(transform = { channel -> channel.name }))
    }

    @Test
    fun rejectsHtmlAndHlsManifestsInsteadOfTreatingSegmentsAsChannels() {
        assertThrows(IllegalArgumentException::class.java, { parser.parse("<html>error</html>", base) })
        assertThrows(IllegalArgumentException::class.java, { parser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,segment\na.ts", base) })
    }

    @Test
    fun mergesSameProgramAcrossDifferentGroups() {
        val text = """
            #EXTM3U
            #EXTINF:-1,News
            https://example.org/a
            #EXTINF:-1 group-title="B",News
            https://example.org/b
            #EXTINF:-1,News
            https://example.org/c
            #EXTINF:-1 group-title="B",News
            https://example.org/a
        """.trimIndent()
        val channel = parser.parse(text, base).single()
        assertEquals("B", channel.group)
        assertEquals(listOf("https://example.org/a", "https://example.org/b", "https://example.org/c"), channel.streams)
    }
}
