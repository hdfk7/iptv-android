package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import cn.hdfk7.android.iptv.core.ui.theme.FocusBorder
import cn.hdfk7.android.iptv.core.ui.theme.FocusCornerRadius
import cn.hdfk7.android.iptv.core.ui.theme.FocusFill
import cn.hdfk7.android.iptv.core.ui.theme.FocusStroke
import cn.hdfk7.android.iptv.core.ui.theme.SelectedPanel

internal fun Modifier.tvFocusBackground(
    focused: () -> Boolean,
    selected: () -> Boolean,
    borderColor: Color = FocusBorder
): Modifier {
    // Read focus during drawing so lazy rows update without waiting for recomposition.
    return drawBehind(onDraw = {
        val hasFocus = focused()
        val fill = when {
            hasFocus -> FocusFill
            selected() -> SelectedPanel
            else -> Color.Transparent
        }
        val radius = FocusCornerRadius.toPx()
        drawRoundRect(color = fill, cornerRadius = CornerRadius(radius))
        if (hasFocus) {
            val strokeWidth = FocusStroke.toPx()
            val inset = strokeWidth / 2
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(radius - inset),
                style = Stroke(width = strokeWidth)
            )
        }
    })
}
