package cn.hdfk7.android.iptv.feature.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import cn.hdfk7.android.iptv.core.data.network.ProxyHttpClient
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.BuildConfig
import cn.hdfk7.android.iptv.core.model.ChannelAggregation
import cn.hdfk7.android.iptv.core.model.ChannelFocusMemory
import cn.hdfk7.android.iptv.app.IptvApplication
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.model.LibraryException
import cn.hdfk7.android.iptv.core.data.repository.RefreshEngine
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.LineQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val INFORMATION_TIMEOUT_MS = 6000L
    }

    private val app = application as IptvApplication
    private val repository = app.playlistRepository
    private val audio = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    internal val channelFocusMemory = ChannelFocusMemory()
    private val mutableState = MutableStateFlow(PlayerState(fixedVolume = audio.isVolumeFixed))
    val state = mutableState.asStateFlow()
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    private var httpClient: okhttp3.OkHttpClient? = null
    @Volatile private var lineAddress: String? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeChannel: Channel? = null
    private var streamIndex = 0
    private val lineRanking = PlaybackLineRanking()
    private val attemptedLines = mutableSetOf<String>()
    private var rankingSnapshot: Long? = null
    private var wantsPlayback = false
    private var timeout: Job? = null
    private var fallback: Job? = null
    private var generation = 0
    private var refreshJob: Job? = null
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
                    clearSelection()
                    if (catalog.channels.isEmpty() && state.value.fullscreen) {
                        exitFullscreen()
                    }
                }
                val playing = activeChannel
                val current = catalog.channels.firstOrNull(predicate = { channel -> channel.id == playing?.id })
                val refreshed = catalog.updatedAt != rankingSnapshot
                if (refreshed) {
                    lineRanking.reset()
                    rankingSnapshot = catalog.updatedAt
                }
                if (playing != null && current != null) {
                    activeChannel = playing.copy(name = current.name, sourceName = current.sourceName)
                    if (refreshed || playing.streams.toSet() != current.streams.toSet()) {
                        val playingUrl = playing.streams.getOrNull(streamIndex)
                        activeChannel = current
                        attemptedLines.retainAll(current.streams.toSet())
                        if (state.value.playback == PlaybackStatus.ERROR) {
                            streamIndex = current.streams.size
                            publishLineRanking()
                        } else if (playingUrl != null && playingUrl in current.streams) {
                            streamIndex = current.streams.indexOf(playingUrl)
                            publishLineRanking()
                            player?.let(block = { currentPlayer ->
                                timeout?.cancel()
                                watchLineHealth(currentPlayer)
                            })
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
                    sources = catalog.library.sources,
                    activeSourceId = catalog.library.activeSourceId,
                    favoriteIds = favoriteIds,
                    selectedName = activeChannel?.name,
                    selectedSourceName = activeChannel?.sourceName,
                    updatedAt = snapshot,
                    stale = snapshot != null && System.currentTimeMillis() - snapshot >= RefreshEngine.DAY_MILLIS,
                    refreshing = catalog.refreshing,
                    refreshFailed = catalog.refreshFailed,
                    message = if (catalog.storageFailed) R.string.storage_error else state.value.message
                )
                if (catalog.loaded) {
                    catalogToken = token
                }
                if (catalog.channels.isNotEmpty() && activeChannel == null) {
                    select(channel = catalog.channels.first())
                }
                if (switched) {
                    refreshJob?.cancel()
                    refresh()
                }
            })
        })
        refresh()
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
        if (refreshJob?.isActive == true) {
            return
        }
        refresh()
    }

    fun enterFullscreen() {
        if (activeChannel == null) {
            return
        }
        mutableState.value = state.value.copy(fullscreen = true)
        setPlayback(true)
    }

    fun exitFullscreen() {
        infoTimer?.cancel()
        mutableState.value = state.value.copy(fullscreen = false, infoVisible = false)
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

    fun toggleFavorite(channel: Channel) {
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
        val channel = activeChannel ?: return
        toggleFavorite(channel)
    }

    private fun stopStream() {
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        wantsPlayback = false
        lineAddress = null
        player?.stop()
        player?.clearMediaItems()
        mutableState.value = state.value.copy(playback = PlaybackStatus.IDLE, line = 0, lineCount = 0, lines = emptyList())
    }

    private fun clearSelection() {
        stopStream()
        activeChannel = null
        mutableState.value = state.value.copy(selectedId = null, selectedName = null, selectedSourceName = null)
    }

    fun applyProxy() {
        stop()
        streamIndex = 0
        start()
        refreshJob?.cancel()
        refresh()
    }

    fun start() {
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
                if (error != null && error.dataSpec.uri.toString() == lineAddress &&
                    (error.responseCode == 401 || error.responseCode == 403 || error.responseCode == 404 || error.responseCode == 410)) {
                    return C.TIME_UNSET
                }
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val created = ExoPlayer.Builder(getApplication())
            .setMediaSourceFactory(DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(loadPolicy))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // Preserve explicit idle/error state during source changes.
                    }
                    Player.STATE_BUFFERING -> {
                        mutableState.value = state.value.copy(playback = PlaybackStatus.LOADING)
                    }
                    Player.STATE_READY -> {
                        updatePlayingState()
                    }
                    Player.STATE_ENDED -> {
                        nextLine()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (created.playbackState == Player.STATE_READY) {
                    updatePlayingState()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                nextLine()
            }
        })
        player = created
        if (activeChannel != null && wantsPlayback) {
            playLine()
        }
    }

    fun stop() {
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        lineAddress = null
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
        val sorted = lineRanking.lines(channel.id, channel.streams)
        selectLine(channel.copy(streams = sorted.map(transform = { line -> line.url })), 0, playing)
    }

    private fun selectLine(channel: Channel, index: Int, playing: Boolean = true) {
        generation += 1
        fallback?.cancel()
        timeout?.cancel()
        activeChannel = channel
        streamIndex = index
        attemptedLines.clear()
        wantsPlayback = playing
        mutableState.value = state.value.copy(selectedId = channel.id, selectedName = channel.name, selectedSourceName = channel.sourceName)
        playLine()
        showInformation()
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
        channelFocusMemory.remember(state.value.activeSourceId, channel.id)
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

    fun retry() {
        val current = state.value.channels.firstOrNull(predicate = { channel -> channel.id == state.value.selectedId }) ?: activeChannel
        if (current != null) {
            select(current)
        }
    }

    fun togglePlayback() {
        if (activeChannel == null || state.value.playback == PlaybackStatus.ERROR) {
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
        if (playing && player?.currentMediaItem == null) {
            wantsPlayback = true
            playLine()
            return
        }
        wantsPlayback = playing
        player?.playWhenReady = playing
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
        publishLineRanking()
        val channel = activeChannel ?: return
        val current = player ?: return
        timeout?.cancel()
        val limit = channel.streams.size
        if (streamIndex >= limit) {
            lineAddress = null
            current.stop()
            mutableState.value = state.value.copy(playback = PlaybackStatus.ERROR)
            return
        }
        mutableState.value = state.value.copy(playback = PlaybackStatus.LOADING)
        if (current.currentMediaItem != null) {
            // Reset the decoder before switching between independent live streams.
            current.stop()
        }
        val address = channel.streams[streamIndex]
        lineAddress = address
        current.setMediaItem(MediaItem.fromUri(address))
        current.prepare()
        current.playWhenReady = wantsPlayback
        watchLineHealth(current)
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
        attemptedLines.add(url)
        lineRanking.failed(channel.id, url)
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

    private fun watchLineHealth(current: ExoPlayer) {
        val limit = BuildConfig.STREAM_PROBE_TIMEOUT_MS.toLong()
        val channel = activeChannel ?: return
        val url = channel.streams.getOrNull(streamIndex) ?: return
        timeout = viewModelScope.launch(block = {
            val startedAt = SystemClock.elapsedRealtime()
            var lastSample = startedAt
            var startupMs = 0L
            var playedMs = 0L
            val observation = LineObservationWindow()
            var bufferingMs = 0L
            var lastRenderedFrames: Int? = null
            var stalledMs = 0L
            var stableMs = 0L
            while (true) {
                delay(minOf(1000L, limit.coerceAtLeast(1L)))
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastSample
                lastSample = now
                val counters = current.videoDecoderCounters
                counters?.ensureUpdated()
                val renderedFrames = counters?.renderedOutputBufferCount
                val videoExpected = current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                val stalled = current.playbackState == Player.STATE_BUFFERING ||
                    (current.isPlaying && videoExpected && (renderedFrames == null || renderedFrames == lastRenderedFrames))
                lastRenderedFrames = renderedFrames

                if (wantsPlayback) {
                    if (current.isPlaying && !stalled && (!videoExpected || (renderedFrames ?: 0) > 0)) {
                        if (startupMs == 0L) {
                            startupMs = now - startedAt
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
                    lineRanking.record(channel.id, url, LineQuality(
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
        mutableState.value = state.value.copy(lines = lines, line = if (index >= 0) index + 1 else 0, lineCount = lines.size)
    }

    private fun updatePlayingState() {
        val status = if (player?.isPlaying == true) {
            PlaybackStatus.PLAYING
        } else {
            PlaybackStatus.PAUSED
        }
        mutableState.value = state.value.copy(playback = status)
    }

    override fun onCleared() {
        stop()
    }

}

