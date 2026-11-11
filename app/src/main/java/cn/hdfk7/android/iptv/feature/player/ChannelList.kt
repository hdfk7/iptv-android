package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.ProgrammePreview
import cn.hdfk7.android.iptv.core.model.channelFocusTarget
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.tvFocusBackground
import cn.hdfk7.android.iptv.core.ui.theme.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private object NoFocusScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

internal class ChannelListFocus {
    val listStates = mutableMapOf<Pair<String, String>, LazyListState>()
    val requesters = mutableStateMapOf<String, FocusRequester>()
    var restoreRequest: Long by mutableLongStateOf(0L)
        private set

    fun restore(): Unit {
        restoreRequest += 1L
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelPanel(
    state: PlayerState,
    channels: List<Channel>,
    onSelect: (Channel) -> Unit,
    modifier: Modifier,
    television: Boolean,
    selectedRowFocus: ChannelListFocus,
    onConfirmSelect: () -> Unit,
    onRight: () -> Unit,
    onLeftToHeader: () -> Unit,
    onBottomBoundary: () -> Unit,
    domestic: Boolean,
    groupKey: String,
    requestDrawerFocus: Boolean,
    onChannelFocused: (Channel) -> Unit,
    groupControls: @Composable () -> Unit
): Unit {
    val favorites = state.activeSourceId == SourceLibrary.FAVORITES_ID
    val selectedIndex = remember(channels, state.selectedId, calculation = {
        channels.indexOfFirst(predicate = { channel -> channel.id == state.selectedId })
    })
    val listState = remember(state.activeSourceId, groupKey, selectedRowFocus, calculation = {
        selectedRowFocus.listStates.getOrPut(state.activeSourceId to groupKey, defaultValue = {
            LazyListState(firstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
        })
    })
    val scope = rememberCoroutineScope()
    var focusedChannelId by remember(calculation = { mutableStateOf<String?>(null) })
    var pendingFocusMove by remember(calculation = { mutableStateOf<Job?>(null) })
    val reveal: suspend (Channel?) -> Unit = { target ->
        if (target != null) {
            val index = channels.indexOf(target)
            if (index >= 0) {
                listState.revealItem(index = index)
                withTimeoutOrNull(1000, block = {
                    while (isActive) {
                        snapshotFlow(block = { selectedRowFocus.requesters[target.id] }).first(predicate = { requester -> requester != null })
                        withFrameNanos(onFrame = { })
                        if (runCatching(block = { selectedRowFocus.requesters[target.id]?.requestFocus() == true }).getOrDefault(false)) {
                            focusedChannelId = target.id
                            break
                        }
                    }
                })
            }
        }
    }
    LaunchedEffect(requestDrawerFocus, groupKey, channels.isNotEmpty(), selectedRowFocus.restoreRequest, block = {
        if (requestDrawerFocus && channels.isNotEmpty()) {
            val target = channelFocusTarget(channels, state.selectedId)
            reveal(target)
        }
    })
    val shape = RoundedCornerShape(12.dp)
    val panel = Modifier.background(Brush.horizontalGradient(listOf(
        BackdropBottom.copy(alpha = 0.80f), BackdropBottom.copy(alpha = 0.64f)
    )))
    Column(
        modifier = modifier.clip(shape).then(panel).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                    Text(text = stringResource(R.string.tv_channels), color = Accent, fontSize = 12.sp)
                    val source = state.sources.firstOrNull(predicate = { entry -> entry.id == state.activeSourceId })
                    Text(text = source?.name ?: stringResource(R.string.favorites), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                }
            })
            if (state.refreshFailed || state.message != null) {
                CatalogMessage(state = state, hasChannels = channels.isNotEmpty())
            }
            groupControls()
            if (channels.isEmpty()) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = {
                    Icon(painter = painterResource(if (favorites) R.drawable.ic_star else R.drawable.ic_source), contentDescription = null, tint = if (favorites) Gold else Muted, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = stringResource(if (favorites) R.string.favorites_empty else R.string.empty), color = Color.White, fontSize = 15.sp)
                })
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScrollSpec, content = {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).onFocusChanged(onFocusChanged = { focus ->
                        if (!focus.hasFocus) {
                            focusedChannelId = null
                            pendingFocusMove?.cancel()
                        }
                    }).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
                        val step = when (event.key) {
                            Key.DirectionUp -> -1
                            Key.DirectionDown -> 1
                            else -> return@onPreviewKeyEvent false
                        }
                        val focusedIndex = channels.indexOfFirst(predicate = { channel -> channel.id == focusedChannelId })
                        if (focusedIndex < 0) {
                            return@onPreviewKeyEvent false
                        }
                        val targetIndex = focusedIndex + step
                        val target = channels.getOrNull(targetIndex)
                        if (target == null) {
                            val boundary = if (step < 0) onLeftToHeader else onBottomBoundary
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && pendingFocusMove?.isActive != true) {
                                boundary()
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyDown && pendingFocusMove?.isActive != true) {
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
                    }), verticalArrangement = Arrangement.spacedBy(2.dp), content = {
                        itemsIndexed(items = channels, key = { _, channel -> channel.id }, itemContent = { index, channel ->
                            val displayName = if (domestic) domesticDisplayName(channel) else channel.name
                            ChannelRow(
                                channel = channel,
                                displayName = displayName,
                                preview = state.programmePreviews[channel.id],
                                index = index,
                                television = television,
                                selected = { channel.id == state.selectedId },
                                highlighted = { focusedChannelId == channel.id },
                                selectedRowFocus = selectedRowFocus,
                                onRight = onRight,
                                onLeftToHeader = onLeftToHeader,
                                onFocused = {
                                    focusedChannelId = channel.id
                                    onChannelFocused(channel)
                                },
                                onSelect = {
                                    if (channel.id != state.selectedId || state.playback in setOf(PlaybackStatus.IDLE, PlaybackStatus.ERROR)) {
                                        onSelect(channel)
                                    }
                                    onConfirmSelect()
                                }
                            )
                        })
                    })
                })
            }
        }
    )
}

