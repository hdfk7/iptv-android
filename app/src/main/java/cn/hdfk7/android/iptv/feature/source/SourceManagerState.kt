package cn.hdfk7.android.iptv.feature.source

import cn.hdfk7.android.iptv.core.model.PlaylistSource

data class SourceDraft(val id: String? = null, val name: String = "", val address: String = "", val guideAddress: String = "")

data class SourceManagerState(
    val open: Boolean = false,
    val draft: SourceDraft? = null,
    val deleting: PlaylistSource? = null,
    val busy: Boolean = false,
    val error: Int? = null
)
