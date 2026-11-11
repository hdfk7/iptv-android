package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.BackdropBottom
import cn.hdfk7.android.iptv.core.ui.theme.PanelAlt

@Composable
internal fun PlaybackSurface(
    state: PlayerState,
    showPlaybackStatus: Boolean,
    onPlay: (() -> Unit)?,
    modifier: Modifier
): Unit {
    Box(modifier = modifier, contentAlignment = Alignment.Center, content = {
        val cachedFrame = state.cachedPreviewFrame?.takeIf(predicate = { state.playback == PlaybackStatus.IDLE })
        if (cachedFrame != null) {
            Image(
                bitmap = remember(cachedFrame, calculation = { cachedFrame.asImageBitmap() }),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = ContentScale.Fit
            )
        }
        val showPicture = state.previewFrameVisible || cachedFrame != null
        when (state.playback) {
            PlaybackStatus.PLAYING -> Unit
            PlaybackStatus.PAUSED -> {
                if (showPlaybackStatus) {
                    Text(text = stringResource(R.string.paused), color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(14.dp))
                }
            }
            else -> Column(
                modifier = if (showPicture) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().background(Brush.radialGradient(listOf(PanelAlt, BackdropBottom)))
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = {
                    if (!showPlaybackStatus) {
                        return@Column
                    }
                    if (state.playback == PlaybackStatus.LOADING) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Accent, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = stringResource(R.string.loading, state.line, state.lineCount), color = Color.White, fontSize = 16.sp)
                    } else {
                        val playAction = if (onPlay != null) {
                            Modifier.clickable(role = Role.Button, onClick = onPlay)
                        } else {
                            Modifier
                        }
                        Box(
                            modifier = Modifier.size(60.dp).clip(CircleShape).then(playAction)
                                .background(if (showPicture) Color.Black.copy(alpha = 0.6f)
                                    else Color.White.copy(alpha = 0.05f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                            content = {
                                Icon(painter = painterResource(R.drawable.ic_play),
                                    contentDescription = if (onPlay != null) stringResource(R.string.play) else null,
                                    tint = Accent, modifier = Modifier.size(26.dp))
                            }
                        )
                        if (state.playback == PlaybackStatus.ERROR) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = stringResource(R.string.stream_error), color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            )
        }
    })
}
