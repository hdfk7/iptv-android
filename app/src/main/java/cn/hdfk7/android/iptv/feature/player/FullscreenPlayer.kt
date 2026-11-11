package cn.hdfk7.android.iptv.feature.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.theme.Gold
import cn.hdfk7.android.iptv.core.ui.theme.Muted
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface

@Composable
fun FullscreenPlayer(state: PlayerState, viewModel: PlayerViewModel, touchControls: Boolean) {
    val inputMode = LocalInputModeManager.current
    val surfaceFocus = remember(calculation = { FocusRequester() })
    val controlFocus = remember(calculation = { FocusRequester() })
    var revealKey by remember(calculation = { mutableStateOf<Int?>(null) })
    LaunchedEffect(key1 = state.infoVisible, block = {
        if (touchControls) {
            return@LaunchedEffect
        }
        inputMode.requestInputMode(InputMode.Keyboard)
        if (state.infoVisible) {
            controlFocus.requestFocus()
        } else {
            surfaceFocus.requestFocus()
        }
    })
    val retryLineButton: @Composable () -> Unit = {
        TvIconButton(icon = R.drawable.ic_refresh, description = stringResource(R.string.retry_current_line), onClick = viewModel::retryCurrentLine)
    }
    val revealOnTouch = if (touchControls) {
        Modifier.pointerInput(key1 = viewModel, block = {
            detectTapGestures(onTap = { viewModel.showInformation() })
        })
    } else {
        Modifier
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().then(revealOnTouch).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            val key = event.nativeKeyEvent
            val informationKey = key.keyCode == KeyEvent.KEYCODE_INFO || key.keyCode == KeyEvent.KEYCODE_MENU
            val directionKey = key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
            val shouldReveal = key.action == KeyEvent.ACTION_DOWN && (informationKey || directionKey)
            if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP || key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                    viewModel.changeChannel(if (key.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    viewModel.showInformation()
                }
                true
            } else if (key.action == KeyEvent.ACTION_UP && key.keyCode == revealKey) {
                revealKey = null
                true
            } else if (shouldReveal) {
                val hidden = !state.infoVisible
                if (hidden || informationKey) {
                    revealKey = key.keyCode
                }
                viewModel.showInformation()
                hidden || informationKey
            } else {
                false
            }
        }).focusRequester(surfaceFocus).focusable(),
        content = {
            PlaybackSurface(state = state, modifier = Modifier.fillMaxSize())
            if (state.infoVisible) {
                val landscapePhone = touchControls && maxWidth > maxHeight && maxWidth >= 580.dp
                val programInfo: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                        Text(text = state.selectedName.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (landscapePhone) 18.sp else if (touchControls) 21.sp else 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = state.selectedSourceName.orEmpty(), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                }
                val controlsPanel = if (touchControls) {
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.displayCutout)
                        .widthIn(max = if (landscapePhone) 720.dp else 520.dp).fillMaxWidth().padding(12.dp)
                        .background(DialogSurface.copy(alpha = 0.96f), RoundedCornerShape(20.dp)).padding(12.dp)
                } else {
                    Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 20.dp)
                        .widthIn(max = 430.dp).fillMaxWidth()
                        .background(DialogSurface.copy(alpha = 0.96f), RoundedCornerShape(20.dp)).padding(14.dp)
                }
                Column(
                    modifier = controlsPanel,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = {
                        if (!landscapePhone) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                                programInfo(Modifier.weight(1f))
                                if (touchControls && state.lineCount > 0) {
                                    StreamLineBadge(state = state, onSwitch = viewModel::changeStreamLine)
                                    retryLineButton()
                                }
                            })
                        }
                        Row(modifier = if (touchControls) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(if (touchControls) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, content = {
                            if (landscapePhone) {
                                programInfo(Modifier.weight(1f).padding(end = 8.dp))
                            }
                            TvIconButton(icon = R.drawable.ic_previous, description = stringResource(R.string.previous), onClick = { viewModel.changeChannel(-1) })
                            TvIconButton(icon = if (state.playback == PlaybackStatus.PLAYING) R.drawable.ic_pause else R.drawable.ic_play, description = stringResource(if (state.playback == PlaybackStatus.PLAYING) R.string.pause else R.string.play), onClick = viewModel::togglePlayback, modifier = Modifier.focusRequester(controlFocus))
                            TvIconButton(icon = R.drawable.ic_next, description = stringResource(R.string.next), onClick = { viewModel.changeChannel(1) })
                            if (landscapePhone && state.lineCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StreamLineBadge(state = state, onSwitch = viewModel::changeStreamLine)
                                retryLineButton()
                            } else if (touchControls && !landscapePhone) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            TvIconButton(icon = if (state.selectedId in state.favoriteIds) R.drawable.ic_star_filled else R.drawable.ic_star, description = stringResource(if (state.selectedId in state.favoriteIds) R.string.favorite_remove else R.string.favorite_add), onClick = viewModel::toggleCurrentFavorite, tint = if (state.selectedId in state.favoriteIds) Gold else Color.White)
                            if (touchControls) {
                                TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.exit_fullscreen), onClick = viewModel::exitFullscreen)
                            } else if (state.lineCount > 0) {
                                StreamLineBadge(state = state, onSwitch = viewModel::changeStreamLine)
                                retryLineButton()
                            }
                        })
                    }
                )
            }
        }
    )
}
