package cn.hdfk7.android.iptv.feature.update

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cn.hdfk7.android.iptv.core.ui.components.IptvDialog as Dialog
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.tvDirections
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface
import cn.hdfk7.android.iptv.core.ui.theme.Muted

@Composable
internal fun AppUpdateContent(
    message: String,
    notes: String,
    notesVersion: String,
    downloading: Boolean,
    validating: Boolean,
    fraction: Float?,
    primaryLabel: Int?,
    canRedownload: Boolean,
    onPrimary: () -> Unit,
    onRedownload: () -> Unit,
    onClose: () -> Unit
): Unit {
    val primaryFocus = remember(calculation = { FocusRequester() })
    val closeFocus = remember(calculation = { FocusRequester() })
    val redownloadFocus = remember(calculation = { FocusRequester() })
    UpdateDialogContainer(downloading = downloading, onClose = onClose, content = {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(key1 = primaryLabel, key2 = canRedownload, block = {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos(onFrame = { _ -> })
            if (primaryLabel != null) {
                primaryFocus.requestFocus()
            } else {
                closeFocus.requestFocus()
            }
        })
        Column(
            modifier = Modifier.padding(12.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 480.dp)
                .background(DialogSurface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                DialogTitle(text = stringResource(R.string.app_update_title))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = {
                        Text(text = message, color = Accent, fontSize = 15.sp)
                        if (notes.isNotBlank() && !downloading) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = {
                                Text(text = stringResource(R.string.app_update_notes, notesVersion), color = Accent,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                for (line in notes.lineSequence().map(transform = String::trim).filter(predicate = String::isNotEmpty)) {
                                    if (line.startsWith("• ") || line.startsWith("- ")) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                                            Text(text = "•", color = Muted, fontSize = 14.sp, lineHeight = 21.sp,
                                                modifier = Modifier.alignByBaseline())
                                            Text(text = line.drop(2).trimStart(), color = Muted, fontSize = 14.sp,
                                                lineHeight = 21.sp, modifier = Modifier.weight(1f).alignByBaseline())
                                        })
                                    } else {
                                        Text(text = line, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                                    }
                                }
                            })
                        }
                        if (downloading) {
                            if (fraction == null || validating) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = {
                    TvAction(
                        onClick = onClose,
                        modifier = Modifier.focusRequester(closeFocus).focusProperties(scope = { canFocus = true }).tvDirections(
                            right = {
                                if (canRedownload) {
                                    redownloadFocus.requestFocus()
                                } else if (primaryLabel != null) {
                                    primaryFocus.requestFocus()
                                }
                            }
                        ),
                        content = { Text(text = stringResource(if (downloading) R.string.app_update_cancel_download else R.string.app_update_close_short)) }
                    )
                    if (canRedownload) {
                        TvAction(onClick = onRedownload, modifier = Modifier.focusRequester(redownloadFocus)
                            .focusProperties(scope = { canFocus = true }).tvDirections(
                                left = { closeFocus.requestFocus() },
                                right = {
                                    if (primaryLabel != null) {
                                        primaryFocus.requestFocus()
                                    }
                                }
                            ), content = {
                            Text(text = stringResource(R.string.app_update_retry_download))
                        })
                    }
                    if (primaryLabel != null) {
                        TvAction(
                            onClick = onPrimary,
                            modifier = Modifier.focusRequester(primaryFocus).focusProperties(scope = { canFocus = true }).tvDirections(
                                left = {
                                    if (canRedownload) {
                                        redownloadFocus.requestFocus()
                                    } else {
                                        closeFocus.requestFocus()
                                    }
                                }
                            ),
                            content = { Text(text = stringResource(primaryLabel)) }
                        )
                    }
                })
            }
        )
    })
}

@Composable
private fun UpdateDialogContainer(downloading: Boolean, onClose: () -> Unit, content: @Composable () -> Unit): Unit {
    val television = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = !downloading), content = {
        Box(
            modifier = if (television) Modifier.width(460.dp) else Modifier,
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    })
}
