package cn.hdfk7.android.iptv.feature.update

import android.app.Activity
import cn.hdfk7.android.iptv.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class UpdateDialogStateTests {
    private val release = AppRelease(31, "1.3.6", "https://example.test/iptv.apk")

    @Test
    fun downloadingAndInstallingExposeNoPrimaryAction(): Unit {
        val ready = UpdateDialogState.Ready(release, File("iptv.apk"), InstallPermission.Granted, 1)
        assertNull(UpdateDialogState.Downloading(release).primaryAction(hasUpdate = true))
        assertNull(UpdateDialogState.Installing(ready).primaryAction(hasUpdate = true))
        assertEquals(UpdateAction.Install, ready.primaryAction(hasUpdate = true))
        assertEquals(UpdateAction.Authorize, ready.copy(permission = InstallPermission.Required).primaryAction(hasUpdate = true))
    }

    @Test
    fun restoredInstallationMatchesBothVersionFieldsAndDigest(): Unit {
        val target = release.copy(downloadUrl = "", sha256 = "first")
        val pending = release.copy(sha256 = "first")
        assertTrue(target.matchesInstallation(pending))
        assertFalse(target.matchesInstallation(pending.copy(sha256 = "second")))
        assertFalse(target.matchesInstallation(pending.copy(versionName = "1.3.7")))
        assertFalse(target.matchesInstallation(pending.copy(versionCode = 32)))
        assertTrue(release.copy(downloadUrl = "").matchesInstallation(release))
    }

    @Test
    fun installerResultKeepsCancellationAndFailureWithoutOverridingVerification(): Unit {
        assertEquals(R.string.app_update_install_cancelled,
            installationResultMessage(null, Activity.RESULT_CANCELED, 0))
        assertEquals(R.string.app_update_install_blocked,
            installationResultMessage(null, Activity.RESULT_FIRST_USER, -111))
        assertEquals(R.string.app_update_install_unconfirmed,
            installationResultMessage(null, Activity.RESULT_OK, 0))
        assertEquals(R.string.app_update_installed_checksum_mismatch,
            installationResultMessage(R.string.app_update_installed_checksum_mismatch, Activity.RESULT_OK, 0))
        assertEquals(R.string.app_update_install_complete,
            installationResultMessage(R.string.app_update_install_complete, Activity.RESULT_CANCELED, 0))
    }

    @Test
    fun manifestAndDownloadFailuresOfferTheirOwnRecovery(): Unit {
        val failedCheck = UpdateDialogState.Available(Result.failure(IllegalStateException()))
        val failedDownload = UpdateDialogState.Available(Result.success(release), message = 1)
        assertEquals(UpdateAction.Check, failedCheck.primaryAction(hasUpdate = false))
        assertEquals(UpdateAction.Download, failedDownload.primaryAction(hasUpdate = true))
        assertEquals(UpdateAction.Check, failedDownload.copy(recovery = UpdateAction.Check).primaryAction(hasUpdate = true))
    }
}
