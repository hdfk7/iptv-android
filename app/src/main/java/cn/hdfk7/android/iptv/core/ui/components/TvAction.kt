package cn.hdfk7.android.iptv.core.ui.components

import cn.hdfk7.android.iptv.core.ui.theme.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalTvActionFocused = staticCompositionLocalOf(defaultFactory = { false })
internal val TvIconButtonSize = 46.dp

@Composable
fun TvAction(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, shape: Shape = RoundedCornerShape(FocusCornerRadius), focusScale: Float = 1f, containerColor: Color = Panel, focusFill: Color = FocusFill, focusInk: Color = FocusInk, focusBorderWidth: Dp = FocusStroke, contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 6.dp), focusHighlight: Boolean? = null, showUnfocusedBorder: Boolean = true, fitContentHeight: Boolean = false, content: @Composable RowScope.() -> Unit) {
    var hasFocus by remember(calculation = { mutableStateOf(false) })
    val focused = focusHighlight ?: hasFocus
    val scale by animateFloatAsState(targetValue = if (focused && enabled) focusScale else 1f, animationSpec = tween(durationMillis = 140), label = "buttonFocusScale")
    val container = when {
        focused -> {
            focusFill
        }
        selected -> {
            SelectedPanel
        }
        else -> {
            containerColor
        }
    }
    val height = if (fitContentHeight) Modifier.defaultMinSize(minHeight = 46.dp) else Modifier.height(46.dp)
    val scaling = if (focusScale == 1f) {
        Modifier
    } else {
        Modifier.graphicsLayer(block = { scaleX = scale; scaleY = scale })
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(height).then(scaling).onFocusChanged(onFocusChanged = { focus -> hasFocus = focus.isFocused }),
        shape = shape,
        border = when {
            focused -> BorderStroke(focusBorderWidth, FocusBorder)
            showUnfocusedBorder -> BorderStroke(1.dp, Border.copy(alpha = 0.55f))
            else -> null
        },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = if (focused) focusInk else Accent),
        contentPadding = contentPadding,
        content = {
            val scope = this
            CompositionLocalProvider(LocalTvActionFocused provides focused, content = { content(scope) })
        }
    )
}

@Composable
fun TvIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.White, selected: Boolean = false, enabled: Boolean = true, focusHighlight: Boolean? = null) {
    TvAction(modifier = modifier.size(TvIconButtonSize), onClick = onClick, selected = selected, enabled = enabled, shape = CircleShape, focusScale = 1f, containerColor = ButtonPanel, focusBorderWidth = FocusStroke, contentPadding = PaddingValues(0.dp), focusHighlight = focusHighlight, content = {
        Icon(painter = painterResource(icon), contentDescription = description, tint = if (LocalTvActionFocused.current) FocusInk else tint, modifier = Modifier.size(20.dp))
    })
}
