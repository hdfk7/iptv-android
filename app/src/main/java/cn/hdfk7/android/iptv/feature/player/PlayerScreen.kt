package cn.hdfk7.android.iptv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.core.ui.components.IptvDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.app.IptvApplication
import cn.hdfk7.android.iptv.core.ui.components.DialogTitle
import cn.hdfk7.android.iptv.core.ui.components.LocalTvRenderingCompatibility
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.components.TvUiSurface
import cn.hdfk7.android.iptv.core.ui.components.TvIconButton
import cn.hdfk7.android.iptv.core.ui.components.tvPanelNavigation
import cn.hdfk7.android.iptv.core.ui.theme.*
import cn.hdfk7.android.iptv.feature.proxy.ProxySettingsDialog
import cn.hdfk7.android.iptv.feature.update.AppUpdateDialog
import cn.hdfk7.android.iptv.feature.update.AppUpdateStore
import cn.hdfk7.android.iptv.feature.source.SourceManagerDialog
import cn.hdfk7.android.iptv.feature.source.SourceManagerViewModel

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, sourceViewModel: SourceManagerViewModel, television: Boolean, onDialogStateChanged: (Boolean) -> Unit): Unit {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourceUi by sourceViewModel.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as IptvApplication
    val resumeUpdate = remember(app, calculation = { AppUpdateStore(app).shouldResume() })
    var updateOpen by rememberSaveable(init = { mutableStateOf(resumeUpdate) })
    var otherSettingsPage by remember(calculation = { mutableStateOf<SettingsPage?>(null) })
    val settingsPage = if (updateOpen) SettingsPage.Update else otherSettingsPage
    fun showSettings(page: SettingsPage?): Unit {
        updateOpen = page == SettingsPage.Update
        otherSettingsPage = page.takeUnless(predicate = { target -> target == SettingsPage.Update })
    }
    var guideChannelId by remember(state.activeSourceId, calculation = { mutableStateOf<String?>(null) })
    val guideChannel = state.channels.firstOrNull(predicate = { channel -> channel.id == guideChannelId })
    var settingsMenuEntry by remember(calculation = {
        mutableStateOf(if (updateOpen) SettingsPage.Update else SettingsPage.Proxy)
    })
    val selectedRowFocus = remember(key1 = viewModel, calculation = { ChannelListFocus() })
    val dialogOpen = sourceUi.open || settingsPage != null || guideChannel != null
    LaunchedEffect(key1 = dialogOpen, block = {
        onDialogStateChanged(dialogOpen)
    })
    BackHandler(enabled = settingsPage != null, onBack = {
        showSettings(if (settingsPage == SettingsPage.Menu) null else SettingsPage.Menu)
    })
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        content = {
            PlaybackVideo(viewModel = viewModel)
            TvUiSurface(television = television, modifier = Modifier.fillMaxSize(), content = {
                CinemaPlayerScreen(
                    state = state,
                    viewModel = viewModel,
                    onSources = sourceViewModel::open,
                    onSettings = {
                        settingsMenuEntry = SettingsPage.Proxy
                        showSettings(SettingsPage.Menu)
                    },
                    onGuide = { id -> guideChannelId = id },
                    selectedRowFocus = selectedRowFocus,
                    dialogOpen = dialogOpen,
                    television = television
                )
            })
            SourceManagerDialog(viewModel = sourceViewModel)
            if (guideChannel != null) {
                ProgrammeScheduleDialog(
                    channelName = guideChannel.name,
                    programmes = state.programmeSchedules[guideChannel.id].orEmpty(),
                    television = television,
                    onClose = {
                        if (television) {
                            selectedRowFocus.restore(guideChannel.id)
                        }
                        guideChannelId = null
                    }
                )
            }
            when (settingsPage) {
                SettingsPage.Menu -> {
                    HomeSettingsDialog(
                        onClose = { showSettings(null) },
                        resumeEnabled = viewModel.resumeEnabled,
                        onToggleResume = viewModel::toggleResume,
                        fillVideoScreen = viewModel.fillVideoScreen,
                        onToggleFillVideoScreen = viewModel::toggleFillVideoScreen,
                        onToggleRenderingCompatibility = viewModel::toggleTvRenderingCompatibility,
                        onProxy = {
                            settingsMenuEntry = SettingsPage.Proxy
                            showSettings(SettingsPage.Proxy)
                        },
                        onUpdate = {
                            settingsMenuEntry = SettingsPage.Update
                            showSettings(SettingsPage.Update)
                        },
                        television = television,
                        entry = settingsMenuEntry
                    )
                }
                SettingsPage.Proxy -> {
                    ProxySettingsDialog(store = app.proxySettings, onClose = { showSettings(SettingsPage.Menu) }, onSaved = viewModel::applyProxy)
                }
                SettingsPage.Update -> {
                    AppUpdateDialog(onClose = { showSettings(SettingsPage.Menu) })
                }
                null -> Unit
            }
        }
    )
}

private enum class SettingsPage { Menu, Proxy, Update }

private enum class SettingsControl { Close, Proxy, Update, Startup, FillVideo, RenderingCompatibility }

