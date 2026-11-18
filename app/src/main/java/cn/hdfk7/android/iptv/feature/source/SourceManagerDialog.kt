package cn.hdfk7.android.iptv.feature.source

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.core.ui.components.IptvDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.SourceLibrary
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.FormFieldSpacing
import cn.hdfk7.android.iptv.core.ui.components.FormFieldWidth
import cn.hdfk7.android.iptv.core.ui.components.FormTwoColumnWidth
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.tvPanelNavigation
import cn.hdfk7.android.iptv.core.ui.components.tvTextFieldColors
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

private data class SourceSelection(val row: String, val action: Int = 0)

private fun Modifier.sourceLogicalFocus(focused: Boolean?): Modifier {
    return if (focused == null) {
        this
    } else {
        focusProperties(scope = { canFocus = false }).semantics(properties = { selected = focused })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceManagerDialog(viewModel: SourceManagerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val television = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val twoColumns = television || configuration.screenWidthDp.dp >= FormTwoColumnWidth + 56.dp
    Box(content = {
        if (state.open) {
            Dialog(onDismissRequest = viewModel::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false), content = {
                val selection = remember(calculation = { mutableStateOf<SourceSelection?>(null) })
                // TV keyboards may overlay the bottom of the screen without resizing the window.
                val keyboardVisible = WindowInsets.isImeVisible
                val phoneEditor = !television && state.draft != null
                Box(
                    modifier = (if (state.draft != null) Modifier.fillMaxSize() else Modifier)
                        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                    contentAlignment = if (television && keyboardVisible) Alignment.TopCenter else Alignment.Center,
                    content = {
                        val panelHeight = if (phoneEditor) {
                            Modifier.verticalScroll(rememberScrollState()).padding(16.dp)
                                .height(if (twoColumns) 306.dp else 400.dp)
                        } else {
                            Modifier.padding(16.dp).heightIn(max = 480.dp)
                        }
                        val innerPadding = 16.dp
                        val panelWidth = if (state.deleting != null) {
                            420.dp
                        } else if (state.draft != null) {
                            val fieldsWidth = if (twoColumns) FormTwoColumnWidth else FormFieldWidth
                            if (twoColumns) 560.dp else fieldsWidth + innerPadding * 2
                        } else if (television && state.deleting == null) {
                            620.dp
                        } else {
                            710.dp
                        }
                        Column(modifier = panelHeight.width(panelWidth)
                            .background(DialogSurface, RoundedCornerShape(22.dp))
                            .padding(innerPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = {
                            when {
                                state.draft != null -> {
                                    SourceEditor(state = state, viewModel = viewModel, twoColumns = twoColumns, television = television)
                                }
                                state.deleting != null -> {
                                    SourceDelete(state = state, viewModel = viewModel, television = television)
                                }
                                else -> {
                                    SourceList(state = state, library = catalog.library, viewModel = viewModel, television = television,
                                        selection = selection)
                                }
                            }
                        })
                    }
                )
            })
        }
    })
}

@Composable
private fun SourceList(state: SourceManagerState, library: SourceLibrary, viewModel: SourceManagerViewModel,
    television: Boolean, selection: MutableState<SourceSelection?>): Unit {
    val listState = rememberLazyListState()
    val rows = listOf("close", SourceLibrary.FAVORITES_ID) +
        library.sources.map(transform = { source -> source.id }) + "add"
    fun lastAction(row: String): Int {
        return when (row) {
            "close", SourceLibrary.FAVORITES_ID -> 0
            "add" -> if (library.sources.isEmpty()) 1 else 0
            else -> 2
        }
    }
    fun currentSelection(): SourceSelection {
        val item = selection.value?.takeIf(predicate = { target -> target.row in rows })
            ?: SourceSelection(library.activeSourceId.takeIf(predicate = rows::contains) ?: SourceLibrary.FAVORITES_ID)
        return item.copy(action = item.action.coerceIn(0, lastAction(item.row)))
    }
    val current = currentSelection()
    val rowIndex = rows.indexOf(current.row)
    fun highlighted(row: String, action: Int = 0): Boolean? {
        return if (television) current == SourceSelection(row, action) else null
    }
    LaunchedEffect(rowIndex, block = {
        val index = rowIndex - 1
        if (index in 0..library.sources.size) {
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull(predicate = { entry -> entry.index == index })
            if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset) {
                listState.scrollToItem(index)
            }
        }
    })
    val navigation = tvPanelNavigation(television, onMove = { horizontal, vertical ->
        val selected = currentSelection()
        val row = rows[(rows.indexOf(selected.row) + vertical).coerceIn(0, rows.lastIndex)]
        selection.value = SourceSelection(row, (selected.action + horizontal).coerceIn(0, lastAction(row)))
    }, onConfirm = {
        if (!state.busy) {
            val selected = currentSelection()
            selection.value = selected
            when (selected.row) {
                "close" -> viewModel.back()
                SourceLibrary.FAVORITES_ID -> viewModel.showFavorites()
                "add" -> if (selected.action == 0) viewModel.add() else viewModel.restoreDefaults()
                else -> library.sources.firstOrNull(predicate = { source -> source.id == selected.row })?.let(block = { source ->
                    when (selected.action) {
                        1 -> viewModel.edit(source)
                        2 -> viewModel.askDelete(source)
                        else -> viewModel.activate(source.id)
                    }
                })
            }
        }
    })
    val listPanel = Modifier.heightIn(max = 408.dp)
    Column(modifier = listPanel.then(navigation), verticalArrangement = Arrangement.spacedBy(if (television) 12.dp else 14.dp), content = {
        DialogHeader(title = stringResource(R.string.library_title), onClose = viewModel::back, highlighted = highlighted("close"))
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp), content = {
            item(key = SourceLibrary.FAVORITES_ID, content = {
                val count = library.favorites.distinctBy(selector = { channel -> channel.key }).size
                SourceCard(
                    name = stringResource(R.string.favorites),
                    detail = stringResource(R.string.source_channel_count, count),
                    active = library.activeSourceId == SourceLibrary.FAVORITES_ID,
                    busy = state.busy,
                    onActivate = viewModel::showFavorites,
                    highlighted = highlighted(SourceLibrary.FAVORITES_ID)
                )
            })
            itemsIndexed(items = library.sources, key = { _, source -> source.id }, itemContent = { _, source ->
                SourceCard(name = source.name, detail = source.url, active = library.activeSourceId == source.id, busy = state.busy, onActivate = { viewModel.activate(source.id) }, onEdit = { viewModel.edit(source) }, onDelete = { viewModel.askDelete(source) },
                    highlighted = highlighted(source.id), editHighlighted = highlighted(source.id, 1), deleteHighlighted = highlighted(source.id, 2))
            })
        })
        SourceError(error = state.error)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = {
            val addLabel = stringResource(R.string.source_add)
            val addFocus = highlighted("add")
            val addModifier = Modifier.sourceLogicalFocus(addFocus)
            TvAction(
                onClick = viewModel::add, enabled = !state.busy, containerColor = ButtonPanel,
                modifier = addModifier.semantics(properties = { contentDescription = addLabel }),
                focusHighlight = addFocus,
                content = {
                    Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = addLabel)
                }
            )
            if (library.sources.isEmpty()) {
                TvAction(onClick = viewModel::restoreDefaults, enabled = !state.busy, containerColor = ButtonPanel,
                    modifier = Modifier.sourceLogicalFocus(highlighted("add", 1)), focusHighlight = highlighted("add", 1),
                    contentPadding = PaddingValues(horizontal = 16.dp), content = {
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
private fun SourceCard(name: String, detail: String, active: Boolean, busy: Boolean, onActivate: () -> Unit,
    highlighted: Boolean?, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    editHighlighted: Boolean? = null, deleteHighlighted: Boolean? = null): Unit {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth().background(BackdropBottom, shape).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            val activateLabel = stringResource(if (active) R.string.source_current else R.string.source_use)
            TvAction(
                onClick = onActivate,
                modifier = Modifier.weight(1f).sourceLogicalFocus(highlighted)
                    .semantics(properties = { contentDescription = activateLabel }),
                containerColor = Color.Transparent, showUnfocusedBorder = false,
                enabled = !busy, focusHighlight = highlighted, fitContentHeight = true,
                content = {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = {
                        Text(text = name, color = Accent, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = detail, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    })
                    Icon(
                        painter = painterResource(if (active) R.drawable.ic_check else R.drawable.ic_play),
                        contentDescription = null, tint = if (active) FocusBorder else Muted,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp)
                    )
                }
            )
            if (onEdit != null) {
                SourceRowAction(R.drawable.ic_edit, R.string.source_edit, onEdit, editHighlighted, !busy)
            }
            if (onDelete != null) {
                SourceRowAction(R.drawable.ic_delete, R.string.source_delete, onDelete, deleteHighlighted, !busy)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceEditor(state: SourceManagerState, viewModel: SourceManagerViewModel, twoColumns: Boolean, television: Boolean): Unit {
    val inputMode = LocalInputModeManager.current
    val draft = state.draft ?: return
    val closeFocus = remember(calculation = { FocusRequester() })
    val view = LocalView.current
    var fieldsReady by remember(draft.id, calculation = { mutableStateOf(false) })
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(draft.id, windowFocused, block = {
        if (!television && windowFocused) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos(onFrame = { _ -> })
            if (!fieldsReady) {
                closeFocus.requestFocus()
                val window = (view.parent as? DialogWindowProvider)?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                }
                fieldsReady = true
            }
        }
    })
    var focused by remember(draft.id, calculation = { mutableIntStateOf(0) })
    var editing by remember(draft.id, calculation = { mutableIntStateOf(0) })
    val fieldRequester = remember(calculation = { FocusRequester() })
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val finishEditing: () -> Unit = {
        keyboard?.hide()
        editing = 0
    }
    BackHandler(enabled = television && editing != 0, onBack = finishEditing)
    LaunchedEffect(editing, block = {
        if (editing != 0) {
            withFrameNanos(onFrame = { _ -> })
            if (fieldRequester.requestFocus()) {
                keyboard?.show()
            } else {
                finishEditing()
            }
        }
    })
    var keyboardWasVisible by remember(editing, calculation = { mutableStateOf(false) })
    LaunchedEffect(editing, imeVisible, block = {
        if (editing != 0) {
            if (imeVisible) {
                keyboardWasVisible = true
            } else if (keyboardWasVisible) {
                finishEditing()
            }
        }
    })
    val rows = remember(calculation = { listOf(listOf(0), listOf(1, 2), listOf(3), listOf(5, 4)) })
    val navigation = tvPanelNavigation(television && editing == 0, onMove = { horizontal, vertical ->
        val row = rows.indexOfFirst(predicate = { controls -> focused in controls })
        val column = rows[row].indexOf(focused)
        val destination = rows[(row + vertical).coerceIn(0, rows.lastIndex)]
        focused = destination[(column + horizontal).coerceIn(0, destination.lastIndex)]
    }, onConfirm = {
        if (!state.busy) {
            when (focused) {
                0, 5 -> viewModel.back()
                4 -> viewModel.save()
                else -> editing = focused
            }
        }
    })
    fun highlighted(index: Int): Boolean? {
        return if (television) focused == index else null
    }
    @Composable
    fun fieldModifier(index: Int): Modifier {
        if (!television) {
            return Modifier.focusProperties(scope = { canFocus = fieldsReady })
        }
        val bringIntoView = remember(calculation = { BringIntoViewRequester() })
        val selected = focused == index
        LaunchedEffect(selected, imeVisible, block = {
            if (selected) {
                withFrameNanos(onFrame = { _ -> })
                bringIntoView.bringIntoView()
            }
        })
        val field = if (editing == index) Modifier.focusRequester(fieldRequester) else Modifier
        return field.bringIntoViewRequester(bringIntoView).focusProperties(scope = { canFocus = editing == index })
    }
    val keyboardActions = if (television) KeyboardActions(onDone = { finishEditing() }) else KeyboardActions.Default
    val nextAction = if (television) ImeAction.Done else ImeAction.Next
    val nameField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == 0 && focused == 1), value = draft.name, onValueChange = viewModel::editName, label = { Text(text = stringResource(R.string.source_name)) }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = nextAction, showKeyboardOnFocus = false), keyboardActions = keyboardActions, readOnly = television && editing != 1, enabled = !state.busy, modifier = modifier.then(fieldModifier(1)))
    }
    val addressField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == 0 && focused == 2), value = draft.address, onValueChange = viewModel::editAddress, label = { Text(text = stringResource(R.string.source_address)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = nextAction, showKeyboardOnFocus = false), keyboardActions = keyboardActions, readOnly = television && editing != 2, enabled = !state.busy, modifier = modifier.then(fieldModifier(2)))
    }
    val guideField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == 0 && focused == 3), value = draft.guideAddress, onValueChange = viewModel::editGuideAddress,
            label = { Text(text = stringResource(R.string.source_guide_address)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false,
                imeAction = ImeAction.Done, showKeyboardOnFocus = false),
            keyboardActions = keyboardActions, readOnly = television && editing != 3,
            enabled = !state.busy, modifier = modifier.then(fieldModifier(3)))
    }
    val scroll = rememberScrollState()
    val editorModifier = if (television) navigation else navigation.verticalScroll(scroll)
    val fields: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().background(BackdropBottom, RoundedCornerShape(14.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = {
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                    nameField(Modifier.weight(1f))
                    addressField(Modifier.weight(1f))
                })
                Row(horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                    guideField(Modifier.weight(1f))
                    Spacer(modifier = Modifier.weight(1f))
                })
            } else {
                nameField(Modifier.fillMaxWidth())
                addressField(Modifier.fillMaxWidth())
                guideField(Modifier.fillMaxWidth())
            }
            if (state.error != null) {
                SourceError(error = state.error)
            }
        })
    }
    Column(modifier = editorModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp), content = {
        DialogHeader(title = stringResource(if (draft.id == null) R.string.source_add else R.string.source_edit),
            onClose = viewModel::back, closeFocus = if (television) null else closeFocus, highlighted = highlighted(0))
        if (television) {
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = { fields() })
        } else {
            fields()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            content = {
                SourceTextAction(R.string.cancel, viewModel::back, highlighted(5), enabled = !state.busy)
                SourceTextAction(if (state.busy) R.string.saving else R.string.save,
                    viewModel::save, highlighted(4), enabled = !state.busy)
            }
        )
    })
}

