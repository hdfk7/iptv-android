package cn.hdfk7.android.iptv.feature.player

import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.data.repository.LibraryRules
import cn.hdfk7.android.iptv.core.model.DomesticChannelGroup
import cn.hdfk7.android.iptv.core.model.groupDomesticChannels
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.BackdropBottom
import cn.hdfk7.android.iptv.core.ui.theme.CinemaMenuSurface
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
    selectedRowFocus: ChannelListFocus,
    dialogOpen: Boolean,
    television: Boolean
): Unit {
    var panel by remember(calculation = { mutableStateOf(TvPanel.Controls) })
    var returnToMore by remember(calculation = { mutableStateOf(false) })
    var opener by remember(calculation = { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) })
    val surfaceFocus = remember(calculation = { FocusRequester() })
    val channelsFocus = remember(calculation = { FocusRequester() })
    val moreFocus = remember(calculation = { FocusRequester() })
    val firstMenuFocus = remember(calculation = { FocusRequester() })
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val hasChannels = state.channels.isNotEmpty()
    val domestic = state.sources.any(predicate = { source -> source.id == state.activeSourceId && LibraryRules.isDomestic(source) })
    val groups = remember(domestic, state.channels, calculation = {
        if (domestic) groupDomesticChannels(state.channels) else emptyMap()
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
    val showPanel: (TvPanel) -> Unit = { target ->
        returnToMore = target == TvPanel.More || (target == TvPanel.Controls && panel == TvPanel.More)
        panel = target
        viewModel.showInformation()
    }
    val dismiss: () -> Unit = {
        if (panel == TvPanel.Controls) {
            viewModel.hideInformation()
        } else {
            panel = TvPanel.Controls
        }
    }
    BackHandler(enabled = state.infoVisible && !dialogOpen, onBack = dismiss)
    LaunchedEffect(dialogOpen, visiblePanel, block = {
        viewModel.holdInformation(dialogOpen || visiblePanel != TvPanel.Controls)
    })
    LaunchedEffect(state.infoVisible, visiblePanel, dialogOpen, hasChannels, windowFocused, television, activeGroup, block = {
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
            visiblePanel == TvPanel.More -> firstMenuFocus.requestFocus()
            visiblePanel == TvPanel.Channels && hasChannels -> selectedRowFocus.focusSelected?.invoke()
            returnToMore -> moreFocus.requestFocus()
            else -> channelsFocus.requestFocus()
        }
    })
    BoxWithConstraints(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(onPreviewKeyEvent = { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode == opener) {
            if (key.action == KeyEvent.ACTION_UP) {
                opener = KeyEvent.KEYCODE_UNKNOWN
            }
            return@onPreviewKeyEvent true
        }
        if (!television || dialogOpen || key.action != KeyEvent.ACTION_DOWN) {
            return@onPreviewKeyEvent false
        }
        if (state.infoVisible) {
            viewModel.showInformation()
        }
        if (visiblePanel == TvPanel.Channels && availableGroups.size > 1 &&
            (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            opener = key.keyCode
            if (key.repeatCount == 0) {
                val direction = if (key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val index = Math.floorMod(availableGroups.indexOf(activeGroup) + direction, availableGroups.size)
                selectedGroup = availableGroups[index]
            }
            return@onPreviewKeyEvent true
        }
        when (key.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                opener = key.keyCode
                if (key.repeatCount == 0) {
                    showPanel(if (key.keyCode == KeyEvent.KEYCODE_MENU && state.infoVisible) TvPanel.More else TvPanel.Controls)
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (state.infoVisible) {
                    return@onPreviewKeyEvent false
                }
                opener = key.keyCode
                if (key.repeatCount == 0) {
                    showPanel(if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) TvPanel.Channels else TvPanel.Controls)
                }
                true
            }
            else -> false
        }
    }), content = {
        val portrait = !television && maxHeight > maxWidth
        val inset = minOf(32.dp, maxWidth * 0.04f)
        val bottom = if (television) 20.dp else 10.dp
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
            modifier = Modifier.fillMaxSize().then(surfaceInput)
        )
        if (state.infoVisible && !dialogOpen) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(118.dp)
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
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .then(controlInsets)
                    .padding(horizontal = inset, vertical = bottom)
            )
            when (visiblePanel) {
                TvPanel.Controls -> Unit
                TvPanel.Channels -> key(state.activeSourceId, activeGroup, block = {
                    ChannelPanel(
                        state = state.copy(channels = groups[activeGroup] ?: state.channels),
                        onSelect = viewModel::select,
                        modifier = if (portrait) {
                            Modifier.align(Alignment.BottomCenter).then(controlInsets).padding(start = inset, end = inset, bottom = 116.dp)
                                .fillMaxWidth().fillMaxHeight(0.46f)
                        } else {
                            Modifier.align(Alignment.TopStart).then(controlInsets).padding(start = inset, top = 24.dp, bottom = if (television) 86.dp else 76.dp)
                                .width(minOf(248.dp, maxWidth * 0.40f)).fillMaxHeight()
                        },
                        compact = true,
                        television = television,
                        requestInitialFocus = false,
                        onInitialFocus = {},
                        selectedRowFocus = selectedRowFocus,
                        videoFocus = surfaceFocus,
                        onConfirmSelect = {
                            panel = TvPanel.Controls
                            viewModel.hideInformation()
                        },
                        onRight = { channelsFocus.requestFocus() },
                        onLeftToHeader = { channelsFocus.requestFocus() },
                        onTopBoundary = { channelsFocus.requestFocus() },
                        onBottomBoundary = { channelsFocus.requestFocus() },
                        cinema = true,
                        domestic = domestic,
                        groupControls = {
                            if (availableGroups.size > 1) {
                                DomesticChannelControls(availableGroups, activeGroup, television, onSelect = { group -> selectedGroup = group })
                            }
                        }
                    )
                })
                TvPanel.More -> CinemaMoreMenu(
                    state = state,
                    viewModel = viewModel,
                    television = television,
                    firstFocus = firstMenuFocus,
                    onSources = {
                        panel = TvPanel.Controls
                        onSources()
                    },
                    onSettings = {
                        panel = TvPanel.Controls
                        onSettings()
                    },
                    modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.BottomEnd)
                        .then(controlInsets)
                        .padding(end = if (portrait) 0.dp else inset, top = 24.dp, bottom = if (portrait) 116.dp else if (television) 84.dp else 78.dp)
                        .width(minOf(if (television) 276.dp else 300.dp, maxWidth - inset * 2))
                )
            }
        }
    })
}

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
    val actions: @Composable () -> Unit = {
        Row(
            modifier = (if (portrait) Modifier.fillMaxWidth() else Modifier)
                .background(BackdropBottom.copy(alpha = 0.68f), RoundedCornerShape(12.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = {
                val actionWidth = if (portrait) Modifier.weight(1f) else Modifier
                CinemaAction(label = stringResource(R.string.tv_channels), onClick = onChannels, modifier = actionWidth.focusRequester(channelsFocus))
                if (state.selectedId != null) {
                    val playing = state.playback == PlaybackStatus.PLAYING
                    val favorite = state.selectedId in state.favoriteIds
                    CinemaAction(label = stringResource(if (playing) R.string.pause else R.string.play),
                        icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, onClick = viewModel::togglePlayback, modifier = actionWidth)
                    CinemaAction(label = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                        icon = if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                        tint = if (favorite) Gold else Accent, onClick = viewModel::toggleCurrentFavorite, modifier = actionWidth)
                }
                CinemaAction(label = stringResource(R.string.tv_more), onClick = onMore, modifier = actionWidth.focusRequester(moreFocus))
            }
        )
    }
    if (portrait) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp), content = {
            channelName(Modifier)
            actions()
        })
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = {
            channelName(Modifier.weight(1f))
            actions()
        })
    }
}