@Composable
private fun HomeSettingsDialog(
    onClose: () -> Unit,
    onProxy: () -> Unit,
    onUpdate: () -> Unit,
    resumeEnabled: Boolean,
    onToggleResume: () -> Unit,
    fillVideoScreen: Boolean,
    onToggleFillVideoScreen: () -> Unit,
    onToggleRenderingCompatibility: () -> Unit,
    television: Boolean,
    entry: SettingsPage
): Unit {
    var selection by remember(entry, calculation = {
        mutableStateOf(if (entry == SettingsPage.Update) SettingsControl.Update else SettingsControl.Proxy)
    })
    SettingsMenuContainer(television = television, onClose = onClose, content = {
        val navigation = tvPanelNavigation(television, onMove = { _, vertical ->
            selection = SettingsControl.entries[(selection.ordinal + vertical).coerceIn(0, SettingsControl.entries.lastIndex)]
        }, onConfirm = {
            when (selection) {
                SettingsControl.Close -> onClose()
                SettingsControl.Proxy -> onProxy()
                SettingsControl.Update -> onUpdate()
                SettingsControl.Startup -> onToggleResume()
                SettingsControl.FillVideo -> onToggleFillVideoScreen()
                SettingsControl.RenderingCompatibility -> onToggleRenderingCompatibility()
            }
        })
        fun highlighted(control: SettingsControl): Boolean? {
            return if (television) selection == control else null
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(DialogSurface, RoundedCornerShape(22.dp))
                .then(navigation).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = {
                        DialogTitle(text = stringResource(R.string.settings_title), modifier = Modifier.weight(1f))
                        val closeHighlight = highlighted(SettingsControl.Close)
                        TvIconButton(
                            icon = R.drawable.ic_close,
                            description = stringResource(R.string.settings_close),
                            onClick = onClose,
                            modifier = Modifier.settingsSelection(closeHighlight),
                            focusHighlight = closeHighlight
                        )
                    }
                )
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = {
                        SettingsGroup(content = {
                            SettingsEntry(
                                icon = R.drawable.ic_settings,
                                title = stringResource(R.string.proxy_title),
                                onClick = onProxy,
                                highlighted = highlighted(SettingsControl.Proxy)
                            )
                            SettingsEntry(
                                icon = R.drawable.ic_update,
                                title = stringResource(R.string.app_update_title),
                                onClick = onUpdate,
                                highlighted = highlighted(SettingsControl.Update)
                            )
                        })
                        SettingsGroup(content = {
                            SettingsEntry(
                                icon = R.drawable.ic_play,
                                title = stringResource(R.string.startup_channel),
                                value = stringResource(if (resumeEnabled) R.string.startup_channel_last else R.string.startup_channel_first),
                                onClick = onToggleResume,
                                highlighted = highlighted(SettingsControl.Startup)
                            )
                            SettingsEntry(
                                icon = R.drawable.ic_fullscreen,
                                title = stringResource(R.string.fill_video_screen),
                                checked = fillVideoScreen,
                                onClick = onToggleFillVideoScreen,
                                highlighted = highlighted(SettingsControl.FillVideo)
                            )
                            if (television) {
                                SettingsEntry(
                                    icon = R.drawable.ic_settings,
                                    title = stringResource(R.string.tv_rendering_compatibility),
                                    checked = LocalTvRenderingCompatibility.current,
                                    onClick = onToggleRenderingCompatibility,
                                    highlighted = highlighted(SettingsControl.RenderingCompatibility)
                                )
                            }
                        })
                    }
                )
            }
        )
    })
}

@Composable
private fun SettingsMenuContainer(television: Boolean, onClose: () -> Unit, content: @Composable () -> Unit): Unit {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !television),
        content = {
            val viewport = if (television) {
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).windowInsetsPadding(WindowInsets.safeDrawing)
            } else {
                Modifier.fillMaxWidth()
            }
            Box(modifier = viewport.padding(horizontal = 16.dp), contentAlignment = Alignment.Center, content = {
                Box(modifier = Modifier.width(if (television) 440.dp else 380.dp), content = { content() })
            })
        }
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit): Unit {
    Column(
        modifier = Modifier.fillMaxWidth().background(BackdropBottom, RoundedCornerShape(14.dp)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

private fun Modifier.settingsSelection(highlighted: Boolean?, reportSelection: Boolean = true): Modifier {
    if (highlighted == null) {
        return this
    }
    val logicalFocus = focusProperties(scope = { canFocus = false })
    return if (reportSelection) logicalFocus.semantics(properties = { selected = highlighted }) else logicalFocus
}

@Composable
private fun SettingsEntry(
    icon: Int,
    title: String,
    onClick: () -> Unit,
    highlighted: Boolean?,
    value: String? = null,
    checked: Boolean? = null
): Unit {
    val reveal = remember(calculation = { BringIntoViewRequester() })
    LaunchedEffect(highlighted, block = {
        if (highlighted == true) {
            reveal.bringIntoView()
        }
    })
    val semantics = if (checked == null) {
        Modifier
    } else {
        Modifier.semantics(properties = {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        })
    }
    TvAction(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).bringIntoViewRequester(reveal)
            .settingsSelection(highlighted, reportSelection = checked == null).then(semantics),
        containerColor = Color.Transparent,
        focusHighlight = highlighted,
        showUnfocusedBorder = false,
        fitContentHeight = true,
        content = {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = Muted)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.width(8.dp))
            when {
                checked != null -> Switch(checked = checked, onCheckedChange = null)
                value != null -> Text(text = value, color = Muted, fontSize = 13.sp, maxLines = 1)
                else -> Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Muted,
                    modifier = Modifier.size(20.dp))
            }
        }
    )
}
