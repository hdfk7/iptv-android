package cn.hdfk7.android.iptv.feature.update

import cn.hdfk7.android.iptv.R

internal enum class UpdateAction {
    Check, Download, Authorize, Install
}

internal fun updateRecovery(message: Int): UpdateAction {
    return when (message) {
        R.string.app_update_archive_mismatch,
        R.string.app_update_download_checksum_mismatch,
        R.string.app_update_download_too_large,
        R.string.app_update_signature_mismatch,
        R.string.app_update_android_unsupported -> UpdateAction.Check
        else -> UpdateAction.Download
    }
}

internal fun installationFailureMessage(code: Int): Int {
    return when (code) {
        -4 -> R.string.app_update_install_storage
        -7, -8 -> R.string.app_update_signature_mismatch
        -12, -14 -> R.string.app_update_android_unsupported
        -16, -113 -> R.string.app_update_install_abi
        -25 -> R.string.app_update_install_downgrade
        -2, -3, in -109..-100 -> R.string.app_update_install_invalid
        -111 -> R.string.app_update_install_blocked
        else -> R.string.app_update_install_rejected
    }
}
