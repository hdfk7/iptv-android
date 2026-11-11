package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface
import cn.hdfk7.android.iptv.core.ui.theme.FocusFill
import cn.hdfk7.android.iptv.core.ui.theme.FocusInk
import cn.hdfk7.android.iptv.core.ui.theme.Muted
import cn.hdfk7.android.iptv.core.ui.theme.SelectedPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ProgrammeScheduleDialog(
    channelName: String,
    programmes: List<Programme>,
    television: Boolean,
    onClose: () -> Unit
): Unit {
    val now = System.currentTimeMillis()
    val closeFocus = remember(calculation = { FocusRequester() })
    val timeFormat = remember(calculation = { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) })
    val firstProgramme = programmes.indexOfFirst(predicate = { item -> item.endsAt > now }).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = firstProgramme)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false), content = {
        LaunchedEffect(television, block = {
            if (television) {
                withFrameNanos(onFrame = { _ -> })
                closeFocus.requestFocus()
            }
        })
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                .width(560.dp).heightIn(max = 480.dp).background(DialogSurface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = {
                        Text(text = stringResource(R.string.guide_title, channelName), color = Color.White,
                            modifier = Modifier.weight(1f))
                        TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.close),
                            onClick = onClose, modifier = Modifier.focusRequester(closeFocus))
                    })
                if (programmes.isEmpty()) {
                    Text(text = stringResource(R.string.guide_empty), color = Muted)
                } else {
                    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp), content = {
                            items(programmes, itemContent = { item ->
                                var focused by remember(calculation = { mutableStateOf(false) })
                                val current = item.startsAt <= now && now < item.endsAt
                                val rowFocus = if (television) {
                                    Modifier.onFocusChanged(onFocusChanged = { focus -> focused = focus.isFocused }).focusable()
                                } else {
                                    Modifier
                                }
                                val background = when {
                                    focused -> FocusFill
                                    current -> SelectedPanel
                                    else -> Color.Transparent
                                }
                                val color = if (focused) FocusInk else Color.White
                                Row(
                                    modifier = Modifier.fillMaxWidth().then(rowFocus)
                                        .background(background, RoundedCornerShape(8.dp)).padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    content = {
                                        Text(text = timeFormat.format(Instant.ofEpochMilli(item.startsAt)), color = color)
                                        Text(text = if (current) stringResource(R.string.programme_current, item.title) else item.title,
                                            color = color, modifier = Modifier.weight(1f))
                                    }
                                )
                            })
                        })
                }
            }
        )
    })
}
