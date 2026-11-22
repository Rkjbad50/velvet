package com.velvet.app.data

import com.velvet.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val UPSTREAM_RELEASES_URL = "https://github.com/Rkjbad50/velvet/releases"
internal const val NEWPIPE_RELEASES_URL = "https://github.com/TeamNewPipe/NewPipeExtractor/releases"

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val html_url: String? = null,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

// Legacy alias for compatibility with existing tests and calls
typealias Release = GitHubRelease

@Serializable
data class UpdatePayload(
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val apkName: String = "velvet.apk",
)

data class DependencyAdvisory(
    val dependencyName: String,
    val bundledVersion: String,
    val upstreamVersion: String,
    val isNewer: Boolean,
    val message: String = "$dependencyName $upstreamVersion is available upstream (bundled $bundledVersion). Applying it requires a new app release.",
)

sealed interface AppUpdateState {
    data class UpdateAvailable(
        val latestVersionName: String,
        val latestVersionCode: Int,
        val downloadUrl: String,
        val expectedSha256: String?,
        val releaseNotes: String,
        val releaseTitle: String,
        val releaseUrl: String,
        val isPrerelease: Boolean,
    ) : AppUpdateState

    data class UpToDate(
        val currentVersionName: String,
        val currentVersionCode: Int,
    ) : AppUpdateState

    data class Error(
        val message: String,
        val isRateLimited: Boolean = false,
        val isOffline: Boolean = false,
    ) : AppUpdateState
}

enum class UpdateTrigger {
    NONE,
    APP,
    EXTRACTOR,
    BOTH,
}

data class UpdateInfo(
    val appUpdateState: AppUpdateState,
    val dependencyAdvisories: List<DependencyAdvisory> = emptyList(),
    val latestVersion: String,
    val latestVersionCode: Int = 0,
    val bundledVersion: String = BuildConfig.VERSION_NAME,
    val bundledVersionCode: Int = BuildConfig.VERSION_CODE,
    val latestExtractorVersion: String = BuildConfig.NEWPIPE_VERSION,
    val bundledExtractorVersion: String = BuildConfig.NEWPIPE_VERSION,
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = UPSTREAM_RELEASES_URL,
    val expectedSha256: String? = null,
    val releaseUrl: String = UPSTREAM_RELEASES_URL,
    val isPrerelease: Boolean = false,
    val isRateLimited: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val trigger: UpdateTrigger = if (appUpdateState is AppUpdateState.UpdateAvailable) UpdateTrigger.APP else UpdateTrigger.NONE,
) {
    /** True ONLY if there is a newer application release. Dependency advisories never affect this. */
    val hasAppUpdate: Boolean get() = appUpdateState is AppUpdateState.UpdateAvailable
    val hasUpdate: Boolean get() = hasAppUpdate
}