private fun LazyListState.isItemFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull(predicate = { visibleItem -> visibleItem.index == index }) ?: return false
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
}

internal suspend fun LazyListState.revealItem(index: Int): Unit {
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
private fun ChannelRow(
    channel: Channel,
    displayName: String,
    preview: ProgrammePreview?,
    index: Int,
    television: Boolean,
    selected: () -> Boolean,
    highlighted: () -> Boolean,
    selectedRowFocus: ChannelListFocus,
    onRight: () -> Unit,
    onLeftToHeader: () -> Unit,
    onFocused: () -> Unit,
    onSelect: () -> Unit
): Unit {
    val channelFocus = remember(calculation = { FocusRequester() })
    DisposableEffect(key1 = channel.id, effect = {
        selectedRowFocus.requesters[channel.id] = channelFocus
        onDispose(onDisposeEffect = {
            if (selectedRowFocus.requesters[channel.id] === channelFocus) {
                selectedRowFocus.requesters.remove(channel.id)
            }
        })
    })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), content = {
        val hasPreview = preview != null
        val rowHeight = if (hasPreview) {
            Modifier.heightIn(min = 72.dp)
        } else {
            Modifier.height(40.dp)
        }
        TvAction(modifier = Modifier.fillMaxWidth().then(rowHeight).tvFocusBackground(focused = highlighted, selected = selected, borderColor = Accent).focusRequester(channelFocus).onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            if (event.key == Key.DirectionRight) {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    onRight()
                }
                true
            } else if (event.key == Key.DirectionLeft) {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    onLeftToHeader()
                }
                true
            } else {
                false
            }
        }).onFocusChanged(onFocusChanged = { focus ->
            if (focus.isFocused) {
                onFocused()
            }
        }), onClick = onSelect, containerColor = Color.Transparent, focusHighlight = false, showUnfocusedBorder = false, fitContentHeight = hasPreview, content = {
            val focused = highlighted()
            val playing = selected()
            val titleBaseline = if (hasPreview) Modifier.alignByBaseline() else Modifier
            Text(text = stringResource(R.string.channel_number, index + 1), fontSize = if (television) 12.sp else 11.sp, color = if (focused) FocusInk.copy(alpha = 0.65f) else if (playing) Accent else Muted, modifier = titleBaseline.width(if (television) 34.dp else 28.dp))
            Column(modifier = titleBaseline.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
                Text(text = displayName, color = if (focused) FocusInk else Accent, fontSize = if (television) 16.sp else 14.sp, lineHeight = if (hasPreview) 20.sp else androidx.compose.ui.unit.TextUnit.Unspecified, fontWeight = if (playing || focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preview != null) {
                    val guideColor = if (focused) FocusInk.copy(alpha = 0.75f) else Muted
                    preview.current?.let(block = { title ->
                        Text(text = stringResource(R.string.programme_current, title), color = guideColor, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                    preview.next?.let(block = { title ->
                        Text(text = stringResource(R.string.programme_next, title), color = guideColor, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                }
            })
            if (playing) {
                Box(modifier = Modifier.size(6.dp).background(FocusBorder, androidx.compose.foundation.shape.CircleShape))
            }
        })
    })
}

@Composable
private fun CatalogMessage(state: PlayerState, hasChannels: Boolean): Unit {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = {
        if (state.message != null) {
            Text(text = stringResource(state.message), color = Gold, fontSize = 11.sp)
        }
        if (state.refreshFailed) {
            Text(text = stringResource(if (hasChannels) R.string.cached else R.string.refresh_error), color = Gold, fontSize = 11.sp)
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
