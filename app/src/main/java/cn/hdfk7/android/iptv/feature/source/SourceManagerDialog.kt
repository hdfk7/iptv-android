package cn.hdfk7.android.iptv.feature.source

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton

@Composable
fun SourceManagerDialog(viewModel: SourceManagerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    Box(content = {
        if (state.open) {
            Dialog(onDismissRequest = viewModel::back, properties = DialogProperties(usePlatformDefaultWidth = false), content = {
                Column(modifier = Modifier.width(710.dp).heightIn(max = 460.dp).background(DialogSurface, RoundedCornerShape(22.dp)).border(1.dp, Border, RoundedCornerShape(22.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = {
                    when {
                        state.draft != null -> {
                            SourceEditor(state = state, viewModel = viewModel)
                        }
                        state.deleting != null -> {
                            SourceDelete(state = state, viewModel = viewModel)
                        }
                        else -> {
                            SourceList(state = state, library = catalog.library, viewModel = viewModel)
                        }
                    }
                })
            })
        }
    })
}

@Composable
private fun SourceList(state: SourceManagerState, library: SourceLibrary, viewModel: SourceManagerViewModel) {
    val inputMode = LocalInputModeManager.current
    val initialFocus = remember(calculation = { FocusRequester() })
    val listState = rememberLazyListState()
    val activeIndex = remember(library.activeSourceId, library.sources) {
        val index = library.sources.indexOfFirst(predicate = { source -> source.id == library.activeSourceId })
        if (library.activeSourceId == SourceLibrary.FAVORITES_ID || index < 0) 0 else index + 1
    }
    LaunchedEffect(key1 = Unit, block = {
        inputMode.requestInputMode(InputMode.Keyboard)
        runCatching(block = { listState.scrollToItem(activeIndex) })
        withFrameNanos(onFrame = { })
        runCatching(block = { initialFocus.requestFocus() })
    })
    Column(modifier = Modifier.height(408.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = {
        DialogHeader(title = stringResource(R.string.library_title), onClose = viewModel::back)
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = {
            item(key = SourceLibrary.FAVORITES_ID, content = {
                SourceCard(name = stringResource(R.string.favorites), detail = library.favorites.distinctBy(selector = { channel -> channel.key }).size.toString(), active = library.activeSourceId == SourceLibrary.FAVORITES_ID, busy = state.busy, onActivate = viewModel::showFavorites, modifier = if (activeIndex == 0) Modifier.focusRequester(initialFocus) else Modifier)
            })
            itemsIndexed(items = library.sources, key = { _, source -> source.id }, itemContent = { index, source ->
                SourceCard(name = source.name, detail = source.url, active = library.activeSourceId == source.id, busy = state.busy, onActivate = { viewModel.activate(source.id) }, onEdit = { viewModel.edit(source) }, onDelete = { viewModel.askDelete(source) }, modifier = if (activeIndex == index + 1) Modifier.focusRequester(initialFocus) else Modifier)
            })
        })
        SourceError(error = state.error)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
            TvIconButton(description = stringResource(R.string.source_add), icon = R.drawable.ic_add, onClick = viewModel::add, enabled = !state.busy)
            if (library.sources.isEmpty()) {
                TvAction(onClick = viewModel::restoreDefaults, enabled = !state.busy, containerColor = ButtonPanel, contentPadding = PaddingValues(horizontal = 16.dp), content = {
                    Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.source_restore), fontSize = 14.sp)
                })
            }
            Spacer(modifier = Modifier.weight(1f))
        })
    })
}

@Composable
private fun SourceCard(name: String, detail: String, active: Boolean, busy: Boolean, onActivate: () -> Unit, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().background(if (active) SelectedPanel else PanelAlt, RoundedCornerShape(14.dp)).border(1.dp, if (active) Accent.copy(alpha = 0.4f) else Border, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), content = {
            Text(text = name, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = detail, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        })
        TvIconButton(description = stringResource(if (active) R.string.source_current else R.string.source_use), icon = if (active) R.drawable.ic_check else R.drawable.ic_play, onClick = onActivate, selected = active, enabled = !busy, modifier = modifier)
        if (onEdit != null) {
            TvIconButton(icon = R.drawable.ic_edit, description = stringResource(R.string.source_edit), onClick = onEdit, enabled = !busy)
        }
        if (onDelete != null) {
            TvIconButton(icon = R.drawable.ic_delete, description = stringResource(R.string.source_delete), onClick = onDelete, enabled = !busy)
        }
        if (onEdit == null && onDelete == null) {
            Spacer(modifier = Modifier.width(98.dp))
        }
    })
}

@Composable
private fun SourceEditor(state: SourceManagerState, viewModel: SourceManagerViewModel) {
    val inputMode = LocalInputModeManager.current
    val draft = state.draft ?: return
    val nameFocus = remember(calculation = { FocusRequester() })
    LaunchedEffect(key1 = draft.id, block = { inputMode.requestInputMode(InputMode.Keyboard); nameFocus.requestFocus() })
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = {
        DialogHeader(title = stringResource(if (draft.id == null) R.string.source_add else R.string.source_edit), onClose = viewModel::back)
        OutlinedTextField(value = draft.name, onValueChange = viewModel::editName, label = { Text(text = stringResource(R.string.source_name)) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus))
        OutlinedTextField(value = draft.address, onValueChange = viewModel::editAddress, label = { Text(text = stringResource(R.string.source_address)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        SourceError(error = state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
            TvIconButton(description = stringResource(if (state.busy) R.string.saving else R.string.save), icon = R.drawable.ic_check, onClick = viewModel::save, selected = true, enabled = !state.busy)
            TvIconButton(description = stringResource(R.string.cancel), icon = R.drawable.ic_close, onClick = viewModel::back, enabled = !state.busy)
        })
    })
}

@Composable
private fun SourceDelete(state: SourceManagerState, viewModel: SourceManagerViewModel) {
    val inputMode = LocalInputModeManager.current
    val source = state.deleting ?: return
    val cancelFocus = remember(calculation = { FocusRequester() })
    LaunchedEffect(key1 = source.id, block = { inputMode.requestInputMode(InputMode.Keyboard); cancelFocus.requestFocus() })
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = {
        DialogHeader(title = stringResource(R.string.source_delete_title, source.name), onClose = viewModel::back)
        SourceError(error = state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
            TvIconButton(description = stringResource(R.string.cancel), icon = R.drawable.ic_close, onClick = viewModel::back, modifier = Modifier.focusRequester(cancelFocus), enabled = !state.busy)
            TvIconButton(description = stringResource(R.string.source_delete), icon = R.drawable.ic_delete, onClick = viewModel::delete, enabled = !state.busy)
        })
    })
}

@Composable
private fun DialogHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp), content = {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), content = {
            Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 25.sp)
        })
        TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.close), onClick = onClose)
    })
}

@Composable
private fun SourceError(error: Int?) {
    Box(content = {
        if (error != null) {
            Text(text = stringResource(error), color = Gold, fontSize = 12.sp)
        }
    })
}
