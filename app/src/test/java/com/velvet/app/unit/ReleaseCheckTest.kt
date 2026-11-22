package com.velvet.app.unit

import android.content.pm.PackageInstaller
import com.velvet.app.data.AppUpdateState
import com.velvet.app.data.AppUpdater
import com.velvet.app.data.GitHubRelease
import com.velvet.app.data.InstallStatusRecord
import com.velvet.app.data.ReleaseAsset
import com.velvet.app.data.ReleaseCheck
import com.velvet.app.data.UpdateInfo
import com.velvet.app.data.UpdatePayload
import com.velvet.app.diagnostics.DiagnosticsLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReleaseCheckTest {
    @Test
    fun comparesUpstreamBuildTagsNumerically() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"build-100","id":12}""", 99))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"build-51"}""", 51))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"build-9"}""", 51))
    }

    @Test
    fun supportsLegacyLatestTag() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"latest","name":"Velvet build 27"}""", 26))
    }

    @Test
    fun supportsSemanticVersioning() {
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.0"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v3.0","name":"Version 3.0"}""", 0, "2.0"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.0","name":"Version 2.0"}""", 0, "2.0"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v1.9","name":"Version 1.9"}""", 0, "2.0"))

        // Version 2.1 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.1"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.1"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.0","name":"Version 2.0"}""", 0, "2.1"))

        // Version 2.2 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.2"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.3","name":"Version 2.3"}""", 0, "2.2"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.1","name":"Version 2.1"}""", 0, "2.2"))

        // Version 2.3 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.3","name":"Version 2.3"}""", 0, "2.3"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.4","name":"Version 2.4"}""", 0, "2.3"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.2","name":"Version 2.2"}""", 0, "2.3"))

        // Version 2.5 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.5","name":"Version 2.5"}""", 0, "2.5"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.6","name":"Version 2.6"}""", 0, "2.5"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.3","name":"Version 2.3"}""", 0, "2.5"))

        // Version 2.6 active checks
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.6","name":"Version 2.6"}""", 0, "2.6"))
        assertTrue(ReleaseCheck.isNewer("""{"tag_name":"v2.7","name":"Version 2.7"}""", 0, "2.6"))
        assertFalse(ReleaseCheck.isNewer("""{"tag_name":"v2.5","name":"Version 2.5"}""", 0, "2.6"))
    }

    @Test(expected = IOException::class)
    fun unrecognizedReleaseIsAnError() {
        ReleaseCheck.isNewer("""{"tag_name":"unknown","name":"invalid"}""", 0)
    }

    // --- New W7 Bugfix Tests (Separated AppUpdateState & DependencyAdvisory, update.json & Installer Failure Reporting) ---

    @Test
    fun sameVersionCodeProducesNoUpdate() {
        val release = GitHubRelease(
            tag_name = "v2.6",
            assets = listOf(ReleaseAsset(name = "velvet.apk", browserDownloadUrl = "http://download/velvet.apk")),
        )
        val payload = UpdatePayload(
            versionName = "2.6",
            versionCode = 67,
            sha256 = "678ed062ef4f649d21183d9fd1838b75fbcb35f6c8a58159a67bf617bd6f7ee8",
        )
        val state = ReleaseCheck.evaluateAppUpdate(
            targetRelease = release,
            updatePayload = payload,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue("Equal versionCode must mean UpToDate", state is AppUpdateState.UpToDate)
        assertEquals(67, (state as AppUpdateState.UpToDate).currentVersionCode)
    }

    @Test
    fun higherVersionCodeProducesUpdateAvailable() {
        val release = GitHubRelease(
            tag_name = "v2.7",
            assets = listOf(ReleaseAsset(name = "velvet.apk", browserDownloadUrl = "http://download/velvet.apk")),
        )
        val payload = UpdatePayload(
            versionName = "2.7",
            versionCode = 68,
            sha256 = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
        )
        val state = ReleaseCheck.evaluateAppUpdate(
            targetRelease = release,
            updatePayload = payload,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue("Strictly higher versionCode must produce UpdateAvailable", state is AppUpdateState.UpdateAvailable)
        val update = state as AppUpdateState.UpdateAvailable
        assertEquals("2.7", update.latestVersionName)
        assertEquals(68, update.latestVersionCode)
        assertEquals("http://download/velvet.apk", update.downloadUrl)
    }

    @Test
    fun lowerVersionCodeProducesNoUpdate() {
        val release = GitHubRelease(
            tag_name = "v2.5",
            assets = listOf(ReleaseAsset(name = "velvet.apk", browserDownloadUrl = "http://download/velvet.apk")),
        )
        val payload = UpdatePayload(
            versionName = "2.5",
            versionCode = 66,
            sha256 = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
        )
        val state = ReleaseCheck.evaluateAppUpdate(
            targetRelease = release,
            updatePayload = payload,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue("Lower versionCode must produce UpToDate", state is AppUpdateState.UpToDate)
    }

    @Test
    fun dependencyNewerAndAppCurrentProducesAdvisoryOnlyAndBannerHidden() {
        val release = GitHubRelease(tag_name = "v2.6")
        val payload = UpdatePayload(versionName = "2.6", versionCode = 67, sha256 = "hash")
        val appState = ReleaseCheck.evaluateAppUpdate(
            targetRelease = release,
            updatePayload = payload,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        val advisories = ReleaseCheck.evaluateDependencyAdvisories(
            latestExtractorTag = "v0.27.1",
            bundledExtractorVersion = "v0.26.5",
        )
        val info = UpdateInfo(
            appUpdateState = appState,
            dependencyAdvisories = advisories,
            latestVersion = "2.6",
            latestVersionCode = 67,
            bundledVersion = "2.6",
            bundledVersionCode = 67,
            latestExtractorVersion = "v0.27.1",
            bundledExtractorVersion = "v0.26.5",
        )

        // Banner on About depends on hasAppUpdate (or hasUpdate alias)
        assertFalse("Banner must be hidden when app is current", info.hasAppUpdate)
        assertFalse("hasUpdate alias must be false when app is current", info.hasUpdate)
        assertEquals(1, info.dependencyAdvisories.size)
        val adv = info.dependencyAdvisories.first()
        assertTrue("NewPipeExtractor advisory must be flagged as newer", adv.isNewer)
        assertEquals(
            "NewPipeExtractor v0.27.1 is available upstream (bundled v0.26.5). Applying it requires a new app release.",
            adv.message,
        )
    }

    @Test
    fun missingUpdateJsonFallbackNeverShowsUpdateWhenTagEqualsVersionName() {
        val releaseSame = GitHubRelease(tag_name = "v2.6")
        val stateSame = ReleaseCheck.evaluateAppUpdate(
            targetRelease = releaseSame,
            updatePayload = null,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue("Tag name equal to installed versionName must fall back to UpToDate", stateSame is AppUpdateState.UpToDate)

        val releaseNewer = GitHubRelease(tag_name = "v2.7")
        val stateNewer = ReleaseCheck.evaluateAppUpdate(
            targetRelease = releaseNewer,
            updatePayload = null,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue("Newer tag name without update.json must fall back to UpdateAvailable", stateNewer is AppUpdateState.UpdateAvailable)
        assertEquals("2.7", (stateNewer as AppUpdateState.UpdateAvailable).latestVersionName)
    }

    @Test
    fun installerFailureReportingWhenVersionCodeUnchanged() {
        DiagnosticsLogger.clear()
        val abortedStatus = InstallStatusRecord(
            statusCode = PackageInstaller.STATUS_FAILURE_ABORTED,
            statusName = AppUpdater.statusToString(PackageInstaller.STATUS_FAILURE_ABORTED),
            message = "User cancelled the installation dialog",
        )
        val outcome = AppUpdater.evaluateInstallOutcome(
            installedVersionCode = 67,
            pendingVersionCode = 68,
            statusRecord = abortedStatus,
        )
        assertFalse("Outcome didApply must be false when installedCode < pendingCode", outcome.didApply)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, outcome.statusCode)
        assertEquals("STATUS_FAILURE_ABORTED (3)", outcome.statusName)
        assertEquals("Update did not apply (STATUS_FAILURE_ABORTED (3)): User cancelled the installation dialog", outcome.reportText)

        // Test conflict status
        val conflictStatus = InstallStatusRecord(
            statusCode = PackageInstaller.STATUS_FAILURE_CONFLICT,
            statusName = AppUpdater.statusToString(PackageInstaller.STATUS_FAILURE_CONFLICT),
            message = "Signatures do not match installed package",
        )
        val conflictOutcome = AppUpdater.evaluateInstallOutcome(
            installedVersionCode = 67,
            pendingVersionCode = 68,
            statusRecord = conflictStatus,
        )
        assertFalse(conflictOutcome.didApply)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, conflictOutcome.statusCode)
        assertEquals("STATUS_FAILURE_CONFLICT (5)", conflictOutcome.statusName)
        assertEquals("Update did not apply (STATUS_FAILURE_CONFLICT (5)): Signatures do not match installed package", conflictOutcome.reportText)

        // Also test recordInstallStatus logs to DiagnosticsLogger
        AppUpdater.recordInstallStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "Signatures do not match")
        val logged = DiagnosticsLogger.entries.value.any { it.message.contains("Update did not apply: STATUS_FAILURE_CONFLICT (5) - Signatures do not match") }
        assertTrue("Failure must be logged in DiagnosticsLogger", logged)
    }

    @Test
    fun appUpdateExtractsMatchingApkAssetFromUpdatePayload() {
        val release = GitHubRelease(
            tag_name = "v2.7",
            assets = listOf(
                ReleaseAsset(name = "other.apk", browserDownloadUrl = "http://download/other.apk"),
                ReleaseAsset(name = "velvet.apk", browserDownloadUrl = "http://download/velvet.apk"),
            ),
        )
        val payload = UpdatePayload(
            versionName = "2.7",
            versionCode = 68,
            sha256 = "hash",
            apkName = "velvet.apk",
        )
        val state = ReleaseCheck.evaluateAppUpdate(
            targetRelease = release,
            updatePayload = payload,
            installedVersionCode = 67,
            installedVersionName = "2.6",
        )
        assertTrue(state is AppUpdateState.UpdateAvailable)
        assertEquals("http://download/velvet.apk", (state as AppUpdateState.UpdateAvailable).downloadUrl)
    }
}