@Composable
private fun CinemaMoreMenu(
    state: PlayerState,
    viewModel: PlayerViewModel,
    television: Boolean,
    firstFocus: FocusRequester,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier
): Unit {
    Column(
        modifier = modifier.background(CinemaMenuSurface, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(8.dp),
        content = {
            CinemaAction(label = stringResource(R.string.library_title), onClick = onSources,
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus))
            CinemaAction(label = stringResource(R.string.refresh), onClick = viewModel::refreshManually, modifier = Modifier.fillMaxWidth())
            if (!television && state.channels.size > 1) {
                Row(content = {
                    CinemaAction(label = stringResource(R.string.previous), onClick = { viewModel.changeChannel(-1) }, modifier = Modifier.weight(1f))
                    CinemaAction(label = stringResource(R.string.next), onClick = { viewModel.changeChannel(1) }, modifier = Modifier.weight(1f))
                })
            }
            if (state.selectedId != null) {
                CinemaAction(label = stringResource(R.string.retry_current_line), onClick = viewModel::retryCurrentLine, modifier = Modifier.fillMaxWidth())
                if (state.lineCount > 0) {
                    Text(text = stringResource(R.string.tv_line_status, state.line, state.lineCount), color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 5.dp))
                }
                if (state.lineCount > 1) {
                    Row(content = {
                        CinemaAction(label = stringResource(R.string.previous_line), onClick = { viewModel.changeStreamLine(-1) }, modifier = Modifier.weight(1f))
                        CinemaAction(label = stringResource(R.string.next_line), onClick = { viewModel.changeStreamLine(1) }, modifier = Modifier.weight(1f))
                    })
                }
            }
            if (state.fixedVolume) {
                Text(text = stringResource(R.string.fixed_volume), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            } else {
                Row(content = {
                    CinemaAction(label = stringResource(R.string.volume_down), onClick = { viewModel.changeVolume(AudioManager.ADJUST_LOWER) }, modifier = Modifier.weight(1f))
                    CinemaAction(label = stringResource(R.string.volume_up), onClick = { viewModel.changeVolume(AudioManager.ADJUST_RAISE) }, modifier = Modifier.weight(1f))
                })
            }
            CinemaAction(label = stringResource(R.string.settings_title), onClick = onSettings, modifier = Modifier.fillMaxWidth())
        }
    )
}

@Composable
private fun CinemaAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    tint: Color = Accent
): Unit {
    TvAction(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.Transparent,
        focusBorderWidth = 2.dp,
        showUnfocusedBorder = false,
        contentPadding = PaddingValues(horizontal = 12.dp),
        content = {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            } else {
                Text(text = label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}
