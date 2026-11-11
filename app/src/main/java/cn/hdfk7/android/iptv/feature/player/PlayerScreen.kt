package cn.hdfk7.android.iptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.app.IptvApplication
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.tvDirections
import cn.hdfk7.android.iptv.core.ui.components.requestWhenReady
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.feature.proxy.ProxySettingsDialog
import cn.hdfk7.android.iptv.feature.update.AppUpdateDialog
import cn.hdfk7.android.iptv.feature.source.SourceManagerDialog
import cn.hdfk7.android.iptv.feature.source.SourceManagerViewModel

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, sourceViewModel: SourceManagerViewModel, television: Boolean, onDialogStateChanged: (Boolean) -> Unit): Unit {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourceUi by sourceViewModel.state.collectAsStateWithLifecycle()
    var settingsPage by remember(calculation = { mutableStateOf<SettingsPage?>(null) })
    var guideChannelId by remember(state.activeSourceId, calculation = { mutableStateOf<String?>(null) })
    val guideChannel = state.channels.firstOrNull(predicate = { channel -> channel.id == guideChannelId })
    var settingsMenuEntry by remember(calculation = { mutableStateOf(SettingsPage.Proxy) })
    val app = LocalContext.current.applicationContext as IptvApplication
    val selectedRowFocus = remember(key1 = viewModel, calculation = { ChannelListFocus() })
    val dialogOpen = sourceUi.open || settingsPage != null || guideChannel != null
    LaunchedEffect(key1 = dialogOpen, block = {
        onDialogStateChanged(dialogOpen)
    })
    BackHandler(enabled = settingsPage != null, onBack = {
        settingsPage = if (settingsPage == SettingsPage.Menu) null else SettingsPage.Menu
    })
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        content = {
            PlaybackVideo(viewModel = viewModel)
            CinemaPlayerScreen(
                state = state,
                viewModel = viewModel,
                onSources = sourceViewModel::open,
                onSettings = {
                    settingsMenuEntry = SettingsPage.Proxy
                    settingsPage = SettingsPage.Menu
                },
                onGuide = { id -> guideChannelId = id },
                selectedRowFocus = selectedRowFocus,
                dialogOpen = dialogOpen,
                television = television
            )
            SourceManagerDialog(viewModel = sourceViewModel)
            if (guideChannel != null) {
                ProgrammeScheduleDialog(
                    channelName = guideChannel.name,
                    programmes = state.programmeSchedules[guideChannel.id].orEmpty(),
                    television = television,
                    onClose = { guideChannelId = null }
                )
            }
            when (settingsPage) {
                SettingsPage.Menu -> {
                    HomeSettingsDialog(
                        onClose = { settingsPage = null },
                        resumeEnabled = viewModel.resumeEnabled,
                        onToggleResume = viewModel::toggleResume,
                        onProxy = {
                            settingsMenuEntry = SettingsPage.Proxy
                            settingsPage = SettingsPage.Proxy
                        },
                        onUpdate = {
                            settingsMenuEntry = SettingsPage.Update
                            settingsPage = SettingsPage.Update
                        },
                        television = television,
                        entry = settingsMenuEntry
                    )
                }
                SettingsPage.Proxy -> {
                    ProxySettingsDialog(store = app.proxySettings, onClose = { settingsPage = SettingsPage.Menu }, onSaved = viewModel::applyProxy)
                }
                SettingsPage.Update -> {
                    AppUpdateDialog(onClose = { settingsPage = SettingsPage.Menu })
                }
                null -> Unit
            }
        }
    )
}

private enum class SettingsPage { Menu, Proxy, Update }

@Composable
private fun HomeSettingsDialog(onClose: () -> Unit, onProxy: () -> Unit, onUpdate: () -> Unit, resumeEnabled: Boolean, onToggleResume: () -> Unit, television: Boolean, entry: SettingsPage): Unit {
    val closeFocus = remember(calculation = { FocusRequester() })
    val proxyFocus = remember(calculation = { FocusRequester() })
    val updateFocus = remember(calculation = { FocusRequester() })
    val resumeFocus = remember(calculation = { FocusRequester() })
    SettingsMenuContainer(television = television, onClose = onClose, content = {
        val inputMode = LocalInputModeManager.current
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(television, windowFocused, entry, block = {
            if (television && windowFocused) {
                inputMode.requestInputMode(InputMode.Keyboard)
                withFrameNanos(onFrame = { })
                val initialFocus = if (entry == SettingsPage.Update) updateFocus else proxyFocus
                initialFocus.requestWhenReady()
            }
        })
        val closeNavigation = if (television) {
            Modifier.focusRequester(closeFocus).focusProperties(scope = { canFocus = true }).tvDirections(down = { proxyFocus.requestFocus() })
        } else {
            Modifier
        }
        val proxyNavigation = if (television) {
            Modifier.focusRequester(proxyFocus).focusProperties(scope = { canFocus = true }).tvDirections(
                up = { closeFocus.requestFocus() },
                down = { updateFocus.requestFocus() }
            )
        } else {
            Modifier
        }
        val updateNavigation = if (television) {
            Modifier.focusRequester(updateFocus).focusProperties(scope = { canFocus = true }).tvDirections(
                up = { proxyFocus.requestFocus() }, down = { resumeFocus.requestFocus() }
            )
        } else {
            Modifier
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(CinemaMenuSurface, RoundedCornerShape(22.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically, content = {
                    Text(text = stringResource(R.string.settings_title), color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TvIconButton(icon = R.drawable.ic_close, description = stringResource(R.string.settings_close), onClick = onClose, modifier = closeNavigation)
                })
                SettingsEntry(icon = R.drawable.ic_settings, title = stringResource(R.string.proxy_title), onClick = onProxy, modifier = proxyNavigation)
                SettingsEntry(icon = R.drawable.ic_update, title = stringResource(R.string.app_update_title), onClick = onUpdate, modifier = updateNavigation)
                val resumeNavigation = if (television) {
                    Modifier.focusRequester(resumeFocus).tvDirections(up = { updateFocus.requestFocus() })
                } else {
                    Modifier
                }
                SettingsEntry(
                    icon = R.drawable.ic_play,
                    title = stringResource(if (resumeEnabled) R.string.resume_enabled else R.string.resume_disabled),
                    onClick = onToggleResume,
                    modifier = resumeNavigation
                )
            }
        )
    })
}

@Composable
private fun SettingsMenuContainer(television: Boolean, onClose: () -> Unit, content: @Composable () -> Unit): Unit {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = !television, decorFitsSystemWindows = !television),
        content = {
            if (television) {
                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center, content = {
                    Box(modifier = Modifier.width(440.dp), content = { content() })
                })
            } else {
                content()
            }
        }
    )
}

@Composable
private fun SettingsEntry(icon: Int, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvAction(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        focusBorderWidth = 2.dp,
        showUnfocusedBorder = false,
        content = {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, modifier = Modifier.weight(1f))
        }
    )
}
