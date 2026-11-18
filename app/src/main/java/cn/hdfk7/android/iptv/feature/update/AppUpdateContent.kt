package cn.hdfk7.android.iptv.feature.update

import cn.hdfk7.android.iptv.core.ui.components.DialogLayout
import cn.hdfk7.android.iptv.core.ui.components.dialogSurface
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import cn.hdfk7.android.iptv.core.ui.theme.BackdropBottom
import cn.hdfk7.android.iptv.core.ui.theme.Muted
import kotlinx.coroutines.launch

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
    val television = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStep = with(LocalDensity.current) { 128.dp.roundToPx() }
    fun scrollNotes(direction: Int): Unit {
        if (television && scroll.maxValue > 0) {
            scope.launch(block = {
                scroll.animateScrollTo((scroll.value + direction * scrollStep).coerceIn(0, scroll.maxValue))
            })
        }
    }
    UpdateDialogContainer(television = television, downloading = downloading, onClose = onClose, content = {
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
            modifier = Modifier.padding(DialogLayout.Inset).widthIn(max = if (television) DialogLayout.StandardWidth else DialogLayout.PhoneWidth).fillMaxWidth().heightIn(max = DialogLayout.MaxHeight)
                .dialogSurface(),
            verticalArrangement = Arrangement.spacedBy(DialogLayout.Spacing),
            content = {
                DialogTitle(text = stringResource(R.string.app_update_title))
                Text(text = message, color = Accent, fontSize = 15.sp)
                val notesSurface = if (notes.isNotBlank() && !downloading) {
                    Modifier.fillMaxWidth().background(BackdropBottom, DialogLayout.GroupShape).padding(12.dp)
                } else {
                    Modifier
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).then(notesSurface),
                    verticalArrangement = Arrangement.spacedBy(DialogLayout.Spacing),
                    content = {
                        if (notes.isNotBlank() && !downloading) {
                            UpdateNotesTitle(notesVersion)
                            Column(
                                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                                    .notesScrollIndicator(scroll).padding(end = 8.dp).verticalScroll(scroll),
                                verticalArrangement = Arrangement.spacedBy(8.dp), content = {
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
                                }
                            )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    content = {
                        TvAction(
                            onClick = onClose,
                            modifier = Modifier.focusRequester(closeFocus).focusProperties(scope = { canFocus = true }).tvDirections(
                                up = { scrollNotes(-1) },
                                down = { scrollNotes(1) },
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
                                    up = { scrollNotes(-1) },
                                    down = { scrollNotes(1) },
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
                                    up = { scrollNotes(-1) },
                                    down = { scrollNotes(1) },
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
                    }
                )
            }
        )
    })
}

@Composable
private fun UpdateNotesTitle(version: String): Unit {
    Text(
        text = stringResource(R.string.app_update_notes, version),
        color = Accent,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun UpdateDialogContainer(television: Boolean, downloading: Boolean, onClose: () -> Unit, content: @Composable () -> Unit): Unit {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = !downloading, usePlatformDefaultWidth = false), content = {
        Box(
            modifier = if (television) Modifier.width(DialogLayout.StandardWidth) else Modifier,
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    })
}

private fun Modifier.notesScrollIndicator(scroll: ScrollState): Modifier = drawWithContent(onDraw = {
    drawContent()
    val range = scroll.maxValue
    if (range > 0 && range < Int.MAX_VALUE && size.height > 0f) {
        val thumb = (size.height * size.height / (size.height + range))
            .coerceIn(minimumValue = minOf(18.dp.toPx(), size.height), maximumValue = size.height)
        val top = (size.height - thumb) * scroll.value / range
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - 2.dp.toPx() else 2.dp.toPx()
        drawLine(color = Muted.copy(alpha = 0.55f), start = Offset(x, top), end = Offset(x, top + thumb),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
})
