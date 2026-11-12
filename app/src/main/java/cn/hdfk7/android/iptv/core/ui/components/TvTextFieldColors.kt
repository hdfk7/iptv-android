package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import cn.hdfk7.android.iptv.core.ui.theme.FocusBorder

@Composable
internal fun tvTextFieldColors(highlighted: Boolean): TextFieldColors {
    return if (highlighted) {
        OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = FocusBorder,
            unfocusedLabelColor = FocusBorder
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }
}
