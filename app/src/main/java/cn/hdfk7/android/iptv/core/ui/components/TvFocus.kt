package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun FocusRequester.requestWhenReady(): Boolean {
    return withTimeoutOrNull(1000, block = {
        var focused = runCatching(block = { requestFocus() }).getOrDefault(false)
        while (!focused) {
            withFrameNanos(onFrame = { _ -> })
            focused = runCatching(block = { requestFocus() }).getOrDefault(false)
        }
        focused
    }) == true
}