@Composable
private fun SourceDelete(state: SourceManagerState, viewModel: SourceManagerViewModel, television: Boolean): Unit {
    val source = state.deleting ?: return
    var focused by remember(source.id, calculation = { mutableIntStateOf(1) })
    val navigation = tvPanelNavigation(television, onMove = { horizontal, vertical ->
        if (vertical < 0) {
            focused = 0
        } else if (vertical > 0 && focused == 0) {
            focused = 1
        } else if (focused != 0) {
            focused = (focused + horizontal).coerceIn(1, 2)
        }
    }, onConfirm = {
        if (!state.busy) {
            if (focused == 2) {
                viewModel.delete()
            } else {
                viewModel.back()
            }
        }
    })
    fun highlighted(index: Int): Boolean? {
        return if (television) focused == index else null
    }
    Column(modifier = navigation, verticalArrangement = Arrangement.spacedBy(12.dp), content = {
        DialogHeader(title = stringResource(R.string.source_delete_title, source.name), onClose = viewModel::back,
            highlighted = highlighted(0))
        SourceError(error = state.error)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            content = {
                SourceTextAction(R.string.cancel, viewModel::back, highlighted(1), enabled = !state.busy)
                SourceTextAction(R.string.source_delete, viewModel::delete, highlighted(2), enabled = !state.busy)
            }
        )
    })
}

