package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.data.local.AdvancedSettingsStore
import cn.hdfk7.android.iptv.core.data.local.validUpdateManifestUrl
import cn.hdfk7.android.iptv.core.model.formatTimeoutSeconds
import cn.hdfk7.android.iptv.core.model.parseTimeoutSeconds
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.tvDirections
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface
import cn.hdfk7.android.iptv.core.ui.components.tvPanelNavigation
import cn.hdfk7.android.iptv.core.ui.theme.ButtonPanel
import cn.hdfk7.android.iptv.core.ui.theme.Panel
import cn.hdfk7.android.iptv.core.ui.theme.Gold
import cn.hdfk7.android.iptv.core.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ConfigControl(val title: Int) {
    Connect(R.string.advanced_connect), Read(R.string.advanced_read), Line(R.string.advanced_line),
    Debug(R.string.advanced_debug), Url(R.string.advanced_url), Defaults(R.string.advanced_defaults),
    Cancel(R.string.advanced_cancel), Save(R.string.advanced_save)
}

@Composable
internal fun AdvancedSettingsDialog(store: AdvancedSettingsStore, television: Boolean, onClose: () -> Unit): Unit {
    var draft by remember(calculation = { mutableStateOf(store.current) })
    var selected by remember(calculation = { mutableStateOf(ConfigControl.Connect) })
    var editing by remember(calculation = { mutableStateOf<ConfigControl?>(null) })
    var saving by remember(calculation = { mutableStateOf(false) })
    var failed by remember(calculation = { mutableStateOf(false) })
    val scope = rememberCoroutineScope()

    fun value(control: ConfigControl): String = when (control) {
        ConfigControl.Connect -> formatTimeoutSeconds(draft.connectTimeoutMs)
        ConfigControl.Read -> formatTimeoutSeconds(draft.readTimeoutMs)
        ConfigControl.Line -> formatTimeoutSeconds(draft.lineTimeoutMs)
        ConfigControl.Url -> draft.updateManifestUrl
        else -> ""
    }

    fun activate(control: ConfigControl): Unit {
        if (saving) {
            return
        }
        when (control) {
            ConfigControl.Connect, ConfigControl.Read, ConfigControl.Line, ConfigControl.Url -> editing = control
            ConfigControl.Debug -> draft = draft.copy(debugPlayback = !draft.debugPlayback)
            ConfigControl.Defaults -> draft = store.defaults
            ConfigControl.Cancel -> onClose()
            ConfigControl.Save -> {
                val settings = draft
                saving = true
                scope.launch(block = {
                    val saved = withContext(context = Dispatchers.IO, block = { store.save(settings) })
                    saving = false
                    failed = !saved
                    if (saved) {
                        store.applySaved(settings)
                        onClose()
                    }
                })
            }
        }
    }

    val highlight = selected.takeIf(predicate = { television })
    SettingsMenuContainer(television = television, onClose = {
        if (!saving) {
            onClose()
        }
    }, width = 560.dp, content = {
        val navigation = tvPanelNavigation(television && editing == null, onMove = { horizontal, vertical ->
            val actionsStart = ConfigControl.Defaults.ordinal
            val next = when {
                vertical < 0 && selected.ordinal >= actionsStart -> ConfigControl.Url.ordinal
                vertical > 0 && selected.ordinal >= actionsStart -> selected.ordinal
                vertical > 0 && selected == ConfigControl.Url -> ConfigControl.Save.ordinal
                vertical != 0 -> (selected.ordinal + vertical).coerceIn(0, ConfigControl.Url.ordinal)
                selected.ordinal >= actionsStart -> (selected.ordinal + horizontal).coerceIn(actionsStart, ConfigControl.Save.ordinal)
                else -> selected.ordinal
            }
            selected = ConfigControl.entries[next]
        }, onConfirm = { activate(selected) })
        SettingsPanel(navigation = navigation, content = {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = {
                    SettingsGroup(content = {
                        for (control in ConfigControl.entries.take(3)) {
                            ConfigAction(
                                control, highlight, modifier = Modifier.fillMaxWidth(), row = true,
                                onClick = { activate(control) }, content = {
                                    Text(stringResource(control.title), modifier = Modifier.weight(1f), fontSize = 16.sp)
                                    Row(
                                        modifier = Modifier.padding(start = 12.dp).widthIn(min = 62.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically,
                                        content = {
                                            Text(value(control), fontSize = 17.sp)
                                            Text(stringResource(R.string.advanced_seconds), color = Muted, fontSize = 13.sp)
                                        }
                                    )
                                    ConfigChevron()
                                }
                            )
                        }
                    })
                    SettingsGroup(content = {
                        ConfigAction(
                            ConfigControl.Debug, highlight, modifier = Modifier.fillMaxWidth(), row = true,
                            onClick = { activate(ConfigControl.Debug) }, content = {
                                Text(stringResource(ConfigControl.Debug.title), modifier = Modifier.weight(1f), fontSize = 16.sp)
                                Switch(checked = draft.debugPlayback, onCheckedChange = null)
                            }
                        )
                        ConfigAction(
                            ConfigControl.Url, highlight, modifier = Modifier.fillMaxWidth(), row = true,
                            onClick = { activate(ConfigControl.Url) }, content = {
                                Text(stringResource(ConfigControl.Url.title), modifier = Modifier.weight(1f), fontSize = 16.sp)
                                ConfigChevron()
                            }
                        )
                    })
                }
            )
            if (failed) {
                Text(stringResource(R.string.advanced_save_error), color = Gold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = {
                ConfigAction(ConfigControl.Defaults, highlight, onClick = { activate(ConfigControl.Defaults) }, content = {
                    Text(stringResource(ConfigControl.Defaults.title))
                })
                Spacer(modifier = Modifier.weight(1f))
                ConfigAction(ConfigControl.Cancel, highlight, onClick = { activate(ConfigControl.Cancel) }, content = {
                    Text(stringResource(ConfigControl.Cancel.title))
                })
                ConfigAction(ConfigControl.Save, highlight, onClick = { activate(ConfigControl.Save) }, content = {
                    Text(stringResource(ConfigControl.Save.title))
                })
            })
        })
    })

    val field = editing
    if (field != null) {
        EditAdvancedValue(field, value(field), television, onClose = { editing = null }, onApply = { input ->
            val timeout = parseTimeoutSeconds(input)
            draft = when (field) {
                ConfigControl.Connect -> draft.copy(connectTimeoutMs = checkNotNull(timeout))
                ConfigControl.Read -> draft.copy(readTimeoutMs = checkNotNull(timeout))
                ConfigControl.Line -> draft.copy(lineTimeoutMs = checkNotNull(timeout))
                ConfigControl.Url -> draft.copy(updateManifestUrl = input.trim())
                else -> draft
            }
            editing = null
        })
    }
}

@Composable
private fun SettingsPanel(
    navigation: Modifier = Modifier,
    title: Int = R.string.advanced_title,
    content: @Composable ColumnScope.() -> Unit
): Unit {
    Column(
        modifier = Modifier.fillMaxWidth().background(DialogSurface, RoundedCornerShape(22.dp))
            .then(navigation).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            DialogTitle(text = stringResource(title))
            content()
        }
    )
}

