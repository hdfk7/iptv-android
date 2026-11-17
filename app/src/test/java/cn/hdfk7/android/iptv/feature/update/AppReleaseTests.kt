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
    fun sameVersionRevisionRequiresTwoDistinctDigests(): Unit {
        val target = AppRelease(32, "1.3.7", "", sha256 = "ABC")
        assertTrue(target.isUpdateFor(32L, "1.3.7", installedDigest = { "xyz" }))
        assertFalse(target.isUpdateFor(32L, "1.3.7", installedDigest = { "abc" }))
        assertFalse(target.copy(notes = "Edited notes").isUpdateFor(32L, "1.3.7", installedDigest = { "abc" }))
        assertFalse(target.isUpdateFor(32L, "1.3.7", installedDigest = { null }))
        assertFalse(target.copy(sha256 = null).isUpdateFor(32L, "1.3.7", installedDigest = { error("Unexpected digest read") }))
        assertTrue(target.isUpdateFor(31L, "1.3.6", installedDigest = { error("Unexpected digest read") }))
    }

    @Test
    fun downloadedPackageMustMatchTheCurrentRelease(): Unit {
        val current = AppRelease(33, "1.3.8", "https://example.test/current.apk", sha256 = "ABC")
        assertTrue(current.matchesDownload(current.copy(notes = "Updated notes", sha256 = "abc")))
        assertFalse(current.matchesDownload(current.copy(versionCode = 32, versionName = "1.3.7")))
        assertFalse(current.matchesDownload(current.copy(sha256 = "def")))
        assertFalse(current.matchesDownload(current.copy(sha256 = null)))
        val legacy = current.copy(sha256 = null)
        assertTrue(legacy.matchesDownload(legacy.copy(notes = "Updated notes")))
        assertFalse(legacy.matchesDownload(legacy.copy(downloadUrl = "https://example.test/older.apk")))
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
