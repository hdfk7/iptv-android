package cn.hdfk7.android.iptv.feature.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.tvDirections
import cn.hdfk7.android.iptv.core.ui.theme.*

@Composable
fun PlaybackPanel(state: PlayerState, viewModel: PlayerViewModel, modifier: Modifier, videoFocus: FocusRequester, selectedRowFocus: ChannelListFocus, onVideoBounds: (Rect) -> Unit, touchControls: Boolean, previewHeight: Dp?, narrowTv: Boolean) {
    var videoFocused by remember(calculation = { mutableStateOf(false) })
    val fullscreenDescription = stringResource(R.string.fullscreen)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (touchControls) 8.dp else 12.dp), content = {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                Text(text = state.selectedName.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (touchControls) 19.sp else 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.selectedSourceName != null && (!touchControls || state.activeSourceId == SourceLibrary.FAVORITES_ID)) {
                    Text(text = state.selectedSourceName, color = Muted, fontSize = 12.sp)
                }
            })
            if (narrowTv && state.line > 0) {
                StreamLineBadge(state = state, onSwitch = viewModel::changeStreamLine)
            }
            if (state.selectedId != null) {
                TvIconButton(icon = if (state.selectedId in state.favoriteIds) R.drawable.ic_star_filled else R.drawable.ic_star, description = stringResource(if (state.selectedId in state.favoriteIds) R.string.favorite_remove else R.string.favorite_add), onClick = viewModel::toggleCurrentFavorite, tint = if (state.selectedId in state.favoriteIds) Gold else Muted, modifier = Modifier.onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            selectedRowFocus.focusSelected?.invoke()
                        }
                        true
                    } else {
                        false
                    }
                }))
            }
        })
        val preview = if (previewHeight == null) Modifier.weight(1f) else Modifier.height(previewHeight)
        Box(modifier = preview.fillMaxWidth().clip(RoundedCornerShape(18.dp)).focusRequester(videoFocus).onFocusChanged(onFocusChanged = { focus -> videoFocused = focus.isFocused }).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            val key = event.nativeKeyEvent
            if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                    selectedRowFocus.restore?.invoke()
                }
                true
            } else if (key.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || key.keyCode == KeyEvent.KEYCODE_ENTER || key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (key.action == KeyEvent.ACTION_UP) {
                    viewModel.enterFullscreen()
                }
                true
            } else {
                false
            }
        }).focusable().semantics(properties = {
            contentDescription = fullscreenDescription
            role = Role.Button
            onClick(action = { viewModel.enterFullscreen(); true })
        }).pointerInput(key1 = viewModel, block = { detectTapGestures(onTap = { viewModel.enterFullscreen() }) }), content = {
            PlaybackSurface(state = state, modifier = Modifier.fillMaxSize().onGloballyPositioned(onGloballyPositioned = { coordinates ->
                onVideoBounds(Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
            }))
            if (videoFocused) {
                Box(modifier = Modifier.matchParentSize().border(2.dp, FocusBorder, RoundedCornerShape(18.dp)))
            }
        })
        PlaybackControls(state = state, viewModel = viewModel, selectedRowFocus = selectedRowFocus, touchControls = touchControls, narrowTv = narrowTv)
    })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlaybackSurface(state: PlayerState, modifier: Modifier, framed: Boolean = true) {
    val frameModifier = if (state.fullscreen || !framed) {
        modifier
    } else {
        modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp))
    }
    Box(modifier = frameModifier, contentAlignment = Alignment.Center, content = {
        if (state.playback == PlaybackStatus.IDLE || state.playback == PlaybackStatus.ERROR || state.playback == PlaybackStatus.LOADING) {
            Column(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(PanelAlt, BackdropBottom))), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = {
                if (state.playback == PlaybackStatus.LOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Accent, strokeWidth = 3.dp)
                } else {
                    Box(modifier = Modifier.size(60.dp).background(Color.White.copy(alpha = 0.05f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center, content = {
                        Icon(painter = painterResource(R.drawable.ic_play), contentDescription = null, tint = Accent, modifier = Modifier.size(26.dp))
                    })
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (state.playback != PlaybackStatus.IDLE) {
                    Text(text = playbackMessage(state), color = Color.White, fontSize = 16.sp)
                }
            })
        }
        if (state.playback == PlaybackStatus.PAUSED) {
            Text(text = stringResource(R.string.paused), color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(14.dp))
        }
    })
}

@Composable
private fun playbackMessage(state: PlayerState): String {
    return when (state.playback) {
        PlaybackStatus.IDLE -> {
            stringResource(R.string.choose_channel)
        }
        PlaybackStatus.LOADING -> {
            stringResource(R.string.loading, state.line, state.lineCount)
        }
        PlaybackStatus.ERROR -> {
            stringResource(R.string.stream_error)
        }
        PlaybackStatus.PAUSED -> {
            stringResource(R.string.paused)
        }
        PlaybackStatus.PLAYING -> {
            stringResource(R.string.playing)
        }
    }
}

