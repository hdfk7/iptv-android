package cn.hdfk7.android.iptv.feature.player

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import cn.hdfk7.android.iptv.BuildConfig
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.app.IptvApplication
import cn.hdfk7.android.iptv.core.data.local.GuideCache
import cn.hdfk7.android.iptv.core.data.local.LineHistory
import cn.hdfk7.android.iptv.core.data.local.StandbyFrameCache
import cn.hdfk7.android.iptv.core.data.network.ProxyHttpClient
import cn.hdfk7.android.iptv.core.data.repository.LibraryRules
import cn.hdfk7.android.iptv.core.data.repository.RefreshEngine
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ChannelAggregation
import cn.hdfk7.android.iptv.core.model.LibraryException
import cn.hdfk7.android.iptv.core.model.LineQuality
import cn.hdfk7.android.iptv.core.model.RecentChannels
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val INFORMATION_TIMEOUT_MS = 6000L
    }

    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    private val audio = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableState = MutableStateFlow(PlayerState(fixedVolume = audio.isVolumeFixed))
    val state = mutableState.asStateFlow()
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    private var httpClient: okhttp3.OkHttpClient? = null
    @Volatile private var attempt: PlaybackAttempt? = null
    private var attemptNumber = 0L
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeChannel: Channel? = null
    private var streamIndex = 0
    private val viewing = app.viewingPreferences
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val history = LineHistory(application.getSharedPreferences("line_history", Context.MODE_PRIVATE))
    private var historyScope = ""
    private val lineRanking = PlaybackLineRanking(
        readHistory = { program, url -> history.read(history.key(historyScope, program, url)) },
        writeHistory = { program, url, quality -> history.write(history.key(historyScope, program, url), quality) }
    )
    private var resumePending = viewing.getBoolean("resume", false)
    private var autoPlayPending = true
    var resumeEnabled: Boolean by mutableStateOf(resumePending)
        private set
    var fillVideoScreen: Boolean by mutableStateOf(viewing.getBoolean("fill_video_screen", false))
        private set

    fun toggleFillVideoScreen(): Unit {
        fillVideoScreen = !fillVideoScreen
        viewing.edit().putBoolean("fill_video_screen", fillVideoScreen).apply()
    }

    fun toggleResume(): Unit {
        resumeEnabled = !resumeEnabled
        viewing.edit().putBoolean("resume", resumeEnabled).apply()
        if (!resumeEnabled && resumePending) {
            resumePending = false
        }
    }

    private fun updateHistoryScope(): Unit {
        val network = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val transport = when {
            network?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            network?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            network?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            else -> "unknown"
        }
        val proxy = app.proxySettings.current?.takeIf(predicate = { settings -> settings.enabled })
        historyScope = "$transport:${proxy?.host}:${proxy?.port}"
    }
    private val attemptedLines = mutableSetOf<String>()
    private var rankingSnapshot: Long? = null
    private var wantsPlayback = false
    private var previewing = false
    internal var previewFrameRequest: PreviewFrameRequest? by mutableStateOf(null)
        private set
    private val standbyFrames = StandbyFrameCache(File(application.cacheDir, "standby-frames"))
    private var scanningPreview = false
    private val playbackAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    private var timeout: Job? = null
    private var fallback: Job? = null
    private var generation = 0
    private var refreshJob: Job? = null
    private val guide = PlayerProgrammeGuide(
        scope = viewModelScope,
        cache = GuideCache(File(application.cacheDir, "programme-guides")),
        download = app.downloader::downloadGuide,
        publish = { snapshot ->
            mutableState.value = state.value.copy(programmePreviews = snapshot.previews, programmeSchedules = snapshot.schedules)
        }
    )
    private var guideForeground = false
    private var tuneStartedAt = 0L
    private var fallbackStartedAt = 0L
    internal var moreMenuRequested: Boolean by mutableStateOf(false)
        private set

    fun requestMoreMenu(): Unit {
        moreMenuRequested = true
        showInformation()
    }

    internal fun consumeMoreMenuRequest(): Unit {
        moreMenuRequested = false
    }

    private var infoTimer: Job? = null
    private var informationHeld = false
    private var informationDeadline = 0L
    private var catalogToken: String? = null

    init {
        viewModelScope.launch(block = {
            var previousFavorites: List<Channel> = emptyList()
            var favoriteIds: Set<String> = emptySet()
            repository.state.collect(collector = { catalog ->
                val source = catalog.library.sources.firstOrNull(predicate = { entry -> entry.id == catalog.library.activeSourceId })
                val token = "${catalog.library.activeSourceId}:${source?.url.orEmpty()}"
                val switched = catalog.loaded && catalogToken != null && catalogToken != token
                val selectionRemoved = catalog.loaded && activeChannel != null &&
                    catalog.channels.none(predicate = { channel -> channel.id == activeChannel?.id })
                if (switched || selectionRemoved) {
                    resumePending = false
                    autoPlayPending = switched || wantsPlayback
                    clearSelection()
                }
                val playing = activeChannel
                val current = catalog.channels.firstOrNull(predicate = { channel -> channel.id == playing?.id })
                val refreshed = catalog.updatedAt != rankingSnapshot
                if (refreshed) {
                    rankingSnapshot = catalog.updatedAt
                }
                if (playing != null && current != null) {
                    activeChannel = playing.copy(name = current.name, sourceName = current.sourceName)
                    if (refreshed || playing.responseTimesMs != current.responseTimesMs ||
                        playing.streams.toSet() != current.streams.toSet()) {
                        val playingUrl = playing.streams.getOrNull(streamIndex)
                        val candidates = candidateStreams(current)
                        activeChannel = current.copy(streams = candidates)
                        attemptedLines.retainAll(current.streams.toSet())
                        if (state.value.playback == PlaybackStatus.ERROR) {
                            streamIndex = current.streams.size
                            publishLineRanking()
                        } else if (playingUrl != null && playingUrl in current.streams) {
                            streamIndex = candidates.indexOf(playingUrl)
                            publishLineRanking()
                        } else {
                            selectProgram(current, wantsPlayback)
                        }
                    }
                }
                val snapshot = catalog.updatedAt
                val favorites = catalog.library.favorites
                if (favorites != previousFavorites) {
                    favoriteIds = (favorites + ChannelAggregation.merge(favorites, SourceLibrary.FAVORITES_ID))
                        .map(transform = { channel -> channel.id }).toSet()
                    previousFavorites = favorites
                }
                mutableState.value = state.value.copy(
                    channels = catalog.channels,
                    recentChannelIds = if (catalog.library.activeSourceId == state.value.activeSourceId && catalogToken != null) {
                        state.value.recentChannelIds
                    } else {
                        readRecent(catalog.library.activeSourceId)
                    },
                    sources = catalog.library.sources,
                    activeSourceId = catalog.library.activeSourceId,
                    favoriteIds = favoriteIds,
                    selectedName = activeChannel?.name,
                    selectedSourceName = activeChannel?.sourceName,
                    updatedAt = snapshot,
                    stale = snapshot != null && RefreshEngine.isStale(snapshot, System.currentTimeMillis()),
                    refreshing = catalog.refreshing,
                    refreshFailed = catalog.refreshFailed,
                    message = if (catalog.storageFailed) R.string.storage_error else state.value.message
                )
                if (guideForeground) {
                    guide.show(catalog.channels)
                }
                if (catalog.loaded) {
                    catalogToken = token
                }
                if (catalog.loaded && catalog.channels.isNotEmpty() && activeChannel == null) {
                    val remembered = if (resumePending) {
                        viewing.getString("channel:${catalog.library.activeSourceId}", null)
                    } else {
                        null
                    }
                    val restored = catalog.channels.firstOrNull(predicate = { channel -> channel.id == remembered })
                    val playing = autoPlayPending
                    previewing = !playing
                    selectProgram(restored ?: catalog.channels.first(), playing)
                }
                if (switched) {
                    refreshJob?.cancel()
                    refresh()
                }
            })
        })
        refresh()
    }

    private fun readRecent(sourceId: String): List<String> {
        val stored = viewing.getString("recent:$sourceId", null) ?: return emptyList()
        return runCatching(block = {
            val array = JSONArray(stored)
            (0 until array.length()).map(transform = array::getString)
        }).getOrDefault(emptyList())
    }

    private fun recordRecent(): Unit {
        val channel = activeChannel ?: return
        val current = state.value
        if (!wantsPlayback || attempt?.address == null || current.activeSourceId != channel.sourceId) {
            return
        }
        val updated = RecentChannels.record(current.recentChannelIds, channel.id)
        if (updated != current.recentChannelIds) {
            viewing.edit().putString("recent:${channel.sourceId}", JSONArray(updated).toString()).apply()
            mutableState.value = current.copy(recentChannelIds = updated)
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = viewModelScope.launch(block = {
            try {
                repository.refresh()
            } catch (exception: LibraryException) {
                mutableState.value = state.value.copy(message = R.string.storage_error)
            }
        })
    }

    fun refreshManually() {
        if (guideForeground) {
            guide.show(state.value.channels, force = true)
        }
        if (refreshJob?.isActive == true) {
            return
        }
        refresh()
    }

    fun showInformation() {
        if (!state.value.infoVisible) {
            mutableState.value = state.value.copy(infoVisible = true)
        }
        informationDeadline = SystemClock.elapsedRealtime() + INFORMATION_TIMEOUT_MS
        if (informationHeld || infoTimer?.isActive == true) {
            return
        }
        infoTimer = viewModelScope.launch(block = {
            var remaining = informationDeadline - SystemClock.elapsedRealtime()
            while (remaining > 0) {
                delay(remaining)
                remaining = informationDeadline - SystemClock.elapsedRealtime()
            }
            mutableState.value = state.value.copy(infoVisible = false)
        })
    }

    fun holdInformation(held: Boolean) {
        if (informationHeld == held) {
            return
        }
        informationHeld = held
        if (held) {
            infoTimer?.cancel()
        } else if (state.value.infoVisible) {
            showInformation()
        }
    }

    fun hideInformation() {
        infoTimer?.cancel()
        mutableState.value = state.value.copy(infoVisible = false)
    }

    private fun toggleFavorite(channel: Channel) {
        viewModelScope.launch(block = {
            try {
                repository.toggleFavorite(channel)
                mutableState.value = state.value.copy(message = null)
            } catch (exception: LibraryException) {
                mutableState.value = state.value.copy(message = R.string.storage_error)
            }
        })
    }

    fun toggleCurrentFavorite() {
        val id = activeChannel?.id ?: return
        val channel = state.value.channels.firstOrNull(predicate = { entry -> entry.id == id }) ?: return
        toggleFavorite(channel)
    }

    private fun stopStream() {
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        wantsPlayback = false
        previewing = false
        clearPreviewScan()
        attempt = null
        player?.stop()
        player?.clearMediaItems()
        mutableState.value = state.value.copy(playback = PlaybackStatus.IDLE, previewFrameVisible = false, cachedPreviewFrame = null,
            line = 0, lineCount = 0)
    }

    private fun clearSelection() {
        stopStream()
        activeChannel = null
        mutableState.value = state.value.copy(selectedId = null, selectedName = null, selectedSourceName = null)
    }

    fun applyProxy() {
        stop()
        updateHistoryScope()
        val channel = activeChannel
        if (channel != null) {
            activeChannel = rankedChannel(channel)
            attemptedLines.clear()
        }
        streamIndex = 0
        start()
        refreshJob?.cancel()
        refresh()
    }

    fun start() {
        if (!guideForeground) {
            guideForeground = true
            guide.show(state.value.channels, force = true)
        }
        if (player != null) {
            return
        }
        val settings = app.proxySettings.current
        if (settings == null) {
            mutableState.value = state.value.copy(message = R.string.proxy_read_error)
            return
        }
        if (state.value.message == R.string.proxy_read_error) {
            mutableState.value = state.value.copy(message = null)
        }
        val client = ProxyHttpClient.create(settings, BuildConfig.STREAM_CONNECT_TIMEOUT_MS, BuildConfig.STREAM_READ_TIMEOUT_MS)
        httpClient = client
        val http = OkHttpDataSource.Factory(client)
        val loadPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val error = loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException
                // Fail over immediately for an unavailable line URL. Segment and transient failures keep Media3's retry policy.
                if (error != null && error.dataSpec.uri.toString() == attempt?.address &&
                    (error.responseCode == 401 || error.responseCode == 403 || error.responseCode == 404 || error.responseCode == 410)) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val created = ExoPlayer.Builder(getApplication())
            .setMediaSourceFactory(DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(loadPolicy))
            .setAudioAttributes(playbackAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events): Unit {
                val active = attempt ?: return
                if (!ownsAttempt(player, active)) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (active.tracksAt == null && !player.currentTracks.isEmpty) {
                    active.tracksAt = now
                }
                if (player.playbackState == Player.STATE_READY && active.readyAt == null) {
                    active.readyAt = now
                }
                if (player.playerError != null || player.playbackState == Player.STATE_ENDED ||
                    (!previewing && isCompletedRecording(player))) {
                    nextLine()
                    return
                }
                publishPlaybackStatus(player, active)
            }
        })
        created.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long): Unit {
                val active = attempt ?: return
                val timeline = eventTime.timeline
                if (!ownsAttempt(created, active) || active.firstFrameAt != null ||
                    eventTime.windowIndex !in 0 until timeline.windowCount ||
                    timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId != active.id ||
                    (!previewing && isCompletedRecording(created))) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                active.firstFrameAt = now
                activeChannel?.let(block = { channel ->
                    previewFrameRequest = PreviewFrameRequest(previewKey(channel), previewing)
                })
                if (previewing) {
                    return
                }
                publishPlaybackStatus(created, active)
                if (BuildConfig.APP_DEBUG_SWITCH) {
                    val tracksMs = active.tracksAt?.let(block = { time -> time - active.startedAt }) ?: -1
                    val readyMs = active.readyAt?.let(block = { time -> time - active.startedAt }) ?: -1
                    Log.d("IptvPlayback", "first_frame_ms=${(now - active.startedAt).coerceAtLeast(1L)} " +
                        "tune_ms=${now - tuneStartedAt} recovery_ms=${if (fallbackStartedAt > 0L) now - fallbackStartedAt else 0L} " +
                        "first_line=${attemptedLines.isEmpty()} tune_id=$generation tracks_ms=$tracksMs ready_ms=$readyMs")
                }
                fallbackStartedAt = 0L
            }
        })
        player = created
        if (activeChannel != null && (wantsPlayback || previewing)) {
            val oldScope = historyScope
            updateHistoryScope()
            val channel = activeChannel
            if (oldScope != historyScope && channel != null) {
                activeChannel = rankedChannel(channel)
                streamIndex = 0
                attemptedLines.clear()
            }
            tuneStartedAt = SystemClock.elapsedRealtime()
            fallbackStartedAt = 0L
            playLine()
        }
    }

    fun stop() {
        guideForeground = false
        guide.stop()
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        attempt = null
        clearPreviewScan()
        mutableState.value = state.value.copy(previewFrameVisible = false)
        val current = player
        player = null
        current?.release()
        val client = httpClient
        httpClient = null
        if (client != null) {
            cleanupScope.launch(block = {
                runCatching(block = { client.connectionPool.evictAll() })
                client.dispatcher.executorService.shutdown()
            })
        }
    }

    fun select(channel: Channel) {
        val current = state.value.channels.firstOrNull(predicate = { candidate -> candidate.id == channel.id }) ?: return
        selectProgram(current, playing = true)
    }

    private fun selectProgram(channel: Channel, playing: Boolean): Unit {
        updateHistoryScope()
        resumePending = false
        autoPlayPending = false
        if (playing) {
            rememberChannel(channel)
        }
        selectLine(rankedChannel(channel), 0, playing)
    }

    private fun rememberChannel(channel: Channel): Unit {
        val preference = "channel:${channel.sourceId}"
        if (viewing.getString(preference, null) != channel.id) {
            viewing.edit().putString(preference, channel.id).apply()
        }
    }

    private fun rankedChannel(channel: Channel): Channel {
        val ranked = lineRanking.lines(channel.id, candidateStreams(channel))
        return channel.copy(streams = ranked.map(transform = { line -> line.url }))
    }

    private fun candidateStreams(channel: Channel): List<String> {
        val domestic = channel.responseTimesMs.isNotEmpty() && state.value.sources.any(predicate = { source ->
            source.id == channel.sourceId && LibraryRules.isDomestic(source)
        })
        return if (domestic) channel.streamsByResponseTime() else channel.streams
    }

    private fun selectLine(channel: Channel, index: Int, playing: Boolean = true) {
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        activeChannel = channel
        tuneStartedAt = SystemClock.elapsedRealtime()
        fallbackStartedAt = 0L
        streamIndex = index
        attemptedLines.clear()
        wantsPlayback = playing
        if (playing) {
            previewing = false
        }
        mutableState.value = state.value.copy(selectedId = channel.id, selectedName = channel.name,
            selectedSourceName = channel.sourceName, cachedPreviewFrame = null)
        if (previewing) {
            val key = previewKey(channel)
            val expectedGeneration = generation
            viewModelScope.launch(block = {
                val cached = withContext(Dispatchers.IO, block = { standbyFrames.read(key) })
                val current = activeChannel
                if (cached != null && generation == expectedGeneration && previewing && current != null && previewKey(current) == key &&
                    !state.value.previewFrameVisible) {
                    mutableState.value = state.value.copy(cachedPreviewFrame = cached)
                }
            })
        }
        playLine()
        if (!previewing) {
            showInformation()
        }
    }

    fun changeChannel(direction: Int) {
        val channels = state.value.channels
        if (channels.isEmpty()) {
            return
        }
        val current = channels.indexOfFirst(predicate = { channel -> channel.id == state.value.selectedId })
        val next = if (current < 0) {
            0
        } else {
            Math.floorMod(current + direction, channels.size)
        }
        val channel = channels[next]
        select(channel)
    }

    fun changeStreamLine(direction: Int) {
        val channel = activeChannel ?: return
        if (channel.streams.size > 1) {
            val index = if (streamIndex !in channel.streams.indices) {
                if (direction < 0) channel.streams.lastIndex else 0
            } else {
                Math.floorMod(streamIndex + direction, channel.streams.size)
            }
            selectLine(channel, index)
        }
    }

    fun retryCurrentLine() {
        val channel = activeChannel ?: return
        val index = state.value.line - 1
        if (index in channel.streams.indices) {
            selectLine(channel, index)
        } else {
            retry()
        }
    }

    private fun retry() {
        val current = state.value.channels.firstOrNull(predicate = { channel -> channel.id == state.value.selectedId }) ?: activeChannel
        if (current != null) {
            select(current)
        }
    }

    fun togglePlayback() {
        if (previewing || activeChannel == null || state.value.playback == PlaybackStatus.ERROR) {
            setPlayback(true)
            return
        }
        val current = player ?: return
        setPlayback(current.currentMediaItem == null || !current.playWhenReady)
    }

    fun setPlayback(playing: Boolean) {
        showInformation()
        if (playing && activeChannel == null) {
            val first = state.value.channels.firstOrNull() ?: return
            select(channel = first)
            return
        }
        if (playing && state.value.playback == PlaybackStatus.ERROR) {
            retry()
            return
        }
        if (playing && previewing) {
            clearPreviewScan()
            previewing = false
            activeChannel?.let(block = ::rememberChannel)
            val current = player
            if (attempt?.address == null || current?.currentMediaItem == null) {
                activeChannel?.let(block = ::select)
                return
            }
            mutableState.value = state.value.copy(
                playback = if (current.playbackState == Player.STATE_READY) PlaybackStatus.PAUSED else PlaybackStatus.LOADING,
                previewFrameVisible = false,
                cachedPreviewFrame = null
            )
        }
        if (playing && player?.currentMediaItem == null) {
            wantsPlayback = true
            playLine()
            return
        }
        wantsPlayback = playing
        player?.let(block = { current ->
            current.playWhenReady = playing
            attempt?.let(block = { active -> publishPlaybackStatus(current, active) })
        })
    }

    fun changeVolume(direction: Int) {
        if (audio.isVolumeFixed) {
            mutableState.value = state.value.copy(fixedVolume = true)
            return
        }
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (exception: SecurityException) {
            mutableState.value = state.value.copy(fixedVolume = true)
        }
    }

    private fun playLine() {
        val channel = activeChannel ?: return
        val current = player ?: return
        attempt = null
        clearPreviewScan()
        timeout?.cancel()
        val limit = channel.streams.size
        if (streamIndex >= limit) {
            current.stop()
            mutableState.value = state.value.copy(
                playback = if (previewing) PlaybackStatus.IDLE else PlaybackStatus.ERROR,
                previewFrameVisible = false, line = 0, lineCount = limit)
            if (BuildConfig.APP_DEBUG_SWITCH) {
                Log.d("IptvPlayback", "tune_failed_id=$generation")
            }
            return
        }
        mutableState.value = state.value.copy(
            playback = if (previewing) PlaybackStatus.IDLE else PlaybackStatus.LOADING,
            previewFrameVisible = false, line = streamIndex + 1, lineCount = limit)
        if (current.currentMediaItem != null) {
            // Reset the decoder before switching between independent live streams.
            current.stop()
        }
        val active = PlaybackAttempt((++attemptNumber).toString(), channel.streams[streamIndex], SystemClock.elapsedRealtime())
        attempt = active
        current.setMediaItem(MediaItem.Builder().setMediaId(active.id).setUri(active.address).build())
        current.playWhenReady = wantsPlayback
        current.prepare()
        watchLineHealth(current, active)
    }

    private fun previewKey(channel: Channel): String = "${catalogToken.orEmpty()}\n${channel.id}"

    internal fun skipBlackPreviewFrame(request: PreviewFrameRequest): Unit {
        if (request !== previewFrameRequest || scanningPreview) {
            return
        }
        val current = player ?: return
        scanningPreview = true
        current.volume = 0f
        current.setAudioAttributes(playbackAudioAttributes, false)
        current.playWhenReady = true
    }

    internal fun finishPreviewFrame(request: PreviewFrameRequest, frame: Bitmap?): Unit {
        if (request !== previewFrameRequest) {
            return
        }
        clearPreviewScan()
        if (request.standby && frame != null) {
            mutableState.value = state.value.copy(previewFrameVisible = true, cachedPreviewFrame = null)
        }
        if (frame != null) {
            viewModelScope.launch(context = Dispatchers.IO, block = { standbyFrames.write(request.key, frame) })
        }
    }

    private fun clearPreviewScan(): Unit {
        previewFrameRequest = null
        if (scanningPreview) {
            scanningPreview = false
            player?.let(block = { current ->
                current.playWhenReady = false
                current.setAudioAttributes(playbackAudioAttributes, true)
                current.volume = 1f
            })
        }
    }

    private fun nextLine() {
        if (player == null || fallback?.isActive == true) {
            return
        }
        timeout?.cancel()
        val expectedGeneration = generation
        val count = activeChannel?.streams?.size ?: return
        if (count == 0 || streamIndex >= count) {
            return
        }
        val channel = activeChannel ?: return
        val url = channel.streams[streamIndex]
        attempt = null
        if (fallbackStartedAt == 0L) {
            fallbackStartedAt = SystemClock.elapsedRealtime()
        }
        attemptedLines.add(url)
        if (!previewing) {
            lineRanking.failed(channel.id, url)
        }
        publishLineRanking()
        val next = activeChannel?.streams?.indexOfFirst(predicate = { candidate -> candidate !in attemptedLines }) ?: -1
        streamIndex = if (next < 0) count else next
        // Defer to avoid modifying the player inside its event dispatch.
        fallback = viewModelScope.launch(block = {
            yield()
            if (generation == expectedGeneration) {
                fallback = null
                playLine()
            }
        })
    }

    private fun watchLineHealth(current: ExoPlayer, active: PlaybackAttempt): Unit {
        val limit = BuildConfig.STREAM_PROBE_TIMEOUT_MS.toLong()
        val channel = activeChannel ?: return
        timeout = viewModelScope.launch(block = {
            var lastSample = active.startedAt
            var startupMs = 0L
            var playedMs = 0L
            val observation = LineObservationWindow()
            var bufferingMs = 0L
            var lastRenderedFrames: Int? = null
            var stalledMs = 0L
            var stableMs = 0L
            while (true) {
                delay(minOf(1000L, limit.coerceAtLeast(1L)))
                if (!ownsAttempt(current, active)) {
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastSample
                lastSample = now
                val counters = current.videoDecoderCounters
                counters?.ensureUpdated()
                val renderedFrames = counters?.renderedOutputBufferCount
                val videoExpected = current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                val audioOnly = hasReadyAudioOnly(current)
                if (active.startupTimedOut(elapsed, wantsPlayback || previewing, audioOnly, limit)) {
                    nextLine()
                    return@launch
                }
                val stalled = current.playbackState == Player.STATE_BUFFERING ||
                    (current.isPlaying && videoExpected && (renderedFrames == null || renderedFrames == lastRenderedFrames))
                lastRenderedFrames = renderedFrames

                if (wantsPlayback) {
                    if (current.isPlaying && !stalled && active.canPresent(audioOnly)) {
                        if (startupMs == 0L) {
                            startupMs = active.firstFrameAt?.minus(active.startedAt) ?: (now - active.startedAt)
                        }
                        playedMs += elapsed
                    } else if (stalled && startupMs > 0L) {
                        bufferingMs += elapsed
                    }
                }

                if (!wantsPlayback) {
                    stalledMs = 0
                    stableMs = 0
                } else if (stalled) {
                    stalledMs += elapsed
                    stableMs = 0
                    if (stalledMs >= limit) {
                        nextLine()
                        return@launch
                    }
                } else if (current.isPlaying) {
                    stableMs += elapsed
                    if (stableMs >= limit) {
                        stalledMs = 0
                    }
                }
                if (observation.advance(elapsed, hasStarted = startupMs > 0L,
                    paused = !wantsPlayback || (!current.isPlaying && !stalled))) {
                    val format = current.videoFormat
                    val size = current.videoSize
                    lineRanking.record(channel.id, active.address, LineQuality(
                        width = if (size.width > 0) size.width else (format?.width ?: 0).coerceAtLeast(0),
                        height = if (size.height > 0) size.height else (format?.height ?: 0).coerceAtLeast(0),
                        frameRate = (format?.frameRate ?: 0f).coerceAtLeast(0f),
                        startupMs = startupMs,
                        playedMs = playedMs,
                        stalledMs = bufferingMs,
                        renderedFrames = renderedFrames ?: 0,
                        droppedFrames = counters?.droppedBufferCount ?: 0
                    ))
                    publishLineRanking()
                }
            }
        })
    }

    private fun publishLineRanking(): Unit {
        val channel = activeChannel ?: return
        val url = channel.streams.getOrNull(streamIndex)
        val order = lineRanking.order(channel.id, channel.streams, url)
        val lines = order.lines
        activeChannel = channel.copy(streams = lines.map(transform = { line -> line.url }))
        val index = order.playingIndex
        if (index >= 0) {
            streamIndex = index
        }
        mutableState.value = state.value.copy(line = if (index >= 0) index + 1 else 0, lineCount = lines.size)
    }

    private fun ownsAttempt(current: Player, active: PlaybackAttempt): Boolean {
        return player === current && attempt === active && current.currentMediaItem?.mediaId == active.id
    }

    private fun isCompletedRecording(current: Player): Boolean {
        return current.playbackState == Player.STATE_READY && activeChannel?.guideRefs?.isNotEmpty() == true &&
            (current.currentManifest as? HlsManifest)?.mediaPlaylist?.hasEndTag == true
    }

    private fun hasReadyAudioOnly(current: Player): Boolean {
        val tracks = current.currentTracks
        return current.playbackState == Player.STATE_READY && tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
            tracks.groups.none(predicate = { group -> group.type == C.TRACK_TYPE_VIDEO })
    }

    private fun publishPlaybackStatus(current: Player, active: PlaybackAttempt): Unit {
        if (previewing || !ownsAttempt(current, active)) {
            return
        }
        val status = active.status(current.playbackState == Player.STATE_READY, current.isPlaying, wantsPlayback,
            hasReadyAudioOnly(current))
        if (state.value.playback != status) {
            mutableState.value = state.value.copy(playback = status)
            if (status == PlaybackStatus.PLAYING) {
                recordRecent()
            }
        }
    }

    override fun onCleared() {
        stop()
    }

}

