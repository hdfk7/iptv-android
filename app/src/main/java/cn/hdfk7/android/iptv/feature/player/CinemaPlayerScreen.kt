package cn.hdfk7.android.iptv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.data.repository.LibraryRules
import cn.hdfk7.android.iptv.core.model.RecentChannels
import cn.hdfk7.android.iptv.core.model.DomesticChannelGroup
import cn.hdfk7.android.iptv.core.model.groupDomesticChannels
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.BackdropBottom
import cn.hdfk7.android.iptv.core.ui.theme.Gold
import cn.hdfk7.android.iptv.core.ui.theme.Muted

private enum class TvPanel { Controls, Channels, More }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CinemaPlayerScreen(
    state: PlayerState,
    viewModel: PlayerViewModel,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onGuide: (String) -> Unit,
    selectedRowFocus: ChannelListFocus,
    dialogOpen: Boolean,
    television: Boolean
): Unit {
    var panel by remember(calculation = { mutableStateOf(TvPanel.Controls) })
    var returnToMore by remember(calculation = { mutableStateOf(false) })
    val openingKey = remember(calculation = { TvOpeningKey() })
    val surfaceFocus = remember(calculation = { FocusRequester() })
    val channelsFocus = remember(calculation = { FocusRequester() })
    val moreFocus = remember(calculation = { FocusRequester() })
    var returnToSettings by remember(calculation = { mutableStateOf(false) })
    val groupFocus = remember(calculation = { FocusRequester() })
    var groupHasFocus by remember(calculation = { mutableStateOf(false) })
    var navigatingGroups by remember(calculation = { mutableStateOf(false) })
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val hasChannels = state.channels.isNotEmpty()
    var browsedChannelId by remember(state.activeSourceId, state.selectedId, calculation = { mutableStateOf(state.selectedId) })
    val guideChannel = state.channels.firstOrNull(predicate = { channel ->
        channel.id == browsedChannelId && channel.guideRefs.isNotEmpty()
    })
    val domestic = state.sources.any(predicate = { source -> source.id == state.activeSourceId && LibraryRules.isDomestic(source) })
    val groups = remember(domestic, state.channels, state.recentChannelIds, calculation = {
        val catalog = if (domestic) {
            groupDomesticChannels(state.channels)
        } else {
            mapOf(DomesticChannelGroup.ALL to state.channels)
        }
        catalog + (DomesticChannelGroup.RECENT to RecentChannels.visible(state.recentChannelIds, state.channels))
    })
    val availableGroups = remember(groups, calculation = {
        groups.filterValues(predicate = { channels -> channels.isNotEmpty() }).keys.toList()
    })
    var selectedGroup by remember(state.activeSourceId, calculation = { mutableStateOf(DomesticChannelGroup.ALL) })
    val activeGroup = selectedGroup.takeIf(predicate = { group -> group in availableGroups }) ?: DomesticChannelGroup.ALL
    val displayChannel = if (domestic) {
        state.channels.firstOrNull(predicate = { channel -> channel.id == state.selectedId })
    } else {
        null
    }
    val displayName = if (displayChannel != null) domesticDisplayName(displayChannel) else state.selectedName
    val visiblePanel = if (state.infoVisible) panel else TvPanel.Controls
    val keepFocusInDrawer = television && visiblePanel == TvPanel.Channels && availableGroups.size > 1 && hasChannels
    val keepFocusInMore = television && visiblePanel == TvPanel.More
    val showPanel: (TvPanel) -> Unit = { target ->
        navigatingGroups = false
        if (target == TvPanel.More) {
            returnToSettings = false
        }
        returnToMore = target == TvPanel.More || (target == TvPanel.Controls && panel == TvPanel.More)
        panel = target
        viewModel.showInformation()
    }
    LaunchedEffect(viewModel.moreMenuRequested, block = {
        if (viewModel.moreMenuRequested && !dialogOpen) {
            showPanel(TvPanel.More)
            viewModel.consumeMoreMenuRequest()
        }
    })
    val dismiss: () -> Unit = {
        panel = TvPanel.Controls
        groupHasFocus = false
        navigatingGroups = false
        returnToMore = false
        viewModel.hideInformation()
    }
    BackHandler(enabled = state.infoVisible && !dialogOpen, onBack = dismiss)
    LaunchedEffect(dialogOpen, visiblePanel, block = {
        viewModel.holdInformation(dialogOpen || visiblePanel != TvPanel.Controls)
    })
    LaunchedEffect(state.infoVisible, visiblePanel, dialogOpen, hasChannels, windowFocused, television, block = {
        if (!television || dialogOpen || !windowFocused) {
            return@LaunchedEffect
        }
        inputMode.requestInputMode(InputMode.Keyboard)
        withFrameNanos(onFrame = { _ -> })
        when {
            !state.infoVisible -> {
                panel = TvPanel.Controls
                returnToMore = false
                surfaceFocus.requestFocus()
            }
            visiblePanel == TvPanel.More -> Unit
            visiblePanel == TvPanel.Channels && hasChannels -> Unit
            returnToMore -> moreFocus.requestFocus()
            else -> channelsFocus.requestFocus()
        }
    })
    BoxWithConstraints(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(onPreviewKeyEvent = { event ->
        val key = event.nativeKeyEvent
        if (openingKey.consume(key.keyCode, key.downTime, released = key.action == KeyEvent.ACTION_UP)) {
            return@onPreviewKeyEvent true
        }
        if (!television || dialogOpen || key.action != KeyEvent.ACTION_DOWN) {
            return@onPreviewKeyEvent false
        }
        if (state.infoVisible) {
            viewModel.showInformation()
        }
        if (visiblePanel == TvPanel.Channels && groupHasFocus && availableGroups.size > 1 &&
            (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            openingKey.record(key.keyCode, key.downTime)
            if (key.repeatCount == 0) {
                val direction = if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val index = Math.floorMod(availableGroups.indexOf(activeGroup) + direction, availableGroups.size)
                selectedGroup = availableGroups[index]
            }
            return@onPreviewKeyEvent true
        }
        when (key.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                openingKey.record(key.keyCode, key.downTime)
                if (key.repeatCount == 0) {
                    showPanel(if (key.keyCode == KeyEvent.KEYCODE_MENU) TvPanel.More else TvPanel.Controls)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (visiblePanel != TvPanel.Controls) {
                    return@onPreviewKeyEvent false
                }
                if (key.repeatCount == 0) {
                    if (hasChannels) {
                        viewModel.changeChannel(if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    } else {
                        showPanel(TvPanel.Controls)
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (visiblePanel != TvPanel.Controls || state.infoVisible) {
                    return@onPreviewKeyEvent false
                }
                openingKey.record(key.keyCode, key.downTime)
                if (key.repeatCount == 0) {
                    showPanel(TvPanel.Channels)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (state.infoVisible) {
                    return@onPreviewKeyEvent false
                }
                openingKey.record(key.keyCode, key.downTime)
                if (key.repeatCount == 0) {
                    showPanel(TvPanel.Controls)
                }
                true
            }
            else -> false
        }
    }), content = {
        val portrait = !television && maxHeight > maxWidth
        val inset = minOf(32.dp, maxWidth * 0.04f)
        val bottom = if (television) 20.dp else 10.dp
        val portraitInset = if (maxWidth < 360.dp) 12.dp else 16.dp
        val portraitWidth = Modifier.padding(horizontal = portraitInset).widthIn(max = 480.dp).fillMaxWidth()
        var portraitControlsHeight by remember(calculation = { mutableIntStateOf(0) })
        val portraitBottom = with(LocalDensity.current) { portraitControlsHeight.toDp() }
        val controlInsets = if (television) {
            Modifier
        } else {
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.systemBarsIgnoringVisibility)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
        }
        val surfaceInput = if (television) {
            Modifier.focusRequester(surfaceFocus).focusProperties(scope = { canFocus = !state.infoVisible && !dialogOpen }).focusable()
        } else if (!dialogOpen) {
            val toggleControls: () -> Unit = {
                if (state.infoVisible) {
                    dismiss()
                } else {
                    showPanel(TvPanel.Controls)
                }
            }
            Modifier.pointerInput(state.infoVisible, visiblePanel, block = {
                detectTapGestures(onTap = { toggleControls() })
            }).semantics(properties = {
                onClick(action = {
                    toggleControls()
                    true
                })
            })
        } else {
            Modifier
        }
        PlaybackSurface(
            state = state,
            fillVideoScreen = viewModel.fillVideoScreen,
            showPlaybackStatus = !dialogOpen && visiblePanel == TvPanel.Controls,
            onPlay = if (!television && state.channels.isNotEmpty()) viewModel::togglePlayback else null,
            modifier = Modifier.fillMaxSize().then(surfaceInput)
        )
        if (state.infoVisible && !dialogOpen) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(if (portrait) maxOf(118.dp, portraitBottom + 20.dp) else 118.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, BackdropBottom.copy(alpha = 0.88f)))))
            CinemaControls(
                state = state,
                displayName = displayName,
                viewModel = viewModel,
                channelsFocus = channelsFocus,
                moreFocus = moreFocus,
                onChannels = { showPanel(if (visiblePanel == TvPanel.Channels) TvPanel.Controls else TvPanel.Channels) },
                onMore = { showPanel(if (visiblePanel == TvPanel.More) TvPanel.Controls else TvPanel.More) },
                portrait = portrait,
                television = television,
                controlsFocusable = !keepFocusInDrawer && !keepFocusInMore,
                modifier = Modifier.align(Alignment.BottomCenter).then(controlInsets).then(
                    if (portrait) {
                        portraitWidth.onSizeChanged(onSizeChanged = { size -> portraitControlsHeight = size.height })
                            .padding(vertical = bottom)
                    } else {
                        Modifier.fillMaxWidth().padding(horizontal = inset, vertical = bottom)
                    }
                )
            )
            when (visiblePanel) {
                TvPanel.Controls -> Unit
                TvPanel.Channels -> key(state.activeSourceId, block = {
                    ChannelPanel(
                        state = state,
                        channels = groups[activeGroup].orEmpty(),
                        onSelect = viewModel::select,
                        onGuide = onGuide,
                        onChannelFocused = { channel ->
                            navigatingGroups = false
                            browsedChannelId = channel.id
                        },
                        modifier = if (portrait) {
                            Modifier.align(Alignment.BottomCenter).then(controlInsets).then(portraitWidth)
                                .padding(bottom = portraitBottom).fillMaxHeight(0.46f)
                        } else {
                            Modifier.align(Alignment.TopStart).then(controlInsets).padding(start = inset, top = 24.dp, bottom = if (television) 86.dp else 76.dp)
                                .width(minOf(248.dp, maxWidth * 0.40f)).fillMaxHeight()
                        },
                        television = television,
                        selectedRowFocus = selectedRowFocus,
                        onConfirmSelect = {
                            panel = TvPanel.Controls
                            viewModel.hideInformation()
                        },
                        onRight = {
                            if (guideChannel != null) {
                                onGuide(guideChannel.id)
                            }
                        },
                        onLeftToHeader = {
                            if (availableGroups.size > 1) {
                                navigatingGroups = true
                                groupFocus.requestFocus()
                            } else if (!keepFocusInDrawer) {
                                channelsFocus.requestFocus()
                            }
                        },
                        onBottomBoundary = { if (!keepFocusInDrawer) { channelsFocus.requestFocus() } },
                        domestic = domestic,
                        groupKey = activeGroup.name,
                        requestDrawerFocus = television && windowFocused && !dialogOpen && !navigatingGroups,
                        groupControls = {
                            if (availableGroups.size > 1) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = {
                                    ChannelGroupControls(availableGroups, activeGroup, television,
                                        modifier = Modifier.focusRequester(groupFocus).onFocusChanged(onFocusChanged = { focus ->
                                            groupHasFocus = focus.hasFocus
                                        }),
                                        onEnterChannels = {
                                            navigatingGroups = false
                                            selectedRowFocus.restore()
                                        },
                                        onSelect = { group ->
                                            selectedGroup = group
                                        })
                                })
                            }
                        }
                    )
                })
                TvPanel.More -> CinemaMoreMenu(
                    state = state,
                    viewModel = viewModel,
                    television = television,
                    restoreSettings = returnToSettings,
                    onClose = dismiss,
                    onSources = {
                        returnToSettings = false
                        if (!television) {
                            panel = TvPanel.Controls
                        }
                        onSources()
                    },
                    onSettings = {
                        returnToSettings = true
                        onSettings()
                    },
                    modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.BottomEnd)
                        .then(controlInsets)
                        .padding(end = if (portrait) 0.dp else inset, top = 24.dp,
                            bottom = if (portrait) portraitBottom else if (television) 84.dp else 78.dp)
                        .width(minOf(if (television) 276.dp else 300.dp, maxWidth - inset * 2))
                )
            }
        }
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CinemaControls(
    state: PlayerState,
    displayName: String?,
    viewModel: PlayerViewModel,
    channelsFocus: FocusRequester,
    moreFocus: FocusRequester,
    onChannels: () -> Unit,
    onMore: () -> Unit,
    portrait: Boolean,
    television: Boolean,
    controlsFocusable: Boolean,
    modifier: Modifier
): Unit {
    val channelName: @Composable (Modifier) -> Unit = { nameModifier ->
        Column(modifier = nameModifier, verticalArrangement = Arrangement.spacedBy(2.dp), content = {
            Text(text = displayName ?: stringResource(R.string.choose_channel), color = Accent,
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.selectedSourceName.isNullOrBlank()) {
                Text(text = state.selectedSourceName, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        })
    }
    val actionBackground = Modifier.background(BackdropBottom.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
        .padding(horizontal = 4.dp)
    val actions: @Composable (Modifier, Dp) -> Unit = { actionWidth, horizontalPadding ->
        CinemaAction(label = stringResource(R.string.tv_channels), onClick = onChannels,
            modifier = actionWidth.focusRequester(channelsFocus).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                val native = event.nativeKeyEvent
                if (television && native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        onChannels()
                    }
                    true
                } else {
                    false
                }
            }), interactive = controlsFocusable, horizontalPadding = horizontalPadding)
        if (state.channels.isNotEmpty()) {
            CinemaPlaybackAction(viewModel = viewModel, initialPlayback = state.playback,
                modifier = actionWidth, interactive = controlsFocusable, horizontalPadding = horizontalPadding)
        }
        if (state.selectedId != null) {
            val favorite = state.selectedId in state.favoriteIds
            CinemaAction(label = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                icon = if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                tint = if (favorite) Gold else Accent, onClick = viewModel::toggleCurrentFavorite,
                modifier = actionWidth, interactive = controlsFocusable, horizontalPadding = horizontalPadding)
        }
        CinemaAction(label = stringResource(R.string.tv_more), onClick = onMore,
            modifier = actionWidth.focusRequester(moreFocus), interactive = controlsFocusable,
            horizontalPadding = horizontalPadding)
    }
    if (portrait) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp), content = {
            channelName(Modifier)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().then(actionBackground), content = {
                val actionCount = 2 + (if (state.channels.isNotEmpty()) 1 else 0) + (if (state.selectedId != null) 1 else 0)
                val availableColumns = (maxWidth / 48.dp).toInt().coerceAtLeast(1)
                val columns = if (availableColumns >= actionCount) actionCount else minOf(2, availableColumns)
                val actionWidth = with(LocalDensity.current) { (maxWidth.toPx() / columns).toInt().toDp() }
                val actionPadding = if (maxWidth < 330.dp) 6.dp else 12.dp
                FlowRow(horizontalArrangement = Arrangement.Center, maxItemsInEachRow = columns, content = {
                    actions(Modifier.width(actionWidth).height(48.dp), actionPadding)
                })
            })
        })
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = {
            channelName(Modifier.weight(1f))
            Row(modifier = actionBackground, verticalAlignment = Alignment.CenterVertically, content = {
                actions(Modifier, 12.dp)
            })
        })
    }
}

@Composable
private fun CinemaPlaybackAction(viewModel: PlayerViewModel, initialPlayback: PlaybackStatus, modifier: Modifier,
    interactive: Boolean, horizontalPadding: Dp): Unit {
    val playbackFlow = remember(viewModel, calculation = {
        viewModel.state.map(transform = { state -> state.playback }).distinctUntilChanged()
    })
    val playback by playbackFlow.collectAsStateWithLifecycle(initialValue = initialPlayback)
    val playing = playback == PlaybackStatus.PLAYING
    val view = LocalView.current
    // The SurfaceView can keep rendering while the TV retains the old controls layer.
    LaunchedEffect(playback, block = {
        withFrameNanos(onFrame = { _ -> })
        view.postInvalidateOnAnimation()
    })
    CinemaAction(
        label = stringResource(if (playing) R.string.pause else R.string.play),
        icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        onClick = viewModel::togglePlayback,
        modifier = modifier,
        interactive = interactive,
        horizontalPadding = horizontalPadding
    )
}