@Composable
fun PlaybackControls(state: PlayerState, viewModel: PlayerViewModel, selectedRowFocus: ChannelListFocus, touchControls: Boolean, narrowTv: Boolean, modifier: Modifier = Modifier, controlsFocus: FocusRequester? = null, onLeft: (() -> Unit)? = null, volumeUpFocus: FocusRequester? = null, onSettingsUp: (() -> Unit)? = null) {
    val tvFocus = remember(calculation = { List(8, init = { _ -> FocusRequester() }) })
    val lineButtonsEnabled = !narrowTv && state.lineCount > 1
    val focusOrder = if (lineButtonsEnabled) listOf(0, 1, 2, 3, 4, 5, 6, 7) else listOf(0, 1, 2, 3, 6, 7)
    val focusTargets = tvFocus.mapIndexed(transform = { index, requester ->
        when (index) {
            0 -> controlsFocus ?: requester
            7 -> volumeUpFocus ?: requester
            else -> requester
        }
    })
    val navigation: (Int) -> Modifier = { index ->
        if (controlsFocus == null) {
            Modifier
        } else {
            val position = focusOrder.indexOf(index)
            Modifier.focusRequester(focusTargets[index]).tvDirections(
                up = when (index) {
                    in 0..3 -> ({
                        selectedRowFocus.restore?.invoke()
                        Unit
                    })
                    else -> onSettingsUp
                },
                left = {
                    if (index == 0) {
                        onLeft?.invoke()
                    } else {
                        focusOrder.getOrNull(position - 1)?.let(block = { target -> focusTargets[target].requestFocus() })
                    }
                },
                right = {
                    if (position >= 0) {
                        focusOrder.getOrNull(position + 1)?.let(block = { target -> focusTargets[target].requestFocus() })
                    }
                }
            )
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp), content = {
        val controls = if (touchControls) Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
        Row(modifier = controls, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrowTv) 6.dp else 8.dp), content = {
            TvIconButton(icon = R.drawable.ic_previous, description = stringResource(R.string.previous), onClick = { viewModel.changeChannel(-1) }, modifier = navigation(0).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            if (onLeft != null) {
                                onLeft()
                            } else {
                                selectedRowFocus.restore?.invoke()
                            }
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            selectedRowFocus.restore?.invoke()
                        }
                        true
                    }
                    else -> {
                        false
                    }
                }
            }))
            TvIconButton(description = stringResource(if (state.playback == PlaybackStatus.PLAYING) R.string.pause else R.string.play), icon = if (state.playback == PlaybackStatus.PLAYING) R.drawable.ic_pause else R.drawable.ic_play, onClick = viewModel::togglePlayback, selected = true, modifier = navigation(1))
            TvIconButton(icon = R.drawable.ic_next, description = stringResource(R.string.next), onClick = { viewModel.changeChannel(1) }, modifier = navigation(2))
            TvIconButton(icon = R.drawable.ic_refresh, description = stringResource(R.string.retry_current_line), onClick = viewModel::retryCurrentLine, modifier = navigation(3))
            if (!touchControls) {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (!narrowTv && state.lineCount > 0) {
                StreamLineBadge(state = state, onSwitch = viewModel::changeStreamLine, previousModifier = navigation(4), nextModifier = navigation(5))
            }
            if (!touchControls) {
                TvIconButton(icon = R.drawable.ic_volume_down, description = stringResource(R.string.volume_down), onClick = { viewModel.changeVolume(android.media.AudioManager.ADJUST_LOWER) }, modifier = navigation(6))
                TvIconButton(icon = R.drawable.ic_volume_up, description = stringResource(R.string.volume_up), onClick = { viewModel.changeVolume(android.media.AudioManager.ADJUST_RAISE) }, modifier = navigation(7).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                    if (event.key == Key.DirectionUp) {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            if (onSettingsUp != null) {
                                onSettingsUp()
                            } else {
                                selectedRowFocus.focusTop?.invoke()
                            }
                        }
                        true
                    } else {
                        false
                    }
                }))
            }
        })
        if (state.fixedVolume) {
            Text(text = stringResource(R.string.fixed_volume), color = Muted, fontSize = 10.sp)
        }
    })
}

@Composable
fun StreamLineBadge(state: PlayerState, onSwitch: (Int) -> Unit, previousModifier: Modifier = Modifier, nextModifier: Modifier = Modifier) {
    val statusColor = when (state.playback) {
        PlaybackStatus.ERROR, PlaybackStatus.LOADING -> Gold
        else -> Accent
    }
    val enabled = state.lineCount > 1
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier = Modifier.clip(shape).background(PanelAlt).border(1.dp, Border, shape),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            val previousLine = stringResource(R.string.previous_line)
            TvAction(
                onClick = { onSwitch(-1) },
                modifier = previousModifier.width(48.dp).semantics(properties = { contentDescription = previousLine }),
                shape = RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp),
                enabled = enabled,
                containerColor = Color.Transparent,
                showUnfocusedBorder = false,
                contentPadding = PaddingValues(horizontal = 8.dp),
                content = {
                    Text(text = state.line.toString().padStart(2, '0'), color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            )
            Box(modifier = Modifier.width(1.dp).height(12.dp).background(Divider))
            val nextLine = stringResource(R.string.next_line)
            TvAction(
                onClick = { onSwitch(1) },
                shape = RoundedCornerShape(topEnd = 23.dp, bottomEnd = 23.dp),
                enabled = enabled,
                modifier = nextModifier.width(48.dp).semantics(properties = { contentDescription = nextLine }),
                containerColor = Color.Transparent,
                showUnfocusedBorder = false,
                contentPadding = PaddingValues(horizontal = 8.dp),
                content = {
                    Text(text = state.lineCount.toString().padStart(2, '0'), color = Muted, fontSize = 12.sp)
                }
            )
        }
    )
}
