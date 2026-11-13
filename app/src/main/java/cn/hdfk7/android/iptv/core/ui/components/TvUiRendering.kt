package cn.hdfk7.android.iptv.core.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView

internal val LocalTvRenderingCompatibility = compositionLocalOf(defaultFactory = { false })

@Composable
internal fun ConfigureTvUiRendering(): Unit {
    val view = LocalView.current
    val enabled = LocalTvRenderingCompatibility.current
    DisposableEffect(view, enabled, effect = {
        val previousLayerType = view.layerType
        if (enabled) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        onDispose(onDisposeEffect = {
            if (enabled) {
                view.setLayerType(previousLayerType, null)
            }
        })
    })
}
