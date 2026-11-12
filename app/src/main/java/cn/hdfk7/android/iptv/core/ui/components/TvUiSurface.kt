package cn.hdfk7.android.iptv.core.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import cn.hdfk7.android.iptv.R

@Composable
internal fun TvUiSurface(modifier: Modifier, content: @Composable () -> Unit): Unit {
    if (requiresSoftwareTvUiRendering(LocalConfiguration.current)) {
        val parentComposition = rememberCompositionContext()
        val currentContent = rememberUpdatedState(content)
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val surface = ComposeView(context)
                surface.id = R.id.tv_ui_surface
                surface.setParentCompositionContext(parentComposition)
                // Keep the video SurfaceView outside this software-rendered UI surface.
                surface.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                surface.setContent(content = { currentContent.value() })
                surface
            },
            onRelease = { surface -> surface.disposeComposition() }
        )
    } else {
        Box(modifier = modifier, content = { content() })
    }
}
