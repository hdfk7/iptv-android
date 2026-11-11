package cn.hdfk7.android.iptv.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFF3F6FA)
val Muted = Color(0xFFBFC7D3)
val Panel = Color(0xFF1D222A)
val ButtonPanel = Color(0xFF29313D)
val PanelAlt = Color(0xFF262D37)
val DialogSurface = Color(0xFF202630)
val SelectedPanel = Color(0xFF303D4F)
val Border = Color(0xFF3B4553)
val Divider = Color(0xFF536071)
val FocusCornerRadius = 8.dp
val FocusStroke = 2.dp
val FocusFill = Color(0xFF395673)
val FocusBorder = Color(0xFF8CACCF)
val FocusInk = Accent
val BackdropTop = Color(0xFF242B35)
val BackdropMiddle = Color(0xFF171C25)
val BackdropBottom = Color(0xFF10141B)
val CinemaMenuSurface = BackdropBottom.copy(alpha = 0.82f)
val Gold = Color(0xFFE5BF7B)
val Error = Color(0xFFFFA49B)

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FocusBorder,
            secondary = Gold,
            background = BackdropBottom,
            surface = Panel,
            surfaceVariant = PanelAlt,
            onPrimary = Color(0xFF18202B),
            onSurface = Accent,
            onSurfaceVariant = Muted,
            error = Error,
            outline = Border
        ),
        content = content
    )
}
