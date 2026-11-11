package cn.hdfk7.android.iptv.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.hdfk7.android.iptv.core.ui.components.TvAction
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.FocusCornerRadius
import cn.hdfk7.android.iptv.core.ui.theme.FocusStroke

@Composable
internal fun CinemaAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    tint: Color = Accent,
    interactive: Boolean = true,
    focusHighlight: Boolean? = null
): Unit {
    val labelContent: @Composable () -> Unit = {
        if (icon != null) {
            key(icon, block = {
                Icon(painter = painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            })
        } else {
            Text(text = label, color = Accent, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!interactive) {
        Box(modifier = modifier.height(46.dp).defaultMinSize(minWidth = 58.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center, content = { labelContent() })
        return
    }
    TvAction(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(FocusCornerRadius),
        containerColor = Color.Transparent,
        focusBorderWidth = FocusStroke,
        showUnfocusedBorder = false,
        focusHighlight = focusHighlight,
        contentPadding = PaddingValues(horizontal = 12.dp),
        content = { labelContent() }
    )
}
