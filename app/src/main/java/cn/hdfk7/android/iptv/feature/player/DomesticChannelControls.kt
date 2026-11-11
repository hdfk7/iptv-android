package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.model.Channel
import cn.hdfk7.android.iptv.core.model.DomesticChannelGroup
import cn.hdfk7.android.iptv.core.ui.components.TvAction

@Composable
internal fun domesticDisplayName(channel: Channel): String {
    val key = channel.key
    if (!key.startsWith("CCTV")) {
        return channel.name
    }
    if (key == "CCTV5+") {
        return stringResource(R.string.domestic_cctv5_plus)
    }
    val number = key.removePrefix("CCTV").toIntOrNull() ?: return channel.name
    val name = stringArrayResource(R.array.domestic_cctv_names).getOrNull(number - 1) ?: return channel.name
    return stringResource(R.string.domestic_cctv_name, key, name)
}

@Composable
internal fun ChannelGroupControls(
    groups: List<DomesticChannelGroup>,
    selected: DomesticChannelGroup,
    television: Boolean,
    onSelect: (DomesticChannelGroup) -> Unit
): Unit {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), content = {
        for (group in groups) {
            val label = when (group) {
                DomesticChannelGroup.ALL -> R.string.domestic_group_all
                DomesticChannelGroup.CCTV -> R.string.domestic_group_cctv
                DomesticChannelGroup.SATELLITE -> R.string.domestic_group_satellite
                DomesticChannelGroup.OTHER -> R.string.domestic_group_other
                DomesticChannelGroup.RECENT -> R.string.recent_channels
            }
            TvAction(
                onClick = { onSelect(group) },
                selected = group == selected,
                modifier = Modifier.weight(1f).height(if (television) 32.dp else 44.dp)
                    .focusProperties(scope = { canFocus = !television }),
                shape = RoundedCornerShape(8.dp),
                containerColor = Color.Transparent,
                contentPadding = PaddingValues(0.dp),
                showUnfocusedBorder = false,
                content = { Text(text = stringResource(label), fontSize = 12.sp, maxLines = 1) }
            )
        }
    })
}
