package cn.hdfk7.android.iptv.feature.update

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun manifestAndDownloadFailuresOfferTheirOwnRecovery(): Unit {
        val failedCheck = UpdateDialogState.Available(Result.failure(IllegalStateException()))
        val failedDownload = UpdateDialogState.Available(Result.success(release), message = 1)
        assertEquals(UpdateAction.Check, failedCheck.primaryAction(hasUpdate = false))
        assertEquals(UpdateAction.Download, failedDownload.primaryAction(hasUpdate = true))
        assertEquals(UpdateAction.Check, failedDownload.copy(recovery = UpdateAction.Check).primaryAction(hasUpdate = true))
    }
}
