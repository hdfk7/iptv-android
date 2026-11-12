package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun IptvDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
): Unit {
    Dialog(onDismissRequest = onDismissRequest, properties = properties, content = {
        ConfigureTvUiRendering()
        content()
    })
}
