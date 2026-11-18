package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface

internal object DialogLayout {
    val Inset = 16.dp
    val Spacing = 12.dp
    val StandardWidth = 560.dp
    val PhoneWidth = 460.dp
    val MaxHeight = 480.dp
    val GroupShape = RoundedCornerShape(14.dp)
    val PhoneTarget = 48.dp
    val SurfaceShape = RoundedCornerShape(22.dp)
}

internal fun Modifier.dialogSurface(contentModifier: Modifier = Modifier): Modifier =
    background(DialogSurface, DialogLayout.SurfaceShape).then(contentModifier).padding(DialogLayout.Inset)
