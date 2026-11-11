package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Programme
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.requestWhenReady
import cn.hdfk7.android.iptv.core.ui.theme.CinemaMenuSurface
import cn.hdfk7.android.iptv.core.ui.theme.FocusBorder
import cn.hdfk7.android.iptv.core.ui.theme.FocusCornerRadius
import cn.hdfk7.android.iptv.core.ui.theme.FocusFill
import cn.hdfk7.android.iptv.core.ui.theme.FocusStroke
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
    val currentIndex = programmes.indexOfFirst(predicate = { item -> item.startsAt <= now && now < item.endsAt })
    val firstProgramme = programmes.indexOfFirst(predicate = { item -> item.endsAt > now }).coerceAtLeast(0)
    val timeFormat = remember(calculation = { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) })
    val list = rememberLazyListState(initialFirstVisibleItemIndex = firstProgramme)
    val panelFocus = remember(calculation = { FocusRequester() })
    // -1 is the Close button; programme rows never own native TV focus.
    var focusedIndex by remember(programmes, calculation = { mutableIntStateOf(if (programmes.isEmpty()) -1 else firstProgramme) })
    var panelCoordinates by remember(calculation = { mutableStateOf<LayoutCoordinates?>(null) })
    val itemCoordinates = remember(calculation = { mutableStateMapOf<Int, LayoutCoordinates>() })
    fun bounds(index: Int): Rect? {
        val panel = panelCoordinates
        val item = itemCoordinates[index]
        return if (panel?.isAttached == true && item?.isAttached == true) {
            panel.localBoundingBoxOf(item, clipBounds = true)
        } else {
            null
        }
    }
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
                .onGloballyPositioned(onGloballyPositioned = { coordinates -> panelCoordinates = coordinates })
                .background(CinemaMenuSurface, RoundedCornerShape(22.dp))
                .drawWithContent(onDraw = {
                    val radius = CornerRadius(FocusCornerRadius.toPx())
                    val current = bounds(currentIndex)
                    if (currentIndex >= 0 && current != null) {
                        drawRoundRect(color = SelectedPanel, topLeft = current.topLeft, size = current.size, cornerRadius = radius)
                    }
                    val focused = if (television) bounds(focusedIndex) else null
                    val focusedRadius = if (focusedIndex == -1 && focused != null) CornerRadius(focused.width / 2) else radius
                    if (focused != null) {
                        drawRoundRect(color = FocusFill, topLeft = focused.topLeft, size = focused.size, cornerRadius = focusedRadius)
                    }
                    drawContent()
                    if (focused != null) {
                        drawRoundRect(color = FocusBorder, topLeft = focused.topLeft, size = focused.size,
                            cornerRadius = focusedRadius, style = Stroke(width = FocusStroke.toPx()))
                    }
                }).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                    Text(text = stringResource(R.string.guide_title, channelName), color = Color.White, modifier = Modifier.weight(1f))
                    TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.close), onClick = onClose,
                        modifier = Modifier.focusProperties(scope = { canFocus = !television })
                            .onGloballyPositioned(onGloballyPositioned = { coordinates -> itemCoordinates[-1] = coordinates })
                            .semantics(properties = { selected = television && focusedIndex == -1 }))
                })
                if (programmes.isEmpty()) {
                    Text(text = stringResource(R.string.guide_empty), color = Muted)
                } else {
                    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(4.dp), content = {
                            itemsIndexed(items = programmes, itemContent = { index, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .onGloballyPositioned(onGloballyPositioned = { coordinates -> itemCoordinates[index] = coordinates })
                                        .semantics(mergeDescendants = true, properties = { selected = television && focusedIndex == index })
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    content = {
                                        Text(text = timeFormat.format(Instant.ofEpochMilli(item.startsAt)), color = Color.White)
                                        Text(text = if (index == currentIndex) stringResource(R.string.programme_current, item.title) else item.title,
                                            color = Color.White, modifier = Modifier.weight(1f))
                                    }
                                )
                            })
                        })
                }
            }
        )
    })
}
