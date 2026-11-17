package cn.hdfk7.android.iptv.feature.proxy

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.core.ui.components.IptvDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.data.local.ProxySettingsStore
import cn.hdfk7.android.iptv.core.model.ProxySettings
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.FormFieldSpacing
import cn.hdfk7.android.iptv.core.ui.components.FormFieldWidth
import cn.hdfk7.android.iptv.core.ui.components.FormTwoColumnWidth
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.tvTextFieldColors
import cn.hdfk7.android.iptv.core.ui.components.tvPanelNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private enum class ProxyControl { Power, Host, Port, Username, Password, Close }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProxySettingsDialog(store: ProxySettingsStore, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val writes = remember(calculation = { Mutex() })
    var proxyEnabled by remember(calculation = { mutableStateOf(store.current?.enabled == true) })
    var switching by remember(calculation = { mutableStateOf(false) })
    var closing by remember(calculation = { mutableStateOf(false) })
    var draft by remember(calculation = { mutableStateOf(store.current ?: ProxySettings()) })
    var error by remember(calculation = { mutableStateOf<Int?>(if (store.current == null) R.string.proxy_read_error else null) })
    val configuration = LocalConfiguration.current
    val television = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val twoColumns = television || configuration.screenWidthDp.dp >= FormTwoColumnWidth + 56.dp
    val updateDraft: (ProxySettings) -> Unit = { updated ->
        if (!proxyEnabled && !switching && !closing) {
            draft = updated
            error = null
        }
    }
    fun persist(switch: Boolean = false, dismiss: Boolean = false): Unit {
        if (closing || (switching && switch)) {
            return
        }
        val fields = draft
        if (switch) {
            switching = true
            error = null
        }
        if (dismiss) {
            closing = true
        }
        scope.launch(block = {
            try {
                writes.withLock(action = {
                    val previous = store.current
                    val settings = when {
                        switch -> fields.copy(enabled = previous?.enabled != true)
                        previous?.enabled == true -> previous
                        else -> fields.copy(enabled = false)
                    }
                    if (!settings.valid()) {
                        error = R.string.proxy_invalid
                        return@withLock
                    }
                    if (settings != (previous ?: ProxySettings())) {
                        withContext(context = Dispatchers.IO, block = { store.save(settings) })
                        proxyEnabled = settings.enabled
                        if (switch || previous == null) {
                            onSaved()
                        }
                    }
                })
                if (dismiss) {
                    onClose()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = R.string.storage_error
            } finally {
                if (switch) {
                    switching = false
                }
                if (dismiss) {
                    closing = false
                }
            }
        })
    }
    val close: () -> Unit = { persist(dismiss = true) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false), content = {
        val inputMode = LocalInputModeManager.current
        val view = LocalView.current
        val focus = remember(calculation = { FocusRequester() })
        var fieldsReady by remember(calculation = { mutableStateOf(false) })
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, block = {
            if (!television && windowFocused && !fieldsReady) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos(onFrame = { _ -> })
                focus.requestFocus()
                val window = (view.parent as? DialogWindowProvider)?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                }
                fieldsReady = true
            }
        })
        val rows = remember(proxyEnabled, calculation = {
            val powerRow = listOf(ProxyControl.Power)
            val closeRow = listOf(ProxyControl.Close)
            if (proxyEnabled) {
                listOf(powerRow, closeRow)
            } else {
                listOf(powerRow, listOf(ProxyControl.Host, ProxyControl.Port),
                    listOf(ProxyControl.Username, ProxyControl.Password), closeRow)
            }
        })
        var selectedControl by remember(calculation = { mutableStateOf(ProxyControl.Power) })
        var editing by remember(calculation = { mutableStateOf<ProxyControl?>(null) })
        val scroll = rememberScrollState()
        val fieldFocus = remember(calculation = { FocusRequester() })
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val canEdit = !proxyEnabled && !switching && !closing
        val toggle: () -> Unit = {
            selectedControl = ProxyControl.Power
            persist(switch = true)
            focusManager.clearFocus()
            keyboard?.hide()
        }
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(selectedControl, block = {
            if (television && selectedControl == ProxyControl.Power) {
                scroll.scrollTo(0)
            }
        })
        val finishEditing: () -> Unit = {
            focusManager.clearFocus()
            keyboard?.hide()
            editing = null
        }
        BackHandler(enabled = television && editing != null, onBack = finishEditing)
        LaunchedEffect(editing, block = {
            if (editing != null) {
                withFrameNanos(onFrame = { _ -> })
                if (fieldFocus.requestFocus()) {
                    keyboard?.show()
                } else {
                    finishEditing()
                }
            }
        })
        var keyboardWasVisible by remember(editing, calculation = { mutableStateOf(false) })
        LaunchedEffect(editing, imeVisible, block = {
            if (editing != null) {
                if (imeVisible) {
                    keyboardWasVisible = true
                } else if (keyboardWasVisible) {
                    finishEditing()
                }
            }
        })
        val navigation = tvPanelNavigation(television && editing == null, onMove = { horizontal, vertical ->
            if (switching || closing) {
                return@tvPanelNavigation
            }
            val row = rows.indexOfFirst(predicate = { controls -> selectedControl in controls })
            val column = rows[row].indexOf(selectedControl)
            val destination = rows[(row + vertical).coerceIn(0, rows.lastIndex)]
            selectedControl = destination[(column + horizontal).coerceIn(0, destination.lastIndex)]
        }, onConfirm = {
            when (selectedControl) {
                ProxyControl.Power -> toggle()
                ProxyControl.Close -> close()
                else -> {
                    if (canEdit) {
                        editing = selectedControl
                    }
                }
            }
        })
        fun highlighted(control: ProxyControl): Boolean? {
            return if (television) selectedControl == control else null
        }
        fun buttonModifier(control: ProxyControl): Modifier {
            return if (television) {
                Modifier.focusProperties(scope = { canFocus = false })
                    .semantics(properties = { selected = selectedControl == control })
            } else if (control == ProxyControl.Power) {
                Modifier.focusRequester(focus)
            } else {
                Modifier
            }
        }
        @Composable
        fun fieldModifier(control: ProxyControl): Modifier {
            var wasFocused by remember(calculation = { mutableStateOf(false) })
            val saveOnBlur = Modifier.onFocusChanged(onFocusChanged = { focusState ->
                if (wasFocused && !focusState.isFocused && !proxyEnabled && !switching) {
                    persist()
                }
                wasFocused = focusState.isFocused
            })
            if (!television) {
                return saveOnBlur.focusProperties(scope = { canFocus = fieldsReady && canEdit })
            }
            val bringIntoView = remember(calculation = { BringIntoViewRequester() })
            val selected = selectedControl == control
            LaunchedEffect(selected, imeVisible, block = {
                if (selected) {
                    withFrameNanos(onFrame = { _ -> })
                    bringIntoView.bringIntoView()
                }
            })
            val requester = if (editing == control) Modifier.focusRequester(fieldFocus) else Modifier
            return requester.bringIntoViewRequester(bringIntoView).then(saveOnBlur)
                .focusProperties(scope = { canFocus = canEdit && editing == control })
        }
        val actions = KeyboardActions(onDone = { finishEditing() })
        fun keyboardOptions(type: KeyboardType = KeyboardType.Text): KeyboardOptions {
            return KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done, showKeyboardOnFocus = false)
        }
        val powerStatus = if (proxyEnabled) R.string.proxy_on else R.string.proxy_off
        val power: @Composable () -> Unit = {
            TvIconButton(
                description = stringResource(powerStatus),
                icon = R.drawable.ic_power,
                selected = proxyEnabled,
                onClick = toggle,
                modifier = buttonModifier(ProxyControl.Power),
                focusHighlight = highlighted(ProxyControl.Power)
            )
        }
        val hostField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == null && selectedControl == ProxyControl.Host), value = draft.host, onValueChange = { value -> updateDraft(draft.copy(host = value.trim())) },
                label = { Text(stringResource(R.string.proxy_ip)) }, singleLine = true,
                keyboardOptions = keyboardOptions(), keyboardActions = actions,
                enabled = !proxyEnabled, readOnly = !canEdit || television && editing != ProxyControl.Host, modifier = modifier.then(fieldModifier(ProxyControl.Host)))
        }
        val portField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == null && selectedControl == ProxyControl.Port), value = draft.port, onValueChange = { value -> updateDraft(draft.copy(port = value)) },
                label = { Text(stringResource(R.string.proxy_port)) }, singleLine = true,
                keyboardOptions = keyboardOptions(KeyboardType.Number), keyboardActions = actions,
                enabled = !proxyEnabled, readOnly = !canEdit || television && editing != ProxyControl.Port, modifier = modifier.then(fieldModifier(ProxyControl.Port)))
        }
        val usernameField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == null && selectedControl == ProxyControl.Username), value = draft.username, onValueChange = { value -> updateDraft(draft.copy(username = value)) },
                label = { Text(stringResource(R.string.proxy_username)) }, singleLine = true,
                keyboardOptions = keyboardOptions(), keyboardActions = actions,
                enabled = !proxyEnabled, readOnly = !canEdit || television && editing != ProxyControl.Username,
                modifier = modifier.then(fieldModifier(ProxyControl.Username)))
        }
        val passwordField: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(colors = tvTextFieldColors(highlighted = television && editing == null && selectedControl == ProxyControl.Password), value = draft.password, onValueChange = { value -> updateDraft(draft.copy(password = value)) },
                label = { Text(stringResource(R.string.proxy_password)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = keyboardOptions(KeyboardType.Password), keyboardActions = actions, singleLine = true,
                enabled = !proxyEnabled, readOnly = !canEdit || television && editing != ProxyControl.Password,
                modifier = modifier.then(fieldModifier(ProxyControl.Password)))
        }
        Box(
            modifier = (if (television) Modifier.fillMaxSize() else Modifier)
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            contentAlignment = if (television) Alignment.TopCenter else Alignment.Center,
            content = {
                val panelHeight = if (television) {
                    Modifier.padding(12.dp).heightIn(max = 480.dp)
                } else {
                    val baseHeight = if (twoColumns) 260.dp else 400.dp
                    val errorSpace = when {
                        configuration.orientation != Configuration.ORIENTATION_PORTRAIT -> 0.dp
                        configuration.screenWidthDp.dp < FormFieldWidth + 56.dp -> 80.dp
                        else -> 56.dp
                    }
                    Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
                        .height(baseHeight + errorSpace)
                }
                val innerPadding = if (television) 14.dp else 16.dp
                val fieldsWidth = if (twoColumns) FormTwoColumnWidth else FormFieldWidth
                Column(
                    modifier = panelHeight.width(fieldsWidth + innerPadding * 2).then(navigation)
                        .background(DialogSurface, RoundedCornerShape(22.dp)).padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                                DialogTitle(text = stringResource(R.string.proxy_title), modifier = Modifier.weight(1f))
                                Text(text = stringResource(powerStatus), color = Accent, fontSize = 16.sp)
                                power()
                            })
                        Column(
                            modifier = Modifier.weight(1f, fill = false).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = {
                                if (twoColumns) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                                        hostField(Modifier.weight(1f))
                                        portField(Modifier.weight(1f))
                                    })
                                    Row(horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                                        usernameField(Modifier.weight(1f))
                                        passwordField(Modifier.weight(1f))
                                    })
                                } else {
                                    hostField(Modifier.fillMaxWidth())
                                    portField(Modifier.fillMaxWidth())
                                    usernameField(Modifier.fillMaxWidth())
                                    passwordField(Modifier.fillMaxWidth())
                                }
                                if (error != null) {
                                    Text(text = stringResource(error!!), color = Error)
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(FormFieldSpacing), content = {
                            TvIconButton(description = stringResource(R.string.proxy_close), icon = R.drawable.ic_close, onClick = close,
                                modifier = buttonModifier(ProxyControl.Close), focusHighlight = highlighted(ProxyControl.Close))
                        })
                    }
                )
            }
        )
    })
}
