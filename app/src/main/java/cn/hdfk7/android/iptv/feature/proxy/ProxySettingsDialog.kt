package cn.hdfk7.android.iptv.feature.proxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.data.local.ProxySettingsStore
import cn.hdfk7.android.iptv.core.model.ProxySettings
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton

@Composable
fun ProxySettingsDialog(store: ProxySettingsStore, onClose: () -> Unit, onSaved: () -> Unit) {
    var draft by remember(calculation = { mutableStateOf(store.current ?: ProxySettings()) })
    var error by remember(calculation = { mutableStateOf<Int?>(if (store.current == null) R.string.proxy_read_error else null) })
    val updateDraft: (ProxySettings) -> Unit = { updated ->
        draft = updated
        error = null
    }
    val focus = remember(calculation = { FocusRequester() })
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false), content = {
        val inputMode = LocalInputModeManager.current
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, block = {
            if (windowFocused) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos(onFrame = { _ -> })
                focus.requestFocus()
            }
        })
        Column(modifier = Modifier.width(560.dp).heightIn(max = 480.dp).background(DialogSurface, RoundedCornerShape(22.dp)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = {
            Text(text = stringResource(R.string.proxy_title), color = Color.White)
            TvIconButton(
                description = stringResource(if (draft.enabled) R.string.proxy_on else R.string.proxy_off),
                icon = R.drawable.ic_power,
                selected = draft.enabled,
                onClick = {
                    val updated = draft.copy(enabled = !draft.enabled)
                    if (updated.valid()) {
                        updateDraft(updated)
                    } else {
                        error = R.string.proxy_invalid
                    }
                },
                modifier = Modifier.focusRequester(focus)
            )
            OutlinedTextField(value = draft.host, onValueChange = { updateDraft(draft.copy(host = it.trim())) }, label = { Text(stringResource(R.string.proxy_ip)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.port, onValueChange = { updateDraft(draft.copy(port = it)) }, label = { Text(stringResource(R.string.proxy_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = {
                OutlinedTextField(value = draft.username, onValueChange = { updateDraft(draft.copy(username = it)) }, label = { Text(stringResource(R.string.proxy_username)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = draft.password, onValueChange = { updateDraft(draft.copy(password = it)) }, label = { Text(stringResource(R.string.proxy_password)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.weight(1f))
            })
            if (error != null) {
                Text(text = stringResource(error!!), color = Error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = {
                TvIconButton(description = stringResource(R.string.proxy_save), icon = R.drawable.ic_check, onClick = {
                    if (!draft.valid()) {
                        error = R.string.proxy_invalid
                        return@TvIconButton
                    }
                    val previous = store.current
                    if (draft != previous) {
                        try {
                            store.save(draft)
                        } catch (exception: Exception) {
                            error = R.string.storage_error
                            return@TvIconButton
                        }
                        if (previous == null || previous.enabled || draft.enabled) {
                            onSaved()
                        }
                    }
                    onClose()
                })
                TvIconButton(description = stringResource(R.string.proxy_cancel), icon = R.drawable.ic_close, onClick = onClose)
            })
        })
    })
}