internal object ReleaseCheck {
    private const val APP_RELEASES_API = "https://api.github.com/repos/Rkjbad50/velvet/releases"
    private const val NEWPIPE_RELEASES_API = "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("""(?:SHA-?256|sha256)[:\s*`]+([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
    private val standaloneSha256Regex = Regex("""\b([a-fA-F0-9]{64})\b""")

    // 1-hour cache
    private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    private var cachedUpdateInfo: UpdateInfo? = null
    private var lastCheckTimestamp: Long = 0L
    private var lastIncludedPrereleases: Boolean = false

    /**
     * Evaluates whether an application update is available.
     * Compares installed versionCode with the release's versionCode from update.json.
     * An update is available ONLY if release versionCode is strictly higher.
     * Falls back to tag comparison ONLY if update.json is missing, and NEVER shows an update
     * when the tag name equals the installed versionName.
     */
    fun evaluateAppUpdate(
        targetRelease: GitHubRelease?,
        updatePayload: UpdatePayload?,
        installedVersionCode: Int,
        installedVersionName: String,
    ): AppUpdateState {
        if (targetRelease == null) {
            return AppUpdateState.UpToDate(installedVersionName, installedVersionCode)
        }

        // 1. If updatePayload is present (from update.json asset), compare versionCode strictly
        if (updatePayload != null) {
            val isNewer = updatePayload.versionCode > installedVersionCode
            return if (isNewer) {
                val apkAsset = targetRelease.assets.firstOrNull { it.name.equals(updatePayload.apkName, ignoreCase = true) }
                    ?: targetRelease.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                val downloadUrl = apkAsset?.browserDownloadUrl ?: targetRelease.html_url ?: UPSTREAM_RELEASES_URL
                AppUpdateState.UpdateAvailable(
                    latestVersionName = updatePayload.versionName,
                    latestVersionCode = updatePayload.versionCode,
                    downloadUrl = downloadUrl,
                    expectedSha256 = updatePayload.sha256,
                    releaseNotes = targetRelease.body.orEmpty(),
                    releaseTitle = targetRelease.name ?: targetRelease.tag_name,
                    releaseUrl = targetRelease.html_url ?: UPSTREAM_RELEASES_URL,
                    isPrerelease = targetRelease.prerelease,
                )
            } else {
                AppUpdateState.UpToDate(installedVersionName, installedVersionCode)
            }
        }

        // 2. Fall back to tag comparison ONLY if update.json is missing
        val cleanTag = targetRelease.tag_name.removePrefix("v").removePrefix("Version ").trim()
        val cleanInstalled = installedVersionName.removePrefix("v").removePrefix("Version ").trim()

        // Never show an update when the tag name equals the installed versionName
        if (cleanTag.equals(cleanInstalled, ignoreCase = true)) {
            return AppUpdateState.UpToDate(installedVersionName, installedVersionCode)
        }

        // Check if tag is newer via build number or SemVer
        val buildRegex = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
        val legacyTitleRegex = Regex(".*[Bb]uild ([0-9]+).*")
        val buildMatch = buildRegex.find(targetRelease.tag_name) ?: legacyTitleRegex.find(targetRelease.name.orEmpty())
        val remoteBuild = buildMatch?.groupValues?.get(1)?.toIntOrNull()

        val isNewer = if (remoteBuild != null && installedVersionCode > 0) {
            remoteBuild > installedVersionCode
        } else {
            SemVer.isNewer(targetRelease.tag_name, installedVersionName)
        }

        return if (isNewer) {
            val apkAsset = targetRelease.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val downloadUrl = apkAsset?.browserDownloadUrl ?: targetRelease.html_url ?: UPSTREAM_RELEASES_URL
            AppUpdateState.UpdateAvailable(
                latestVersionName = cleanTag,
                latestVersionCode = remoteBuild ?: 0,
                downloadUrl = downloadUrl,
                expectedSha256 = extractSha256(targetRelease.body.orEmpty()),
                releaseNotes = targetRelease.body.orEmpty(),
                releaseTitle = targetRelease.name ?: targetRelease.tag_name,
                releaseUrl = targetRelease.html_url ?: UPSTREAM_RELEASES_URL,
                isPrerelease = targetRelease.prerelease,
            )
        } else {
            AppUpdateState.UpToDate(installedVersionName, installedVersionCode)
        }
    }

    /**
     * Evaluates dependency advisories (e.g. NewPipeExtractor).
     * These are purely informational and NEVER trigger an app update banner or button.
     */
    fun evaluateDependencyAdvisories(
        latestExtractorTag: String?,
        bundledExtractorVersion: String = BuildConfig.NEWPIPE_VERSION,
    ): List<DependencyAdvisory> {
        if (latestExtractorTag.isNullOrBlank()) return emptyList()
        val isNewer = SemVer.isNewer(latestExtractorTag, bundledExtractorVersion)
        return listOf(
            DependencyAdvisory(
                dependencyName = "NewPipeExtractor",
                bundledVersion = bundledExtractorVersion,
                upstreamVersion = latestExtractorTag,
                isNewer = isNewer,
                message = "NewPipeExtractor $latestExtractorTag is available upstream (bundled $bundledExtractorVersion). Applying it requires a new app release.",
            )
        )
    }

    /**
     * Checks for updates against both VELVET GitHub repository and upstream TeamNewPipe/NewPipeExtractor.
     * Caches successful responses for 1 hour; handles rate limits (HTTP 403/429) and offline states gracefully.
     */
    fun checkUpdate(
        http: OkHttpClient,
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        includePrereleases: Boolean = false,
        forceRefresh: Boolean = false,
        clock: () -> Long = { System.currentTimeMillis() },
    ): UpdateInfo {
        val now = clock()
        val cached = cachedUpdateInfo
        if (!forceRefresh && cached != null && (now - lastCheckTimestamp < CACHE_DURATION_MS) && lastIncludedPrereleases == includePrereleases) {
            return cached
        }

        var rateLimited = false
        var offline = false
        var errorMsg: String? = null

        // 1. Fetch App Releases
        var appReleases: List<GitHubRelease> = emptyList()
        try {
            val req = Request.Builder()
                .url(APP_RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val call = http.newCall(req)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    rateLimited = true
                    errorMsg = "GitHub API rate limit reached. Cached information shown."
                } else if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    appReleases = json.decodeFromString<List<GitHubRelease>>(body)
                } else {
                    errorMsg = "HTTP error ${response.code} from GitHub Releases"
                }
            }
        } catch (e: IOException) {
            offline = true
            errorMsg = "Offline: Check your internet connection"
        } catch (e: Exception) {
            errorMsg = e.message ?: "Failed to check for updates"
        }

        // 2. Fetch NewPipeExtractor Latest Release
        var latestExtractorTag: String? = null
        if (!rateLimited && !offline) {
            try {
                val req = Request.Builder()
                    .url(NEWPIPE_RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val call = http.newCall(req)
                call.timeout().timeout(10, TimeUnit.SECONDS)
                call.execute().use { response ->
                    if (response.code == 403 || response.code == 429) {
                        rateLimited = true
                        errorMsg = "GitHub API rate limit reached."
                    } else if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val rel = json.decodeFromString<GitHubRelease>(body)
                        latestExtractorTag = rel.tag_name
                    }
                }
            } catch (e: IOException) {
                offline = true
                errorMsg = "Offline: Check your internet connection"
            } catch (_: Exception) {
            }
        }

        // If rate-limited or offline and we have a cached result, return the cached result annotated
        if ((rateLimited || offline) && cached != null) {
            return cached.copy(
                isRateLimited = rateLimited,
                isOffline = offline,
                errorMessage = errorMsg,
            )
        }

        // Filter app releases by pre-release setting
        val eligibleReleases = if (includePrereleases) {
            appReleases
        } else {
            appReleases.filter { !it.prerelease && !it.tag_name.contains("-alpha", true) && !it.tag_name.contains("-beta", true) && !it.tag_name.contains("-rc", true) }
        }

        val targetRelease = eligibleReleases.firstOrNull() ?: appReleases.firstOrNull()

        // 3. Fetch update.json metadata if published with release
        var updatePayload: UpdatePayload? = null
        val updateJsonAsset = targetRelease?.assets?.firstOrNull { it.name.equals("update.json", ignoreCase = true) }
        if (updateJsonAsset != null && !rateLimited && !offline) {
            try {
                val req = Request.Builder()
                    .url(updateJsonAsset.browserDownloadUrl)
                    .header("Accept", "application/json")
                    .build()
                val call = http.newCall(req)
                call.timeout().timeout(5, TimeUnit.SECONDS)
                call.execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        updatePayload = json.decodeFromString<UpdatePayload>(text)
                    }
                }
            } catch (_: Exception) {
                // Fall back to tag
            }
        }

        val installedCode = if (installedVersionCode > 0) installedVersionCode else BuildConfig.VERSION_CODE

        val appUpdateState = if ((rateLimited || offline) && cached == null) {
            AppUpdateState.Error(
                message = errorMsg ?: "Failed to check for updates",
                isRateLimited = rateLimited,
                isOffline = offline,
            )
        } else if (cached != null && (rateLimited || offline)) {
            cached.appUpdateState
        } else {
            evaluateAppUpdate(
                targetRelease = targetRelease,
                updatePayload = updatePayload,
                installedVersionCode = installedCode,
                installedVersionName = currentVersionName,
            )
        }

        val advisories = evaluateDependencyAdvisories(
            latestExtractorTag = latestExtractorTag,
            bundledExtractorVersion = BuildConfig.NEWPIPE_VERSION,
        )

        val trigger = when {
            appUpdateState is AppUpdateState.UpdateAvailable && advisories.any { it.isNewer } -> UpdateTrigger.BOTH
            appUpdateState is AppUpdateState.UpdateAvailable -> UpdateTrigger.APP
            advisories.any { it.isNewer } -> UpdateTrigger.EXTRACTOR
            else -> UpdateTrigger.NONE
        }

        val latestVersionName = when (appUpdateState) {
            is AppUpdateState.UpdateAvailable -> appUpdateState.latestVersionName
            is AppUpdateState.UpToDate -> appUpdateState.currentVersionName
            is AppUpdateState.Error -> currentVersionName
        }
        val latestVersionCode = when (appUpdateState) {
            is AppUpdateState.UpdateAvailable -> appUpdateState.latestVersionCode
            is AppUpdateState.UpToDate -> appUpdateState.currentVersionCode
            is AppUpdateState.Error -> installedCode
        }
        val downloadUrl = when (appUpdateState) {
            is AppUpdateState.UpdateAvailable -> appUpdateState.downloadUrl
            else -> targetRelease?.html_url ?: UPSTREAM_RELEASES_URL
        }
        val expectedSha256 = when (appUpdateState) {
            is AppUpdateState.UpdateAvailable -> appUpdateState.expectedSha256
            else -> extractSha256(targetRelease?.body.orEmpty())
        }

        val result = UpdateInfo(
            appUpdateState = appUpdateState,
            dependencyAdvisories = advisories,
            latestVersion = latestVersionName,
            latestVersionCode = latestVersionCode,
            bundledVersion = currentVersionName,
            bundledVersionCode = installedCode,
            latestExtractorVersion = latestExtractorTag ?: BuildConfig.NEWPIPE_VERSION,
            bundledExtractorVersion = BuildConfig.NEWPIPE_VERSION,
            releaseTitle = targetRelease?.name ?: targetRelease?.tag_name.orEmpty(),
            releaseNotes = targetRelease?.body.orEmpty(),
            downloadUrl = downloadUrl,
            expectedSha256 = expectedSha256,
            releaseUrl = targetRelease?.html_url ?: UPSTREAM_RELEASES_URL,
            isPrerelease = targetRelease?.prerelease ?: false,
            isRateLimited = rateLimited,
            isOffline = offline,
            errorMessage = errorMsg,
            trigger = trigger,
        )

        if (!rateLimited && !offline) {
            cachedUpdateInfo = result
            lastCheckTimestamp = now
            lastIncludedPrereleases = includePrereleases
        }

        return result
    }

    /** Extracts a 64-character hex SHA-256 string from release notes. */
    fun extractSha256(notes: String): String? {
        val match = sha256Regex.find(notes)
        if (match != null) return match.groupValues[1]
        // Fallback to standalone hex string if annotated
        return standaloneSha256Regex.find(notes)?.groupValues?.get(1)
    }

    fun hasNewerBuild(http: OkHttpClient, installedVersionCode: Int = BuildConfig.VERSION_CODE, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        return checkUpdate(http, installedVersionCode, currentVersionName).hasAppUpdate
    }

    /**
     * Legacy evaluation function preserved for existing unit tests.
     */
    internal fun isNewer(body: String, upstreamBuild: Int, currentVersionName: String = "2.6"): Boolean {
        val release = json.decodeFromString<GitHubRelease>(body)
        val tag = release.tag_name
        val name = release.name.orEmpty()

        val buildRegex = Regex("(?:v[0-9.]+-)?build-([0-9]+)")
        val legacyTitleRegex = Regex(".*[Bb]uild ([0-9]+).*")

        val buildMatch = buildRegex.find(tag) ?: legacyTitleRegex.find(name)
        val remoteBuild = buildMatch?.groupValues?.get(1)?.toIntOrNull()
        if (remoteBuild != null && upstreamBuild > 0) {
            return remoteBuild > upstreamBuild
        }

        val cleanTag = tag.removePrefix("v").removePrefix("Version ").trim()
        val cleanCurrent = currentVersionName.removePrefix("v").removePrefix("Version ").trim()
        if (cleanTag.equals(cleanCurrent, ignoreCase = true) && upstreamBuild <= 0) {
            return false
        }

        val semverRegex = Regex("""(?:v|Version\s*)?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
        val remoteMatch = semverRegex.find(tag) ?: semverRegex.find(name)
        val currentMatch = semverRegex.find(currentVersionName)
        if (remoteMatch != null && currentMatch != null) {
            val rMaj = remoteMatch.groupValues[1].toIntOrNull() ?: 0
            val rMin = remoteMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val rPatch = remoteMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val cMaj = currentMatch.groupValues[1].toIntOrNull() ?: 0
            val cMin = currentMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val cPatch = currentMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            if (rMaj != cMaj) return rMaj > cMaj
            if (rMin != cMin) return rMin > cMin
            return rPatch > cPatch
        }

        throw IOException("Unrecognized VELVET release")
    }

    fun clearCache() {
        cachedUpdateInfo = null
        lastCheckTimestamp = 0L
    }
}
