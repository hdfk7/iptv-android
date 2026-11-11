package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ChannelCategory
import cn.hdfk7.android.iptv.core.model.ChannelFocusMemory
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.theme.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val FOCUS_CHANNEL_SWITCH_DELAY_MS = 150L

private object NoFocusScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

class ChannelListFocus(val memory: ChannelFocusMemory) {
    val requesters = mutableStateMapOf<String, FocusRequester>()
    var restore: (() -> Unit)? = null
    var focusTop: (() -> Unit)? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelPanel(state: PlayerState, onSelect: (Channel) -> Unit, modifier: Modifier, compact: Boolean, television: Boolean, requestInitialFocus: Boolean, onInitialFocus: () -> Unit, selectedRowFocus: ChannelListFocus, videoFocus: FocusRequester, onConfirmSelect: (() -> Unit)? = null, onRight: (() -> Unit)? = null, onLeftToHeader: (() -> Unit)? = null, onTopBoundary: (() -> Unit)? = null, onBottomBoundary: (() -> Unit)? = null, cinema: Boolean = false, domestic: Boolean = false, groupKey: String = "", requestDrawerFocus: Boolean = false, groupControls: @Composable () -> Unit = {}) {
    val favorites = state.activeSourceId == SourceLibrary.FAVORITES_ID
    val selectedIndex = remember(state.channels, state.selectedId, calculation = {
        state.channels.indexOfFirst(predicate = { channel -> channel.id == state.selectedId })
    })
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    var focusSelection by remember(calculation = { mutableStateOf<Job?>(null) })
    var pendingChannel by remember(calculation = { mutableStateOf<Channel?>(null) })
    var focusedChannelId by remember(calculation = { mutableStateOf<String?>(null) })
    var pendingFocusMove by remember(calculation = { mutableStateOf<Job?>(null) })
    val reveal: suspend (Channel?) -> Unit = { target ->
        if (target != null) {
            val index = state.channels.indexOf(target)
            if (index >= 0) {
                listState.revealItem(index = index)
                withTimeoutOrNull(1000, block = {
                    while (isActive) {
                        snapshotFlow(block = { selectedRowFocus.requesters[target.id] }).first(predicate = { requester -> requester != null })
                        withFrameNanos(onFrame = { })
                        if (selectedRowFocus.requesters[target.id]?.requestFocus() == true) {
                            break
                        }
                    }
                })
            }
        }
    }
    SideEffect(effect = {
        selectedRowFocus.restore = {
            scope.launch(block = {
                reveal(selectedRowFocus.memory.target(state.activeSourceId, state.channels, state.selectedId))
            })
        }
    })
    var listHasFocus by remember(calculation = { mutableStateOf(false) })
    var enteringList by remember(calculation = { mutableStateOf(false) })
    val selectChannel: (Channel) -> Unit = { channel ->
        if (channel.id != state.selectedId) {
            onSelect(channel)
        }
    }
    val commitChannel: (Channel) -> Unit = { channel ->
        focusSelection?.cancel()
        pendingChannel = null
        selectChannel(channel)
    }
    LaunchedEffect(requestDrawerFocus, groupKey, state.channels.isNotEmpty(), block = {
        if (requestDrawerFocus && state.channels.isNotEmpty()) {
            val index = selectedIndex.coerceAtLeast(0)
            // The lazy list may still hold the scroll anchor from the previous group.
            listState.scrollToItem(index)
            reveal(state.channels[index])
        }
    })
    LaunchedEffect(state.activeSourceId, groupKey, selectedIndex, state.selectedId, block = {
        if (listHasFocus || requestDrawerFocus) {
            return@LaunchedEffect
        }
        if (selectedIndex >= 0) {
            listState.revealItem(index = selectedIndex)
        } else {
            listState.scrollToItem(0)
        }
    })
    val shape = RoundedCornerShape(if (cinema) 12.dp else 18.dp)
    val panel = if (cinema) {
        Modifier.background(Brush.horizontalGradient(listOf(BackdropBottom.copy(alpha = 0.80f), BackdropBottom.copy(alpha = 0.64f))))
    } else {
        Modifier.background(Panel).border(1.dp, Border, shape)
    }
    Column(
        modifier = modifier.clip(shape).then(panel).padding(if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                    Text(text = if (cinema) stringResource(R.string.tv_channels) else if (compact) stringResource(R.string.channel_count, state.channels.size) else state.channels.size.toString(), color = if (cinema) Accent else Muted, fontSize = if (compact) 12.sp else 14.sp)
                    if (cinema) {
                        val source = state.sources.firstOrNull(predicate = { entry -> entry.id == state.activeSourceId })
                        Text(text = source?.name ?: stringResource(R.string.favorites), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                })
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                }
            })
            if (state.refreshFailed || state.message != null) {
                CatalogMessage(state = state)
            }
            groupControls()
            if (state.channels.isEmpty()) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = {
                    Icon(painter = painterResource(if (favorites) R.drawable.ic_star else R.drawable.ic_source), contentDescription = null, tint = if (favorites) Gold else Muted, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = stringResource(if (favorites) R.string.favorites_empty else R.string.empty), color = Color.White, fontSize = 15.sp)
                })
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScrollSpec) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).onFocusChanged(onFocusChanged = { focus ->
                        listHasFocus = focus.hasFocus
                        if (!focus.hasFocus) {
                            enteringList = true
                            focusedChannelId = null
                            if (pendingFocusMove?.isActive != true) {
                                pendingChannel?.let(block = commitChannel)
                            }
                            focusSelection?.cancel()
                            pendingChannel = null
                            pendingFocusMove?.cancel()
                        }
                    }).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                        val step = when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        val focusedIndex = state.channels.indexOfFirst(predicate = { channel -> channel.id == focusedChannelId })
                        if (focusedIndex < 0) {
                            return@onPreviewKeyEvent false
                        }
                        val targetIndex = focusedIndex + step
                        val target = state.channels.getOrNull(targetIndex)
                        if (target == null) {
                            val boundary = if (step < 0) onTopBoundary else onBottomBoundary
                            if (boundary == null) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && pendingFocusMove?.isActive != true) {
                                boundary()
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyDown && pendingFocusMove?.isActive != true) {
                            focusSelection?.cancel()
                            if (listState.isItemFullyVisible(targetIndex) && selectedRowFocus.requesters[target.id]?.requestFocus() == true) {
                                return@onPreviewKeyEvent true
                            }
                            pendingFocusMove = scope.launch(block = {
                                val info = listState.layoutInfo
                                val lastVisible = info.visibleItemsInfo.lastOrNull()
                                if (lastVisible != null && targetIndex == lastVisible.index + 1) {
                                    listState.scrollBy(value = (lastVisible.size + info.mainAxisItemSpacing).toFloat())
                                }
                                reveal(target)
                            })
                        }
                        true
                    }), verticalArrangement = Arrangement.spacedBy(if (cinema) 2.dp else 6.dp), content = {
                        itemsIndexed(items = state.channels, key = { _, channel -> channel.id }, itemContent = { index, channel ->
                            val displayName = if (domestic) domesticDisplayName(channel) else channel.name
                            ChannelRow(channel = channel, displayName = displayName, index = index, television = television, cinema = cinema, selected = { channel.id == state.selectedId && (cinema || (focusedChannelId == null && !listHasFocus)) }, highlighted = { focusedChannelId == channel.id }, showSource = favorites, requestFocus = requestInitialFocus && index == 0, selectedRowFocus = selectedRowFocus, onInitialFocus = onInitialFocus, onVideoFocus = { if (!cinema) { commitChannel(channel) }; if (onRight != null) { onRight() } else { videoFocus.requestFocus() } }, onLeftToHeader = onLeftToHeader, onFocused = {
                                focusedChannelId = channel.id
                                if (cinema || enteringList) {
                                    enteringList = false
                                } else {
                                    focusSelection?.cancel()
                                    pendingChannel = channel
                                    focusSelection = scope.launch(block = {
                                        delay(FOCUS_CHANNEL_SWITCH_DELAY_MS)
                                        pendingChannel = null
                                        selectChannel(channel)
                                    })
                                }
                            }, onSelect = {
                                val moveToVideo = television && !cinema && channel.id == state.selectedId
                                if (channel.id == state.selectedId && state.playback in setOf(PlaybackStatus.IDLE, PlaybackStatus.ERROR)) {
                                    onSelect(channel)
                                } else {
                                    commitChannel(channel)
                                }
                                onConfirmSelect?.invoke()
                                if (moveToVideo) {
                                    videoFocus.requestFocus()
                                }
                            })
                        })
                    })
                }
            }
        }
    )
}

private fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull(predicate = { visibleItem -> visibleItem.index == index }) ?: return false
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
}

private suspend fun LazyListState.revealItem(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull(predicate = { visibleItem -> visibleItem.index == index })
    if (item == null) {
        scrollToItem(index = index)
        return
    }
    val itemEnd = item.offset + item.size
    val delta = when {
        item.offset < info.viewportStartOffset -> item.offset - info.viewportStartOffset
        itemEnd > info.viewportEndOffset -> itemEnd - info.viewportEndOffset
        else -> 0
    }
    if (delta != 0) {
        scrollBy(value = delta.toFloat())
    }
}

@Composable
private fun ChannelRow(channel: Channel, displayName: String, index: Int, television: Boolean, cinema: Boolean, selected: () -> Boolean, highlighted: () -> Boolean, showSource: Boolean, requestFocus: Boolean, selectedRowFocus: ChannelListFocus, onInitialFocus: () -> Unit, onVideoFocus: () -> Unit, onLeftToHeader: (() -> Unit)?, onFocused: () -> Unit, onSelect: () -> Unit) {
    val categoryLabel = remember(channel, calculation = {
        when (ChannelCategory.classify(channel)) {
            ChannelCategory.CCTV -> R.string.channel_category_cctv
            ChannelCategory.SATELLITE -> R.string.channel_category_satellite
            ChannelCategory.GREATER_CHINA -> R.string.channel_category_greater_china
            ChannelCategory.SPORTS -> R.string.channel_category_sports
            ChannelCategory.OTHER -> R.string.channel_category_other
        }
    })
    val channelFocus = remember(calculation = { FocusRequester() })
    val view = LocalView.current
    val inputMode = LocalInputModeManager.current
    DisposableEffect(key1 = channel.id, effect = {
        selectedRowFocus.requesters[channel.id] = channelFocus
        onDispose(onDisposeEffect = { selectedRowFocus.requesters.remove(channel.id) })
    })
    LaunchedEffect(key1 = requestFocus, block = {
        if (!requestFocus) {
            return@LaunchedEffect
        }
        var waited = 0
        while (isActive && waited < 3000) {
            if (view.hasWindowFocus()) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos(onFrame = { })
                if (runCatching(block = { channelFocus.requestFocus() }).getOrDefault(false)) {
                    onInitialFocus()
                    return@LaunchedEffect
                }
            }
            delay(50)
            waited += 50
        }
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), content = {
        TvAction(modifier = Modifier.fillMaxWidth().height(if (cinema) 40.dp else 58.dp).drawBehind(onDraw = {
            // Lazy rows can recompose separately as they enter the viewport. Resolve the fill when drawing.
            val focused = highlighted()
            val fill = when {
                focused -> if (cinema) FocusFill.copy(alpha = 0.84f) else FocusFill
                selected() -> SelectedPanel
                else -> if (cinema) Color.Transparent else Panel
            }
            val radius = (if (cinema) 8.dp else 16.dp).toPx()
            drawRoundRect(color = fill, cornerRadius = CornerRadius(radius))
            if (focused) {
                val strokeWidth = 1.dp.toPx()
                val inset = strokeWidth / 2
                drawRoundRect(
                    color = if (cinema) Accent else FocusBorder,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(radius - inset),
                    style = Stroke(width = strokeWidth)
                )
            }
        }).focusRequester(channelFocus).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            if (event.key == Key.DirectionRight) {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    onVideoFocus()
                }
                true
            } else if (event.key == Key.DirectionLeft) {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && onLeftToHeader != null) {
                    onLeftToHeader()
                }
                true
            } else {
                false
            }
        }).onFocusChanged(onFocusChanged = { focus ->
            if (focus.isFocused) {
                selectedRowFocus.memory.remember(channel.sourceId, channel.id)
                onFocused()
            }
        }), onClick = onSelect, containerColor = Color.Transparent, focusHighlight = false, showUnfocusedBorder = !cinema, content = {
            val focused = highlighted()
            val playing = selected()
            Text(text = stringResource(R.string.channel_number, index + 1), fontSize = if (television) 12.sp else 11.sp, color = if (focused) FocusInk.copy(alpha = 0.65f) else if (playing) Accent else Muted, modifier = Modifier.width(if (television) 34.dp else 28.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                Text(text = displayName, color = if (focused) FocusInk else Accent, fontSize = if (television) 16.sp else 14.sp, fontWeight = if (playing || focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!cinema) {
                    Text(text = if (showSource) channel.sourceName else stringResource(categoryLabel), color = if (focused) FocusInk.copy(alpha = 0.65f) else if (playing) Accent else Muted, fontSize = if (television) 12.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            })
            if (cinema && playing) {
                Box(modifier = Modifier.size(6.dp).background(FocusBorder, androidx.compose.foundation.shape.CircleShape))
            }
        })
    })
}

@Composable
private fun CatalogMessage(state: PlayerState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = {
        if (state.message != null) {
            Text(text = stringResource(state.message), color = Gold, fontSize = 11.sp)
        }
        if (state.refreshFailed) {
            Text(text = stringResource(if (state.channels.isEmpty()) R.string.refresh_error else R.string.cached), color = Gold, fontSize = 11.sp)
        } else if (state.activeSourceId == SourceLibrary.FAVORITES_ID) {
            Text(text = stringResource(R.string.source_aggregate), color = Muted, fontSize = 11.sp)
        } else if (state.updatedAt != null) {
            val timestamp = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.updatedAt))
            Text(text = stringResource(R.string.updated, timestamp), color = Muted, fontSize = 11.sp)
        }
        if (state.stale) {
            Text(text = stringResource(R.string.stale), color = Gold, fontSize = 11.sp)
        }
    })
}
