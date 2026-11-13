package cn.hdfk7.android.iptv.feature.update

import cn.hdfk7.android.iptv.R
import java.io.File

internal enum class InstallPermission {
    Required, Saving, Granted
}

internal sealed interface UpdateDialogState {
    val release: AppRelease?
        get() = null
    val message: Int?
        get() = null

    data object Checking : UpdateDialogState

    data class Available(
        val result: Result<AppRelease>,
        override val message: Int? = null,
        val recovery: UpdateAction = UpdateAction.Download
    ) : UpdateDialogState {
        override val release: AppRelease?
            get() = result.getOrNull()
    }

    data class Downloading(override val release: AppRelease) : UpdateDialogState

    data class Ready(
        override val release: AppRelease,
        val apk: File,
        val permission: InstallPermission,
        override val message: Int
    ) : UpdateDialogState

    data class Installing(val ready: Ready) : UpdateDialogState {
        override val release: AppRelease
            get() = ready.release
        override val message: Int
            get() = R.string.app_update_install_ready
    }

    fun primaryAction(hasUpdate: Boolean): UpdateAction? {
        return when (this) {
            Checking, is Downloading, is Installing -> null
            is Available -> if (hasUpdate) recovery else UpdateAction.Check
            is Ready -> if (permission == InstallPermission.Granted) UpdateAction.Install else UpdateAction.Authorize
        }
    }
}
