package cn.hdfk7.android.iptv.feature.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** Keep the video surface attached while opening and closing controls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlaybackVideo(viewModel: PlayerViewModel): Unit {
    var surface by remember(calculation = { mutableStateOf<SurfaceView?>(null) })
    val request = viewModel.previewFrameRequest
    LaunchedEffect(surface, request, block = {
        val videoSurface = surface ?: return@LaunchedEffect
        if (request == null) {
            return@LaunchedEffect
        }
        val sample = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(sample.width * sample.height)
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        do {
            if (copyFrame(videoSurface, sample, handler)) {
                sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                val visiblePixels = pixels.count(predicate = { color ->
                    maxOf((color shr 16) and 255, (color shr 8) and 255, color and 255) > 24
                })
                if (visiblePixels >= pixels.size / 20) {
                    viewModel.finishPreviewFrame(request, visible = true)
                    return@LaunchedEffect
                }
                viewModel.skipBlackPreviewFrame(request)
            }
            delay(80)
        } while (SystemClock.elapsedRealtime() < deadline)
        viewModel.finishPreviewFrame(request, visible = false)
    })
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val video = PlayerView(context)
            video.useController = false
            video.isFocusable = false
            video.isFocusableInTouchMode = false
            surface = video.videoSurfaceView as? SurfaceView
            video
        },
        onRelease = { video -> video.player = null },
        update = { video -> video.player = viewModel.player }
    )
}

private suspend fun copyFrame(surface: SurfaceView, sample: Bitmap, handler: Handler): Boolean {
    return suspendCancellableCoroutine(block = { continuation ->
        try {
            PixelCopy.request(surface, sample, { result -> continuation.resume(result == PixelCopy.SUCCESS) }, handler)
        } catch (exception: IllegalArgumentException) {
            continuation.resume(false)
        }
    })
}
