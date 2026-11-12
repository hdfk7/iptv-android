package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal fun tvPanelNavigation(
    enabled: Boolean,
    onMove: (horizontal: Int, vertical: Int) -> Unit,
    onConfirm: () -> Unit
): Modifier {
    val focus = remember(calculation = { FocusRequester() })
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current
    var confirmPress by remember(enabled, windowFocused, calculation = { mutableLongStateOf(-1L) })
    LaunchedEffect(enabled, windowFocused, block = {
        if (enabled && windowFocused) {
            inputMode.requestInputMode(InputMode.Keyboard)
            focus.requestWhenReady()
        }
    })
    if (!enabled) {
        return Modifier
    }
    return Modifier.focusRequester(focus).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val native = event.nativeKeyEvent
                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                    confirmPress = native.downTime
                } else if (event.type == KeyEventType.KeyUp && confirmPress == native.downTime) {
                    confirmPress = -1L
                    onConfirm()
                }
                true
            }
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                if (event.type == KeyEventType.KeyDown) {
                    val horizontal = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    val vertical = when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> 0
                    }
                    onMove(horizontal, vertical)
                }
                true
            }
            else -> false
        }
    }).focusable()
}
