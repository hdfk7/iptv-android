package cn.hdfk7.android.iptv.feature.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppReleaseTests {
    @Test
    fun identicalVersionDoesNotOfferDownload(): Unit {
        assertFalse(AppRelease(30, "1.3.5", "").differsFrom(30L, "1.3.5"))
    }

    @Test
    fun eitherVersionFieldCanOfferDownload(): Unit {
        assertTrue(AppRelease(31, "1.3.5", "").differsFrom(30L, "1.3.5"))
        assertTrue(AppRelease(30, "1.3.6", "").differsFrom(30L, "1.3.5"))
        assertTrue(AppRelease(31, "1.3.6", "").differsFrom(30L, "1.3.5"))
    }

    @Test
    fun lowerVersionIsStillAnAvailableDownload(): Unit {
        assertTrue(AppRelease(29, "1.3.4", "").differsFrom(30L, "1.3.5"))
        assertTrue(AppRelease(30, "1.3.4", "").differsFrom(30L, "1.3.5"))
    }

    @Test
    fun higherInstalledCodeDoesNotImplyInstallationSucceeded(): Unit {
        assertTrue(AppRelease(29, "1.3.4", "").differsFrom(30L, "1.3.5"))
        assertTrue(AppRelease(30, "1.3.6", "").differsFrom(30L, "1.3.5"))
    }
}
