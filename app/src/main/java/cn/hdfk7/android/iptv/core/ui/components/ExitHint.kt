package cn.hdfk7.android.iptv.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.hdfk7.android.iptv.R
import cn.hdfk7.android.iptv.core.ui.theme.Accent
import cn.hdfk7.android.iptv.core.ui.theme.DialogSurface

@Composable
fun ExitHint(visible: Boolean): Unit {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter, content = {
        if (visible) {
            Surface(
                modifier = Modifier.padding(bottom = 36.dp).semantics(properties = {
                    liveRegion = LiveRegionMode.Polite
                }),
                shape = RoundedCornerShape(24.dp),
                color = DialogSurface,
                contentColor = Accent,
                content = {
                    Text(
                        text = stringResource(R.string.press_back_again),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            )
        }
    })
}