@Composable
private fun SourceRowAction(icon: Int, label: Int, onClick: () -> Unit, highlighted: Boolean?, enabled: Boolean): Unit {
    val description = stringResource(label)
    TvAction(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(40.dp).sourceLogicalFocus(highlighted)
            .semantics(properties = { contentDescription = description }),
        focusHighlight = highlighted,
        containerColor = Color.Transparent,
        showUnfocusedBorder = false,
        contentPadding = PaddingValues(8.dp),
        content = {
            Icon(painter = painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    )
}

@Composable
private fun SourceTextAction(
    label: Int,
    onClick: () -> Unit,
    highlighted: Boolean?,
    enabled: Boolean = true
): Unit {
    val text = stringResource(label)
    val description = text
    TvAction(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.sourceLogicalFocus(highlighted).semantics(properties = { contentDescription = description }),
        focusHighlight = highlighted,
        content = { Text(text = text, fontSize = 14.sp) }
    )
}

@Composable
private fun DialogHeader(title: String, onClose: () -> Unit, closeFocus: FocusRequester? = null, highlighted: Boolean? = null): Unit {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = {
        DialogTitle(text = title, modifier = Modifier.weight(1f))
        val modifier = if (closeFocus != null) Modifier.focusRequester(closeFocus) else Modifier
        TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.close), onClick = onClose,
            modifier = modifier.sourceLogicalFocus(highlighted), focusHighlight = highlighted)
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
