package cn.hdfk7.android.iptv.core.ui.components

import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

@Composable
internal fun ConfigureTvUiRendering(): Unit {
    val view = LocalView.current
    val affected = requiresSoftwareTvUiRendering(LocalConfiguration.current)
    DisposableEffect(view, affected, effect = {
        val previousLayerType = view.layerType
        if (affected) {
            // This MiTV retains stale HWUI focus layers. Only rasterize its Compose UI;
            // video continues on its independent SurfaceView surface.
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        onDispose(onDisposeEffect = {
            if (affected) {
                view.setLayerType(previousLayerType, null)
            }
        })
    })
}

internal fun requiresSoftwareTvUiRendering(configuration: Configuration): Boolean {
    val television = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    return television && Build.MODEL == "MiTV-MFFU0"
}
