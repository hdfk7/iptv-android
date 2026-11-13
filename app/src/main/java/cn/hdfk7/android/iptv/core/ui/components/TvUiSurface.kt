package cn.hdfk7.android.iptv.core.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import cn.hdfk7.android.iptv.R

@Composable
internal fun TvUiSurface(television: Boolean, modifier: Modifier, content: @Composable () -> Unit): Unit {
    if (television) {
        val softwareRendering = LocalTvRenderingCompatibility.current
        val parentComposition = rememberCompositionContext()
        val currentContent = rememberUpdatedState(content)
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val surface = ComposeView(context)
                surface.id = R.id.tv_ui_surface
                surface.setParentCompositionContext(parentComposition)
                // Keep the video SurfaceView outside this software-rendered UI surface.
                surface.setContent(content = { currentContent.value() })
                surface
            },
            onRelease = { surface -> surface.disposeComposition() },
            update = { surface ->
                val layer = if (softwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE
                if (surface.layerType != layer) {
                    surface.setLayerType(layer, null)
                }
            }
        )
    } else {
        Box(modifier = modifier, content = { content() })
    }
}