@Composable
private fun ConfigChevron(): Unit {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.padding(start = 8.dp).size(20.dp),
        tint = Muted
    )
}

@Composable
private fun ConfigAction(
    control: ConfigControl,
    selected: ConfigControl?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    row: Boolean = false,
    content: @Composable RowScope.() -> Unit
): Unit {
    val reveal = remember(calculation = { BringIntoViewRequester() })
    LaunchedEffect(selected == control, block = {
        if (selected == control) {
            reveal.bringIntoView()
        }
    })
    TvAction(
        onClick = onClick,
        modifier = modifier.bringIntoViewRequester(reveal).focusProperties(scope = { canFocus = selected == null }),
        focusHighlight = selected?.let(block = { item -> item == control }),
        containerColor = when {
            row -> Color.Transparent
            control == ConfigControl.Save -> ButtonPanel
            else -> Panel
        },
        showUnfocusedBorder = !row,
        fitContentHeight = true,
        content = content
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EditAdvancedValue(control: ConfigControl, initial: String, television: Boolean, onClose: () -> Unit, onApply: (String) -> Unit): Unit {
    val url = control == ConfigControl.Url
    var input by remember(control, calculation = {
        val selection = if (url) TextRange(initial.length) else TextRange(0, initial.length)
        mutableStateOf(TextFieldValue(initial, selection = selection))
    })
    var invalid by remember(control, calculation = { mutableStateOf(false) })
    val focus = remember(calculation = { FocusRequester() })
    val cancelFocus = remember(calculation = { FocusRequester() })
    val applyFocus = remember(calculation = { FocusRequester() })
    val inputMode = LocalInputModeManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible
    val fieldNavigation = if (television) {
        Modifier.onPreviewKeyEvent(onPreviewKeyEvent = { event ->
            if (event.key == Key.DirectionDown && !keyboardVisible) {
                if (event.type == KeyEventType.KeyDown) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    cancelFocus.requestFocus()
                }
                true
            } else {
                false
            }
        })
    } else {
        Modifier
    }
    fun apply(): Unit {
        invalid = if (url) !validUpdateManifestUrl(input.text.trim()) else parseTimeoutSeconds(input.text) == null
        if (!invalid) {
            keyboard?.hide()
            onApply(input.text)
        }
    }
    SettingsMenuContainer(television = television, onClose = onClose, width = if (url && television) 620.dp else 360.dp, content = {
        SettingsPanel(title = control.title, content = {
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    input = value
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).then(fieldNavigation),
                placeholder = if (url) {
                    { Text(initial, color = Muted, maxLines = 1) }
                } else {
                    null
                },
                suffix = if (url) {
                    null
                } else {
                    { Text(stringResource(R.string.advanced_seconds), color = Muted) }
                },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = if (url) KeyboardType.Uri else KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() })
            )
            if (invalid) {
                Text(stringResource(if (url) R.string.advanced_bad_url else R.string.advanced_bad_timeout), color = Gold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = {
                val cancelNavigation = if (television) {
                    Modifier.focusRequester(cancelFocus).tvDirections(
                        up = { focus.requestFocus() }, right = { applyFocus.requestFocus() }
                    )
                } else {
                    Modifier
                }
                val applyNavigation = if (television) {
                    Modifier.focusRequester(applyFocus).tvDirections(
                        up = { focus.requestFocus() }, left = { cancelFocus.requestFocus() }
                    )
                } else {
                    Modifier
                }
                TvAction(onClick = onClose, modifier = cancelNavigation, content = { Text(stringResource(R.string.advanced_cancel)) })
                TvAction(onClick = ::apply, modifier = applyNavigation, content = { Text(stringResource(R.string.advanced_apply)) })
            })
            LaunchedEffect(Unit, block = {
                if (television) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                }
                focus.requestFocus()
                keyboard?.show()
            })
        })
    })
}
