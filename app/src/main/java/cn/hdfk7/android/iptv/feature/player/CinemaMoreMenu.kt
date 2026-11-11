package cn.hdfk7.android.iptv.feature.player

import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.components.requestWhenReady
import cn.hdfk7.android.iptv.core.ui.theme.CinemaMenuSurface
import cn.hdfk7.android.iptv.core.ui.theme.FocusBorder
import cn.hdfk7.android.iptv.core.ui.theme.FocusCornerRadius
import cn.hdfk7.android.iptv.core.ui.theme.FocusFill
import cn.hdfk7.android.iptv.core.ui.theme.FocusStroke
import cn.hdfk7.android.iptv.core.ui.theme.Muted

@Composable
internal fun CinemaMoreMenu(
    state: PlayerState,
    viewModel: PlayerViewModel,
    television: Boolean,
    restoreSettings: Boolean,
    onClose: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier
): Unit {
    val menuFocus = remember(calculation = { FocusRequester() })
    if (television) {
        Dialog(onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            content = {
                val windowFocused = LocalWindowInfo.current.isWindowFocused
                val inputMode = LocalInputModeManager.current
                val focusManager = LocalFocusManager.current
                LaunchedEffect(windowFocused, restoreSettings, block = {
                    if (windowFocused) {
                        inputMode.requestInputMode(InputMode.Keyboard)
                        withFrameNanos(onFrame = { _ -> })
                        focusManager.clearFocus(force = true)
                        withFrameNanos(onFrame = { _ -> })
                        menuFocus.requestWhenReady()
                    }
                })
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd, content = {
                    CinemaMoreMenuContent(state = state, viewModel = viewModel, television = television,
                        menuFocus = menuFocus, restoreSettings = restoreSettings,
                        onSources = onSources, onSettings = onSettings, modifier = modifier)
                })
            })
    } else {
        CinemaMoreMenuContent(state = state, viewModel = viewModel, television = television,
            menuFocus = menuFocus, restoreSettings = restoreSettings,
            onSources = onSources, onSettings = onSettings, modifier = modifier)
    }
}

@Composable
private fun CinemaMoreMenuContent(
    state: PlayerState,
    viewModel: PlayerViewModel,
    television: Boolean,
    menuFocus: FocusRequester,
    restoreSettings: Boolean,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier
): Unit {
    var focusedAction by remember(restoreSettings, calculation = {
        mutableStateOf(if (restoreSettings) MoreAction.SETTINGS else MoreAction.SOURCE)
    })
    var menuCoordinates by remember(calculation = { mutableStateOf<LayoutCoordinates?>(null) })
    val itemCoordinates = remember(calculation = { mutableStateMapOf<MoreAction, LayoutCoordinates>() })
    val rows = remember(television, state.channels.size, state.selectedId != null, state.lineCount, state.fixedVolume, calculation = {
        moreMenuRows(
            television = television,
            channelCount = state.channels.size,
            hasSelection = state.selectedId != null,
            lineCount = state.lineCount,
            fixedVolume = state.fixedVolume
        )
    })
    LaunchedEffect(rows, block = {
        if (rows.none(predicate = { row -> focusedAction in row })) {
            focusedAction = MoreAction.SOURCE
        }
    })
    fun navigation(action: MoreAction): Modifier {
        if (!television) {
            return Modifier
        }
        return Modifier.focusProperties(scope = { canFocus = false })
            .onGloballyPositioned(onGloballyPositioned = { coordinates -> itemCoordinates[action] = coordinates })
            .semantics(properties = { selected = focusedAction == action })
    }
    fun activate(action: MoreAction): Unit {
        when (action) {
            MoreAction.SOURCE -> onSources()
            MoreAction.REFRESH -> viewModel.refreshManually()
            MoreAction.RETRY -> viewModel.retryCurrentLine()
            MoreAction.PREVIOUS_CHANNEL -> viewModel.changeChannel(-1)
            MoreAction.NEXT_CHANNEL -> viewModel.changeChannel(1)
            MoreAction.PREVIOUS_LINE -> viewModel.changeStreamLine(-1)
            MoreAction.NEXT_LINE -> viewModel.changeStreamLine(1)
            MoreAction.VOLUME_DOWN -> viewModel.changeVolume(AudioManager.ADJUST_LOWER)
            MoreAction.VOLUME_UP -> viewModel.changeVolume(AudioManager.ADJUST_RAISE)
            MoreAction.SETTINGS -> onSettings()
        }
    }
    val directionKeys = if (television) {
        Modifier.focusRequester(menuFocus)
            .onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        activate(focusedAction)
                    }
                    return@onPreviewKeyEvent true
                }
                val direction = when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> MoreMenuDirection.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MoreMenuDirection.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MoreMenuDirection.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MoreMenuDirection.RIGHT
                    else -> return@onPreviewKeyEvent false
                }
                if (native.action == KeyEvent.ACTION_DOWN) {
                    focusedAction = moveMoreMenuFocus(rows, focusedAction, direction)
                }
                true
            }).focusable()
    } else {
        Modifier
    }
    Column(
        modifier = modifier.then(directionKeys)
            .onGloballyPositioned(onGloballyPositioned = { coordinates -> menuCoordinates = coordinates })
            .background(CinemaMenuSurface, RoundedCornerShape(12.dp))
            .drawWithContent(onDraw = {
                val menu = menuCoordinates
                val item = itemCoordinates[focusedAction]
                val bounds = if (television && menu?.isAttached == true && item?.isAttached == true) {
                    menu.localBoundingBoxOf(item, clipBounds = false)
                } else {
                    null
                }
                if (bounds != null) {
                    drawRoundRect(color = FocusFill, topLeft = bounds.topLeft, size = bounds.size,
                        cornerRadius = CornerRadius(FocusCornerRadius.toPx()))
                }
                drawContent()
                if (bounds != null) {
                    drawRoundRect(color = FocusBorder, topLeft = bounds.topLeft, size = bounds.size,
                        cornerRadius = CornerRadius(FocusCornerRadius.toPx()), style = Stroke(width = FocusStroke.toPx()))
                }
            })
            .verticalScroll(rememberScrollState()).padding(8.dp),
        content = {
            for (row in rows) {
                if (row.first() == MoreAction.SETTINGS && state.fixedVolume) {
                    Text(text = stringResource(R.string.fixed_volume), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), content = {
                    for (action in row) {
                        CinemaAction(
                            label = stringResource(action.label),
                            onClick = { activate(action) },
                            modifier = Modifier.weight(1f).then(navigation(action)),
                            focusHighlight = if (television) false else null
                        )
                    }
                })
                if (row.first() == MoreAction.RETRY && state.lineCount > 0) {
                    Text(text = stringResource(R.string.tv_line_status, state.line, state.lineCount), color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 5.dp))
                }
            }
        }
    )
}

