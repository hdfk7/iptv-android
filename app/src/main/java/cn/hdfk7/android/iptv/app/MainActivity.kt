package cn.hdfk7.android.iptv.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.hdfk7.android.iptv.core.ui.components.ExitHint
import cn.hdfk7.android.iptv.core.ui.components.LocalTvRenderingCompatibility
import cn.hdfk7.android.iptv.core.ui.theme.IptvTheme
import cn.hdfk7.android.iptv.feature.source.SourceManagerViewModel
import cn.hdfk7.android.iptv.feature.player.PlayerScreen
import cn.hdfk7.android.iptv.feature.player.PlayerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()
    private val sourceViewModel: SourceManagerViewModel by viewModels()
    private var television = false
    private var lastBackPressTime = 0L
    private var playerDialogOpen = false
    private var exitHintTime by mutableLongStateOf(0L)

    private companion object {
        const val DOUBLE_BACK_INTERVAL_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        if (television) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!television) {
            // Let the app gradient draw behind the bars; Compose adds the insets back as padding.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
        }
        setContent(content = {
            if (!television && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutoutMode = if (viewModel.fillVideoScreen) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                SideEffect {
                    val attributes = window.attributes
                    if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                        attributes.layoutInDisplayCutoutMode = cutoutMode
                        window.attributes = attributes
                    }
                }
            }
            LaunchedEffect(key1 = Unit, block = {
                insets.hide(WindowInsetsCompat.Type.systemBars())
            })
            IptvTheme(content = {
                LaunchedEffect(key1 = exitHintTime, block = {
                    if (exitHintTime != 0L) {
                        delay(DOUBLE_BACK_INTERVAL_MS)
                        exitHintTime = 0L
                    }
                })
                Box(modifier = Modifier.fillMaxSize(), content = {
                    CompositionLocalProvider(
                        LocalTvRenderingCompatibility provides (television && viewModel.tvRenderingCompatibility),
                        content = {
                            PlayerScreen(viewModel = viewModel, sourceViewModel = sourceViewModel, television = television,
                                onDialogStateChanged = { open -> playerDialogOpen = open })
                        }
                    )
                    ExitHint(visible = exitHintTime != 0L)
                })
            })
        })
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
        viewModel.refresh()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    // Intercept TV BACK before focused Compose controls; this is Activity's public callback.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (television && !playerDialogOpen && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                viewModel.requestMoreMenu()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerDialogOpen) {
                return super.dispatchKeyEvent(event)
            }
            if (television) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (viewModel.state.value.infoVisible) {
                        lastBackPressTime = 0L
                        exitHintTime = 0L
                        onBackPressedDispatcher.onBackPressed()
                        return true
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (lastBackPressTime != 0L && now - lastBackPressTime < DOUBLE_BACK_INTERVAL_MS) {
                        finish()
                    } else {
                        lastBackPressTime = now
                        exitHintTime = now
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = keyCode in listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        if (handled) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> {
                        viewModel.changeChannel(1)
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        viewModel.changeChannel(-1)
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        viewModel.togglePlayback()
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        viewModel.setPlayback(true)
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        viewModel.setPlayback(false)
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

}
