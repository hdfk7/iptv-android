package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.model.programmeCurrentIndex
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.requestWhenReady
import cn.hdfk7.android.iptv.core.ui.components.tvFocusBackground
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.CinemaMenuSurface
import cn.hdfk7.android.iptv.core.ui.theme.Muted
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
    val currentIndex = programmeCurrentIndex(programmes, now)
    val firstProgramme = if (currentIndex >= 0) {
        currentIndex
    } else {
        programmes.indexOfFirst(predicate = { item -> item.endsAt > now }).coerceAtLeast(0)
    }
    val timeFormat = remember(calculation = { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) })
    val rowPadding = 10.dp
    val list = rememberLazyListState(initialFirstVisibleItemIndex = firstProgramme)
    val panelFocus = remember(calculation = { FocusRequester() })
    // -1 is the Close button; programme rows never own native TV focus.
    var focusedIndex by remember(programmes, calculation = { mutableIntStateOf(if (programmes.isEmpty()) -1 else firstProgramme) })
    LaunchedEffect(television, programmes, focusedIndex, block = {
        if (television && focusedIndex >= 0) {
            list.revealItem(focusedIndex)
        }
    })
    val navigation = if (television) {
        Modifier.focusRequester(panelFocus).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            when (event.key) {
                Key.DirectionUp, Key.DirectionDown -> {
                    if (event.type == KeyEventType.KeyDown) {
                        val step = if (event.key == Key.DirectionUp) -1 else 1
                        focusedIndex = (focusedIndex + step).coerceIn(-1, programmes.lastIndex)
                    }
                    true
                }
                Key.DirectionLeft -> {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        onClose()
                    }
                    true
                }
                Key.DirectionRight -> true
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (focusedIndex == -1 && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        onClose()
                    }
                    true
                }
                else -> false
            }
        }).focusable()
    } else {
        Modifier
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false), content = {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val inputMode = LocalInputModeManager.current
        val focusManager = LocalFocusManager.current
        LaunchedEffect(television, windowFocused, block = {
            if (television && windowFocused) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos(onFrame = { _ -> })
                focusManager.clearFocus(force = true)
                withFrameNanos(onFrame = { _ -> })
                panelFocus.requestWhenReady()
            }
        })
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                .width(560.dp).heightIn(max = 480.dp).then(navigation)
                .background(CinemaMenuSurface, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                    DialogTitle(text = stringResource(R.string.guide_title, channelName),
                        modifier = Modifier.weight(1f).padding(start = rowPadding))
                    val closeNavigation = if (television) {
                        Modifier.focusProperties(scope = { canFocus = false })
                            .semantics(properties = { selected = focusedIndex == -1 })
                    } else {
                        Modifier
                    }
                    TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.close), onClick = onClose,
                        modifier = closeNavigation, focusHighlight = if (television) focusedIndex == -1 else null)
                })
                if (programmes.isEmpty()) {
                    Text(text = stringResource(R.string.guide_empty), color = Muted)
                } else {
                    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp), content = {
                            itemsIndexed(items = programmes, itemContent = { index, item ->
                                val current = index == currentIndex
                                val focusSemantics = if (television) {
                                    Modifier.semantics(mergeDescendants = true, properties = { selected = focusedIndex == index })
                                } else {
                                    Modifier
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().tvFocusBackground(
                                        focused = { television && focusedIndex == index },
                                        selected = { current },
                                        borderColor = Accent
                                    ).then(focusSemantics).padding(rowPadding),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    content = {
                                        Text(text = timeFormat.format(Instant.ofEpochMilli(item.startsAt)), color = Color.White,
                                            modifier = Modifier.alignByBaseline().widthIn(min = 44.dp),
                                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                                        Text(text = if (current) stringResource(R.string.programme_current, item.title) else item.title,
                                            color = Color.White, modifier = Modifier.weight(1f).alignByBaseline(),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                )
                            })
                        })
                }
            }
        )
    })
}
