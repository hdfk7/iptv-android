package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import cn.hdfk7.android.iptv.core.ui.theme.Accent

@Composable
internal fun DialogTitle(text: String, modifier: Modifier = Modifier): Unit {
    Text(text = text, modifier = modifier, color = Accent,
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}
