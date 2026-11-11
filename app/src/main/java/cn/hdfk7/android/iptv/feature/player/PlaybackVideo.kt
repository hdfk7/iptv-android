package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

/** Keep the video surface attached while opening and closing controls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlaybackVideo(viewModel: PlayerViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val video = PlayerView(context)
            video.useController = false
            video.isFocusable = false
            video.isFocusableInTouchMode = false
            video
        },
        onRelease = { video -> video.player = null },
        update = { video -> video.player = viewModel.player }
    )
}
